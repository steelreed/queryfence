# QueryFence Design

Status: **draft for v0.1**. This document is the specification the rule engine and the golden
corpus are written against. Numbered clauses (`RP-3`, `UW-1`, ...) are referenced from corpus cases.

## Goals

- Verify, in integration tests, that every SQL statement touching a protected table carries the
  required predicate.
- Work with any data-access technology that goes through a `javax.sql.DataSource`.
- Point at the exact class, method and line that produced an offending statement.
- **Fail closed:** anything we cannot prove safe is reported.

## Non-goals (v0.1)

- **Runtime enforcement.** QueryFence never rewrites or blocks SQL. Use Postgres RLS, Hibernate
  `@TenantId`/filters or MyBatis-Plus `TenantLineInnerInterceptor` for that.
- **Parameter value checks.** We prove that `tenant_id = ?` exists, not that `?` is bound to the
  current tenant.
- **SQL that tests never execute.** No static analysis of source code, mapper XML or JPQL.
- **Seeing inside the database.** Views, stored procedures, triggers and `CALL` are opaque.
- **DDL and `TRUNCATE`.** Schema statements are ignored by all rules.
- **Upsert conflict branches.** `ON CONFLICT DO UPDATE` / `ON DUPLICATE KEY UPDATE` are not analysed
  beyond the `INSERT` column list; a protected table read there is reported as not analysed (RP-13).
- **Parallel test execution.** Statements are attributed to the running test by time window;
  JUnit parallel execution is unsupported in v0.1.
- Spring Boot starter for production, baseline file, HTML report, custom rule DSL, UI.

## Terminology

- **Protected table** — a table listed in a `require-predicate` rule's `tables`.
- **Occurrence** — one place a protected table appears as a row source: a `FROM` item, a `JOIN`
  item, an `UPDATE`/`DELETE` target, an `INSERT` target, or a joined table of a multi-table
  `UPDATE`/`DELETE` (`JOIN`, `FROM`, `USING`). A self-join has two occurrences.
- **Reference name** — the alias of an occurrence if it has one, otherwise its unqualified table name.
- **Query block** — one `SELECT ... FROM ... WHERE ...` unit. Each subquery, each CTE body and each
  branch of a set operation (`UNION`, `INTERSECT`, `EXCEPT`) is its own block. `UPDATE` and `DELETE`
  statements are blocks too.
- **Fenced** — an occurrence is fenced when the conditions that apply to it (RP-6) guarantee that
  it only contributes rows of the tenants named by a value (RP-2), directly or through tenant-column
  equalities (RP-3), combined by `AND`/`OR` as defined in RP-4.

## Rule: `require-predicate`

```yaml
- id: tenant-isolation
  type: require-predicate
  column: tenant_id
  tables: [purchase_order, order_item, invoice]
  allowedFunctions: [current_setting]   # optional, default empty
```

A statement passes when **every occurrence** of every protected table is *fenced*. Each unfenced
occurrence is one violation (except RP-5, which reports one violation per query block).

### RP-1 Every occurrence is checked independently

Each occurrence must be fenced by a predicate bound to its own reference name. Protecting one
occurrence never protects another, except through an explicit tenant-column equality (RP-3).

```sql
-- pass
SELECT * FROM purchase_order o WHERE o.tenant_id = ?

-- violation: purchase_order (o) — the only filter is on status
SELECT * FROM purchase_order o WHERE o.status = ?

-- violation: purchase_order (p) — self-join, only o is fenced
SELECT * FROM purchase_order o JOIN purchase_order p ON p.parent_id = o.id WHERE o.tenant_id = ?
```

### RP-2 Value predicates

A **value predicate** on occurrence `X` has one of these forms, where `ref` is `X`'s reference name
and `col` is the rule's `column`:

| Form | Example |
|---|---|
| `ref.col = value` or `value = ref.col` | `o.tenant_id = ?`, `42 = o.tenant_id` |
| `ref.col IN (value, ...)` with at least one value, all values | `o.tenant_id IN (?, ?)` |

`value` is a JDBC parameter (`?`), a named parameter (`:tenant`), a string or numeric literal, a
call to a function listed in the rule's `allowedFunctions` (compared by unqualified name,
case-insensitively, arguments not checked), or a `CAST` of any of those (`CAST(? AS BIGINT)`,
`current_setting('app.tenant_id')::bigint`).

Not value predicates: `<>`, `!=`, `<`, `>`, `LIKE`, `BETWEEN`, `IS NULL`, `IS NOT NULL`,
`NOT IN`, `IN (subquery)`, and comparison with a function that is not in `allowedFunctions`.

```sql
-- pass
SELECT * FROM invoice WHERE invoice.tenant_id IN (?, ?)

-- violation by default; pass with allowedFunctions: [current_setting]
SELECT * FROM purchase_order WHERE tenant_id = current_setting('app.tenant_id')::bigint

-- violation: inequality selects every other tenant
SELECT * FROM purchase_order o WHERE o.tenant_id <> ?
```

