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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal raw-socket IRC client for protocol-level integration tests — no jircd-protocol
 * dependency, by design.
 */
public final class RawIrcClient implements AutoCloseable {

  private final Socket socket;
  private final BufferedReader reader;
  private final OutputStream out;

  private RawIrcClient(Socket socket) throws IOException {
    this.socket = socket;
    this.reader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    this.out = socket.getOutputStream();
  }

  public static RawIrcClient connectPlaintext(int port) throws IOException {
    return new RawIrcClient(new Socket("localhost", port));
  }

  public static RawIrcClient connectTls(int port) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {new TrustAllTrustManager()}, null);
    return new RawIrcClient(context.getSocketFactory().createSocket("localhost", port));
  }

  public void send(String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  public String readLine() throws IOException {
    return reader.readLine();
  }

  /**
   * Reads lines until one starting with {@code prefix} is found (or the timeout elapses), returning
   * it.
   */
  public String readUntil(String prefix, Duration timeout) throws IOException {
    Instant deadline = Instant.now().plus(timeout);
    socket.setSoTimeout(Math.max(1, (int) timeout.toMillis()));
    while (Instant.now().isBefore(deadline)) {
      String line = reader.readLine();
      if (line == null) {
        throw new IOException("Connection closed while waiting for: " + prefix);
      }
      if (line.contains(prefix)) {
        return line;
      }
    }
    throw new IOException("Timed out waiting for: " + prefix);
  }

  public List<String> readLinesFor(Duration duration) throws IOException {
    List<String> lines = new ArrayList<>();
    socket.setSoTimeout(Math.max(1, (int) duration.toMillis()));
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    } catch (java.net.SocketTimeoutException expected) {
      // expected: we're only reading for a bounded window
    }
    return lines;
  }

  public void registerAndAwaitWelcome(String nickname, String username) throws IOException {
    send("NICK " + nickname);
    send("USER " + username + " 0 * :" + username);
    readUntil(" 001 ", Duration.ofSeconds(5));
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private static final class TrustAllTrustManager implements X509TrustManager {
    @Override
    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

    @Override
    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
      return new java.security.cert.X509Certificate[0];
    }
  }
}
