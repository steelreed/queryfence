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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orders.OrderRepository;
import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/** Captures real statements from JdbcTemplate against an in-memory H2 database. */
class CaptureTest {

  private static final Policy POLICY =
      Policy.builder()
          .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
          .updateWithoutWhere("no-unbounded-update")
          .build();

  private FencedDataSource fenced;
  private OrderRepository repository;

  @BeforeEach
  void setUp() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    new JdbcTemplate(h2)
        .execute(
            "CREATE TABLE purchase_order (id BIGINT PRIMARY KEY, tenant_id BIGINT,"
                + " status VARCHAR(32), total DECIMAL(10, 2))");

    fenced = QueryFence.wrap(h2, POLICY);
    repository = new OrderRepository(new JdbcTemplate(fenced));
  }

  @Test
  void recordsTheStatementAndTheLineThatProducedIt() {
    repository.findByTenantAndStatus(1L, "OPEN");
    int expectedLine = OrderRepository.lastLine;

    assertThat(fenced.recorder().statements())
        .singleElement()
        .satisfies(
            statement -> {
              assertThat(statement.sql())
                  .isEqualTo(
                      "SELECT id, total FROM purchase_order WHERE tenant_id = ? AND status = ?");
              assertThat(statement.batch()).isFalse();
              assertThat(statement.failed()).isFalse();
              assertThat(statement.origin().className()).isEqualTo(OrderRepository.class.getName());
              assertThat(statement.origin().methodName()).isEqualTo("findByTenantAndStatus");
              assertThat(statement.origin().fileName()).isEqualTo("OrderRepository.java");
              assertThat(statement.origin().lineNumber()).isEqualTo(expectedLine);
            });
  }

  @Test
  void reportsAViolationAtTheExactLineOfTheLeakingQuery() {
    repository.findByTenantAndStatus(1L, "OPEN");
    repository.findByStatus(1L, "OPEN");
    int leakLine = OrderRepository.lastLine;

    assertThat(fenced.recorder().findings())
        .singleElement()
        .satisfies(
            found -> {
              assertThat(found.violation().code()).isEqualTo(Violation.Code.MISSING_PREDICATE);
              assertThat(found.violation().table()).isEqualTo("purchase_order");
              assertThat(found.origin().methodName()).isEqualTo("findByStatus");
              assertThat(found.origin().lineNumber()).isEqualTo(leakLine);
              assertThat(found.toString())
                  .contains("com.acme.orders.OrderRepository#findByStatus")
                  .contains("OrderRepository.java:" + leakLine);
            });
  }

  /** A lambda is reported as the method that contains it, not as its synthetic name. */
  @Test
  void findsTheApplicationFrameThroughFrameworkCallbacks() {
    repository.findByStatusInCallback("OPEN");
    int expectedLine = OrderRepository.lastLine;

    assertThat(fenced.recorder().statements())
        .singleElement()
        .satisfies(
            statement -> {
              assertThat(statement.origin().className()).isEqualTo(OrderRepository.class.getName());
              assertThat(statement.origin().methodName()).isEqualTo("findByStatusInCallback");
              assertThat(statement.origin().toString())
                  .isEqualTo(
                      "com.acme.orders.OrderRepository#findByStatusInCallback"
                          + " (OrderRepository.java:"
                          + statement.origin().lineNumber()
                          + ")");
              assertThat(statement.origin().lineNumber()).isGreaterThan(expectedLine);
            });
  }

  @Test
  void recordsEveryStatementOfABatch() {
    repository.insertAll(
        List.of(
            new Object[] {1L, 7L, "OPEN", 10},
            new Object[] {2L, 7L, "OPEN", 20},
            new Object[] {3L, 7L, "OPEN", 30}));
    int expectedLine = OrderRepository.lastLine;

    assertThat(fenced.recorder().statements())
        .singleElement()
        .satisfies(
            statement -> {
              assertThat(statement.sql()).startsWith("INSERT INTO purchase_order");
              assertThat(statement.batch()).isTrue();
              assertThat(statement.batchSize()).isEqualTo(3);
              assertThat(statement.origin().methodName()).isEqualTo("insertAll");
              assertThat(statement.origin().lineNumber()).isEqualTo(expectedLine);
            });
    assertThat(fenced.recorder().findings()).isEmpty();
  }

  @Test
  void reportsEveryRuleBrokenByOneStatement() {
    repository.closeAll();

    assertThat(fenced.recorder().findings())
        .extracting(found -> found.violation().code())
        .containsExactlyInAnyOrder(Violation.Code.MISSING_PREDICATE, Violation.Code.NO_WHERE);
  }

  @Test
  void appliesSuppressionsByOrigin() {
    FencedDataSource suppressed =
        QueryFence.wrap(
            fenced.delegate(),
            Policy.builder()
                .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
                .suppress(
                    "tenant-isolation",
                    OrderRepository.class.getName() + "#findByStatus",
                    "Admin screen that lists orders of every tenant.")
                .build());
    OrderRepository admin = new OrderRepository(new JdbcTemplate(suppressed));

    admin.findByStatus(1L, "OPEN");

    assertThat(suppressed.recorder().statements()).hasSize(1);
    assertThat(suppressed.recorder().findings()).isEmpty();
  }

  @Test
  void recordsStatementsExecutedOnOtherThreads() throws Exception {
    Thread worker = new Thread(() -> repository.findByStatus(1L, "OPEN"));
    worker.start();
    worker.join();

    assertThat(fenced.recorder().statements()).hasSize(1);
    assertThat(fenced.recorder().findings()).hasSize(1);
  }

  @Test
  void recordsStatementsThatFailedWhileExecuting() {
    repository.insertFixedRow();
    assertThatThrownBy(() -> repository.insertFixedRow()).isInstanceOf(RuntimeException.class);
    int expectedLine = OrderRepository.lastLine;

    assertThat(fenced.recorder().statements())
        .hasSize(2)
        .last()
        .satisfies(
            statement -> {
              assertThat(statement.failed()).isTrue();
              assertThat(statement.origin().methodName()).isEqualTo("insertFixedRow");
              assertThat(statement.origin().lineNumber()).isEqualTo(expectedLine);
            });
  }

  @Test
  void doesNotSeeStatementsThatFailBeforeTheyAreExecuted() {
    // Known limitation: a statement the driver rejects while preparing it never reaches the
    // listener, so QueryFence cannot check it. The test fails on its own anyway.
    assertThatThrownBy(() -> repository.readMissingTable()).isInstanceOf(RuntimeException.class);

    assertThat(fenced.recorder().statements()).isEmpty();
  }

  @Test
  void resolvesTheOriginInsideTheConfiguredBasePackages() {
    FencedDataSource scoped =
        QueryFence.wrap(fenced.delegate(), POLICY, CaptureSettings.ofBasePackages("com.acme"));

    new OrderRepository(new JdbcTemplate(scoped)).findByStatus(1L, "OPEN");
    int expectedLine = OrderRepository.lastLine;

    assertThat(scoped.recorder().statements())
        .singleElement()
        .satisfies(
            statement -> {
              assertThat(statement.origin().className()).isEqualTo(OrderRepository.class.getName());
              assertThat(statement.origin().lineNumber()).isEqualTo(expectedLine);
            });
  }

  @Test
  void reportsAnUnknownOriginWhenNoFrameMatchesTheBasePackages() {
    FencedDataSource scoped =
        QueryFence.wrap(
            fenced.delegate(), POLICY, CaptureSettings.ofBasePackages("com.nowhere.at.all"));

    new OrderRepository(new JdbcTemplate(scoped)).findByStatus(1L, "OPEN");

    assertThat(scoped.recorder().statements())
        .singleElement()
        .satisfies(
            statement -> {
              assertThat(statement.origin().isKnown()).isFalse();
              assertThat(statement.origin()).hasToString("unknown origin");
              assertThat(statement.origin().classAndMethod()).isEqualTo("unknown");
            });
    assertThat(scoped.recorder().findings()).hasSize(1);
  }

  @Test
  void clearForgetsWhatWasRecordedSoFar() {
    repository.findByStatus(1L, "OPEN");
    assertThat(fenced.recorder().statements()).hasSize(1);

    fenced.recorder().clear();
    assertThat(fenced.recorder().statements()).isEmpty();
    assertThat(fenced.recorder().findings()).isEmpty();

    repository.findByStatus(1L, "OPEN");
    assertThat(fenced.recorder().statements()).hasSize(1);
  }

  @Test
  void leavesTheSqlUnchangedAndKeepsTheDataSourceUsable() {
    repository.insertAll(List.<Object[]>of(new Object[] {9L, 3L, "OPEN", 5}));

    List<Finding> findings = fenced.recorder().findings();
    assertThat(findings).isEmpty();
    assertThat(
            new JdbcTemplate(fenced)
                .queryForObject("SELECT COUNT(*) FROM purchase_order", Long.class))
        .isEqualTo(1L);
    assertThat(fenced.delegate()).isNotSameAs(fenced);
  }

  /** A pool, a proxy over it, and both fenced: one execution must be one statement, not two. */
  @Test
  void recordsAStatementOnceWhenFencedDataSourcesAreLayered() {
    FencedDataSource outer = QueryFence.wrap(new LazyConnectionDataSourceProxy(fenced), POLICY);

    new OrderRepository(new JdbcTemplate(outer)).findByStatus(1L, "OPEN");
    int leakLine = OrderRepository.lastLine;

    assertThat(outer.recorder().statements())
        .singleElement()
        .satisfies(statement -> assertThat(statement.origin().lineNumber()).isEqualTo(leakLine));
    assertThat(outer.recorder().findings()).hasSize(1);
    assertThat(fenced.recorder().statements()).isEmpty();
  }

  @Test
  void theInnerLayerStillRecordsWhatReachesItDirectly() {
    FencedDataSource outer = QueryFence.wrap(new LazyConnectionDataSourceProxy(fenced), POLICY);
    OrderRepository throughOuter = new OrderRepository(new JdbcTemplate(outer));

    throughOuter.insertFixedRow();
    assertThatThrownBy(throughOuter::insertFixedRow).isInstanceOf(RuntimeException.class);
    repository.findByStatus(1L, "OPEN");

    assertThat(outer.recorder().statements()).hasSize(2);
    assertThat(fenced.recorder().statements())
        .singleElement()
        .satisfies(statement -> assertThat(statement.sql()).contains("WHERE status = ?"));
  }
}
