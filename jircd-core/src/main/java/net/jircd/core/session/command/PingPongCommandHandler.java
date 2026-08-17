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
package net.jircd.core.session.command;

import java.util.Map;
import net.jircd.core.session.LivenessMonitor;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * {@code PING}/{@code PONG} handlers (FR-039): a client-initiated {@code PING} gets an immediate
 * {@code PONG} reply on any connection, registered or not; a client's {@code PONG} resets its
 * {@link LivenessMonitor} timer.
 */
public final class PingPongCommandHandler {

  private PingPongCommandHandler() {}

  public static CommandHandler ping() {
    return (session, message) -> {
      String token =
          message.params().isEmpty() ? session.connectionId() : message.params().getFirst();
      if (session.writer() != null) {
        session
            .writer()
            .enqueueRaw(
                new Message(Map.of(), null, Command.PONG, "PONG", java.util.List.of(token)));
      }
    };
  }

  public static CommandHandler pong() {
    return (session, message) -> {
      session.markAlive();
      LivenessMonitor monitor = session.livenessMonitor();
      if (monitor != null) {
        monitor.onPongReceived();
      }
    };
  }
}