### RP-3 Tenant-column equality (transitive fencing)

`X.col = Y.col`, where `X` and `Y` are both occurrences of tables protected by the rule, fences `X`
when `Y` is already fenced, and fences `Y` when `X` is already fenced. Fencing propagates along a
chain of such equalities (`o` anchored, `i.tenant_id = o.tenant_id`,
`v.tenant_id = i.tenant_id`). At least one occurrence of the chain must be anchored by a value
predicate; equalities alone fence nothing.

```sql
-- pass: i is fenced through o, which is anchored by a parameter
SELECT * FROM purchase_order o JOIN order_item i ON i.order_id = o.id AND i.tenant_id = o.tenant_id
WHERE o.tenant_id = ?

-- violation: purchase_order (o) and order_item (i) — no anchor anywhere in the chain
SELECT * FROM purchase_order o JOIN order_item i ON i.order_id = o.id AND i.tenant_id = o.tenant_id
```

### RP-4 AND / OR composition

Whether a condition fences an occurrence is decided recursively, given the set `K` of occurrences
already known to be fenced:

| Condition | Fences |
|---|---|
| value predicate on `X` (RP-2) | `X` |
| `X.col = Y.col` (RP-3) | `X` if `Y` ∈ `K`; `Y` if `X` ∈ `K` |
| `a AND b AND ...` | the smallest set `S` such that every conjunct's result, computed with `K ∪ S`, is in `S` |
| `a OR b OR ...` | the occurrences fenced by **every** branch (intersection) |
| parentheses | whatever the inner condition fences |
| anything else (`NOT`, `CASE`, functions, subqueries, ...) | nothing |

An `OR` therefore fences `X` only when no branch can return rows of another tenant.

```sql
-- pass: AND chain
SELECT * FROM purchase_order o WHERE (o.status = ? AND (o.tenant_id = ?))

-- pass: every branch fences o
SELECT * FROM purchase_order o WHERE o.tenant_id = ? OR o.tenant_id = ?

-- pass: applied recursively
SELECT * FROM purchase_order o
WHERE (o.tenant_id = ? AND (o.status = 'A' OR o.status = 'B')) OR (o.tenant_id IN (?, ?) AND o.total > 0)

-- violation: the second branch returns every tenant's rows
SELECT * FROM purchase_order o WHERE o.tenant_id = ? OR o.status = 'OPEN'

-- violation: one branch out of three has no tenant filter
SELECT * FROM purchase_order o
WHERE (o.tenant_id = ? AND o.status = 'A') OR (o.tenant_id = ? AND o.status = 'B') OR o.status = 'C'
```

### RP-5 Alias binding and ambiguous columns

The column must be qualified with the occurrence's reference name. An unqualified column binds to
the occurrence **only** when that occurrence is the sole row source of its query block. When a table
has an alias, the table name is not a valid qualifier.

In a query block with several row sources, an unqualified tenant column used in a predicate cannot
be bound, because QueryFence does not know the schema. If that block has unfenced occurrences, they
are reported as **one** `AMBIGUOUS_COLUMN` violation for the block instead of one
`MISSING_PREDICATE` each: the fix is to qualify the column. That violation carries no `table` and no
`alias` — it is about the block, not one table — and its message lists the candidate tables.

```sql
-- pass: single row source, unqualified column is unambiguous
SELECT * FROM purchase_order WHERE tenant_id = ?

-- pass: no alias, table name as qualifier
SELECT * FROM purchase_order WHERE purchase_order.tenant_id = ?

-- violation: order_item (i) — the predicate is bound to o
SELECT * FROM purchase_order o JOIN order_item i ON i.order_id = o.id WHERE o.tenant_id = ?

-- violation AMBIGUOUS_COLUMN: unqualified column in a two-table block
SELECT * FROM purchase_order o JOIN order_item i ON i.order_id = o.id WHERE tenant_id = ?
```

### RP-6 Where the predicate may appear

For an occurrence `T` in a query block, conditions are taken from:

1. the block's `WHERE` clause;
2. the `ON` clause of any `INNER` (or plain) `JOIN` in the block, since inner-join conditions filter
   exactly like `WHERE`;
3. the `ON` clause of the `LEFT JOIN` that introduces `T` (T is the nullable side, so the condition
   limits which of its rows can appear). Such a condition fences `T` only.

These conditions are combined as one `AND` (RP-4). Not accepted: the `ON` clause of a `LEFT JOIN` for
the preserved (left) side, any `RIGHT JOIN` or `FULL JOIN` `ON` clause, and `HAVING`. Those conditions
do not remove rows of the preserved side. The fix is to move the tenant predicate of the preserved
table to `WHERE`.

```sql
-- pass: tenant filters in an inner join's ON clause
SELECT * FROM purchase_order o JOIN order_item i ON i.order_id = o.id AND o.tenant_id = ? AND i.tenant_id = ?

-- pass: i is the nullable side of its own LEFT JOIN
SELECT * FROM purchase_order o LEFT JOIN order_item i ON i.order_id = o.id AND i.tenant_id = ?
WHERE o.tenant_id = ?

-- violation: purchase_order (o) — a LEFT JOIN condition does not filter the left side
SELECT * FROM purchase_order o LEFT JOIN order_item i ON i.order_id = o.id AND o.tenant_id = ? AND i.tenant_id = ?
```

