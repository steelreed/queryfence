/*
 * Copyright 2026 the QueryFence authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.steelreed.queryfence.report.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Switching QueryFence off must be impossible to miss. */
class DisabledTest {

  @BeforeEach
  void reset() {
    Disabled.reset();
    RunReport.instance().reset();
  }

  @Test
  void recordsTheReasonInTheReport() {
    Disabled.announce("the JUnit extension of queryfence.yml", false, null);

    assertThat(RunReport.instance().isDisabledSomewhere()).isTrue();
    assertThat(RunReport.instance().json())
        .contains("\"disabled\":true")
        .contains("queryfence.enabled=false for the JUnit extension of queryfence.yml");
    assertThat(RunReport.instance().summary()).contains("QueryFence was DISABLED");
  }

  @Test
  void failsWhenCiIsSet() {
    assertThatThrownBy(() -> Disabled.announce("this test context", false, "true"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CI=true")
        .hasMessageContaining("queryfence.allowDisabledInCi=true");
  }

  @Test
  void passesOnCiWhenDeliberatelyAllowed() {
    assertThatCode(() -> Disabled.announce("this test context", true, "true"))
        .doesNotThrowAnyException();

    assertThat(RunReport.instance().isDisabledSomewhere()).isTrue();
  }

  @Test
  void ignoresABlankCiMarker() {
    assertThatCode(() -> Disabled.announce("this test context", false, " "))
        .doesNotThrowAnyException();
  }
}
