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

import com.steelreed.queryfence.core.internal.RequirePredicateRule;
import com.steelreed.queryfence.core.internal.UnboundedWriteRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The rules QueryFence checks, with their suppressions. Immutable; create one with {@link
 * #builder()}.
 *
 * <pre>{@code
 * Policy policy = Policy.builder()
 *     .requirePredicate("tenant-isolation", "tenant_id", "purchase_order", "order_item")
 *     .updateWithoutWhere("no-unbounded-update")
 *     .deleteWithoutWhere("no-unbounded-delete")
 *     .build();
 * }</pre>
 */
public final class Policy {

  private final List<Rule> rules;
  private final List<Suppression> suppressions;
  private final Mode mode;
  private final Mode onUnparseable;

  private Policy(Builder builder) {
    this.rules = List.copyOf(builder.rules);
    this.suppressions = List.copyOf(builder.suppressions);
    this.mode = builder.mode;
    this.onUnparseable = builder.onUnparseable;
  }

  /**
   * Starts building a policy.
   *
   * @return a builder with no rules, both modes set to {@link Mode#FAIL}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The rules of this policy, in declaration order.
   *
   * @return an unmodifiable list
   */
  public List<Rule> rules() {
    return rules;
  }

  /**
   * The suppressions of this policy, in declaration order.
   *
   * @return an unmodifiable list
   */
  public List<Suppression> suppressions() {
    return suppressions;
  }

  /**
   * Whether violations fail the test or are only reported.
   *
   * @return {@link Mode#FAIL} by default
   */
  public Mode mode() {
    return mode;
  }

  /**
   * How statements that cannot be parsed are treated.
   *
   * @return {@link Mode#FAIL} by default, which is what "fail closed" means here
   */
  public Mode onUnparseable() {
    return onUnparseable;
  }

  /**
   * The mode that governs one violation: {@link #onUnparseable()} for {@link
   * Violation.Code#UNPARSEABLE}, {@link #mode()} for every other code. Callers decide whether to
   * fail with this, never with {@link #mode()} alone, so that {@code onUnparseable: REPORT} really
   * does only report.
   *
   * @param code the code of the violation
   * @return the mode that governs violations with this code
   * @throws NullPointerException when {@code code} is {@code null}
   */
  public Mode modeFor(Violation.Code code) {
    Objects.requireNonNull(code, "code");
    return code == Violation.Code.UNPARSEABLE ? onUnparseable : mode;
  }

  /** Builds a {@link Policy}. Invalid input fails fast with {@link IllegalArgumentException}. */
  public static final class Builder {

    private final List<Rule> rules = new ArrayList<>();
    private final List<Suppression> suppressions = new ArrayList<>();
    private Mode mode = Mode.FAIL;
    private Mode onUnparseable = Mode.FAIL;

    private Builder() {}

    /**
     * Requires every occurrence of {@code tables} to be filtered by {@code column}.
     *
     * @param id rule id, unique within the policy, as it appears in reports
     * @param column the tenant column, for example {@code tenant_id}
     * @param tables the protected tables, optionally schema-qualified
     * @return this builder
     * @throws IllegalArgumentException if the id is already used, or an argument is blank or empty
     */
    public Builder requirePredicate(String id, String column, String... tables) {
      return requirePredicate(id, column, List.of(tables), List.of());
    }

    /**
     * Same, accepting comparisons with the named functions as tenant values, which is what an
     * RLS-style {@code tenant_id = current_setting('app.tenant')} needs.
     *
     * @param id rule id, unique within the policy
     * @param column the tenant column
     * @param tables the protected tables, non-empty
     * @param allowedFunctions functions accepted as a value, matched on their unqualified name
     * @return this builder
     * @throws IllegalArgumentException if the id is already used, or an argument is blank or empty
     */
    public Builder requirePredicate(
        String id, String column, Collection<String> tables, Collection<String> allowedFunctions) {
      return add(RequirePredicateRule.of(id, column, tables, allowedFunctions));
    }

    /**
     * Same, naming the column rows are looked up by when no tenant is involved ({@code id} by
     * default). A statement whose only filter is that column is reported as {@link
     * Violation.Code#PRIMARY_KEY_LOOKUP}, which carries a different fix.
     *
     * @param id rule id, unique within the policy
     * @param column the tenant column
     * @param tables the protected tables, non-empty
     * @param allowedFunctions functions accepted as a value, may be empty
     * @param primaryKey the column rows are looked up by, for example {@code id}
     * @return this builder
     * @throws IllegalArgumentException if the id is already used, or an argument is blank or empty
     */
    public Builder requirePredicate(
        String id,
        String column,
        Collection<String> tables,
        Collection<String> allowedFunctions,
        String primaryKey) {
      return add(RequirePredicateRule.of(id, column, tables, allowedFunctions, primaryKey));
    }

    /**
     * Requires every {@code UPDATE} to have a WHERE clause that is not always true.
     *
     * @param id rule id, unique within the policy
     * @return this builder
     * @throws IllegalArgumentException if the id is already used or blank
     */
    public Builder updateWithoutWhere(String id) {
      return add(new UnboundedWriteRule(id, UnboundedWriteRule.Kind.UPDATE));
    }

    /**
     * Requires every {@code DELETE} to have a WHERE clause that is not always true.
     *
     * @param id rule id, unique within the policy
     * @return this builder
     * @throws IllegalArgumentException if the id is already used or blank
     */
    public Builder deleteWithoutWhere(String id) {
      return add(new UnboundedWriteRule(id, UnboundedWriteRule.Kind.DELETE));
    }

    /**
     * Accepts the violations of one rule produced by one method, for a documented reason.
     *
     * @param ruleId the rule to suppress; it must exist when {@link #build()} runs
     * @param origin the producing method as {@code fully.qualified.Class#method}
     * @param reason why those statements are safe; must not be blank
     * @return this builder
     * @throws IllegalArgumentException if the origin is not {@code Class#method}, or the reason is
     *     blank
     */
    public Builder suppress(String ruleId, String origin, String reason) {
      suppressions.add(new Suppression(ruleId, origin, reason));
      return this;
    }

    /**
     * Sets what happens when a statement breaks a rule.
     *
     * @param mode {@link Mode#FAIL} to fail the test, {@link Mode#REPORT} to only collect
     * @return this builder
     */
    public Builder mode(Mode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      return this;
    }

    /**
     * Sets what happens to a statement QueryFence cannot parse.
     *
     * @param onUnparseable {@link Mode#FAIL} to treat it as a violation, {@link Mode#REPORT} to
     *     only collect it
     * @return this builder
     */
    public Builder onUnparseable(Mode onUnparseable) {
      this.onUnparseable = Objects.requireNonNull(onUnparseable, "onUnparseable");
      return this;
    }

    /**
     * Validates and builds the policy.
     *
     * @return the policy
     * @throws IllegalArgumentException if a suppression names a rule the policy does not declare
     */
    public Policy build() {
      Set<String> ids = new HashSet<>();
      for (Rule rule : rules) {
        ids.add(rule.id());
      }
      for (Suppression suppression : suppressions) {
        if (!ids.contains(suppression.ruleId())) {
          throw new IllegalArgumentException(
              "Suppression for "
                  + suppression.origin()
                  + " refers to unknown rule '"
                  + suppression.ruleId()
                  + "'");
        }
      }
      return new Policy(this);
    }

    private Builder add(Rule rule) {
      for (Rule existing : rules) {
        if (existing.id().equals(rule.id())) {
          throw new IllegalArgumentException("Duplicate rule id '" + rule.id() + "'");
        }
      }
      rules.add(rule);
      return this;
    }
  }
}
