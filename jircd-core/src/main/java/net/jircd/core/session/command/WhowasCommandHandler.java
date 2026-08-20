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
import net.jircd.core.session.WhowasHistory;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WHOWAS} — last-known identity lookup for a disconnected nickname
 * (002-extended-irc-commands FR-016 through FR-019), backed by the bounded, global {@link
 * WhowasHistory}. Accepts no count parameter this release — always the single most recent entry.
 */
public final class WhowasCommandHandler implements CommandHandler {

  private final WhowasHistory whowasHistory;
  private final Supplier<String> serverName;

  public WhowasCommandHandler(WhowasHistory whowasHistory, Supplier<String> serverName) {
    this.whowasHistory = whowasHistory;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    if (message.params().isEmpty()) {
      Replies.send(
          session, server, NumericReply.ERR_NEEDMOREPARAMS, "WHOWAS", "Not enough parameters");
      return;
    }
    String nickname = message.params().getFirst();
    var found = whowasHistory.mostRecentFor(nickname);
    if (found.isEmpty()) {
      Replies.send(
          session, server, NumericReply.ERR_WASNOSUCHNICK, nickname, "There was no such nickname");
    } else {
      var entry = found.get();
      // Same FR-038 resolution WHOIS/WHO already use: real hostname for an administrator, the
      // snapshotted presented (cloaked, if it was active) value for everyone else — WHOWAS is
      // not admin-gated like WHOHOST, so it must not leak the real value to an ordinary client.
      String hostname =
          session.isAdministrator() ? entry.realHostname() : entry.presentedHostname();
      Replies.send(
          session,
          server,
          NumericReply.RPL_WHOWASUSER,
          entry.nickname(),
          entry.ident(),
          hostname,
          "*",
          entry.realname());
    }
    Replies.send(session, server, NumericReply.RPL_ENDOFWHOWAS, nickname, "End of WHOWAS");
  }
}
