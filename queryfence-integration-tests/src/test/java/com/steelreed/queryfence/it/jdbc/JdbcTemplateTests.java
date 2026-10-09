/*
 * Copyright 2026 the QueryFence authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.steelreed.queryfence.it.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.shop.jdbc.JdbcOrderDao;
import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.it.FencedTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** JdbcTemplate: hand-written SQL on MySQL and Postgres. */
abstract class JdbcTemplateTests extends FencedTestBase {

  @Autowired JdbcOrderDao dao;

  @Test
  void acceptsAQueryFilteredByTenant() {
    dao.byTenantAndStatus(7L, "OPEN");

    assertNoFinding();
  }

  @Test
  void catchesAQueryWithoutTheTenantFilter() {
    dao.byStatus(7L, "OPEN");

    assertCaught("purchase_order", "byStatus");
  }

  @Test
  void acceptsAJoinWhereBothTablesAreFenced() {
    dao.joinItems(7L);

    assertNoFinding();
  }

  @Test
  void catchesAJoinedTableWithoutTheTenantFilter() {
    dao.joinItemsWithoutTenant(7L);

    assertCaught("order_item", "joinItemsWithoutTenant");
  }

  @Test
  void acceptsAnUpdateFilteredByTenant() {
    dao.closeOrder(7L, 1L);

    assertNoFinding();
  }

  @Test
  void catchesAnUnboundedUpdate() {
    dao.closeEverything();

    assertThat(findings())
        .extracting(finding -> finding.violation().code())
        .contains(Violation.Code.MISSING_PREDICATE, Violation.Code.NO_WHERE);
  }
}
