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
package com.steelreed.queryfence.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Identifier normalization (DESIGN.md RP-12). */
class NamesTest {

  @ParameterizedTest
  @CsvSource({
    "purchase_order, purchase_order",
    "PURCHASE_ORDER, purchase_order",
    "'\"Purchase_Order\"', purchase_order",
    "'`Purchase_Order`', purchase_order",
    "'[Purchase_Order]', purchase_order",
    "'  purchase_order  ', purchase_order",
  })
  void normalizeRemovesQuotesAndLowersCase(String input, String expected) {
    assertThat(Names.normalize(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "'\"Purchase_Order\"', Purchase_Order",
    "'`o`', o",
    "'[o]', o",
    "PurchaseOrder, PurchaseOrder",
  })
  void unquoteKeepsTheCase(String input, String expected) {
    assertThat(Names.unquote(input)).isEqualTo(expected);
  }

  @Test
  void keepsUnbalancedOrTooShortIdentifiersAsTheyAre() {
    assertThat(Names.normalize("\"Order")).isEqualTo("\"order");
    assertThat(Names.unquote("\"Order")).isEqualTo("\"Order");
    assertThat(Names.normalize("[Order")).isEqualTo("[order");
    assertThat(Names.unquote("`")).isEqualTo("`");
    assertThat(Names.normalize("o")).isEqualTo("o");
  }

  @Test
  void passesNullThrough() {
    assertThat(Names.normalize(null)).isNull();
    assertThat(Names.unquote(null)).isNull();
  }
}
