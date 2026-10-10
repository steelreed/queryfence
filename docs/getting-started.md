# Getting started

Two steps for a Spring Boot project: add a dependency, write a policy. There is no annotation to
add and no test code to change.

## 1. Add the dependency

=== "Spring Boot"

    ```xml
    <dependency>
      <groupId>com.steelreed</groupId>
      <artifactId>queryfence-spring-test</artifactId>
      <version>0.1.0</version>
      <scope>test</scope>
    </dependency>
    ```

=== "Plain JUnit 5"

    ```xml
    <dependency>
      <groupId>com.steelreed</groupId>
      <artifactId>queryfence-junit5</artifactId>
      <version>0.1.0</version>
      <scope>test</scope>
    </dependency>
    ```

!!! note "Unreleased changes"
    To try a change that is not released yet, clone the repository and run `./mvnw install`, which
    puts the current `-SNAPSHOT` version in your local repository.

## 2. Declare the policy

`src/test/resources/queryfence.yml`:

```yaml
version: 1
mode: FAIL               # FAIL the test, or REPORT only
onUnparseable: FAIL      # SQL we cannot parse is a violation; REPORT downgrades those only

rules:
  - id: tenant-isolation
    type: require-predicate
    column: tenant_id
    tables: [purchase_order, order_item, invoice]

  - id: no-unbounded-update
    type: update-without-where

  - id: no-unbounded-delete
    type: delete-without-where
```

## 3. Run your tests

=== "Spring Boot"

    Your tests stay exactly as they are:

    ```java
    @SpringBootTest
    class OrderServiceTest {

      @Autowired OrderService service;

      @Test
      void listsPendingOrders() {
        assertThat(service.pendingOrders(TENANT_A)).hasSize(2);
      }
    }
    ```

    Every `DataSource` bean of the test context is wrapped automatically.

=== "Plain JUnit 5"

    ```java
    class OrderRepositoryTest {

      @RegisterExtension
      static final QueryFenceExtension queryFence = QueryFenceExtension.fromClasspath();

      OrderRepository repository;

      @BeforeEach
      void setUp() {
        DataSource dataSource = queryFence.wrap(TestDatabase.dataSource());
        repository = new OrderRepository(new JdbcTemplate(dataSource));
      }
    }
    ```

Only the statements of the test method — and of everything it calls, on any thread — are checked.
Fixtures in `@BeforeEach`, context startup and migrations run outside that window.

## Adding it to a project that already exists

Start in `REPORT` mode and read the report before you make anything fail: see
[Adopting in an existing project](ADOPTION.md).
