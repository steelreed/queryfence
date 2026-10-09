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
package com.steelreed.queryfence.jdbc;

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.SqlChecker;
import com.steelreed.queryfence.core.Suppression;
import com.steelreed.queryfence.core.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects the statements executed through a wrapped {@code DataSource} and checks them against the
 * policy. Thread-safe: statements executed on other threads during a test are recorded too.
 */
public final class QueryRecorder {

  private final Policy policy;
  private final SqlChecker checker;
  private final List<CapturedStatement> statements = new CopyOnWriteArrayList<>();
  private final Set<Suppression> matchedSuppressions = ConcurrentHashMap.newKeySet();

  QueryRecorder(Policy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.checker = SqlChecker.of(policy);
  }

  /**
   * The statements recorded so far.
   *
   * @return an unmodifiable snapshot, oldest first
   */
  public List<CapturedStatement> statements() {
    return List.copyOf(statements);
  }

  /** Forgets every recorded statement; the next test starts from an empty list. */
  public void clear() {
    statements.clear();
  }

  /**
   * The suppressions of the policy that have silenced at least one violation so far. Kept for the
   * whole run — {@link #clear()} does not forget them — so the report can name the suppressions
   * that matched nothing, which usually means the code they point at has moved.
   *
   * @return the matched suppressions, never {@code null}
   */
  public Set<Suppression> matchedSuppressions() {
    return Set.copyOf(matchedSuppressions);
  }

  /**
   * The policy these statements are checked against.
   *
   * @return the policy given to {@link QueryFence#wrap(javax.sql.DataSource, Policy)}
   */
  public Policy policy() {
    return policy;
  }

  /**
   * The violations of the recorded statements, each with the code that produced it. Suppressions of
   * the policy are applied here, because they are keyed by origin.
   *
   * @return an unmodifiable list, empty when every statement satisfied the policy
   */
  public List<Finding> findings() {
    List<Finding> findings = new ArrayList<>();
    for (CapturedStatement statement : statements) {
      for (Violation violation : checker.check(statement.sql())) {
        if (!isSuppressed(violation, statement.origin())) {
          findings.add(new Finding(violation, statement));
        }
      }
    }
    return List.copyOf(findings);
  }

  private boolean isSuppressed(Violation violation, Origin origin) {
    if (!origin.isKnown()) {
      return false;
    }
    for (Suppression suppression : policy.suppressions()) {
      if (suppression.ruleId().equals(violation.ruleId())
          && suppression.origin().equals(origin.classAndMethod())) {
        matchedSuppressions.add(suppression);
        return true;
      }
    }
    return false;
  }

  void record(CapturedStatement statement) {
    statements.add(statement);
  }

  /**
   * A violation together with the statement and the code that produced it.
   *
   * @param violation what the rule engine found
   * @param statement the statement it was found in, with its origin
   */
  public record Finding(Violation violation, CapturedStatement statement) {

    /**
     * The code that produced the statement.
     *
     * @return the origin of {@link #statement()}
     */
    public Origin origin() {
      return statement.origin();
    }

    @Override
    public String toString() {
      return "["
          + violation.ruleId()
          + "] "
          + violation.code()
          + "\n    "
          + violation.message()
          + "\n    sql    : "
          + statement.sql()
          + "\n    origin : "
          + statement.origin();
    }
  }
}
