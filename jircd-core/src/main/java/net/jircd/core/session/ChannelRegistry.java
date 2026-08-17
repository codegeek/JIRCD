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

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide channel-name uniqueness with create-on-first-join (FR-003), compared using the same
 * rfc1459 casemapping as {@link NicknameRegistry} (FR-052) — {@code #Foo} and {@code #foo} resolve
 * to one channel, storing whichever casing created it. Its narrow public surface leaves room to
 * later span servers without callers changing (FR-022).
 */
public final class ChannelRegistry {

  private final Map<String, Channel> byFoldedName = new ConcurrentHashMap<>();

  /** Returns the existing channel of this name, or atomically creates and returns a new one. */
  public Channel getOrCreate(String name) {
    String folded = CaseMapping.fold(name);
    return byFoldedName.computeIfAbsent(folded, ignored -> new Channel(name));
  }

  public Optional<Channel> lookup(String name) {
    return Optional.ofNullable(byFoldedName.get(CaseMapping.fold(name)));
  }

  /**
   * Removes a channel once it has no remaining members (FR-003 — a zero-member channel is not
   * durable).
   */
  public void removeIfEmpty(Channel channel) {
    byFoldedName.computeIfPresent(
        CaseMapping.fold(channel.name()),
        (key, existing) -> {
          if (existing.equals(channel) && existing.isEmpty()) {
            return null;
          }
          return existing;
        });
  }

  public Collection<Channel> all() {
    return byFoldedName.values();
  }
}
