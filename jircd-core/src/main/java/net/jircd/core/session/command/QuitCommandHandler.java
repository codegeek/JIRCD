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

import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.protocol.Message;

/**
 * {@code QUIT} — usable at any time, including before registration completes (FR-060). Invokes
 * {@link DisconnectCleanup} with the client-supplied reason, or a fixed default if none was given —
 * never a blank reason.
 */
public final class QuitCommandHandler implements CommandHandler {

  private static final String DEFAULT_REASON = "Client Quit";

  private final DisconnectCleanup disconnectCleanup;

  public QuitCommandHandler(DisconnectCleanup disconnectCleanup) {
    this.disconnectCleanup = disconnectCleanup;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String reason = message.params().isEmpty() ? null : message.params().getFirst();
    disconnectCleanup.cleanup(session, reason != null ? reason : DEFAULT_REASON);
  }
}
