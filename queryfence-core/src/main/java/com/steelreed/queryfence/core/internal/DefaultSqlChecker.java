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

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Rule;
import com.steelreed.queryfence.core.SqlChecker;
import com.steelreed.queryfence.core.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;

/** Default {@link SqlChecker}: parses once per SQL string, then applies every rule. */
public final class DefaultSqlChecker implements SqlChecker {

  /** Shared by all checkers: parse results do not depend on the policy. */
  private static final ParseCache PARSE_CACHE = new ParseCache(10_000);

  /**
   * First keywords of statements that carry no tenant data and that JSqlParser does not always
   * understand (RP-13): session setup, transaction control, schema statements. An unparseable
   * statement that starts with one of them is ignored instead of reported, because tests routinely
   * run them — but only when it also uses none of {@link #READS_OR_WRITES_ROWS}: {@code CREATE
   * TABLE t AS SELECT ...} or {@code SET @x = (SELECT ...)} read rows and stay fail closed.
   * Keywords whose statements run queries ({@code COPY}, {@code DO}, {@code EXECUTE}, {@code
   * EXPLAIN}, {@code PREPARE}) are not listed at all.
   */
  private static final Set<String> IGNORED_WHEN_UNPARSEABLE =
      Set.of(
          "set",
          "show",
          "begin",
          "start",
          "commit",
          "rollback",
          "savepoint",
          "release",
          "use",
          "analyze",
          "analyse",
          "vacuum",
          "discard",
          "reset",
          "lock",
          "unlock",
          "grant",
          "revoke",
          "create",
          "alter",
          "drop",
          "truncate",
          "comment",
          "call",
          "deallocate",
          "flush",
          "checkpoint",
          "describe",
          "desc",
          "pragma",
          "attach",
          "detach",
          "refresh",
          "cluster",
          "reindex",
          "listen",
          "notify");

  /** Keywords that read or write rows; any of them makes an unparseable statement reportable. */
  private static final Set<String> READS_OR_WRITES_ROWS =
      Set.of("select", "insert", "update", "delete", "merge", "upsert", "copy");

  private final Policy policy;

  public DefaultSqlChecker(Policy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public List<Violation> check(String sql) {
    Objects.requireNonNull(sql, "sql");
    ParseCache.Parsed parsed = PARSE_CACHE.get(sql);
    if (!parsed.failed()) {
      return checkStatements(parsed.statements(), sql);
    }
    // One statement the parser rejects can hide the others of a multi-statement string, and the
    // keyword of the first one says nothing about the rest: split the string and check each part.
    List<String> parts = SqlText.statements(sql);
    if (parts.size() <= 1) {
      return checkUnparseable(sql);
    }
    List<Violation> violations = new ArrayList<>();
    for (String part : parts) {
      ParseCache.Parsed parsedPart = PARSE_CACHE.get(part);
      violations.addAll(
          parsedPart.failed()
              ? checkUnparseable(part)
              : checkStatements(parsedPart.statements(), part));
    }
    return List.copyOf(violations);
  }

  /**
   * Applies every rule to parsed statements. An unexpected failure while analysing a statement is
   * reported like a statement that does not parse, never thrown: the engine fails closed.
   */
  List<Violation> checkStatements(List<Statement> statements, String sql) {
    List<Violation> violations = new ArrayList<>();
    for (Statement statement : statements) {
      String statementSql = statements.size() == 1 ? sql : String.valueOf(statement);
      try {
        violations.addAll(checkStatement(statement, statementSql));
      } catch (RuntimeException | StackOverflowError e) {
        violations.addAll(
            parserViolations(
                statementSql, Messages::unanalysableMentioning, Messages.unanalysable()));
      }
    }
    return List.copyOf(violations);
  }

  private List<Violation> checkStatement(Statement statement, String sql) {
    if (statement instanceof ExplainStatement explain
        && explain.getOption(ExplainStatement.OptionType.ANALYZE) != null
        && explain.getStatement() != null) {
      // EXPLAIN ANALYZE executes the statement it explains (RP-13).
      return checkStatement(explain.getStatement(), sql);
    }
    List<Violation> violations = new ArrayList<>();
    for (Rule rule : policy.rules()) {
      if (rule instanceof RequirePredicateRule requirePredicate) {
        violations.addAll(new RequirePredicateAnalyzer(requirePredicate, sql).analyze(statement));
      } else if (rule instanceof UnboundedWriteRule unboundedWrite) {
        violations.addAll(UnboundedWriteCheck.check(unboundedWrite, statement, sql));
      }
    }
    return violations;
  }

  /** RP-13: ignored when it cannot touch rows, otherwise reported. */
  private List<Violation> checkUnparseable(String sql) {
    if (cannotTouchRows(sql)) {
      return List.of();
    }
    return parserViolations(sql, Messages::unparseableMentioning, Messages.unparseable());
  }

  private static boolean cannotTouchRows(String sql) {
    if (!IGNORED_WHEN_UNPARSEABLE.contains(SqlText.leadingKeyword(sql))) {
      return false;
    }
    List<String> words = SqlText.words(sql);
    for (int i = 0; i < words.size(); i++) {
      String word = words.get(i);
      String previous = i == 0 ? "" : words.get(i - 1);
      boolean foreignKeyAction =
          previous.equals("on") && (word.equals("delete") || word.equals("update"));
      boolean tableQuery = previous.equals("as") && word.equals("table"); // AS TABLE t
      if ((READS_OR_WRITES_ROWS.contains(word) && !foreignKeyAction) || tableQuery) {
        return false;
      }
    }
    return true;
  }

  /**
   * One violation per protected table the statement mentions, so a parser failure cannot hide a
   * table from the report; one table-less violation when it mentions none.
   */
  private List<Violation> parserViolations(
      String sql, Function<String, String> mentioning, String withoutTable) {
    List<String> mentioned = ProtectedTables.mentionedIn(sql, policy);
    if (mentioned.isEmpty()) {
      return List.of(
          new Violation(
              Violation.PARSER_RULE,
              null,
              Violation.Code.UNPARSEABLE,
              null,
              null,
              withoutTable,
              sql));
    }
    List<Violation> violations = new ArrayList<>(mentioned.size());
    for (String table : mentioned) {
      violations.add(
          new Violation(
              Violation.PARSER_RULE,
              null,
              Violation.Code.UNPARSEABLE,
              table,
              null,
              mentioning.apply(table),
              sql));
    }
    return List.copyOf(violations);
  }
}
