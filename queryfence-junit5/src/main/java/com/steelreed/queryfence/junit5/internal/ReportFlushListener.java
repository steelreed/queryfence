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
package com.steelreed.queryfence.junit5.internal;

import com.steelreed.queryfence.report.internal.RunReport;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Prints the summary and writes the report when the test plan ends.
 *
 * <p>Registered through {@code META-INF/services}, so it runs for every JUnit Platform launch — the
 * JUnit extension, {@code queryfence-spring-test}, Maven, Gradle and the IDE alike — without any
 * test code.
 *
 * <p>This exists because a JVM shutdown hook is too late: Surefire has closed the channel that
 * relays the forked JVM's output by then, so the summary was written to a stream nobody reads. The
 * end of the test plan is still inside the run, so the summary reaches the build log. The shutdown
 * hook stays as a fallback for runs that are not launched by the platform, and printing is guarded,
 * so the summary appears once.
 */
public final class ReportFlushListener implements TestExecutionListener {

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    RunReport.instance().flush();
  }
}
