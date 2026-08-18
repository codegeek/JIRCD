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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class Story6OperTest {

  @Test
  void correctCredentialsGrantOperatorPrivilege() throws Exception {
    try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      assertThat(alice.readUntil("381", Duration.ofSeconds(5))).contains("381");
    }
  }

  @Test
  void incorrectCredentialsAreRejectedWithoutGrantingPrivilege() throws Exception {
    try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
      assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");

      alice.send("WHOHOST alice");
      assertThat(alice.readUntil("481", Duration.ofSeconds(5))).contains("481");
    }
  }

  @Test
  void thirdConsecutiveFailedAttemptDisconnects() throws Exception {
    try (TestServer server =
            TestServer.start(TestServer.adminEnabledYaml() + "operFailureThreshold: 3\n");
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      for (int attempt = 1; attempt <= 2; attempt++) {
        alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
        assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");
      }

      alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
      assertThatThrownBy(() -> alice.readUntil("_never_matches_", Duration.ofSeconds(5)))
          .hasMessageContaining("Connection closed");
    }
  }
}
