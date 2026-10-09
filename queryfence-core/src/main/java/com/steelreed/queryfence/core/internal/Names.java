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

import java.util.Locale;

/** Identifier normalization: quotes removed, lower case (DESIGN.md RP-12). */
final class Names {

  private Names() {}

  static String normalize(String identifier) {
    if (identifier == null) {
      return null;
    }
    String s = identifier.trim();
    if (s.length() >= 2) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '"' && last == '"')
          || (first == '`' && last == '`')
          || (first == '[' && last == ']')) {
        s = s.substring(1, s.length() - 1);
      }
    }
    return s.toLowerCase(Locale.ROOT);
  }

  /** Removes quotes but keeps the case, for showing identifiers as the user wrote them. */
  static String unquote(String identifier) {
    if (identifier == null) {
      return null;
    }
    String s = identifier.trim();
    if (s.length() >= 2) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '"' && last == '"')
          || (first == '`' && last == '`')
          || (first == '[' && last == ']')) {
        return s.substring(1, s.length() - 1);
      }
    }
    return s;
  }
}
