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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChannelRegistryTest {

  private ClientSession newSession(String id) {
    return new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
  }

  @Test
  void getOrCreateResolvesToOneChannelRegardlessOfCasing() {
    ChannelRegistry registry = new ChannelRegistry();
    Channel first = registry.getOrCreate("#Foo");
    Channel second = registry.getOrCreate("#foo");
    assertThat(second).isSameAs(first);
    assertThat(first.name()).isEqualTo("#Foo");
  }

  @Test
  void lookupByAnyCasingFindsTheChannel() {
    ChannelRegistry registry = new ChannelRegistry();
    registry.getOrCreate("#Lobby");
    assertThat(registry.lookup("#lobby")).isPresent();
    assertThat(registry.lookup("#LOBBY")).isPresent();
  }

  @Test
  void firstJoinerBecomesOperator() {
    ChannelRegistry registry = new ChannelRegistry();
    Channel channel = registry.getOrCreate("#lobby");
    ClientSession first = newSession("a");
    ClientSession second = newSession("b");

    boolean firstGrantedOp = channel.addMember(first);
    boolean secondGrantedOp = channel.addMember(second);

    assertThat(firstGrantedOp).isTrue();
    assertThat(secondGrantedOp).isFalse();
    assertThat(channel.operators()).containsExactly(first);
    assertThat(channel.members()).containsExactlyInAnyOrder(first, second);
  }
}
