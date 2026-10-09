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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

/** Parse results cached by SQL string (architecture rule 5). Thread-safe, bounded LRU. */
final class ParseCache {

  /** Parsed statements, or {@code statements == null} when the SQL could not be parsed. */
  record Parsed(List<Statement> statements) {
    boolean failed() {
      return statements == null;
    }
  }

  private final Map<String, Parsed> cache;

  ParseCache(int maxEntries) {
    this.cache =
        new LinkedHashMap<>(64, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > maxEntries;
          }
        };
  }

  Parsed get(String sql) {
    synchronized (cache) {
      Parsed cached = cache.get(sql);
      if (cached != null) {
        return cached;
      }
    }
    Parsed parsed = parse(sql);
    synchronized (cache) {
      cache.put(sql, parsed);
    }
    return parsed;
  }

  /** Entries currently cached; for tests. */
  int size() {
    synchronized (cache) {
      return cache.size();
    }
  }

  private static Parsed parse(String sql) {
    try {
      return new Parsed(List.copyOf(CCJSqlParserUtil.parseStatements(sql)));
    } catch (Exception | StackOverflowError e) {
      return new Parsed(null);
    }
  }
}
