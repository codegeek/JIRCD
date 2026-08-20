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
 * {@code LUSERS} — server-wide connected-client and active-channel counts only
 * (002-extended-irc-commands FR-003) — this server has no operator-vs-non-operator or
 * unknown-connection breakdown to report, so the fuller RFC 2812 numeric set ({@code
 * RPL_LUSEROP}/{@code RPL_LUSERUNKNOWN}/{@code RPL_LUSERME}) is not sent.
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
    int channelCount = channelRegistry.all().size();
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCLIENT,
        "There are " + clientCount + " users and " + invisibleCount + " invisible on 1 servers");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCHANNELS,
        String.valueOf(channelCount),
        "channels formed");
  }
}
