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

import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.connector.TableNotFoundException;

import javax.inject.Inject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class PrometheusSplitManager
        implements ConnectorSplitManager
{
    private final PrometheusClient prometheusClient;

    @Inject
    public PrometheusSplitManager(PrometheusClient prometheusClient)
    {
        this.prometheusClient = requireNonNull(prometheusClient, "client is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle connectorTableHandle,
            SplitSchedulingStrategy splitSchedulingStrategy)
    {
        PrometheusTableHandle tableHandle = (PrometheusTableHandle) connectorTableHandle;
        PrometheusTable table = prometheusClient.getTable(tableHandle.getSchemaName(), tableHandle.getTableName());

        // this can happen if table is removed during a query
        if (table == null) {
            throw new TableNotFoundException(tableHandle.toSchemaTableName());
        }

        List<ConnectorSplit> splits = new ArrayList<>();
        try {
            splits.add(new PrometheusSplit(buildQuery(prometheusClient.config.getPrometheusURI(), table.getName())));
        }
        catch (UnsupportedEncodingException e) {
            throw new UnsupportedOperationException(e.getMessage());
        }
        catch (URISyntaxException e) {
            throw new UnsupportedOperationException(e.getMessage());
        }
        Collections.shuffle(splits);

        return new FixedSplitSource(splits);
    }

    private URI buildQuery(URI baseURI, String tableName)
            throws UnsupportedEncodingException, URISyntaxException
    {
        return new URI(baseURI.toString() +
                "/" +
                "api/v1/query?query=" +
                URLEncoder.encode(tableName, "UTF-8") +
                "[" + prometheusClient.config.getQueryChunkSizeDuration() + "]");
    }
}