### RP-7 Subqueries

Every subquery is its own query block and is checked on its own, wherever it appears: `WHERE`
(`IN`, `EXISTS`, scalar comparison), `SELECT` list, `FROM` (derived table), `HAVING`, `QUALIFY`,
`GROUP BY`, `ORDER BY`, `LIMIT`, `OFFSET`, `FETCH`, `DISTINCT ON`, `WINDOW` definitions,
`START WITH`/`CONNECT BY`, `SET` of an `UPDATE` or of a MySQL `INSERT ... SET`, `RETURNING`, and the
value of a `SET @variable = (...)`. A predicate in an outer block never fences an occurrence in an
inner block. A protected table in any other place the engine does not walk is reported as not
analysed (RP-13), never skipped.

A correlated subquery may anchor its occurrences on an enclosing block through a tenant-column
equality (RP-3): `i.tenant_id = o.tenant_id` inside the subquery fences `i` when `o` is a fenced
occurrence of an enclosing block.

A filter applied to a derived table from outside does **not** fence the table inside it: v0.1 does
not push predicates down. This is safe but is a **known source of false positives** (some ORMs wrap
queries in a derived table for pagination). Its real-world rate will be measured in Phase 5.

```sql
-- pass
SELECT * FROM purchase_order o WHERE o.tenant_id = ?
  AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id AND i.tenant_id = ?)

-- pass: correlated equality to a fenced outer occurrence
SELECT * FROM purchase_order o WHERE o.tenant_id = ?
  AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id AND i.tenant_id = o.tenant_id)

-- violation: order_item (i) inside EXISTS is unfenced
SELECT * FROM purchase_order o WHERE o.tenant_id = ?
  AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id)

-- violation (known false positive): purchase_order inside the derived table
SELECT * FROM (SELECT * FROM purchase_order) t WHERE t.tenant_id = ?
```

### RP-8 Set operations

Each branch of `UNION`, `UNION ALL`, `INTERSECT` and `EXCEPT` is its own query block.

```sql
-- pass
SELECT id FROM purchase_order WHERE tenant_id = ? UNION SELECT id FROM invoice WHERE tenant_id = ?

-- violation: invoice in the second branch
SELECT id FROM purchase_order WHERE tenant_id = ? UNION SELECT id FROM invoice
```

### RP-9 Common table expressions

Each CTE body is a query block. A reference to a CTE name is not an occurrence, even when the CTE
has the same name as a protected table. As with derived tables, a filter applied where the CTE is
used does not fence the tables inside its body (same known false-positive source as RP-7).

**Recursive CTEs:** every branch is a block of its own and must fence its own occurrences, including
the recursive branch — the anchor branch does not fence it, because the recursion walks rows that
the join condition alone does not restrict to one tenant. A tree walk over a protected table
therefore needs the tenant predicate in both branches. Violations reported in a branch that reads
the CTE itself say so and suggest adding `AND <alias>.<column> = ?` to that branch.

```sql
-- pass
WITH mine AS (SELECT * FROM purchase_order WHERE tenant_id = ?) SELECT * FROM mine WHERE mine.status = ?

-- violation: purchase_order in the CTE body
WITH all_orders AS (SELECT * FROM purchase_order) SELECT * FROM all_orders a WHERE a.tenant_id = ?

-- violation: purchase_order (p) in the recursive branch
WITH RECURSIVE tree AS (
  SELECT id, parent_id FROM purchase_order WHERE tenant_id = ?
  UNION ALL
  SELECT p.id, p.parent_id FROM purchase_order p JOIN tree ON p.parent_id = tree.id)
SELECT * FROM tree

-- pass: both branches fence their own occurrence
WITH RECURSIVE tree AS (
  SELECT id, parent_id FROM purchase_order WHERE tenant_id = ?
  UNION ALL
  SELECT p.id, p.parent_id FROM purchase_order p JOIN tree ON p.parent_id = tree.id AND p.tenant_id = ?)
SELECT * FROM tree
```

### RP-10 UPDATE and DELETE

The target is an occurrence; its conditions come from the statement's `WHERE` (and inner-join `ON`
clauses of MySQL multi-table forms). Joined tables of multi-table forms (`JOIN`, `FROM`, `USING`)
are occurrences under the same rules. Subqueries in `SET` and `WHERE` are checked per RP-7.

```sql
-- pass
UPDATE purchase_order SET status = ? WHERE tenant_id = ? AND id = ?

-- violation: purchase_order — only filtered by id
DELETE FROM purchase_order WHERE id = ?

-- violation: order_item (i) in a MySQL multi-table UPDATE
UPDATE purchase_order o JOIN order_item i ON i.order_id = o.id SET i.price = ? WHERE o.tenant_id = ?
```

