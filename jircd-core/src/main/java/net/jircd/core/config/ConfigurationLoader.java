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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and validates the YAML Server Configuration file (contracts/server-configuration.md),
 * rejecting any invalid value with a specific, actionable error naming the offending key (FR-012,
 * SC-008) — never a partially-applied result.
 */
public final class ConfigurationLoader {

  private static final Set<String> BASE_64_HASH_PREFIXES =
      Set.of("$2a$", "$2b$", "$2y$", "$argon2");

  private final Set<String> knownCapabilityIds;
  private final Set<String> knownServerExtensionIds;

  public ConfigurationLoader(Set<String> knownCapabilityIds, Set<String> knownServerExtensionIds) {
    this.knownCapabilityIds = knownCapabilityIds;
    this.knownServerExtensionIds = knownServerExtensionIds;
  }

  @SuppressWarnings("unchecked")
  public ServerConfiguration load(InputStream yamlInput) throws ConfigurationException {
    Object rootObj = new Yaml().load(yamlInput);
    Map<String, Object> root = rootObj == null ? Map.of() : (Map<String, Object>) rootObj;

    String serverName = asString(root.get("serverName"));
    if (serverName != null && !serverName.contains(".")) {
      throw new ConfigurationException("serverName must contain a '.': " + serverName);
    }

    int nicknameMaxLength =
        positiveIntWithinCeiling(
            root,
            "nicknameMaxLength",
            ServerConfiguration.DEFAULT_NICKNAME_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int channelNameMaxLength =
        positiveIntWithinCeiling(
            root,
            "channelNameMaxLength",
            ServerConfiguration.DEFAULT_CHANNEL_NAME_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int topicMaxLength =
        positiveIntWithinCeiling(
            root,
            "topicMaxLength",
            ServerConfiguration.DEFAULT_TOPIC_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int maxModesPerCommand =
        positiveIntWithinCeiling(
            root,
            "maxModesPerCommand",
            ServerConfiguration.DEFAULT_MAX_MODES_PER_COMMAND,
            ServerConfiguration.MAX_MODES_CEILING);
    int operFailureThreshold =
        positiveIntWithinCeiling(
            root,
            "operFailureThreshold",
            ServerConfiguration.DEFAULT_OPER_FAILURE_THRESHOLD,
            ServerConfiguration.OPER_FAILURE_THRESHOLD_CEILING);

    boolean whoMaskEnabled =
        root.containsKey("whoMaskEnabled") ? asBoolean(root.get("whoMaskEnabled")) : true;

    List<ServerConfiguration.Listener> listeners = parseListeners(root.get("listeners"));

    ServerConfiguration.RateLimit rateLimit = parseRateLimit(root.get("rateLimit"));

    Map<String, Boolean> capabilityStates =
        parseExtensionStates(
            root.get("capabilities"), knownCapabilityIds, knownServerExtensionIds, "capabilities");
    Map<String, Boolean> serverExtensionStates =
        parseExtensionStates(
            root.get("server-extensions"),
            knownServerExtensionIds,
            knownCapabilityIds,
            "server-extensions");

    List<ServerConfiguration.AdministratorCredential> credentials =
        parseCredentials(root.get("administratorCredentials"));

    return new ServerConfiguration(
        capabilityStates,
        serverExtensionStates,
        listeners,
        rateLimit,
        credentials,
        serverName,
        nicknameMaxLength,
        channelNameMaxLength,
        topicMaxLength,
        whoMaskEnabled,
        maxModesPerCommand,
        operFailureThreshold);
  }

  private static int positiveIntWithinCeiling(
      Map<String, Object> root, String key, int defaultValue, int ceiling)
      throws ConfigurationException {
    if (!root.containsKey(key)) {
      return defaultValue;
    }
    Object raw = root.get(key);
    if (!(raw instanceof Integer value) || value <= 0 || value > ceiling) {
      throw new ConfigurationException(
          key + " must be a positive integer no greater than " + ceiling + ", got: " + raw);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static List<ServerConfiguration.Listener> parseListeners(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return List.of();
    }
    List<ServerConfiguration.Listener> listeners = new ArrayList<>();
    for (Object entry : (List<Object>) raw) {
      Map<String, Object> map = (Map<String, Object>) entry;
      Object port = map.get("port");
      if (!(port instanceof Integer)) {
        throw new ConfigurationException("listeners entry missing a valid 'port': " + entry);
      }
      boolean tls = map.containsKey("tls") && asBoolean(map.get("tls"));
      listeners.add(new ServerConfiguration.Listener((Integer) port, tls));
    }
    return listeners;
  }

  @SuppressWarnings("unchecked")
  private static ServerConfiguration.RateLimit parseRateLimit(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return new ServerConfiguration.RateLimit(20, 5.0);
    }
    Map<String, Object> map = (Map<String, Object>) raw;
    Object bucketSize = map.getOrDefault("bucketSize", 20);
    Object refillRate = map.getOrDefault("refillRatePerSecond", 5.0);
    if (!(bucketSize instanceof Integer bucketSizeInt) || bucketSizeInt <= 0) {
      throw new ConfigurationException(
          "rateLimit.bucketSize must be a positive integer, got: " + bucketSize);
    }
    double refillRateValue = ((Number) refillRate).doubleValue();
    if (refillRateValue <= 0) {
      throw new ConfigurationException(
          "rateLimit.refillRatePerSecond must be positive, got: " + refillRate);
    }
    return new ServerConfiguration.RateLimit(bucketSizeInt, refillRateValue);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Boolean> parseExtensionStates(
      Object raw, Set<String> ownKnownIds, Set<String> otherKindKnownIds, String sectionName)
      throws ConfigurationException {
    Map<String, Boolean> states = new LinkedHashMap<>();
    if (raw == null) {
      return states;
    }
    Map<String, Object> map = (Map<String, Object>) raw;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String id = entry.getKey();
      if (otherKindKnownIds.contains(id)) {
        throw new ConfigurationException(
            "'"
                + id
                + "' is not a valid entry under '"
                + sectionName
                + "' — section/kind mismatch");
      }
      if (!ownKnownIds.contains(id)) {
        throw new ConfigurationException("Unknown extension id under '" + sectionName + "': " + id);
      }
      String value = String.valueOf(entry.getValue());
      if (!value.equals("enabled") && !value.equals("disabled")) {
        throw new ConfigurationException(
            "'" + sectionName + "." + id + "' must be 'enabled' or 'disabled', got: " + value);
      }
      states.put(id, value.equals("enabled"));
    }
    return states;
  }

  @SuppressWarnings("unchecked")
  private static List<ServerConfiguration.AdministratorCredential> parseCredentials(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return List.of();
    }
    List<ServerConfiguration.AdministratorCredential> credentials = new ArrayList<>();
    for (Object entry : (List<Object>) raw) {
      Map<String, Object> map = (Map<String, Object>) entry;
      String username = asString(map.get("username"));
      String hashedPassword = asString(map.get("hashedPassword"));
      if (username == null || hashedPassword == null) {
        throw new ConfigurationException(
            "administratorCredentials entry missing username/hashedPassword");
      }
      if (BASE_64_HASH_PREFIXES.stream().noneMatch(hashedPassword::startsWith)) {
        throw new ConfigurationException(
            "administratorCredentials."
                + username
                + ".hashedPassword is not a recognized hash format"
                + " (plain-text or unrecognized credential storage is rejected)");
      }
      credentials.add(new ServerConfiguration.AdministratorCredential(username, hashedPassword));
    }
    return credentials;
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static boolean asBoolean(Object value) {
    return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
  }
}
