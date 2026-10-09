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
 * One SQL statement as it was sent to the database.
 *
 * @param sql the statement text, exactly as the driver received it
 * @param origin the application code that produced it
 * @param batch whether it was executed as part of a JDBC batch
 * @param batchSize the number of batched executions, or {@code 1} outside a batch
 * @param failed whether the execution threw
 */
public record CapturedStatement(
    String sql, Origin origin, boolean batch, int batchSize, boolean failed) {

  @Override
  public String toString() {
    return sql + "  [" + origin + (batch ? ", batch of " + batchSize : "") + "]";
  }
}
