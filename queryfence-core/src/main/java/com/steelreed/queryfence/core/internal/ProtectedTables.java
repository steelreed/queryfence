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

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Rule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which protected tables a statement mentions, read from the raw SQL.
 *
 * <p>This is the only place in the engine that looks at SQL as text instead of as a parsed
 * statement, and it exists for one case: a statement that does not parse. We cannot say what such a
 * statement does, but we can say which protected tables it names, so an unparseable statement does
 * not hide a table from the report.
 */
final class ProtectedTables {

  private ProtectedTables() {}

  /**
   * The protected tables named in this SQL, in the order the policy declares them.
   *
   * <p>Matching is on whole identifiers, case-insensitively: {@code purchase_order} matches {@code
   * PURCHASE_ORDER}, {@code "purchase_order"}, {@code `purchase_order`} and {@code
   * app.purchase_order}, but not {@code purchase_order_archive}. Comments and string literals are
   * ignored, so a table name inside a literal does not count.
   */
  static List<String> mentionedIn(String sql, Policy policy) {
    List<String> declared = declaredIn(policy);
    if (declared.isEmpty()) {
      return List.of();
    }
    Set<String> identifiers = new HashSet<>(SqlText.words(sql));
    List<String> mentioned = new ArrayList<>();
    for (String table : declared) {
      if (identifiers.contains(table) && !mentioned.contains(table)) {
        mentioned.add(table);
      }
    }
    return List.copyOf(mentioned);
  }

  private static List<String> declaredIn(Policy policy) {
    List<String> names = new ArrayList<>();
    for (Rule rule : policy.rules()) {
      if (rule instanceof RequirePredicateRule requirePredicate) {
        for (RequirePredicateRule.TableName table : requirePredicate.tables()) {
          names.add(table.name());
        }
      }
    }
    return names;
  }
}
