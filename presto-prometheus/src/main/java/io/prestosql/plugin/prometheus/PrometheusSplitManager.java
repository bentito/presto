/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.prometheus;

import com.google.common.collect.Lists;
import io.airlift.units.Duration;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.connector.TableNotFoundException;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;

import javax.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

public class PrometheusSplitManager
        implements ConnectorSplitManager
{
    static final long OFFSET_MILLIS = 1L;
    private final PrometheusClient prometheusClient;

    @Inject
    public PrometheusSplitManager(PrometheusClient prometheusClient)
    {
        this.prometheusClient = requireNonNull(prometheusClient, "client is null");
        requireNonNull(prometheusClient.config.getQueryChunkSizeDuration(), "query-chunk-size-duration is null");
        requireNonNull(prometheusClient.config.getMaxQueryRangeDuration(), "max-query-range-duration is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle connectorTableHandle,
            SplitSchedulingStrategy splitSchedulingStrategy)
    {
        PrometheusTableHandle tableHandle = (PrometheusTableHandle) connectorTableHandle;
        PrometheusTable table = prometheusClient.getTable(tableHandle.getSchemaName(), tableHandle.getTableName());

        // this can happen if table is removed during a query
        if (table == null) {
            throw new TableNotFoundException(tableHandle.toSchemaTableName());
        }
        List<ConnectorSplit> splits = new ArrayList<>();
        generateTimesForSplits(
                PrometheusTimeMachine.now(),
                prometheusClient.config.getMaxQueryRangeDuration(),
                prometheusClient.config.getQueryChunkSizeDuration())
                .forEach(time -> {
                    try {
                        splits.add(new PrometheusSplit(buildQuery(prometheusClient.config.getPrometheusURI(),
                                time,
                                table.getName(),
                                prometheusClient.config.getQueryChunkSizeDuration())));
                    }
                    catch (URISyntaxException e) {
                        throw new UnsupportedOperationException(e.getMessage());
                    }
                });
        return new FixedSplitSource(splits);
    }

    // URIBuilder handles URI encode
    private URI buildQuery(URI baseURI, String time, String metricName, String queryChunkSizeDuration)
            throws URISyntaxException
    {
        List<NameValuePair> nameValuePairs = new ArrayList<>(2);
        nameValuePairs.add(new BasicNameValuePair("query",
                metricName + "[" + queryChunkSizeDuration + "]"));
        nameValuePairs.add(new BasicNameValuePair("time",
                time));
        return new URIBuilder(baseURI.toString())
                .setPath("api/v1/query")
                .setParameters(nameValuePairs).build();
    }

    /**
     * Utility method to get the end times in decimal seconds that divide up the query into chunks
     * The times will be used in queries to Prometheus like: `http://localhost:9090/api/v1/query?query=up[21d]&time=1568229904.000"`
     * ** NOTE: Prometheus instant query wants the duration and end time specified.
     *
     * @param now                       a LocalDateTime likely from PrometheusTimeMachine class for testability
     * @param maxQueryRangeDurationStr  a String like `21d`
     * @param queryChunkSizeDurationStr a String like `1d`
     * @return list of end times as decimal epoch seconds, like ["1568053244.143", "1568926595.321"]
     */
    protected static List<String> generateTimesForSplits(LocalDateTime now, String maxQueryRangeDurationStr, String queryChunkSizeDurationStr)
    {
        java.time.Duration maxQueryRangeDuration = java.time.Duration.ofMillis(Duration.valueOf(maxQueryRangeDurationStr).toMillis());
        java.time.Duration queryChunkSizeDuration = java.time.Duration.ofMillis(Duration.valueOf(queryChunkSizeDurationStr).toMillis());
        if (maxQueryRangeDuration.isNegative()) {
            throw new IllegalArgumentException("max-query-range-duration may not be negative");
        }
        if (queryChunkSizeDuration.isNegative()) {
            throw new IllegalArgumentException("query-chunk-size-duration may not be negative");
        }
        if (queryChunkSizeDuration.isZero()) {
            throw new IllegalArgumentException("query-chunk-size-duration may not be zero");
        }
        BigDecimal maxQueryRangeDecimal = BigDecimal.valueOf(maxQueryRangeDuration.getSeconds()).add(BigDecimal.valueOf(maxQueryRangeDuration.getNano(), 9));
        BigDecimal queryChunkSizeDecimal = BigDecimal.valueOf(queryChunkSizeDuration.getSeconds()).add(BigDecimal.valueOf(queryChunkSizeDuration.getNano(), 9));

        int numChunks = maxQueryRangeDecimal.divide(queryChunkSizeDecimal).setScale(0, RoundingMode.UP).intValue();

        return Lists.reverse(IntStream.range(0, numChunks).mapToObj(n -> {
            long endTime = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
                    n * queryChunkSizeDuration.toMillis() - n * OFFSET_MILLIS;
            return endTime;
        }).map(endTimeMilli -> decimalSecondString(endTimeMilli)).collect(Collectors.toList()));
    }

    static String decimalSecondString(long millis)
    {
        return new BigDecimal(Long.toString(millis)).divide(new BigDecimal(1000L)).toPlainString();
    }

    static long timeUnitsToSeconds(String timeWithUnits)
    {
        return TimeUnit.MILLISECONDS.toSeconds(Duration.valueOf(timeWithUnits).toMillis());
    }

    static long timeUnitsToMillis(String timeWithUnits)
    {
        return Duration.valueOf(timeWithUnits).toMillis();
    }
}
