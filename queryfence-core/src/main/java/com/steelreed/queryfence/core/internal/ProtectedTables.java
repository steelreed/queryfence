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
import java.util.Locale;
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
    Set<String> identifiers = identifiersOf(sql);
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

  private static Set<String> identifiersOf(String sql) {
    Set<String> identifiers = new HashSet<>();
    StringBuilder word = new StringBuilder();
    String text = withoutCommentsAndLiterals(sql);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
        word.append(c);
      } else {
        take(word, identifiers);
      }
    }
    take(word, identifiers);
    return identifiers;
  }

  private static void take(StringBuilder word, Set<String> identifiers) {
    if (word.length() > 0) {
      identifiers.add(word.toString().toLowerCase(Locale.ROOT));
      word.setLength(0);
    }
  }

  private static String withoutCommentsAndLiterals(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? sql.length() : end;
      } else if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i);
        i = end < 0 ? sql.length() : end + 2;
        out.append(' ');
      } else if (sql.charAt(i) == '\'') {
        i = endOfLiteral(sql, i);
        out.append(' ');
      } else {
        out.append(sql.charAt(i));
        i++;
      }
    }
    return out.toString();
  }

  private static int endOfLiteral(String sql, int start) {
    int i = start + 1;
    while (i < sql.length()) {
      if (sql.charAt(i) == '\'') {
        // '' inside a literal is an escaped quote, not its end.
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return sql.length();
  }
}
