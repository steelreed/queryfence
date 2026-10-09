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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.statement.upsert.Upsert;

/**
 * Weakens the tenant protection of a statement, one predicate at a time, on the parsed statement —
 * never on the SQL text, so formatting cannot fool it. Each {@link Transformation} turns a
 * statement QueryFence accepts into one it must reject.
 */
final class TenantPredicates {

  /** How one tenant predicate is weakened. */
  enum Transformation {
    /** Turn the predicate into a constant-true comparison, or drop the INSERT tenant column. */
    REMOVE,
    /** Replace the predicate with {@code predicate OR 1 = 1}, which selects every tenant. */
    OR_ALWAYS_TRUE,
    /** Bind the predicate to another table of the same query block. */
    REBIND_TO_OTHER_TABLE,
    /** Move the predicate from WHERE into the ON clause of a LEFT JOIN of another table. */
    MOVE_INTO_LEFT_JOIN_ON,
    /** Compare the tenant column with another column instead of a value. */
    COMPARE_WITH_COLUMN
  }

  record Mutation(Transformation transformation, String description, String sql) {}

  private static final String OTHER_COLUMN = "status";

  private TenantPredicates() {}

  /** Every applicable mutation of every tenant predicate of {@code sql}. */
  static List<Mutation> mutations(String sql, String column) {
    List<Mutation> mutations = new ArrayList<>();
    for (Transformation transformation : Transformation.values()) {
      mutations.addAll(mutations(sql, column, transformation));
    }
    return mutations;
  }

  /** The mutations of one transformation; empty when it applies to no predicate of {@code sql}. */
  static List<Mutation> mutations(String sql, String column, Transformation transformation) {
    Statements parsed = parseOrNull(sql);
    if (parsed == null) {
      return List.of();
    }
    int count = new Walker(column, -1, transformation).walk(parsed);
    List<Mutation> mutations = new ArrayList<>();
    for (int target = 0; target < count; target++) {
      Statements statements = parseOrNull(sql);
      if (statements == null) {
        continue;
      }
      Walker walker = new Walker(column, target, transformation);
      walker.walk(statements);
      if (walker.applied) {
        mutations.add(new Mutation(transformation, walker.description, join(statements)));
      }
    }
    return mutations;
  }

  private static Statements parseOrNull(String sql) {
    try {
      return CCJSqlParserUtil.parseStatements(sql);
    } catch (Exception e) {
      return null;
    }
  }

  private static String join(Statements statements) {
    StringBuilder sb = new StringBuilder();
    for (Statement statement : statements) {
      if (sb.length() > 0) {
        sb.append("; ");
      }
      sb.append(statement);
    }
    return sb.toString();
  }

  private static Expression alwaysTrue() {
    return new EqualsTo(new LongValue(1), new LongValue(1));
  }

  /** The query block a predicate belongs to: its table references, joins and tenant links. */
  private static final class Block {
    private final List<String> refs = new ArrayList<>();
    private final List<Join> joins = new ArrayList<>();

    /** Pairs of refs joined by a tenant-column equality, which fence each other (RP-3). */
    private final List<String[]> tenantLinks = new ArrayList<>();

    /** True when {@code a} and {@code b} are connected by a chain of tenant-column equalities. */
    boolean linked(String a, String b) {
      List<String> reachable = new ArrayList<>();
      reachable.add(a);
      for (int i = 0; i < reachable.size(); i++) {
        String from = reachable.get(i);
        for (String[] link : tenantLinks) {
          String next = null;
          if (link[0].equalsIgnoreCase(from)) {
            next = link[1];
          } else if (link[1].equalsIgnoreCase(from)) {
            next = link[0];
          }
          if (next != null && reachable.stream().noneMatch(next::equalsIgnoreCase)) {
            if (next.equalsIgnoreCase(b)) {
              return true;
            }
            reachable.add(next);
          }
        }
      }
      return false;
    }
  }

  /** Counts tenant predicates and transforms the one at index {@code target}. */
  private static final class Walker {

