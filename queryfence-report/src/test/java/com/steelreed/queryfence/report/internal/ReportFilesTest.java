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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several test JVMs write reports into one directory; none of their findings may be lost. */
class ReportFilesTest {

  private static final String RUN = "4242@2026-10-09T09:00:00Z";

  @TempDir Path directory;

  @Test
  void mergesTheReportsOfEveryTestJvmOfTheRunWhateverOrderTheyFinishIn() throws Exception {
    var first =
        ReportFiles.writeAndMerge(
            directory, "report-a.json", report(RUN, "OrderTest#lists", 3, false), RUN);
    assertThat(first).isEqualTo(new ReportFiles.Merged(1, 1));

    var second =
        ReportFiles.writeAndMerge(
            directory, "report-b.json", report(RUN, "InvoiceTest#lists", 4, false), RUN);

    assertThat(second).isEqualTo(new ReportFiles.Merged(2, 2));
    Map<String, Object> merged = merged();
    assertThat(merged.get("reports")).isEqualTo(List.of("report-a.json", "report-b.json"));
    Map<String, Object> group = onlyGroup(merged);
    assertThat(group.get("summary"))
        .isEqualTo(Map.of("tests", 2L, "statements", 7L, "findings", 2L));
    assertThat(findings(group))
        .extracting(finding -> finding.get("test"))
        .containsExactly("OrderTest#lists", "InvoiceTest#lists");
    assertThat(Files.exists(directory.resolve("report-a.json"))).isTrue();
    assertThat(Files.exists(directory.resolve("report-b.json"))).isTrue();
  }

  /** Real forked JVMs finishing at the same moment: the lock keeps every one of them. */
  @Test
  void losesNothingWhenForkedJvmsFinishTogether() throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    List<Process> forks = new java.util.ArrayList<>();
    for (int fork = 1; fork <= 4; fork++) {
      forks.add(
          new ProcessBuilder(
                  java,
                  "-cp",
                  System.getProperty("java.class.path"),
                  Fork.class.getName(),
                  directory.toString(),
                  "report-fork" + fork + ".json",
                  "Fork" + fork + "#test")
              .inheritIO()
              .start());
    }
    for (Process fork : forks) {
      assertThat(fork.waitFor()).isZero();
    }

