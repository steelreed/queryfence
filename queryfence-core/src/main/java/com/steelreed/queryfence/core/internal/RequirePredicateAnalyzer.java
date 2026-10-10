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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.statement.upsert.Upsert;

/**
 * {@code require-predicate} for one statement (DESIGN.md RP-1..RP-13). One instance per rule and
 * statement; not thread-safe.
 */
final class RequirePredicateAnalyzer {

  /** A row source of a query block. Identity matters: a self-join has two sources. */
  private static final class Source {
    final String ref;
    final String table;
    final String alias;
    final boolean fenceable;

    /** The CTE this source refers to, or {@code null} when it is a real table. */
    final String cteRef;

    Source(String ref, String table, String alias, boolean fenceable, String cteRef) {
      this.ref = ref;
      this.table = table;
      this.alias = alias;
      this.fenceable = fenceable;
      this.cteRef = cteRef;
    }
  }

  /** One query block: its row sources, and the fenced ones once computed. */
  private static final class Scope {
    final Scope parent;
    final List<Source> sources = new ArrayList<>();
    final Set<Source> fenced = new HashSet<>();
    boolean ambiguousColumn;

    Scope(Scope parent) {
      this.parent = parent;
    }

    Source lookup(String ref) {
      for (Scope s = this; s != null; s = s.parent) {
        for (Source source : s.sources) {
          if (source.ref.equals(ref)) {
            return source;
          }
        }
      }
      return null;
    }

    Set<Source> fencedInEnclosingBlocks() {
      Set<Source> out = new HashSet<>();
      for (Scope s = parent; s != null; s = s.parent) {
        out.addAll(s.fenced);
      }
      return out;
    }
  }

  private final RequirePredicateRule rule;
  private final String sql;
  private final List<Violation> violations = new ArrayList<>();

  /** Table nodes checked as occurrences, by identity: the rest are reported as not analysed. */
  private final Set<Table> checkedTables = Collections.newSetFromMap(new IdentityHashMap<>());

  /** Name of the recursive CTE whose body is being analyzed, for RP-9 messages. */
  private String recursiveCte;

  RequirePredicateAnalyzer(RequirePredicateRule rule, String sql) {
    this.rule = rule;
    this.sql = sql;
  }

  List<Violation> analyze(Statement statement) {
    if (statement instanceof Merge merge) {
      reportMerge(merge);
      return violations;
    }
    Statement analysed = rowReadingPart(statement);
    if (analysed instanceof Select select) {
      analyzeSelect(select, null, Set.of());
    } else if (analysed instanceof Update update) {
      analyzeUpdate(update);
    } else if (analysed instanceof Delete delete) {
      analyzeDelete(delete);
    } else if (analysed instanceof Insert insert) {
      analyzeInsert(insert);
    } else if (analysed instanceof Upsert upsert) {
      checkInsertTarget(upsert.getTable(), upsert.getColumns(), upsert.getUpdateSets());
      if (upsert.getSelect() != null) {
        analyzeSelect(upsert.getSelect(), null, Set.of());
      }
    } else if (analysed instanceof SetStatement set) {
      analyzeSet(set);
      return violations;
    } else {
      // Everything else (DDL, TRUNCATE, transaction control, CALL, ...) is out of scope (RP-13).
      return violations;
    }
    reportUnanalysed(TableOccurrences.in(analysed));
    return violations;
  }

  /**
   * The part of a statement that reads or writes rows: the statement itself, or the query that
   * {@code CREATE TABLE ... AS SELECT} and {@code CREATE MATERIALIZED VIEW} copy rows from (RP-13).
   * A plain view reads no rows when it is created and stays out of scope.
   */
  private static Statement rowReadingPart(Statement statement) {
    if (statement instanceof CreateTable create) {
      return create.getSelect();
    }
    if (statement instanceof CreateView view) {
      return view.isMaterialized() ? view.getSelect() : null;
    }
    return statement;
  }

