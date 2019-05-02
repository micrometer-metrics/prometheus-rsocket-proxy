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

import io.rsocket.Closeable;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Modified from https://github.com/CollaborationInEncapsulation/reactive-pacman/blob/master/game-server/src/main/java/org/coinen/reactive/pacman/controller/rsocket/support/ReconnectingRSocket.java
 */
public class ReconnectingRSocket extends BaseSubscriber<RSocket> implements RSocket {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReconnectingRSocket.class);

  private volatile MonoProcessor<RSocket> rSocketMono;
  private static final AtomicReferenceFieldUpdater<ReconnectingRSocket, MonoProcessor> RSOCKET_MONO =
    AtomicReferenceFieldUpdater.newUpdater(ReconnectingRSocket.class, MonoProcessor.class, "rSocketMono");

  public ReconnectingRSocket(Mono<RSocket> rSocketMono, Duration backoff, Duration backoffMax) {
    this.rSocketMono = MonoProcessor.create();

    rSocketMono.retryBackoff(Long.MAX_VALUE, backoff, backoffMax)
      .repeat()
      .subscribe(this);
  }

  @Override
  protected void hookOnSubscribe(Subscription subscription) {
    subscription.request(1);
  }

  @Override
  protected void hookOnNext(RSocket value) {
    LOGGER.info("Connected.");
    value.onClose()
      .subscribe(null, this::reconnect, this::reconnect);
    rSocketMono.onNext(value);
  }

  private void reconnect(Throwable t) {
    LOGGER.error("Error.", t);
    reconnect();
  }

  private void reconnect() {
    LOGGER.info("Reconnecting...");
    request(1);
  }

  @Override
  public Mono<Payload> requestResponse(Payload payload) {
    return Mono
      .defer(() -> {
        MonoProcessor<RSocket> rSocketMono = this.rSocketMono;
        if (rSocketMono.isSuccess()) {
          return rSocketMono.peek()
            .requestResponse(payload)
            .doOnError(throwable -> {
              if (throwable instanceof ClosedChannelException) {
                RSOCKET_MONO.compareAndSet(this, rSocketMono, MonoProcessor.create());
              }
            });
        } else {
          return rSocketMono.flatMap(rSocket ->
            rSocket.requestResponse(payload)
              .doOnError(throwable -> {
                if (throwable instanceof ClosedChannelException) {
                  RSOCKET_MONO.compareAndSet(this, rSocketMono, MonoProcessor.create());
                }
              })
          );
        }
      });
  }

  @Override
  public Mono<Void> fireAndForget(Payload payload) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Flux<Payload> requestStream(Payload payload) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Mono<Void> metadataPush(Payload payload) {
    throw new UnsupportedOperationException();
  }

  @Override
  public double availability() {
    return rSocketMono.isSuccess() ? rSocketMono.peek().availability() : 0;
  }

  @Override
  public void dispose() {
    super.dispose();
    rSocketMono.dispose();
  }

  @Override
  public boolean isDisposed() {
    return super.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    if (rSocketMono.isSuccess()) {
      return rSocketMono.peek().onClose();
    } else {
      return rSocketMono.flatMap(Closeable::onClose);
    }
  }
}
