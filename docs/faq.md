# FAQ

### Does QueryFence change my SQL?

No. It observes statements through a `DataSource` proxy and passes them to the driver unchanged.
It has no runtime mode in 0.1.

### Does my production code depend on QueryFence?

No. The dependency is `test` scope, there is no annotation to add, and suppressions live in the
policy file.

### Why did it report `findById`?

A primary key is not a tenant filter: ids are guessable, and `where id = ?` happily returns another
tenant's row. That is `PRIMARY_KEY_LOOKUP`. Use `findByIdAndTenantId`, or map the tenant on the
entity with Hibernate `@TenantId` so every load carries it.

### Why did it report a lazy association or a fetch join?

Hibernate loads child rows by foreign key only (`where order_id = ?`). The rows belong to a parent
you fenced, but the statement does not say so. Map the tenant on the child entity, or protect only
the aggregate root and accept that the child table is then not checked.

### Why is a query with `OR` reported although both branches look safe?

An `OR` fences a table only when **every** branch fences it. `tenant_id = ? OR tenant_id = ?` is
accepted; `tenant_id = ? OR status = 'OPEN'` is not, because the second branch returns every
tenant's rows. Use `IN (?, ?)`.

### It says my SQL is `UNPARSEABLE`, but the database runs it.

QueryFence fails closed: what it cannot parse, it cannot clear. Open an issue with the statement —
that is a bug worth fixing upstream — and suppress the origin meanwhile. Statements that cannot
read or write rows (`SET`, `SHOW`, `FLUSH`, DDL, `CALL`) are ignored when they do not parse.

### Does it work with JPA/Hibernate criteria, jOOQ, or a query builder I wrote myself?

Yes. Anything that ends up as SQL on a `DataSource` is checked, whoever generated it.

### My `@DataJpaTest` saves an entity that breaks the policy, and nothing is reported.

QueryFence checks SQL that reaches JDBC. In a `@Transactional` test — `@DataJpaTest` is one by
default — the transaction is rolled back at the end, and Hibernate does not flush pending
`INSERT`/`UPDATE`/`DELETE` statements before a rollback, so the write never reaches the database and
is never checked. Flush inside the test: `entityManager.flush()` (or `TestEntityManager.flush()`),
or `saveAndFlush()`. Queries are sent as they run and are checked either way. See
[Integrations](integrations.md#writes-in-transactional-tests).

### Does it slow my tests down?

Parsing happens once per distinct SQL string and is cached. The integration suite checks tens of
statements per test with no measurable difference.

### Can I use it outside tests?

Not in 0.1. The engine (`queryfence-core`) has no JDBC or test dependencies, which keeps a runtime
mode possible later, but nothing ships for it yet.

### What about parallel test execution?

JUnit parallel execution *inside* one JVM is unsupported in 0.1: statements are attributed to the
running test by time window. Running tests in several JVMs (Surefire `forkCount`, Gradle
`maxParallelForks`) is fine: each JVM checks its own tests, and `report.json` merges their reports
(see [the report files](configuration.md#the-report-files)).

### Which JUnit and Spring versions?

CI runs JUnit 5.10 through 6.1 and Spring Boot 3.3 through 4.1. `junit-jupiter-api` is a `provided`
dependency, so your build picks the version.
