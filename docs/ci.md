# Running it in CI

QueryFence needs nothing special: it runs inside the tests you already run.

```yaml
- name: Build and test
  run: ./mvnw -B -ntp verify
```

A violation fails the test that executed the query, so the build fails where the problem is.

## The report

Every run writes `target/queryfence/report.json` (`build/queryfence/report.json` under Gradle),
grouped per policy. When the tests run in several JVMs, each JVM writes its own
`report-<start>-<pid>.json` next to it and `report.json` is their merge, so forks lose nothing;
see [the report files](configuration.md#the-report-files).

```json
{
  "generatedAt": "2026-09-24T09:12:03Z",
  "disabled": false,
  "disabledReasons": [],
  "policies": [
    {
      "policy": "queryfence.yml",
      "mode": "FAIL",
      "summary": { "tests": 48, "statements": 50, "findings": 1 },
      "findings": [
        {
          "test": "com.acme.order.OrderServiceTest#listsPendingOrders",
          "rule": "tenant-isolation",
          "code": "MISSING_PREDICATE",
          "table": "purchase_order",
          "alias": "o",
          "message": "purchase_order (o) has no tenant filter. Add …",
          "sql": "SELECT o.id FROM purchase_order o WHERE o.status = ?",
          "origin": { "class": "com.acme.order.OrderRepository", "method": "findByStatus",
                      "file": "OrderRepository.java", "line": 42 }
        }
      ]
    }
  ]
}
```

Keep it as a build artifact:

```yaml
- name: Upload the QueryFence report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: queryfence-report
    path: |
      **/target/queryfence/
      **/build/queryfence/
```

## Summarising it

The repository ships a small script with no dependencies:

```bash
python3 tools/queryfence-summary.py target/queryfence/report.json
python3 tools/queryfence-summary.py target/queryfence/report.json --triage
```

It groups findings by rule, table, code and origin, and `--triage` prints one entry per origin —
the list to work through when adopting QueryFence on an existing project.

## Do not switch it off to go green

`queryfence.enabled=false` fails the build when `CI` is set, unless someone also sets
`queryfence.allowDisabledInCi=true`. A pipeline that checks nothing should not look green.
