# Configuration

## The policy file

`src/test/resources/queryfence.yml`, or another file named with `@QueryFencePolicy`.

```yaml
version: 1                       # required; only 1 exists
mode: FAIL                       # FAIL | REPORT, default FAIL
onUnparseable: FAIL              # FAIL | REPORT, default FAIL; governs UNPARSEABLE only
basePackages: []                 # optional: resolve origins inside these packages only

rules:
  - id: tenant-isolation         # required, unique
    type: require-predicate      # require-predicate | update-without-where | delete-without-where
    column: tenant_id            # require-predicate only, required
    tables: [purchase_order]     # require-predicate only, required, non-empty
    allowedFunctions: []         # optional: functions accepted as a value, e.g. current_setting
    primaryKey: id               # optional, default id; drives PRIMARY_KEY_LOOKUP

suppressions:
  - rule: tenant-isolation       # a rule id, or `parser` for UNPARSEABLE findings
    origin: com.acme.Foo#bar     # required, Class#method
    reason: why this is safe     # required, non-blank
```

Unknown keys, unknown rule types, a wrong version, a duplicate rule id or a suppression without a
reason are configuration errors: QueryFence refuses to load the policy, names the file and says
which key is wrong. The file is read once per JVM, so the mistake is reported once, not once per
test class.

## Modes

| Setting | Effect |
|---|---|
| `mode: FAIL` | a violation fails the test |
| `mode: REPORT` | violations are collected and printed, nothing fails |
| `onUnparseable: FAIL` | SQL that does not parse is a violation |
| `onUnparseable: REPORT` | it is only reported |

The two settings are independent: `onUnparseable` governs `UNPARSEABLE` findings, `mode` governs
every other code. `mode: FAIL` with `onUnparseable: REPORT` fails the build on a leak while only
recording the statements the parser could not read, which is what a first adoption wants.

Start a new adoption in `REPORT`; see [Adopting in an existing project](ADOPTION.md).

## In Java

```java
Policy policy = Policy.builder()
    .requirePredicate("tenant-isolation", "tenant_id", "purchase_order", "order_item")
    .updateWithoutWhere("no-unbounded-update")
    .suppress("tenant-isolation", "com.acme.admin.ReportJob#nightly", "Platform report")
    .mode(Mode.REPORT)
    .build();

QueryFenceExtension.of(policy);
```

## Origin resolution

By default the origin is the first stack frame outside the JDK, drivers, ORMs, frameworks and
QueryFence. A lambda is reported as the method that contains it, not under its synthetic
`lambda$...$0` name. To make the origin exact, name your packages — in the policy file, which is
what `queryfence-spring-test` reads:

```yaml
basePackages: [com.acme]
```

or in Java, when you build the extension yourself:

```java
QueryFenceExtension.of(policy, CaptureSettings.ofBasePackages("com.acme"));
```

## The report files

At the end of the run QueryFence prints a summary on the console and writes JSON into a report
directory:

| Build | Directory |
|---|---|
| Maven | `target/queryfence/` |
| Gradle (the module has `build.gradle` or `build.gradle.kts` and no `pom.xml`) | `build/queryfence/` |
| anything else | `target/queryfence/` |
| any build, when the `queryfence.reportDir` system property is set | that directory |

Both build tools run tests from the module's own directory, so the default lands in that module's
build output. To choose the directory yourself, set `queryfence.reportDir` as a system property of
the test JVM:

```kotlin
// Gradle
tasks.test { systemProperty("queryfence.reportDir", layout.buildDirectory.dir("qf").get().asFile.path) }
```

```xml
<!-- Maven Surefire / Failsafe -->
<systemPropertyVariables>
  <queryfence.reportDir>${project.build.directory}/qf</queryfence.reportDir>
</systemPropertyVariables>
```

The directory holds:

- `report.json` — **the report to read**: every finding of the run.
- `report-<start>-<pid>.json` — one file per test JVM.

Builds often run tests in several JVMs (Surefire `forkCount > 1` or `reuseForks=false`, Gradle
`maxParallelForks > 1` or `forkEvery`). Each JVM only sees its own tests, so each writes its own
file, and then, holding a lock on the directory, merges the files of the current run into
`report.json`. Whichever JVM finishes last writes the complete merge; no fork overwrites another's
findings. The console summary each JVM prints covers that JVM's tests, and says how many JVMs
`report.json` merges when there are several.

A run is recognised by the process that launched the test JVMs (the Maven JVM, the Gradle daemon).
Files of an earlier Maven build are left out of the merge and deleted, so a rerun without `clean`
starts from a fresh report. The Gradle daemon outlives a build, so under Gradle the files of
earlier builds run by the same daemon are merged too. Clear the directory before the tests run:

```kotlin
tasks.test { doFirst { delete(layout.buildDirectory.dir("queryfence")) } }
```

## The emergency switch

`queryfence.enabled=false` (a Spring property, or `-Dqueryfence.enabled=false` for the JUnit
extension) switches the checks off for a run. QueryFence then prints a loud warning, writes
`"disabled": true` with the reason into the report, and **fails the build when the `CI`
environment variable is set** unless `queryfence.allowDisabledInCi=true` is also set.

It is an emergency exit, not a configuration option. To accept one known query, write a
[suppression](suppressions.md) with a reason.
