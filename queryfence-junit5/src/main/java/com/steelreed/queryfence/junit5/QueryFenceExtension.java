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

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Suppression;
import com.steelreed.queryfence.jdbc.CaptureSettings;
import com.steelreed.queryfence.jdbc.FencedDataSource;
import com.steelreed.queryfence.jdbc.QueryFence;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import com.steelreed.queryfence.report.internal.Disabled;
import com.steelreed.queryfence.report.internal.Findings;
import com.steelreed.queryfence.report.internal.RunReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Checks the SQL a test executes against a {@link Policy}.
 *
 * <pre>{@code
 * class OrderRepositoryTest {
 *
 *   @RegisterExtension
 *   static final QueryFenceExtension queryFence = QueryFenceExtension.fromClasspath();
 *
 *   DataSource dataSource = queryFence.wrap(TestDatabase.dataSource());
 * }
 * }</pre>
 *
 * <p>Only the statements of the test method itself — and of everything it calls, on any thread —
 * are checked. Fixtures in {@code @BeforeEach}/{@code @AfterEach}, context startup and migrations
 * run outside that window.
 */
public final class QueryFenceExtension
    implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

  /** How the report names this policy: the resource it came from, or that it was built in Java. */
  private static final String INLINE_POLICY = "policy built in Java";

  private final Policy policy;
  private final String policyName;
  private final CaptureSettings captureSettings;
  private final List<FencedDataSource> dataSources = new CopyOnWriteArrayList<>();

  private QueryFenceExtension(Policy policy, String policyName, CaptureSettings captureSettings) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.policyName = Objects.requireNonNull(policyName, "policyName");
    this.captureSettings = Objects.requireNonNull(captureSettings, "captureSettings");
    RunReport.instance().registerShutdownHook();
  }

  /**
   * Loads {@value PolicyFile#DEFAULT_RESOURCE} from the classpath.
   *
   * @return an extension checking that policy
   * @throws IllegalStateException when the policy is missing or invalid
   */
  public static QueryFenceExtension fromClasspath() {
    return fromClasspath(PolicyFile.DEFAULT_RESOURCE);
  }

  /**
   * Loads a policy from the classpath.
   *
   * @param resource the policy resource, for example {@code queryfence-legacy.yml}
   * @return an extension checking that policy
   * @throws IllegalStateException when the policy is missing or invalid
   */
  public static QueryFenceExtension fromClasspath(String resource) {
    return new QueryFenceExtension(
        PolicyFile.fromClasspath(resource), resource, PolicyFile.captureSettings(resource));
  }

  /**
   * Uses a policy built in Java.
   *
   * @param policy the rules to check
   * @return an extension checking that policy
   */
  public static QueryFenceExtension of(Policy policy) {
    return of(policy, CaptureSettings.defaults());
  }

  /**
   * Uses a policy built in Java, with explicit origin resolution settings.
   *
   * @param policy the rules to check
   * @param captureSettings how the origin of a statement is resolved
   * @return an extension checking that policy
   */
  public static QueryFenceExtension of(Policy policy, CaptureSettings captureSettings) {
    return new QueryFenceExtension(policy, INLINE_POLICY, captureSettings);
  }

  /**
   * Wraps a data source so the statements the test executes through it are checked. When {@code
   * -Dqueryfence.enabled=false} is set, the data source is returned unchanged and a warning is
   * printed.
   *
   * @param dataSource the data source the code under test uses
   * @return the data source to hand to the code under test
   */
  public DataSource wrap(DataSource dataSource) {
    if (!enabled()) {
      Disabled.announce(
          "the JUnit extension of " + policyName,
          Boolean.parseBoolean(System.getProperty(Disabled.ALLOW_IN_CI)));
      return dataSource;
    }
    FencedDataSource fenced = QueryFence.wrap(dataSource, policy, captureSettings);
    dataSources.add(fenced);
    return fenced;
  }

  private static boolean enabled() {
    return !"false".equalsIgnoreCase(System.getProperty(Disabled.PROPERTY));
  }

  /**
   * The policy this extension checks.
   *
   * @return the policy
   */
  public Policy policy() {
    return policy;
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    dataSources.forEach(dataSource -> dataSource.recorder().clear());
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    List<Finding> findings = new ArrayList<>();
    Set<Suppression> matched = new LinkedHashSet<>();
    int statements = 0;
    for (FencedDataSource dataSource : dataSources) {
      findings.addAll(dataSource.recorder().findings());
      statements += dataSource.recorder().statements().size();
      matched.addAll(dataSource.recorder().matchedSuppressions());
      dataSource.recorder().clear();
    }
    String test = testId(context);
    RunReport.instance().add(policyName, policy, test, findings, statements, matched);

    List<Finding> failing = Findings.failing(policy, findings);
    if (!failing.isEmpty()) {
      throw new AssertionError(
          Findings.failureMessage(failing, test, RunReport.instance().reportFile().toString()));
    }
  }

  private static String testId(ExtensionContext context) {
    return context
        .getTestClass()
        .map(
            testClass ->
                testClass.getName()
                    + context.getTestMethod().map(method -> "#" + method.getName()).orElse(""))
        .orElseGet(context::getDisplayName);
  }
}
