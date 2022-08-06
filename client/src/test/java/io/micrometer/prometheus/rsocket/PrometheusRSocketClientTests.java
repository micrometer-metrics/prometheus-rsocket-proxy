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
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketServer;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.local.LocalClientTransport;
import io.rsocket.transport.local.LocalServerTransport;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
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
    });

    LocalClientTransport local = LocalClientTransport.create("local");

    CountDownLatch reconnectionAttemptLatch = new CountDownLatch(3);

    PrometheusRSocketClient.build(meterRegistry, local)
        .retry(Retry.fixedDelay(10, Duration.ofMillis(5)).doAfterRetry(retry -> reconnectionAttemptLatch.countDown()))
        .connect();

    assertThat(reconnectionAttemptLatch.await(1, SECONDS)).isTrue();
    assertThat(meterRegistry.get("prometheus.connection.retry").summary().count()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void doesntAttemptReconnectWhenPushAndClose() throws InterruptedException {
    CountDownLatch connectionLatch = new CountDownLatch(3);

    RSocketServer.create()
        .payloadDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          connectionLatch.countDown();
          return Mono.empty();
        })
        .bind(serverTransport)
        .block();

    PrometheusRSocketClient client = PrometheusRSocketClient.build(meterRegistry, serverTransport.clientTransport())
        .retry(Retry.max(3))
        .connect();

    client.pushAndClose();

    assertThat(connectionLatch.await(1, SECONDS)).isFalse();
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

    RSocketServer.create()
        .payloadDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          // normal scrape
          serverSocket.set(sendingSocket);
          sendingSocket.requestResponse(payload).subscribe();

          return Mono.just(new RSocket() {

            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              dyingScrapeLatch.countDown();
              return Mono.just(EmptyPayload.INSTANCE);
            }

            @Override
            public Mono<Void> fireAndForget(Payload payload) {
              dyingScrapeLatch.countDown();
              return Mono.empty();
            }
          });
        })
        .bind(serverTransport)
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
        .connectBlockingly();

    assertThat(normalScrapeLatch.await(1, SECONDS)).isTrue();

    // trigger dying scrape
    client.pushAndCloseBlockingly();
    assertThat(dyingScrapeLatch.await(1, TimeUnit.SECONDS))
        .as("Dying scrape should be successfully called")
        .isTrue();

    // after pushAndClose(), the client should no longer be scrapable
    Boolean failed = serverSocket.get()
        .requestResponse(payload)
        .map(response -> false)
        .onErrorReturn(true)
        .block(Duration.ofSeconds(10));

    assertThat(failed).isTrue();
  }

  @Test
  void blockingConnectAndPush() throws NoSuchAlgorithmException, InterruptedException, ExecutionException {
    CountDownLatch pushLatch = new CountDownLatch(1);
    AtomicBoolean pushed = new AtomicBoolean(false);
    Payload payload = DefaultPayload.create(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
    RSocketServer.create()
        .payloadDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          sendingSocket.requestResponse(payload)
              .subscribeOn(Schedulers.newSingle("server-polls"))
              .subscribe();

          return Mono.just(new RSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              return Mono.defer(() -> {
                await(pushLatch);
                pushed.set(true);
                return Mono.just(EmptyPayload.INSTANCE);
              })
                  .map(p -> (Payload) p)
                  .subscribeOn(Schedulers.newSingle("server-polls"));
            }
          });
        })
        .bind(serverTransport)
        .block();

    CountDownLatch keyReceivedLatch = new CountDownLatch(1);
    AtomicBoolean keyReceived = new AtomicBoolean(false);
    PrometheusRSocketClient.Builder clientBuilder = PrometheusRSocketClient.build(
        meterRegistry,
        () -> "",
        serverTransport.clientTransport()
    )
        .retry(Retry.max(0))
        .doOnKeyReceived(() -> {
          await(keyReceivedLatch);
          keyReceived.set(true);
        });

    CompletableFuture<PrometheusRSocketClient> clientFuture = supplyAsync(clientBuilder::connectBlockingly, newSingleThreadExecutor());
    runAsync(keyReceivedLatch::countDown, delayedExecutor(200, MILLISECONDS));
    assertThat(keyReceived).as("Public key should not be received (not connected)").isFalse();
    PrometheusRSocketClient client = clientFuture.get();
    assertThat(keyReceived).as("Public key should be received(connected)").isTrue();

    CompletableFuture<Void> closeFuture = runAsync(client::pushAndCloseBlockingly, newSingleThreadExecutor());
    runAsync(pushLatch::countDown, delayedExecutor(200, MILLISECONDS));
    assertThat(pushed).as("Data should not be pushed").isFalse();
    closeFuture.get();
    assertThat(pushed).as("Data should be pushed").isTrue();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, SECONDS)) {
        throw new RuntimeException("Latch timed out, there might be a problem with the test setup.");
      }
    }
    catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
