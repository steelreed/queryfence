# JSqlParser: an unqualified column named `number` does not parse

**Status: ready to file.** Found while dogfooding QueryFence on a billing schema
(`invoice.number`), where it turned a correct, tenant-filtered `UPDATE` into an `UNPARSEABLE`
violation.

Everything below is the issue text; paste it into
<https://github.com/JSQLParser/JSqlParser/issues>.

---

**Title:** `number` cannot be used as an unqualified column name in an expression

**Version:** JSqlParser 5.4, Java 17

### Minimal reproduction

```java
CCJSqlParserUtil.parse("SELECT number FROM invoice");        // throws JSQLParserException
CCJSqlParserUtil.parse("SELECT i.number FROM invoice i");    // parses
```

The same holds anywhere a column reference is expected:

| Statement | 5.4 |
|---|---|
| `SELECT number FROM invoice` | fails |
| `SELECT number AS n FROM invoice` | fails |
| `SELECT count(number) FROM invoice` | fails |
| `SELECT id FROM invoice WHERE number IS NULL` | fails |
| `SELECT id FROM invoice ORDER BY number` | fails |
| `SELECT id FROM invoice GROUP BY number` | fails |
| `DELETE FROM invoice WHERE number = ?` | fails |
| `UPDATE invoice SET number = ? WHERE number IS NULL` | fails |
| `SELECT i.number FROM invoice i` | parses |
| `SELECT "number" FROM invoice` | parses |
| `` SELECT `number` FROM invoice `` | parses |
| `INSERT INTO invoice (number) VALUES (?)` | parses |
| `UPDATE invoice SET number = ?` | parses |

So the token is accepted as an assignment target and inside an insert column list, but not as an
expression.

### Expected / Actual

**Expected:** `number` parses as a column name wherever a column reference is allowed. It is not a
reserved word in the SQL standard, nor in PostgreSQL, MySQL, MariaDB, H2 or SQL Server, and all of
them accept `CREATE TABLE invoice (number VARCHAR(32))` followed by `SELECT number FROM invoice`.

**Actual:** `net.sf.jsqlparser.parser.JSQLParserException` — the statement does not parse.

We assume the cause is the `NUMBER` type keyword used by `CAST(x AS NUMBER)` (Oracle), which is
also accepted today; the grammar seems to prefer the type token over an identifier in expression
position. Other names we checked in the same position all parse: `value`, `key`, `size`, `year`,
`date`, `level`, `position`, `type`, `state`, `language`, `domain`, `source`, `target`, `user`,
`comment`, `rank`, `percent`, `interval`, `action`, `method`, `amount`, `status`, `total`.

### Why this matters to QueryFence

[QueryFence](https://github.com/steelreed/queryfence) parses the SQL an application's integration
tests actually execute and checks it against a policy (for example: every statement touching
`invoice` must filter by `tenant_id`). What we cannot parse we cannot clear, so a statement that
does not parse is reported as a violation — fail closed. `invoice.number` is an ordinary column
name in billing schemas, so a correct, properly filtered statement is reported as unverifiable and
the build fails on a false positive.

### Environment

- JSqlParser 5.4 (also reproduced on 5.3)
- Java 17, macOS 15
- Dialects affected: all; we saw it on PostgreSQL 17 SQL written by hand through Spring's
  `JdbcTemplate`

### Workaround (for QueryFence users, until a fix ships)

Qualify the column (`i.number`) or quote it (`"number"`). Both parse today.
