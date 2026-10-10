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

import java.util.ArrayList;
import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Every table node of a parsed statement, wherever it appears, found by JSqlParser's own full walk
 * of the tree. The analyzer walks the clauses it understands; comparing what it walked with this
 * list is how a table in a clause it does not understand is reported instead of skipped (fail
 * closed). Column qualifiers ({@code o} in {@code o.tenant_id}) are not tables and are not listed.
 */
final class TableOccurrences {

  private TableOccurrences() {}

  /**
   * The table nodes of a statement, in walk order.
   *
   * @throws UnsupportedOperationException when JSqlParser cannot walk this statement type
   */
  static List<Table> in(Statement statement) {
    Collector collector = new Collector();
    collector.getTables(statement);
    return collector.found;
  }

  /** The table nodes of an expression, for statements that are only a list of expressions. */
  static List<Table> in(Expression expression) {
    Collector collector = new Collector();
    collector.getTables(expression);
    return collector.found;
  }

  private static final class Collector extends TablesNamesFinder<Void> {
    private final List<Table> found = new ArrayList<>();

    @Override
    public <S> Void visit(Table table, S context) {
      found.add(table);
      return super.visit(table, context);
    }
  }
}
