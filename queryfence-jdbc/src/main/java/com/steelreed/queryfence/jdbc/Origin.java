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

/**
 * The application code that produced a statement.
 *
 * @param className fully qualified class name, or {@code null} when it could not be resolved
 * @param methodName method name, or {@code null}
 * @param fileName source file name, or {@code null}
 * @param lineNumber line number, or {@code -1} when unknown
 */
public record Origin(String className, String methodName, String fileName, int lineNumber) {

  private static final Origin UNKNOWN = new Origin(null, null, null, -1);

  /**
   * The origin used when no application frame could be found.
   *
   * @return an origin whose fields are all unset
   */
  public static Origin unknown() {
    return UNKNOWN;
  }

  /**
   * Whether an application frame was found.
   *
   * @return {@code true} when the class name is known
   */
  public boolean isKnown() {
    return className != null;
  }

  /**
   * The key suppressions are written with.
   *
   * @return {@code com.acme.OrderRepository#findByStatus}, or {@code unknown}
   */
  public String classAndMethod() {
    return isKnown() ? className + "#" + methodName : "unknown";
  }

  /** {@code com.acme.OrderRepository#findByStatus (OrderRepository.java:42)}. */
  @Override
  public String toString() {
    if (!isKnown()) {
      return "unknown origin";
    }
    if (fileName == null || lineNumber < 0) {
      return classAndMethod();
    }
    return classAndMethod() + " (" + fileName + ":" + lineNumber + ")";
  }
}
