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

import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;
import net.jircd.protocol.Utf8Validator;

/**
 * {@code QUIT} — usable at any time, including before registration completes (FR-060). Invokes
 * {@link DisconnectCleanup} with the client-supplied reason, or a fixed default if none was given —
 * never a blank reason.
 */
public final class QuitCommandHandler implements CommandHandler {

  private static final String DEFAULT_REASON = "Client Quit";

  private final DisconnectCleanup disconnectCleanup;
  private final Supplier<String> serverName;

  public QuitCommandHandler(DisconnectCleanup disconnectCleanup, Supplier<String> serverName) {
    this.disconnectCleanup = disconnectCleanup;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String reason = message.params().isEmpty() ? null : message.params().getFirst();
    if (reason != null
        && !Utf8Validator.isValidUtf8(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          "QUIT",
          "Invalid UTF-8 in reason");
      return;
    }
    disconnectCleanup.cleanup(session, reason != null ? reason : DEFAULT_REASON);
  }
}
