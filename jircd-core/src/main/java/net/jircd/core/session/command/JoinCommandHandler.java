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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.ChannelName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;

/**
 * {@code JOIN} — create-or-join (FR-003), with the {@code JOIN}-gate check point FR-043 requires:
 * ban-mask (FR-062) and invite-only (FR-065), each checked independently. This release's actual
 * moderation command handlers roll out in Story 5, so these gates never actually reject anything
 * yet — the mechanism exists now, empty of any way to populate a ban or an invitation.
 *
 * <p>Accepts a comma-separated list of channel names, with an optional matching comma-separated
 * list of keys (RFC1459 §4.2.1, 005-fix-batch-conformance FR-013) — each named channel is processed
 * independently through the same single-channel logic; one channel failing its own check doesn't
 * stop the others in the same command from being processed.
 */
public final class JoinCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier channelNameMaxLength;

  public JoinCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier channelNameMaxLength) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.channelNameMaxLength = channelNameMaxLength;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "JOIN",
          "Not enough parameters");
      return;
    }
    // 005-fix-batch-conformance FR-013 — comma-separated channel (and optional matching
    // comma-separated key) lists are core JOIN grammar (RFC1459 §4.2.1), not an extension. A
    // fewer-keys-than-channels list leaves the remainder keyless. Each named channel is
    // processed independently — one failing its own check doesn't stop the others.
    List<String> names = List.of(message.params().getFirst().split(",", -1));
    for (String name : names) {
      joinOne(session, name);
    }
  }

  private void joinOne(ClientSession session, String name) {
    if (!ChannelName.isValid(name, channelNameMaxLength.getAsInt())) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_BADCHANMASK, name, "Bad Channel Mask");
      return;
    }

    Channel channel = channelRegistry.getOrCreate(name);

    if (!passesBanGate(channel, session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_BANNEDFROMCHAN,
          channel.name(),
          "Cannot join channel (+b)");
      return;
    }
    if (!passesInviteOnlyGate(channel, session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_INVITEONLYCHAN,
          channel.name(),
          "Cannot join channel (+i)");
      return;
    }

    channel.addMember(session);
    session.channelMemberships().add(channel);

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message joinNotification =
        new Message(Map.of(), presentedForm, Command.JOIN, "JOIN", List.of(channel.name()));
    for (ClientSession member : channel.members()) {
      if (member.writer() != null) {
        member.writer().enqueueRaw(joinNotification);
      }
    }

    // 005-fix-batch-conformance FR-014 — the same 332-only shape TopicCommandHandler's own
    // query path already uses (this project tracks no set-at timestamp, so no 333 either).
    if (channel.topic() != null) {
      Replies.send(
          session, serverName.get(), NumericReply.RPL_TOPIC, channel.name(), channel.topic());
    }

    sendNamesReply(session, channel, serverName.get());
  }

  public static void sendNamesReply(ClientSession requester, Channel channel, String serverName) {
    StringBuilder names = new StringBuilder();
    for (ClientSession member : channel.members()) {
      if (!names.isEmpty()) {
        names.append(' ');
      }
      if (channel.operators().contains(member)) {
        names.append('@');
      } else if (channel.voiced().contains(member)) {
        names.append('+');
      }
      names.append(member.nickname());
    }
    String visibility = visibilitySymbol(channel);
    Replies.send(
        requester,
        serverName,
        NumericReply.RPL_NAMREPLY,
        visibility,
        channel.name(),
        names.toString());
    Replies.send(
        requester, serverName, NumericReply.RPL_ENDOFNAMES, channel.name(), "End of /NAMES list");
  }

  private static String visibilitySymbol(Channel channel) {
    if (channel.activeModes().contains(CoreChannelModes.SECRET)) {
      return "@";
    }
    if (channel.activeModes().contains(CoreChannelModes.PRIVATE)) {
      return "*";
    }
    return "=";
  }

  private boolean passesBanGate(Channel channel, ClientSession session) {
    if (channel.bans().isEmpty()) {
      return true;
    }
    String presented = PresentedIdentity.presentedForm(session, extensionRegistry);
    String real = Hostmask.format(session.nickname(), session.ident(), session.realHostname());
    for (var ban : channel.bans()) {
      if (NickMask.matches(presented, ban.mask()) || NickMask.matches(real, ban.mask())) {
        return false;
      }
    }
    return true;
  }

  private boolean passesInviteOnlyGate(Channel channel, ClientSession session) {
    if (!channel.activeModes().contains(CoreChannelModes.INVITE_ONLY)) {
      return true;
    }
    String folded = CaseMapping.fold(session.nickname());
    return channel.invited().remove(folded);
  }
}
