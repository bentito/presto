/*
 *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package io.prestosql.plugin.prometheus;

import java.time.ZonedDateTime;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class PrometheusPredicateTimeInfo
{
    Optional<ZonedDateTime> predicateLowerTimeBound;
    Optional<ZonedDateTime> predicateUpperTimeBound;

    private PrometheusPredicateTimeInfo(Builder builder)
    {
        predicateLowerTimeBound = builder.predicateLowerTimeBound;
        predicateUpperTimeBound = builder.predicateUpperTimeBound;
    }

    public Optional<ZonedDateTime> getPredicateLowerTimeBound()
    {
        return predicateLowerTimeBound;
    }

    public Optional<ZonedDateTime> getPredicateUpperTimeBound()
    {
        return predicateUpperTimeBound;
    }

    public Builder toBuilder()
    {
        return new Builder(this);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {

        Optional<ZonedDateTime> predicateLowerTimeBound;
        Optional<ZonedDateTime> predicateUpperTimeBound;

        public Builder()
        {
            // Empty constructor
        }

        public Builder(PrometheusPredicateTimeInfo prometheusPredicateTimeInfo)
        {
            this.predicateLowerTimeBound = prometheusPredicateTimeInfo.predicateLowerTimeBound;
            this.predicateUpperTimeBound = prometheusPredicateTimeInfo.predicateUpperTimeBound;
        }

        public PrometheusPredicateTimeInfo build()
        {
            requireNonNull(predicateLowerTimeBound, "must set a lower time bound for predicate time info");
            requireNonNull(predicateUpperTimeBound, "must set a upper time bound for predicate time info");
            return new PrometheusPredicateTimeInfo(this);
        }
    }
}
