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
package net.jircd.core.extension;

import net.jircd.core.capability.Capability;

/**
 * The {@code id}/{@code providedCapability}/{@code start}/{@code stop} boilerplate every Story 2
 * {@link CapabilityExtension} (message-tags, server-time, echo-message) shares — none of them need
 * {@link ServerContext} or hold any state, so a concrete extension only needs to override whichever
 * of {@link #contributeTags}/{@link #includeSenderInFanOut} applies to it.
 */
public abstract class AbstractCapabilityExtension implements CapabilityExtension {

  private final String id;
  private final Capability capability;

  protected AbstractCapabilityExtension(String id) {
    this.id = id;
    this.capability = new Capability(id);
  }

  @Override
  public final String id() {
    return id;
  }

  @Override
  public final Capability providedCapability() {
    return capability;
  }

  @Override
  public void start(ServerContext context) {}

  @Override
  public void stop() {}
}
