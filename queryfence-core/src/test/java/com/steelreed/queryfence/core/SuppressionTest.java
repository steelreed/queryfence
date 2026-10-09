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
package com.steelreed.queryfence.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A suppression without a documented reason is a configuration error (architecture rule 3). */
class SuppressionTest {

  @Test
  void acceptsAClassMethodOriginWithAReason() {
    Suppression suppression =
        new Suppression("tenant", "com.acme.admin.ReportJob#nightlyTotals", "platform operator");

    assertThat(suppression.ruleId()).isEqualTo("tenant");
    assertThat(suppression.origin()).isEqualTo("com.acme.admin.ReportJob#nightlyTotals");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n"})
  void rejectsABlankReason(String reason) {
    assertThatThrownBy(() -> new Suppression("tenant", "com.acme.Repo#find", reason))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no reason");
  }

  @Test
  void rejectsAMissingReason() {
    assertThatThrownBy(() -> new Suppression("tenant", "com.acme.Repo#find", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no reason");
  }

  @ParameterizedTest
  @ValueSource(strings = {"com.acme.Repo", "com.acme.Repo#", "#find"})
  void rejectsAnOriginThatIsNotClassHashMethod(String origin) {
    assertThatThrownBy(() -> new Suppression("tenant", origin, "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Class#method");
  }

  @Test
  void rejectsABlankRuleOrOrigin() {
    assertThatThrownBy(() -> new Suppression(" ", "com.acme.Repo#find", "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rule");

    assertThatThrownBy(() -> new Suppression("tenant", " ", "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("origin");

    assertThatThrownBy(() -> new Suppression(null, "com.acme.Repo#find", "reason"))
        .isInstanceOf(NullPointerException.class);
  }
}
