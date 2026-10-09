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
 */
public final class CapturingListener implements QueryExecutionListener {

  private final Consumer<CapturedStatement> sink;
  private final OriginResolver originResolver;

  public CapturingListener(Consumer<CapturedStatement> sink, OriginResolver originResolver) {
    this.sink = sink;
    this.originResolver = originResolver;
  }

  @Override
  public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    // Nothing: the origin is resolved after execution, from the same stack.
  }

  @Override
  public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
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
