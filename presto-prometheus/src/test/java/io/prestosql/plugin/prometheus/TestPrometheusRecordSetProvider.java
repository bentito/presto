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
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.connector.RecordSet;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.type.InternalTypeManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.PrometheusRecordCursor.getMapFromBlock;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TestPrometheusRecordSetProvider
{
    private PrometheusHttpServer prometheusHttpServer;
    private URI dataUri;
    private static final Metadata METADATA = createTestMetadataManager();
    public static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA);
    static final Type varcharMapType = TYPE_MANAGER.getType(TypeSignature.parseTypeSignature("map(varchar, varchar)"));

    @Test
    public void testGetRecordSet()
    {
        ConnectorTableHandle tableHandle = new PrometheusTableHandle("schema", "table");
        PrometheusRecordSetProvider recordSetProvider = new PrometheusRecordSetProvider();
        RecordSet recordSet = recordSetProvider.getRecordSet(PrometheusTransactionHandle.INSTANCE, SESSION,
                new PrometheusSplit(dataUri), tableHandle, ImmutableList.of(
                        new PrometheusColumnHandle("labels", MetadataUtil.mapType(createUnboundedVarcharType(), createUnboundedVarcharType()), 0),
                        new PrometheusColumnHandle("timestamp", DoubleType.DOUBLE, 1),
                        new PrometheusColumnHandle("value", DoubleType.DOUBLE, 2)));
        assertNotNull(recordSet, "recordSet is null");

        RecordCursor cursor = recordSet.cursor();
        assertNotNull(cursor, "cursor is null");

        Map<Double, Map> actual = new LinkedHashMap<>();
        while (cursor.advanceNextPosition()) {
            actual.put(cursor.getDouble(1), (Map) getMapFromBlock(varcharMapType, (Block) cursor.getObject(0)));
        }
        Map<Double, Map> expected = ImmutableMap.<Double, Map>builder()
                .put(1565962969.044 * 1000, ImmutableMap.of("instance", "localhost:9090", "__name__", "up", "job", "prometheus"))
                .put(1565962984.045 * 1000, ImmutableMap.of("instance", "localhost:9090", "__name__", "up", "job", "prometheus"))
                .put(1565962999.044 * 1000, ImmutableMap.of("instance", "localhost:9090", "__name__", "up", "job", "prometheus"))
                .put(1565963014.044 * 1000, ImmutableMap.of("instance", "localhost:9090", "__name__", "up", "job", "prometheus"))
                .build();
        assertEquals(actual, expected);
    }

    //
    // Start http server for testing
    //

    @BeforeClass
    public void setUp()
            throws Exception
    {
        prometheusHttpServer = new PrometheusHttpServer();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/up_matrix_response.json");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        if (prometheusHttpServer != null) {
            prometheusHttpServer.stop();
        }
    }
}
