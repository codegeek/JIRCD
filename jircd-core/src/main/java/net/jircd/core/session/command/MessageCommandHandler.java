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

import java.util.Set;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;
import net.jircd.protocol.Utf8Validator;

/**
 * {@code PRIVMSG}/{@code NOTICE} — channel and direct messaging (FR-004, FR-005). Builds one shared
 * {@link OutboundMessage} and enqueues it onto every recipient's {@code SessionWriter} — this
 * handler applies no capability-dependent formatting itself; that happens per-recipient at drain
 * time.
 */
public final class MessageCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final boolean notice;

  public MessageCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      boolean notice) {
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.notice = notice;
  }

  @Override
  public void handle(ClientSession session, net.jircd.protocol.Message message) {
    String commandName = notice ? "NOTICE" : "PRIVMSG";
    if (message.params().size() < 2) {
      if (!notice) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_NEEDMOREPARAMS,
            commandName,
            "Not enough parameters");
      }
      return;
    }
    String target = message.params().getFirst();
    String body = message.params().get(1);

    if (!Utf8Validator.isValidUtf8(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      if (!notice) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_UNKNOWNCOMMAND,
            commandName,
            "Invalid UTF-8 in message body");
      }
      return;
    }

    Set<ClientSession> recipients;
    if (target.startsWith("#")) {
      var channel = channelRegistry.lookup(target);
      if (channel.isEmpty() || !passesSendGate(channel.get(), session)) {
        if (!notice) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_CANNOTSENDTOCHAN,
              target,
              "Cannot send to channel");
        }
        return;
      }
      recipients = channel.get().members();
    } else {
      var recipient = nicknameRegistry.lookup(target);
      if (recipient.isEmpty()) {
        if (!notice) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_NOSUCHNICK,
              target,
              "No such nick/channel");
        }
        return;
      }
      recipients = Set.of(recipient.get());
    }

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    OutboundMessage outbound = OutboundMessage.now(presentedForm, commandName, target, body);
    boolean echoToSender =
        extensionRegistry.enabled().stream()
            .filter(net.jircd.core.extension.CapabilityExtension.class::isInstance)
            .map(net.jircd.core.extension.CapabilityExtension.class::cast)
            .anyMatch(capability -> capability.includeSenderInFanOut(session));
    for (ClientSession recipient : recipients) {
      if (recipient == session && !echoToSender) {
        continue; // echo-message (Story 2) decides whether the sender sees its own message
      }
      if (recipient.writer() != null) {
        recipient.writer().enqueue(outbound);
      }
    }
  }

  /**
   * The {@code SEND}-gate check point FR-043 requires: every currently-recognized flag whose {@code
   * gates} includes {@code SEND}, checked independently — {@code members-only} requires membership;
   * {@code moderated} requires operator or voice (FR-045); {@code ban-mask} requires neither the
   * sender's presented nor real identity to match any active ban (FR-062, dual-matched to resist
   * {@code cloak} evasion) — muting a matched sender without removing them from members.
   */
  private boolean passesSendGate(Channel channel, ClientSession session) {
    if (channel.activeModes().contains(CoreChannelModes.MEMBERS_ONLY)
        && !channel.members().contains(session)) {
      return false;
    }
    if (channel.activeModes().contains(CoreChannelModes.MODERATED)
        && !channel.operators().contains(session)
        && !channel.voiced().contains(session)) {
      return false;
    }
    if (!channel.bans().isEmpty()) {
      String presented = PresentedIdentity.presentedForm(session, extensionRegistry);
      String real = Hostmask.format(session.nickname(), session.ident(), session.realHostname());
      for (var ban : channel.bans()) {
        if (NickMask.matches(presented, ban.mask()) || NickMask.matches(real, ban.mask())) {
          return false;
        }
      }
    }
    return true;
  }
}
