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
package net.jircd.serverextensions.admin;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.util.List;
import net.jircd.core.config.ServerConfiguration;

/**
 * Verifies {@code OPER} credentials against {@code ServerConfiguration.administratorCredentials}
 * (FR-034), the same salted, computationally-expensive hashing approach FR-024's account
 * credentials use (research.md "Administrator credential storage") — never plain text.
 */
public final class AdminCredentialVerifier {

  private AdminCredentialVerifier() {}

  public static boolean verify(
      List<ServerConfiguration.AdministratorCredential> credentials,
      String username,
      String password) {
    for (ServerConfiguration.AdministratorCredential credential : credentials) {
      if (credential.username().equals(username)) {
        try {
          return BCrypt.verifyer()
              .verify(password.toCharArray(), credential.hashedPassword())
              .verified;
        } catch (IllegalArgumentException unsupportedHashFormat) {
          return false;
        }
      }
    }
    return false;
  }
}
