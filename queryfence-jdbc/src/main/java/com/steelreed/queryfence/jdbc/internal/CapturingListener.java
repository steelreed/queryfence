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
package com.steelreed.queryfence.jdbc.internal;

import com.steelreed.queryfence.jdbc.CapturedStatement;
import java.util.List;
import java.util.function.Consumer;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * Records every statement the driver executes, including the statements of a JDBC batch. Recording
 * happens after execution, so a statement that failed is recorded too: it reached the database.
 *
 * <p>A statement is recorded once, by the outermost fenced data source it passes through. An
 * application can layer data sources (a {@code LazyConnectionDataSourceProxy} or a routing data
 * source over a pool), and when every layer is fenced, one execution goes through several capturing
 * proxies on the same thread, nested inside each other. Each layer counts the executions in flight
 * on its thread, and only the outermost one records, so findings and statement counts are not
 * multiplied by the number of layers.
 */
public final class CapturingListener implements QueryExecutionListener {

  /** Executions in flight on this thread, across every fenced data source. */
  private static final ThreadLocal<int[]> IN_FLIGHT = ThreadLocal.withInitial(() -> new int[1]);

  private final Consumer<CapturedStatement> sink;
  private final OriginResolver originResolver;

  public CapturingListener(Consumer<CapturedStatement> sink, OriginResolver originResolver) {
    this.sink = sink;
    this.originResolver = originResolver;
  }

  @Override
  public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    // The origin is resolved after execution, from the same stack; here we only count the nesting.
    IN_FLIGHT.get()[0]++;
  }

  @Override
  public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    int[] inFlight = IN_FLIGHT.get();
    inFlight[0]--;
    if (inFlight[0] > 0) {
      return; // an outer fenced data source is executing this statement and records it
    }
    IN_FLIGHT.remove();
    if (queries == null || queries.isEmpty()) {
      return;
    }
    var origin = originResolver.resolve();
    boolean batch = execution.isBatch();
    for (QueryInfo query : queries) {
      String sql = query.getQuery();
      if (sql == null || sql.isBlank()) {
        continue;
      }
      // A batched PreparedStatement reports one query with one parameter set per execution.
      int batchSize = batch ? Math.max(batchSizeOf(query, execution), 1) : 1;
      sink.accept(new CapturedStatement(sql, origin, batch, batchSize, !execution.isSuccess()));
    }
  }

  private static int batchSizeOf(QueryInfo query, ExecutionInfo execution) {
    if (query.getParametersList() != null && !query.getParametersList().isEmpty()) {
      return query.getParametersList().size();
    }
    return execution.getBatchSize();
  }
}
