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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.steelreed.queryfence.core.Corpus.Case;
import com.steelreed.queryfence.core.Corpus.Expected;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every case of the golden corpus through the rule engine. */
class GoldenCorpusTest {

  private static final Map<String, Policy> POLICIES = Corpus.policies();

  static Stream<Arguments> cases() {
    return Corpus.cases().stream().map(c -> Arguments.of(Named.of(c.id(), c)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void reportsExactlyTheExpectedViolations(Case c) {
    assertThat(actualViolations(c))
        .as("%s (%s, %s): %s", c.id(), c.file(), c.clause(), c.reason())
        .containsExactlyInAnyOrderElementsOf(c.expect());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void neverThrows(Case c) {
    assertThatCode(() -> checker(c).check(c.sql()))
        .as("%s must not make the engine throw", c.id())
        .doesNotThrowAnyException();
  }

  static List<Expected> actualViolations(Case c) {
    return checker(c).check(c.sql()).stream()
        .map(v -> new Expected(v.ruleId(), v.code().name(), v.table(), v.alias(), v.message()))
        .toList();
  }

  static SqlChecker checker(Case c) {
    Policy policy = POLICIES.get(c.policy());
    if (policy == null) {
      throw new IllegalArgumentException("Unknown corpus policy '" + c.policy() + "'");
    }
    return SqlChecker.of(policy);
  }
}
