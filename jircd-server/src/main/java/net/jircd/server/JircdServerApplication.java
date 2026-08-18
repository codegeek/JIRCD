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
package net.jircd.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ConnectionHandler;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PlaintextListener;
import net.jircd.core.session.TlsListener;
import net.jircd.core.session.command.CapCommandHandler;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.ListCommandHandler;
import net.jircd.core.session.command.MessageCommandHandler;
import net.jircd.core.session.command.NamesCommandHandler;
import net.jircd.core.session.command.NickCommandHandler;
import net.jircd.core.session.command.PartCommandHandler;
import net.jircd.core.session.command.PingPongCommandHandler;
import net.jircd.core.session.command.QuitCommandHandler;
import net.jircd.core.session.command.TopicCommandHandler;
import net.jircd.core.session.command.UserCommandHandler;
import net.jircd.core.session.command.UserModeCommandHandler;
import net.jircd.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application entry point (composition root): loads {@link ServerConfiguration}, starts {@link
 * ExtensionRegistry}, starts the plaintext and TLS listeners, refuses to start with a specific
 * error on invalid configuration (FR-012).
 */
public final class JircdServerApplication {

  private static final Logger LOG = LoggerFactory.getLogger(JircdServerApplication.class);

  private final NicknameRegistry nicknameRegistry = new NicknameRegistry();
  private final ChannelRegistry channelRegistry = new ChannelRegistry();
  private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();
  private final DisconnectCleanup disconnectCleanup =
      new DisconnectCleanup(nicknameRegistry, channelRegistry);
  private final ConnectionHandler connectionHandler;
  private final Instant startedAt = Instant.now();

  private volatile ServerConfiguration configuration;
  private volatile String serverName;
  private final String serverVersion;
  private final ConfigurationReloader reloader;

  private PlaintextListener plaintextListener;
  private TlsListener tlsListener;

  public JircdServerApplication(Path configPath) throws ConfigurationException, IOException {
    extensionRegistry.attachContext(
        new ServerContext(nicknameRegistry, channelRegistry, extensionRegistry));
    extensionRegistry.discover(Thread.currentThread().getContextClassLoader());

    Set<String> knownCapabilityIds =
        extensionRegistry.all().stream()
            .filter(net.jircd.core.extension.CapabilityExtension.class::isInstance)
            .map(net.jircd.core.extension.Extension::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<String> knownServerExtensionIds =
        extensionRegistry.all().stream()
            .filter(e -> !(e instanceof net.jircd.core.extension.CapabilityExtension))
            .map(net.jircd.core.extension.Extension::id)
            .collect(java.util.stream.Collectors.toSet());
    ConfigurationLoader loader =
        new ConfigurationLoader(knownCapabilityIds, knownServerExtensionIds);

    try (InputStream in = new FileInputStream(configPath.toFile())) {
      this.configuration = loader.load(in);
    }

    this.serverVersion = resolveServerVersion();
    this.serverName = resolveServerName(configuration.serverName());
    extensionRegistry.updateConfiguredLengths(
        configuration.nicknameMaxLength(),
        configuration.channelNameMaxLength(),
        configuration.topicMaxLength(),
        configuration.maxModesPerCommand());

    this.connectionHandler =
        new ConnectionHandler(extensionRegistry, disconnectCleanup, () -> serverName);
    registerStory1Handlers();

    this.reloader = new ConfigurationReloader(configPath, loader, extensionRegistry, configuration);

    enableConfiguredExtensions();
  }

  private void enableConfiguredExtensions() {
    configuration
        .capabilityStates()
        .forEach(
            (id, enabled) -> {
              if (enabled) {
                extensionRegistry.enable(id);
              }
            });
    configuration
        .serverExtensionStates()
        .forEach(
            (id, enabled) -> {
              if (enabled) {
                extensionRegistry.enable(id);
              }
            });
  }

  private void registerStory1Handlers() {
    var registrationCompletion =
        new net.jircd.core.session.command.RegistrationCompletion(
            () -> serverName, serverVersion, startedAt, extensionRegistry);
    connectionHandler.registerHandler(
        Command.NICK,
        new NickCommandHandler(
            nicknameRegistry,
            () -> serverName,
            () -> configuration.nicknameMaxLength(),
            registrationCompletion));
    connectionHandler.registerHandler(
        Command.USER, new UserCommandHandler(() -> serverName, registrationCompletion));
    connectionHandler.registerHandler(
        Command.JOIN,
        new JoinCommandHandler(
            channelRegistry,
            extensionRegistry,
            () -> serverName,
            () -> configuration.channelNameMaxLength()));
    connectionHandler.registerHandler(
        Command.PART, new PartCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.PRIVMSG,
        new MessageCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName, false));
    connectionHandler.registerHandler(
        Command.NOTICE,
        new MessageCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName, true));
    connectionHandler.registerHandler(
        Command.QUIT, new QuitCommandHandler(disconnectCleanup, () -> serverName));
    connectionHandler.registerHandler(
        Command.TOPIC,
        new TopicCommandHandler(
            channelRegistry, () -> serverName, () -> configuration.topicMaxLength()));
    connectionHandler.registerHandler(
        Command.NAMES, new NamesCommandHandler(channelRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.LIST, new ListCommandHandler(channelRegistry, () -> serverName));
    connectionHandler.registerHandler(Command.MODE, new UserModeCommandHandler(() -> serverName));
    connectionHandler.registerHandler(Command.PING, PingPongCommandHandler.ping());
    connectionHandler.registerHandler(Command.PONG, PingPongCommandHandler.pong());
    connectionHandler.registerHandler(
        Command.CAP,
        new CapCommandHandler(
            new CapabilityNegotiator(extensionRegistry), () -> serverName, registrationCompletion));
  }

