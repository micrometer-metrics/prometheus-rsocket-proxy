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

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter(PrometheusMetricsExportAutoConfiguration.class)
@ConditionalOnBean(PrometheusMeterRegistry.class)
@ConditionalOnProperty(prefix = "management.metrics.export.prometheus.rsocket", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PrometheusRSocketProperties.class)
public class PrometheusRSocketAutoConfiguration {

  @ConditionalOnMissingBean
  @Bean(destroyMethod = "pushAndClose")
  PrometheusRSocketClient prometheusRSocketClient(PrometheusMeterRegistry meterRegistry, PrometheusRSocketProperties properties) {
    return new PrometheusRSocketClient(meterRegistry, properties.createClientTransport(),
      c -> c.retryBackoff(properties.getMaxRetries(), properties.getFirstBackoff(), properties.getMaxBackoff()));
  }
}
