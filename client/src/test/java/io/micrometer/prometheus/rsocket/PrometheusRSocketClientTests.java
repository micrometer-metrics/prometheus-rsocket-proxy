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
import io.rsocket.RSocketFactory;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.local.LocalServerTransport;
import io.rsocket.util.DefaultPayload;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRSocketClientTests {

  LocalServerTransport serverTransport = LocalServerTransport.createEphemeral();

  PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  /**
   * See https://github.com/micrometer-metrics/prometheus-rsocket-proxy/issues/3
   */
  @Test
  void dyingScrapeAfterNormalScrape() throws NoSuchAlgorithmException, InterruptedException {
    CountDownLatch dyingScrapeLatch = new CountDownLatch(1);
    Payload payload = DefaultPayload.create(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
    RSocketFactory.receive()
        .frameDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> {
          // normal scrape
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

    PrometheusRSocketClient client = new PrometheusRSocketClient(meterRegistry, serverTransport.clientTransport(), voidFlux -> voidFlux);

    // trigger dying scrape
    client.pushAndClose();
    assertThat(dyingScrapeLatch.await(1, TimeUnit.SECONDS))
        .as("Dying scrape (fire-and-forget) should be successfully called")
        .isTrue();
  }
}
