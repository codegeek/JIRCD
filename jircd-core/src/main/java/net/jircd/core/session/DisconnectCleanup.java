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

import java.util.HashSet;
import java.util.Set;
import net.jircd.protocol.Command;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one path every disconnect trigger funnels through — a client-sent {@code QUIT}, a keep-alive
 * timeout, an {@code OPER} lockout, and an abrupt TCP-level loss all call this rather than
 * duplicating cleanup logic per trigger (FR-017, research.md "Voluntary disconnect and quit
 * reasons"). Removes channel membership (including operator/voice status, data-model.md {@code
 * Channel} validation rules) and notifies every affected channel — exactly once per distinct
 * neighbor, not once per shared channel.
 */
public final class DisconnectCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(DisconnectCleanup.class);

  private final NicknameRegistry nicknameRegistry;
  private final ChannelRegistry channelRegistry;

  public DisconnectCleanup(NicknameRegistry nicknameRegistry, ChannelRegistry channelRegistry) {
    this.nicknameRegistry = nicknameRegistry;
    this.channelRegistry = channelRegistry;
  }

  public void cleanup(ClientSession session, String reason) {
    Set<ClientSession> neighbors = new HashSet<>();
    for (Channel channel : Set.copyOf(session.channelMemberships())) {
      channel.removeMember(session);
      neighbors.addAll(channel.members());
      session.channelMemberships().remove(channel);
      channelRegistry.removeIfEmpty(channel);
    }
    neighbors.remove(session);

    if (session.nickname() != null && session.ident() != null && session.realHostname() != null) {
      String prefix = Hostmask.format(session.nickname(), session.ident(), session.realHostname());
      Message quit =
          new Message(java.util.Map.of(), prefix, Command.QUIT, "QUIT", java.util.List.of(reason));
      for (ClientSession neighbor : neighbors) {
        // Borrowed reference to the neighbor's own still-active writer — owned and
        // closed by that neighbor's own connection lifecycle, never here.
        @SuppressWarnings("PMD.CloseResource")
        SessionWriter writer = neighbor.writer();
        if (writer != null) {
          writer.enqueueRaw(quit);
        }
      }
    }

    if (session.nickname() != null) {
      nicknameRegistry.release(session.nickname(), session);
    }

    session.lifecycle().close();
    // This session's own writer — this is exactly where it gets closed; PMD's
    // CloseResource heuristic doesn't credit a conditional close() here.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer != null) {
      writer.close();
    }
    LOG.debug("Cleaned up connection {} ({})", session.connectionId(), reason);
  }
}
