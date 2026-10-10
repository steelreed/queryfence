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
import java.util.List;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Edge cases of the checker entry point that the corpus cannot express as SQL cases. */
class DefaultSqlCheckerTest {

  private final Policy policy =
      Policy.builder()
          .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
          .updateWithoutWhere("no-unbounded-update")
          .build();

  private final SqlChecker checker = SqlChecker.of(policy);

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
  void reportsAStatementWhoseAnalysisFailsInsteadOfThrowing() {
    PlainSelect broken =
        new PlainSelect() {
          @Override
          public List<SelectItem<?>> getSelectItems() {
            throw new IllegalStateException("analysis bug");
          }
        };
    String sql = "SELECT * FROM purchase_order";

    assertThat(new DefaultSqlChecker(policy).checkStatements(List.of(broken), sql))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.code()).isEqualTo(Violation.Code.UNPARSEABLE);
              assertThat(v.ruleId()).isEqualTo(Violation.PARSER_RULE);
              assertThat(v.table()).isEqualTo("purchase_order");
              assertThat(v.message())
                  .isEqualTo(
                      "QueryFence could not analyse this statement, so it cannot prove it safe."
                          + " It mentions purchase_order, which stays unverified here: a missing"
                          + " filter on that table would go unnoticed. Report the SQL to"
                          + " QueryFence, or set onUnparseable: REPORT to only report it.");
              assertThat(v.sql()).isEqualTo(sql);
            });
  }

  @Test
  void reportsAFailedAnalysisWithoutTableWhenNoProtectedTableIsMentioned() {
    PlainSelect broken =
        new PlainSelect() {
          @Override
          public List<SelectItem<?>> getSelectItems() {
            throw new StackOverflowError();
          }
        };

    assertThat(new DefaultSqlChecker(policy).checkStatements(List.of(broken), "SELECT 1"))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.table()).isNull();
              assertThat(v.message())
                  .isEqualTo(
                      "QueryFence could not analyse this statement, so it cannot prove it safe."
                          + " Report the SQL to QueryFence, or set onUnparseable: REPORT to only"
                          + " report it.");
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET search_path TO app; SELECT * FROM purchase_order",
        "SET search_path TO app;\nSELECT * FROM purchase_order;",
        "BEGIN; SELECT * FROM purchase_order; COMMIT",
        "-- c; d\nSET search_path TO app; /* ; */ SELECT * FROM purchase_order",
        "SET x TO 'a;b'; SELECT * FROM \"purchase_order\""
      })
  void checksEveryStatementOfAStringThatDoesNotParseAsAWhole(String sql) {
    assertThat(checker.check(sql))
        .extracting(Violation::code)
        .containsExactly(Violation.Code.MISSING_PREDICATE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET search_path TO app;",
        "SET search_path TO app; ; -- done",
        "SET search_path TO app; SET x TO 'SELECT * FROM purchase_order'",
        "CREATE TABLE t (id INT REFERENCES purchase_order (id) ON DELETE CASCADE) FOO",
        "CREATE TABLE t (id INT) ON UPDATE CASCADE FOO",
        "SET x TO $$ a $$",
        "SET x TO $1; SET y TO z"
      })
  void ignoresUnparseableStatementsThatCannotTouchRows(String sql) {
    assertThat(checker.check(sql)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET x = (SELECT tenant_id FROM purchase_order) FOO",
        "CREATE TABLE t AS SELECT * FROM x ??",
        "CREATE TRIGGER t BEFORE UPDATE ON x FOO",
        "SET x TO $q$ ; SELECT 1 $q$; SET y TO z",
        "LOCK x ?? $$ unterminated ; SELECT 1",
        "EXPLAIN SELEKT 1",
        "COPY x FROM STDIN",
        "DO $$ BEGIN END $$",
        "SET x TO 1; SELEKT 1"
      })
  void reportsUnparseableStatementsThatCanTouchRows(String sql) {
    assertThat(checker.check(sql))
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.code()).isEqualTo(Violation.Code.UNPARSEABLE));
  }

  @Test
  void reportsTheUnparseablePartOfASplitStringWithItsOwnSql() {
    assertThat(checker.check("SET x TO 1; UPDATE purchase_order SET"))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.code()).isEqualTo(Violation.Code.UNPARSEABLE);
              assertThat(v.table()).isEqualTo("purchase_order");
              assertThat(v.sql()).isEqualTo("UPDATE purchase_order SET");
            });
  }

  @Test
  void rejectsNullInput() {
    assertThatThrownBy(() -> checker.check(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SqlChecker.of(null)).isInstanceOf(NullPointerException.class);
  }
}
