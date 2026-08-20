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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One message on its way to one recipient, holding only the parts that are the same for every
 * recipient of a single fan-out (data-model.md "OutboundMessage"). Immutable and
 * capability-agnostic by construction — capability-dependent decoration ({@code
 * message-tags}/{@code server-time}) happens per-recipient at drain time in {@link SessionWriter},
 * never here.
 *
 * @param clientTags the sender's own tags (e.g. a {@code TAGMSG}'s vendor tags,
 *     002-extended-irc-commands FR-020), empty for every other command — kept separate from
 *     capability-contributed tags ({@code msgid}/{@code time}) so a client-supplied tag can never
 *     clobber a server-reserved one (merge order in {@link SessionWriter})
 */
public record OutboundMessage(
    String senderPresentedForm,
    String command,
    String target,
    String body,
    Map<String, String> clientTags,
    Instant sentAt,
    UUID messageId) {

  public OutboundMessage {
    clientTags = Map.copyOf(clientTags);
  }

  public static OutboundMessage now(
      String senderPresentedForm, String command, String target, String body) {
    return new OutboundMessage(
        senderPresentedForm, command, target, body, Map.of(), Instant.now(), UUID.randomUUID());
  }

  public static OutboundMessage now(
      String senderPresentedForm,
      String command,
      String target,
      String body,
      Map<String, String> clientTags) {
    return new OutboundMessage(
        senderPresentedForm, command, target, body, clientTags, Instant.now(), UUID.randomUUID());
  }
}
