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
package com.steelreed.queryfence.junit5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.acme.orders.OrderRepository;
import com.steelreed.queryfence.core.Mode;
import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.jdbc.CaptureSettings;
import com.steelreed.queryfence.junit5.internal.ReportFlushListener;
import com.steelreed.queryfence.report.internal.Disabled;
import com.steelreed.queryfence.report.internal.RunReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * Runs test classes that use the extension inside JUnit's test kit, so the failures they produce
 * can be asserted instead of failing this build.
 */
class ExtensionBehaviourTest {

  @TempDir Path reportDirectory;

  @BeforeEach
  void resetReport() {
    RunReport.instance().reset();
    RunReport.instance().reportFile(reportDirectory.resolve("report.json"));
  }

  @Test
  void failsTheTestAndPointsAtTheLineOfTheLeakingQuery() {
    Events tests = run(LeakingCase.class);

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests))
        .contains("QueryFence: 1 violation in " + LeakingCase.class.getName() + "#listsOrders")
        .contains("MISSING_PREDICATE")
        .contains("purchase_order")
        .contains("Add \"purchase_order.tenant_id = ?\"")
        .contains("SELECT id FROM purchase_order WHERE status = ?")
        .contains(
            "com.acme.orders.OrderRepository#findByStatus (OrderRepository.java:"
                + OrderRepository.lastLine
                + ")");
  }

  @Test
  void passesWhenEveryStatementIsFenced() {
    run(FencedCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
  }

  @Test
  void ignoresStatementsExecutedOutsideTheTestMethod() {
    run(LeakInBeforeEachCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
  }

  @Test
  void reportsWithoutFailingInReportMode() {
    run(ReportModeCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings()).hasSize(1);
  }

  @Test
  void groupsTheReportByPolicy() {
    run(LeakingCase.class);
    run(ReportModeCase.class);

    assertThat(RunReport.instance().results())
        .extracting(results -> results.policy() + "/" + results.mode())
        .containsExactly("queryfence.yml/FAIL", "queryfence-report-mode.yml/REPORT");
    assertThat(RunReport.instance().summary())
        .contains("QueryFence [queryfence.yml, mode FAIL]: 1 violation")
        .contains("QueryFence [queryfence-report-mode.yml, mode REPORT]: 1 violation");
  }

  @Test
  void canBeSwitchedOffWithALoudWarning() {
    System.setProperty("queryfence.enabled", "false");
    System.setProperty("queryfence.allowDisabledInCi", "true");
    Disabled.reset();
    try {
      run(LeakingCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
      assertThat(RunReport.instance().findings()).isEmpty();
      assertThat(RunReport.instance().isDisabledSomewhere()).isTrue();
      assertThat(RunReport.instance().json())
          .contains("\"disabled\":true")
          .contains("queryfence.enabled=false for the JUnit extension of queryfence.yml");
      assertThat(RunReport.instance().summary()).contains("QueryFence was DISABLED");
    } finally {
      System.clearProperty("queryfence.enabled");
      System.clearProperty("queryfence.allowDisabledInCi");
      Disabled.reset();
    }
  }

  @Test
  void appliesSuppressionsFromThePolicyFile() {
    run(SuppressedCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings()).isEmpty();
  }

  @Test
  void writesTheJsonReportWithOneEntryPerFinding() throws Exception {
    run(LeakingCase.class);
    RunReport.instance().flush();

    String json = Files.readString(reportDirectory.resolve("report.json"));
    assertThat(json)
        .contains("\"policy\":\"queryfence.yml\"")
        .contains("\"mode\":\"FAIL\"")
        .contains("\"tests\":1")
        .contains("\"findings\":1")
        .contains("\"code\":\"MISSING_PREDICATE\"")
        .contains("\"table\":\"purchase_order\"")
        .contains("\"method\":\"findByStatus\"")
        .contains("\"line\":" + OrderRepository.lastLine)
        .contains("\"test\":\"" + LeakingCase.class.getName() + "#listsOrders\"");
  }

  @Test
  void countsEveryCheckedStatementInTheSummary() {
    run(FencedCase.class);

    assertThat(RunReport.instance().summary())
        .contains("QueryFence [queryfence.yml, mode FAIL]: 0 violations in 1 test")
        .contains("1 statements checked");
  }

  @Test
  void failsOnAnUnparseableStatementWhenOnUnparseableIsFail() {
    run(FailAndUnparseableFailCase.class).assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(run(FailAndUnparseableFailCase.class))).contains("UNPARSEABLE");
  }

  @Test
  void failsOnAnUnparseableStatementEvenWhenTheModeOnlyReports() {
    run(ReportAndUnparseableFailCase.class).assertStatistics(stats -> stats.started(1).failed(1));
  }

  @Test
  void onlyReportsAnUnparseableStatementWhenOnUnparseableIsReport() {
    run(FailAndUnparseableReportCase.class)
        .assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings())
        .singleElement()
        .satisfies(
            reported ->
                assertThat(reported.finding().violation().code().name()).isEqualTo("UNPARSEABLE"));
  }

  @Test
  void reportsAnUnparseableStatementInReportModeWithoutFailing() {
    run(ReportAndUnparseableReportCase.class)
        .assertStatistics(stats -> stats.started(1).succeeded(1));

    assertThat(RunReport.instance().findings()).hasSize(1);
  }

  @Test
  void failsOnTheLeakWhileOnlyReportingTheStatementItCouldNotParse() {
    Events tests = run(LeakAndUnparseableCase.class);

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests))
        .contains("QueryFence: 1 violation")
        .contains("MISSING_PREDICATE")
        .doesNotContain("UNPARSEABLE");
    assertThat(RunReport.instance().findings())
        .extracting(reported -> reported.finding().violation().code().name())
        .containsExactlyInAnyOrder("MISSING_PREDICATE", "UNPARSEABLE");
  }

  @Test
  void namesTheProtectedTableAnUnparseableStatementMentions() {
    run(FailAndUnparseableReportCase.class);

    assertThat(RunReport.instance().findings())
        .singleElement()
        .satisfies(
            reported -> {
              assertThat(reported.finding().violation().table()).isEqualTo("purchase_order");
              assertThat(reported.finding().violation().ruleId()).isEqualTo("parser");
              assertThat(reported.finding().violation().message())
                  .contains("It mentions purchase_order, which stays unverified here");
            });
  }

  @Test
  void listsTheSuppressionsThatMatchedNothing() {
    run(StaleSuppressionCase.class).assertStatistics(stats -> stats.started(1).succeeded(1));
    RunReport.instance().flush();

    assertThat(RunReport.instance().summary())
        .contains("1 suppression matched nothing")
        .contains("tenant-isolation at com.acme.orders.OrderRepository#listEverything");
    assertThat(RunReport.instance().json())
        .contains("\"unmatchedSuppressions\":[{\"rule\":\"tenant-isolation\"")
        .contains("\"origin\":\"com.acme.orders.OrderRepository#listEverything\"");
  }

  @Test
  void doesNotListASuppressionThatSilencedSomething() {
    run(SuppressedCase.class);

    assertThat(RunReport.instance().summary()).doesNotContain("matched nothing");
    assertThat(RunReport.instance().json()).contains("\"unmatchedSuppressions\":[]");
  }

  @Test
  void printsTheSummaryWhenTheTestPlanEnds() throws Exception {
    run(LeakingCase.class);
    java.io.PrintStream out = System.out;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      new ReportFlushListener().testPlanExecutionFinished(null);
    } finally {
      System.setOut(out);
    }

    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("QueryFence [queryfence.yml, mode FAIL]: 1 violation in 1 test");
    assertThat(Files.exists(reportDirectory.resolve("report.json"))).isTrue();
  }

  @Test
  void printsTheSummaryOnlyOncePerResult() {
    run(LeakingCase.class);
    RunReport.instance().flush();
    java.io.PrintStream out = System.out;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      new ReportFlushListener().testPlanExecutionFinished(null);
    } finally {
      System.setOut(out);
    }

    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8)).isEmpty();
  }

  @Test
  void readsTheBasePackagesOfThePolicyFile() {
    assertThat(PolicyFile.captureSettings("queryfence-base-packages.yml").basePackages())
        .containsExactly("com.acme");
    assertThat(PolicyFile.captureSettings("queryfence.yml")).isSameAs(CaptureSettings.defaults());
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
            .flatMap(event -> event.getPayload(org.junit.platform.engine.TestExecutionResult.class))
            .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable);
    assertThat(failure).isPresent();
    return failure.orElseThrow().getMessage();
  }

  // ------------------------------------------------------------------ cases run by the test kit

  /** Executes a query that forgets the tenant filter. */
  static class LeakingCase {

    @RegisterExtension static final QueryFenceExtension fence = QueryFenceExtension.fromClasspath();

    @Test
    void listsOrders() {
      repository(fence).findByStatus(1L, "OPEN");
    }
  }

  /** Executes only fenced queries. */
  static class FencedCase {

    @RegisterExtension static final QueryFenceExtension fence = QueryFenceExtension.fromClasspath();

    @Test
    void listsOrders() {
      List<?> orders = repository(fence).findByTenantAndStatus(1L, "OPEN");
      assertThat(orders).isEmpty();
    }
  }

  /** Leaks in a fixture, which is outside the capture window. */
  static class LeakInBeforeEachCase {

    @RegisterExtension static final QueryFenceExtension fence = QueryFenceExtension.fromClasspath();

    @BeforeEach
    void seed() {
      repository(fence).findByStatus(1L, "OPEN");
    }

    @Test
    void listsOrders() {
      repository(fence).findByTenantAndStatus(1L, "OPEN");
    }
  }

  /** Same leak, but the policy only reports. */
  static class ReportModeCase {

    @RegisterExtension
    static final QueryFenceExtension fence =
        QueryFenceExtension.fromClasspath("queryfence-report-mode.yml");

    @Test
    void listsOrders() {
      repository(fence).findByStatus(1L, "OPEN");
    }
  }

  /** Same leak, but the policy suppresses that method with a reason. */
  static class SuppressedCase {

    @RegisterExtension
    static final QueryFenceExtension fence =
        QueryFenceExtension.fromClasspath("queryfence-suppressed.yml");

    @Test
    void listsOrders() {
      repository(fence).findByStatus(1L, "OPEN");
    }
  }

  /** Executes a statement QueryFence cannot parse; both modes fail. */
  static class FailAndUnparseableFailCase {

    @RegisterExtension
    static final QueryFenceExtension fence = QueryFenceExtension.of(policy(Mode.FAIL, Mode.FAIL));

    @Test
    void renumbers() {
      repository(fence).renumber(1L);
    }
  }

  /** The mode only reports, but unparseable statements still fail. */
  static class ReportAndUnparseableFailCase {

    @RegisterExtension
    static final QueryFenceExtension fence = QueryFenceExtension.of(policy(Mode.REPORT, Mode.FAIL));

    @Test
    void renumbers() {
      repository(fence).renumber(1L);
    }
  }

  /** Violations fail, but what the parser cannot read is only reported. */
  static class FailAndUnparseableReportCase {

    @RegisterExtension
    static final QueryFenceExtension fence = QueryFenceExtension.of(policy(Mode.FAIL, Mode.REPORT));

    @Test
    void renumbers() {
      repository(fence).renumber(1L);
    }
  }

  /** Nothing fails. */
  static class ReportAndUnparseableReportCase {

    @RegisterExtension
    static final QueryFenceExtension fence =
        QueryFenceExtension.of(policy(Mode.REPORT, Mode.REPORT));

    @Test
    void renumbers() {
      repository(fence).renumber(1L);
    }
  }

  /** A leak and an unparseable statement in one test, with onUnparseable: REPORT. */
  static class LeakAndUnparseableCase {

    @RegisterExtension
    static final QueryFenceExtension fence = QueryFenceExtension.of(policy(Mode.FAIL, Mode.REPORT));

    @Test
    void listsOrdersAndRenumbers() {
      OrderRepository repository = repository(fence);
      repository.renumber(1L);
      repository.findByStatus(1L, "OPEN");
    }
  }

  /** The policy suppresses a method that the test never calls. */
  static class StaleSuppressionCase {

    @RegisterExtension
    static final QueryFenceExtension fence =
        QueryFenceExtension.fromClasspath("queryfence-stale-suppression.yml");

    @Test
    void listsOrders() {
      repository(fence).findByTenantAndStatus(1L, "OPEN");
    }
  }

  private static Policy policy(Mode mode, Mode onUnparseable) {
    return Policy.builder()
        .mode(mode)
        .onUnparseable(onUnparseable)
        .requirePredicate("tenant-isolation", "tenant_id", "purchase_order")
        .build();
  }

  private static OrderRepository repository(QueryFenceExtension fence) {
    DataSource dataSource = fence.wrap(TestDatabase.create());
    return new OrderRepository(dataSource);
  }
}