    private final String column;
    private final int target;
    private final Transformation transformation;
    private int index;
    private boolean applied;
    private String description = "nothing";
    private Block block = new Block();

    Walker(String column, int target, Transformation transformation) {
      this.column = column.toLowerCase(Locale.ROOT);
      this.target = target;
      this.transformation = transformation;
    }

    int walk(Statements statements) {
      for (Statement statement : statements) {
        statement(statement);
      }
      return index;
    }

    private void statement(Statement statement) {
      if (statement instanceof Select select) {
        select(select);
      } else if (statement instanceof Update update) {
        Block enclosing = enterBlock();
        reference(update.getTable());
        joins(update.getStartJoins());
        joins(update.getJoins());
        if (update.getFromItem() != null) {
          reference(update.getFromItem());
        }
        withItems(update.getWithItemsList());
        if (update.getUpdateSets() != null) {
          for (UpdateSet set : update.getUpdateSets()) {
            expressions(set.getValues());
          }
        }
        condition(update.getWhere(), update::setWhere, true);
        leaveBlock(enclosing);
      } else if (statement instanceof Delete delete) {
        Block enclosing = enterBlock();
        reference(delete.getTable());
        if (delete.getUsingFromItemList() != null) {
          delete.getUsingFromItemList().forEach(this::reference);
        }
        joins(delete.getJoins());
        withItems(delete.getWithItemsList());
        condition(delete.getWhere(), delete::setWhere, true);
        leaveBlock(enclosing);
      } else if (statement instanceof Insert insert) {
        withItems(insert.getWithItemsList());
        insertColumns(
            insert.getColumns(), valuesOf(insert.getSelect()), () -> insert.setColumns(null));
        updateSets(insert.getSetUpdateSets());
        if (insert.getSelect() != null) {
          select(insert.getSelect());
        }
      } else if (statement instanceof Upsert upsert) {
        insertColumns(
            upsert.getColumns(), valuesOf(upsert.getSelect()), () -> upsert.setColumns(null));
        updateSets(upsert.getUpdateSets());
        if (upsert.getSelect() != null) {
          select(upsert.getSelect());
        }
      }
    }

    private static Values valuesOf(Select select) {
      return select instanceof Values values ? values : null;
    }

    private void select(Select select) {
      withItems(select.getWithItemsList());
      if (select instanceof PlainSelect plain) {
        Block enclosing = enterBlock();
        reference(plain.getFromItem());
        joins(plain.getJoins());
        if (plain.getSelectItems() != null) {
          for (SelectItem<?> item : plain.getSelectItems()) {
            // Select items are walked for their subqueries only; nothing is replaced there.
            expression(item.getExpression(), replacement -> {});
          }
        }
        collectTenantLinks(plain.getWhere());
        condition(plain.getWhere(), plain::setWhere, true);
        condition(plain.getHaving(), plain::setHaving, false);
        leaveBlock(enclosing);
      } else if (select instanceof SetOperationList list) {
        list.getSelects().forEach(this::select);
      } else if (select instanceof Values values) {
        expressions(values.getExpressions());
      } else if (select.getSelectBody() != null && select.getSelectBody() != select) {
        select(select.getSelectBody());
      }
    }

    private Block enterBlock() {
      Block enclosing = block;
      block = new Block();
      return enclosing;
    }

    private void leaveBlock(Block enclosing) {
      block = enclosing;
    }

    private void reference(FromItem item) {
      if (item == null) {
        return;
      }
      if (item instanceof Table table) {
        String ref = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        block.refs.add(unquote(ref));
      } else if (item instanceof ParenthesedFromItem nested) {
        reference(nested.getFromItem());
        joins(nested.getJoins());
      } else if (item instanceof Select nested) {
        if (item.getAlias() != null) {
          block.refs.add(unquote(item.getAlias().getName()));
        }
        select(nested);
      }
    }

