<div class="qf-hero" markdown>

<span class="qf-pill">Part of Steelreed · 0.1.0</span>

# QueryFence

<p class="qf-lead"><strong>SQL policy testing for the JVM.</strong> Declare a rule once (<em>every
query touching <code>purchase_order</code> must filter by <code>tenant_id</code></em>) and QueryFence
checks every statement your integration tests actually send to the database, then fails the build on
the ones that break it, pointing at the class, method and line that wrote the query.</p>

[Get started](getting-started.md){ .md-button .md-button--primary }
[View on GitHub](https://github.com/steelreed/queryfence){ .md-button }

</div>

!!! note "First release"
    0.1.0 is the first release, and the public API may still change before 1.0. CI tests
    QueryFence on JUnit 5.10 to 6.1 and Spring Boot 3.3 to 4.1; there are no real users on Spring
    Boot 4 yet, so that combination is tested but not promised.

## The problem

One query forgets `WHERE tenant_id = ?`.

1. **Code review misses it.** The missing filter is an absence, and absences are hard to see.
2. **Tests stay green.** Fixtures usually hold one tenant, so "all orders" and "this tenant's
   orders" return the same rows.
3. **Production leaks.** Customer A opens a page and sees customer B's data.

The query can come from JPQL, a derived repository method, MyBatis XML, jOOQ, `JdbcTemplate`, or
code an assistant wrote five minutes ago. QueryFence looks at the one thing they have in common:
the SQL that reaches the driver.

## What it looks like

```text
QueryFence: 1 violation in com.acme.order.OrderServiceTest#listsPendingOrders

  [tenant-isolation] MISSING_PREDICATE
    table   : purchase_order (alias o)
    problem : purchase_order (o) has no tenant filter. Add "o.tenant_id = ?" to the WHERE clause of the query block that uses it.
    sql     : SELECT o.id, o.total FROM purchase_order o WHERE o.status = ?
    origin  : com.acme.order.OrderRepository#findByStatus (OrderRepository.java:42)

Full report: target/queryfence/report.json
```

## Enforce at runtime, verify in tests

QueryFence does not rewrite or block SQL, and it is not a replacement for Postgres RLS, Hibernate
`@TenantId` or a MyBatis tenant interceptor. Those **enforce**. QueryFence **verifies** — in CI,
against the SQL your application really runs — that the enforcement is doing its job, including on
the code paths that quietly fall outside it.

[Get started](getting-started.md){ .md-button .md-button--primary }
[How it works](how-it-works.md){ .md-button }
