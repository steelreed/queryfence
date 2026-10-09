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
package com.steelreed.queryfence.it.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.shop.mybatis.MyBatisOrderService;
import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.it.FencedTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MyBatis dynamic SQL: the same mapper method produces a different statement for every set of
 * arguments, so only the statement that was actually sent can be checked.
 */
abstract class MyBatisTests extends FencedTestBase {

  @Autowired MyBatisOrderService service;

  @Test
  void acceptsADynamicQueryThatIncludesTheTenantCondition() {
    service.searchWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void catchesTheSameMapperMethodCalledWithoutATenant() {
    service.searchWithoutTenant();

    assertCaught("purchase_order", "searchWithoutTenant");
  }

  @Test
  void acceptsADynamicQueryWhereOnlyTheTenantConditionRemains() {
    service.searchWithTenantOnly(7L);

    assertNoFinding();
  }

  @Test
  void acceptsAJoinWhereBothTablesAreFenced() {
    service.joinedWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void catchesAJoinedTableWithoutTheTenantFilter() {
    service.joinedWithoutItemTenant(7L);

    assertCaught("order_item", "joinedWithoutItemTenant");
  }

  @Test
  void acceptsAnUpdateThatKeepsItsTenantCondition() {
    service.closeWithTenant(7L, List.of(1L));

    assertNoFinding();
  }

  @Test
  void catchesAnUpdateWhoseDynamicWhereDisappeared() {
    service.closeEverything();

    assertThat(findings())
        .extracting(finding -> finding.violation().code())
        .contains(Violation.Code.MISSING_PREDICATE, Violation.Code.NO_WHERE);
  }
}
