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
package com.steelreed.queryfence.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.SqlChecker;
import com.steelreed.queryfence.core.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Edge cases of the checker entry point that the corpus cannot express as SQL cases. */
class DefaultSqlCheckerTest {

  private final SqlChecker checker =
      SqlChecker.of(
          Policy.builder()
              .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
              .updateWithoutWhere("no-unbounded-update")
              .build());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET search_path TO app",
        "   SET search_path TO app",
        "-- prepare the session\nSET search_path TO app",
        "/* prepare */ SET search_path TO app",
        "-- only a comment",
        "",
        "   "
      })
  void ignoresStatementsThatCannotTouchRows(String sql) {
    assertThat(checker.check(sql)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELEKT 1",
        "UPDATE purchase_order SET",
        "??",
        "/* c */ SELEKT 1",
        // an unterminated comment hides the keyword, so we cannot prove the statement is harmless
        "/* unterminated SET search_path TO app"
      })
  void reportsUnparseableStatementsThatCouldTouchRows(String sql) {
    assertThat(checker.check(sql))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.code()).isEqualTo(Violation.Code.UNPARSEABLE);
              assertThat(v.ruleId()).isEqualTo(Violation.PARSER_RULE);
              assertThat(v.ruleType()).isNull();
              assertThat(v.sql()).isEqualTo(sql);
            });
  }

  @Test
  void reportsTheOffendingStatementOfAMultiStatementString() {
    String sql =
        "SELECT id FROM purchase_order WHERE tenant_id = ?; UPDATE purchase_order SET x = 1";

    assertThat(checker.check(sql))
        .extracting(Violation::code, Violation::sql)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                Violation.Code.MISSING_PREDICATE, "UPDATE purchase_order SET x = 1"),
            org.assertj.core.groups.Tuple.tuple(
                Violation.Code.NO_WHERE, "UPDATE purchase_order SET x = 1"));
  }

  @Test
  void keepsTheWholeStringAsTheStatementOfASingleStatement() {
    String sql = "UPDATE purchase_order SET x = 1";

    assertThat(checker.check(sql)).allSatisfy(v -> assertThat(v.sql()).isEqualTo(sql));
  }

  @Test
  void rejectsNullInput() {
    assertThatThrownBy(() -> checker.check(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SqlChecker.of(null)).isInstanceOf(NullPointerException.class);
  }
}
