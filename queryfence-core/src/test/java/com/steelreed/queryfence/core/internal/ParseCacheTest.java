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

/** The parse cache is bounded and remembers failures as well as successes. */
class ParseCacheTest {

  private static final String SELECT = "SELECT id FROM purchase_order WHERE tenant_id = ?";

  @Test
  void returnsTheSameParsedStatementsForTheSameSql() {
    ParseCache cache = new ParseCache(10);

    ParseCache.Parsed first = cache.get(SELECT);
    ParseCache.Parsed second = cache.get(SELECT);

    assertThat(first.failed()).isFalse();
    assertThat(first.statements()).hasSize(1);
    assertThat(second).isSameAs(first);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  void splitsAndCachesMultiStatementStrings() {
    ParseCache cache = new ParseCache(10);

    ParseCache.Parsed parsed = cache.get(SELECT + "; DELETE FROM invoice WHERE tenant_id = ?");

    assertThat(parsed.failed()).isFalse();
    assertThat(parsed.statements()).hasSize(2);
  }

  @Test
  void cachesParseFailures() {
    ParseCache cache = new ParseCache(10);

    ParseCache.Parsed parsed = cache.get("SELEKT * FROM purchase_order");

    assertThat(parsed.failed()).isTrue();
    assertThat(parsed.statements()).isNull();
    assertThat(cache.get("SELEKT * FROM purchase_order")).isSameAs(parsed);
  }

  @Test
  void evictsTheLeastRecentlyUsedEntryWhenFull() {
    ParseCache cache = new ParseCache(2);

    ParseCache.Parsed first = cache.get("SELECT 1 FROM purchase_order");
    cache.get("SELECT 2 FROM purchase_order");
    cache.get("SELECT 1 FROM purchase_order"); // touch the first entry
    cache.get("SELECT 3 FROM purchase_order"); // evicts "SELECT 2 ..."

    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.get("SELECT 1 FROM purchase_order")).isSameAs(first);
  }
}