  /** MERGE is not analysed: every protected table it names is reported (RP-13). */
  private void reportMerge(Merge merge) {
    for (Table table : TableOccurrences.in(merge)) {
      if (isProtected(table)) {
        String alias = aliasOf(table);
        String name = Names.normalize(table.getName());
        violations.add(
            violation(
                Violation.Code.UNSUPPORTED_STATEMENT,
                name,
                alias,
                Messages.unsupportedStatement("MERGE", name, alias)));
      }
    }
  }

  /**
   * Fail closed for clauses the analyzer does not walk: a protected table the statement names that
   * was never checked as an occurrence is reported (RP-13).
   */
  private void reportUnanalysed(List<Table> tables) {
    for (Table table : tables) {
      if (!checkedTables.contains(table) && isProtected(table)) {
        String alias = aliasOf(table);
        String name = Names.normalize(table.getName());
        violations.add(
            violation(
                Violation.Code.UNSUPPORTED_STATEMENT,
                name,
                alias,
                Messages.unanalysedOccurrence(name, alias)));
      }
    }
  }

  /** MySQL {@code SET @x = (SELECT ...)}: the subqueries of the assigned values (RP-7). */
  private void analyzeSet(SetStatement set) {
    List<Expression> values = new ArrayList<>();
    for (int i = 0; i < set.getCount(); i++) {
      if (set.getExpressions(i) != null) {
        values.addAll(set.getExpressions(i));
      }
    }
    analyzeSubqueries(values, null, Set.of());
    for (Expression value : values) {
      reportUnanalysed(TableOccurrences.in(value));
    }
  }

  // ---------------------------------------------------------------- statements

