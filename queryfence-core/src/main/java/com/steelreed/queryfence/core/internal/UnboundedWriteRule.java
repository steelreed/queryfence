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

/** {@code update-without-where} and {@code delete-without-where} (DESIGN.md UW-*, DW-*). */
public record UnboundedWriteRule(String id, Kind kind) implements Rule {

  public enum Kind {
    UPDATE("update-without-where"),
    DELETE("delete-without-where");

    private final String type;

    Kind(String type) {
      this.type = type;
    }
  }

  public UnboundedWriteRule {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Rule id must not be blank");
    }
  }

  @Override
  public String type() {
    return kind.type;
  }
}
