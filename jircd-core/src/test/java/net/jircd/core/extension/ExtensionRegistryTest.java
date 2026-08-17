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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.NicknameRegistry;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {

  private static class FakeServerExtension implements ServerExtension {
    private final String id;
    private final String extensionPoint;
    boolean started;
    boolean stopped;

    FakeServerExtension(String id, String extensionPoint) {
      this.id = id;
      this.extensionPoint = extensionPoint;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String extensionPoint() {
      return extensionPoint;
    }

    @Override
    public void start(ServerContext context) {
      started = true;
    }

    @Override
    public void stop() {
      stopped = true;
    }
  }

  private ExtensionRegistry newRegistry() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.attachContext(
        new ServerContext(new NicknameRegistry(), new ChannelRegistry(), registry));
    return registry;
  }

  @Test
  void enableDisableLifecycle() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension extension = new FakeServerExtension("cloak", "hostname-display");
    registry.register(extension);

    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.DISABLED);
    registry.enable("cloak");
    assertThat(extension.started).isTrue();
    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.ENABLED);

    registry.disable("cloak");
    assertThat(extension.stopped).isTrue();
    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void failedStartTransitionsToFailedWithoutAffectingOtherExtensions() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension healthy = new FakeServerExtension("admin", null);
    FakeServerExtension broken =
        new FakeServerExtension("broken", null) {
          @Override
          public void start(ServerContext context) {
            throw new RuntimeException("boom");
          }
        };
    registry.register(healthy);
    registry.register(broken);

    registry.enable("admin");
    assertThatThrownBy(() -> registry.enable("broken")).isInstanceOf(RuntimeException.class);

    assertThat(registry.stateOf("broken")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("admin")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void conflictingExtensionPointClaimIsRejected() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension first = new FakeServerExtension("cloak-a", "hostname-display");
    FakeServerExtension second = new FakeServerExtension("cloak-b", "hostname-display");
    registry.register(first);
    registry.register(second);

    registry.enable("cloak-a");
    assertThatThrownBy(() -> registry.enable("cloak-b"))
        .isInstanceOf(ExtensionRegistry.ExtensionPointConflictException.class);
    assertThat(registry.stateOf("cloak-b")).isEqualTo(Extension.State.DISABLED);

    registry.disable("cloak-a");
    registry.enable("cloak-b"); // now free to claim
    assertThat(registry.stateOf("cloak-b")).isEqualTo(Extension.State.ENABLED);
  }
}