    private void withItems(List<WithItem<?>> withItems) {
      if (withItems != null) {
        for (WithItem<?> item : withItems) {
          if (item.getSelect() != null) {
            select(item.getSelect());
          }
        }
      }
    }

    private void joins(List<Join> joins) {
      if (joins == null) {
        return;
      }
      for (Join join : joins) {
        block.joins.add(join);
        reference(join.getFromItem());
        if (join.getOnExpressions() != null) {
          join.getOnExpressions().forEach(this::collectTenantLinks);
        }
      }
      for (Join join : joins) {
        onClause(join);
      }
    }

    /** Walks a join's ON clause, allowing a conjunct to be replaced in place. */
    private void onClause(Join join) {
      if (join.getOnExpressions() == null || join.getOnExpressions().isEmpty()) {
        return;
      }
      List<Expression> on = new ArrayList<>(join.getOnExpressions());
      for (int i = 0; i < on.size(); i++) {
        int position = i;
        condition(on.get(i), replacement -> on.set(position, replacement), false);
      }
      join.setOnExpressions(on);
    }

    /** Records the pairs of tables that a tenant-column equality fences together (RP-3). */
    private void collectTenantLinks(Expression expression) {
      if (expression instanceof AndExpression and) {
        collectTenantLinks(and.getLeftExpression());
        collectTenantLinks(and.getRightExpression());
      } else if (expression instanceof OrExpression or) {
        collectTenantLinks(or.getLeftExpression());
        collectTenantLinks(or.getRightExpression());
      } else if (expression instanceof ParenthesedExpressionList<?> list) {
        list.forEach(this::collectTenantLinks);
      } else if (expression instanceof EqualsTo equals
          && isTenantColumn(equals.getLeftExpression())
          && isTenantColumn(equals.getRightExpression())) {
        String left = qualifierOf((Column) equals.getLeftExpression());
        String right = qualifierOf((Column) equals.getRightExpression());
        if (left != null && right != null) {
          block.tenantLinks.add(new String[] {left, right});
        }
      }
    }

    private void condition(Expression expression, Consumer<Expression> replace, boolean inWhere) {
      if (expression != null) {
        expression(expression, replace, inWhere);
      }
    }

    private void expressions(ExpressionList<?> list) {
      if (list == null) {
        return;
      }
      for (Expression expression : list) {
        expression(expression, replacement -> {});
      }
    }

    private void updateSets(List<UpdateSet> sets) {
      if (sets == null || transformation != Transformation.REMOVE) {
        return;
      }
      for (UpdateSet set : sets) {
        for (int i = 0; i < set.getColumns().size(); i++) {
          if (isTenantColumn(set.getColumn(i)) && hit("INSERT ... SET " + column)) {
            set.getColumns().remove(i);
            set.getValues().remove(i);
            applied = true;
            return;
          }
        }
      }
    }

    private void insertColumns(
        ExpressionList<Column> columns, Values values, Runnable clearColumns) {
      if (columns == null || transformation != Transformation.REMOVE) {
        return;
      }
      for (int i = 0; i < columns.size(); i++) {
        if (isTenantColumn(columns.get(i)) && hit("INSERT column " + column)) {
          columns.remove(i);
          if (columns.isEmpty()) {
            clearColumns.run();
          }
          removeValueAt(values, i);
          applied = true;
          return;
        }
      }
    }

    private void removeValueAt(Values values, int position) {
      if (values == null || values.getExpressions() == null) {
        return;
      }
      ExpressionList<?> rows = values.getExpressions();
      if (rows instanceof ParenthesedExpressionList<?> singleRow) {
        singleRow.remove(position);
        return;
      }
      for (Expression row : rows) {
        if (row instanceof ParenthesedExpressionList<?> list) {
          list.remove(position);
        }
      }
    }

    private void expression(Expression expression, Consumer<Expression> replace) {
      expression(expression, replace, false);
    }

