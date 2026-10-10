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
import java.util.Locale;

/**
 * SQL read as text, for statements the parser rejects.
 *
 * <p>The engine reasons about parsed statements. When JSqlParser rejects a string, the only thing
 * left is the text: which statements it holds, which keyword each starts with, which words it uses.
 * Everything here errs on the side of reporting: a split in the wrong place yields fragments that
 * do not parse and are reported, never a statement that disappears.
 */
final class SqlText {

  private SqlText() {}

  /**
   * The statements of a string, split on the semicolons that separate them. A semicolon inside a
   * comment, a string literal, a quoted identifier or a Postgres dollar-quoted body does not split.
   * Fragments that hold nothing but whitespace and comments are dropped; the others are trimmed.
   */
  static List<String> statements(String sql) {
    List<String> out = new ArrayList<>();
    int start = 0;
    boolean significant = false;
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      int next;
      if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? sql.length() : end + 1;
        continue;
      } else if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i + 2);
        i = end < 0 ? sql.length() : end + 2;
        continue;
      } else if (c == '\'' || c == '"' || c == '`') {
        next = endOfQuoted(sql, i, c);
      } else if (c == '$') {
        next = Math.max(endOfDollarQuoted(sql, i), i + 1);
      } else if (c == ';') {
        add(out, sql.substring(start, i), significant);
        start = i + 1;
        significant = false;
        i++;
        continue;
      } else {
        next = i + 1;
      }
      if (!Character.isWhitespace(c)) {
        significant = true;
      }
      i = next;
    }
    add(out, sql.substring(start), significant);
    return List.copyOf(out);
  }

  private static void add(List<String> out, String fragment, boolean significant) {
    if (significant) {
      out.add(fragment.trim());
    }
  }

  /** The first keyword of a statement, lower case, skipping leading comments and whitespace. */
  static String leadingKeyword(String sql) {
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? sql.length() : end + 1;
      } else if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i);
        i = end < 0 ? sql.length() : end + 2;
      } else {
        break;
      }
    }
    int start = i;
    while (i < sql.length() && Character.isLetter(sql.charAt(i))) {
      i++;
    }
    return sql.substring(start, i).toLowerCase(Locale.ROOT);
  }

  /**
   * The words of a statement in order, lower case: identifiers and keywords outside comments and
   * string literals. Quoted identifiers count with their content, so {@code "purchase_order"} is
   * the word {@code purchase_order}.
   */
  static List<String> words(String sql) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    String text = withoutCommentsAndLiterals(sql);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
        word.append(c);
      } else {
        take(word, words);
      }
    }
    take(word, words);
    return words;
  }

  private static void take(StringBuilder word, List<String> words) {
    if (word.length() > 0) {
      words.add(word.toString().toLowerCase(Locale.ROOT));
      word.setLength(0);
    }
  }

  /** The SQL with comments and single-quoted string literals replaced by a space. */
  static String withoutCommentsAndLiterals(String sql) {
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
        i = endOfQuoted(sql, i, '\'');
        out.append(' ');
      } else {
        out.append(sql.charAt(i));
        i++;
      }
    }
    return out.toString();
  }

  /**
   * The index just past a quoted token starting at {@code start}. A doubled quote inside it is an
   * escaped quote, not its end. Backslash escapes are not honoured on purpose: reading {@code \'}
   * as the end of a literal can only cut a statement into more pieces, never hide one.
   */
  private static int endOfQuoted(String sql, int start, char quote) {
    int i = start + 1;
    while (i < sql.length()) {
      if (sql.charAt(i) == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return sql.length();
  }

  /**
   * The index just past a Postgres dollar-quoted body ({@code $$ ... $$}, {@code $tag$ ... $tag$})
   * starting at {@code start}, or {@code start} when no dollar quote starts there ({@code $1} is a
   * parameter).
   */
  private static int endOfDollarQuoted(String sql, int start) {
    int i = start + 1;
    if (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
      while (i < sql.length()
          && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
        i++;
      }
    }
    if (i >= sql.length() || sql.charAt(i) != '$') {
      return start;
    }
    String tag = sql.substring(start, i + 1);
    int end = sql.indexOf(tag, i + 1);
    return end < 0 ? sql.length() : end + tag.length();
  }
}
