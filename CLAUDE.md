# CLAUDE.md — QueryFence

Context for AI coding agents working in this repository. Read fully before making changes.

## What this project is

**QueryFence** — SQL policy testing for the JVM ("ArchUnit for SQL").

Users declare rules once (e.g. every query touching `purchase_order` must filter by `tenant_id`).
During integration tests, QueryFence captures every SQL statement actually sent to the database
(from JPA/Hibernate, MyBatis, jOOQ, JdbcTemplate, native queries, AI-written code), parses it,
checks it against the policy, and fails the build on violations — pointing to the exact
class, method and line that produced the SQL.

Primary problem: multi-tenant data leaks caused by a missing `WHERE tenant_id = ?`.

It **verifies** isolation. It does **not** replace runtime enforcement (Postgres RLS,
Hibernate `@TenantId`/filters, MyBatis-Plus tenant interceptor). It complements them.

## Brand and coordinates

- Umbrella brand: **Steelreed** (a reed bends in the storm and does not break; tagline:
  *Backend tools that bend, not break.*). Formerly Trestack, renamed before 0.1.0.
- GitHub: `github.com/steelreed/queryfence`
- Maven groupId: `com.steelreed`, a namespace verified on Central through the `steelreed.com` domain
- Java packages: `com.steelreed.queryfence.*`
- License: Apache-2.0
- Palette: Navy `#0B1D33`, Steel `#2B72C2`, Reed `#4FC3F7`, Mist `#F2F7FC`

## Modules

| Module | Purpose | Allowed dependencies |
|---|---|---|
| `queryfence-core` | Parser adapter, rule engine, policy model | **JSqlParser only** |
| `queryfence-jdbc` | Capture SQL via datasource-proxy, resolve origin via `StackWalker` | core, datasource-proxy |
| `queryfence-report` | Collects findings per policy, console summary, `report.json` | jdbc |
| `queryfence-junit5` | JUnit 5 extension, YAML policy loading | report, junit-jupiter-api (`provided`), snakeyaml |
| `queryfence-spring-test` | Auto-wrap every `DataSource` bean in Spring test contexts; primary entry point for Spring Boot users | junit5, report, spring-test + spring-context + spring-aop + junit-jupiter-api (`provided`) |
| `queryfence-bom` | Version alignment; lists every published module | — |
| `queryfence-integration-tests` | Testcontainers matrix: {Hibernate, MyBatis, JdbcTemplate} × {MySQL, Postgres}; **not published** | everything, test scope |

`examples/` holds two standalone Spring Boot projects (MySQL + MyBatis, Postgres + JPA) that run
with `docker compose up`; they are not part of the reactor.

`dogfood/` holds Northwind Shop, a realistic multi-tenant application (60+ queries across JPA,
MyBatis and JdbcTemplate, 62 Testcontainers tests) that QueryFence is measured against in REPORT
mode. It is not in the reactor and is never published, and its deliberately sloppy queries are the
measurement — do not fix them. Results: `dogfood/DOGFOOD-REPORT.md`.

## Architecture rules (do not break)

1. `queryfence-core` must never depend on JDBC, JUnit, Spring or YAML libraries. It also must not
   *model* them: a `Violation` says what is wrong with a statement, never where the statement came
   from. Origins are a capture concern and live in `queryfence-jdbc` (`Origin`, `Finding`), so the
   engine stays reusable outside tests (runtime mode, text-to-SQL validation).
2. **Fail closed.** Anything we cannot prove safe is a violation. Unparseable SQL is a
   violation by default (`onUnparseable: FAIL`), user may downgrade.
3. Suppressions live in the policy (YAML / builder) keyed by `Class#method` and **require a
   reason**. Never add annotations that production code would need to depend on.
4. Public API is small and explicit. Anything not meant for users goes in an `internal`
   package, which the published Javadoc excludes. Prefer interfaces + static factories over
   exposing implementation classes. Every public type and member carries Javadoc: the release
   build must produce zero Javadoc warnings.
5. Parse results are cached by SQL string, and a policy file is read once per JVM.
6. The console summary is printed when the JUnit test plan ends (a platform
   `TestExecutionListener` registered through `META-INF/services`), never only from a JVM shutdown
   hook: Surefire stops relaying the forked JVM's output before the hook runs.

## Rule semantics: `require-predicate`

