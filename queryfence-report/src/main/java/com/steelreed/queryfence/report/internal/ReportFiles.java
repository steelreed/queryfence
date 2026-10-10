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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where the report files live, and how the reports of several test JVMs become one.
 *
 * <p>A build can run tests in several JVMs: Surefire with {@code forkCount > 1} or {@code
 * reuseForks=false}, Gradle with {@code maxParallelForks > 1} or {@code forkEvery}. Each JVM only
 * knows its own results, so each one writes its own {@code report-<start>-<pid>.json} and never
 * touches another JVM's file. Then, holding a lock on the directory, it merges the files of the
 * current run into {@code report.json}. Whichever JVM finishes last writes the complete merge, and
 * no finding is lost whatever the order.
 *
 * <p>The current run is identified by the process that launched the test JVM: the nearest {@code
 * java} ancestor (Maven, through the shell Surefire forks with; the Gradle daemon; ...). A file of
 * another run is left out of the merge, and deleted once the process that launched it has ended, so
 * a rerun without {@code clean} does not show yesterday's findings. When the launcher cannot be
 * identified, nothing is ever deleted and every file of the directory is merged.
 */
final class ReportFiles {

  /** System property naming the directory the report files are written to. */
  static final String DIRECTORY_PROPERTY = "queryfence.reportDir";

  /** The merged report, the one people and tools read. */
  static final String MERGED = "report.json";

  private static final String PREFIX = "report-";
  private static final String LOCK = ".report.lock";
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

  /** What a merge produced. */
  record Merged(int reports, int findings) {}

  private ReportFiles() {}

  /**
   * The directory reports go to: {@value #DIRECTORY_PROPERTY} when set, else {@code
   * build/queryfence} in a Gradle project and {@code target/queryfence} otherwise. Both build tools
   * run tests from the module's own directory.
   */
  static Path defaultDirectory() {
    return defaultDirectory(System.getProperty(DIRECTORY_PROPERTY), Path.of(""));
  }

