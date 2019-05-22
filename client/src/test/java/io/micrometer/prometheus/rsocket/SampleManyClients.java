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
package io.micrometer.prometheus.rsocket;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import io.rsocket.transport.netty.client.TcpClientTransport;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SampleManyClients {
  private static final int CLIENT_COUNT = 1000;
  private static final int ROW_COUNT = 1000;

  public static void main(String[] args) {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);

    List<PrometheusMeterRegistry> registries = IntStream.range(0, CLIENT_COUNT)
      .mapToObj(n -> {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new CollectorRegistry(), Clock.SYSTEM);
        meterRegistry.config().commonTags("client.id", Integer.toString(n));

        new PrometheusRSocketClient(meterRegistry,
          TcpClientTransport.create("localhost", 7001),
          c -> c.retryBackoff(Long.MAX_VALUE, Duration.ofSeconds(10), Duration.ofMinutes(10)));

        return meterRegistry;
      })
      .collect(Collectors.toList());

    Flux.interval(Duration.ofMillis(100))
      .doOnEach(n -> {
        for (PrometheusMeterRegistry meterRegistry : registries) {
          for (int r = 0; r < ROW_COUNT; r++) {
            meterRegistry.counter("my.counter", "row", Integer.toString(r)).increment();
          }
        }
      })
      .blockLast();
  }
}