    assertThat(findings(onlyGroup(merged())))
        .extracting(finding -> finding.get("test"))
        .containsExactlyInAnyOrder("Fork1#test", "Fork2#test", "Fork3#test", "Fork4#test");
  }

  /** One forked test JVM: writes its report and merges, like RunReport at the end of a run. */
  static final class Fork {
    public static void main(String[] args) throws Exception {
      ReportFiles.writeAndMerge(Path.of(args[0]), args[1], report(RUN, args[2], 1, false), RUN);
    }
  }

  @Test
  void rewritingItsOwnReportDoesNotCountAJvmTwice() throws Exception {
    ReportFiles.writeAndMerge(directory, "report-a.json", report(RUN, "A#one", 1, false), RUN);
    ReportFiles.writeAndMerge(directory, "report-a.json", report(RUN, "A#two", 2, false), RUN);

    assertThat(findings(onlyGroup(merged())))
        .singleElement()
        .satisfies(finding -> assertThat(finding.get("test")).isEqualTo("A#two"));
  }

  @Test
  void aSuppressionIsUnmatchedOnlyWhenNoJvmMatchedIt() throws Exception {
    ReportFiles.writeAndMerge(
        directory, "report-a.json", report(RUN, "A#one", 1, false, "com.a.A#x", "com.a.A#y"), RUN);
    ReportFiles.writeAndMerge(
        directory, "report-b.json", report(RUN, "B#one", 1, false, "com.a.A#y"), RUN);

    assertThat(onlyGroup(merged()).get("unmatchedSuppressions"))
        .isEqualTo(
            List.of(Map.of("rule", "tenant-isolation", "origin", "com.a.A#y", "reason", "legacy")));
  }

  @Test
  void isDisabledWhenAnyJvmWasDisabled() throws Exception {
    ReportFiles.writeAndMerge(directory, "report-a.json", report(RUN, "A#one", 1, false), RUN);
    ReportFiles.writeAndMerge(directory, "report-b.json", report(RUN, "B#one", 1, true), RUN);

    Map<String, Object> merged = merged();
    assertThat(merged.get("disabled")).isEqualTo(true);
    assertThat(merged.get("disabledReasons")).isEqualTo(List.of("queryfence.enabled=false"));
  }

  @Test
  void leavesOutAndDeletesTheReportsOfAFinishedRun() throws Exception {
    String finished = "999999999@2001-01-01T00:00:00Z";
    ReportFiles.writeAndMerge(
        directory, "report-old.json", report(finished, "Old#x", 1, false), finished);

    ReportFiles.writeAndMerge(directory, "report-new.json", report(RUN, "New#x", 1, false), RUN);

    assertThat(merged().get("reports")).isEqualTo(List.of("report-new.json"));
    assertThat(Files.exists(directory.resolve("report-old.json"))).isFalse();
  }

  @Test
  void leavesOutButKeepsTheReportsOfARunThatIsStillGoing() throws Exception {
    ProcessHandle self = ProcessHandle.current();
    String running = self.pid() + "@" + self.info().startInstant().orElseThrow();
    ReportFiles.writeAndMerge(
        directory, "report-other.json", report(running, "Other#x", 1, false), running);

    ReportFiles.writeAndMerge(directory, "report-new.json", report(RUN, "New#x", 1, false), RUN);

    assertThat(merged().get("reports")).isEqualTo(List.of("report-new.json"));
    assertThat(Files.exists(directory.resolve("report-other.json"))).isTrue();
  }

  @Test
  void mergesEverythingAndDeletesNothingWhenTheRunIsUnknown() throws Exception {
    String finished = "999999999@2001-01-01T00:00:00Z";
    ReportFiles.writeAndMerge(
        directory, "report-old.json", report(finished, "Old#x", 1, false), finished);

    var merged =
        ReportFiles.writeAndMerge(
            directory, "report-new.json", report(null, "New#x", 1, false), null);

    assertThat(merged.reports()).isEqualTo(2);
    assertThat(Files.exists(directory.resolve("report-old.json"))).isTrue();
  }

  @Test
  void namesAReportItCannotReadInsteadOfFailing() throws Exception {
    Files.writeString(directory.resolve("report-broken.json"), "{\"policies\": [");

    ReportFiles.writeAndMerge(directory, "report-a.json", report(RUN, "A#one", 1, false), RUN);

    assertThat(merged().get("unreadableReports")).isEqualTo(List.of("report-broken.json"));
    assertThat(findings(onlyGroup(merged()))).hasSize(1);
  }

  @Test
  void writesToTheConfiguredDirectoryOrTheBuildToolsOutputDirectory() throws Exception {
    Path maven = Files.createDirectories(directory.resolve("maven"));
    Files.writeString(maven.resolve("pom.xml"), "<project/>");
    Path gradle = Files.createDirectories(directory.resolve("gradle"));
    Files.writeString(gradle.resolve("build.gradle.kts"), "");
    Path both = Files.createDirectories(directory.resolve("both"));
    Files.writeString(both.resolve("pom.xml"), "<project/>");
    Files.writeString(both.resolve("build.gradle"), "");
    Path neither = Files.createDirectories(directory.resolve("neither"));

    assertThat(ReportFiles.defaultDirectory(" out/qf ", maven)).isEqualTo(Path.of("out/qf"));
    assertThat(ReportFiles.defaultDirectory(null, maven)).isEqualTo(Path.of("target/queryfence"));
    assertThat(ReportFiles.defaultDirectory("", gradle)).isEqualTo(Path.of("build/queryfence"));
    assertThat(ReportFiles.defaultDirectory(null, both)).isEqualTo(Path.of("target/queryfence"));
    assertThat(ReportFiles.defaultDirectory(null, neither)).isEqualTo(Path.of("target/queryfence"));
  }

  @Test
  void namesItsOwnReportAfterTheJvm() {
    assertThat(ReportFiles.ownFileName())
        .matches("report-\\d{8}T\\d{6}-" + ProcessHandle.current().pid() + "\\.json");
  }

  @Test
  void readsBackWhatTheWriterWrites() {
    Json json = new Json();
    json.object()
        .field("text", "quote \" backslash \\ newline \n tab \t control \u0001")
        .field("number", 42)
        .field("flag", true)
        .field("nothing", null)
        .key("list")
        .array()
        .value("a")
        .end()
        .end();

    Object parsed = JsonReader.parse(json.toString());

    Map<String, Object> expected =
        new HashMap<>(
            Map.of(
                "text",
                "quote \" backslash \\ newline \n tab \t control \u0001",
                "number",
                42L,
                "flag",
                true,
                "list",
                List.of("a")));
    expected.put("nothing", null);
    assertThat(parsed).isEqualTo(expected);
    Json again = new Json();
    again.any(parsed);
    assertThat(JsonReader.parse(again.toString())).isEqualTo(parsed);
  }

  // ------------------------------------------------------------------------------------ helpers

  /** A per-JVM report as RunReport writes it, with one finding. */
  private static String report(
      String run, String test, int statements, boolean disabled, String... unmatchedOrigins) {
    Json json = new Json();
    json.object();
    json.field("generatedAt", "2026-10-09T09:00:00Z");
    json.field("run", run);
    json.field("disabled", disabled);
    json.key("disabledReasons").array();
    if (disabled) {
      json.value("queryfence.enabled=false");
    }
    json.end();
    json.key("policies").array().object();
    json.field("policy", "queryfence.yml");
    json.field("mode", "REPORT");
    json.field("onUnparseable", "FAIL");
    json.key("summary").object();
    json.field("tests", 1).field("statements", statements).field("findings", 1);
    json.end();
    json.key("findings").array().object();
    json.field("test", test).field("code", "MISSING_PREDICATE");
    json.end().end();
    json.key("unmatchedSuppressions").array();
    for (String origin : unmatchedOrigins) {
      json.object()
          .field("rule", "tenant-isolation")
          .field("origin", origin)
          .field("reason", "legacy")
          .end();
    }
    json.end();
    json.end().end();
    json.end();
    return json.toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> merged() throws Exception {
    return (Map<String, Object>)
        JsonReader.parse(Files.readString(directory.resolve(ReportFiles.MERGED)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyGroup(Map<String, Object> merged) {
    List<Object> groups = (List<Object>) merged.get("policies");
    assertThat(groups).hasSize(1);
    return (Map<String, Object>) groups.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> findings(Map<String, Object> group) {
    return (List<Map<String, Object>>) group.get("findings");
  }
}
