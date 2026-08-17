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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A named, joinable group through which members exchange messages (data-model.md "Channel",
 * Aggregate Root). {@code name} is its identity; membership and moderation state are guarded by
 * this aggregate.
 */
public final class Channel {

  public static final int MAX_BANS = 100;

  private final String name;
  private final String foldedName;
  private final Set<ClientSession> members = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> operators = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> voiced = ConcurrentHashMap.newKeySet();
  private final Set<ChannelMode> activeModes = ConcurrentHashMap.newKeySet();
  private final List<BanEntry> bans = new CopyOnWriteArrayList<>();
  private final Set<String> invited = ConcurrentHashMap.newKeySet();
  private volatile String topic;

  public Channel(String name) {
    this.name = name;
    this.foldedName = CaseMapping.fold(name);
  }

  public String name() {
    return name;
  }

  public Set<ClientSession> members() {
    return members;
  }

  public Set<ClientSession> operators() {
    return operators;
  }

  public Set<ClientSession> voiced() {
    return voiced;
  }

  public Set<ChannelMode> activeModes() {
    return activeModes;
  }

  public List<BanEntry> bans() {
    return bans;
  }

  public Set<String> invited() {
    return invited;
  }

  public String topic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public boolean isEmpty() {
    return members.isEmpty();
  }

  /**
   * Adds a member, granting operator status if this is the channel's very first member (classic
   * first-join-gets-operator default, FR-013). Returns {@code true} if operator status was granted
   * by this call.
   */
  public synchronized boolean addMember(ClientSession session) {
    boolean wasEmpty = members.isEmpty();
    members.add(session);
    if (wasEmpty) {
      operators.add(session);
    }
    return wasEmpty;
  }

  /**
   * Removes a departing session from membership, operator, and voice state together
   * (data-model.md).
   */
  public void removeMember(ClientSession session) {
    members.remove(session);
    operators.remove(session);
    voiced.remove(session);
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Channel other && foldedName.equals(other.foldedName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(foldedName);
  }
}
