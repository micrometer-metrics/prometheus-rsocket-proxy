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

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.local.LocalClientTransport;
import io.rsocket.transport.local.LocalServerTransport;
import io.rsocket.util.DefaultPayload;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRSocketClientTests {

  LocalServerTransport serverTransport = LocalServerTransport.createEphemeral();

  PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  @Test
  void reconnection() throws InterruptedException {
    LocalServerTransport serverTransport = LocalServerTransport.createEphemeral();
    serverTransport.start(connection -> {
      connection.dispose();
      return Mono.empty();
    }, 0);

    LocalClientTransport local = LocalClientTransport.create("local");

    CountDownLatch reconnectionAttemptLatch = new CountDownLatch(3);

    PrometheusRSocketClient.build(meterRegistry, local)
        .retry(Retry.fixedDelay(10, Duration.ofMillis(5)).doAfterRetry(retry -> reconnectionAttemptLatch.countDown()))
        .connect();

    assertThat(reconnectionAttemptLatch.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(meterRegistry.get("prometheus.connection.retry").summary().count()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void doesntAttemptReconnectWhenPushAndClose() throws InterruptedException {
    CountDownLatch connectionLatch = new CountDownLatch(3);

    RSocketFactory.receive()
        .frameDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          connectionLatch.countDown();
          return Mono.empty();
        })
        .transport(serverTransport)
        .start()
        .block();

    PrometheusRSocketClient client = PrometheusRSocketClient.build(meterRegistry, serverTransport.clientTransport())
        .retry(Retry.max(3))
        .connect();

    client.pushAndClose();

    assertThat(connectionLatch.await(1, TimeUnit.SECONDS)).isFalse();
    assertThat(connectionLatch.getCount()).isEqualTo(2);
  }

  /**
   * See https://github.com/micrometer-metrics/prometheus-rsocket-proxy/issues/3
   */
  @Test
  void dyingScrapeAfterNormalScrape() throws NoSuchAlgorithmException, InterruptedException {
    CountDownLatch dyingScrapeLatch = new CountDownLatch(1);
    Payload payload = DefaultPayload.create(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
    AtomicReference<RSocket> serverSocket = new AtomicReference<>();

    RSocketFactory.receive()
        .frameDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          // normal scrape
          serverSocket.set(sendingSocket);
          sendingSocket.requestResponse(payload).subscribe();

          return Mono.just(new AbstractRSocket() {
            @Override
            public Mono<Void> fireAndForget(Payload payload) {
              dyingScrapeLatch.countDown();
              return Mono.empty();
            }
          });
        })
        .transport(serverTransport)
        .start()
        .block();

    CountDownLatch normalScrapeLatch = new CountDownLatch(1);

    PrometheusRSocketClient client = PrometheusRSocketClient
        .build(
            meterRegistry,
            () -> {
              normalScrapeLatch.countDown();
              return meterRegistry.scrape();
            },
            serverTransport.clientTransport()
        )
        .retry(Retry.max(0))
        .connect();

    assertThat(normalScrapeLatch.await(1, TimeUnit.SECONDS)).isTrue();

    // trigger dying scrape
    client.pushAndClose().block(Duration.ofSeconds(1));
    assertThat(dyingScrapeLatch.await(1, TimeUnit.SECONDS))
        .as("Dying scrape (fire-and-forget) should be successfully called")
        .isTrue();

    // after pushAndClose(), the client should no longer be scrapable
    Boolean failed = serverSocket.get()
        .requestResponse(payload)
        .map(response -> false)
        .onErrorReturn(true)
        .block(Duration.ofSeconds(10));

    assertThat(failed).isTrue();
  }
}
