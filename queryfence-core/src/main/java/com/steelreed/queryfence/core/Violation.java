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

/**
 * One statement that breaks one rule.
 *
 * @param ruleId the id of the broken rule, or {@link #PARSER_RULE} for {@link Code#UNPARSEABLE}
 * @param ruleType the type of the broken rule, or {@code null} for {@link Code#UNPARSEABLE}
 * @param code what kind of problem was found
 * @param table the normalized table name (lower case, unquoted, without schema), or {@code null}
 * @param alias the table alias as written in the SQL, or {@code null} when there is none
 * @param message what is wrong and how to fix it
 * @param sql the statement as it was checked
 */
public record Violation(
    String ruleId,
    String ruleType,
    Code code,
    String table,
    String alias,
    String message,
    String sql) {

  /**
   * The rule id of a violation the parser raised rather than a rule: {@link Code#UNPARSEABLE}.
   * Suppress one with {@code rule: parser} in the policy, like any other rule.
   */
  public static final String PARSER_RULE = "parser";

  /** What kind of problem a violation is; see {@code docs/DESIGN.md} "Violation model". */
  public enum Code {

    /** A protected table is read or written without a tenant filter. */
    MISSING_PREDICATE,

    /**
     * The only filter is the primary key, as {@code findById} and {@code EntityManager.find}
     * produce. Ids are guessable, so this is not tenant isolation.
     */
    PRIMARY_KEY_LOOKUP,

    /**
     * The tenant column is used unqualified in a query block that reads several tables, so it
     * cannot be bound to any of them.
     */
    AMBIGUOUS_COLUMN,

    /** An {@code INSERT} into a protected table does not set the tenant column. */
    MISSING_INSERT_COLUMN,

    /** An {@code UPDATE} or {@code DELETE} has no WHERE clause and touches every row. */
    NO_WHERE,

    /** An {@code UPDATE} or {@code DELETE} has a WHERE clause that is always true. */
    TAUTOLOGICAL_WHERE,

    /** A statement type QueryFence does not analyse yet, such as {@code MERGE}. */
    UNSUPPORTED_STATEMENT,

    /** SQL the parser does not understand, and that could read or write rows. */
    UNPARSEABLE
  }
}
