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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.type.InternalTypeManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Optional;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.MetadataUtil.METRIC_CODEC;
import static io.prestosql.plugin.prometheus.MetadataUtil.mapType;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestPrometheusMetadata
{
    private PrometheusHttpServer prometheusHttpServer;
    private URI dataUri;
    private static final PrometheusTableHandle RUNTIME_DETERMINED_TABLE_HANDLE = new PrometheusTableHandle("prometheus", "up");
    private PrometheusMetadata metadata;
    private static final Metadata METADATA = createTestMetadataManager();
    public static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA);

    @BeforeClass
    public void setUp()
            throws Exception
    {
        prometheusHttpServer = new PrometheusHttpServer();
        PrometheusConfig config = new PrometheusConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        config.setPrometheusURI(dataUri);
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        metadata = new PrometheusMetadata(client);
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
    public void testListSchemaNames()
    {
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableSet.of("prometheus"));
    }

    @Test
    public void testGetTableHandle()
    {
        assertEquals(metadata.getTableHandle(SESSION, new SchemaTableName("prometheus", "up")), RUNTIME_DETERMINED_TABLE_HANDLE);
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("prometheus", "unknown")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "numbers")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "unknown")));
    }

    @Test
    public void testGetColumnHandles()
    {
        // known table
        assertEquals(metadata.getColumnHandles(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE), ImmutableMap.of(
                "labels", new PrometheusColumnHandle("labels", createUnboundedVarcharType(), 0),
                "value", new PrometheusColumnHandle("value", DOUBLE, 1),
                "timestamp", new PrometheusColumnHandle("timestamp", TimestampType.TIMESTAMP, 2)));

        // unknown table
        try {
            metadata.getColumnHandles(SESSION, new PrometheusTableHandle("unknown", "unknown"));
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException expected) {
        }
        try {
            metadata.getColumnHandles(SESSION, new PrometheusTableHandle("prometheus", "unknown"));
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException expected) {
        }
    }

    @Test
    public void getTableMetadata()
    {
        // known table
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE);
        assertEquals(tableMetadata.getTable(), new SchemaTableName("prometheus", "up"));
        assertEquals(tableMetadata.getColumns(), ImmutableList.of(
                new ColumnMetadata("labels", mapType(createUnboundedVarcharType(), createUnboundedVarcharType())),
                new ColumnMetadata("timestamp", TimestampType.TIMESTAMP),
                new ColumnMetadata("value", DOUBLE)));

        // unknown tables should produce null
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("unknown", "unknown")));
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("prometheus", "unknown")));
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("unknown", "numbers")));
    }

    @Test
    public void testListTables()
    {
        assertTrue(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("prometheus"))).contains(new SchemaTableName("prometheus", "up")));

        // unknown schema
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("unknown"))), ImmutableSet.of());
    }

    @Test
    public void getColumnMetadata()
    {
        assertEquals(metadata.getColumnMetadata(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE, new PrometheusColumnHandle("text", createUnboundedVarcharType(), 0)),
                new ColumnMetadata("text", createUnboundedVarcharType()));

        // prometheus connector assumes that the table handle and column handle are
        // properly formed, so it will return a metadata object for any
        // PrometheusTableHandle and PrometheusColumnHandle passed in.  This is on because
        // it is not possible for the Presto Metadata system to create the handles
        // directly.
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testCreateTable()
    {
        metadata.createTable(
                SESSION,
                new ConnectorTableMetadata(
                        new SchemaTableName("prometheus", "foo"),
                        ImmutableList.of(new ColumnMetadata("text", createUnboundedVarcharType()))),
                false);
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testDropTableTable()
    {
        metadata.dropTable(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE);
    }
}
