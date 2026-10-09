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
import com.steelreed.queryfence.core.Suppression;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects what every test found and, once the test JVM ends, prints a summary and writes {@code
 * target/queryfence/report.json}.
 *
 * <p>Results are grouped by policy: one run can use several policies (a strict one for most tests,
 * a reporting one for a legacy package), and each group carries its own mode.
 *
 * <p>The flush is a shutdown hook rather than a JUnit callback on purpose: it behaves the same on
 * every JUnit 5 and 6 version, and it also runs when the build stops after a failure.
 */
public final class RunReport {

  /** One finding, with the test that executed the statement. */
  public record ReportedFinding(String test, Finding finding) {}

  /** What one policy found during the run. */
  public static final class PolicyResults {

    private final String policy;
    private final Policy declared;
    private final Set<String> tests = new LinkedHashSet<>();
    private final List<ReportedFinding> findings = new ArrayList<>();
    private final Set<Suppression> matchedSuppressions = new LinkedHashSet<>();
    private int statements;

    private PolicyResults(String policy, Policy declared) {
      this.policy = policy;
      this.declared = declared;
    }

    public String policy() {
      return policy;
    }

    public Mode mode() {
      return declared.mode();
    }

    public Mode onUnparseable() {
      return declared.onUnparseable();
    }

    /**
     * The suppressions of this policy that silenced nothing during the run. A suppression that
     * matches nothing is usually a suppression whose code has moved, and it is the one thing a
     * policy file cannot tell you by reading it.
     */
    public List<Suppression> unmatchedSuppressions() {
      return declared.suppressions().stream()
          .filter(suppression -> !matchedSuppressions.contains(suppression))
          .toList();
    }

    public List<ReportedFinding> findings() {
      return List.copyOf(findings);
    }

    public int testCount() {
      return tests.size();
    }

    public int statementCount() {
      return statements;
    }
  }

  private static final RunReport INSTANCE = new RunReport();

  /** Keyed by policy name and mode, so a policy run in both modes stays two entries. */
  private final Map<String, PolicyResults> results = new LinkedHashMap<>();

  private final AtomicBoolean hookRegistered = new AtomicBoolean();
  private boolean dirty;
  private final Set<String> disabledReasons = new LinkedHashSet<>();
  private volatile Path reportFile = Path.of("target", "queryfence", "report.json");

  private RunReport() {}

  public static RunReport instance() {
    return INSTANCE;
  }

