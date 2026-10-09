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
import java.util.Collection;
import java.util.List;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

/** Small helpers over JSqlParser expressions. */
final class Expressions {

  private Expressions() {}

  /** Removes redundant parentheses around a single expression. */
  static Expression unwrap(Expression e) {
    Expression current = e;
    while (current instanceof ParenthesedExpressionList<?> list && list.size() == 1) {
      current = list.get(0);
    }
    return current;
  }

  /** Splits {@code a AND (b AND c)} into {@code [a, b, c]}. */
  static List<Expression> conjuncts(Expression e) {
    List<Expression> out = new ArrayList<>();
    collect(e, true, out);
    return out;
  }

  /** Splits {@code a OR (b OR c)} into {@code [a, b, c]}. */
  static List<Expression> disjuncts(Expression e) {
    List<Expression> out = new ArrayList<>();
    collect(e, false, out);
    return out;
  }

  private static void collect(Expression e, boolean and, List<Expression> out) {
    Expression u = unwrap(e);
    if (and && u instanceof AndExpression a) {
      collect(a.getLeftExpression(), true, out);
      collect(a.getRightExpression(), true, out);
    } else if (!and && u instanceof OrExpression o) {
      collect(o.getLeftExpression(), false, out);
      collect(o.getRightExpression(), false, out);
    } else {
      out.add(u);
    }
  }

  /** The subqueries directly reachable from the given expressions, without entering them. */
  static List<Select> subqueries(Collection<? extends Expression> expressions) {
    SubqueryCollector collector = new SubqueryCollector();
    for (Expression e : expressions) {
      if (e == null) {
        continue;
      }
      if (e instanceof Select select) {
        collector.found.add(select);
      } else {
        e.accept(collector, null);
      }
    }
    return collector.found;
  }

  /**
   * True when the condition is made only of constants and is always true, or is an OR with such an
   * operand (DESIGN.md UW-2).
   */
  static boolean alwaysTrue(Expression e) {
    Expression u = unwrap(e);
    if (u instanceof BooleanValue b) {
      return b.getValue();
    }
    if (u instanceof LongValue l) {
      return l.getValue() != 0;
    }
    if (u instanceof Column c && c.getTable() == null) {
      return "true".equalsIgnoreCase(c.getColumnName());
    }
    if (u instanceof OrExpression o) {
      return alwaysTrue(o.getLeftExpression()) || alwaysTrue(o.getRightExpression());
    }
    if (u instanceof AndExpression a) {
      return alwaysTrue(a.getLeftExpression()) && alwaysTrue(a.getRightExpression());
    }
    if (u instanceof EqualsTo eq) {
      return sameLiteral(eq.getLeftExpression(), eq.getRightExpression());
    }
    if (u instanceof NotEqualsTo ne) {
      Expression l = unwrap(ne.getLeftExpression());
      Expression r = unwrap(ne.getRightExpression());
      return isLiteral(l) && isLiteral(r) && !sameLiteral(l, r);
    }
    return false;
  }

  private static boolean isLiteral(Expression e) {
    return e instanceof LongValue || e instanceof DoubleValue || e instanceof StringValue;
  }

  private static boolean sameLiteral(Expression left, Expression right) {
    Expression l = unwrap(left);
    Expression r = unwrap(right);
    if (l instanceof LongValue a && r instanceof LongValue b) {
      return a.getValue() == b.getValue();
    }
    if (l instanceof DoubleValue a && r instanceof DoubleValue b) {
      return a.getValue() == b.getValue();
    }
    if (l instanceof StringValue a && r instanceof StringValue b) {
      return a.getValue().equals(b.getValue());
    }
    return false;
  }

  private static final class SubqueryCollector extends ExpressionVisitorAdapter<Void> {
    private final List<Select> found = new ArrayList<>();

    // Select.accept(ExpressionVisitor) dispatches to visit(Select); keep both to be safe.
    @Override
    public <S> Void visit(Select select, S context) {
      found.add(select);
      return null;
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
      found.add(select);
      return null;
    }
  }
}
