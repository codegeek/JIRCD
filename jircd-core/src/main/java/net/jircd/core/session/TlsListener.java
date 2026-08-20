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

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import net.jircd.core.config.ServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The optional TLS connection listener (FR-018): blocking {@link SSLServerSocket}/{@code SSLSocket}
 * API matching the virtual-thread model (research.md "TLS approach"), not {@code SSLEngine}.
 */
public final class TlsListener implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TlsListener.class);

  private final SSLServerSocket serverSocket;
  private final ConnectionHandler connectionHandler;
  private volatile boolean running = true;

  public TlsListener(ServerConfiguration.Listener listener, ConnectionHandler connectionHandler)
      throws IOException, GeneralSecurityException {
    this.connectionHandler = connectionHandler;
    SSLContext sslContext = TlsSupport.buildServerContext(listener);
    this.serverSocket =
        (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(listener.port());
  }

  public void start() {
    Thread.ofVirtual().name("tls-listener").start(this::acceptLoop);
  }

  public int boundPort() {
    return serverSocket.getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        // Same as PlaintextListener: ownership transfers to ConnectionHandler.
        @SuppressWarnings("PMD.CloseResource")
        var socket = serverSocket.accept();
        connectionHandler.accept(socket);
      } catch (IOException e) {
        if (running) {
          LOG.warn("TLS accept loop error", e);
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    running = false;
    serverSocket.close();
  }
}
