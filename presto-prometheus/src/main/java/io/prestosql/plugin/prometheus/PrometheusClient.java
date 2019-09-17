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
import com.google.common.io.Resources;
import io.airlift.json.JsonCodec;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeSignature;
import org.apache.http.client.utils.URIBuilder;

import javax.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class PrometheusClient
{
    // schema name is fixed to "prometheus"
    private final Supplier<Map<String, Object>> tables;
    protected final PrometheusConfig config;
    private static TypeManager typeManager;
    protected static final String METRICS_ENDPOINT = "/api/v1/label/__name__/values";

    @Inject
    public PrometheusClient(PrometheusConfig config, JsonCodec<Map<String, Object>> metricCodec, TypeManager typeManager)
            throws URISyntaxException
    {
        requireNonNull(config, "config is null");
        requireNonNull(metricCodec, "metricCodec is null");
        requireNonNull(typeManager, "typeManager is null");

        tables = Suppliers.memoize(metricsSupplier(metricCodec, getPrometheusMetricsURI(config)));
        this.config = config;
        this.typeManager = typeManager;
    }

    public Set<String> getSchemaNames()
    {
        return ImmutableSet.of("prometheus");
    }

    private URI getPrometheusMetricsURI(PrometheusConfig config)
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
        if (schema.equals("prometheus")) {
            String status = (String) tables.get().get("status");
            if (status.equals("success")) {
                List<String> tableNames = (List<String>) tables.get().get("data");
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
        if (schema.equals("prometheus")) {
            List<String> tableNames = (List<String>) tables.get().get("data");
            if (tableNames == null) {
                return null;
            }
            if (!tableNames.contains(tableName)) {
                return null;
            }
            PrometheusTable table = new PrometheusTable(
                    tableName,
                    ImmutableList.of(
                            new PrometheusColumn("labels", typeManager.getType(TypeSignature.parseTypeSignature("map(varchar, varchar)"))),
                            new PrometheusColumn("timestamp", TimestampType.TIMESTAMP),
                            new PrometheusColumn("value", DoubleType.DOUBLE)));
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
        URL result = metadataUri.toURL();
        String json = Resources.toString(result, UTF_8);
        Map<String, Object> metrics = metricsCodec.fromJson(json);

        return metrics;
    }
}
