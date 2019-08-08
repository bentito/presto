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

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import io.airlift.json.JsonCodec;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeSignature;

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

import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.transformValues;
import static com.google.common.collect.Maps.uniqueIndex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class PrometheusClient
{
    /**
     * SchemaName -> (TableName -> TableMetadata)
     */
    private final Supplier<Map<String, Map<String, PrometheusTable>>> schemas;
    private final Supplier<Map<String, Object>> tables;
    private static TypeManager typeManager;

    @Inject
    public PrometheusClient(PrometheusConfig config, JsonCodec<Map<String, List<PrometheusTable>>> catalogCodec,
            JsonCodec<Map<String, Object>> metricCodec, TypeManager typeManager)
    {
        requireNonNull(config, "config is null");
        requireNonNull(catalogCodec, "catalogCodec is null");
        requireNonNull(metricCodec, "metricCodec is null");
        requireNonNull(typeManager, "typeManager is null");

        schemas = Suppliers.memoize(schemasSupplier(catalogCodec, config.getMetadata()));
        tables = Suppliers.memoize(metricsSupplier(metricCodec, config.getMetricMetadata()));
        this.typeManager = typeManager;
    }

    public Set<String> getSchemaNames()
    {
        return schemas.get().keySet();
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
        else {
            Map<String, PrometheusTable> tables = schemas.get().get(schema);
            if (tables == null) {
                return ImmutableSet.of();
            }
            return tables.keySet();
        }
    }

    public PrometheusTable getTable(String schema, String tableName)
    {
        requireNonNull(schema, "schema is null");
        requireNonNull(tableName, "tableName is null");
        if (schema.equals("prometheus")) {
            try {
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
                                new PrometheusColumn("value", DoubleType.DOUBLE)),
                        ImmutableList.of(new URI(
                                "http://localhost:9090" +
                                        "/" +
                                        "api/v1/query?query=" +
                                        tableName +
                                        "[365d]")));
                return table;
            }
            catch (ClassCastException | URISyntaxException cce) {
                return null;
            }
        }
        else {
            Map<String, PrometheusTable> tables = schemas.get().get(schema);
            if (tables == null) {
                return null;
            }
            return tables.get(tableName);
        }
    }

    private static Supplier<Map<String, Map<String, PrometheusTable>>> schemasSupplier(final JsonCodec<Map<String, List<PrometheusTable>>> catalogCodec, final URI metadataUri)
    {
        return () -> {
            try {
                return lookupSchemas(metadataUri, catalogCodec);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Map<String, Map<String, PrometheusTable>> lookupSchemas(URI metadataUri, JsonCodec<Map<String, List<PrometheusTable>>> catalogCodec)
            throws IOException
    {
        URL result = metadataUri.toURL();
        String json = Resources.toString(result, UTF_8);
        Map<String, List<PrometheusTable>> catalog = catalogCodec.fromJson(json);

        return ImmutableMap.copyOf(transformValues(catalog, resolveAndIndexTables(metadataUri)));
    }

    private static Function<List<PrometheusTable>, Map<String, PrometheusTable>> resolveAndIndexTables(final URI metadataUri)
    {
        return tables -> {
            Iterable<PrometheusTable> resolvedTables = transform(tables, tableUriResolver(metadataUri));
            return ImmutableMap.copyOf(uniqueIndex(resolvedTables, PrometheusTable::getName));
        };
    }

    private static Function<PrometheusTable, PrometheusTable> tableUriResolver(final URI baseUri)
    {
        return table -> {
            List<URI> sources = ImmutableList.copyOf(transform(table.getSources(), baseUri::resolve));
            return new PrometheusTable(table.getName(),
                    ImmutableList.of(
                            new PrometheusColumn("labels", typeManager.getType(TypeSignature.parseTypeSignature("map(varchar, varchar)"))),
                            new PrometheusColumn("timestamp", TimestampType.TIMESTAMP),
                            new PrometheusColumn("value", DoubleType.DOUBLE)),
                    sources);
        };
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
