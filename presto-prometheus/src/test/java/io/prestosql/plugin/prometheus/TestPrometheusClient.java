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
import com.google.common.collect.ImmutableSet;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.type.InternalTypeManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Set;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.MetadataUtil.METRIC_CODEC;
import static io.prestosql.plugin.prometheus.MetadataUtil.mapType;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestPrometheusClient
{
    private PrometheusHttpServer prometheusHttpServer;
    private static final Metadata METADATA = createTestMetadataManager();
    public static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA);

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
    public void testMetadata()
            throws Exception
    {
        URI metricsMetadata = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        PrometheusConfig config = new PrometheusConfig();
        config.setPrometheusURI(metricsMetadata);
        config.setQueryChunkSizeDuration("365d");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        assertEquals(client.getSchemaNames(), ImmutableSet.of("prometheus"));
        assertTrue(client.getTableNames("prometheus").contains("up"));
        PrometheusTable table = client.getTable("prometheus", "up");
        assertNotNull(table, "table is null");
        assertEquals(table.getName(), "up");
        assertEquals(table.getColumns(), ImmutableList.of(
                new PrometheusColumn("labels", mapType(createUnboundedVarcharType(), createUnboundedVarcharType())),
                new PrometheusColumn("timestamp", TimestampType.TIMESTAMP),
                new PrometheusColumn("value", DoubleType.DOUBLE)));
    }

    @Test
    public void testHandleErrorResponse()
            throws Exception
    {
        URI metricsMetadata = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics-error.json");
        PrometheusConfig config = new PrometheusConfig();
        config.setPrometheusURI(metricsMetadata);
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        Set<String> tableNames = client.getTableNames("prometheus");
        assertEquals(tableNames, ImmutableSet.of());
        PrometheusTable table = client.getTable("prometheus", "up");
        assertNull(table);
    }
}
