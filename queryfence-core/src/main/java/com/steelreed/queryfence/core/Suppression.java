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
package com.steelreed.queryfence.core;

import java.util.Objects;

/**
 * Accepts violations of one rule produced by one method, for a documented reason.
 *
 * @param ruleId the id of the suppressed rule
 * @param origin the producing method as {@code fully.qualified.Class#method}
 * @param reason why the statements of that method are safe; must not be blank
 */
public record Suppression(String ruleId, String origin, String reason) {

  /**
   * Validates the suppression.
   *
   * @throws IllegalArgumentException if the origin is not {@code Class#method}, or a field is blank
   * @throws NullPointerException if the rule id or the origin is {@code null}
   */
  public Suppression {
    requireNonBlank(ruleId, "rule");
    requireNonBlank(origin, "origin");
    if (origin.indexOf('#') <= 0 || origin.endsWith("#")) {
      throw new IllegalArgumentException(
          "Suppression origin must be Class#method, got '" + origin + "'");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(
          "Suppression of rule '"
              + ruleId
              + "' for "
              + origin
              + " has no reason. Every suppression must explain why it is safe.");
    }
  }

  private static void requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, "Suppression " + name + " is required");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Suppression " + name + " must not be blank");
    }
  }
}
