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
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.buffer.ByteBufUtil;
import io.rsocket.AbstractRSocket;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.micrometer.MicrometerRSocketInterceptor;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.util.DefaultPayload;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import sun.security.rsa.RSAKeyPairGenerator;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@SpringBootApplication
public class Main {
  public static void main(String[] args) {
    SpringApplication.run(Main.class, args);
  }
}

@RestController
class PrometheusController {
  private AtomicReference<PMap<RSocket, KeyPair>> scrapableApps = new AtomicReference<>(HashTreePMap.empty());

  private final PrometheusMeterRegistry meterRegistry;
  private final Timer scrapeTimerSuccess;
  private final Timer scrapeTimerClosed;
  private final Counter scrapeSocketsClosed;
  private final Timer scrapeTimerError;
  private final DistributionSummary scrapePayload;
  private final MicrometerRSocketInterceptor metricsInterceptor;

  PrometheusController(PrometheusMeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.metricsInterceptor = new MicrometerRSocketInterceptor(meterRegistry);
    meterRegistry.gauge("prometheus.proxy.scrape.active.connections", scrapableApps, apps -> apps.get().size());

    this.scrapeTimerSuccess = Timer.builder("prometheus.proxy.scrape")
      .tag("outcome", "success")
      .publishPercentileHistogram()
      .register(meterRegistry);

    this.scrapeTimerClosed = meterRegistry.timer("prometheus.proxy.scrape", "outcome", "closed");
    this.scrapeTimerError = meterRegistry.timer("prometheus.proxy.scrape", "outcome", "error");
    this.scrapePayload = DistributionSummary.builder("prometheus.proxy.scrape.payload")
      .publishPercentileHistogram()
      .baseUnit("bytes")
      .register(meterRegistry);

    this.scrapeSocketsClosed = meterRegistry.counter("prometheus.proxy.scrape.sockets.closed");

    Flux.interval(Duration.ofSeconds(5))
      .doOnEach(n -> meterRegistry.counter("five.second.tick").increment())
      .subscribe();
  }

  @PostConstruct
  public void connect() {
    RSAKeyPairGenerator generator = new RSAKeyPairGenerator();

    RSocketFactory.receive()
      .frameDecoder(PayloadDecoder.ZERO_COPY)
      .acceptor((setup, sendingSocket) -> {
        scrapableApps.getAndUpdate(apps -> apps.plus(metricsInterceptor.apply(sendingSocket), generator.generateKeyPair()));

        sendingSocket
          .onClose()
          .doOnEach(n -> {
            scrapeSocketsClosed.increment();
            scrapableApps.getAndUpdate(apps -> apps.minus(sendingSocket));
          })
          .subscribe();

        return Mono.just(new AbstractRSocket() {
        });
      })
      .transport(TcpServerTransport.create(7001))
      .start()
      .subscribe();
  }

  @GetMapping(value = "/metrics", produces = "text/plain")
  public Mono<String> prometheus() {
    return Flux
      .fromIterable(scrapableApps.get().entrySet())
      .flatMap(appAndKeyPair -> {
        KeyPair keyPair = appAndKeyPair.getValue();
        RSocket rsocket = appAndKeyPair.getKey();
        Timer.Sample sample = Timer.start();
        return rsocket
          .requestResponse(DefaultPayload.create(
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())))
          .map(payload -> {
            try {
              byte[] decrypted = decrypt(keyPair, ByteBufUtil.getBytes(payload.sliceMetadata()), ByteBufUtil.getBytes(payload.sliceData()));
              ByteArrayInputStream bis = new ByteArrayInputStream(decrypted);
              try (GZIPInputStream gis = new GZIPInputStream(bis);
                   BufferedReader br = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                  sb.append(line);
                }
                String uncompressed = sb.toString();
                scrapePayload.record(uncompressed.length());
                return uncompressed;
              }
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            } finally {
              payload.release();
              sample.stop(scrapeTimerSuccess);
            }
          })
          .onErrorResume(throwable -> {
            if (throwable instanceof ClosedChannelException) {
              scrapableApps.getAndUpdate(apps -> apps.minus(rsocket));
              sample.stop(scrapeTimerClosed);
            } else {
              sample.stop(scrapeTimerError);
            }
            return Mono.empty();
          });
      })
      .concatWithValues(meterRegistry.scrape())
      .collect(Collectors.joining("\n"));
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
}
