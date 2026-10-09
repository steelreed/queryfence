# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-09-24

First release. QueryFence checks the SQL your integration tests actually send to the database
against a policy you declare once, and fails the build on the statements that break it, naming the
class, method and line that produced the query.

### Added

**Rule engine (`queryfence-core`)**

- `require-predicate`: every occurrence of a protected table must be filtered by the tenant column —
  in `FROM`, joins, subqueries, CTEs, each `UNION` branch, `UPDATE`/`DELETE` targets and
  `INSERT ... SELECT` sources. Value predicates, `IN` lists, casts and functions named in
  `allowedFunctions` count; transitive tenant-column equalities count when the chain has a value
  anchor; an `OR` counts only when every branch fences the table.
- `update-without-where` and `delete-without-where`: no WHERE clause, or one that is always true.
- Violation codes `MISSING_PREDICATE`, `PRIMARY_KEY_LOOKUP`, `AMBIGUOUS_COLUMN`,
  `MISSING_INSERT_COLUMN`, `NO_WHERE`, `TAUTOLOGICAL_WHERE`, `UNSUPPORTED_STATEMENT` and
  `UNPARSEABLE`. Every message states the problem **and** the fix.
- Fail closed: SQL that does not parse, and statement types not analysed yet, are violations.
  Statements that can neither read nor write rows (`SET`, `SHOW`, `FLUSH`, DDL, `CALL`) are ignored
  when they do not parse, because test fixtures run them all the time.
- `onUnparseable` governs `UNPARSEABLE` findings and nothing else, independently of `mode`:
  `mode: FAIL` with `onUnparseable: REPORT` fails the build on a leak while only recording the
  statements the parser could not read. The decision is per finding (`Policy.modeFor(code)`).
- An `UNPARSEABLE` finding names the protected tables the statement mentions — one finding per
  table, matched on whole identifiers, ignoring comments and string literals — so a parser failure
  cannot hide a table from the report. These findings carry the rule id `parser` and are suppressed
  with `rule: parser`.
- Public API: `Policy` (with a builder), `Rule`, `Mode`, `Suppression`, `SqlChecker`, `Violation`.
  The engine depends on JSqlParser only — no JDBC, no JUnit, no Spring, and it does not model where
  a statement came from.

**Capture (`queryfence-jdbc`)**

- `QueryFence.wrap(DataSource, Policy)` records every statement the driver executes, including each
  statement of a JDBC batch and statements that failed while executing, and passes the SQL on
  unchanged.
- Fenced data sources can be layered: when one wraps another (directly or through a proxy or a
  routing data source), each execution is recorded once, by the outermost fenced data source it
  passes through, so findings and statement counts are not multiplied by the number of layers.
- The origin — class, method, file, line — is resolved with `StackWalker`, skipping the JDK,
  drivers, ORMs, frameworks and QueryFence itself. `CaptureSettings.ofBasePackages("com.acme")`, or
  `basePackages:` in the policy file, makes it exact. A lambda is reported as the method that
  contains it rather than under its synthetic `lambda$...$0` name.

**Reports (`queryfence-report`)**

- A console summary and `target/queryfence/report.json`, grouped per policy, each group with its own
  mode. Findings carry the rule, the code, the table, the message, the SQL and the origin.
- Forked test JVMs lose nothing: each JVM writes its own `report-<start>-<pid>.json`, and merges the
  reports of the current run into `report.json` under a file lock, so Surefire `forkCount > 1`,
  `reuseForks=false`, Gradle `maxParallelForks > 1` and `forkEvery` all end with every finding in
  `report.json`. Reports of an earlier Maven build are dropped; under Gradle, clear the directory
  before the tests (see `docs/configuration.md`).
- The report directory is `target/queryfence` under Maven, `build/queryfence` in a Gradle module,
  or the `queryfence.reportDir` system property.
- The summary is printed when the test plan ends, through a JUnit Platform `TestExecutionListener`,
  so it reaches the build log under Maven Surefire and Gradle instead of a stream that a JVM
  shutdown hook writes to after the runner has stopped listening.
- Suppressions that matched nothing during the run are listed in the summary and in the report under
  `unmatchedSuppressions`: that is how an exception whose code has moved shows up.
- `tools/queryfence-summary.py` summarises a report by rule, table, code and origin, with a
  `--triage` listing for adoption. It reads a `report.json` or a report directory (merging the
  per-JVM reports itself when `report.json` is missing), several modules at once. No dependencies.

**JUnit 5 (`queryfence-junit5`)**

