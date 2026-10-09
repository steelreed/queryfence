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
package com.steelreed.queryfence.it.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.shop.jpa.JpaOrderService;
import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.it.FencedTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Hibernate through Spring Data JPA: nobody writes this SQL by hand, which is exactly why it is
 * worth checking. Each test says what QueryFence must find in the SQL Hibernate produced.
 */
abstract class HibernateTests extends FencedTestBase {

  @Autowired JpaOrderService service;

  @Test
  void acceptsADerivedQueryThatCarriesTheTenant() {
    service.derivedWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void catchesADerivedQueryWithoutTheTenant() {
    service.derivedWithoutTenant();

    assertCaught("purchase_order", "derivedWithoutTenant");
  }

  @Test
  void acceptsAPaginatedQueryThatCarriesTheTenant() {
    service.pagedWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void catchesAPaginatedQueryWithoutTheTenant() {
    service.pagedWithoutTenant();

    assertCaught("purchase_order", "pagedWithoutTenant");
  }

  @Test
  void acceptsJpqlThatCarriesTheTenant() {
    service.jpqlWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void catchesJpqlWithoutTheTenant() {
    service.jpqlWithoutTenant();

    assertCaught("purchase_order", "jpqlWithoutTenant");
  }

  @Test
  void acceptsACorrelatedSubqueryThatCarriesTheTenantOnBothLevels() {
    service.subqueryWithTenant(7L);

    assertNoFinding();
  }

  @Test
  void reportsTheChildTableOfAFetchJoin() {
    service.fetchJoin(7L);

    // Hibernate joins order_item with the foreign key only. The rows it returns do belong to the
    // fenced parent, but the statement does not say so, so QueryFence reports it (fail closed).
    assertThat(findings())
        .as("SQL was: %s", lastSql())
        .anySatisfy(finding -> assertThat(finding.violation().table()).isEqualTo("order_item"));
  }

  @Test
  void reportsChildrenLoadedByForeignKeyOnly() {
    service.countItemsOf(7L, 1L);

    // Same story for a lazy association: select ... from order_item where order_id = ?
    assertThat(findings())
        .as("SQL was: %s", lastSql())
        .anySatisfy(finding -> assertThat(finding.violation().table()).isEqualTo("order_item"));
  }

  @Test
  void catchesEntityManagerFindWhichOnlyKnowsThePrimaryKey() {
    service.loadById(1L);

    assertCaught(Violation.Code.PRIMARY_KEY_LOOKUP, "purchase_order", "loadById");
    assertThat(findings().get(0).violation().message())
        .contains("findByIdAndTenantId")
        .contains("@TenantId");
  }

  @Test
  void catchesFindByIdOfTheSpringDataRepository() {
    service.findById(2L);

    assertCaught(Violation.Code.PRIMARY_KEY_LOOKUP, "purchase_order", "findById");
  }
}
