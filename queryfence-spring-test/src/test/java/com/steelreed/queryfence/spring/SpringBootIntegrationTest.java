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
package com.steelreed.queryfence.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.acme.layered.LayeredApplication;
import com.acme.layered.LayeredApplication.TenantRouting;
import com.acme.shop.OrderRepository;
import com.acme.shop.ShopApplication;
import com.steelreed.queryfence.jdbc.FencedDataSource;
import com.steelreed.queryfence.report.internal.Disabled;
import com.steelreed.queryfence.report.internal.RunReport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A real Spring Boot test: the user adds the dependency and {@code queryfence.yml}, writes no
 * QueryFence code, and a query that forgets the tenant filter fails the build.
 */
class SpringBootIntegrationTest {

  @TempDir Path reportDirectory;

  @BeforeEach
  void resetReport() {
    RunReport.instance().reset();
    RunReport.instance().reportFile(reportDirectory.resolve("report.json"));
  }

  @Test
  void failsTheSpringBootTestAtTheLineOfTheLeakingQuery() {
    Events tests = run(LeakingSpringBootCase.class);

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests))
        .contains("QueryFence: 1 violation in " + LeakingSpringBootCase.class.getName())
        .contains("MISSING_PREDICATE")
        .contains("SELECT id FROM purchase_order WHERE status = ?")
        .contains(
            "com.acme.shop.OrderRepository#findByStatus (OrderRepository.java:"
                + OrderRepository.lastLine
                + ")");
  }

  @Test
  void passesWhenTheQueryFiltersByTenant() {
    run(FencedSpringBootCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings()).isEmpty();
  }

  @Test
  void usesThePolicyNamedByTheAnnotation() {
    run(OtherPolicyCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().results())
        .singleElement()
        .satisfies(
            results -> {
              assertThat(results.policy()).isEqualTo("queryfence-report-mode.yml");
              assertThat(results.findings()).hasSize(1);
            });
  }

  @Test
  void canBeSwitchedOffWithALoudWarning() {
    Disabled.reset();
    try {
      run(DisabledCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
      assertThat(RunReport.instance().findings()).isEmpty();
      assertThat(RunReport.instance().json())
          .contains("\"disabled\":true")
          .contains("the Spring test context of queryfence.yml");
    } finally {
      Disabled.reset();
    }
  }

  @Test
  void wrapsEveryDataSourceBeanWithoutTestCode() {
    run(DataSourceBeanCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
  }

  /** Lazy proxy over routing data source over Hikari pool, all beans: one query, one statement. */
  @Test
  void recordsAStatementOnceHoweverTheDataSourceBeansAreLayered() {
    Events tests = run(LayeredLeakCase.class);

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests))
        .contains("QueryFence: 1 violation in " + LayeredLeakCase.class.getName());
    assertThat(RunReport.instance().results())
        .singleElement()
        .satisfies(
            results -> {
              assertThat(results.statementCount()).isEqualTo(1);
              assertThat(results.findings()).hasSize(1);
            });
  }

  @Test
  void keepsTheConcreteTypeOfEveryDataSourceBean() {
    Events tests = run(ConcreteTypeCase.class);

    assertThat(tests.failed().count()).as(() -> failureMessage(tests)).isZero();
    tests.assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().results())
        .singleElement()
        .satisfies(results -> assertThat(results.statementCount()).isEqualTo(1));
  }

  @Test
  void onlyReportsWhatItCannotParseAndResolvesOriginsInTheDeclaredBasePackages() {
    run(UnparseableCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings())
        .singleElement()
        .satisfies(
            reported -> {
              assertThat(reported.finding().violation().code().name()).isEqualTo("UNPARSEABLE");
              assertThat(reported.finding().violation().table()).isEqualTo("purchase_order");
              assertThat(reported.finding().origin().className())
                  .isEqualTo(OrderRepository.class.getName());
              assertThat(reported.finding().origin().methodName()).isEqualTo("renumber");
            });
  }

  private static Events run(Class<?> testClass) {
    return EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(testClass))
        .execute()
        .testEvents();
  }

  private static String failureMessage(Events tests) {
    Optional<Throwable> failure =
        tests.failed().stream()
            .findFirst()
            .flatMap(event -> event.getPayload(TestExecutionResult.class))
            .flatMap(TestExecutionResult::getThrowable);
    assertThat(failure).isPresent();
    return failure.orElseThrow().getMessage();
  }

  // ------------------------------------------------------------------ cases run by the test kit

  @SpringBootTest(classes = ShopApplication.class)
  static class LeakingSpringBootCase {

    @Autowired OrderRepository repository;

    @Test
    void listsOrders() {
      repository.findByStatus(1L, "OPEN");
    }
  }

  @SpringBootTest(classes = ShopApplication.class)
  static class FencedSpringBootCase {

    @Autowired OrderRepository repository;

    @Test
    void listsOrders() {
      assertThat(repository.findByTenantAndStatus(1L, "OPEN")).isEmpty();
    }
  }

  @SpringBootTest(classes = ShopApplication.class)
  @QueryFencePolicy("queryfence-report-mode.yml")
  static class OtherPolicyCase {

    @Autowired OrderRepository repository;

    @Test
    void listsOrders() {
      repository.findByStatus(1L, "OPEN");
    }
  }

  /** mode FAIL, onUnparseable REPORT: the statement is recorded and the test still passes. */
  @SpringBootTest(classes = ShopApplication.class)
  @QueryFencePolicy("queryfence-unparseable-report.yml")
  static class UnparseableCase {

    @Autowired OrderRepository repository;

    @Test
    void renumbers() {
      repository.renumber(1L);
    }
  }

  @SpringBootTest(
      classes = ShopApplication.class,
      properties = {"queryfence.enabled=false", "queryfence.allowDisabledInCi=true"})
  static class DisabledCase {

    @Autowired OrderRepository repository;

    @Test
    void listsOrders() {
      repository.findByStatus(1L, "OPEN");
    }
  }

  @SpringBootTest(classes = ShopApplication.class)
  static class DataSourceBeanCase {

    @Autowired DataSource dataSource;

    @Test
    void theDataSourceBeanIsFenced() {
      assertThat(dataSource).isInstanceOf(com.steelreed.queryfence.jdbc.FencedDataSource.class);
    }
  }

  @SpringBootTest(classes = LayeredApplication.class)
  static class LayeredLeakCase {

    @Autowired OrderRepository repository;

    @Test
    void listsOrders() {
      repository.findByStatus(1L, "OPEN");
    }
  }

  /** Injection by the concrete pool type must keep working, and the injected pool is fenced. */
  @SpringBootTest(classes = LayeredApplication.class)
  static class ConcreteTypeCase {

    @Autowired HikariDataSource pool;
    @Autowired TenantRouting routing;

    @Test
    void injectsTheBeanByItsOwnClass() throws Exception {
      assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:h2:mem:layered;DB_CLOSE_DELAY=-1");
      assertThat(pool).isInstanceOf(FencedDataSource.class);
      assertThat(routing).isInstanceOf(FencedDataSource.class);
      assertThat(((FencedDataSource) pool).delegate()).isNotSameAs(pool);
      assertThat(pool.unwrap(HikariDataSource.class)).isSameAs(pool);
      assertThat(pool.isWrapperFor(FencedDataSource.class)).isTrue();

      new JdbcTemplate(pool)
          .queryForList("SELECT id FROM purchase_order WHERE tenant_id = ?", Long.class, 1L);
    }
  }
}
