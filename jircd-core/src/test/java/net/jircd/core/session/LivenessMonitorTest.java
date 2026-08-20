/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LivenessMonitorTest {

  private final List<SessionWriter> writersToClose = new ArrayList<>();

  @AfterEach
  void closeWriters() {
    writersToClose.forEach(SessionWriter::close);
    writersToClose.clear();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private ClientSession newSession(MutableClock clock) {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    // Closed in closeWriters() (@AfterEach) via writersToClose — PMD's CloseResource
    // check doesn't track a resource escaping into a field for later cleanup.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer =
        new SessionWriter(session, java.io.OutputStream.nullOutputStream(), TagRenderer.NONE);
    session.attachWriter(writer);
    writersToClose.add(writer);
    // Align the session's liveness timestamp exactly with the fake clock's start —
    // ClientSession's own field initializer otherwise reads the real wall clock, which
    // (however briefly) drifts from an independently-constructed MutableClock.
    session.markAliveAt(clock.instant());
    return session;
  }

  @Test
  void idleBeyondIntervalTriggersAPing() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow();

    assertThat(session.lifecycle().isClosing()).isFalse(); // PING sent, not yet timed out
  }

  @Test
  void pongResetsTheTimer() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow(); // sends PING
    clock.advance(Duration.ofSeconds(10));
    monitor.onPongReceived();

    clock.advance(Duration.ofSeconds(29));
    monitor.checkNow();
    assertThat(session.lifecycle().isClosing()).isFalse();
  }

  @Test
  void noPongWithinTimeoutTransitionsSessionToClosing() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow(); // sends PING

    clock.advance(Duration.ofSeconds(31));
    monitor.checkNow(); // no PONG arrived — times out

    assertThat(session.lifecycle().isClosing()).isTrue();
  }
}