  /** Makes sure the report is flushed when the test JVM ends. */
  public void registerShutdownHook() {
    if (hookRegistered.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "queryfence-report"));
    }
  }

  /** Where the report is written; {@code target/queryfence/report.json} by default. */
  public Path reportFile() {
    return reportFile;
  }

  /** Writes the report somewhere else; mainly for QueryFence's own tests. */
  public void reportFile(Path reportFile) {
    this.reportFile = reportFile;
  }

  /** Records what one test executed under one policy. */
  public synchronized void add(
      String policy,
      Policy declared,
      String test,
      List<Finding> testFindings,
      int executedStatements,
      Set<Suppression> matchedSuppressions) {
    PolicyResults group =
        results.computeIfAbsent(
            policy + "/" + declared.mode(), key -> new PolicyResults(policy, declared));
    group.tests.add(test);
    group.statements += executedStatements;
    group.matchedSuppressions.addAll(matchedSuppressions);
    for (Finding finding : testFindings) {
      group.findings.add(new ReportedFinding(test, finding));
    }
    dirty = true;
  }

  /** Records that QueryFence was switched off somewhere, so the report cannot look clean. */
  public synchronized void disabled(String reason) {
    disabledReasons.add(reason);
    dirty = true;
    registerShutdownHook();
  }

  public synchronized boolean isDisabledSomewhere() {
    return !disabledReasons.isEmpty();
  }

  public synchronized List<PolicyResults> results() {
    return List.copyOf(results.values());
  }

  /** Every finding of the run, whatever policy found it. */
  public synchronized List<ReportedFinding> findings() {
    List<ReportedFinding> all = new ArrayList<>();
    results.values().forEach(group -> all.addAll(group.findings));
    return List.copyOf(all);
  }

  /** Prints the summary and writes the JSON report. */
  public synchronized void flush() {
    if (results.isEmpty() && disabledReasons.isEmpty()) {
      return;
    }
    if (!dirty) {
      // Already printed and written; nothing has happened since.
      return;
    }
    dirty = false;
    System.out.println(summary());
    write();
  }

  public synchronized String summary() {
    StringBuilder sb = new StringBuilder();
    for (String reason : disabledReasons) {
      sb.append("\nQueryFence was DISABLED: ").append(reason);
    }
    for (PolicyResults group : results.values()) {
      sb.append("\nQueryFence [")
          .append(group.policy)
          .append(", mode ")
          .append(group.mode())
          .append("]: ")
          .append(group.findings.size())
          .append(group.findings.size() == 1 ? " violation in " : " violations in ")
          .append(group.tests.size())
          .append(group.tests.size() == 1 ? " test" : " tests")
          .append(" (")
          .append(group.statements)
          .append(" statements checked, unparseable ")
          .append(group.onUnparseable())
          .append(")");
      for (ReportedFinding reported : group.findings) {
        sb.append("\n\n").append(Findings.format(reported.finding(), reported.test()));
      }
      List<Suppression> unmatched = group.unmatchedSuppressions();
      if (!unmatched.isEmpty()) {
        sb.append("\n\n  ")
            .append(
                unmatched.size() == 1
                    ? "1 suppression matched nothing; the code it names has probably moved:"
                    : unmatched.size()
                        + " suppressions matched nothing; the code they name has probably moved:");
        for (Suppression suppression : unmatched) {
          sb.append("\n    ")
              .append(suppression.ruleId())
              .append(" at ")
              .append(suppression.origin());
        }
      }
    }
    if (!findings().isEmpty()) {
      sb.append("\n\nFull report: ").append(reportFile);
    }
    return sb.toString();
  }

  private void write() {
    try {
      Path parent = reportFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(reportFile, json(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write the QueryFence report to " + reportFile, e);
    }
  }

  public synchronized String json() {
    Json json = new Json();
    json.object();
    json.field("generatedAt", Instant.now().toString());
    json.field("disabled", !disabledReasons.isEmpty());
    json.key("disabledReasons").array();
    disabledReasons.forEach(json::value);
    json.end();
    json.key("policies").array();
    for (PolicyResults group : results.values()) {
      json.object();
      json.field("policy", group.policy);
      json.field("mode", group.mode().name());
      json.field("onUnparseable", group.onUnparseable().name());
      json.key("summary").object();
      json.field("tests", group.tests.size());
      json.field("statements", group.statements);
      json.field("findings", group.findings.size());
      json.end();
      json.key("findings").array();
      group.findings.forEach(reported -> writeFinding(json, reported));
      json.end();
      json.key("unmatchedSuppressions").array();
      for (Suppression suppression : group.unmatchedSuppressions()) {
        json.object();
        json.field("rule", suppression.ruleId());
        json.field("origin", suppression.origin());
        json.field("reason", suppression.reason());
        json.end();
      }
      json.end();
      json.end();
    }
    json.end();
    json.end();
    return json.toString();
  }

  private static void writeFinding(Json json, ReportedFinding reported) {
    var violation = reported.finding().violation();
    var statement = reported.finding().statement();
    var origin = statement.origin();
    json.object();
    json.field("test", reported.test());
    json.field("rule", violation.ruleId());
    json.field("type", violation.ruleType());
    json.field("code", violation.code().name());
    json.field("table", violation.table());
    json.field("alias", violation.alias());
    json.field("message", violation.message());
    json.field("sql", statement.sql());
    json.field("batch", statement.batch());
    json.key("origin").object();
    json.field("class", origin.className());
    json.field("method", origin.methodName());
    json.field("file", origin.fileName());
    json.field("line", origin.lineNumber());
    json.end();
    json.end();
  }

  /** Forgets everything recorded so far; mainly for QueryFence's own tests. */
  public synchronized void reset() {
    results.clear();
    disabledReasons.clear();
    dirty = false;
  }
}
