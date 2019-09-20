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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;

import javax.validation.constraints.NotNull;

import java.net.URI;
import java.net.URISyntaxException;

public class PrometheusConnectorConfig
{
    private URI prometheusURI = new URI("http://localhost:9090");
    private String queryChunkSizeDuration = "1d";
    private String maxQueryRangeDuration = "21d";
    private String cacheDuration = "30s";

    public PrometheusConnectorConfig()
            throws URISyntaxException
    {}

    @NotNull
    public URI getPrometheusURI()
    {
        return prometheusURI;
    }

    @Config("prometheus-uri")
    @ConfigDescription("Where to find Prometheus coordinator host")
    public PrometheusConnectorConfig setPrometheusURI(URI prometheusURI)
    {
        this.prometheusURI = prometheusURI;
        return this;
    }

    @NotNull
    public String getQueryChunkSizeDuration()
    {
        return queryChunkSizeDuration;
    }

    @Config("query-chunk-size-duration")
    @ConfigDescription("The duration of each query to Prometheus")
    public PrometheusConnectorConfig setQueryChunkSizeDuration(String queryChunkSizeDuration)
    {
        this.queryChunkSizeDuration = queryChunkSizeDuration;
        return this;
    }

    @NotNull
    public String getMaxQueryRangeDuration()
    {
        return maxQueryRangeDuration;
    }

    @Config("max-query-range-duration")
    @ConfigDescription("Width of overall query to Prometheus, will be divided into query-chunk-size-duration queries")
    public PrometheusConnectorConfig setMaxQueryRangeDuration(String maxQueryRangeDuration)
    {
        this.maxQueryRangeDuration = maxQueryRangeDuration;
        return this;
    }

    @NotNull
    public String getCacheDuration()
    {
        return cacheDuration;
    }

    @Config("cache-duration")
    @ConfigDescription("How long values in this config are cached")
    public PrometheusConnectorConfig setCacheDuration(String cacheConfigDuration)
    {
        this.cacheDuration = cacheConfigDuration;
        return this;
    }
}
