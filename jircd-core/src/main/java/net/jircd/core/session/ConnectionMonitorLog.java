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

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured, reviewable logging of connection lifecycle events (connect/disconnect) for monitoring
 * purposes (009-connection-monitoring-log) — distinct from {@link SecurityEventLog}, which is
 * scoped to security-relevant events only.
 */
public final class ConnectionMonitorLog {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionMonitorLog.class);

  private ConnectionMonitorLog() {}

  public static void connected(String connectionId, String remoteAddress) {
    LOG.info(
        "connection-event=connected connection={} remoteAddress={}", connectionId, remoteAddress);
  }

  public static void disconnected(String connectionId, Duration duration, String reason) {
    LOG.info(
        "connection-event=disconnected connection={} durationMs={} reason={}",
        connectionId,
        duration.toMillis(),
        reason);
  }
}
