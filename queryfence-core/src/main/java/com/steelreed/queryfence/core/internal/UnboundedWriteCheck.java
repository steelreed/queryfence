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

import com.steelreed.queryfence.core.Violation;
import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;

/** {@code update-without-where} and {@code delete-without-where} (DESIGN.md UW-*, DW-*). */
final class UnboundedWriteCheck {

  private UnboundedWriteCheck() {}

  static List<Violation> check(UnboundedWriteRule rule, Statement statement, String sql) {
    Table table;
    Expression where;
    if (rule.kind() == UnboundedWriteRule.Kind.UPDATE && statement instanceof Update update) {
      table = update.getTable();
      where = update.getWhere();
    } else if (rule.kind() == UnboundedWriteRule.Kind.DELETE
        && statement instanceof Delete delete) {
      // Also set for the multi-table forms: DELETE o, i FROM ... reports its FROM table.
      table = delete.getTable();
      where = delete.getWhere();
    } else {
      return List.of();
    }
    String name = table == null ? null : Names.normalize(table.getName());
    String alias =
        table == null || table.getAlias() == null
            ? null
            : Names.unquote(table.getAlias().getName());
    if (where == null) {
      return List.of(
          violation(
              rule,
              Violation.Code.NO_WHERE,
              name,
              alias,
              Messages.noWhere(rule.kind(), name, alias),
              sql));
    }
    if (Expressions.alwaysTrue(where)) {
      return List.of(
          violation(
              rule,
              Violation.Code.TAUTOLOGICAL_WHERE,
              name,
              alias,
              Messages.tautologicalWhere(rule.kind(), name, alias),
              sql));
    }
    return List.of();
  }

  private static Violation violation(
      UnboundedWriteRule rule,
      Violation.Code code,
      String table,
      String alias,
      String message,
      String sql) {
    return new Violation(rule.id(), rule.type(), code, table, alias, message, sql);
  }
}
