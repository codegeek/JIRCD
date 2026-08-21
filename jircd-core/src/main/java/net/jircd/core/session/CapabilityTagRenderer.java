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

import java.util.LinkedHashMap;
import java.util.Map;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;

/**
 * The real {@link TagRenderer}: for each currently-{@code ENABLED} {@link CapabilityExtension},
 * live-checks whether the recipient has it negotiated and, if so, merges in its contributed tags
 * (T092) — never caches the enabled/negotiated set, the same live-check posture {@code
 * CapabilityNegotiator} already has (FR-007/FR-035).
 */
public final class CapabilityTagRenderer implements TagRenderer {

  /**
   * The IRCv3 capability name gating client-tag forwarding — a wire-protocol constant, not an
   * internal implementation detail (the same {@code jircd-core}-can't-depend-on-{@code
   * jircd-capabilities} reasoning {@code TagmsgCommandHandler}'s own copy of this constant
   * documents). A recipient with nothing negotiated has no way to parse a tag section at all, so it
   * must never appear on the wire for them (005-fix-batch-conformance FR-010, oragono/Ergo issue
   * 754 regression).
   */
  private static final String MESSAGE_TAGS_CAPABILITY = "message-tags";

  private final ExtensionRegistry extensionRegistry;

  public CapabilityTagRenderer(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    // 005-fix-batch-conformance FR-010 — seed with the sender's own client tags first, so a
    // capability-contributed tag (msgid/time, reserved names) still wins on a key collision, but
    // a client's own tag otherwise survives to the wire instead of being silently dropped. Only
    // `+`-prefixed client-only tags ever forward, to every recipient alike including the sender's
    // own echo: a bare tag (message-tags spec's reserved, non-`+` namespace — e.g. an unrecognized
    // vendor tag) is never a client's to set at all, so it's dropped rather than round-tripped.
    // Either way, nothing is forwarded unless the recipient negotiated message-tags itself — a
    // recipient with nothing negotiated has no way to parse a tag section on the wire.
    Map<String, String> tags = new LinkedHashMap<>();
    if (recipient.negotiatedCapabilities().contains(MESSAGE_TAGS_CAPABILITY)) {
      for (var entry : message.clientTags().entrySet()) {
        if (entry.getKey().startsWith("+")) {
          tags.put(entry.getKey(), entry.getValue());
        }
      }
    }
    for (var extension : extensionRegistry.enabled()) {
      if (extension instanceof CapabilityExtension capability
          && recipient.negotiatedCapabilities().contains(capability.providedCapability().name())) {
        tags.putAll(capability.contributeTags(message));
      }
    }
    return tags;
  }
}
