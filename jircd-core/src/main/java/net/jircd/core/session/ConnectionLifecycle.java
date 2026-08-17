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

import java.util.concurrent.atomic.AtomicReference;

/** The {@code CONNECTING} → {@code REGISTERED} → {@code CLOSING} state machine (FR-001). */
public final class ConnectionLifecycle {

  public enum State {
    CONNECTING,
    REGISTERED,
    CLOSING
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.CONNECTING);

  public State get() {
    return state.get();
  }

  public boolean isRegistered() {
    return state.get() == State.REGISTERED;
  }

  public boolean isClosing() {
    return state.get() == State.CLOSING;
  }

  /** CONNECTING -> REGISTERED. No-op if already REGISTERED or CLOSING. */
  public void completeRegistration() {
    state.compareAndSet(State.CONNECTING, State.REGISTERED);
  }

  /** Any state -> CLOSING. Terminal; there is no path back. */
  public void close() {
    state.set(State.CLOSING);
  }
}
