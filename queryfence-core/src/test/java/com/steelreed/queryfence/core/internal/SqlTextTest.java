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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Splitting and scanning SQL text for statements the parser rejects. */
class SqlTextTest {

  @Test
  void splitsOnSemicolonsAndTrimsEachStatement() {
    assertThat(SqlText.statements(" SET a TO b ;SELECT 1;  "))
        .containsExactly("SET a TO b", "SELECT 1");
    assertThat(SqlText.statements("a;b")).containsExactly("a", "b");
    assertThat(SqlText.statements(";")).isEmpty();
    assertThat(SqlText.statements("")).isEmpty();
  }

  @Test
  void dropsStatementsMadeOfCommentsOnly() {
    assertThat(SqlText.statements("-- a;b\nx; /* c; d */ ; y")).containsExactly("-- a;b\nx", "y");
    assertThat(SqlText.statements("x; -- trailing")).containsExactly("x");
    assertThat(SqlText.statements("x; /* unterminated ; y")).containsExactly("x");
  }

  @Test
  void doesNotSplitInsideQuotes() {
    assertThat(SqlText.statements("a 'x;y' b; c")).containsExactly("a 'x;y' b", "c");
    assertThat(SqlText.statements("a 'it''s;' b; c")).containsExactly("a 'it''s;' b", "c");
    assertThat(SqlText.statements("a \"x;y\" b; c")).containsExactly("a \"x;y\" b", "c");
    assertThat(SqlText.statements("a `x;y` b; c")).containsExactly("a `x;y` b", "c");
    assertThat(SqlText.statements("a 'unterminated; c")).containsExactly("a 'unterminated; c");
    assertThat(SqlText.statements("'a';b")).containsExactly("'a'", "b");
  }

  @Test
  void doesNotSplitInsideDollarQuotedBodies() {
    assertThat(SqlText.statements("DO $$ a; b $$; c")).containsExactly("DO $$ a; b $$", "c");
    assertThat(SqlText.statements("x $f$ a; $$; b $f$; c"))
        .containsExactly("x $f$ a; $$; b $f$", "c");
    assertThat(SqlText.statements("x $_1$ a; $_1$;c")).containsExactly("x $_1$ a; $_1$", "c");
    assertThat(SqlText.statements("x $$ a; b")).containsExactly("x $$ a; b");
    assertThat(SqlText.statements("$$a;b$$;c")).containsExactly("$$a;b$$", "c");
  }

  @Test
  void readsPositionalParametersAndLoneDollarsAsPlainText() {
    assertThat(SqlText.statements("x = $1; y = $2")).containsExactly("x = $1", "y = $2");
    assertThat(SqlText.statements("x $a; y")).containsExactly("x $a", "y");
    assertThat(SqlText.statements("x $")).containsExactly("x $");
  }

  @Test
  void findsTheLeadingKeyword() {
    assertThat(SqlText.leadingKeyword("  Set x")).isEqualTo("set");
    assertThat(SqlText.leadingKeyword("-- c\n/* d */SELECT")).isEqualTo("select");
    assertThat(SqlText.leadingKeyword("-- only")).isEmpty();
    assertThat(SqlText.leadingKeyword("/* open")).isEmpty();
    assertThat(SqlText.leadingKeyword("a")).isEqualTo("a");
    assertThat(SqlText.leadingKeyword("(SELECT")).isEmpty();
  }

  @Test
  void listsWordsOutsideCommentsAndLiterals() {
    assertThat(SqlText.words("SET x TO 'SELECT y' -- DELETE\n/* UPDATE */ \"Q\".r$1"))
        .containsExactly("set", "x", "to", "q", "r$1");
    assertThat(SqlText.words("a 'it''s' b")).containsExactly("a", "b");
    assertThat(SqlText.words("a")).containsExactly("a");
    assertThat(SqlText.words("")).isEmpty();
  }
}
