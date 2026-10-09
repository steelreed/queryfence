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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader for the reports other test JVMs wrote, so merging them needs no JSON library.
 * Objects become {@code LinkedHashMap}s (key order kept), arrays {@code List}s, integers {@code
 * Long}s and other numbers {@code Double}s.
 */
final class JsonReader {

  private final String in;
  private int pos;

  private JsonReader(String in) {
    this.in = in;
  }

  /**
   * Parses one JSON document.
   *
   * @throws IllegalArgumentException when the text is not valid JSON
   */
  static Object parse(String text) {
    JsonReader reader = new JsonReader(text);
    Object value = reader.value();
    reader.skipWhitespace();
    if (reader.pos != text.length()) {
      throw reader.error("trailing characters");
    }
    return value;
  }

  private Object value() {
    skipWhitespace();
    if (pos >= in.length()) {
      throw error("unexpected end");
    }
    char c = in.charAt(pos);
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++; // {
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw error("expected a key");
      }
      String key = string();
      skipWhitespace();
      expect(':');
      map.put(key, value());
      skipWhitespace();
      char next = next();
      if (next == '}') {
        return map;
      }
      if (next != ',') {
        throw error("expected , or }");
      }
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    pos++; // [
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      list.add(value());
      skipWhitespace();
      char next = next();
      if (next == ']') {
        return list;
      }
      if (next != ',') {
        throw error("expected , or ]");
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char escaped = next();
      switch (escaped) {
        case '"', '\\', '/' -> sb.append(escaped);
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (pos + 4 > in.length()) {
            throw error("bad unicode escape");
          }
          try {
            sb.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
          } catch (NumberFormatException e) {
            throw error("bad unicode escape");
          }
          pos += 4;
        }
        default -> throw error("bad escape");
      }
    }
  }

  private Object literal(String word, Object value) {
    if (!in.startsWith(word, pos)) {
      throw error("unexpected token");
    }
    pos += word.length();
    return value;
  }

  private Object number() {
    int start = pos;
    while (pos < in.length() && "+-0123456789.eE".indexOf(in.charAt(pos)) >= 0) {
      pos++;
    }
    String text = in.substring(start, pos);
    if (text.isEmpty()) {
      throw error("unexpected character");
    }
    try {
      if (text.contains(".") || text.contains("e") || text.contains("E")) {
        return Double.parseDouble(text);
      }
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw error("bad number " + text);
    }
  }

  private void skipWhitespace() {
    while (pos < in.length() && Character.isWhitespace(in.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= in.length()) {
      throw error("unexpected end");
    }
    return in.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("expected " + c);
    }
  }

  private IllegalArgumentException error(String problem) {
    return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + problem);
  }
}
