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
import io.prestosql.spi.connector.ColumnMetadata;
import org.testng.annotations.Test;

import java.net.URI;

import static io.prestosql.plugin.prometheus.MetadataUtil.TABLE_CODEC;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static org.testng.Assert.assertEquals;

public class TestPrometheusTable
{
    private final PrometheusTable prometheusTable = new PrometheusTable("tableName",
            ImmutableList.of(new PrometheusColumn("a", createUnboundedVarcharType()), new PrometheusColumn("b", BIGINT)),
            ImmutableList.of(URI.create("file://table-1.json"), URI.create("file://table-2.json")));

    @Test
    public void testColumnMetadata()
    {
        assertEquals(prometheusTable.getColumnsMetadata(), ImmutableList.of(
                new ColumnMetadata("a", createUnboundedVarcharType()),
                new ColumnMetadata("b", BIGINT)));
    }

    @Test
    public void testRoundTrip()
    {
        String json = TABLE_CODEC.toJson(prometheusTable);
        PrometheusTable prometheusTableCopy = TABLE_CODEC.fromJson(json);

        assertEquals(prometheusTableCopy.getName(), prometheusTable.getName());
        assertEquals(prometheusTableCopy.getColumns(), prometheusTable.getColumns());
        assertEquals(prometheusTableCopy.getSources(), prometheusTable.getSources());
    }
}