  static Path defaultDirectory(String configured, Path workingDirectory) {
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured.strip());
    }
    boolean maven = Files.exists(workingDirectory.resolve("pom.xml"));
    boolean gradle =
        Files.exists(workingDirectory.resolve("build.gradle"))
            || Files.exists(workingDirectory.resolve("build.gradle.kts"));
    return Path.of(!maven && gradle ? "build" : "target", "queryfence");
  }

  /** The file name of this JVM's own report: start time and pid, unique across forks. */
  static String ownFileName() {
    long started = ManagementFactory.getRuntimeMXBean().getStartTime();
    return PREFIX
        + STAMP.format(Instant.ofEpochMilli(started))
        + "-"
        + ProcessHandle.current().pid()
        + ".json";
  }

  /** When this JVM started, for the report. */
  static String jvmStartedAt() {
    return Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
  }

  /**
   * The run this JVM belongs to: the nearest {@code java} ancestor process, as {@code
   * pid@startInstant}, or {@code null} when it cannot be identified.
   */
  static String currentRun() {
    Optional<ProcessHandle> ancestor = ProcessHandle.current().parent();
    // Surefire forks through a shell, so the launcher can be the grandparent.
    for (int depth = 0; depth < 3 && ancestor.isPresent(); depth++) {
      ProcessHandle process = ancestor.get();
      Optional<String> command = process.info().command();
      Optional<Instant> started = process.info().startInstant();
      if (command.isPresent() && started.isPresent() && isJava(command.get())) {
        return process.pid() + "@" + started.get();
      }
      ancestor = process.parent();
    }
    return null;
  }

  private static boolean isJava(String command) {
    String name = Path.of(command).getFileName().toString();
    return name.equals("java") || name.equals("java.exe");
  }

  /** Whether the launcher of a run is still running; unknown runs are assumed alive. */
  static boolean isAlive(String run) {
    if (run == null) {
      return true;
    }
    int at = run.indexOf('@');
    if (at < 0) {
      return true;
    }
    try {
      long pid = Long.parseLong(run.substring(0, at));
      String started = run.substring(at + 1);
      return ProcessHandle.of(pid)
          .flatMap(process -> process.info().startInstant())
          .map(instant -> instant.toString().equals(started))
          .orElse(false);
    } catch (NumberFormatException e) {
      return true;
    }
  }

  /**
   * Writes this JVM's report, then merges every report of the run into {@code report.json}.
   *
   * @param directory where the reports live
   * @param ownName the file name of this JVM's report
   * @param ownJson this JVM's report
   * @param run the run this JVM belongs to, or {@code null} when unknown
   */
  static Merged writeAndMerge(Path directory, String ownName, String ownJson, String run)
      throws IOException {
    Files.createDirectories(directory);
    writeAtomically(directory.resolve(ownName), ownJson);
    try (FileChannel channel =
            FileChannel.open(
                directory.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      Map<String, Map<String, Object>> reports = new LinkedHashMap<>();
      List<String> unreadable = new ArrayList<>();
      for (Path file : reportFiles(directory)) {
        String name = file.getFileName().toString();
        Map<String, Object> report;
        try {
          report = read(file);
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
          unreadable.add(name);
          continue;
        }
        String reportRun = (String) report.get("run");
        if (Objects.equals(reportRun, run) || run == null) {
          reports.put(name, report);
        } else if (reportRun != null && !isAlive(reportRun)) {
          Files.deleteIfExists(file); // a finished run; its launcher has ended
        }
      }
      String merged = merge(reports, unreadable, run);
      writeAtomically(directory.resolve(MERGED), merged);
      return new Merged(reports.size(), countFindings(reports.values()));
    }
  }

  private static List<Path> reportFiles(Path directory) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, PREFIX + "*.json")) {
      stream.forEach(files::add);
    }
    files.sort(null);
    return files;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(Path file) throws IOException {
    return (Map<String, Object>) JsonReader.parse(Files.readString(file, StandardCharsets.UTF_8));
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path temporary =
        Files.createTempFile(target.getParent(), "." + target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Merges per-JVM reports into one document with the same shape. Policy groups are matched on
   * policy and mode; tests, statements and findings add up; a suppression is unmatched only when no
   * JVM matched it; QueryFence counts as disabled when any JVM had it disabled.
   */
  @SuppressWarnings("unchecked")
  static String merge(
      Map<String, Map<String, Object>> reports, List<String> unreadable, String run) {
    boolean disabled = false;
    Set<Object> disabledReasons = new LinkedHashSet<>();
    Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    for (Map<String, Object> report : reports.values()) {
      disabled |= Boolean.TRUE.equals(report.get("disabled"));
      disabledReasons.addAll(list(report.get("disabledReasons")));
      for (Object item : list(report.get("policies"))) {
        Map<String, Object> group = (Map<String, Object>) item;
        String key = group.get("policy") + "/" + group.get("mode");
        Map<String, Object> merged = groups.get(key);
        if (merged == null) {
          merged = new LinkedHashMap<>();
          merged.put("policy", group.get("policy"));
          merged.put("mode", group.get("mode"));
          merged.put("onUnparseable", group.get("onUnparseable"));
          merged.put("tests", 0L);
          merged.put("statements", 0L);
          merged.put("findings", new ArrayList<>());
          merged.put(
              "unmatchedSuppressions", new ArrayList<>(list(group.get("unmatchedSuppressions"))));
          groups.put(key, merged);
        } else {
          List<Object> stillUnmatched = list(group.get("unmatchedSuppressions"));
          ((List<Object>) merged.get("unmatchedSuppressions"))
              .removeIf(
                  suppression ->
                      stillUnmatched.stream()
                          .noneMatch(other -> sameSuppression(suppression, other)));
        }
        Map<String, Object> summary = (Map<String, Object>) group.get("summary");
        merged.put("tests", (Long) merged.get("tests") + number(summary, "tests"));
        merged.put("statements", (Long) merged.get("statements") + number(summary, "statements"));
        ((List<Object>) merged.get("findings")).addAll(list(group.get("findings")));
      }
    }

    Json json = new Json();
    json.object();
    json.field("generatedAt", Instant.now().toString());
    json.field("run", run);
    json.key("reports").any(new ArrayList<>(reports.keySet()));
    json.key("unreadableReports").any(unreadable);
    json.field("disabled", disabled);
    json.key("disabledReasons").any(new ArrayList<>(disabledReasons));
    json.key("policies").array();
    for (Map<String, Object> group : groups.values()) {
      List<Object> findings = (List<Object>) group.get("findings");
      json.object();
      json.key("policy").any(group.get("policy"));
      json.key("mode").any(group.get("mode"));
      json.key("onUnparseable").any(group.get("onUnparseable"));
      json.key("summary").object();
      json.key("tests").any(group.get("tests"));
      json.key("statements").any(group.get("statements"));
      json.field("findings", findings.size());
      json.end();
      json.key("findings").any(findings);
      json.key("unmatchedSuppressions").any(group.get("unmatchedSuppressions"));
      json.end();
    }
    json.end();
    json.end();
    return json.toString();
  }

  @SuppressWarnings("unchecked")
  private static boolean sameSuppression(Object one, Object other) {
    Map<String, Object> a = (Map<String, Object>) one;
    Map<String, Object> b = (Map<String, Object>) other;
    return Objects.equals(a.get("rule"), b.get("rule"))
        && Objects.equals(a.get("origin"), b.get("origin"));
  }

  @SuppressWarnings("unchecked")
  private static int countFindings(Iterable<Map<String, Object>> reports) {
    int count = 0;
    for (Map<String, Object> report : reports) {
      for (Object group : list(report.get("policies"))) {
        count += list(((Map<String, Object>) group).get("findings")).size();
      }
    }
    return count;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value instanceof List<?> list ? (List<Object>) list : List.of();
  }

  private static long number(Map<String, Object> map, String key) {
    return map != null && map.get(key) instanceof Number number ? number.longValue() : 0L;
  }
}
