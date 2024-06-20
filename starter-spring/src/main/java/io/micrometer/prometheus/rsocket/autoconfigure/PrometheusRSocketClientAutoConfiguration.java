/**
 * Copyright 2019 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micrometer.prometheus.rsocket.autoconfigure;

import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.util.retry.Retry;

@AutoConfiguration
@AutoConfigureAfter(PrometheusMetricsExportAutoConfiguration.class)
@ConditionalOnBean(PrometheusMeterRegistry.class)
@ConditionalOnProperty(prefix = "management.metrics.export.prometheus.rsocket", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PrometheusRSocketClientProperties.class)
public class PrometheusRSocketClientAutoConfiguration {

  @ConditionalOnMissingBean
  @Bean(destroyMethod = "pushAndCloseBlockingly")
  PrometheusRSocketClient prometheusRSocketClient(PrometheusMeterRegistry meterRegistry, PrometheusRSocketClientProperties properties) {
    return PrometheusRSocketClient.build(meterRegistry, properties.createClientTransport())
        .retry(Retry.backoff(properties.getMaxRetries(), properties.getFirstBackoff())
            .maxBackoff(properties.getMaxBackoff()))
        .connectBlockingly();
  }
}
