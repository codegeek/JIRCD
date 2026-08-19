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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ConnectionAdmissionExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.MalformedMessageException;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import net.jircd.protocol.NumericReply;
import net.jircd.protocol.Utf8Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-connection command dispatch loop (FR-015): blocking read → parse via {@code
 * jircd-protocol} → route to a registered handler, matched case-insensitively. Before creating a
 * {@link ClientSession} at all, consults the {@code connection-admission} extension point (FR-066);
 * with nothing claiming it this release, every connection is admitted.
 *
 * <p>Reads raw bytes and validates each line's UTF-8 well-formedness (FR-054) before ever decoding
 * it to a {@code String} — a lenient, char-stream-based reader (e.g. {@code
 * InputStreamReader}/{@code BufferedReader} over a {@code Charset}) silently substitutes the
 * replacement character for malformed input by default, which would make every downstream per-field
 * {@code Utf8Validator} check in individual command handlers permanently unreachable: by the time a
 * handler sees a {@code String}, any invalid byte sequence has already been silently sanitized
 * away. Validating the whole raw line once, here, is the single source of truth instead.
 */
public final class ConnectionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionHandler.class);

  /**
   * 512 bytes (command+params, CR-LF inclusive) plus up to 4096 bytes for a message-tags section
   * (FR-049).
   */
  private static final int MAX_LINE_LENGTH_BYTES = 512 + 4096;

  /** Commands valid before registration completes (FR-001, FR-060, FR-039, FR-006). */
  private static final java.util.Set<Command> PRE_REGISTRATION_COMMANDS =
      java.util.Set.of(
          Command.NICK, Command.USER, Command.CAP, Command.PING, Command.PONG, Command.QUIT);

  private final ExtensionRegistry extensionRegistry;
  private final DisconnectCleanup disconnectCleanup;
  private final Supplier<String> serverName;
  private final Supplier<ServerConfiguration.RateLimit> rateLimit;
  private final TagRenderer tagRenderer;
  private final Map<Command, CommandHandler> handlers = new ConcurrentHashMap<>();
  private final AtomicLong connectionIdCounter = new AtomicLong();

  public ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Supplier<ServerConfiguration.RateLimit> rateLimit) {
    this.extensionRegistry = extensionRegistry;
    this.disconnectCleanup = disconnectCleanup;
    this.serverName = serverName;
    this.rateLimit = rateLimit;
    this.tagRenderer = new CapabilityTagRenderer(extensionRegistry);
  }

  public void registerHandler(Command command, CommandHandler handler) {
    handlers.put(command, handler);
  }

  /** Spawns one virtual thread to own this connection's lifetime end to end. */
  public void accept(Socket socket) {
    Thread.ofVirtual()
        .name("connection-" + connectionIdCounter.get())
        .start(() -> handleConnection(socket));
  }

  private void handleConnection(Socket socket) {
    String remoteAddress =
        socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress();
    if (!isAdmitted(remoteAddress)) {
      closeQuietly(socket);
      return;
    }

    String connectionId = "c" + connectionIdCounter.incrementAndGet();
    ServerConfiguration.RateLimit configuredRateLimit = rateLimit.get();
    ClientSession session =
        new ClientSession(
            connectionId,
            remoteAddress,
            new RateLimitBucket(
                configuredRateLimit.bucketSize(),
                configuredRateLimit.refillRatePerSecond(),
                java.time.Clock.systemUTC()));
    // Attached to the session and closed later via DisconnectCleanup, not in this method.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer;
    try {
      writer = new SessionWriter(session, socket.getOutputStream(), tagRenderer);
    } catch (IOException e) {
      closeQuietly(socket);
      return;
    }
    session.attachWriter(writer);
    writer.setOnOverflow(() -> disconnectCleanup.cleanup(session, "Excess Flood"));

    try (BufferedInputStream in = new BufferedInputStream(socket.getInputStream())) {
      byte[] lineBytes;
      while ((lineBytes = readLineBytes(in)) != null) {
        if (session.lifecycle().isClosing()) {
          break;
        }
        processLine(session, lineBytes);
        if (session.lifecycle().isClosing()) {
          break;
        }
      }
      if (!session.lifecycle().isClosing()) {
        disconnectCleanup.cleanup(session, "Connection reset by peer");
      }
    } catch (IOException e) {
      if (!session.lifecycle().isClosing()) {
        disconnectCleanup.cleanup(session, "Connection reset by peer");
      }
    } finally {
      closeQuietly(socket);
    }
  }

  /**
   * Reads raw bytes up to the next {@code \n} (an optional preceding {@code \r} is stripped),
   * returning them without decoding. Returns {@code null} only at end-of-stream with no bytes read
   * at all — a final, unterminated line at EOF is still returned, the same as {@code
   * BufferedReader#readLine()} would.
   */
  private static byte[] readLineBytes(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      buffer.write(b);
    }
    if (b == -1 && buffer.size() == 0) {
      return null;
    }
    byte[] bytes = buffer.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    return length == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, length);
  }

  private void processLine(ClientSession session, byte[] lineBytes) {
    if (!Utf8Validator.isValidUtf8(lineBytes)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          "*",
          "Malformed message (invalid UTF-8)");
      return;
    }
    if (lineBytes.length + 2 > MAX_LINE_LENGTH_BYTES) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_INPUTTOOLONG, "Input line was too long");
      return;
    }
    String line = new String(lineBytes, StandardCharsets.UTF_8);

    Message message;
    try {
      message = MessageParser.parse(line);
    } catch (MalformedMessageException e) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_UNKNOWNCOMMAND, line, "Malformed message");
      return;
    }

    if (!session.rateLimitBucket().tryConsume()) {
      return; // FR-016: silently drop over-rate traffic rather than disconnecting immediately
    }

    if (message.command() == null) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          message.rawCommand(),
          "Unknown command");
      return;
    }

    CommandHandler handler = handlers.get(message.command());
    if (handler == null) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          message.rawCommand(),
          "Unknown command");
      return;
    }

    if (!session.lifecycle().isRegistered()
        && !PRE_REGISTRATION_COMMANDS.contains(message.command())) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_NOTREGISTERED, "You have not registered");
      return;
    }

    try {
      handler.handle(session, message);
    } catch (RuntimeException e) {
      LOG.warn(
          "Command handler for {} threw on connection {}",
          message.command(),
          session.connectionId(),
          e);
    }
  }

  private boolean isAdmitted(String remoteAddress) {
    for (Extension extension : extensionRegistry.enabled()) {
      if (extension instanceof ServerExtension serverExtension
          && "connection-admission".equals(serverExtension.extensionPoint())
          && extension instanceof ConnectionAdmissionExtension admission) {
        return admission.admit(remoteAddress);
      }
    }
    return true; // nothing claims connection-admission this release — always permit
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
