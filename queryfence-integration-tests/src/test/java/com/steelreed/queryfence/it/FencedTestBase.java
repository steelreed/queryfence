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
package com.steelreed.queryfence.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.steelreed.queryfence.core.Violation;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import com.steelreed.queryfence.spring.internal.FencedDataSources;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared assertions. The policy runs in REPORT mode, so a deliberate leak does not fail the test
 * that executes it; each test states what QueryFence must have found.
 */
public abstract class FencedTestBase {

  @Autowired protected FencedDataSources fences;

  @BeforeEach
  void forgetPreviousStatements() {
    fences.clear();
  }

  /** The statements executed since the last {@link #forgetPreviousStatements()}. */
  protected List<Finding> findings() {
    return fences.findings();
  }

  /** Asserts that the last query was accepted: no finding at all. */
  protected void assertNoFinding() {
    assertThat(findings())
        .as("QueryFence must accept this query; a finding here is a false positive")
        .isEmpty();
  }

  /** Asserts that the leak was caught with {@code code}, and says which method wrote the query. */
  protected void assertCaught(Violation.Code code, String table, String originMethod) {
    assertThat(findings())
        .as("QueryFence must catch this leak")
        .isNotEmpty()
        .anySatisfy(
            finding -> {
              assertThat(finding.violation().code()).isEqualTo(code);
              assertThat(finding.violation().table()).isEqualTo(table);
              assertThat(finding.origin().methodName()).isEqualTo(originMethod);
            });
  }

  /** Asserts that the leak was caught on {@code table}, and says which method wrote the query. */
  protected void assertCaught(String table, String originMethod) {
    assertThat(findings())
        .as("QueryFence must catch this leak")
        .isNotEmpty()
        .anySatisfy(
            finding -> {
              assertThat(finding.violation().code()).isEqualTo(Violation.Code.MISSING_PREDICATE);
              assertThat(finding.violation().table()).isEqualTo(table);
              assertThat(finding.origin().methodName()).isEqualTo(originMethod);
            });
  }

  /** The SQL the framework actually sent, for the assertions that inspect its shape. */
  protected String lastSql() {
    List<Finding> found = findings();
    assertThat(found).isNotEmpty();
    return found.get(found.size() - 1).statement().sql();
  }
}
