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
package com.steelreed.queryfence.spring.internal;

import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import com.steelreed.queryfence.report.internal.Findings;
import com.steelreed.queryfence.report.internal.RunReport;
import java.util.List;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Checks the statements a test method executed. {@code beforeTestExecution} and {@code
 * afterTestExecution} bracket the test method itself, so fixtures, context startup and migrations
 * stay outside the capture window (DESIGN.md).
 */
public final class QueryFenceTestExecutionListener extends AbstractTestExecutionListener {

  @Override
  public int getOrder() {
    // After the listeners that prepare the context and the transaction.
    return 6_000;
  }

  @Override
  public void beforeTestExecution(TestContext testContext) {
    dataSources(testContext).ifPresent(FencedDataSources::clear);
  }

  @Override
  public void afterTestExecution(TestContext testContext) {
    var registry = dataSources(testContext);
    if (registry.isEmpty()) {
      return;
    }
    FencedDataSources dataSources = registry.get();
    List<Finding> findings = dataSources.findings();
    String test =
        testContext.getTestClass().getName() + "#" + testContext.getTestMethod().getName();
    RunReport.instance().registerShutdownHook();
    RunReport.instance()
        .add(
            dataSources.policyName(),
            dataSources.policy(),
            test,
            findings,
            dataSources.statementCount(),
            dataSources.matchedSuppressions());
    dataSources.clear();

    List<Finding> failing = Findings.failing(dataSources.policy(), findings);
    if (failing.isEmpty()) {
      return;
    }
    if (testContext.getTestException() != null) {
      // The test already failed; do not hide its error behind ours.
      return;
    }
    throw new AssertionError(
        Findings.failureMessage(failing, test, RunReport.instance().reportFile().toString()));
  }

  private static java.util.Optional<FencedDataSources> dataSources(TestContext testContext) {
    try {
      return java.util.Optional.of(
          testContext.getApplicationContext().getBean(FencedDataSources.class));
    } catch (NoSuchBeanDefinitionException | IllegalStateException e) {
      return java.util.Optional.empty();
    }
  }
}