  private void analyzeSelect(Select select, Scope parent, Set<String> ctes) {
    Set<String> env = withItems(select.getWithItemsList(), parent, ctes);
    // ORDER BY, LIMIT, OFFSET and FETCH: a PlainSelect reads them in its own block, so a correlated
    // subquery there can anchor on it; the other forms read them in the enclosing block.
    List<Expression> tail = new ArrayList<>();
    addOrderBy(tail, select.getOrderByElements());
    addLimit(tail, select.getLimit());
    addLimit(tail, select.getLimitBy());
    if (select.getOffset() != null) {
      tail.add(select.getOffset().getOffset());
    }
    if (select.getFetch() != null) {
      tail.add(select.getFetch().getExpression());
    }
    if (select instanceof PlainSelect ps) {
      List<Expression> others = new ArrayList<>(tail);
      addSelectItems(others, ps.getSelectItems());
      if (ps.getDistinct() != null) {
        addSelectItems(others, ps.getDistinct().getOnSelectItems());
      }
      others.add(ps.getHaving());
      others.add(ps.getQualify());
      others.add(ps.getOracleHierarchical());
      if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
        for (Object e : ps.getGroupBy().getGroupByExpressionList()) {
          others.add((Expression) e);
        }
      }
      if (ps.getWindowDefinitions() != null) {
        for (WindowDefinition window : ps.getWindowDefinitions()) {
          if (window.getPartitionExpressionList() != null) {
            for (Object e : window.getPartitionExpressionList()) {
              others.add((Expression) e);
            }
          }
          addOrderBy(others, window.getOrderByElements());
        }
      }
      List<FromItem> items = new ArrayList<>();
      if (ps.getFromItem() != null) {
        items.add(ps.getFromItem());
      }
      analyzeBlock(items, ps.getJoins(), ps.getWhere(), others, parent, env);
      return;
    }
    if (select instanceof SetOperationList list) {
      for (Select branch : list.getSelects()) {
        analyzeSelect(branch, parent, env);
      }
    } else if (select instanceof ParenthesedSelect parenthesed) {
      analyzeSelect(parenthesed.getSelect(), parent, env);
    } else if (select instanceof Values values) {
      if (values.getExpressions() != null) {
        tail.addAll(values.getExpressions());
      }
    } else if (select instanceof TableStatement table) {
      // Postgres TABLE t: SELECT * FROM t without a WHERE clause.
      analyzeBlock(List.of(table.getTable()), null, null, tail, parent, env);
      return;
    }
    analyzeSubqueries(tail, parent, env);
  }

  private void analyzeUpdate(Update update) {
    Set<String> ctes = withItems(update.getWithItemsList(), null, Set.of());
    List<FromItem> items = new ArrayList<>();
    items.add(update.getTable());
    List<Join> joins = new ArrayList<>();
    if (update.getStartJoins() != null) {
      joins.addAll(update.getStartJoins());
    }
    if (update.getFromItem() != null) {
      items.add(update.getFromItem());
    }
    if (update.getJoins() != null) {
      joins.addAll(update.getJoins());
    }
    List<Expression> others = new ArrayList<>();
    addUpdateSetValues(others, update.getUpdateSets());
    addOrderBy(others, update.getOrderByElements());
    addLimit(others, update.getLimit());
    addSelectItems(others, update.getReturningClause());
    analyzeBlock(items, joins, update.getWhere(), others, null, ctes);
  }

  private void analyzeDelete(Delete delete) {
    Set<String> ctes = withItems(delete.getWithItemsList(), null, Set.of());
    List<FromItem> items = new ArrayList<>();
    if (delete.getTable() != null) {
      items.add(delete.getTable());
    }
    if (delete.getUsingFromItemList() != null) {
      items.addAll(delete.getUsingFromItemList());
    }
    List<Expression> others = new ArrayList<>();
    addOrderBy(others, delete.getOrderByElements());
    addLimit(others, delete.getLimit());
    addSelectItems(others, delete.getReturningClause());
    analyzeBlock(items, delete.getJoins(), delete.getWhere(), others, null, ctes);
  }

  /**
   * RP-11 for the target, then the rows it reads: the source query and the subqueries of a MySQL
   * {@code INSERT ... SET} list and of {@code RETURNING}. The upsert branches ({@code ON CONFLICT
   * DO UPDATE}, {@code ON DUPLICATE KEY UPDATE}) are not analysed, so a protected table there is
   * reported as not analysed.
   */
  private void analyzeInsert(Insert insert) {
    Set<String> ctes = withItems(insert.getWithItemsList(), null, Set.of());
    checkInsertTarget(insert.getTable(), insert.getColumns(), insert.getSetUpdateSets());
    if (insert.getSelect() != null) {
      analyzeSelect(insert.getSelect(), null, ctes);
    }
    List<Expression> others = new ArrayList<>();
    addUpdateSetValues(others, insert.getSetUpdateSets());
    addSelectItems(others, insert.getReturningClause());
    analyzeSubqueries(others, null, ctes);
  }

  /** RP-11: the tenant column must be in the explicit column list (or MySQL SET list). */
  private void checkInsertTarget(Table table, List<Column> columns, List<UpdateSet> setUpdateSets) {
    if (table != null) {
      checkedTables.add(table);
    }
    if (table == null || !isProtected(table)) {
      return;
    }
    boolean present = containsColumn(columns);
    if (!present && setUpdateSets != null) {
      for (UpdateSet set : setUpdateSets) {
        present |= containsColumn(set.getColumns());
      }
    }
    if (!present) {
      String name = Names.normalize(table.getName());
      violations.add(
          violation(
              Violation.Code.MISSING_INSERT_COLUMN,
              name,
              null,
              Messages.missingInsertColumn(name, rule.column())));
    }
  }

  private boolean containsColumn(List<Column> columns) {
    if (columns == null) {
      return false;
    }
    for (Column c : columns) {
      if (rule.column().equals(Names.normalize(c.getColumnName()))) {
        return true;
      }
    }
    return false;
  }

  /** Analyzes CTE bodies (RP-9) and returns the CTE names visible to the statement. */
  private Set<String> withItems(List<WithItem<?>> withItems, Scope parent, Set<String> ctes) {
    if (withItems == null || withItems.isEmpty()) {
      return ctes;
    }
    Set<String> env = new HashSet<>(ctes);
    for (WithItem<?> item : withItems) {
      env.add(Names.normalize(item.getAliasName()));
    }
    for (WithItem<?> item : withItems) {
      if (item.getSelect() == null) {
        continue;
      }
      String previous = recursiveCte;
      if (item.isRecursive()) {
        recursiveCte = Names.normalize(item.getAliasName());
      }
      analyzeSelect(item.getSelect(), parent, env);
      recursiveCte = previous;
    }
    return env;
  }

  // ---------------------------------------------------------------- query blocks

  private void analyzeBlock(
      List<FromItem> items,
      List<Join> joins,
      Expression where,
      List<Expression> others,
      Scope parent,
      Set<String> ctes) {
    Scope scope = new Scope(parent);
    List<Expression> base = new ArrayList<>();
    Map<Source, List<Expression>> leftJoinConditions = new LinkedHashMap<>();
    List<Expression> allJoinConditions = new ArrayList<>();

    for (FromItem item : items) {
      addFromItem(item, scope, parent, ctes, base, leftJoinConditions, allJoinConditions);
    }
    if (joins != null) {
      for (Join join : joins) {
        addJoin(join, scope, parent, ctes, base, leftJoinConditions, allJoinConditions);
      }
    }
    if (where != null) {
      base.add(where);
    }

    computeFenced(scope, base, leftJoinConditions);
    reportUnfenced(scope, base, leftJoinConditions);

    List<Expression> subqueryHolders = new ArrayList<>();
    if (where != null) {
      subqueryHolders.add(where);
    }
    subqueryHolders.addAll(allJoinConditions);
    subqueryHolders.addAll(others);
    analyzeSubqueries(subqueryHolders, scope, ctes);
  }

  private Source addFromItem(
      FromItem item,
      Scope scope,
      Scope parent,
      Set<String> ctes,
      List<Expression> base,
      Map<Source, List<Expression>> leftJoinConditions,
      List<Expression> allJoinConditions) {
    if (item instanceof Table table) {
      checkedTables.add(table);
      String name = Names.normalize(table.getName());
      String alias = aliasOf(table);
      boolean isCte = table.getSchemaName() == null && ctes.contains(name);
      boolean fenceable = !isCte && isProtected(table);
      Source source =
          new Source(
              alias != null ? Names.normalize(alias) : name,
              name,
              alias,
              fenceable,
              isCte ? name : null);
      scope.sources.add(source);
      return source;
    }
    if (item instanceof ParenthesedFromItem nested) {
      addFromItem(
          nested.getFromItem(), scope, parent, ctes, base, leftJoinConditions, allJoinConditions);
      if (nested.getJoins() != null) {
        for (Join join : nested.getJoins()) {
          addJoin(join, scope, parent, ctes, base, leftJoinConditions, allJoinConditions);
        }
      }
      return null;
    }
    // Derived table, VALUES, table function, lateral subquery: a row source, never fenceable.
    // A derived table is its own block; outer filters do not reach inside it (RP-7).
    if (item instanceof Select select) {
      analyzeSelect(select, parent, ctes);
    }
    String alias = item.getAlias() == null ? null : Names.unquote(item.getAlias().getName());
    Source source =
        new Source(alias == null ? "" : Names.normalize(alias), null, alias, false, null);
    scope.sources.add(source);
    return source;
  }

  private void addJoin(
      Join join,
      Scope scope,
      Scope parent,
      Set<String> ctes,
      List<Expression> base,
      Map<Source, List<Expression>> leftJoinConditions,
      List<Expression> allJoinConditions) {
    Source joined =
        addFromItem(
            join.getFromItem(), scope, parent, ctes, base, leftJoinConditions, allJoinConditions);
    Collection<Expression> on = join.getOnExpressions();
    if (on == null || on.isEmpty()) {
      return;
    }
    allJoinConditions.addAll(on);
    if (join.isRight() || join.isFull() || join.isNatural() || join.isApply()) {
      return; // RP-6: these ON clauses do not fence
    }
    if (join.isLeft()) {
      if (joined != null) {
        leftJoinConditions.computeIfAbsent(joined, k -> new ArrayList<>()).addAll(on);
      }
      return;
    }
    base.addAll(on); // inner (or plain) join: filters like WHERE
  }

  /** RP-4 and RP-6: fixpoint over the block's conditions. */
  private void computeFenced(
      Scope scope, List<Expression> base, Map<Source, List<Expression>> leftJoinConditions) {
    Set<Source> outer = scope.fencedInEnclosingBlocks();
    boolean changed = true;
    while (changed) {
      changed = false;
      Set<Source> known = union(outer, scope.fenced);
      for (Source s : fencesAll(base, known, scope)) {
        if (scope.sources.contains(s) && scope.fenced.add(s)) {
          changed = true;
        }
      }
      for (Map.Entry<Source, List<Expression>> entry : leftJoinConditions.entrySet()) {
        Source joined = entry.getKey();
        if (!scope.fenced.contains(joined)
            && fencesAll(entry.getValue(), union(outer, scope.fenced), scope).contains(joined)) {
          scope.fenced.add(joined);
          changed = true;
        }
      }
    }
  }

  private void reportUnfenced(
      Scope scope, List<Expression> base, Map<Source, List<Expression>> leftJoinConditions) {
    List<Source> unfenced = new ArrayList<>();
    for (Source s : scope.sources) {
      if (s.fenceable && !scope.fenced.contains(s)) {
        unfenced.add(s);
      }
    }
    if (unfenced.isEmpty()) {
      return;
    }
    if (scope.ambiguousColumn) {
      List<String> candidates = new ArrayList<>();
      for (Source s : unfenced) {
        candidates.add(Messages.display(s.table, s.alias));
      }
      Source first = unfenced.get(0);
      violations.add(
          violation(
              Violation.Code.AMBIGUOUS_COLUMN,
              null,
              null,
              Messages.ambiguousColumn(
                  candidates, first.alias == null ? first.table : first.alias, rule.column())));
      return;
    }
    boolean recursiveBranch = recursiveCte != null && referencesCte(scope, recursiveCte);
    for (Source s : unfenced) {
      if (!recursiveBranch && looksUpByPrimaryKey(s, base, leftJoinConditions, scope)) {
        violations.add(
            violation(
                Violation.Code.PRIMARY_KEY_LOOKUP,
                s.table,
                s.alias,
                Messages.primaryKeyLookup(s.table, s.alias, rule.column(), rule.primaryKey())));
        continue;
      }
      violations.add(
          violation(
              Violation.Code.MISSING_PREDICATE,
              s.table,
              s.alias,
              recursiveBranch
                  ? Messages.missingPredicateInRecursiveBranch(
                      s.table, s.alias, rule.column(), recursiveCte)
                  : Messages.missingPredicate(s.table, s.alias, rule.column())));
    }
  }

  /**
   * True when the only thing narrowing this occurrence is its primary key: {@code where o.id = ?}.
   * The rows are still one tenant's rows only by luck, but the fix is a different one, so the
   * message differs (RP-14).
   */
  private boolean looksUpByPrimaryKey(
      Source source,
      List<Expression> base,
      Map<Source, List<Expression>> leftJoinConditions,
      Scope scope) {
    List<Expression> conditions = new ArrayList<>(base);
    List<Expression> joined = leftJoinConditions.get(source);
    if (joined != null) {
      conditions.addAll(joined);
    }
    for (Expression condition : conditions) {
      for (Expression conjunct : Expressions.conjuncts(condition)) {
        if (isPrimaryKeyValuePredicate(conjunct, source, scope)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isPrimaryKeyValuePredicate(Expression conjunct, Source source, Scope scope) {
    Expression e = Expressions.unwrap(conjunct);
    if (e instanceof EqualsTo equals) {
      return (boundTo(equals.getLeftExpression(), source, scope)
              && isValue(equals.getRightExpression()))
          || (boundTo(equals.getRightExpression(), source, scope)
              && isValue(equals.getLeftExpression()));
    }
    if (e instanceof InExpression in && !in.isNot()) {
      return boundTo(in.getLeftExpression(), source, scope)
          && in.getRightExpression() instanceof ExpressionList<?> list
          && !list.isEmpty()
          && allValues(list);
    }
    return false;
  }

  /** True when the expression is this occurrence's primary-key column. */
  private boolean boundTo(Expression expression, Source source, Scope scope) {
    if (!(expression instanceof Column column)
        || !rule.primaryKey().equals(Names.normalize(column.getColumnName()))) {
      return false;
    }
    Table qualifier = column.getTable();
    if (qualifier == null || qualifier.getName() == null) {
      return scope.sources.size() == 1 && scope.sources.get(0) == source;
    }
    return scope.lookup(Names.normalize(qualifier.getName())) == source;
  }

  /** True when the block reads the given CTE, which makes it the recursive branch (RP-9). */
  private static boolean referencesCte(Scope scope, String cte) {
    for (Source s : scope.sources) {
      if (cte.equals(s.cteRef)) {
        return true;
      }
    }
    return false;
  }

  private void analyzeSubqueries(List<Expression> holders, Scope scope, Set<String> ctes) {
    for (Select subquery : Expressions.subqueries(holders)) {
      analyzeSelect(subquery, scope, ctes);
    }
  }

  // ---------------------------------------------------------------- fencing (RP-2..RP-5)

  /** The occurrences fenced by a list of conditions combined with AND. */
  private Set<Source> fencesAll(List<Expression> conditions, Set<Source> known, Scope scope) {
    List<Expression> conjuncts = new ArrayList<>();
    for (Expression c : conditions) {
      conjuncts.addAll(Expressions.conjuncts(c));
    }
    return fencesAnd(conjuncts, known, scope);
  }

  private Set<Source> fencesAnd(List<Expression> conjuncts, Set<Source> known, Scope scope) {
    Set<Source> acc = new HashSet<>();
    boolean changed = true;
    while (changed) {
      changed = false;
      Set<Source> k = union(known, acc);
      for (Expression c : conjuncts) {
        if (acc.addAll(fences(c, k, scope))) {
          changed = true;
        }
      }
    }
    return acc;
  }

  private Set<Source> fences(Expression condition, Set<Source> known, Scope scope) {
    Expression e = Expressions.unwrap(condition);
    if (e instanceof AndExpression) {
      return fencesAnd(Expressions.conjuncts(e), known, scope);
    }
    if (e instanceof OrExpression) {
      Set<Source> result = null;
      for (Expression branch : Expressions.disjuncts(e)) {
        Set<Source> fenced = fences(branch, known, scope);
        if (result == null) {
          result = new HashSet<>(fenced);
        } else {
          result.retainAll(fenced);
        }
      }
      return result == null ? Set.of() : result;
    }
    if (e instanceof EqualsTo eq) {
      return fencesEquality(eq.getLeftExpression(), eq.getRightExpression(), known, scope);
    }
    if (e instanceof InExpression in && !in.isNot()) {
      Expression right = in.getRightExpression();
      if (right instanceof ExpressionList<?> list && !list.isEmpty() && allValues(list)) {
        Source s = tenantColumn(in.getLeftExpression(), scope, true);
        if (s != null) {
          return Set.of(s);
        }
      }
    }
    return Set.of();
  }

  private Set<Source> fencesEquality(
      Expression left, Expression right, Set<Source> known, Scope scope) {
    Expression l = Expressions.unwrap(left);
    Expression r = Expressions.unwrap(right);
    if (isValue(r)) {
      Source s = tenantColumn(l, scope, true);
      return s == null ? Set.of() : Set.of(s);
    }
    if (isValue(l)) {
      Source s = tenantColumn(r, scope, true);
      return s == null ? Set.of() : Set.of(s);
    }
    boolean candidate = isTenantColumnName(l) && isTenantColumnName(r);
    Source ls = tenantColumn(l, scope, candidate);
    Source rs = tenantColumn(r, scope, candidate);
    if (ls != null && rs != null && ls != rs) {
      if (known.contains(rs)) {
        return Set.of(ls);
      }
      if (known.contains(ls)) {
        return Set.of(rs);
      }
    }
    return Set.of();
  }

  /**
   * Resolves {@code expression} to a fenceable occurrence when it is the rule's column bound to it
   * (RP-5). Records an ambiguous use when the column is unqualified in a multi-source block.
   */
  private Source tenantColumn(Expression expression, Scope scope, boolean candidatePredicate) {
    if (!(expression instanceof Column column) || !isTenantColumnName(column)) {
      return null;
    }
    Table qualifier = column.getTable();
    Source source;
    if (qualifier == null || qualifier.getName() == null) {
      if (scope.sources.size() != 1) {
        if (candidatePredicate) {
          scope.ambiguousColumn = true;
        }
        return null;
      }
      source = scope.sources.get(0);
    } else {
      source = scope.lookup(Names.normalize(qualifier.getName()));
    }
    return source != null && source.fenceable ? source : null;
  }

  private boolean isTenantColumnName(Expression expression) {
    return expression instanceof Column column
        && rule.column().equals(Names.normalize(column.getColumnName()));
  }

  /** RP-2 values: parameters, literals, allowed functions, and casts of those. */
  private boolean isValue(Expression expression) {
    Expression e = Expressions.unwrap(expression);
    if (e instanceof JdbcParameter
        || e instanceof JdbcNamedParameter
        || e instanceof LongValue
        || e instanceof DoubleValue
        || e instanceof StringValue) {
      return true;
    }
    if (e instanceof SignedExpression signed) {
      return isValue(signed.getExpression());
    }
    if (e instanceof CastExpression cast) {
      return isValue(cast.getLeftExpression());
    }
    if (e instanceof Function function) {
      List<String> parts = function.getMultipartName();
      String name =
          parts == null || parts.isEmpty() ? function.getName() : parts.get(parts.size() - 1);
      return name != null && rule.allowedFunctions().contains(Names.normalize(name));
    }
    return false;
  }

  private boolean allValues(ExpressionList<?> list) {
    for (Expression e : list) {
      if (!isValue(e)) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- helpers

  private boolean isProtected(Table table) {
    return rule.protects(Names.normalize(table.getSchemaName()), Names.normalize(table.getName()));
  }

  private static String aliasOf(Table table) {
    return table.getAlias() == null ? null : Names.unquote(table.getAlias().getName());
  }

  private static void addOrderBy(List<Expression> out, List<OrderByElement> orderBy) {
    if (orderBy != null) {
      for (OrderByElement element : orderBy) {
        out.add(element.getExpression());
      }
    }
  }

  private static void addSelectItems(List<Expression> out, List<? extends SelectItem<?>> items) {
    if (items != null) {
      for (SelectItem<?> item : items) {
        out.add(item.getExpression());
      }
    }
  }

  private static void addLimit(List<Expression> out, Limit limit) {
    if (limit != null) {
      out.add(limit.getRowCount());
      out.add(limit.getOffset());
      if (limit.getByExpressions() != null) {
        out.addAll(limit.getByExpressions());
      }
    }
  }

  private static void addUpdateSetValues(List<Expression> out, List<UpdateSet> sets) {
    if (sets != null) {
      for (UpdateSet set : sets) {
        if (set.getValues() != null) {
          out.addAll(set.getValues());
        }
      }
    }
  }

  private static Set<Source> union(Set<Source> a, Set<Source> b) {
    Set<Source> out = new HashSet<>(a);
    out.addAll(b);
    return out;
  }

  private Violation violation(Violation.Code code, String table, String alias, String message) {
    return new Violation(rule.id(), rule.type(), code, table, alias, message, sql);
  }
}
