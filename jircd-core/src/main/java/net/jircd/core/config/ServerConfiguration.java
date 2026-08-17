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

import java.util.List;
import java.util.Map;

/**
 * The administrator-controlled settings loaded at startup and re-applied on reload (data-model.md
 * "ServerConfiguration", Aggregate Root).
 */
public record ServerConfiguration(
    Map<String, Boolean> capabilityStates,
    Map<String, Boolean> serverExtensionStates,
    List<Listener> listeners,
    RateLimit rateLimit,
    List<AdministratorCredential> administratorCredentials,
    String serverName,
    int nicknameMaxLength,
    int channelNameMaxLength,
    int topicMaxLength,
    boolean whoMaskEnabled,
    int maxModesPerCommand,
    int operFailureThreshold) {

  public record Listener(int port, boolean tls) {}

  public record RateLimit(int bucketSize, double refillRatePerSecond) {}

  public record AdministratorCredential(String username, String hashedPassword) {}

  public static final int DEFAULT_NICKNAME_MAX_LENGTH = 9;
  public static final int DEFAULT_CHANNEL_NAME_MAX_LENGTH = 50;
  public static final int DEFAULT_TOPIC_MAX_LENGTH = 390;
  public static final int DEFAULT_MAX_MODES_PER_COMMAND = 6;
  public static final int DEFAULT_OPER_FAILURE_THRESHOLD = 5;
  public static final int LENGTH_CEILING = 400;
  public static final int MAX_MODES_CEILING = 20;
  public static final int OPER_FAILURE_THRESHOLD_CEILING = 20;
}
