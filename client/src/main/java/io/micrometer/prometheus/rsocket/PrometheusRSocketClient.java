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
import io.micrometer.core.util.internal.logging.InternalLogger;
import io.micrometer.core.util.internal.logging.InternalLoggerFactory;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.ClientTransport;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.xerial.snappy.Snappy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(PrometheusRSocketClient.class);
  private final MeterRegistryAndScrape<?> registryAndScrape;

  private volatile RSocket connection;
  private AtomicReference<PublicKey> latestKey = new AtomicReference<>();

  private volatile boolean requestedDisconnect = false;
  private RSocket sendingSocket;

  private Duration timeout;

  /**
   * Creates a {@link PrometheusRSocketClient}.
   *
   * @param registryAndScrape the registry and scrape meter
   * @param transport         the client transport
   * @param reconnectRetry    the reconnectRetry configuration
   * @param onKeyReceived     the callback if a key has been received
   */
  private PrometheusRSocketClient(MeterRegistryAndScrape<?> registryAndScrape,
                                  ClientTransport transport,
                                  Retry reconnectRetry,
                                  Runnable onKeyReceived) {
    this(registryAndScrape, transport, reconnectRetry, Retry.backoff(6, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(5)), Duration.ofSeconds(5), onKeyReceived);
  }

  /**
   * Creates a {@link PrometheusRSocketClient}.
   *
   * @param registryAndScrape the registry and scrape meter
   * @param transport         the client transport
   * @param retry             the retry configuration
   * @param timeout           the timeout to connect and push the data
   * @param onKeyReceived     the callback if a key has been received
   */
  private PrometheusRSocketClient(MeterRegistryAndScrape<?> registryAndScrape,
                                  ClientTransport transport,
                                  Retry reconnectRetry,
                                  Retry retry,
                                  Duration timeout,
                                  Runnable onKeyReceived) {
    this.registryAndScrape = registryAndScrape;
    this.timeout = timeout;

    RSocketConnector.create()
        .reconnect(new Retry() {
          @Override
          public Publisher<?> generateCompanion(Flux<RetrySignal> retrySignals) {
            return reconnectRetry.generateCompanion(retrySignals
                .doOnNext(retrySignal -> {
                      Throwable failure = retrySignal.failure();
                      DistributionSummary.builder("prometheus.connection.retry")
                          .description("Attempts at retrying an RSocket connection to the Prometheus proxy")
                          .baseUnit("retries")
                          .tag("exception", failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage())
                          .register(registryAndScrape.registry)
                          .record(retrySignal.totalRetries());
                    }
                )
            );
          }
        })
        .acceptor((payload, r) -> {
          LOGGER.trace("Acceptor for requestResponse and fireAndForget has been set up.");
          this.sendingSocket = r;
          return Mono.just(new RSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              try {
                LOGGER.trace("Received public key from Prometheus proxy in requestResponse.");
                PublicKey key = decodePublicKey(payload.getData());
                latestKey.set(key);
                onKeyReceived.run();
                return Mono.fromCallable(() -> scrapePayload(key));
              } finally {
                payload.release();
              }
            }

            @Override
            public Mono<Void> fireAndForget(Payload payload) {
              try {
                LOGGER.trace("Received public key from Prometheus proxy in fireAndForget.");
                latestKey.set(decodePublicKey(payload.getData()));
                onKeyReceived.run();
                return Mono.empty();
              } finally {
                payload.release();
              }
            }
          });
        })
        .connect(transport)
        .doOnError(t -> {
          LOGGER.trace("Failed to connect to Prometheus proxy.", t);
          Counter.builder("prometheus.connection.error")
              .baseUnit("errors")
              .tag("exception", t.getClass().getSimpleName() == null ? t.getClass().getName() : t.getClass().getSimpleName())
              .register(registryAndScrape.registry)
              .increment();
        })
        .doOnSuccess(connection -> {
          LOGGER.trace("Successfully established connection to Prometheus proxy.");
          Counter.builder("prometheus.connection.success")
              .baseUnit("connections")
              .register(registryAndScrape.registry)
              .increment();
        })
        .doOnNext(connection -> this.connection = connection)
        .flatMap(socket -> socket.onClose()
            .then(Mono.fromCallable(() -> 1)) // https://github.com/rsocket/rsocket-java/issues/819
            .onErrorReturn(1))
        .repeat(() -> !requestedDisconnect)
        .retryWhen(retry)
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

  /**
   * Closes the {@link PrometheusRSocketClient}
   */
  public void close() {
    this.requestedDisconnect = true;
    if (this.connection != null) {
      this.connection.dispose();
    }
  }

  /**
   * Pushes the data in a blocking way and closes the connection.
   */
  public void pushAndCloseBlockingly() {
    pushAndCloseBlockingly(timeout);
  }

  /**
   * Pushes the data in a blocking way and closes the connection.
   *
   * @param timeout the amount of time to wait for the data to be sent
   */
  public void pushAndCloseBlockingly(Duration timeout) {
    LOGGER.debug("Pushing data to RSocket Proxy before closing the connection...");
    CountDownLatch latch = new CountDownLatch(1);
    PublicKey key = latestKey.get();
    if (key != null) {
      try {
        sendingSocket
            // This should be requestResponse instead of fireAndForget
            // since the latter will almost immediately emit the complete signal (before anything happens on the wire)
            // so waiting for it to terminate does not make too much sense; more details: https://github.com/micrometer-metrics/prometheus-rsocket-proxy/pull/54
            .requestResponse(scrapePayload(key))
            .doOnSuccess(payload -> LOGGER.info("Pushing data to RSocket Proxy before closing the connection was successful!"))
            .doOnError(throwable -> LOGGER.warn("Pushing data to RSocket Proxy before closing the connection failed!", throwable))
            .doOnCancel(() -> LOGGER.warn("Pushing data to RSocket Proxy before closing the connection was cancelled!"))
            .doFinally(signalType -> latch.countDown())
            .subscribe();
      } catch (Exception exception) {
        latch.countDown();
        LOGGER.warn("Sending the payload failed!", exception);
      }

      try {
        if (!latch.await(timeout.toMillis(), MILLISECONDS)) {
          LOGGER.warn("Sending the payload timed out!");
        }
      } catch (InterruptedException exception) {
        LOGGER.warn("Waiting for sending the payload was interrupted!", exception);
      }
    }
    close();
  }

  /**
   * Pushes the data asynchronously (non-blocking) and closes the connection.
   */
  public void pushAndClose() {
    LOGGER.debug("Pushing data to RSocket Proxy before closing the connection...");
    PublicKey key = latestKey.get();
    if (key != null) {
      try {
        sendingSocket
            .fireAndForget(scrapePayload(key))
            .doOnSuccess(payload -> LOGGER.info("Pushing data to RSocket Proxy before closing the connection was successful!"))
            .doOnError(throwable -> LOGGER.warn("Pushing data to RSocket Proxy before closing the connection failed!", throwable))
            .doOnCancel(() -> LOGGER.warn("Pushing data to RSocket Proxy before closing the connection was cancelled!"))
            .subscribe();
      } catch (Exception exception) {
        LOGGER.warn("Sending the payload failed!", exception);
      }
    }
    close();
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

  /**
   * Builder class to create a {@link PrometheusRSocketClient}.
   */
  public static class Builder {
    private MeterRegistryAndScrape<?> registryAndScrape;
    private final ClientTransport clientTransport;

    private Retry reconnectRetry = Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(10))
        .maxBackoff(Duration.ofMinutes(10));

    private Retry retry = Retry.backoff(6, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(5));

    private Duration timeout = Duration.ofSeconds(5);

    private Runnable onKeyReceived = () -> {
    };

    <M extends MeterRegistry> Builder(M registry, Supplier<String> scrape, ClientTransport clientTransport) {
      this.registryAndScrape = new MeterRegistryAndScrape<>(registry, scrape);
      this.clientTransport = clientTransport;
    }

    /**
     * Configures the reconnectRetry for {@link PrometheusRSocketClient}.
     *
     * @param reconnectRetry the reconnectRetry configuration
     * @return the {@link Builder}
     */
    public Builder reconnectRetry(Retry reconnectRetry) {
      this.reconnectRetry = reconnectRetry;
      return this;
    }

    /**
     * Configures the retry for {@link PrometheusRSocketClient}.
     *
     * @param retry the retry configuration
     * @return the {@link Builder}
     */
    public Builder retry(Retry retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Timeout for the {@link PrometheusRSocketClient}.
     *
     * @param timeout the timeout in seconds
     * @return the {@link Builder}
     */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Callback of {@link PrometheusRSocketClient} if a key is received.
     *
     * @param onKeyReceived callback which is executed if a key is received
     * @return the {@link Builder}
     */
    public Builder doOnKeyReceived(Runnable onKeyReceived) {
      this.onKeyReceived = onKeyReceived;
      return this;
    }

    /**
     * Connects the {@link PrometheusRSocketClient}.
     *
     * @return the {@link PrometheusRSocketClient}
     */
    public PrometheusRSocketClient connect() {
      return connect(timeout);
    }

    /**
     * Connects the {@link PrometheusRSocketClient}.
     *
     * @param timeout the timeout for the client to connect
     *
     * @return the {@link PrometheusRSocketClient}
     */
    public PrometheusRSocketClient connect(Duration timeout) {
      LOGGER.debug("Connecting to RSocket Proxy...");
      return new PrometheusRSocketClient(
          registryAndScrape,
          clientTransport,
          reconnectRetry,
          retry,
          timeout,
          () -> {
            LOGGER.info("Connected to RSocket Proxy!");
            onKeyReceived.run();
          }
      );
    }

    /**
     * Connects the {@link PrometheusRSocketClient} blockingly.
     *
     * @return the {@link PrometheusRSocketClient}
     */
    public PrometheusRSocketClient connectBlockingly() {
      return connectBlockingly(timeout);
    }

    /**
     * Connects the {@link PrometheusRSocketClient} blockingly with the given timeout.
     *
     * @param timeout the timeout to wait for the connection to be established
     *
     * @return the {@link PrometheusRSocketClient}
     */
    public PrometheusRSocketClient connectBlockingly(Duration timeout) {
      LOGGER.debug("Connecting to RSocket Proxy...");
      CountDownLatch latch = new CountDownLatch(1);
      PrometheusRSocketClient client = new PrometheusRSocketClient(
          registryAndScrape,
          clientTransport,
          reconnectRetry,
          retry,
          timeout,
          () -> {
            LOGGER.info("Connected to RSocket Proxy!");
            onKeyReceived.run();
            latch.countDown();
          }
      );

      try {
        if (!latch.await(timeout.toMillis(), MILLISECONDS)) {
          LOGGER.warn("Creating the connection and receiving the key timed out!");
        }
      } catch (InterruptedException exception) {
        LOGGER.warn("Waiting for receiving the key was interrupted!", exception);
      }

      return client;
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