### RP-11 INSERT

`INSERT INTO t (...)` into a protected table passes only when the rule's column appears in the
explicit column list (MySQL `INSERT ... SET col = ...` counts). An `INSERT` without a column list
is a violation: we cannot prove which columns are set. The inserted value is not checked (v0.1).
For `INSERT ... SELECT`, the `SELECT` is checked as a query block per RP-1..RP-9.
`REPLACE INTO` (MySQL) is treated like `INSERT`.

```sql
-- pass
INSERT INTO purchase_order (id, tenant_id, status) VALUES (?, ?, ?)

-- violation: column list has no tenant_id
INSERT INTO purchase_order (id, status) VALUES (?, ?)

-- violation: no column list
INSERT INTO purchase_order VALUES (?, ?, ?)

-- violation: the source purchase_order (o) of INSERT ... SELECT
INSERT INTO invoice (tenant_id, order_id) SELECT o.tenant_id, o.id FROM purchase_order o
```

### RP-12 Identifiers

- Identifiers compare case-insensitively after removing quotes (MySQL backticks, ANSI/Postgres
  double quotes, SQL Server brackets).
- A `tables` entry without a schema matches the table in any schema (`app.purchase_order`
  matches `purchase_order`). An entry with a schema (`app.purchase_order`) matches references with
  that schema **and** unqualified references, since the search path is unknown.

### RP-13 Statements out of scope of the rule

Statements that touch no protected table pass. DDL, `TRUNCATE`, transaction control (`BEGIN`,
`COMMIT`, `SET`, `SAVEPOINT`), `SHOW`, `EXPLAIN` without `ANALYZE` and `CALL` are ignored: test
fixtures routinely truncate tables, and schema statements carry no tenant data. Statements of these
kinds that do read rows are checked like the query they run: the `SELECT` of
`CREATE TABLE ... AS SELECT` and of `CREATE MATERIALIZED VIEW`, the statement under
`EXPLAIN ANALYZE` (which executes it), and the subqueries of a `SET @variable = (...)`. A plain
`CREATE VIEW` reads no rows and stays ignored; views are opaque.

A DML statement type the rule does not analyse (`MERGE` in v0.1) is a violation
(`UNSUPPORTED_STATEMENT`) for every protected table it names, wherever it names it: target,
`USING` source, or any subquery. Within the statements the rule does analyse, a protected table
that appears in a place the engine does not walk (a table function argument, an upsert conflict
branch, ...) is an `UNSUPPORTED_STATEMENT` violation too, with its own message: every table node of
the parsed statement must have been checked as an occurrence.

A string holding several statements separated by `;` is split and each statement is checked. When
the string does not parse as a whole, it is split on its top-level semicolons (not inside comments,
string literals, quoted identifiers or `$$` bodies) and each part is parsed and checked on its own,
so one statement the parser rejects never hides the others.