- `QueryFenceExtension.fromClasspath()` loads `queryfence.yml`; `wrap(DataSource)` fences a data
  source. Only statements of the test method and the code it calls are checked, on any thread.
- `FAIL` mode fails the test with the rule, the fix, the SQL and the origin line; `REPORT` mode only
  collects.
- The policy loader rejects unknown keys, unknown rule types and modes, a wrong version and
  suppressions without a reason, naming the resource and the offending key — including the errors
  raised by the policy model itself, such as an origin that is not `Class#method`. A policy file is
  read once per JVM, so a mistake is diagnosed once instead of once per test class.
- `basePackages:` in the policy file names the packages origins are resolved in, which
  `queryfence-spring-test` reads as well; nothing else needs to change.
- Built against JUnit 5.10.5, the floor we support; `junit-jupiter-api` is `provided`, so your build
  chooses the version. CI runs 5.10, 5.13 and 6.1.

**Spring (`queryfence-spring-test`)**

- Every `DataSource` bean of a Spring test context is wrapped automatically: the dependency plus a
  `queryfence.yml` is the whole setup, with no test code to change.
- A wrapped bean keeps its class: it is replaced by a class-based proxy of its own type, so
  `@Autowired HikariDataSource` (injection by the concrete pool type) keeps working. A bean whose
  class cannot be subclassed (a final class, or a final `getConnection`) is still wrapped, but can
  then only be injected as `DataSource`.
- Layered data source beans — a `LazyConnectionDataSourceProxy`, `TransactionAwareDataSourceProxy`
  or `AbstractRoutingDataSource` over a pool bean — are all wrapped, and each statement is still
  recorded once per execution.
- `@QueryFencePolicy("other.yml")` selects another policy for a test class, and takes part in the
  Spring context cache key.

**Safety rails**

- Suppressions live in the policy, keyed by `Class#method`, and a blank reason is a configuration
  error. Production code never depends on QueryFence.
- `queryfence.enabled=false` switches the checks off, prints a loud warning, records
  `"disabled": true` with the reason in the report, and fails the build when the `CI` environment
  variable is set unless `queryfence.allowDisabledInCi=true` says so deliberately.

**Testing**

- 205 golden corpus cases (SQL, policy, expected violations with their exact messages), a
  metamorphic suite that weakens every passing case five ways and requires a violation, and PIT
  mutation testing at 88% threshold.
- Testcontainers matrix {Hibernate, MyBatis, JdbcTemplate} × {MySQL 8.4, Postgres 17} on
  framework-generated SQL.

### Known limitations

These are documented in [docs/DESIGN.md](https://github.com/steelreed/queryfence/blob/main/docs/DESIGN.md) and measured against real frameworks in
`queryfence-integration-tests`.

- **ORM associations.** A fetch join or a lazy association loads child rows by foreign key only
  (`... from order_item where order_id = ?`). The rows belong to a parent your code fenced, but the
  statement does not say so, so QueryFence reports them. Map the tenant on the child entity with
  Hibernate `@TenantId` — then the SQL carries it and QueryFence goes quiet — or protect only the
  aggregate root and accept that the child table is no longer checked.
- **Primary-key lookups.** `findById` and `EntityManager.find` are reported as
  `PRIMARY_KEY_LOOKUP`. That is deliberate: an id is guessable, so it is not tenant isolation. Use
  `findByIdAndTenantId`, or map the tenant on the entity.
- **Derived tables and CTEs.** A filter applied outside a derived table or a CTE does not fence the
  tables inside it: v0.1 does not push predicates down. Move the filter inside the subquery.
- **Statements that do not parse.** What JSqlParser 5.4 cannot parse, QueryFence cannot clear, so a
  DML statement that fails to parse is a violation even if it is correct. One shape is known:
  **an unqualified column named `number`** (`SELECT number FROM invoice`) does not parse, while
  `i.number` and `"number"` do. Qualify or quote the column, or suppress the origin with
  `rule: parser`; reported upstream in
  [docs/upstream-issues](https://github.com/steelreed/queryfence/tree/main/docs/upstream-issues).
  The finding names the protected tables the statement mentions, so the blind spot is visible.
- **Only what your tests run.** Untested code paths are unchecked, parameter *values* are not
  checked, views and stored procedures are opaque, and JUnit parallel execution is unsupported.

### API stability

0.1.0 is the first release: the API is small on purpose, but it is **not frozen**. Anything in a
`*.internal` package carries no promise at all and may change in any release. Breaking changes to
the public API will be listed here, and the API freezes at 1.0.

[Unreleased]: https://github.com/steelreed/queryfence/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/steelreed/queryfence/releases/tag/v0.1.0
