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
import io.airlift.json.JsonCodec;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.type.InternalTypeManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URISyntaxException;

import static io.airlift.json.JsonCodec.jsonCodec;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.MetadataUtil.METRIC_CODEC;
import static io.prestosql.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static org.testng.Assert.assertEquals;

public class TestPrometheusSplit
{
    private PrometheusHttpServer prometheusHttpServer;
    private URI dataUri;
    private final PrometheusSplit split = new PrometheusSplit(URI.create("http://127.0.0.1/test.file"));
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
        PrometheusConfig config = new PrometheusConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prom-metrics-non-standard-name.json");
        config.setPrometheusURI(dataUri);
        config.setQueryChunkSizeDuration("365d");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("prometheus", "up now");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("prometheus", table.getName()),
                null);
        PrometheusSplit split = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        String queryInSplit = split.getUri().getQuery();
        assertEquals(queryInSplit,
                new URI("http://doesnotmatter:9090/api/v1/query?query=up+now[" + config.getQueryChunkSizeDuration() + "]").getQuery());
    }

    @Test
    public void testQueryInSplits()
            throws URISyntaxException
    {
        PrometheusConfig config = new PrometheusConfig();
        dataUri = prometheusHttpServer.resolve("/prometheus-data/prometheus-metrics.json");
        config.setPrometheusURI(dataUri);
        config.setQueryChunkSizeDuration("365d");
        PrometheusClient client = new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
        PrometheusTable table = client.getTable("prometheus", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("prometheus", table.getName()),
                null);
        PrometheusSplit split = (PrometheusSplit) splits.getNextBatch(NOT_PARTITIONED, 1).getNow(null).getSplits().get(0);
        String queryInSplit = split.getUri().getQuery();
        assertEquals(queryInSplit,
                new URI("http://doesnotmatter:9090/api/v1/query?query=up[" + config.getQueryChunkSizeDuration() + "]").getQuery());
    }
}
