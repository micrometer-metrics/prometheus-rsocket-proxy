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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.lang.Nullable;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.ClientTransport;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.xerial.snappy.Snappy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  private final MeterRegistryAndScrape<?> registryAndScrape;

  private final Disposable connection;
  private AtomicReference<PublicKey> latestKey = new AtomicReference<>();
  private final Runnable onKeyReceived;

  private final AbstractRSocket rsocket = new AbstractRSocket() {
    @Override
    public Mono<Payload> requestResponse(Payload payload) {
      PublicKey key = decodePublicKey(payload.getData());
      latestKey.set(key);
      onKeyReceived.run();
      try {
        return requestedDisconnect ? Mono.error(new IllegalStateException("CLOSE_REQUESTED_BY_CLIENT")) : Mono.just(scrapePayload(key));
      } catch (Exception e) {
        return Mono.error(e);
      }
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
      latestKey.set(decodePublicKey(payload.getData()));
      onKeyReceived.run();
      return Mono.empty();
    }
  };

  private volatile boolean requestedDisconnect = false;
  private RSocket sendingSocket;

  private PrometheusRSocketClient(MeterRegistryAndScrape<?> registryAndScrape,
                                  ClientTransport transport,
                                  Retry retry,
                                  Runnable onKeyReceived) {
    this.registryAndScrape = registryAndScrape;
    this.onKeyReceived = onKeyReceived;

    this.connection = RSocketFactory.connect()
        .errorConsumer(t ->
            Counter.builder("prometheus.connection.error")
                .tag("exception", t.getClass().getName())
                .register(registryAndScrape.registry)
                .increment()
        )
        .reconnect(new Retry() {
          @Override
          public Publisher<?> generateCompanion(Flux<RetrySignal> retrySignals) {
            return retry.generateCompanion(retrySignals
                .doOnNext(retrySignal ->
                    DistributionSummary.builder("prometheus.connection.retry")
                        .description("Attempts at retrying an RSocket connection to the Prometheus proxy")
                        .baseUnit("retries")
                        .tag("exception", retrySignal.failure().getCause().getMessage())
                        .register(registryAndScrape.registry)
                        .record(retrySignal.totalRetries())
                )
            );
          }
        })
        .acceptor(r -> {
          this.sendingSocket = r;
          return rsocket;
        })
        .transport(transport)
        .start()
        .flatMap(RSocket::onClose)
        .repeat()
        .subscribe();
  }

  /**
   * The most common case is that you want to both register proxy client metrics to a {@link PrometheusMeterRegistry} and
   * also scrape from that registry.
   *
   * @param prometheusMeterRegistry The registry to scrape metrics from and also publish client metrics to.
   * @param clientTransport         The transport to connect to the proxy server with.
   * @return The client, connecting asynchronously at the point of return.
   */
  public static Builder build(PrometheusMeterRegistry prometheusMeterRegistry, ClientTransport clientTransport) {
    return new Builder(prometheusMeterRegistry, prometheusMeterRegistry::scrape, clientTransport);
  }

  /**
   * A generalization of {@link #build(PrometheusMeterRegistry, ClientTransport)} where you define the scrape function yourself.
   * This is useful when, for example, you want to concatenate scrapes from several {@link PrometheusMeterRegistry} instances,
   * perhaps each with different common tags for different parts of the application, and present these together as the scrape.
   *
   * @param meterRegistry   The registry to publish client metrics to.
   * @param scrape          A scrape in the Prometheus format used.
   * @param clientTransport The transport to connect to the proxy server with.
   * @param <M>             The type of registry to publish client metrics to.
   * @return The client, connecting asynchronously at the point of return.
   */
  public static <M extends MeterRegistry> Builder build(M meterRegistry, Supplier<String> scrape, ClientTransport clientTransport) {
    return new Builder(meterRegistry, scrape, clientTransport);
  }

  public void close() {
    this.requestedDisconnect = true;
    connection.dispose();
  }

  public Mono<Void> pushAndClose() {
    PublicKey key = latestKey.get();
    if (key != null) {
      try {
        return sendingSocket
            .fireAndForget(scrapePayload(key))
            .then(Mono.fromRunnable(this::close));
      } catch (Exception ignored) {
        // give up, we tried...
      }
    }
    return Mono.fromRunnable(this::close);
  }

  private Payload scrapePayload(@Nullable PublicKey publicKey) throws Exception {
    String scrape = registryAndScrape.scrape();

    if (publicKey == null) {
      return DefaultPayload.create(scrape, "plaintext");
    }

    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(128);
    SecretKey secKey = generator.generateKey();

    Cipher aesCipher = Cipher.getInstance("AES");
    aesCipher.init(Cipher.ENCRYPT_MODE, secKey);
    byte[] encryptedMetrics = aesCipher.doFinal(Snappy.compress(scrape));

    Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    cipher.init(Cipher.PUBLIC_KEY, publicKey);
    byte[] encryptedPublicKey = cipher.doFinal(secKey.getEncoded());

    return DefaultPayload.create(encryptedMetrics, encryptedPublicKey);
  }

  @Nullable
  private PublicKey decodePublicKey(ByteBuffer encodedKeyBuffer) {
    byte[] encodedKey = new byte[encodedKeyBuffer.capacity()];
    encodedKeyBuffer.get(encodedKey);

    if ("plaintext".equals(new String(encodedKey, StandardCharsets.UTF_8))) {
      return null;
    }

    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encodedKey);
    try {
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePublic(keySpec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(e);
    }
  }

  public static class Builder {
    private MeterRegistryAndScrape<?> registryAndScrape;
    private final ClientTransport clientTransport;

    private Retry retry = Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(10))
        .maxBackoff(Duration.ofMinutes(10));

    private Runnable onKeyReceived = () -> { };

    <M extends MeterRegistry> Builder(M registry, Supplier<String> scrape, ClientTransport clientTransport) {
      this.registryAndScrape = new MeterRegistryAndScrape<>(registry, scrape);
      this.clientTransport = clientTransport;
    }

    public Builder retry(Retry retry) {
      this.retry = retry;
      return this;
    }

    public Builder doOnKeyReceived(Runnable onKeyReceived) {
      this.onKeyReceived = onKeyReceived;
      return this;
    }

    public PrometheusRSocketClient connect() {
      return new PrometheusRSocketClient(registryAndScrape, clientTransport, retry, onKeyReceived);
    }
  }

  private static class MeterRegistryAndScrape<M extends MeterRegistry> {
    final M registry;
    final Supplier<String> scrape;

    private MeterRegistryAndScrape(M registry, Supplier<String> scrape) {
      this.registry = registry;
      this.scrape = scrape;
    }

    String scrape() {
      return scrape.get();
    }
  }
}
