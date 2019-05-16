package io.micrometer.prometheus.rsocket;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
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

        PrometheusRSocketClient.connect(meterRegistry, "localhost", 7001)
          .retryBackoff(Long.MAX_VALUE, Duration.ofSeconds(10), Duration.ofMinutes(10))
          .subscribe();

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
