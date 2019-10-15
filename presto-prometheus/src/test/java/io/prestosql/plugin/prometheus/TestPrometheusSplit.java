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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.airlift.json.JsonCodec;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.type.InternalTypeManager;
import org.apache.http.NameValuePair;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.airlift.json.JsonCodec.jsonCodec;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.MetadataUtil.METRIC_CODEC;
import static io.prestosql.plugin.prometheus.PrometheusSplitManager.OFFSET_MILLIS;
import static io.prestosql.plugin.prometheus.PrometheusSplitManager.decimalSecondString;
import static io.prestosql.plugin.prometheus.PrometheusSplitManager.timeUnitsToMillis;
import static io.prestosql.plugin.prometheus.PrometheusSplitManager.timeUnitsToSeconds;
import static io.prestosql.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static java.time.LocalDateTime.ofInstant;
import static org.apache.http.client.utils.URLEncodedUtils.parse;
import static org.testng.Assert.assertEquals;

public class TestPrometheusSplit
{
    private PrometheusHttpServer prometheusHttpServer;
    private URI dataUri;
    private final PrometheusSplit split = new PrometheusSplit(URI.create("http://127.0.0.1/test.file"));
    private static final Metadata METADATA = createTestMetadataManager();
    public static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA);
    static final int NUMBER_MORE_THAN_EXPECTED_NUMBER_SPLITS = 100;

    @BeforeClass
    public void setUp()
            throws Exception
    {
        prometheusHttpServer = new PrometheusHttpServer();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        if (prometheusHttpServer != null) {
            prometheusHttpServer.stop();
        }
    }

    @Test
    public void testAddresses()
    {
        // http split with default port
        PrometheusSplit httpSplit = new PrometheusSplit(URI.create("http://prometheus.com/prometheus"));
        assertEquals(httpSplit.getAddresses(), ImmutableList.of(HostAddress.fromString("prometheus.com")));
        assertEquals(httpSplit.isRemotelyAccessible(), true);

        // http split with custom port
        httpSplit = new PrometheusSplit(URI.create("http://prometheus.com:8080/prometheus"));
        assertEquals(httpSplit.getAddresses(), ImmutableList.of(HostAddress.fromParts("prometheus.com", 8080)));
        assertEquals(httpSplit.isRemotelyAccessible(), true);

        // http split with default port
        PrometheusSplit httpsSplit = new PrometheusSplit(URI.create("https://prometheus.com/prometheus"));
        assertEquals(httpsSplit.getAddresses(), ImmutableList.of(HostAddress.fromString("prometheus.com")));
        assertEquals(httpsSplit.isRemotelyAccessible(), true);

        // http split with custom port
        httpsSplit = new PrometheusSplit(URI.create("https://prometheus.com:8443/prometheus"));
        assertEquals(httpsSplit.getAddresses(), ImmutableList.of(HostAddress.fromParts("prometheus.com", 8443)));
        assertEquals(httpsSplit.isRemotelyAccessible(), true);
    }

    @Test
    public void testJsonRoundTrip()
    {
        JsonCodec<PrometheusSplit> codec = jsonCodec(PrometheusSplit.class);
        String json = codec.toJson(split);
        PrometheusSplit copy = codec.fromJson(json);
        assertEquals(copy.getUri(), split.getUri());

        assertEquals(copy.getAddresses(), ImmutableList.of(HostAddress.fromString("127.0.0.1")));
        assertEquals(copy.isRemotelyAccessible(), true);
    }

    @Test
    public void testQueryWithTableNameNeedingURLEncodeInSplits()
            throws URISyntaxException
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        LocalDateTime now = java.time.LocalDateTime.of(2019, 10, 2, 7, 26, 56, 00);
        PrometheusTimeMachine.useFixedClockAt(now);

        dataUri = prometheusHttpServer.resolve("/prometheus-data/prom-metrics-non-standard-name.json");
        config.setPrometheusURI(dataUri);
        config.setQueryChunkSizeDuration("1d");
        config.setMaxQueryRangeDuration("21d");
        config.setCacheDuration("30s");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("default", "up now");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        PrometheusSplit split = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        String queryInSplit = split.getUri().getQuery();
        String timeShouldBe = decimalSecondString(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
                timeUnitsToMillis(config.getMaxQueryRangeDuration()) +
                timeUnitsToMillis(config.getQueryChunkSizeDuration()) -
                OFFSET_MILLIS * 20);
        assertEquals(queryInSplit,
                new URI("http://doesnotmatter:9090/api/v1/query?query=up+now[" + config.getQueryChunkSizeDuration() + "]" + "&time=" +
                        timeShouldBe).getQuery());
    }

    @Test
    public void testQueryDividedIntoSplitsFirstSplitHasRightTime()
            throws URISyntaxException
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        LocalDateTime now = java.time.LocalDateTime.of(2019, 10, 2, 7, 26, 56, 00);
        PrometheusTimeMachine.useFixedClockAt(now);

        config.setPrometheusURI(dataUri);
        config.setMaxQueryRangeDuration("21d");
        config.setQueryChunkSizeDuration("1d");
        config.setCacheDuration("30s");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("default", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        PrometheusSplit split = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        String queryInSplit = split.getUri().getQuery();
        String timeShouldBe = decimalSecondString(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
                timeUnitsToMillis(config.getMaxQueryRangeDuration()) +
                timeUnitsToMillis(config.getQueryChunkSizeDuration()) -
                OFFSET_MILLIS * 20);
        assertEquals(queryInSplit,
                new URI("http://doesnotmatter:9090/api/v1/query?query=up[" + config.getQueryChunkSizeDuration() + "]" + "&time=" +
                        timeShouldBe).getQuery());
    }

    @Test
    public void testQueryDividedIntoSplitsLastSplitHasRightTime()
            throws URISyntaxException
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        config.setPrometheusURI(dataUri);
        LocalDateTime now = java.time.LocalDateTime.of(2019, 10, 2, 7, 26, 56, 00);
        PrometheusTimeMachine.useFixedClockAt(now);

        config.setMaxQueryRangeDuration("21d");
        config.setQueryChunkSizeDuration("1d");
        config.setCacheDuration("30s");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("default", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splitsMaybe = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        List<ConnectorSplit> splits = splitsMaybe.getNextBatch(NOT_PARTITIONED, NUMBER_MORE_THAN_EXPECTED_NUMBER_SPLITS).getNow(null).getSplits();
        int lastSplitIndex = splits.size() - 1;
        PrometheusSplit lastSplit = (PrometheusSplit) splits.get(lastSplitIndex);
        String queryInSplit = lastSplit.getUri().getQuery();
        String timeShouldBe = decimalSecondString(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        URI uriAsFormed = new URI("http://doesnotmatter:9090/api/v1/query?query=up[" +
                config.getQueryChunkSizeDuration() + "]" +
                "&time=" + timeShouldBe);
        assertEquals(queryInSplit, uriAsFormed.getQuery());
    }

    @Test
    public void testQueryDividedIntoSplitsShouldHaveCorrectSpacingBetweenTimes()
            throws URISyntaxException
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        config.setPrometheusURI(dataUri);
        config.setMaxQueryRangeDuration("21d");
        config.setQueryChunkSizeDuration("1d");
        config.setCacheDuration("30s");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("default", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        PrometheusSplit split1 = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        Map<String, String> paramsMap1 = parse(split1.getUri(), StandardCharsets.UTF_8).stream().collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
        PrometheusSplit split2 = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        Map<String, String> paramsMap2 = parse(split2.getUri(), StandardCharsets.UTF_8).stream().collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
        assertEquals(paramsMap1.get("query"), "up[1d]");
        assertEquals(paramsMap2.get("query"), "up[1d]");
        long diff = Double.valueOf(paramsMap2.get("time")).longValue() - Double.valueOf(paramsMap1.get("time")).longValue();
        assertEquals(timeUnitsToSeconds(config.getQueryChunkSizeDuration()), diff);
    }

    @Test
    public void testCorrectNumberOfSplitsCreated()
            throws URISyntaxException
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        config.setPrometheusURI(dataUri);
        config.setMaxQueryRangeDuration("21d");
        config.setQueryChunkSizeDuration("1d");
        config.setCacheDuration("30s");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("default", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        int numSplits = splits.getNextBatch(NOT_PARTITIONED, NUMBER_MORE_THAN_EXPECTED_NUMBER_SPLITS).getNow(null).getSplits().size();
        assertEquals(numSplits, timeUnitsToSeconds(config.getMaxQueryRangeDuration()) / timeUnitsToSeconds(config.getQueryChunkSizeDuration()));
    }

    @Test
    public void testSplitTimesCorrect()
            throws Exception
    {
        String maxQueryRangeDurationStr = "3d";
        String queryChunkSizeDurationStr = "1d";
        LocalDateTime now = ofInstant(Instant.ofEpochMilli(1000000000L), ZoneId.systemDefault());
        PrometheusTimeMachine.useFixedClockAt(now);

        List<String> splitTimes = PrometheusSplitManager.generateTimesForSplits(now, maxQueryRangeDurationStr, queryChunkSizeDurationStr);
        List<String> expectedSplitTimes = ImmutableList.of(
                "827199.998", "913599.999", "1000000");
        assertEquals(splitTimes, expectedSplitTimes);
    }

    @Test
    public void testSplitTimesCorrectNonModuloZeroDurationToChunk()
            throws Exception
    {
        String maxQueryRangeDurationStr = "3d";
        String queryChunkSizeDurationStr = "2d";
        LocalDateTime now = ofInstant(Instant.ofEpochMilli(1000000000L), ZoneId.systemDefault());
        PrometheusTimeMachine.useFixedClockAt(now);

        List<String> splitTimes = PrometheusSplitManager.generateTimesForSplits(now, maxQueryRangeDurationStr, queryChunkSizeDurationStr);
        List<String> expectedSplitTimes = ImmutableList.of(
                "827199.999", "1000000");
        assertEquals(splitTimes, expectedSplitTimes);
    }

    @Test
    public void testSplitTimesCorrectVersusMock()
            throws Exception
    {
        String maxQueryRangeDurationStr = "120s";
        String queryChunkSizeDurationStr = "30s";
        LocalDateTime now = ofInstant(Instant.ofEpochMilli(1568638172000L), ZoneId.systemDefault());
        PrometheusTimeMachine.useFixedClockAt(now);

        List<String> splitTimes = PrometheusSplitManager.generateTimesForSplits(now, maxQueryRangeDurationStr, queryChunkSizeDurationStr);
        List<String> promTimesReturned = mockPrometheusResponseToChunkedQueries(queryChunkSizeDurationStr, splitTimes);
        assertEquals(promTimesReturned, convertMockTimesToStrings(promTimeValuesMock));
    }

    @Test
    public void testSplitTimesAreTimesNearBoundaryNotMissing()
            throws Exception
    {
        String maxQueryRangeDurationStr = "120s";
        String queryChunkSizeDurationStr = "30s";
        LocalDateTime now = ofInstant(Instant.ofEpochMilli(1568638171999L), ZoneId.systemDefault());
        PrometheusTimeMachine.useFixedClockAt(now);

        List<String> splitTimes = PrometheusSplitManager.generateTimesForSplits(now, maxQueryRangeDurationStr, queryChunkSizeDurationStr);
        List<String> promTimesReturned = mockPrometheusResponseToChunkedQueries(queryChunkSizeDurationStr, splitTimes);
        System.out.println(promTimesReturned);
        assertEquals(promTimesReturned, convertMockTimesToStrings(promTimeValuesMock));
    }

    @Test
    public void testMockPrometheusResponseShouldBeCorrectWhenUpperBoundaryAlignsWithData()
            throws Exception
    {
        List<Double> expectedResponse = ImmutableList.of(1568638142., 1568638157., 1568638171.999);
        assertEquals(mockPrometheusResponseToQuery("30s", "1568638171.999"), expectedResponse);
    }

    @Test
    public void testMockPrometheusResponseShouldBeCorrectWhenLowerBoundaryAlignsWithData()
            throws Exception
    {
        List<Double> expectedResponse = ImmutableList.of(1568638142., 1568638157., 1568638171.999);
        assertEquals(mockPrometheusResponseToQuery("30s", "1568638172."), expectedResponse);
    }

    @Test
    public void testMockPrometheusResponseShouldBeCorrectWhenLowerBoundaryLaterThanData()
            throws Exception
    {
        List<Double> expectedResponse = ImmutableList.of(1568638157., 1568638171.999);
        assertEquals(mockPrometheusResponseToQuery("30s", "1568638172.001"), expectedResponse);
    }

    @Test
    public void testMockPrometheusResponseWithSeveralChunksShouldBeCorrect()
            throws Exception
    {
        List<String> expectedResponse = ImmutableList.of("1568638112", "1568638126.997", "1568638142", "1568638157", "1568638171.999");
        List<String> splitTimes = ImmutableList.of("1568638141.999", "1568638172.");
        assertEquals(mockPrometheusResponseToChunkedQueries("30s", splitTimes), expectedResponse);
    }

    /**
     * mock Prometheus chunked query responses (time values only)
     *
     * @param splitTimes         the end times that would be used for each Prometheus instant query
     * @param queryChunkDuration the duration value that would be used for each query, `30s` for instance
     * @return the values from the Prometheus data that would be return by all the chunked queries
     */
    private List<String> mockPrometheusResponseToChunkedQueries(String queryChunkDuration, List<String> splitTimes)
    {
        return Lists.reverse(splitTimes).stream()
                .map(endTime -> mockPrometheusResponseToQuery(queryChunkDuration, endTime))
                .flatMap(x -> x.stream())
                .sorted()
                .map(doubleValue -> doubleToPlainString(doubleValue))
                .collect(Collectors.toList());
    }

    /**
     * mock Prometheus instant query
     *
     * @param queryChunkDuration
     * @param endTime
     * @return
     */
    private List<Double> mockPrometheusResponseToQuery(String queryChunkDuration, String endTime)
    {
        return promTimeValuesMock.stream()
                .filter(promTimeValue -> ((Double.parseDouble(endTime) - new Double(timeUnitsToSeconds(queryChunkDuration))) <= promTimeValue) &&
                        (promTimeValue <= Double.parseDouble(endTime)))
                .collect(Collectors.toList());
    }

    /**
     * Convert list of Double to list of String and avoid scientific notation
     */
    List<String> convertMockTimesToStrings(List<Double> times)
    {
        return times.stream().map(time -> doubleToPlainString(time)).collect(Collectors.toList());
    }

    /**
     * Convert Double to String and avoid scientific notation
     */
    String doubleToPlainString(Double aDouble)
    {
        return new BigDecimal(aDouble.toString()).stripTrailingZeros().toPlainString();
    }

    /**
     * Prometheus mock data
     * The array below represents to a response to from real data:
     * $ curl "http://127.0.0.1:9090/api/v1/query?query=up[120s]&time=1568638172"
     * Just the time items from the "values" section of the response
     */
    static final ImmutableList<Double> promTimeValuesMock = new ImmutableList.Builder<Double>()
            .add(1568638066.999)
            .add(1568638081.996)
            .add(1568638097.)
            .add(1568638112.)
            .add(1568638126.997)
            .add(1568638142.)
            .add(1568638157.)
            .add(1568638171.999)
            .build();
}
