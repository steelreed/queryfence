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

import java.util.List;

/**
 * Violation messages. Each states the problem and the fix; the templates are specified in DESIGN.md
 * "Violation model" and asserted verbatim by the golden corpus.
 */
final class Messages {

  private Messages() {}

  static String missingPredicate(String table, String alias, String column) {
    return display(table, alias)
        + " has no tenant filter. Add \""
        + ref(table, alias)
        + "."
        + column
        + " = ?\" to the WHERE clause of the query block that uses it.";
  }

  static String missingPredicateInRecursiveBranch(
      String table, String alias, String column, String cte) {
    return display(table, alias)
        + " has no tenant filter in the recursive branch of CTE \""
        + cte
        + "\". Add \"AND "
        + ref(table, alias)
        + "."
        + column
        + " = ?\" to that branch.";
  }

  static String primaryKeyLookup(String table, String alias, String column, String primaryKey) {
    return display(table, alias)
        + " is looked up by "
        + primaryKey
        + " only, and ids are easy to guess. Filter by tenant as well (findBy"
        + camelCase(primaryKey)
        + "And"
        + camelCase(column)
        + "(...) in Spring Data), or map the tenant on the entity (Hibernate @TenantId) so every"
        + " load carries it.";
  }

  /** {@code tenant_id} becomes {@code TenantId}, for the method name in the hint. */
  private static String camelCase(String column) {
    StringBuilder sb = new StringBuilder();
    boolean upper = true;
    for (char c : column.toCharArray()) {
      if (c == '_') {
        upper = true;
      } else {
        sb.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return sb.toString();
  }

  static String ambiguousColumn(List<String> tables, String exampleRef, String column) {
    return "Column \""
        + column
        + "\" is not qualified in a query block that reads "
        + String.join(", ", tables)
        + ", so it protects none of them. Qualify it with the table alias, for example \""
        + exampleRef
        + "."
        + column
        + " = ?\".";
  }

  /** How a table is shown in messages: {@code table (alias)} when it has an alias. */
  static String display(String table, String alias) {
    return alias == null ? table : table + " (" + alias + ")";
  }

  static String missingInsertColumn(String table, String column) {
    return "INSERT into "
        + table
        + " does not set "
        + column
        + ". Add "
        + column
        + " to the column list and bind the current tenant.";
  }

  static String noWhere(UnboundedWriteRule.Kind kind, String table, String alias) {
    return prefix(kind, table, alias)
        + " has no WHERE clause and "
        + verb(kind)
        + " every row. Add a WHERE clause that selects only the intended rows.";
  }

  static String tautologicalWhere(UnboundedWriteRule.Kind kind, String table, String alias) {
    return prefix(kind, table, alias)
        + " has a WHERE clause that is always true and "
        + verb(kind)
        + " every row. Replace it with a condition that selects only the intended rows.";
  }

  static String unsupportedStatement(String statement, String table, String alias) {
    return statement
        + " statements on "
        + display(table, alias)
        + " are not analysed yet. Rewrite the statement as INSERT or UPDATE, or suppress its origin"
        + " with a reason.";
  }

  static String unparseable() {
    return "QueryFence could not parse this statement, so it cannot prove it safe. Report the SQL"
        + " to QueryFence, or set onUnparseable: REPORT to only report it.";
  }

  static String unparseableMentioning(String table) {
    return "QueryFence could not parse this statement, so it cannot prove it safe. It mentions "
        + table
        + ", which stays unverified here: a missing filter on that table would go unnoticed."
        + " Report the SQL to QueryFence, or set onUnparseable: REPORT to only report it.";
  }

  private static String prefix(UnboundedWriteRule.Kind kind, String table, String alias) {
    return (kind == UnboundedWriteRule.Kind.UPDATE ? "UPDATE of " : "DELETE from ")
        + display(table, alias);
  }

  private static String verb(UnboundedWriteRule.Kind kind) {
    return kind == UnboundedWriteRule.Kind.UPDATE ? "changes" : "removes";
  }

  private static String ref(String table, String alias) {
    return alias == null ? table : alias;
  }
}
