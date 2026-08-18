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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.BanEntry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.ModeChange;
import net.jircd.protocol.ModeStringParser;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;

/**
 * {@code MODE} (channel form, FR-064): one whole-command operator-privilege check (not per-flag —
 * except the {@code b}-alone bare ban-list query, which needs no privilege at all), then applies an
 * ordered {@link ModeChange} list left-to-right, stopping (but keeping what was already applied —
 * not atomic, not rolled back) at the first unrecognized flag, missing parameter, or unresolvable
 * {@code MEMBER}-kind target; a parameter-consuming flag beyond {@code
 * ServerConfiguration.maxModesPerCommand} stops silently, no reply beyond the echo of what was
 * applied so far.
 */
public final class ModeCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier maxModesPerCommand;

  public ModeCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier maxModesPerCommand) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.maxModesPerCommand = maxModesPerCommand;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "MODE",
          "Not enough parameters");
      return;
    }
    String channelName = message.params().getFirst();
    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          channelName,
          "No such channel");
      return;
    }
    Channel channel = found.get();

    if (message.params().size() < 2) {
      // A bare "MODE #chan" with no flag argument at all is treated the same as an unrecognized
      // flag (T114) — there is no flag character to name in the reply.
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNMODE,
          "*",
          "is unknown mode char to me");
      return;
    }

    String modeStringArg = message.params().get(1);
    if (message.params().size() == 2 && modeStringArg.equals("b")) {
      sendBanList(session, channel);
      return;
    }

    if (!channel.operators().contains(session)) {
      SecurityEventLog.rejectedModerationAction(
          session.connectionId(), "MODE", channelName, "not a channel operator");
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_CHANOPRIVSNEEDED,
          channel.name(),
          "You're not channel operator");
      return;
    }

    applyChanges(session, channel, message);
  }

  private void applyChanges(ClientSession session, Channel channel, Message message) {
    // Only the modestring itself (e.g. "+ov-b") goes through ModeStringParser — the value
    // parameters that follow (nicknames, ban masks) are consumed directly from message.params()
    // below as each parameter-consuming flag is encountered, never re-scanned as mode letters.
    List<ModeChange> changes = ModeStringParser.parse(message.params().get(1));
    Collection<ChannelMode> recognized =
        extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL);

    List<ModeChange> applied = new ArrayList<>();
    List<String> appliedParams = new ArrayList<>();
    int nextParamIndex = 2;
    int parameterConsumingCount = 0;

    for (ModeChange change : changes) {
      ChannelMode mode = resolve(recognized, change.flag());
      if (mode == null) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_UNKNOWNMODE,
            String.valueOf(change.flag()),
            "is unknown mode char to me");
        break;
      }

      if (mode.kind() == ChannelMode.Kind.BOOLEAN) {
        applyBoolean(channel, mode, change);
        applied.add(change);
        continue;
      }

      if (parameterConsumingCount >= maxModesPerCommand.getAsInt()) {
        break; // silent stop — no reply beyond the echo of what was applied so far
      }
      if (nextParamIndex >= message.params().size()) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_NEEDMOREPARAMS,
            "MODE",
            "Not enough parameters");
        break;
      }
      String rawParam = message.params().get(nextParamIndex++);
      parameterConsumingCount++;

      String appliedParam;
      if (mode.kind() == ChannelMode.Kind.MEMBER) {
        var target = channel.findMember(rawParam);
        if (target.isEmpty()) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_USERNOTINCHANNEL,
              rawParam,
              "They aren't on that channel");
          break;
        }
        applyMember(channel, mode, change, target.get());
        appliedParam = target.get().nickname();
      } else {
        // LIST-kind — only ban-mask exists this release.
        String normalized = NickMask.normalize(rawParam);
        if (change.sign() == '+') {
          boolean ok = channel.addBan(new BanEntry(normalized, session.nickname(), Instant.now()));
          if (!ok) {
            Replies.send(
                session,
                serverName.get(),
                NumericReply.ERR_BANLISTFULL,
                channel.name(),
                normalized,
                "Channel ban list is full");
            break;
          }
        } else {
          channel.removeBan(normalized);
        }
        appliedParam = normalized;
      }
      applied.add(change);
      appliedParams.add(appliedParam);
    }

    if (!applied.isEmpty()) {
      broadcastEcho(session, channel, applied, appliedParams);
    }
  }

  private static void applyBoolean(Channel channel, ChannelMode mode, ModeChange change) {
    if (change.sign() == '+') {
      if (mode == CoreChannelModes.PRIVATE) {
        channel.activeModes().remove(CoreChannelModes.SECRET);
      } else if (mode == CoreChannelModes.SECRET) {
        channel.activeModes().remove(CoreChannelModes.PRIVATE);
      }
      channel.activeModes().add(mode);
    } else {
      channel.activeModes().remove(mode);
    }
  }

  private static void applyMember(
      Channel channel, ChannelMode mode, ModeChange change, ClientSession target) {
    Set<ClientSession> set =
        mode == CoreChannelModes.OPERATOR ? channel.operators() : channel.voiced();
    if (change.sign() == '+') {
      set.add(target);
    } else {
      set.remove(target);
    }
  }

  private static ChannelMode resolve(Collection<ChannelMode> recognized, char flag) {
    for (ChannelMode mode : recognized) {
      if (mode.flag() == flag) {
        return mode;
      }
    }
    return null;
  }

  private void sendBanList(ClientSession session, Channel channel) {
    for (BanEntry ban : channel.bans()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.RPL_BANLIST,
          channel.name(),
          ban.mask(),
          ban.setBy(),
          String.valueOf(ban.setAt().getEpochSecond()));
    }
    Replies.send(
        session,
        serverName.get(),
        NumericReply.RPL_ENDOFBANLIST,
        channel.name(),
        "End of channel ban list");
  }

  private void broadcastEcho(
      ClientSession session,
      Channel channel,
      List<ModeChange> applied,
      List<String> appliedParams) {
    List<String> params = new ArrayList<>();
    params.add(channel.name());
    params.add(ModeEcho.format(applied));
    params.addAll(appliedParams);
    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message echo = new Message(Map.of(), presentedForm, Command.MODE, "MODE", params);
    for (ClientSession member : channel.members()) {
      if (member.writer() != null) {
        member.writer().enqueueRaw(echo);
      }
    }
  }
}