`docs/DESIGN.md` is the specification (clauses RP-1..RP-14, UW-*, DW-*); the golden corpus is its
executable form. Summary:

A statement passes when **every occurrence** of a protected table — in FROM, JOIN, subqueries
(WHERE/FROM/SELECT list), CTEs, each UNION branch, UPDATE/DELETE targets (including joined forms),
and INSERT...SELECT sources — is *fenced* by the conditions of its own query block:

- **Value predicate:** `<alias>.<column> = <parameter | literal | allowed function>` or
  `<alias>.<column> IN (<values>)`. Functions are rejected unless listed in the rule's
  `allowedFunctions` (e.g. `current_setting`).
- **Transitive:** `x.tenant_id = y.tenant_id` fences one side when the other is already fenced;
  a chain needs at least one value-predicate anchor. Correlated subqueries may anchor on a fenced
  occurrence of an enclosing block.
- **AND/OR:** an AND fences what any conjunct fences (with propagation); an **OR fences X only if
  every branch fences X**, recursively (`tenant_id = ? OR 1=1` is a bypass).
- **Alias binding:** the predicate must be bound to **that occurrence's alias**. An unqualified
  column only binds in a single-table block; otherwise one `AMBIGUOUS_COLUMN` violation per block.
- **Placement:** WHERE, any INNER JOIN's ON, or the ON of the LEFT JOIN that introduces the table.
  Not: preserved side of an outer join's ON, RIGHT/FULL JOIN ON, HAVING.
- **Derived tables / CTEs:** filters outside do not fence tables inside (no pushdown in v0.1) —
  a known false-positive source, measured in Phase 5.
- **Primary key only:** an unfenced occurrence whose only value predicate is on the rule's
  `primaryKey` (default `id`) is `PRIMARY_KEY_LOOKUP`, not `MISSING_PREDICATE`: the fix is
  `findByIdAndTenantId` or `@TenantId`, not a WHERE clause.

INSERT: the column must be present in the column list. DDL and TRUNCATE are ignored; MERGE on a
protected table is `UNSUPPORTED_STATEMENT`. An unparseable DML statement is a violation with the
rule id `parser`, one per protected table it mentions (found by scanning the raw SQL), and it is
governed by `onUnparseable`, not by `mode` — the decision is per finding, through
`Policy.modeFor(code)`.
Identifiers compare case-insensitively; handle quoted identifiers (MySQL backticks,
Postgres double quotes) and schema-qualified names (`app.purchase_order`).
Every violation message states the problem **and the fix**, from fixed templates in DESIGN.md.

Capture window: the test method body and everything it calls; `@BeforeEach`/`@AfterEach`,
context startup and migrations are not checked.

Checking parameter *values* against the current tenant is out of scope for v0.1.

## v0.1 scope

In: `require-predicate`, `update-without-where`, `delete-without-where`, JUnit 5 extension,
`queryfence-spring-test` (zero-code setup for Spring Boot: dependency + `queryfence.yml`),
modes FAIL and REPORT, YAML + Java builder policy, suppressions with reason, console report,
`target/queryfence/report.json`, examples on MySQL and Postgres.

Out (do not build yet): runtime blocking, Spring Boot starter for production use, baseline file, HTML report,
parameter value checks, custom rule DSL, UI.

## Testing approach

- **Golden corpus** is the heart of the project: `queryfence-core/src/test/resources/corpus`,
  data files of SQL + expected violations (including the exact message), loaded by
  `GoldenCorpusTest`. Each case declares its `clause` (a DESIGN.md id), `policy` and `dialect`
  (ansi/mysql/postgres). `CorpusStructureTest` checks ids, clauses, dialects and policies.
- Every rule change or bug fix adds corpus cases first (test-first). Prefer a violating case for
  every structure that only has a passing one.
- **Metamorphic test**: `MetamorphicTest` weakens every passing case five ways on the parsed
  statement (never on the SQL text) — remove the predicate, wrap it in `OR 1 = 1`, rebind it to
  another table, move it into a LEFT JOIN's ON, compare with a column — and requires a violation
  each time. A passing case that survives one is an engine hole.
- PIT mutation testing on the rule engine, threshold 88%: `./mvnw -pl queryfence-core -Pmutation
  verify` (CI job "Mutation testing (JDK 21)").
