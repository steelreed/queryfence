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
package com.steelreed.queryfence.jdbc;

import java.util.List;
import java.util.Objects;

/**
 * How the origin of a statement is resolved.
 *
 * <p>By default the first stack frame outside the JDK, the ORM, the framework and QueryFence itself
 * is used. Naming your own packages makes the result exact and cheaper:
 *
 * <pre>{@code
 * CaptureSettings.ofBasePackages("com.acme.orders", "com.acme.billing")
 * }</pre>
 */
public final class CaptureSettings {

  private static final CaptureSettings DEFAULTS = new CaptureSettings(List.of());

  private final List<String> basePackages;

  private CaptureSettings(List<String> basePackages) {
    this.basePackages = basePackages;
  }

  /**
   * Resolves the origin by skipping infrastructure frames.
   *
   * @return the default settings
   */
  public static CaptureSettings defaults() {
    return DEFAULTS;
  }

  /**
   * Resolves the origin as the first stack frame in one of these packages.
   *
   * @param basePackages your own package prefixes, for example {@code com.acme}
   * @return settings that report {@link Origin#unknown()} when no frame matches
   * @throws IllegalArgumentException if a package is blank
   */
  public static CaptureSettings ofBasePackages(String... basePackages) {
    Objects.requireNonNull(basePackages, "basePackages");
    for (String basePackage : basePackages) {
      if (basePackage == null || basePackage.isBlank()) {
        throw new IllegalArgumentException("Base packages must not be blank");
      }
    }
    return new CaptureSettings(List.of(basePackages));
  }

  /**
   * The packages the origin must come from.
   *
   * @return an unmodifiable list, empty when infrastructure frames are skipped instead
   */
  public List<String> basePackages() {
    return basePackages;
  }
}
