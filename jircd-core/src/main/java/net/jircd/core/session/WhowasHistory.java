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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * A bounded, server-scoped, global ring buffer of {@link WhowasEntry} values
 * (002-extended-irc-commands data-model.md "WhowasHistory", research.md "WHOWAS bounded history
 * store") — not one bucket per nickname. Thread-safe via a single intrinsic lock; write volume is
 * inherently low (at most one entry per disconnection), so a whole-structure lock has no measurable
 * contention risk. Capacity is read live on every {@link #record}, the same
 * administrator-configurable-and-reloadable posture every other bounded numeric limit on this
 * server already uses — lowering it evicts the oldest surplus entries starting with the very next
 * recorded disconnection.
 */
public final class WhowasHistory {

  private final Deque<WhowasEntry> entries = new ArrayDeque<>();
  private final IntSupplier capacity;

  public WhowasHistory(IntSupplier capacity) {
    this.capacity = capacity;
  }

  public synchronized void record(WhowasEntry entry) {
    int max = capacity.getAsInt();
    while (entries.size() >= max) {
      entries.removeFirst();
    }
    entries.addLast(entry);
  }

  /** The most recent entry for {@code nickname} (rfc1459-casemapped), if any. */
  public synchronized Optional<WhowasEntry> mostRecentFor(String nickname) {
    String folded = CaseMapping.fold(nickname);
    WhowasEntry mostRecent = null;
    for (WhowasEntry candidate : entries) {
      if (CaseMapping.fold(candidate.nickname()).equals(folded)
          && (mostRecent == null
              || candidate.disconnectedAt().isAfter(mostRecent.disconnectedAt()))) {
        mostRecent = candidate;
      }
    }
    return Optional.ofNullable(mostRecent);
  }
}
