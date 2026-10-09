# Integration tests

What real frameworks send to real databases, and what QueryFence makes of it.

```bash
./mvnw -pl queryfence-integration-tests -am -Pintegration verify   # needs Docker
```

Without `-Pintegration` these tests are skipped, so the everyday build stays fast and Docker-free.
CI runs them on pull requests and on `main`.

## Matrix

{Hibernate (Spring Data JPA), MyBatis, JdbcTemplate} × {MySQL 8.4, Postgres 17}. Every combination
has both a query that leaks (QueryFence must report it) and a correct query (QueryFence must stay
quiet). The policy runs in `REPORT` mode so each test can assert what was found.

The application code lives in `com.acme.shop`, not in `com.steelreed.*`: QueryFence skips its own
packages when it resolves an origin, so fixtures in its own package would be attributed to the test
framework instead of to the code that wrote the query.

## What the frameworks generate

| Construct | SQL QueryFence saw | Verdict |
|---|---|---|
| Derived query `findByTenantIdAndStatus` | `... from purchase_order po1_0 where po1_0.tenant_id=? and po1_0.status=?` | accepted |
| Derived query `findByStatus` | `... where po1_0.status=?` | **leak reported** |
| `Pageable` | page query with `fetch first ? rows only` plus a `count(*)` query | both checked; accepted or reported together |
| `@Query` JPQL | the JPQL translated to SQL, aliases `po1_0` | accepted / reported as written |
| JPQL `exists` subquery | correlated `exists (select ... from order_item i1_0 where i1_0.order_id=po1_0.id and i1_0.tenant_id=?)` | accepted |
| `EntityManager.find` | `... from purchase_order po1_0 where po1_0.id=?` | **leak reported**: a primary key is not a tenant filter |
| `join fetch o.items` | `... from purchase_order po1_0 join order_item i1_0 on po1_0.id=i1_0.order_id where po1_0.tenant_id=?` | **reported on `order_item`** (see below) |
| Lazy association | `... from order_item i1_0 where i1_0.order_id=?` | **reported on `order_item`** (see below) |
| MyBatis `<if>` with a tenant | `... where tenant_id = ? and status in (?, ?)` | accepted |
| MyBatis `<if>` without a tenant | the same mapper method, `... where status in (?, ?)` | **leak reported** |
| MyBatis `<foreach>` | expands to `in (?, ?)`, formatting and newlines included | parsed and accepted |
| MyBatis `<where>` that collapses | `UPDATE purchase_order SET status = 'CLOSED'` | **leak reported**, twice: no tenant and no WHERE |

## Associations: reported on purpose

A fetch join and a lazy association both load child rows by foreign key only:

```sql
select ... from order_item i1_0 where i1_0.order_id=?
```

Those rows do belong to a parent the application already fenced, but the statement does not say so,
and QueryFence only judges the statement. It reports them. Three ways out, in order of preference:

1. Map the tenant on the association too (Hibernate `@TenantId`, or a `@JoinColumn` pair that
   includes `tenant_id`), so the generated SQL carries it.
2. Protect only the aggregate root in the policy, and rely on the root's filter for its children.
3. Suppress that origin with a reason.
