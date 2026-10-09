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
package com.steelreed.queryfence.report.internal;

import com.steelreed.queryfence.core.Mode;
import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.jdbc.CapturedStatement;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import java.util.List;

/** Formats findings for the console and for assertion messages. */
public final class Findings {

  private Findings() {}

  /**
   * The findings that must fail the test, which is decided per finding: an {@code UNPARSEABLE}
   * finding is governed by {@code onUnparseable}, every other one by {@code mode}. A run may
   * therefore fail on a leak while only reporting a statement the parser could not read, which is
   * what {@code onUnparseable: REPORT} promises.
   */
  public static List<Finding> failing(Policy policy, List<Finding> findings) {
    return findings.stream()
        .filter(finding -> policy.modeFor(finding.violation().code()) == Mode.FAIL)
        .toList();
  }

  /** One finding, as the console report and the failure message show it. */
  public static String format(Finding finding, String test) {
    Violation violation = finding.violation();
    CapturedStatement statement = finding.statement();
    StringBuilder sb = new StringBuilder();
    sb.append("  [")
        .append(violation.ruleId() == null ? "parser" : violation.ruleId())
        .append("] ")
        .append(violation.code());
    if (violation.table() != null) {
      sb.append("\n    table   : ").append(violation.table());
      if (violation.alias() != null) {
        sb.append(" (alias ").append(violation.alias()).append(")");
      }
    }
    sb.append("\n    problem : ").append(violation.message());
    sb.append("\n    sql     : ").append(statement.sql().replace("\n", " "));
    sb.append("\n    origin  : ").append(statement.origin());
    if (test != null) {
      sb.append("\n    test    : ").append(test);
    }
    return sb.toString();
  }

  /** The message of the {@link AssertionError} that fails a test. */
  public static String failureMessage(List<Finding> findings, String test, String reportFile) {
    StringBuilder sb = new StringBuilder();
    sb.append("QueryFence: ")
        .append(findings.size())
        .append(findings.size() == 1 ? " violation" : " violations")
        .append(" in ")
        .append(test)
        .append("\n");
    for (Finding finding : findings) {
      sb.append("\n").append(format(finding, null)).append("\n");
    }
    sb.append("\nFull report: ").append(reportFile);
    return sb.toString();
  }
}
