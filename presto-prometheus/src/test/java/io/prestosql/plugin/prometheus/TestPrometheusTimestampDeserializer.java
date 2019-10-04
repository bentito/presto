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

import org.testng.annotations.Test;

import java.sql.Timestamp;
import java.time.ZonedDateTime;

import static org.testng.Assert.assertEquals;

public class TestPrometheusTimestampDeserializer
{

    @Test
    public void testDeserializeTimestamp()
    {
        ZonedDateTime now = ZonedDateTime.now();
        String nowEpochInSecondsStr = String.valueOf(now.toInstant().getEpochSecond());
        long nowEpochNanos = now.toInstant().getNano();
        String nowEpochMillisStr = String.valueOf(nowEpochNanos).substring(0, 3);
        String nowTimeStr = nowEpochInSecondsStr + "." + nowEpochMillisStr;
        Timestamp nowTimestampActual = PrometheusTimestampDeserializer.decimalEpochTimestampToSQLTimestamp(nowTimeStr);
        assertEquals(nowTimestampActual,
                new Timestamp(now.toInstant().toEpochMilli()));
    }
}