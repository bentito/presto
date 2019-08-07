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
import io.prestosql.spi.HostAddress;
import org.testng.annotations.Test;

import java.net.URI;

import static io.airlift.json.JsonCodec.jsonCodec;
import static org.testng.Assert.assertEquals;

public class TestPrometheusSplit
{
    private final PrometheusSplit split = new PrometheusSplit(URI.create("http://127.0.0.1/test.file"));

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
}
