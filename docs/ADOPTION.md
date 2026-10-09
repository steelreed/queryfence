# Adopting QueryFence in an existing project

This is the path for a Spring Boot application that already exists and already has tests. The goal
of the first week is **not** a green build: it is an honest list of what your queries do today.
So you start in `REPORT` mode, read the report, and only then decide what to fix.

Budget: about an hour for steps 1 to 3, then as long as your triage list deserves.

## 1. Add the dependency

```xml
<dependency>
  <groupId>com.steelreed</groupId>
  <artifactId>queryfence-spring-test</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Nothing else changes: no annotation on your tests, no change to production code. Without a policy
file on the test classpath, the dependency does nothing at all.

## 2. Write the first policy

`src/test/resources/queryfence.yml`:

```yaml
version: 1
mode: REPORT          # start here. FAIL comes later, when the list is empty.
onUnparseable: REPORT # also start here: see what does not parse before it fails anyone

rules:
  - id: tenant-isolation
    type: require-predicate
    column: tenant_id
    tables:
      - purchase_order
      - order_item
      - invoice
```

Listing the tables is the only real work. Get the list from the schema, not from memory:

```sql
-- Postgres
SELECT table_name FROM information_schema.columns
WHERE column_name = 'tenant_id' AND table_schema = 'public' ORDER BY table_name;

-- MySQL
SELECT table_name FROM information_schema.columns
WHERE column_name = 'tenant_id' AND table_schema = DATABASE() ORDER BY table_name;
```

**Read the list before you paste it.** Two kinds of table come back from that query and only one of
them belongs in the policy on day one:

- tables whose rows always belong to one tenant — those are the ones you protect;
- tables whose `tenant_id` is *nullable*, typically an `audit_log` or an `outbox` that platform jobs
  also write. QueryFence will report those writes, correctly and unhelpfully: the job really does
  not set a tenant. Either leave the table out until you have looked at every writer, or keep it in
  and suppress the platform job by name.

If your tenant column has another name, say so in `column`. If rows are looked up by something
other than `id`, set `primaryKey` on the rule.

Leave `update-without-where` and `delete-without-where` out for now. Add them once the tenant rule
is under control; they usually find a handful of fixture-cleanup statements and nothing else.

### Optional: make the origins exact

QueryFence resolves the origin of a statement by walking the stack and taking the first frame that
is not the JDK, a driver, an ORM, a framework or QueryFence itself. In most applications that is
already your code. If your stack has layers QueryFence does not know — a generated client, an
in-house framework — name your own packages and only they are considered:

```yaml
basePackages: [com.acme]
```

Nothing else changes: the same file, the same rules. `@QueryFencePolicy` needs no new attribute,
because it names this file and the file carries the packages.

## 3. Run the tests you already have

```bash
./mvnw verify
```

No test fails because of a finding: `REPORT` mode only collects. One thing does still fail, and it
is worth knowing on the first run — an invalid policy. A file QueryFence cannot load is a
configuration error, so every test that needs it errors out, naming the file and the key that is
wrong. That is the one failure you may see in step 3, and it is a typo, not a leak.

At the end of the run you get a summary per policy on the console and a file:

```
target/queryfence/report.json
```

In a multi-module build there is one report **per module that ran tests**, each under that module's
own `target/`, so use the path of the module you are looking at
(`orders-service/target/queryfence/report.json`) rather than the one at the root.

The summary already tells you the shape of the problem. The file tells you where it is.

## 4. Read the report

The summariser is a single dependency-free script that lives in the QueryFence repository, not in
the published artifacts. Fetch it once into your own repository:

```bash
curl -fsSLO https://raw.githubusercontent.com/steelreed/queryfence/main/tools/queryfence-summary.py
```

Then, from the module whose report you want to read:

```bash
python3 queryfence-summary.py target/queryfence/report.json
python3 queryfence-summary.py target/queryfence/report.json --triage
```

The first command groups the findings by rule, by table, by code and by origin. The second prints
one entry per origin — class, method and line — which is the list you actually work through.

Two numbers matter on day one:

- **statements checked.** If this is small, your test suite does not exercise much SQL, and
  QueryFence can only report on what runs. That is a fact about the test suite, not about
  QueryFence.
- **distinct origins.** Not findings: origins. Twenty findings from one repository method are one
  fix.

## 5. Triage: leak, false positive, or suppression

Go through the triage list and put every origin in one of three buckets.

### Real leak

The query really can return another tenant's rows. These are why you installed QueryFence.
Typical shapes:

| What the report says | What it means |
|---|---|
| `MISSING_PREDICATE` on a table you own | the query forgot the tenant |
| `PRIMARY_KEY_LOOKUP` | `findById` / `EntityManager.find`: an id is guessable |
| `NO_WHERE`, `TAUTOLOGICAL_WHERE` | an update or delete that hits every row |

Fix the query, or fix the mapping. For `PRIMARY_KEY_LOOKUP` on an entity, mapping the tenant with
Hibernate `@TenantId` fixes every load of that entity at once, including lazy ones.

### False positive

The statement is safe, but does not say so. In v0.1 this is almost always one of:

| Shape | Why | What to do |
|---|---|---|
| Child rows loaded by foreign key (`where order_id = ?`) | a lazy association or fetch join | map the tenant on the child entity (`@TenantId`), or protect only the aggregate root |
| A filter applied outside a derived table or CTE | v0.1 does not push predicates down | move the filter inside the subquery |
| A tenant-column equality with no anchor | `i.tenant_id = o.tenant_id` where `o` is not fenced either | fence the parent |
| `UNPARSEABLE` on SQL that is valid | the parser does not know that syntax | open an issue with the statement; meanwhile suppress it with `rule: parser`, or set `onUnparseable: REPORT` |

Count these. If they dominate, fix them structurally (usually: map the tenant on the entities)
rather than one suppression at a time.

### Deliberate exception

Cross-tenant admin screens, platform reports, migrations run from tests. These get a suppression,
and the reason is the point:

```yaml
suppressions:
  - rule: tenant-isolation
    origin: com.acme.admin.PlatformReportJob#nightlyTotals
    reason: Platform-wide revenue report; runs as the operator, never in a tenant request.
