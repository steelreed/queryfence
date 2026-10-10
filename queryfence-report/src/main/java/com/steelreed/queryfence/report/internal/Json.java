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
package com.steelreed.queryfence.report.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A tiny JSON writer, so the report needs no JSON library. */
final class Json {

  /** One open container: what closes it, and whether it already holds an entry. */
  private static final class Level {
    private final char close;
    private boolean hasEntries;

    private Level(char close) {
      this.close = close;
    }
  }

  private final StringBuilder out = new StringBuilder();
  private final Deque<Level> open = new ArrayDeque<>();

  Json object() {
    beforeValue();
    out.append('{');
    open.push(new Level('}'));
    return this;
  }

  Json array() {
    beforeValue();
    out.append('[');
    open.push(new Level(']'));
    return this;
  }

  Json end() {
    out.append(open.pop().close);
    return this;
  }

  Json key(String name) {
    beforeValue();
    out.append(quote(name)).append(':');
    return this;
  }

  /** Writes a bare value, for arrays. */
  Json value(String value) {
    beforeValue();
    out.append(value == null ? "null" : quote(value));
    return this;
  }

  Json field(String name, String value) {
    key(name);
    out.append(value == null ? "null" : quote(value));
    return this;
  }

  Json field(String name, int value) {
    key(name);
    out.append(value);
    return this;
  }

  Json field(String name, boolean value) {
    key(name);
    out.append(value);
    return this;
  }

  /**
   * Writes a value read by {@link JsonReader}: a map, a list, a string, a number, a boolean or
   * null.
   */
  Json any(Object value) {
    if (value instanceof Map<?, ?> map) {
      object();
      map.forEach((name, item) -> key(name.toString()).any(item));
      return end();
    }
    if (value instanceof List<?> list) {
      array();
      list.forEach(this::any);
      return end();
    }
    beforeValue();
    out.append(value instanceof String text ? quote(text) : String.valueOf(value));
    return this;
  }

  /** Writes the separator this position needs, if any. */
  private void beforeValue() {
    Level level = open.peek();
    if (level == null) {
      return;
    }
    boolean afterKey = out.length() > 0 && out.charAt(out.length() - 1) == ':';
    if (afterKey) {
      return;
    }
    if (level.hasEntries) {
      out.append(',');
    }
    level.hasEntries = true;
  }

  private static String quote(String value) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  @Override
  public String toString() {
    return out.toString();
  }
}
