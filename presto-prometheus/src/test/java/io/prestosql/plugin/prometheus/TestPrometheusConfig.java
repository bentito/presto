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

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestPrometheusConfig
{
    @Test
    public void testDefaults()
            throws URISyntaxException
    {
        assertRecordedDefaults(recordDefaults(PrometheusConfig.class)
                .setPrometheusURI(null)
                .setQueryChunkSizeDuration(null)
                .setMaxQueryRangeDuration(null));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("prometheus-uri", "file://test.json")
                .put("query-chunk-size-duration", "1d")
                .put("max-query-range-duration", "3w")
                .build();

        URI uri = URI.create("file://test.json");
        PrometheusConfig expected = new PrometheusConfig();
        expected.setPrometheusURI(uri);
        expected.setQueryChunkSizeDuration("1d");
        expected.setMaxQueryRangeDuration("3w");

        assertFullMapping(properties, expected);
    }
}
