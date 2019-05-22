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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("management.metrics.export.prometheus.rsocket")
public class PrometheusRSocketProperties {
  /**
   * The maximum number of connection attempts to make.
   */
  private long maxRetries = Long.MAX_VALUE;

  /**
   * The first connection attempt backoff delay to apply, then grow exponentially.
   */
  private Duration firstBackoff = Duration.ofSeconds(10);

  /**
   * The maximum connection attempt delay to apply despite exponential growth.
   */
  private Duration maxBackoff = Duration.ofMinutes(10);

  public long getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(long maxRetries) {
    this.maxRetries = maxRetries;
  }

  public Duration getFirstBackoff() {
    return firstBackoff;
  }

  public void setFirstBackoff(Duration firstBackoff) {
    this.firstBackoff = firstBackoff;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }

  public void setMaxBackoff(Duration maxBackoff) {
    this.maxBackoff = maxBackoff;
  }
}
