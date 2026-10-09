# Integrations

## Spring Boot

Add `queryfence-spring-test` and a `queryfence.yml`. A `ContextCustomizerFactory` registered
through `spring.factories` wraps every `DataSource` bean of every test context
(`@SpringBootTest`, `@DataJpaTest`, `@JdbcTest`, …); a `TestExecutionListener` checks what each
test method executed. Without a policy on the classpath the dependency changes nothing.

Each wrapped bean keeps its class: it is replaced by a class-based proxy of its own type that sends
`getConnection` through the capture and every other method to the original bean, so
`@Autowired HikariDataSource` and pool settings keep working. A bean whose class cannot be
subclassed (a final class, or a final `getConnection`) is wrapped as a plain `DataSource`; inject it
by the `DataSource` interface.

Layered data sources are fine: when a `LazyConnectionDataSourceProxy`,
`TransactionAwareDataSourceProxy` or `AbstractRoutingDataSource` bean sits over a pool bean, both
are wrapped, so code using either one is checked, and a statement that passes through several
layers is recorded once, by the outermost.

Another policy for one test class:

```java
@SpringBootTest
@QueryFencePolicy("queryfence-legacy.yml")
class LegacyReportingTest { }
```

## Plain JUnit 5

```java
@RegisterExtension
static final QueryFenceExtension queryFence = QueryFenceExtension.fromClasspath();

DataSource fenced = queryFence.wrap(rawDataSource);
```

`junit-jupiter-api` is a `provided` dependency: your build decides the version. CI tests JUnit
5.10 through 6.1.

## Anything behind a DataSource

Hibernate, Spring Data JPA, MyBatis, jOOQ, `JdbcTemplate` and raw JDBC all go through the proxy.
What QueryFence checks is the SQL the framework generated, which is the point: nobody writes a
derived query's SQL by hand.

| Framework construct | What QueryFence sees |
|---|---|
| Derived query `findByTenantIdAndStatus` | `... where po1_0.tenant_id=? and po1_0.status=?` — accepted |
| Derived query `findByStatus` | `... where po1_0.status=?` — reported |
| `Pageable` | the page query *and* the `count(*)` query, both checked |
| `@Query` JPQL, `exists` subqueries | the translated SQL, aliases and all |
| `findById` / `EntityManager.find` | `... where po1_0.id=?` — `PRIMARY_KEY_LOOKUP` |
| Fetch join, lazy association | `... from order_item i1_0 where i1_0.order_id=?` — reported, see below |
| MyBatis `<if>` / `<foreach>` / `<where>` | the statement that set of arguments produced |

### Associations and `@TenantId`

A fetch join or a lazy association loads child rows by foreign key only. Those rows belong to a
parent your code fenced, but the statement does not say so, so QueryFence reports them.

The recommended fix is to map the tenant on the child entity with Hibernate `@TenantId`. Hibernate
then adds the tenant condition to every load of that entity, including lazy ones; the generated SQL
carries it and QueryFence goes quiet. *Hibernate enforces, QueryFence verifies.*

The fallback is to protect only the aggregate root in the policy. It is cheaper, and the price is
explicit: the child table is then never checked, so a query that reads it directly is not reported.

## Databases

Whatever JSqlParser understands. The integration suite runs MySQL 8.4 and Postgres 17 through
Testcontainers on every pull request.
