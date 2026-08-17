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
package net.jircd.core.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityNegotiationGrammar;

/**
 * The {@code CAP LS}/{@code REQ}/{@code ACK}/{@code NAK}/{@code END} state machine
 * (FR-006/FR-007/FR-008). Gates registration completion while a negotiation is in progress. Always
 * available — never one of the toggleable extensions itself (FR-035); the offered/accepted list is
 * sourced live from {@link ExtensionRegistry}, never cached (FR-007).
 */
public final class CapabilityNegotiator {

  private final ExtensionRegistry extensionRegistry;

  public CapabilityNegotiator(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  public void beginNegotiation(ClientSession session) {
    session.setNegotiating(true);
  }

  public List<String> currentlyOfferedCapabilityNames() {
    return extensionRegistry.offeredCapabilities().stream().map(Capability::name).toList();
  }

  /** Which requested capabilities were accepted (currently offered) vs. declined (FR-007). */
  public record NegotiationResult(Set<String> accepted, Set<String> declined) {}

  /** Requests the given capabilities, recording each accepted one on the session. */
  public NegotiationResult request(ClientSession session, List<String> requested) {
    Set<String> offered = Set.copyOf(currentlyOfferedCapabilityNames());
    Set<String> accepted = new LinkedHashSet<>();
    Set<String> declined = new LinkedHashSet<>();
    for (String name : requested) {
      if (offered.contains(name)) {
        accepted.add(name);
        session.negotiatedCapabilities().add(name);
      } else {
        declined.add(name);
      }
    }
    return new NegotiationResult(accepted, declined);
  }

  public void endNegotiation(ClientSession session) {
    session.setNegotiating(false);
  }

  /**
   * Whether {@code CAP} params name a recognized subcommand at all — for a generic
   * malformed-command fallback.
   */
  public static boolean isRecognizedSubcommand(net.jircd.protocol.Message message) {
    return CapabilityNegotiationGrammar.parseSubcommand(message) != null;
  }
}
