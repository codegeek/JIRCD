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

import java.util.Map;

/**
 * Produces the message-tags a recipient should see on a delivered {@link OutboundMessage},
 * live-checked against that recipient's currently negotiated-and-enabled capabilities (research.md
 * "Message fan-out concurrency model"). The Foundational default renders no tags at all; {@code
 * jircd-capabilities/*} wires the real {@code message-tags}/{@code server-time} decoration in
 * (T092).
 */
@FunctionalInterface
public interface TagRenderer {

  TagRenderer NONE = (recipient, message) -> Map.of();

  Map<String, String> render(ClientSession recipient, OutboundMessage message);
}