    private void expression(Expression expression, Consumer<Expression> replace, boolean inWhere) {
      if (expression == null) {
        return;
      }
      if (expression instanceof AndExpression and) {
        expression(and.getLeftExpression(), and::setLeftExpression, inWhere);
        expression(and.getRightExpression(), and::setRightExpression, inWhere);
      } else if (expression instanceof OrExpression or) {
        expression(or.getLeftExpression(), or::setLeftExpression, false);
        expression(or.getRightExpression(), or::setRightExpression, false);
      } else if (expression instanceof NotExpression not) {
        expression(not.getExpression(), not::setExpression, false);
      } else if (expression instanceof ExistsExpression exists) {
        expression(exists.getRightExpression(), exists::setRightExpression, false);
      } else if (expression instanceof Select nested) {
        select(nested);
      } else if (expression instanceof InExpression in) {
        if (isTenantColumn(in.getLeftExpression()) && !in.isNot()) {
          candidate(in, replace, inWhere, "IN predicate");
          return;
        }
        expression(in.getLeftExpression(), in::setLeftExpression, false);
        expression(in.getRightExpression(), in::setRightExpression, false);
      } else if (expression instanceof EqualsTo equals) {
        if (mentionsTenantColumn(equals)) {
          candidate(equals, replace, inWhere, "equality on " + column);
          return;
        }
        comparison(equals);
      } else if (expression instanceof ComparisonOperator comparison) {
        comparison(comparison);
      } else if (expression instanceof ParenthesedExpressionList<?> list) {
        @SuppressWarnings("unchecked")
        ExpressionList<Expression> typed = (ExpressionList<Expression>) list;
        for (int i = 0; i < typed.size(); i++) {
          int position = i;
          expression(typed.get(i), replacement -> typed.set(position, replacement), inWhere);
        }
      }
    }

    private void comparison(ComparisonOperator comparison) {
      expression(comparison.getLeftExpression(), comparison::setLeftExpression, false);
      expression(comparison.getRightExpression(), comparison::setRightExpression, false);
    }

    /** Applies the transformation to this predicate when it is the selected one and applicable. */
    private void candidate(
        Expression predicate, Consumer<Expression> replace, boolean inWhere, String what) {
      if (!hit(what)) {
        return;
      }
      applied =
          switch (transformation) {
            case REMOVE -> {
              replace.accept(alwaysTrue());
              yield true;
            }
            case OR_ALWAYS_TRUE -> {
              replace.accept(new OrExpression(predicate, alwaysTrue()));
              yield true;
            }
            case REBIND_TO_OTHER_TABLE -> rebind(predicate);
            case MOVE_INTO_LEFT_JOIN_ON -> moveIntoLeftJoin(predicate, replace, inWhere);
            case COMPARE_WITH_COLUMN -> compareWithColumn(predicate);
          };
    }

    /**
     * Binds the predicate to another table of the same block (RP-5). Only applies to a qualified
     * column in a block with at least two row sources: rebinding an unqualified column of a
     * single-table block would leave it bound to the same table.
     */
    private boolean rebind(Expression predicate) {
      Column tenantColumn = firstTenantColumn(predicate);
      if (tenantColumn == null) {
        return false;
      }
      String current = qualifierOf(tenantColumn);
      if (current == null || block.refs.size() < 2) {
        return false;
      }
      String other = otherRef(current);
      if (other == null) {
        return false;
      }
      tenantColumn.setTable(new Table(other));
      description += " rebound to " + other;
      return true;
    }

    /** Moves the predicate out of WHERE into the ON clause of a LEFT JOIN of another table. */
    private boolean moveIntoLeftJoin(
        Expression predicate, Consumer<Expression> replace, boolean inWhere) {
      if (!inWhere) {
        return false;
      }
      Column tenantColumn = firstTenantColumn(predicate);
      String ref = tenantColumn == null ? null : qualifierOf(tenantColumn);
      if (ref == null) {
        return false;
      }
      for (Join join : block.joins) {
        String joined = refOf(join.getFromItem());
        // A comma or cross join has no ON clause; turning it into a LEFT JOIN would not be SQL.
        if (joined == null || join.isSimple() || join.isCross() || joined.equalsIgnoreCase(ref)) {
          continue;
        }
        replace.accept(alwaysTrue());
        join.setInner(false);
        join.setLeft(true);
        List<Expression> on =
            join.getOnExpressions() == null
                ? new ArrayList<>()
                : new ArrayList<>(join.getOnExpressions());
        on.add(predicate);
        join.setOnExpressions(on);
        description += " moved into the LEFT JOIN of " + joined;
        return true;
      }
      return false;
    }

