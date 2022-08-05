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
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketServer;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.micrometer.MicrometerRSocketInterceptor;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.transport.netty.server.WebsocketServerTransport;
import io.rsocket.util.DefaultPayload;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.rsocket.util.EmptyPayload;
import org.xerial.snappy.Snappy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A {@link Controller} for endpoints to be scraped by Prometheus.
 *
 * @author Jon Schneider
 * @author Christian Tzolov
 */
@RestController
public class PrometheusController {
  private final PrometheusMeterRegistry meterRegistry;
  private final Timer scrapeTimerSuccess;
  private final Timer scrapeTimerClosed;
  private final DistributionSummary scrapePayload;
  private final MicrometerRSocketInterceptor metricsInterceptor;
  private final PrometheusControllerProperties properties;
  private final Map<RSocket, ConnectionState> scrapableApps = new ConcurrentHashMap<>();

  public PrometheusController(PrometheusMeterRegistry meterRegistry, PrometheusControllerProperties properties) {
    this.meterRegistry = meterRegistry;
    this.metricsInterceptor = new MicrometerRSocketInterceptor(meterRegistry);
    this.properties = properties;
    meterRegistry.gaugeMapSize("prometheus.proxy.scrape.active.connections", Tags.empty(), scrapableApps);
    this.scrapeTimerSuccess = Timer.builder("prometheus.proxy.scrape")
        .tag("outcome", "success")
        .tag("exception", "none")
        .publishPercentileHistogram()
        .register(meterRegistry);

    this.scrapeTimerClosed = meterRegistry.timer("prometheus.proxy.scrape", "outcome", "closed", "exception", "none");
    this.scrapePayload = DistributionSummary.builder("prometheus.proxy.scrape.payload")
        .publishPercentileHistogram()
        .baseUnit("bytes")
        .register(meterRegistry);
  }

  @PostConstruct
  public void connect() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");

    RSocketServer.create()
        .payloadDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> acceptRSocket(generator, sendingSocket))
        .bind(TcpServerTransport.create(this.properties.getTcpPort()))
        .doOnError(t -> {
          Counter.builder("prometheus.proxy.connection.error")
              .tag("exception", t.getClass().getName())
              .tag("transport", "TCP")
              .register(meterRegistry)
              .increment();
        })
        .subscribe();

    RSocketServer.create()
        .payloadDecoder(PayloadDecoder.ZERO_COPY)
        .acceptor((setup, sendingSocket) -> acceptRSocket(generator, sendingSocket))
        .bind(WebsocketServerTransport.create(this.properties.getWebsocketPort()))
        .doOnError(t -> {
          Counter.builder("prometheus.proxy.connection.error")
              .tag("exception", t.getClass().getName())
              .tag("transport", "Websocket")
              .register(meterRegistry)
              .increment();
        })
        .subscribe();
  }

  private Mono<RSocket> acceptRSocket(KeyPairGenerator generator, RSocket sendingSocket) {
    // respond with Mono.error(..) to
    RSocket metricsInterceptedSendingSocket = metricsInterceptor.apply(sendingSocket);

    ConnectionState connectionState = new ConnectionState(generator.generateKeyPair());
    scrapableApps.put(metricsInterceptedSendingSocket, connectionState);

    // a key to be used by the client to push metrics as it's dying if this happens before the first scrape
    //noinspection CallingSubscribeInNonBlockingScope
    metricsInterceptedSendingSocket.fireAndForget(connectionState.createKeyPayload()).subscribe();

    return Mono.just(new RSocket() {
      @Override
      public Mono<Payload> requestResponse(Payload payload) {
        try {
          connectionState.setDyingPush(connectionState.receiveScrapePayload(payload, null));
        } catch (Throwable t) {
          t.printStackTrace();
        }
        return Mono.just(EmptyPayload.INSTANCE);
      }

      @Override
      public Mono<Void> fireAndForget(Payload payload) {
        try {
          connectionState.setDyingPush(connectionState.receiveScrapePayload(payload, null));
        } catch (Throwable t) {
          t.printStackTrace();
        }
        return Mono.empty();
      }
    });
  }

  @GetMapping(value = "/metrics/proxy", produces = "text/plain")
  public Mono<String> proxyMetrics() {
    return Mono.just(meterRegistry.scrape());
  }

  @GetMapping(value = "/metrics/connected", produces = "text/plain")
  public Mono<String> prometheus() {
    return Flux
        .fromIterable(scrapableApps.entrySet())
        .flatMap(socketAndState -> {
          ConnectionState connectionState = socketAndState.getValue();
          RSocket rsocket = socketAndState.getKey();
          Timer.Sample sample = Timer.start();
          return rsocket
              .requestResponse(connectionState.createKeyPayload())
              .map(payload -> connectionState.receiveScrapePayload(payload, sample))
              .onErrorResume(throwable -> {
                scrapableApps.remove(rsocket);

                if (throwable instanceof ClosedChannelException) {
                  sample.stop(scrapeTimerClosed);
                } else {
                  sample.stop(meterRegistry.timer("prometheus.proxy.scrape",
                      "outcome", "error",
                      "exception", throwable.getMessage()));
                }

                return connectionState.getDyingPush();
              });
        })
        .collect(Collectors.joining("\n"));
  }

  class ConnectionState {
    private final KeyPair keyPair;

    // the last metrics of a dying application instance
    private String dyingPush;

    ConnectionState(KeyPair keyPair) {
      this.keyPair = keyPair;
    }

    Mono<String> getDyingPush() {
      return Mono.justOrEmpty(dyingPush);
    }

    void setDyingPush(String dyingPush) {
      this.dyingPush = dyingPush;
    }

    String receiveScrapePayload(Payload payload, Timer.Sample timing) {
      try {
        ByteBuf sliceMetadata = payload.sliceMetadata();
        ByteBuf sliceData = payload.sliceData();
        byte[] decrypted = decrypt(keyPair,
            ByteBufUtil.getBytes(sliceMetadata, sliceMetadata.readerIndex(), sliceMetadata.readableBytes(), false),
            ByteBufUtil.getBytes(sliceData, sliceData.readerIndex(), sliceData.readableBytes(), false));

        String uncompressed = Snappy.uncompressString(decrypted);
        scrapePayload.record(uncompressed.length());
        return uncompressed;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        payload.release();
        if (timing != null) {
          timing.stop(scrapeTimerSuccess);
        }
      }
    }

    private byte[] decrypt(KeyPair keyPair, byte[] encryptedKey, byte[] data) {
      try {
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(keyPair.getPrivate().getEncoded()));

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.PRIVATE_KEY, privateKey);
        byte[] decryptedKey = cipher.doFinal(encryptedKey);

        SecretKey originalKey = new SecretKeySpec(decryptedKey, 0, decryptedKey.length, "AES");
        Cipher aesCipher = Cipher.getInstance("AES");
        aesCipher.init(Cipher.DECRYPT_MODE, originalKey);

        return aesCipher.doFinal(data);
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }

    Payload createKeyPayload() {
      return DefaultPayload.create(keyPair.getPublic().getEncoded());
    }
  }
}
