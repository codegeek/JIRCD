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
package net.jircd.capabilities.messagetags;

import java.util.Map;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.OutboundMessage;

/**
 * The {@code message-tags} capability (FR-025): contributes the {@code msgid} tag unconditionally —
 * present on every delivered message for a recipient that has this capability negotiated at all,
 * regardless of whether {@code server-time} is also negotiated (FR-059, data-model.md
 * "OutboundMessage" — {@code messageId}).
 */
public final class MessageTagsExtension implements CapabilityExtension {

  public static final String ID = "message-tags";
  private static final Capability CAPABILITY = new Capability(ID);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ServerContext context) {}

  @Override
  public void stop() {}

  @Override
  public Capability providedCapability() {
    return CAPABILITY;
  }

  @Override
  public Map<String, String> contributeTags(OutboundMessage message) {
    return Map.of("msgid", message.messageId().toString());
  }
}