- Integration tests via Testcontainers: {Hibernate, MyBatis, JdbcTemplate} × {MySQL, Postgres},
  each with a deliberately leaky query that must be caught **and** a correct query that must not be
  reported. They need Docker and only run with `-Pintegration` (CI job "Integration tests"), so the
  everyday `./mvnw verify` stays fast and Docker-free.
- Application code in those tests lives in `com.acme.*`: QueryFence skips its own packages when it
  resolves an origin, so fixtures in `com.steelreed.*` would resolve to the test framework instead.

## Conventions

- Java 17 baseline; CI runs 17, 21, 25.
- Format: `./mvnw spotless:apply` (google-java-format, license header required).
- Before every commit: `./mvnw verify` must pass.
- Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`.
- Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.
- Keep versions in the parent `pom.xml` `<properties>`.
- **Do not upgrade `junit-bom` to 6.x before the platform decision is made** (see
  `docs/decisions/junit-platform.md`). `junit-jupiter-api` is `provided` in the published modules,
  and CI runs them against the oldest and the newest supported JUnit. The JUnit platform QueryFence
  builds on is part of the public contract of `queryfence-junit5`, so that decision belongs to
  Phase 3, where the extension is designed. Close or hold Dependabot PRs that propose it.

### Workflow

- `main` is protected by a ruleset: changes land only through pull requests, and the
  `Build (JDK 17)`, `Build (JDK 21)` and `Build (JDK 25)` checks must pass on a branch that is
  up to date with `main`. Force-push and branch deletion are blocked.
- Never commit or push directly to `main`. Work on a branch named by change type:
  `feat/...`, `fix/...`, `docs/...`, `test/...`, `chore/...`.
- Open a pull request whose description states **what** changed and **why**, plus anything the
  maintainer should review closely (decisions taken, open questions).
- Wait for CI to be green on the PR. **Never merge**, enable auto-merge or approve on the
  maintainer's behalf: the maintainer reads and merges every PR.
- No AI attribution trailers (`Co-Authored-By: Claude`, "Generated with Claude Code") in commits
  or PR descriptions.

## Docs and adoption

- Documentation site: MkDocs Material, `mkdocs.yml` + `docs/`, deployed to
  `docs.steelreed.com` by `.github/workflows/docs.yml` on every push to `main`.
  `docs/llms.txt` is the summary for AI agents and must stay in sync with the rules.
- `docs/ADOPTION.md` is the REPORT-mode path into an existing project;
  `tools/queryfence-summary.py` summarises `report.json` (no dependencies).
- `examples/` must keep failing: `examples/verify-examples.sh` asserts each example's build fails
  *because of QueryFence*, and CI runs it in the integration job.

## Security — never do these

- Never read, print, create or commit GPG keys, passphrases, Central Portal tokens or any
  secret. Release credentials live only in GitHub Secrets.
- Never run `-Prelease deploy`, push tags, or trigger a release. Releasing is done by the
  maintainer manually.
- Rule-bypass reports are security issues: see `SECURITY.md`.

## Current status and plan

Phase 0 to Phase 6 are done: design, 260 golden cases, rule engine, metamorphic tests, PIT, SQL
capture with origin resolution, the JUnit and Spring modules, the Testcontainers matrix, the
examples, the documentation site and the Phase 5 dogfood measurement (`dogfood/`, 20 findings on 75
statements, 4 false positives, and a list of what 0.1.0 must fix first — see
`dogfood/DOGFOOD-REPORT.md`). Phase 7 (current): release 0.1.0 — see `docs/RELEASE.md`.
Releasing is the maintainer's job; agents never deploy, tag or touch GPG and Central credentials.

1. Phase 0 — design on paper (README, DESIGN.md, 30 cases)
2. Phase 1 — core + golden corpus (≥150 cases) + PIT
3. Phase 2 — jdbc capture + origin detection
4. Phase 3 — JUnit 5 extension + Spring test support + reports
5. Phase 4 — Testcontainers matrix + examples
6. Phase 5 — dogfood in real projects in REPORT mode (target <5% false positives)
7. Phase 6 — docs site (MkDocs Material, `docs.steelreed.com`, include `llms.txt`)
8. Phase 7 — release 0.1.0 (tag `v0.1.0`; workflow uploads with autoPublish=false)
9. Phase 8 — launch post and community

Competitive landscape to stay aware of: TenantLayer (Postgres RLS, Hibernate only),
MyBatis-Plus `TenantLineInnerInterceptor`, Hibernate `@TenantId`, Python `sql-guardrail`.
