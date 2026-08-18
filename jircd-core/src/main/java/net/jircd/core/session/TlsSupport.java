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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link SSLContext} the optional TLS listener uses (FR-018). Certificate management is
 * a deployment concern, not a specification one (spec.md Assumptions) — this loads an
 * administrator-supplied PKCS12 keystore if configured ({@code jircd.tls.keystore}/{@code
 * jircd.tls.keystorePassword} system properties), or generates a self-signed one via the JDK's own
 * {@code keytool} for a usable zero-configuration default.
 */
public final class TlsSupport {

  private static final Logger LOG = LoggerFactory.getLogger(TlsSupport.class);

  private TlsSupport() {}

  public static SSLContext buildServerContext() throws GeneralSecurityException, IOException {
    String configuredPath = System.getProperty("jircd.tls.keystore");
    String password = System.getProperty("jircd.tls.keystorePassword", "changeit");

    Path keystorePath =
        configuredPath != null ? Path.of(configuredPath) : selfSignedKeystorePath(password);

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(keystorePath.toFile())) {
      keyStore.load(in, password.toCharArray());
    }

    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password.toCharArray());

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagerFactory.getKeyManagers(), null, null);
    return context;
  }

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIR =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  private static Path selfSignedKeystorePath(String password) throws IOException {
    // The shared system temp directory is world-readable/writable on most platforms (CWE-379),
    // and keytool controls the keystore file's own permissions once it creates it — so instead,
    // this file holding a TLS private key lives in its own owner-only-traversable directory,
    // which keeps it inaccessible to other users regardless of the file's own mode bits.
    Path dir = Files.createTempDirectory("jircd-tls-", OWNER_ONLY_DIR);
    dir.toFile().deleteOnExit();
    Path path = dir.resolve("keystore.p12");
    path.toFile().deleteOnExit();
    String javaHome = System.getProperty("java.home");
    String keytool = Path.of(javaHome, "bin", "keytool").toString();
    ProcessBuilder pb =
        new ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias",
            "jircd",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
            "-keystore",
            path.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            password,
            "-keypass",
            password,
            "-dname",
            "CN=jircd-self-signed");
    // No stdout/stderr piping — nothing reads it, and a Process's stream is a Closeable
    // resource we'd otherwise have to manage; DISCARD avoids creating the pipe at all.
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    try {
      Process process = pb.start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException("keytool exited with code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while generating self-signed keystore", e);
    }
    LOG.info("Generated a self-signed TLS keystore at {} (no jircd.tls.keystore configured)", path);
    return path;
  }
}