    /** Compares the tenant column with another column instead of a value (RP-2). */
    private boolean compareWithColumn(Expression predicate) {
      Column tenantColumn = firstTenantColumn(predicate);
      if (tenantColumn == null) {
        return false;
      }
      Column other = new Column(OTHER_COLUMN);
      String qualifier = qualifierOf(tenantColumn);
      if (qualifier != null) {
        other.setTable(new Table(qualifier));
      }
      if (predicate instanceof EqualsTo equals) {
        if (isTenantColumn(equals.getLeftExpression())
            && !(equals.getRightExpression() instanceof Column)) {
          equals.setRightExpression(other);
          return true;
        }
        if (isTenantColumn(equals.getRightExpression())
            && !(equals.getLeftExpression() instanceof Column)) {
          equals.setLeftExpression(other);
          return true;
        }
        return false;
      }
      if (predicate instanceof InExpression in
          && in.getRightExpression() instanceof ExpressionList<?> list
          && !list.isEmpty()) {
        @SuppressWarnings("unchecked")
        ExpressionList<Expression> typed = (ExpressionList<Expression>) list;
        typed.set(0, other);
        return true;
      }
      return false;
    }

    /**
     * A table of this block that the predicate can be rebound to. Tables already fenced together by
     * a tenant-column equality are skipped: moving the anchor along such a chain keeps the
     * statement safe, so it would not be a weakening.
     */
    private String otherRef(String current) {
      for (String ref : block.refs) {
        if (!ref.equalsIgnoreCase(current) && !block.linked(current, ref)) {
          return ref;
        }
      }
      return null;
    }

    private static String refOf(FromItem item) {
      if (item == null) {
        return null;
      }
      if (item.getAlias() != null) {
        return unquote(item.getAlias().getName());
      }
      return item instanceof Table table ? unquote(table.getName()) : null;
    }

    private Column firstTenantColumn(Expression predicate) {
      if (predicate instanceof EqualsTo equals) {
        if (isTenantColumn(equals.getLeftExpression())) {
          return (Column) equals.getLeftExpression();
        }
        return isTenantColumn(equals.getRightExpression())
            ? (Column) equals.getRightExpression()
            : null;
      }
      if (predicate instanceof InExpression in && isTenantColumn(in.getLeftExpression())) {
        return (Column) in.getLeftExpression();
      }
      return null;
    }

    private static String qualifierOf(Column column) {
      return column.getTable() == null || column.getTable().getName() == null
          ? null
          : unquote(column.getTable().getName());
    }

    private boolean mentionsTenantColumn(ComparisonOperator comparison) {
      return isTenantColumn(comparison.getLeftExpression())
          || isTenantColumn(comparison.getRightExpression());
    }

    private boolean isTenantColumn(Expression expression) {
      return expression instanceof Column c
          && column.equals(unquote(c.getColumnName()).toLowerCase(Locale.ROOT));
    }

    private static String unquote(String identifier) {
      if (identifier != null && identifier.length() >= 2) {
        char first = identifier.charAt(0);
        char last = identifier.charAt(identifier.length() - 1);
        if ((first == '"' && last == '"')
            || (first == '`' && last == '`')
            || (first == '[' && last == ']')) {
          return identifier.substring(1, identifier.length() - 1);
        }
      }
      return identifier;
    }

    /** True when this occurrence is the one to transform. */
    private boolean hit(String what) {
      boolean selected = index == target;
      if (selected) {
        description = what + " #" + index;
      }
      index++;
      return selected;
    }
  }
}