Some statements are dialect syntax JSqlParser cannot parse (`SET search_path TO app`,
`FLUSH TABLES`). An unparseable statement is ignored instead of reported only when **both** hold:
it starts with a keyword of a statement that carries no tenant data (`SET`, `SHOW`, `BEGIN`,
`START`, `COMMIT`, `ROLLBACK`, `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `GRANT`, `CALL`, `FLUSH`,
...), and it uses no keyword that reads or writes rows (`SELECT`, `INSERT`, `UPDATE`, `DELETE`,
`MERGE`, `UPSERT`, `COPY`, or `AS TABLE`; `ON DELETE`/`ON UPDATE` of a foreign key do not count).
Comments and string literals are not read. Statements that run queries (`COPY`, `DO`, `EXECUTE`,
`EXPLAIN`, `PREPARE`) are never ignored by keyword. Anything else that fails to parse stays fail
closed and is reported (`UNPARSEABLE`).

### RP-14 Lookups by primary key

An occurrence that is not fenced, but whose only value predicate is on the rule's `primaryKey`
(`id` by default, configurable per rule), is reported as `PRIMARY_KEY_LOOKUP` rather than
`MISSING_PREDICATE`. The statement is just as unsafe — ids are guessable, and `WHERE id = ?` returns
another tenant's row as happily as its own — but the fix is a different one, so the message differs:
filter by tenant as well, or map the tenant on the entity so every load carries it.

This is the shape `EntityManager.find`, `JpaRepository.findById` and `deleteById` produce.

```sql
-- violation PRIMARY_KEY_LOOKUP
SELECT id, total FROM purchase_order WHERE id = ?
UPDATE purchase_order SET status = ? WHERE id = ?

-- pass: the tenant filter is there too
SELECT id, total FROM purchase_order WHERE id = ? AND tenant_id = ?

-- violation MISSING_PREDICATE: under OR the id narrows nothing
SELECT id FROM purchase_order WHERE id = ? OR status = ?
```

## Rule: `update-without-where`

```yaml
- id: no-unbounded-update
  type: update-without-where
```

Applies to every `UPDATE`, on any table.

- **UW-1** An `UPDATE` without a `WHERE` clause is a violation, including multi-table forms whose
  only restriction is a join, and statements with `LIMIT` but no `WHERE`.
- **UW-2** An `UPDATE` whose `WHERE` is a tautology is a violation. A tautology is a condition made
  only of constants that is true (`1 = 1`, `TRUE`, `1`, `'a' = 'a'`), or a top-level OR chain with
  at least one such operand (`id = ? OR 1 = 1`).
- **UW-3** Anything else passes. `WHERE 1 = 1 AND id = ?` passes: the constant is a harmless
  conjunct, a common artifact of query builders.

```sql
-- pass
UPDATE purchase_order SET status = ? WHERE id = ?
-- violation (UW-1)
UPDATE purchase_order SET status = 'CLOSED'
-- violation (UW-2)
UPDATE purchase_order SET status = ? WHERE 1 = 1
```

## Rule: `delete-without-where`

Same definition as `update-without-where`, for `DELETE` (clauses **DW-1..DW-3**). Postgres
`DELETE ... USING` and MySQL multi-table `DELETE` without `WHERE` violate DW-1. `TRUNCATE` is DDL and
out of scope.

```sql
-- pass
DELETE FROM order_item WHERE order_id = ?
-- violation (DW-1)
DELETE FROM order_item
-- violation (DW-2)
DELETE FROM order_item WHERE order_id = ? OR TRUE
```

## Unparseable SQL

A statement JSqlParser cannot parse produces an `UNPARSEABLE` violation, whatever tables it may
touch. So does a statement that parses but whose analysis fails unexpectedly: the engine never
throws, it reports the statement as one it could not analyse, with the same rule id, code, mode and
table scan, and a message that says *analyse* instead of *parse*.

**Mode.** `onUnparseable` governs these violations and nothing else, independently of `mode`:
`onUnparseable: FAIL` (the default) fails the test, `onUnparseable: REPORT` only records it. A run
with `mode: FAIL` and `onUnparseable: REPORT` therefore fails on a leak while only reporting what
the parser could not read — the setting a first adoption wants. The decision is per finding
(`Policy.modeFor(code)`), never per run.

**Rule id.** These violations come from the parser, not from a rule, and carry the rule id
`parser`. They are suppressed by origin like any other violation, with `rule: parser`.

**Tables.** We cannot say what an unparseable statement does, but we can say which protected tables
it names, so a parser failure does not hide a table from the report: the raw SQL is scanned for the
tables the policy protects and one violation is emitted per table mentioned, in the order the policy
declares them. Matching is on whole identifiers, case-insensitively; a schema prefix
(`app.purchase_order`) and quoting (`"purchase_order"`, `` `purchase_order` ``) still match,
`purchase_order_archive` does not, and comments and string literals are ignored. A statement that
mentions no protected table produces one violation with no table.

## Known bypasses and how they are blocked

Every entry below has corpus cases. New bypasses are security issues (see `SECURITY.md`) and are
fixed test-first by adding corpus cases.

| Bypass | Example | Blocked by |
|---|---|---|
| `OR` with an unfenced branch | `WHERE o.tenant_id = ? OR 1 = 1` | RP-4 |
| Predicate under `NOT` / negation | `WHERE NOT (o.tenant_id <> ?)` | RP-4 (`NOT` fences nothing) |
| Inequality or range | `WHERE o.tenant_id <> ?`, `> 0` | RP-2 |
| Comparison with an arbitrary function | `WHERE o.tenant_id = some_fn()` | RP-2 (`allowedFunctions`) |
| Alias bound to the wrong table | `... JOIN order_item i ... WHERE o.tenant_id = ?` | RP-1, RP-5 |
| Unqualified column in a multi-table block | `... JOIN order_item i ... WHERE tenant_id = ?` | RP-5 |
| Join without a condition for the joined table | `JOIN invoice v ON v.order_id = o.id` | RP-1 |
| Self-join with one side fenced | `purchase_order o JOIN purchase_order p` | RP-1 |
| Tenant filter on the preserved side of a `LEFT JOIN` placed in `ON` | `LEFT JOIN ... ON ... AND o.tenant_id = ?` | RP-6 |
| Unfenced subquery (`EXISTS`, `IN`, scalar, `SELECT` list) | `EXISTS (SELECT 1 FROM order_item i WHERE ...)` | RP-7 |
| Filter outside a derived table | `FROM (SELECT * FROM purchase_order) t WHERE t.tenant_id = ?` | RP-7 |
| Unfenced `UNION` branch | `... UNION SELECT id FROM invoice` | RP-8 |
| Unfenced CTE body | `WITH a AS (SELECT * FROM purchase_order) ...` | RP-9 |
| Tenant-column equalities with no anchor | `i.tenant_id = o.tenant_id` alone | RP-3 |
| Predicate hidden in a comment | `WHERE o.status = ? -- AND o.tenant_id = ?` | parser drops comments; RP-1 |
| `INSERT` without column list | `INSERT INTO purchase_order VALUES (...)` | RP-11 |
| Unfenced `INSERT ... SELECT` source | `INSERT INTO t (...) SELECT ... FROM purchase_order` | RP-11 |
| Quoting / case / schema tricks | `` `Purchase_Order` ``, `app."PURCHASE_ORDER"` | RP-12 |
| SQL the parser does not understand | vendor syntax | fail closed: `UNPARSEABLE` |
| Unsupported DML | `MERGE INTO purchase_order ...`, `MERGE ... USING purchase_order` | RP-13 |
| Protected table in a clause the engine does not walk | `generate_series(1, (SELECT ... FROM purchase_order))` | RP-13 |
| Statement hidden behind an ignorable one | `SET search_path TO app; SELECT * FROM purchase_order` | RP-13 (split) |
| Query inside a statement ignored by keyword | `CREATE TABLE t AS SELECT ...`, `COPY (SELECT ...)` | RP-13 |

Known **unblocked** paths in v0.1 (documented limitations): views and stored procedures over
protected tables (list views in `tables` as a workaround), the value bound to `?` or returned by an
allowed function, upsert conflict branches, and SQL that no test executes.

### Statements JSqlParser cannot parse

QueryFence can only reason about SQL that JSqlParser 5.4 understands. Statements that can neither
read nor write rows are ignored when they fail to parse (RP-13), but a DML statement that fails to
parse is a violation, even if it carries a correct tenant filter. That is deliberate: a
pattern-based ignore list would be a bypass anybody could copy into their policy. Such a statement
is suppressed by origin, with a reason, like any other exception.

One valid statement shape is known to fail: **an unqualified column named `number`**.
`SELECT number FROM invoice` does not parse, while `SELECT i.number FROM invoice i` and
`SELECT "number" FROM invoice` do; the cause appears to be the `NUMBER` type keyword of
`CAST(x AS NUMBER)`. `number` is an ordinary column name in billing schemas, so the workaround is to
qualify or quote the column, or to suppress the origin with `rule: parser`. Reported in
`docs/upstream-issues/jsqlparser-unqualified-column-named-number.md`.

Apart from that, we know of no valid DML statement that JSqlParser 5.4 rejects. Dialect syntax we
checked and that parses: `DISTINCT ON`, `ILIKE`, JSONB `->>`, `FOR UPDATE SKIP LOCKED`, `RETURNING`, Oracle
hints, `WINDOW`, `TABLESAMPLE`, `ANY(ARRAY[...])`, `FROM ONLY`, MySQL `JSON_TABLE` in FROM and JOIN,
`INSERT ... SET`, `REPLACE`, multi-table `UPDATE`/`DELETE`, `UPDATE ... FROM`, `DELETE ... USING`,
`ON CONFLICT`, `ON DUPLICATE KEY UPDATE`, `LATERAL`. Statements that do not parse and are ignored by
keyword when they read no rows: `SET`, `FLUSH`, `BEGIN`, `START TRANSACTION`.

When we do find one, we report it upstream and record the report in `docs/upstream-issues/`.

### Dialects

Corpus cases declare a `dialect` (`ansi`, `mysql`, `postgres`) but v0.1 parses every statement with
the default JSqlParser grammar, which accepts the dialect syntax we have met so far. Feeding the
declared dialect into the parser (and into dialect-specific rules) is deferred; the field exists so
the corpus does not have to be relabelled later.

**ORM associations.** A fetch join or a lazy association loads child rows by foreign key only
(`select ... from order_item where order_id = ?`). Those rows belong to a parent the application
fenced, but the statement does not say so, so QueryFence reports them.

The recommended answer is to **map the tenant on the child entity** with Hibernate `@TenantId` (or
an equivalent filter). Hibernate then adds the tenant condition to every load of that entity,
including lazy ones, the generated SQL carries it, and QueryFence goes quiet — which is the whole
idea: *Hibernate enforces, QueryFence verifies*. Nothing about QueryFence changes; the SQL does.

Two fallbacks, with their price:

- Protect only the aggregate root in the policy. Cheap, but the child table is then never checked:
  a query that reads `order_item` directly, without going through the root, is not reported.
- Suppress the origin with a reason. Narrow, but it has to be revisited whenever that code changes.

See `queryfence-integration-tests/README.md` for what each framework generates.

Known **false-positive** sources in v0.1, to be measured in Phase 5 (target below 5%): ORM
associations loaded by foreign key (above), tenant
filters applied outside a derived table or CTE (RP-7, RP-9), recursive CTE branches that walk a
tree of one tenant's rows (RP-9), and tenant filters on the preserved side of an outer join placed
in `ON` (RP-6, a real bug in most cases).

## Violation model

Each violation carries:

| Field | Example |
|---|---|
| `ruleId` | `tenant-isolation` |
| `ruleType` | `require-predicate` |
| `code` | `MISSING_PREDICATE`, `PRIMARY_KEY_LOOKUP`, `AMBIGUOUS_COLUMN`, `MISSING_INSERT_COLUMN`, `NO_WHERE`, `TAUTOLOGICAL_WHERE`, `UNSUPPORTED_STATEMENT`, `UNPARSEABLE` |
| `table`, `alias` | `order_item`, `i`; normalized table name (lower case, no quotes, no schema); `null` when not applicable |
| `message` | what is wrong **and how to fix it** (see below) |
| `sql` | the statement as it was checked |
| `sql` | the statement as sent to the driver |
The origin and the test are **not** part of a violation: `queryfence-core` does not know them.
`queryfence-jdbc` pairs each violation with the statement that produced it, and that statement
carries its origin:

| Field of `Finding` | Example |
|---|---|
| `violation` | the fields above |
| `statement.origin` | `com.acme.order.OrderRepository#findByStatus (OrderRepository.java:42)` |
| `statement.batch` | `false`, or the batch size when it was part of one |

Every message states the problem and the fix. Messages are generated from fixed templates, so the
golden corpus asserts them verbatim. `{ref}` is the alias, or the table name when there is no alias;
`{table}` is shown as `table (alias)` when there is an alias.

| Code | Message template |
|---|---|
| `MISSING_PREDICATE` | `{table} has no tenant filter. Add "{ref}.{column} = ?" to the WHERE clause of the query block that uses it.` |
| `MISSING_PREDICATE` in the recursive branch of a CTE | `{table} has no tenant filter in the recursive branch of CTE "{cte}". Add "AND {ref}.{column} = ?" to that branch.` |
| `PRIMARY_KEY_LOOKUP` | `{table} is looked up by {primaryKey} only, and ids are easy to guess. Filter by tenant as well (findBy{PrimaryKey}And{Column}(...) in Spring Data), or map the tenant on the entity (Hibernate @TenantId) so every load carries it.` |
| `AMBIGUOUS_COLUMN` | `Column "{column}" is not qualified in a query block that reads {tables}, so it protects none of them. Qualify it with the table alias, for example "{ref}.{column} = ?".` |
| `MISSING_INSERT_COLUMN` | `INSERT into {table} does not set {column}. Add {column} to the column list and bind the current tenant.` |
| `NO_WHERE` | `UPDATE of {table} has no WHERE clause and changes every row. Add a WHERE clause that selects only the intended rows.` (`DELETE from {table} ... removes every row.` for DELETE) |
| `TAUTOLOGICAL_WHERE` | `UPDATE of {table} has a WHERE clause that is always true and changes every row. Replace it with a condition that selects only the intended rows.` (DELETE: `removes every row`) |
| `UNSUPPORTED_STATEMENT` | `{STATEMENT} statements on {table} are not analysed yet. Rewrite the statement as INSERT or UPDATE, or suppress its origin with a reason.` |
| `UNSUPPORTED_STATEMENT` in a clause that is not analysed | `{table} appears in a part of this statement that QueryFence does not analyse yet, so its tenant filter cannot be verified. Rewrite the statement to read it in FROM, a JOIN or a WHERE subquery, or suppress its origin with a reason.` |
| `UNPARSEABLE` | `QueryFence could not parse this statement, so it cannot prove it safe. Report the SQL to QueryFence, or set onUnparseable: REPORT to only report it.` |
| `UNPARSEABLE` mentioning a protected table | `QueryFence could not parse this statement, so it cannot prove it safe. It mentions {table}, which stays unverified here: a missing filter on that table would go unnoticed. Report the SQL to QueryFence, or set onUnparseable: REPORT to only report it.` |
| `UNPARSEABLE` after a failed analysis | The two `UNPARSEABLE` templates above with `could not analyse` in place of `could not parse`. |

For `AMBIGUOUS_COLUMN`, `{tables}` lists the unfenced occurrences of the block as
`table (alias)`, `{ref}` is the first of them, and `table`/`alias` are `null`.

## Architecture

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `queryfence-core` | Policy model, parser adapter, rule engine, violation model | JSqlParser only |
| `queryfence-jdbc` | `DataSource` proxy, statement capture, origin resolution | core, datasource-proxy |
| `queryfence-junit5` | JUnit 5 extension, YAML policy loading, console and JSON reports | jdbc, junit-jupiter-api, snakeyaml |
| `queryfence-spring-test` | Wrap every `DataSource` bean in Spring test contexts; zero-code setup | junit5, spring-test and spring-context (`provided`) |
| `queryfence-bom` | Version alignment | — |

`queryfence-core` never depends on JDBC, JUnit, Spring or YAML, so the engine can be reused later
(runtime mode, text-to-SQL validation) without dragging test libraries along.

The dependency rule is also a modelling rule: **core knows nothing about where a statement came
from**. A `Violation` describes a statement, not a call site; there is no stack trace, no class
name and no JDBC type in `queryfence-core`. `queryfence-jdbc` adds that context by pairing a
violation with the `CapturedStatement` that produced it (`QueryRecorder.Finding`), and the report
layers read the origin from there.

### Processing flow

```text
 test starts ──► Recorder.open(testId)
                     │
 code under test ──► wrapped DataSource ──► CapturedStatement(sql, origin, thread)
                     │                         origin = first stack frame outside
                     │                         JDBC / ORM / framework / QueryFence
 test ends ────► Recorder.close() ──► statements
                                          │
                     ParsedStatement cache (key: SQL string)
                                          │
                     rules ──► violations ──► suppressions (Class#method + reason)
                                          │
                     FAIL: throw AssertionError listing violations
                     REPORT: log only
                                          │
 JVM / launcher session ends ──► target/queryfence/report.json
```

- **Capture window.** Statements executed by the test method body **and all code it calls**, on
  any thread, through a wrapped `DataSource` are checked: the window runs from JUnit's
  `BeforeTestExecutionCallback` to `AfterTestExecutionCallback`. Fixture code in
  `@BeforeEach`/`@AfterEach`/`@BeforeAll`/`@AfterAll`, Spring context startup and schema
  migrations run outside that window and are not checked.
- **Origin resolution.** `StackWalker` returns the first frame whose class is not infrastructure:
  the JDK, the JDBC drivers, `org.hibernate.`, `org.springframework.`, `org.apache.ibatis.`,
  `org.mybatis.`, `com.baomidou.`, `org.jooq.`, `com.zaxxer.`, `net.ttddyy.`,
  `com.steelreed.queryfence.`, plus generated proxy classes (`$$`, `$Proxy`). Naming your own
  packages with `CaptureSettings.ofBasePackages("com.acme")` makes the result exact: the origin is
  then the first frame in those packages, or `Origin.unknown()` when the statement comes from
  somewhere else entirely.
- **What capture sees.** Every statement the driver executes, including each statement of a JDBC
  batch (with its batch size) and statements that threw while executing. A statement the driver
  rejects while *preparing* it never reaches the listener, so QueryFence cannot check it — that
  test fails on its own anyway.
- **Suppressions** match the resolved origin exactly on class name and method name (no overloads).
  A suppression without a non-blank `reason` is a configuration error at load time. Suppressions
  that matched nothing during the run are listed in the report as stale.
- **Parse cache** is keyed by the exact SQL string and shared across tests.

### Public API (sketch)

Everything else lives in `internal` packages.

```java
// queryfence-core — com.steelreed.queryfence.core
Policy policy = Policy.builder()
    .requirePredicate("tenant-isolation", "tenant_id", "purchase_order", "order_item")
    .updateWithoutWhere("no-unbounded-update")
    .deleteWithoutWhere("no-unbounded-delete")
    .suppress("tenant-isolation", "com.acme.admin.PlatformReportJob#nightlyTotals", "reason ...")
    .mode(Mode.FAIL)
    .onUnparseable(Mode.FAIL)
    .build();

// allowedFunctions:
// .requirePredicate("tenant-isolation", "tenant_id", List.of("purchase_order"), List.of("current_setting"))

SqlChecker checker = SqlChecker.of(policy);
List<Violation> violations = checker.check("SELECT ...");   // no origin, no suppression

// queryfence-junit5 — com.steelreed.queryfence.junit5
@RegisterExtension
static final QueryFenceExtension queryFence = QueryFenceExtension.fromClasspath("queryfence.yml");
// or QueryFenceExtension.of(policy)
DataSource wrapped = queryFence.wrap(dataSource);

// queryfence-spring-test — no user-facing API; registered through META-INF/spring.factories
// (ContextCustomizerFactory + TestExecutionListener), reads classpath:queryfence.yml.
```

### Policy file

```yaml
version: 1                       # required; only 1 is valid
mode: FAIL                       # FAIL | REPORT, default FAIL
onUnparseable: FAIL              # FAIL | REPORT, default FAIL, governs UNPARSEABLE only
basePackages: []                 # optional: resolve origins inside these packages only
rules:
  - id: tenant-isolation         # required, unique
    type: require-predicate      # require-predicate | update-without-where | delete-without-where
    column: tenant_id            # require-predicate only, required
    tables: [purchase_order]     # require-predicate only, required, non-empty
    allowedFunctions: []         # require-predicate only, optional (RP-2)
    primaryKey: id               # require-predicate only, optional, default id (RP-14)
suppressions:
  - rule: tenant-isolation       # a rule id, or `parser` for UNPARSEABLE
    origin: com.acme.Foo#bar     # required, Class#method
    reason: why this is safe     # required, non-blank
```

Unknown keys, duplicate rule ids and missing required fields are configuration errors, and every
message names the file the mistake is in. The file is read once per JVM, so a suite of a hundred
test classes parses it once and an invalid file is diagnosed once.

`basePackages` lives here rather than in `Policy` because it is a capture concern: the engine knows
nothing about stack traces. The loader hands it to `CaptureSettings`.

## Roadmap

| Version | Scope |
|---|---|
| 0.1 | require-predicate, update/delete-without-where, JUnit 5, Spring test support, JSON report |
| 0.2 | Baseline file, HTML report, parameter value check |
| 0.3 | Runtime mode, Spring Boot starter, text-to-SQL validator |
| 1.0 | API freeze |
