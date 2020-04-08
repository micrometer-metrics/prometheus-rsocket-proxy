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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.*;
import io.rsocket.transport.ClientTransport;
import io.rsocket.util.DefaultPayload;
import org.xerial.snappy.Snappy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  private final MeterRegistryAndScrape<?> registryAndScrape;

  private final Disposable connection;
  private AtomicReference<PublicKey> latestKey = new AtomicReference<>();

  private final AbstractRSocket rsocket = new AbstractRSocket() {
    @Override
    public Mono<Payload> requestResponse(Payload payload) {
      PublicKey key = decodePublicKey(payload.getData());
      latestKey.set(key);
      onKeyExchanged.run();
      return Mono.just(scrapePayload(key));
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
      latestKey.set(decodePublicKey(payload.getData()));
      onKeyExchanged.run();
      return Mono.empty();
    }
  };

  private boolean pushOnDisconnect = false;
  private RSocket sendingSocket;
  private Runnable onKeyExchanged;

  private PrometheusRSocketClient(MeterRegistryAndScrape<?> registryAndScrape,
                                  ClientTransport transport,
                                  UnaryOperator<Flux<Void>> customizeAndRetry,
                                  Runnable onKeyExchanged) {
    this.registryAndScrape = registryAndScrape;
    this.onKeyExchanged = onKeyExchanged;
    Counter attempts = Counter.builder("prometheus.connection.attempts")
        .description("Attempts at making an outbound RSocket connection to the Prometheus proxy")
        .baseUnit("attempts")
        .register(registryAndScrape.registry);
    attempts.increment();

    this.connection = customizeAndRetry.apply(RSocketFactory.connect()
        .acceptor(r -> {
          this.sendingSocket = r;
          return rsocket;
        })
        .transport(transport)
        .start()
        .doOnCancel(() -> {
          if (pushOnDisconnect) {
            PublicKey key = latestKey.get();
            if (key != null) {
              sendingSocket
                  .fireAndForget(scrapePayload(key))
                  .block(Duration.ofSeconds(10));
            }
          }
        })
        .flatMap(Closeable::onClose)
        .repeat(() -> {
          attempts.increment();
          return true;
        })
    ).subscribe();
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
   * @return The client, connecting asynchronously at the point of return.
   */
  public static <M extends MeterRegistry> Builder build(M meterRegistry, Supplier<String> scrape, ClientTransport clientTransport) {
    return new Builder(meterRegistry, scrape, clientTransport);
  }

  public void close() {
    connection.dispose();
  }

  public void pushAndClose() {
    this.pushOnDisconnect = true;
    close();
  }

  private Payload scrapePayload(PublicKey publicKey) {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(128);
      SecretKey secKey = generator.generateKey();

      Cipher aesCipher = Cipher.getInstance("AES");
      aesCipher.init(Cipher.ENCRYPT_MODE, secKey);
      byte[] encryptedMetrics = aesCipher.doFinal(Snappy.compress(registryAndScrape.scrape()));

      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      cipher.init(Cipher.PUBLIC_KEY, publicKey);
      byte[] encryptedPublicKey = cipher.doFinal(secKey.getEncoded());

      return DefaultPayload.create(encryptedMetrics, encryptedPublicKey);
    } catch (Throwable e) {
      throw new IllegalArgumentException(e);
    }
  }

  private PublicKey decodePublicKey(ByteBuffer encodedKeyBuffer) {
    byte[] encodedKey = new byte[encodedKeyBuffer.capacity()];
    encodedKeyBuffer.get(encodedKey);

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

    private UnaryOperator<Flux<Void>> customizeAndRetry = c -> c.retryBackoff(Long.MAX_VALUE, Duration.ofSeconds(10), Duration.ofMinutes(10));
    private Runnable onKeyExchanged = () -> {
    };

    <M extends MeterRegistry> Builder(M registry, Supplier<String> scrape, ClientTransport clientTransport) {
      this.registryAndScrape = new MeterRegistryAndScrape<>(registry, scrape);
      this.clientTransport = clientTransport;
    }

    public Builder customizeAndRetry(UnaryOperator<Flux<Void>> customizeAndRetry) {
      this.customizeAndRetry = customizeAndRetry;
      return this;
    }

    public Builder onKeyExchanged(Runnable onKeyExchanged) {
      this.onKeyExchanged = onKeyExchanged;
      return this;
    }

    public PrometheusRSocketClient connect() {
      return new PrometheusRSocketClient(registryAndScrape, clientTransport, customizeAndRetry, onKeyExchanged);
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
