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

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.jircd.server.JircdServerApplication;

/**
 * Starts a real {@link JircdServerApplication} on ephemeral ports for protocol-level integration
 * tests.
 */
public final class TestServer implements AutoCloseable {

  /** Enables all three Story 2 capabilities — none are enabled by default (research.md). */
  public static final String ALL_CAPABILITIES_ENABLED_YAML =
      """
      capabilities:
        message-tags: enabled
        server-time: enabled
        echo-message: enabled
      """;

  public static final String ADMIN_USERNAME = "root-admin";
  public static final String ADMIN_PASSWORD = "correct horse battery staple";

  /**
   * Enables {@code admin} (off by default, contracts/server-configuration.md) with one real,
   * bcrypt-hashed credential for {@link #ADMIN_USERNAME}/{@link #ADMIN_PASSWORD} — {@code admin}
   * itself MUST be enabled for {@code OPER} etc. to work at all.
   */
  public static String adminEnabledYaml() {
    String hash = BCrypt.withDefaults().hashToString(10, ADMIN_PASSWORD.toCharArray());
    return "server-extensions:\n"
        + "  admin: enabled\n"
        + "administratorCredentials:\n"
        + "  - username: "
        + ADMIN_USERNAME
        + "\n"
        + "    hashedPassword: \""
        + hash
        + "\"\n";
  }

  public final JircdServerApplication application;
  public final Path configPath;

  private TestServer(JircdServerApplication application, Path configPath) {
    this.application = application;
    this.configPath = configPath;
  }

  public static TestServer start() throws Exception {
    return start("");
  }

  public static TestServer start(String extraYaml) throws Exception {
    Path configPath = Files.createTempFile("jircd-test-", ".yaml");
    Files.writeString(configPath, baseYaml() + extraYaml);
    JircdServerApplication application = new JircdServerApplication(configPath);
    application.start();
    return new TestServer(application, configPath);
  }

  /**
   * The {@code serverName}/{@code listeners} preamble every config in this test suite starts with.
   */
  public static String baseYaml() {
    return """
        serverName: test.jircd.local
        listeners:
          - port: 0
            tls: false
          - port: 0
            tls: true
        """;
  }

  public int plaintextPort() {
    return application.plaintextPort();
  }

  public int tlsPort() {
    return application.tlsPort();
  }

  @Override
  public void close() throws IOException {
    application.stop();
  }
}
