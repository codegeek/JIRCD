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

  private final ExtensionRegistry extensionRegistry;

  public CapabilityTagRenderer(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (var extension : extensionRegistry.enabled()) {
      if (extension instanceof CapabilityExtension capability
          && recipient.negotiatedCapabilities().contains(capability.providedCapability().name())) {
        tags.putAll(capability.contributeTags(message));
      }
    }
    return tags;
  }
}
