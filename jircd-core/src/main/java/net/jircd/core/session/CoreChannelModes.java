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
package net.jircd.core.session;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * This release's seven core, always-present channel-mode flags (contracts/irc-protocol-commands.md
 * "Full Channel Mode Catalog") — never gated by FR-011 toggling (FR-036). Command-level enforcement
 * for each rolls out story-by-story, but the catalog itself — what {@code RPL_ISUPPORT}'s {@code
 * CHANMODES}/{@code PREFIX} and {@code 004}'s mode-letter list advertise — is complete from
 * registration onward.
 */
public final class CoreChannelModes {

  public static final ChannelMode MEMBERS_ONLY =
      new ChannelMode(
          "members-only", 'n', ChannelMode.Kind.BOOLEAN, Set.of(GateAction.SEND), ChannelMode.CORE);
  public static final ChannelMode MODERATED =
      new ChannelMode(
          "moderated", 'm', ChannelMode.Kind.BOOLEAN, Set.of(GateAction.SEND), ChannelMode.CORE);
  public static final ChannelMode PRIVATE =
      new ChannelMode(
          "private", 'p', ChannelMode.Kind.BOOLEAN, Set.of(GateAction.DISCOVER), ChannelMode.CORE);
  public static final ChannelMode SECRET =
      new ChannelMode(
          "secret", 's', ChannelMode.Kind.BOOLEAN, Set.of(GateAction.DISCOVER), ChannelMode.CORE);
  public static final ChannelMode INVITE_ONLY =
      new ChannelMode(
          "invite-only", 'i', ChannelMode.Kind.BOOLEAN, Set.of(GateAction.JOIN), ChannelMode.CORE);
  public static final ChannelMode BAN_MASK =
      new ChannelMode(
          "ban-mask",
          'b',
          ChannelMode.Kind.LIST,
          Set.of(GateAction.SEND, GateAction.JOIN),
          ChannelMode.CORE);
  public static final ChannelMode OPERATOR =
      new ChannelMode("operator", 'o', ChannelMode.Kind.MEMBER, Set.of(), ChannelMode.CORE);
  public static final ChannelMode VOICE =
      new ChannelMode("voice", 'v', ChannelMode.Kind.MEMBER, Set.of(), ChannelMode.CORE);

  /** Order matters for {@code CHANMODES}/{@code PREFIX} formatting (SupportedFeatures). */
  public static final Set<ChannelMode> ALL =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Set.of(
                  BAN_MASK,
                  INVITE_ONLY,
                  MODERATED,
                  MEMBERS_ONLY,
                  PRIVATE,
                  SECRET,
                  OPERATOR,
                  VOICE)));

  private CoreChannelModes() {}
}
