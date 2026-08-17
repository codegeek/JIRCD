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
import net.jircd.core.session.NicknameRegistry;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code NICK} — claims/changes a nickname (FR-001/FR-002). Format ({@code 432}) and uniqueness
 * ({@code 433}) are independent, sequential checks; {@code 431} for a missing argument. Any of
 * these sent before this session has successfully claimed a nickname address {@code *} (FR-053,
 * {@link Replies}).
 */
public final class NickCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final java.util.function.Supplier<String> serverName;
  private final java.util.function.IntSupplier nicknameMaxLength;
  private final RegistrationCompletion registrationCompletion;

  public NickCommandHandler(
      NicknameRegistry nicknameRegistry,
      java.util.function.Supplier<String> serverName,
      java.util.function.IntSupplier nicknameMaxLength,
      RegistrationCompletion registrationCompletion) {
    this.nicknameRegistry = nicknameRegistry;
    this.serverName = serverName;
    this.nicknameMaxLength = nicknameMaxLength;
    this.registrationCompletion = registrationCompletion;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty() || message.params().getFirst().isEmpty()) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_NONICKNAMEGIVEN, "No nickname given");
      return;
    }
    String requested = message.params().getFirst();

    if (!Hostmask.isValidNickname(requested, nicknameMaxLength.getAsInt())) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_ERRONEUSNICKNAME,
          requested,
          "Erroneous nickname");
      return;
    }

    if (!nicknameRegistry.claim(requested, session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NICKNAMEINUSE,
          requested,
          "Nickname is already in use");
      return;
    }

    String previous = session.nickname();
    if (previous != null && !previous.equals(requested)) {
      nicknameRegistry.release(previous, session);
    }
    session.setNickname(requested);
    registrationCompletion.tryComplete(session);
  }
}
