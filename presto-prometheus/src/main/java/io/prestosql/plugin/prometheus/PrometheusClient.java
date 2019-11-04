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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.inject.ConfigurationException;
import com.google.inject.spi.Message;
import io.airlift.json.JsonCodec;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TypeManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.http.client.utils.URIBuilder;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.prestosql.plugin.prometheus.PrometheusSplitManager.timeUnitsToSeconds;
import static io.prestosql.spi.StandardErrorCode.NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static io.prestosql.spi.type.TypeSignature.mapType;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class PrometheusClient
{
    // schema name is fixed to "default"
    private final Supplier<Map<String, Object>> tableSupplier;
    protected static PrometheusConnectorConfig config;
    protected static final String METRICS_ENDPOINT = "/api/v1/label/__name__/values";
    static List<PrometheusColumn> prometheusColumns;
    private Optional<TupleDomain<ColumnHandle>> pushedDown = Optional.empty();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .build();

    @Inject
    public PrometheusClient(PrometheusConnectorConfig config, JsonCodec<Map<String, Object>> metricCodec, TypeManager typeManager)
            throws URISyntaxException
    {
        requireNonNull(config, "config is null");
        requireNonNull(metricCodec, "metricCodec is null");
        requireNonNull(typeManager, "typeManager is null");

        long maxQueryRangeDuration = timeUnitsToSeconds(config.getMaxQueryRangeDuration());
        long queryChunkSizeDuration = timeUnitsToSeconds(config.getQueryChunkSizeDuration());
        if (maxQueryRangeDuration < queryChunkSizeDuration) {
            throw new ConfigurationException(ImmutableList.of(new Message("max-query-range-duration must be greater than query-chunk-size-duration")));
        }

        tableSupplier = Suppliers.memoizeWithExpiration(metricsSupplier(metricCodec, getPrometheusMetricsURI(config)),
                timeUnitsToSeconds(config.getCacheDuration()), TimeUnit.SECONDS);
        MapType varcharMapType = (MapType) typeManager.getType(mapType(VARCHAR.getTypeSignature(), VARCHAR.getTypeSignature()));
        this.config = config;
        this.prometheusColumns = ImmutableList.of(
                new PrometheusColumn("labels", varcharMapType),
                new PrometheusColumn("timestamp", TimestampType.TIMESTAMP),
                new PrometheusColumn("value", DoubleType.DOUBLE));
    }

    public Set<String> getSchemaNames()
    {
        return ImmutableSet.of("default");
    }

    private URI getPrometheusMetricsURI(PrometheusConnectorConfig config)
            throws URISyntaxException
    {
        // endpoint to retrieve metric names from Prometheus
        URI uri = config.getPrometheusURI();
        return new URIBuilder()
                .setScheme(uri.getScheme())
                .setHost(uri.getAuthority())
                .setPath(uri.getPath().concat(METRICS_ENDPOINT))
                .build();
    }

    public Set<String> getTableNames(String schema)
    {
        requireNonNull(schema, "schema is null");
        if (schema.equals("default")) {
            String status = (String) tableSupplier.get().get("status");
            if (status.equals("success")) {
                List<String> tableNames = (List<String>) tableSupplier.get().get("data");
                if (tableNames == null) {
                    return ImmutableSet.of();
                }
                return tableNames.stream().collect(Collectors.toSet());
            }
            else {
                //TODO this deals with success|error, there may be warnings to handle
                return ImmutableSet.of();
            }
        }
        return ImmutableSet.of();
    }

    public PrometheusTable getTable(String schema, String tableName)
    {
        requireNonNull(schema, "schema is null");
        requireNonNull(tableName, "tableName is null");
        if (schema.equals("default")) {
            List<String> tableNames = (List<String>) tableSupplier.get().get("data");
            if (tableNames == null) {
                return null;
            }
            if (!tableNames.contains(tableName)) {
                return null;
            }
            PrometheusTable table = new PrometheusTable(
                    tableName,
                    prometheusColumns);
            pushedDown.ifPresent(pushedDownPredicate -> table.setPredicate(Optional.of(pushedDownPredicate)));
            return table;
        }
        else {
            return null;
        }
    }

    private static Supplier<Map<String, Object>> metricsSupplier(final JsonCodec<Map<String, Object>> metricsCodec, final URI metadataUri)
    {
        return () -> {
            try {
                return lookupMetrics(metadataUri, metricsCodec);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Map<String, Object> lookupMetrics(URI metadataUri, JsonCodec<Map<String, Object>> metricsCodec)
            throws IOException
    {
        String json = getHttpResponse(metadataUri).string();
        Map<String, Object> metrics = metricsCodec.fromJson(json);

        return metrics;
    }

    protected void setPredicate(Optional<TupleDomain<ColumnHandle>> pushedDown)
    {
        this.pushedDown = pushedDown;
    }

    static ResponseBody getHttpResponse(URI uri)
            throws IOException
    {
        Request.Builder requestBuilder = new Request.Builder();
        getBearerAuthInfoFromFile().map(bearerToken ->
                requestBuilder.header("Authorization", "Bearer " + bearerToken));
        requestBuilder.url(uri.toURL());
        Request request = requestBuilder.build();
        Response response = httpClient.newCall(request).execute();
        if (response.isSuccessful()) {
            return response.body();
        }
        else {
            throw new PrestoException(REMOTE_TASK_ERROR, "Bad response " + response.code() + response.message());
        }
    }

    static Optional<String> getBearerAuthInfoFromFile()
    {
        String tokenFileName = config.getBearerTokenFile();
        if (tokenFileName != null) {
            try {
                File tokenFile = new File(tokenFileName);
                return Optional.of(Files.toString(tokenFile, UTF_8));
            }
            catch (Exception e) {
                throw new PrestoException(NOT_FOUND, "Failed to find/read file: " + tokenFileName, e);
            }
        }
        return Optional.empty();
    }
}
