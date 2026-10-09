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

import com.steelreed.queryfence.core.Rule;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** {@code require-predicate}: every occurrence of a protected table must be fenced. */
public record RequirePredicateRule(
    String id,
    String column,
    List<TableName> tables,
    Set<String> allowedFunctions,
    String primaryKey)
    implements Rule {

  public static final String TYPE = "require-predicate";

  /** Column name a row is usually looked up by when no tenant is involved. */
  public static final String DEFAULT_PRIMARY_KEY = "id";

  /** A {@code tables} entry; {@code schema} is {@code null} when the entry has none. */
  public record TableName(String schema, String name) {}

  public static RequirePredicateRule of(
      String id, String column, Collection<String> tables, Collection<String> allowedFunctions) {
    return of(id, column, tables, allowedFunctions, DEFAULT_PRIMARY_KEY);
  }

  public static RequirePredicateRule of(
      String id,
      String column,
      Collection<String> tables,
      Collection<String> allowedFunctions,
      String primaryKey) {
    requireNonBlank(id, "Rule id");
    requireNonBlank(column, "Rule '" + id + "' column");
    Objects.requireNonNull(tables, "tables");
    if (tables.isEmpty()) {
      throw new IllegalArgumentException("Rule '" + id + "' must list at least one table");
    }
    List<TableName> names =
        tables.stream()
            .map(
                t -> {
                  requireNonBlank(t, "Rule '" + id + "' table");
                  int dot = t.lastIndexOf('.');
                  return dot < 0
                      ? new TableName(null, Names.normalize(t))
                      : new TableName(
                          Names.normalize(t.substring(0, dot)),
                          Names.normalize(t.substring(dot + 1)));
                })
            .toList();
    Set<String> functions =
        allowedFunctions == null
            ? Set.of()
            : allowedFunctions.stream()
                .map(Names::normalize)
                .collect(Collectors.toUnmodifiableSet());
    requireNonBlank(primaryKey, "Rule '" + id + "' primaryKey");
    return new RequirePredicateRule(
        id, Names.normalize(column), names, functions, Names.normalize(primaryKey));
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** RP-12: an entry without schema matches any schema; an unqualified reference matches any. */
  boolean protects(String schema, String name) {
    for (TableName table : tables) {
      if (table.name().equals(name)
          && (table.schema() == null || schema == null || table.schema().equals(schema))) {
        return true;
      }
    }
    return false;
  }

  private static void requireNonBlank(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
  }
}
