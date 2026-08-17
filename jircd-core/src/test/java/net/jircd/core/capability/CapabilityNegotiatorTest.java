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
package net.jircd.core.capability;

import static org.assertj.core.api.Assertions.assertThat;

import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import org.junit.jupiter.api.Test;

class CapabilityNegotiatorTest {

  @Test
  void negotiationSucceedsAndRegistrationCanCompleteWithZeroEnabledCapabilities() {
    ExtensionRegistry registry = new ExtensionRegistry();
    CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

    assertThat(negotiator.currentlyOfferedCapabilityNames()).isEmpty();

    negotiator.beginNegotiation(session);
    assertThat(session.isNegotiatingCapabilities()).isTrue();

    var result = negotiator.request(session, java.util.List.of("message-tags"));
    assertThat(result.accepted())
        .isEmpty(); // nothing enabled, so nothing offered/accepted (FR-008)
    assertThat(result.declined()).containsExactly("message-tags");

    negotiator.endNegotiation(session);
    assertThat(session.isNegotiatingCapabilities()).isFalse();
  }
}
