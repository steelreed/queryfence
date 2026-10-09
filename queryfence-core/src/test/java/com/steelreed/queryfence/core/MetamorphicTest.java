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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.steelreed.queryfence.core.Corpus.Case;
import com.steelreed.queryfence.core.TenantPredicates.Mutation;
import com.steelreed.queryfence.core.TenantPredicates.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Metamorphic relations: a statement QueryFence accepts must stop being accepted as soon as its
 * tenant protection is weakened, however the weakening is written. Each {@link Transformation} is a
 * bypass a developer could write by accident; a passing case that survives one is an engine hole.
 */
class MetamorphicTest {

  private static final String COLUMN = "tenant_id";

  static Stream<Arguments> passingCasesAndTransformations() {
    List<Arguments> arguments = new ArrayList<>();
    for (Case c : Corpus.cases()) {
      if (!c.expect().isEmpty()) {
        continue;
      }
      for (Transformation transformation : Transformation.values()) {
        arguments.add(Arguments.of(Named.of(c.id() + " / " + transformation, c), transformation));
      }
    }
    return arguments.stream();
  }

  static Stream<Arguments> allCases() {
    return Corpus.cases().stream().map(c -> Arguments.of(Named.of(c.id(), c)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("passingCasesAndTransformations")
  void weakeningATenantPredicateProducesAViolation(Case c, Transformation transformation) {
    List<Mutation> mutations = TenantPredicates.mutations(c.sql(), COLUMN, transformation);
    assumeFalse(
        mutations.isEmpty(), transformation + " does not apply to any predicate of " + c.id());

    SqlChecker checker = GoldenCorpusTest.checker(c);
    for (Mutation mutation : mutations) {
      assertThat(checker.check(mutation.sql()))
          .as(
              "%s: %s (%s) must be caught%n  before: %s%n  after : %s",
              c.id(), mutation.transformation(), mutation.description(), c.sql(), mutation.sql())
          .isNotEmpty();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allCases")
  void mutatedStatementsNeverMakeTheEngineThrow(Case c) {
    SqlChecker checker = GoldenCorpusTest.checker(c);
    for (Mutation mutation : TenantPredicates.mutations(c.sql(), COLUMN)) {
      assertThatCode(() -> checker.check(mutation.sql()))
          .as("%s: %s", c.id(), mutation.sql())
          .doesNotThrowAnyException();
    }
  }

  @Test
  void everyTransformationAppliesToSeveralPassingCases() {
    for (Transformation transformation : Transformation.values()) {
      long cases =
          Corpus.cases().stream()
              .filter(c -> c.expect().isEmpty())
              .filter(c -> !TenantPredicates.mutations(c.sql(), COLUMN, transformation).isEmpty())
              .count();
      assertThat(cases)
          .as("passing cases that %s can weaken", transformation)
          .isGreaterThanOrEqualTo(5);
    }
  }

  @Test
  void mostPassingCasesActuallyCarryATenantPredicate() {
    List<Case> passing = Corpus.cases().stream().filter(c -> c.expect().isEmpty()).toList();
    List<String> withoutPredicate =
        passing.stream()
            .filter(c -> TenantPredicates.mutations(c.sql(), COLUMN).isEmpty())
            .map(Case::id)
            .toList();

    // Statements that pass without any tenant predicate exist on purpose (unprotected tables,
    // ignored statements, CTE shadowing) but they must stay a small minority of the corpus.
    assertThat(withoutPredicate)
        .as("passing cases with no tenant predicate at all")
        .hasSizeLessThan(passing.size() / 4);
  }
}
