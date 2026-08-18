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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code INVITE} — records a per-nickname invitation on a channel (FR-065), pairing with the {@code
 * invite-only} flag ({@code ModeCommandHandler}/{@code JoinCommandHandler}'s {@code JOIN}-gate).
 * The invitation itself works regardless of whether {@code invite-only} is active; only *issuing*
 * one while it's active requires the sender to be an operator.
 */
public final class InviteCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public InviteCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().size() < 2) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "INVITE",
          "Not enough parameters");
      return;
    }
    String targetNickname = message.params().getFirst();
    String channelName = message.params().get(1);

    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty() || !found.get().members().contains(session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOTONCHANNEL,
          channelName,
          "You're not on that channel");
      return;
    }
    Channel channel = found.get();

    var target = nicknameRegistry.lookup(targetNickname);
    if (target.isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHNICK,
          targetNickname,
          "No such nick/channel");
      return;
    }
    if (channel.members().contains(target.get())) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_USERONCHANNEL,
          targetNickname,
          channel.name(),
          "is already on channel");
      return;
    }
    if (channel.activeModes().contains(CoreChannelModes.INVITE_ONLY)
        && !channel.operators().contains(session)) {
      SecurityEventLog.rejectedModerationAction(
          session.connectionId(), "INVITE", channelName, "not a channel operator");
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_CHANOPRIVSNEEDED,
          channel.name(),
          "You're not channel operator");
      return;
    }

    channel.invited().add(CaseMapping.fold(target.get().nickname()));
    Replies.send(
        session,
        serverName.get(),
        NumericReply.RPL_INVITING,
        target.get().nickname(),
        channel.name());

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message inviteMessage =
        new Message(
            Map.of(),
            presentedForm,
            Command.INVITE,
            "INVITE",
            List.of(target.get().nickname(), channel.name()));
    if (target.get().writer() != null) {
      target.get().writer().enqueueRaw(inviteMessage);
    }
  }
}
