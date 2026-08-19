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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * FR-054: a message containing an invalid UTF-8 byte sequence in any of `PRIVMSG`/`NOTICE` bodies,
 * channel topics, realnames, or `QUIT`/`PART` reasons is rejected as malformed (FR-015), the same
 * way any other malformed protocol message is — never silently passed through, mistranscoded, or
 * partially accepted.
 */
class Utf8ValidationTest {

  /** A single {@code 0xFF} byte is never valid standalone UTF-8, regardless of context. */
  private static byte[] rawLine(String asciiPrefix, String asciiSuffix) {
    byte[] prefixBytes = asciiPrefix.getBytes(StandardCharsets.UTF_8);
    byte[] suffixBytes = asciiSuffix.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[prefixBytes.length + 1 + suffixBytes.length];
    System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
    result[prefixBytes.length] = (byte) 0xFF;
    System.arraycopy(suffixBytes, 0, result, prefixBytes.length + 1, suffixBytes.length);
    return result;
  }

  @Test
  void invalidUtf8InPrivmsgBodyIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));

      alice.sendRawBytes(rawLine("PRIVMSG #lobby :bad", "text"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }

  @Test
  void invalidUtf8InNoticeBodyIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));

      alice.sendRawBytes(rawLine("NOTICE #lobby :bad", "text"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }

  @Test
  void invalidUtf8InTopicIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice"); // operator
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));

      alice.sendRawBytes(rawLine("TOPIC #lobby :bad", "topic"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }

  @Test
  void invalidUtf8InUserRealnameIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.send("NICK alice");
      alice.sendRawBytes(rawLine("USER alice 0 * :bad", "name"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }

  @Test
  void invalidUtf8InQuitReasonIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.sendRawBytes(rawLine("QUIT :bad", "reason"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }

  @Test
  void invalidUtf8InPartReasonIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));

      alice.sendRawBytes(rawLine("PART #lobby :bad", "reason"));
      assertThat(alice.readUntil("421", Duration.ofSeconds(5))).contains("421");
    }
  }
}
