package io.micrometer.prometheus.rsocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Random;

public class SampleClient {
  public static void main(String[] args) {
    PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    PrometheusRSocketClient.connect(meterRegistry, "localhost", 7001)
      .subscribe();

    Random r = new Random();

    Counter counter = meterRegistry.counter("my.counter", "instance", Integer.toString(r.nextInt(10)));
    Flux.interval(Duration.ofMillis(100))
      .doOnEach(n -> counter.increment())
      .blockLast();
  }
}
