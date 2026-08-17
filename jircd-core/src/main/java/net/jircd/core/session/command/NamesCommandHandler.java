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
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code NAMES} — current membership list of a channel, regardless of the requester's own
 * membership (FR-041).
 */
public final class NamesCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final Supplier<String> serverName;

  public NamesCommandHandler(ChannelRegistry channelRegistry, Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "NAMES",
          "Not enough parameters");
      return;
    }
    var found = channelRegistry.lookup(message.params().getFirst());
    if (found.isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          message.params().getFirst(),
          "No such channel");
      return;
    }
    JoinCommandHandler.sendNamesReply(session, found.get(), serverName.get());
  }
}
