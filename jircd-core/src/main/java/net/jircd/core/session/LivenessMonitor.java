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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * Per-connection keep-alive (FR-039): sends a server-initiated {@code PING} after a configured idle
 * interval, and — if no {@code PONG} arrives within a configured timeout — sends {@code ERROR} and
 * drives the session to {@code CLOSING} via {@link DisconnectCleanup}. Driven by an injectable
 * clock rather than real time so tests are deterministic.
 */
public final class LivenessMonitor {

  private final ClientSession session;
  private final DisconnectCleanup disconnectCleanup;
  private final Duration idleInterval;
  private final Duration timeout;
  private final Clock clock;

  private volatile boolean pingOutstanding;
  private volatile Instant pingSentAt;

  public LivenessMonitor(
      ClientSession session,
      DisconnectCleanup disconnectCleanup,
      Duration idleInterval,
      Duration timeout,
      Clock clock) {
    this.session = session;
    this.disconnectCleanup = disconnectCleanup;
    this.idleInterval = idleInterval;
    this.timeout = timeout;
    this.clock = clock;
  }

  /** Evaluates whether to send a PING or time out the connection, based on the current clock. */
  public void checkNow() {
    Instant now = clock.instant();
    if (pingOutstanding) {
      if (Duration.between(pingSentAt, now).compareTo(timeout) >= 0) {
        timeOut();
      }
      return;
    }
    if (Duration.between(session.lastLivenessAt(), now).compareTo(idleInterval) >= 0) {
      sendPing(now);
    }
  }

  /** Called when this connection's PONG (answering our own PING) is received. */
  public void onPongReceived() {
    pingOutstanding = false;
    session.markAliveAt(clock.instant());
  }

  private void sendPing(Instant now) {
    pingOutstanding = true;
    pingSentAt = now;
    // Borrowed reference to the session's own still-active writer — not this
    // monitor's to close; the session's connection lifecycle owns it.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer != null) {
      writer.enqueueRaw(
          new Message(
              Map.of(), null, Command.PING, "PING", java.util.List.of(session.connectionId())));
    }
  }

  private void timeOut() {
    // Same as sendPing(): a borrowed reference, closed below via
    // disconnectCleanup.cleanup(), not directly by this method.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer != null) {
      writer.enqueueRaw(
          new Message(Map.of(), null, Command.ERROR, "ERROR", java.util.List.of("Ping timeout")));
    }
    disconnectCleanup.cleanup(session, "Ping timeout");
  }
}
