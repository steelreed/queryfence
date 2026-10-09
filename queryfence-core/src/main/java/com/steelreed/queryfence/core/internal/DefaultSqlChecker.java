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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.sf.jsqlparser.statement.Statement;

/** Default {@link SqlChecker}: parses once per SQL string, then applies every rule. */
public final class DefaultSqlChecker implements SqlChecker {

  /** Shared by all checkers: parse results do not depend on the policy. */
  private static final ParseCache PARSE_CACHE = new ParseCache(10_000);

  /**
   * Statements that carry no tenant data and that JSqlParser does not always understand (RP-13).
   * When such a statement fails to parse it is ignored instead of reported: tests routinely run
   * session setup and schema statements. Anything that could read or write rows stays fail closed.
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
          "explain",
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
          "do",
          "prepare",
          "deallocate",
          "execute",
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
          "notify",
          "copy");

  private final Policy policy;

  public DefaultSqlChecker(Policy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public List<Violation> check(String sql) {
    Objects.requireNonNull(sql, "sql");
    ParseCache.Parsed parsed = PARSE_CACHE.get(sql);
    if (parsed.failed()) {
      if (IGNORED_WHEN_UNPARSEABLE.contains(leadingKeyword(sql))) {
        return List.of();
      }
      return unparseable(sql);
    }
    List<Statement> statements = parsed.statements();
    List<Violation> violations = new ArrayList<>();
    for (Statement statement : statements) {
      String statementSql = statements.size() == 1 ? sql : statement.toString();
      for (Rule rule : policy.rules()) {
        if (rule instanceof RequirePredicateRule requirePredicate) {
          violations.addAll(
              new RequirePredicateAnalyzer(requirePredicate, statementSql).analyze(statement));
        } else if (rule instanceof UnboundedWriteRule unboundedWrite) {
          violations.addAll(UnboundedWriteCheck.check(unboundedWrite, statement, statementSql));
        }
      }
    }
    return List.copyOf(violations);
  }

  /** The first keyword of a statement, skipping leading comments and whitespace. */
  /**
   * One violation per protected table the statement mentions, so a parser failure cannot hide a
   * table from the report; one table-less violation when it mentions none.
   */
  private List<Violation> unparseable(String sql) {
    List<String> mentioned = ProtectedTables.mentionedIn(sql, policy);
    if (mentioned.isEmpty()) {
      return List.of(
          new Violation(
              Violation.PARSER_RULE,
              null,
              Violation.Code.UNPARSEABLE,
              null,
              null,
              Messages.unparseable(),
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
              Messages.unparseableMentioning(table),
              sql));
    }
    return List.copyOf(violations);
  }

  private static String leadingKeyword(String sql) {
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? sql.length() : end + 1;
      } else if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i);
        i = end < 0 ? sql.length() : end + 2;
      } else {
        break;
      }
    }
    int start = i;
    while (i < sql.length() && Character.isLetter(sql.charAt(i))) {
      i++;
    }
    return sql.substring(start, i).toLowerCase(Locale.ROOT);
  }
}
