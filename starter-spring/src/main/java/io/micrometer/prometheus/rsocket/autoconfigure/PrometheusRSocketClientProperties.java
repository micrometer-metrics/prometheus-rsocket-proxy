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

import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import java.time.Duration;

@ConfigurationProperties("management.prometheus.metrics.export.rsocket")
public class PrometheusRSocketClientProperties {

  /**
   * The host name of the proxy to connect to.
   */
  private String host = "localhost";

  /**
   * The port to make a connection on.
   */
  private int port = 7001;

  /**
   * The maximum number of connection attempts to make.
   */
  private long maxRetries = Long.MAX_VALUE;

  /**
   * The first connection attempt backoff delay to apply, then grow exponentially.
   */
  private Duration firstBackoff = Duration.ofSeconds(10);

  /**
   * The maximum connection attempt delay to apply despite exponential growth.
   */
  private Duration maxBackoff = Duration.ofMinutes(10);

  /**
   * RSocket transport protocol.
   */
  private Transport transport = Transport.TCP;

  /**
   * Whether to use a secured protocol.
   */
  private boolean secure = false;

  public long getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(long maxRetries) {
    this.maxRetries = maxRetries;
  }

  public Duration getFirstBackoff() {
    return firstBackoff;
  }

  public void setFirstBackoff(Duration firstBackoff) {
    this.firstBackoff = firstBackoff;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }

  public void setMaxBackoff(Duration maxBackoff) {
    this.maxBackoff = maxBackoff;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public Transport getTransport() {
    return transport;
  }

  public void setTransport(Transport transport) {
    this.transport = transport;
  }

  public void setSecure(boolean secure) {
    this.secure = secure;
  }

  public boolean isSecure() {
    return secure;
  }

  ClientTransport createClientTransport() {
    final TcpClient tcpClient = TcpClient.create().host(this.host).port(this.port);
    return this.transport.create(this.secure ? tcpClient.secure() : tcpClient);
  }

  /**
   * Choice of transport protocol for the RSocket server.
   */
  enum Transport {

    /**
     * TCP transport protocol.
     */
    TCP {
      @Override
      ClientTransport create(TcpClient tcpClient) {
        return TcpClientTransport.create(tcpClient);
      }
    },

    /**
     * WebSocket transport protocol.
     */
    WEBSOCKET {
      @Override
      ClientTransport create(TcpClient tcpClient) {
        return WebsocketClientTransport.create(HttpClient.from(tcpClient), "/");
      }
    };

    abstract ClientTransport create(TcpClient tcpClient);
  }
}
