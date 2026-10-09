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

import java.util.List;
import org.junit.jupiter.api.Test;

/** Policy validation: a bad policy is a configuration error and must fail fast. */
class PolicyTest {

  @Test
  void defaultsToFailingOnViolationsAndOnUnparseableSql() {
    Policy policy = Policy.builder().updateWithoutWhere("u").build();

    assertThat(policy.mode()).isEqualTo(Mode.FAIL);
    assertThat(policy.onUnparseable()).isEqualTo(Mode.FAIL);
    assertThat(policy.suppressions()).isEmpty();
  }

  @Test
  void governsUnparseableStatementsWithOnUnparseableAndEverythingElseWithMode() {
    Policy failButReportUnparseable =
        Policy.builder().mode(Mode.FAIL).onUnparseable(Mode.REPORT).updateWithoutWhere("u").build();

    assertThat(failButReportUnparseable.modeFor(Violation.Code.UNPARSEABLE)).isEqualTo(Mode.REPORT);
    assertThat(failButReportUnparseable.modeFor(Violation.Code.MISSING_PREDICATE))
        .isEqualTo(Mode.FAIL);

    Policy reportButFailUnparseable =
        Policy.builder().mode(Mode.REPORT).onUnparseable(Mode.FAIL).updateWithoutWhere("u").build();

    assertThat(reportButFailUnparseable.modeFor(Violation.Code.UNPARSEABLE)).isEqualTo(Mode.FAIL);
    assertThat(reportButFailUnparseable.modeFor(Violation.Code.MISSING_PREDICATE))
        .isEqualTo(Mode.REPORT);
  }

  @Test
  void exposesRulesInDeclarationOrder() {
    Policy policy =
        Policy.builder()
            .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
            .updateWithoutWhere("no-unbounded-update")
            .deleteWithoutWhere("no-unbounded-delete")
            .build();

    assertThat(policy.rules())
        .extracting(Rule::id, Rule::type)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("tenant-isolation", "require-predicate"),
            org.assertj.core.groups.Tuple.tuple("no-unbounded-update", "update-without-where"),
            org.assertj.core.groups.Tuple.tuple("no-unbounded-delete", "delete-without-where"));
  }

  @Test
  void rejectsDuplicateRuleIds() {
    Policy.Builder builder =
        Policy.builder().requirePredicate("tenant", "tenant_id", "purchase_order");

    assertThatThrownBy(() -> builder.updateWithoutWhere("tenant"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate rule id 'tenant'");
  }

  @Test
  void rejectsRulesWithoutIdColumnOrTables() {
    assertThatThrownBy(() -> Policy.builder().requirePredicate(" ", "tenant_id", "purchase_order"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Rule id");

    assertThatThrownBy(() -> Policy.builder().requirePredicate("tenant", "", "purchase_order"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column");

    assertThatThrownBy(
            () -> Policy.builder().requirePredicate("tenant", "tenant_id", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one table");

    assertThatThrownBy(() -> Policy.builder().updateWithoutWhere(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsSuppressionsOfUnknownRules() {
    Policy.Builder builder =
        Policy.builder()
            .requirePredicate("tenant", "tenant_id", "purchase_order")
            .suppress("typo", "com.acme.Repo#find", "cross-tenant admin report");

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown rule 'typo'");
  }

  @Test
  void keepsSuppressionsOfKnownRules() {
    Policy policy =
        Policy.builder()
            .requirePredicate("tenant", "tenant_id", "purchase_order")
            .suppress("tenant", "com.acme.Repo#find", "cross-tenant admin report")
            .mode(Mode.REPORT)
            .onUnparseable(Mode.REPORT)
            .build();

    assertThat(policy.suppressions())
        .containsExactly(
            new Suppression("tenant", "com.acme.Repo#find", "cross-tenant admin report"));
    assertThat(policy.mode()).isEqualTo(Mode.REPORT);
    assertThat(policy.onUnparseable()).isEqualTo(Mode.REPORT);
  }

  @Test
  void rulesAndSuppressionsAreUnmodifiable() {
    Policy policy = Policy.builder().updateWithoutWhere("u").build();

    assertThatThrownBy(() -> policy.rules().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> policy.suppressions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
