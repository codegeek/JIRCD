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
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code LUSERS} — server-wide connected-client, connected-operator, and active-channel counts
 * (002-extended-irc-commands FR-003; operator count and the closing {@code RPL_LUSERME} added by
 * 006-complete-core-protocol FR-011/FR-012). {@code RPL_LUSERUNKNOWN} stays unsent — this server
 * still has no notion of a connection that hasn't yet become a full {@code ClientSession}, the one
 * part of the original blocking reasoning that still holds.
 */
public final class LusersCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final ChannelRegistry channelRegistry;
  private final Supplier<String> serverName;

  public LusersCommandHandler(
      NicknameRegistry nicknameRegistry,
      ChannelRegistry channelRegistry,
      Supplier<String> serverName) {
    this.nicknameRegistry = nicknameRegistry;
    this.channelRegistry = channelRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    int clientCount = nicknameRegistry.all().size();
    long invisibleCount =
        nicknameRegistry.all().stream()
            .filter(s -> s.userModes().contains(UserMode.INVISIBLE))
            .count();
    long operatorCount =
        nicknameRegistry.all().stream()
            .filter(s -> s.userModes().contains(UserMode.OPERATOR))
            .count();
    int channelCount = channelRegistry.all().size();
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCLIENT,
        "There are " + clientCount + " users and " + invisibleCount + " invisible on 1 servers");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSEROP,
        String.valueOf(operatorCount),
        "operator(s) online");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCHANNELS,
        String.valueOf(channelCount),
        "channels formed");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERME,
        "I have " + clientCount + " clients and 1 servers");
  }
}
