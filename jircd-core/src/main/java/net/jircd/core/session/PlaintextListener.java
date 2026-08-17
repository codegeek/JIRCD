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
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plaintext connection listener: a {@link ServerSocketChannel} accept loop, one virtual thread per
 * connection (research.md "Networking model").
 */
public final class PlaintextListener implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PlaintextListener.class);

  private final ServerSocketChannel serverChannel;
  private final ConnectionHandler connectionHandler;
  private volatile boolean running = true;

  public PlaintextListener(int port, ConnectionHandler connectionHandler) throws IOException {
    this.connectionHandler = connectionHandler;
    this.serverChannel = ServerSocketChannel.open();
    this.serverChannel.bind(new InetSocketAddress(port));
  }

  public void start() {
    Thread.ofVirtual().name("plaintext-listener").start(this::acceptLoop);
  }

  public int boundPort() {
    return serverChannel.socket().getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        // Ownership of the accepted connection transfers to ConnectionHandler,
        // which closes it (closeQuietly) once that connection ends — not here.
        @SuppressWarnings("PMD.CloseResource")
        var channel = serverChannel.accept();
        connectionHandler.accept(channel.socket());
      } catch (IOException e) {
        if (running) {
          LOG.warn("Plaintext accept loop error", e);
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    running = false;
    serverChannel.close();
  }
}
