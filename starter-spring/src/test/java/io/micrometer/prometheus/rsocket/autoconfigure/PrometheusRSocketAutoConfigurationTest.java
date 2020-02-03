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

package io.micrometer.prometheus.rsocket.autoconfigure;

import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.ServerTransport;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.transport.netty.server.WebsocketServerTransport;
import org.junit.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.SocketUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class PrometheusRSocketAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class, PrometheusRSocketAutoConfiguration.class));

  private Mono<CloseableChannel> startServer(ServerTransport<CloseableChannel> serverTransport, CountDownLatch latch) {
    return RSocketFactory.receive()
        .acceptor((setup, sendingSocket) -> {
          latch.countDown();
          final AbstractRSocket rsocket = new AbstractRSocket() {

            @Override
            public Mono<Void> fireAndForget(Payload payload) {
              return Mono.empty();
            }
          };
          return Mono.just(rsocket);
        })
        .transport(serverTransport)
        .start();
  }

  @Test
  public void prometheusRSocketClientTcp() {
    int port = SocketUtils.findAvailableTcpPort();
    final CountDownLatch latch = new CountDownLatch(1);
    this.startServer(TcpServerTransport.create(port), latch)
        .block();
    this.contextRunner.withPropertyValues("management.metrics.export.prometheus.rsocket.port=" + port,
        "management.metrics.export.prometheus.rsocket.transport=tcp")
        .run(context -> {
          latch.await(5, TimeUnit.SECONDS);
          assertThat(latch.getCount()).isEqualTo(0);
        });
  }

  @Test
  public void prometheusRSocketClientWebsocket() {
    int port = SocketUtils.findAvailableTcpPort();
    final CountDownLatch latch = new CountDownLatch(1);
    this.startServer(WebsocketServerTransport.create(port), latch)
        .block();
    this.contextRunner.withPropertyValues("management.metrics.export.prometheus.rsocket.port=" + port,
        "management.metrics.export.prometheus.rsocket.transport=websocket")
        .run(context -> {
          latch.await(5, TimeUnit.SECONDS);
          assertThat(latch.getCount()).isEqualTo(0);
        });
  }
}
