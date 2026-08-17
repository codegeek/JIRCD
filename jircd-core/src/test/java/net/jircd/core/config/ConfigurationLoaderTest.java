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
package net.jircd.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationLoaderTest {

  private static final Set<String> CAPABILITY_IDS =
      Set.of("message-tags", "server-time", "echo-message");
  private static final Set<String> SERVER_EXTENSION_IDS = Set.of("cloak", "admin");

  private ConfigurationLoader loader() {
    return new ConfigurationLoader(CAPABILITY_IDS, SERVER_EXTENSION_IDS);
  }

  private ServerConfiguration load(String yaml) throws ConfigurationException {
    return loader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void loadsDefaultsWhenEmpty() throws Exception {
    ServerConfiguration config = load("{}");
    assertThat(config.nicknameMaxLength()).isEqualTo(9);
    assertThat(config.channelNameMaxLength()).isEqualTo(50);
    assertThat(config.topicMaxLength()).isEqualTo(390);
    assertThat(config.maxModesPerCommand()).isEqualTo(6);
    assertThat(config.operFailureThreshold()).isEqualTo(5);
    assertThat(config.whoMaskEnabled()).isTrue();
  }

  @Test
  void unknownExtensionIdIsRejected() {
    assertThatThrownBy(() -> load("capabilities:\n  nonexistent: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void sectionKindMismatchIsRejected() {
    assertThatThrownBy(() -> load("capabilities:\n  cloak: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("server-extensions:\n  message-tags: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void malformedListenerIsRejected() {
    assertThatThrownBy(() -> load("listeners:\n  - tls: false\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void malformedRateLimitIsRejected() {
    assertThatThrownBy(() -> load("rateLimit:\n  bucketSize: -1\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void dotFreeServerNameIsRejected() {
    assertThatThrownBy(() -> load("serverName: localhost\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void outOfRangeLengthValuesAreRejected() {
    assertThatThrownBy(() -> load("nicknameMaxLength: 0\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("channelNameMaxLength: 401\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("topicMaxLength: -5\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void outOfRangeOperFailureThresholdIsRejected() {
    assertThatThrownBy(() -> load("operFailureThreshold: 0\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("operFailureThreshold: 21\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void plainTextCredentialIsRejected() {
    assertThatThrownBy(
            () ->
                load(
                    "administratorCredentials:\n  - username: root\n    hashedPassword: hunter2\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void validConfigurationParsesCleanly() throws Exception {
    String yaml =
        """
                serverName: irc.example.net
                nicknameMaxLength: 5
                listeners:
                  - port: 6667
                    tls: false
                capabilities:
                  message-tags: enabled
                server-extensions:
                  admin: enabled
                administratorCredentials:
                  - username: root-admin
                    hashedPassword: "$2b$10$abcdefghijklmnopqrstuv"
                """;
    ServerConfiguration config = load(yaml);
    assertThat(config.serverName()).isEqualTo("irc.example.net");
    assertThat(config.nicknameMaxLength()).isEqualTo(5);
    assertThat(config.listeners()).containsExactly(new ServerConfiguration.Listener(6667, false));
    assertThat(config.capabilityStates()).containsEntry("message-tags", true);
    assertThat(config.serverExtensionStates()).containsEntry("admin", true);
    assertThat(config.administratorCredentials()).hasSize(1);
  }
}