```

A suppression without a reason is a configuration error, on purpose. If you cannot write the
sentence, it is not an exception — it is a leak you have not looked at yet.

Statements the parser could not read are suppressed the same way, under the rule id `parser`:

```yaml
suppressions:
  - rule: parser
    origin: com.acme.billing.InvoiceDao#renumber
    reason: Valid Postgres that JSqlParser 5.4 cannot read (column named "number"); filed upstream.
```

An `UNPARSEABLE` finding names the protected tables the statement mentions, so you can see what is
unverified rather than only that something is. And every suppression that matched nothing during the
run is listed in the summary and in `report.json` under `unmatchedSuppressions` — that is how you
notice an exception whose code has moved.

## 6. Turn it on, one table at a time

When the triage list is empty, or only holds documented suppressions:

```yaml
mode: FAIL
onUnparseable: FAIL
```

From then on the build fails on the query that broke the rule, pointing at the line that wrote it.

**If the list is not empty — and on a real codebase it will not be — do not wait.** v0.1 has no
baseline file, so "fix everything, then switch" is the only path that does not work. Adopt table by
table instead: `tables:` is the dial.

```yaml
rules:
  # Under control: the build fails if anyone breaks it again.
  - id: tenant-isolation
    type: require-predicate
    column: tenant_id
    tables: [invoice, payment]
```

```yaml
mode: FAIL
```

Fix the findings of one table, move it into the `FAIL` policy, and keep the rest in a second policy
in `REPORT` mode for the test classes that still need it (`@QueryFencePolicy("queryfence-wip.yml")`)
— or simply leave the other tables out of `tables:` until their turn comes. A protected table that
is enforced today is worth more than six tables reported forever.

`onUnparseable` is a separate dial from `mode`, on purpose: `mode: FAIL` with
`onUnparseable: REPORT` fails the build on a leak while only recording the statements the parser
could not read. That is usually the right setting for the first month.

## 7. Keep it honest

- Do not switch QueryFence off to make a build green. It writes `"disabled": true` into the report
  and fails on CI unless someone sets `queryfence.allowDisabledInCi=true` deliberately.
- Add a test that exercises the query, rather than a suppression, whenever you can: QueryFence only
  sees SQL your tests run.
- Revisit suppressions when the code they name changes. The report lists the ones that matched
  nothing, which usually means the code moved.

## What QueryFence does not do

It verifies; it does not enforce. Keep your runtime protection — Postgres RLS, Hibernate
`@TenantId`, a MyBatis interceptor — and let QueryFence prove, in CI, that the SQL you actually run
carries the filter. *Enforce at runtime, verify in tests.*
