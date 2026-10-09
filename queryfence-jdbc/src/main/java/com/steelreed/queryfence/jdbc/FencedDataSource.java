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

import javax.sql.DataSource;

/**
 * A {@code DataSource} that records the statements executed through it. The SQL itself is passed to
 * the driver unchanged: QueryFence observes, it never rewrites or blocks.
 */
public interface FencedDataSource extends DataSource {

  /**
   * The recorder holding the statements executed through this data source.
   *
   * @return the recorder, shared by every connection this data source hands out
   */
  QueryRecorder recorder();

  /**
   * The data source this one wraps.
   *
   * @return the original, unwrapped data source
   */
  DataSource delegate();
}
