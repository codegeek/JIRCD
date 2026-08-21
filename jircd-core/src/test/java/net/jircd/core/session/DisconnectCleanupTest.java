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

import static org.assertj.core.api.Assertions.assertThat;

import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;

/**
 * Two disconnect triggers can race for the same session (a client-sent {@code QUIT} racing an
 * abrupt TCP-level close of the same socket, surfaced by a flaky CI failure recording three {@code
 * WhowasEntry} values for only two disconnects) — {@link DisconnectCleanup#cleanup} MUST run its
 * full body exactly once per session, no matter how many times it's called.
 */
class DisconnectCleanupTest {

  private ClientSession registeredSession() {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.setNickname("erin");
    session.setIdent("erin");
    session.setRealname("erin");
    return session;
  }

  @Test
  void secondCleanupCallForTheSameSessionIsANoOp() {
    WhowasHistory whowasHistory = new WhowasHistory(() -> 100);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(), new ChannelRegistry(), whowasHistory, new ExtensionRegistry());
    ClientSession session = registeredSession();

    cleanup.cleanup(session, "first trigger");
    cleanup.cleanup(session, "second, racing trigger");

    assertThat(whowasHistory.mostRecentNFor("erin", 0)).hasSize(1);
  }

  @Test
  void firstCleanupCallClaimsTheClosingTransition() {
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new ExtensionRegistry());
    ClientSession session = registeredSession();

    cleanup.cleanup(session, "reason");

    assertThat(session.lifecycle().isClosing()).isTrue();
  }
}