  public void start() throws IOException, GeneralSecurityException {
    List<ServerConfiguration.Listener> listeners =
        configuration.listeners().isEmpty()
            ? List.of(
                new ServerConfiguration.Listener(6667, false),
                new ServerConfiguration.Listener(6697, true))
            : configuration.listeners();
    for (ServerConfiguration.Listener listener : listeners) {
      if (listener.tls()) {
        tlsListener = new TlsListener(listener.port(), connectionHandler);
        tlsListener.start();
        LOG.info("TLS listener started on port {}", listener.port());
      } else {
        plaintextListener = new PlaintextListener(listener.port(), connectionHandler);
        plaintextListener.start();
        LOG.info("Plaintext listener started on port {}", listener.port());
      }
    }
  }

  public void stop() throws IOException {
    if (plaintextListener != null) {
      plaintextListener.close();
    }
    if (tlsListener != null) {
      tlsListener.close();
    }
  }

  public ConfigurationReloader reloader() {
    return reloader;
  }

  public int plaintextPort() {
    return plaintextListener.boundPort();
  }

  public int tlsPort() {
    return tlsListener.boundPort();
  }

  public ExtensionRegistry extensionRegistry() {
    return extensionRegistry;
  }

  public NicknameRegistry nicknameRegistry() {
    return nicknameRegistry;
  }

  public ChannelRegistry channelRegistry() {
    return channelRegistry;
  }

  public String serverName() {
    return serverName;
  }

  private String resolveServerVersion() throws IOException {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("net/jircd/server/version.properties")) {
      if (in == null) {
        throw new IOException("net/jircd/server/version.properties resource is missing");
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version");
      if (version == null || version.isBlank()) {
        throw new IOException("net/jircd/server/version.properties has no 'version' entry");
      }
      return version;
    }
  }

  private static String resolveServerName(String configured) {
    if (configured != null) {
      return configured;
    }
    String hostname;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      hostname = "localhost";
    }
    return hostname.contains(".") ? hostname : hostname + ".local";
  }

  public static void main(String[] args) throws InterruptedException {
    Path configPath = Path.of(args.length > 0 ? args[0] : "jircd.yaml");
    JircdServerApplication application;
    try {
      application = new JircdServerApplication(configPath);
      application.start();
    } catch (ConfigurationException e) {
      LOG.error("Refusing to start: {}", e.getMessage());
      System.exit(1);
      return;
    } catch (IOException | GeneralSecurityException e) {
      LOG.error("Refusing to start: {}", e.getMessage());
      System.exit(1);
      return;
    }
    SighupReloadHandler.install(application);
    LOG.info("jircd started as {}", application.serverName());
    Thread.currentThread().join();
  }
}
