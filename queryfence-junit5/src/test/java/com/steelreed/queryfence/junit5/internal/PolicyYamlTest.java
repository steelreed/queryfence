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
package com.steelreed.queryfence.junit5.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.steelreed.queryfence.core.Mode;
import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Rule;
import com.steelreed.queryfence.core.Suppression;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A policy nobody can load is worse than no policy, so every error names what is wrong. */
class PolicyYamlTest {

  @Test
  void readsRulesSuppressionsAndModes() {
    Policy policy =
        read(
            """
            version: 1
            mode: REPORT
            onUnparseable: REPORT
            rules:
              - id: tenant-isolation
                type: require-predicate
                column: tenant_id
                tables: [purchase_order, order_item]
                allowedFunctions: [current_setting]
              - id: no-unbounded-update
                type: update-without-where
              - id: no-unbounded-delete
                type: delete-without-where
            suppressions:
              - rule: tenant-isolation
                origin: com.acme.ReportJob#nightly
                reason: Cross-tenant report.
            """);

    assertThat(policy.mode()).isEqualTo(Mode.REPORT);
    assertThat(policy.onUnparseable()).isEqualTo(Mode.REPORT);
    assertThat(policy.rules())
        .extracting(Rule::id)
        .containsExactly("tenant-isolation", "no-unbounded-update", "no-unbounded-delete");
    assertThat(policy.suppressions())
        .containsExactly(
            new Suppression(
                "tenant-isolation", "com.acme.ReportJob#nightly", "Cross-tenant report."));
  }

  @Test
  void defaultsBothModesToFail() {
    Policy policy =
        read(
            """
            version: 1
            rules:
              - id: no-unbounded-delete
                type: delete-without-where
            """);

    assertThat(policy.mode()).isEqualTo(Mode.FAIL);
    assertThat(policy.onUnparseable()).isEqualTo(Mode.FAIL);
  }

  @Test
  void rejectsAnUnsupportedVersion() {
    assertThatThrownBy(() -> read("version: 2\nrules: []\n"))
        .hasMessageContaining("queryfence.yml declares version 2");
  }

  @Test
  void rejectsUnknownKeys() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rulez:
                      - id: x
                    """))
        .hasMessageContaining("unknown key 'rulez'");
  }

  @Test
  void rejectsAnUnknownRuleType() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: x
                        type: require-tenant
                    """))
        .hasMessageContaining("unknown type 'require-tenant'");
  }

  @Test
  void rejectsARequirePredicateRuleWithoutColumnOrTables() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: x
                        type: require-predicate
                        tables: [purchase_order]
                    """))
        .hasMessageContaining("rule 'x' has no 'column'");

    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: x
                        type: require-predicate
                        column: tenant_id
                    """))
        .hasMessageContaining("rule 'x' has no 'tables'");
  }

  @Test
  void rejectsASuppressionWithoutAReason() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: tenant
                        type: require-predicate
                        column: tenant_id
                        tables: [purchase_order]
                    suppressions:
                      - rule: tenant
                        origin: com.acme.Repo#find
                    """))
        .hasMessageContaining("a suppression has no 'reason'");
  }

  @Test
  void rejectsASuppressionOfAnUnknownRule() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: tenant
                        type: require-predicate
                        column: tenant_id
                        tables: [purchase_order]
                    suppressions:
                      - rule: typo
                        origin: com.acme.Repo#find
                        reason: because
                    """))
        .hasMessageContaining("unknown rule 'typo'");
  }

  @Test
  void rejectsAnEmptyOrRuleLessDocument() {
    assertThatThrownBy(() -> read("")).hasMessageContaining("queryfence.yml is empty");
    assertThatThrownBy(() -> read("version: 1\n")).hasMessageContaining("has no 'rules'");
    assertThatThrownBy(() -> read("version: 1\nrules: []\n"))
        .hasMessageContaining("declares no rule");
  }

  @Test
  void rejectsAnUnknownMode() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    mode: WARN
                    rules:
                      - id: x
                        type: delete-without-where
                    """))
        .hasMessageContaining("has mode: WARN; expected FAIL or REPORT");
  }

  @Test
  void readsTheBasePackagesThatMakeOriginsExact() {
    assertThat(
            parse(
                    """
                    version: 1
                    basePackages: [com.acme, com.northwind.shop]
                    rules:
                      - id: tenant-isolation
                        type: require-predicate
                        column: tenant_id
                        tables: [purchase_order]
                    """)
                .basePackages())
        .containsExactly("com.acme", "com.northwind.shop");
  }

  @Test
  void hasNoBasePackagesUnlessTheFileNamesThem() {
    assertThat(
            parse(
                    """
                    version: 1
                    rules:
                      - id: tenant-isolation
                        type: require-predicate
                        column: tenant_id
                        tables: [purchase_order]
                    """)
                .basePackages())
        .isEmpty();
  }

  @Test
  void namesThePolicyFileWhenThePolicyModelRejectsSomething() {
    assertThatThrownBy(
            () ->
                read(
                    """
                    version: 1
                    rules:
                      - id: tenant-isolation
                        type: require-predicate
                        column: tenant_id
                        tables: [purchase_order]
                    suppressions:
                      - rule: tenant-isolation
                        origin: com.acme.orders.OrderRepository
                        reason: An admin screen.
                    """))
        .hasMessageContaining("QueryFence policy queryfence.yml is invalid")
        .hasMessageContaining("Suppression origin must be Class#method");
  }

  private static Policy read(String yaml) {
    return parse(yaml).policy();
  }

  private static PolicyYaml.Parsed parse(String yaml) {
    return new PolicyYaml("queryfence.yml")
        .read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }
}
