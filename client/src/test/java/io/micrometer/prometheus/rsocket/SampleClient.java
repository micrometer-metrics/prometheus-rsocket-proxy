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

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Random;

public class SampleClient {
  public static void main(String[] args) {
    PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    meterRegistry.config().commonTags("process.id", ManagementFactory.getRuntimeMXBean().getName());

    PrometheusRSocketClient.build(meterRegistry, WebsocketClientTransport.create("localhost", 8081))
        .retry(Retry.fixedDelay(3, Duration.ofSeconds(1)))
        .connect();

    Random r = new Random();

    Counter counter = meterRegistry.counter("my.counter", "instance", Integer.toString(r.nextInt(10)));
    Flux.interval(Duration.ofMillis(100))
        .doOnEach(n -> counter.increment())
        .blockLast();
  }
}
