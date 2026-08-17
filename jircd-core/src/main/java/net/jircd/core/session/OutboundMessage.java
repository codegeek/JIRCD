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
import java.util.UUID;

/**
 * One message on its way to one recipient, holding only the parts that are the same for every
 * recipient of a single fan-out (data-model.md "OutboundMessage"). Immutable and
 * capability-agnostic by construction — capability-dependent decoration ({@code
 * message-tags}/{@code server-time}) happens per-recipient at drain time in {@link SessionWriter},
 * never here.
 */
public record OutboundMessage(
    String senderPresentedForm,
    String command,
    String target,
    String body,
    Instant sentAt,
    UUID messageId) {

  public static OutboundMessage now(
      String senderPresentedForm, String command, String target, String body) {
    return new OutboundMessage(
        senderPresentedForm, command, target, body, Instant.now(), UUID.randomUUID());
  }
}
