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
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.AbstractRSocket;
import io.rsocket.Closeable;
import io.rsocket.Payload;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  public static Flux<Void> connect(PrometheusMeterRegistry registry, String bindAddress, int port) {
    Counter attempts = Counter.builder("prometheus.connection.attempts")
      .description("Attempts at making an outbound RSocket connection to the Prometheus proxy")
      .baseUnit("attempts")
      .register(registry);

    attempts.increment();

    return RSocketFactory.connect()
      .acceptor(
        rSocket ->
          new AbstractRSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(128);
                SecretKey secKey = generator.generateKey();

                Cipher aesCipher = Cipher.getInstance("AES");
                aesCipher.init(Cipher.ENCRYPT_MODE, secKey);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try(GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                  gos.write(registry.scrape().getBytes());
                }

                byte[] encryptedMetrics = aesCipher.doFinal(bos.toByteArray());

                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(payload.getDataUtf8()));
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PublicKey publicKey = keyFactory.generatePublic(keySpec);

                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                cipher.init(Cipher.PUBLIC_KEY, publicKey);
                byte[] encryptedPublicKey = cipher.doFinal(secKey.getEncoded());

                return Mono.just(DefaultPayload.create(encryptedMetrics, encryptedPublicKey));
              } catch (Throwable e) {
                throw new IllegalArgumentException(e);
              }
            }
          })
      .transport(TcpClientTransport.create(bindAddress, port))
      .start()
      .flatMap(Closeable::onClose)
      .repeat(() -> {
        attempts.increment();
        return true;
      });
  }
}
