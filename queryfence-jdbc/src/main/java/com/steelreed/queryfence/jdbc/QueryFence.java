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

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.jdbc.internal.CapturingListener;
import com.steelreed.queryfence.jdbc.internal.DelegatingFencedDataSource;
import com.steelreed.queryfence.jdbc.internal.OriginResolver;
import java.util.Objects;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

/**
 * Wraps a {@code DataSource} so that every statement executed through it is recorded and checked
 * against a {@link Policy}.
 *
 * <pre>{@code
 * FencedDataSource fenced = QueryFence.wrap(dataSource, policy);
 * // ... run the code under test against fenced ...
 * List<Finding> findings = fenced.recorder().findings();
 * }</pre>
 *
 * <p>The SQL reaches the driver unchanged; QueryFence never rewrites or blocks a statement.
 *
 * <p>Fenced data sources can be layered: when a fenced data source wraps another one (directly, or
 * through a proxy or routing data source), a statement is recorded once, by the outermost fenced
 * data source it passes through on its thread. The inner one records the statements that reach it
 * directly.
 */
public final class QueryFence {

  private QueryFence() {}

  /**
   * Wraps a data source, resolving origins by skipping infrastructure frames.
   *
   * @param dataSource the data source the code under test uses
   * @param policy the rules to check the executed statements against
   * @return a data source that records and checks, and passes the SQL on unchanged
   */
  public static FencedDataSource wrap(DataSource dataSource, Policy policy) {
    return wrap(dataSource, policy, CaptureSettings.defaults());
  }

  /**
   * Wraps a data source with explicit origin resolution settings.
   *
   * @param dataSource the data source the code under test uses
   * @param policy the rules to check the executed statements against
   * @param settings how the origin of a statement is resolved
   * @return a data source that records and checks, and passes the SQL on unchanged
   */
  public static FencedDataSource wrap(
      DataSource dataSource, Policy policy, CaptureSettings settings) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(settings, "settings");

    QueryRecorder recorder = new QueryRecorder(policy);
    DataSource proxy =
        ProxyDataSourceBuilder.create(dataSource)
            .name("queryfence")
            .listener(new CapturingListener(recorder::record, new OriginResolver(settings)))
            .build();
    return new DelegatingFencedDataSource(proxy, dataSource, recorder);
  }
}
