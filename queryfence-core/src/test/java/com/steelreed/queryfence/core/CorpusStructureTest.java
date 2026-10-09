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
package com.steelreed.queryfence.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.steelreed.queryfence.core.Corpus.Case;
import com.steelreed.queryfence.core.Corpus.Expected;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The corpus must stay consistent with itself and with {@code docs/DESIGN.md}. */
class CorpusStructureTest {

  private static final Set<String> DIALECTS = Set.of("ansi", "mysql", "postgres");
  private static final Path DESIGN = Path.of("..", "docs", "DESIGN.md");

  private final List<Case> cases = Corpus.cases();

  @Test
  void caseIdsAreUniqueAndNamedAfterTheirFile() {
    Set<String> seen = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    List<String> misnamed = new ArrayList<>();
    for (Case c : cases) {
      if (!seen.add(c.id())) {
        duplicates.add(c.id());
      }
      String prefix = c.file().replace(".yml", "-");
      if (!c.id().startsWith(prefix) || !c.id().substring(prefix.length()).matches("\\d{3}")) {
        misnamed.add(c.id() + " in " + c.file());
      }
    }
    assertThat(duplicates).as("duplicate case ids").isEmpty();
    assertThat(misnamed).as("case ids must be <file>-<nnn>").isEmpty();
  }

  @Test
  void everyClauseExistsInDesignDocument() throws IOException {
    Set<String> defined = clausesDefinedInDesign();
    assertThat(defined).as("clauses parsed from %s", DESIGN).isNotEmpty();

    Set<String> unknown = new TreeSet<>();
    for (Case c : cases) {
      for (String clause : c.clause()) {
        if (!defined.contains(clause)) {
          unknown.add(clause + " (" + c.id() + ")");
        }
      }
    }
    assertThat(unknown).as("clauses that DESIGN.md does not define").isEmpty();
  }

  @Test
  void everyCaseDeclaresAKnownDialectAndPolicy() {
    Map<String, Policy> policies = Corpus.policies();
    for (Case c : cases) {
      assertThat(c.dialect()).as("dialect of %s", c.id()).isIn(DIALECTS);
      assertThat(policies).as("policy of %s", c.id()).containsKey(c.policy());
      assertThat(c.reason()).as("reason of %s", c.id()).isNotBlank();
      assertThat(c.clause()).as("clause of %s", c.id()).isNotEmpty();
    }
  }

  @Test
  void expectedViolationsUseKnownCodesAndCarryAFixHint() {
    for (Case c : cases) {
      for (Expected e : c.expect()) {
        assertThat(Violation.Code.valueOf(e.code())).as("code of %s", c.id()).isNotNull();
        assertThat(e.message()).as("message of %s", c.id()).isNotBlank();
        assertThat(e.message())
            .as("message of %s must say how to fix the violation", c.id())
            .containsAnyOf("Add ", "Replace ", "Qualify ", "Rewrite ", "Report ", "Filter ");
      }
    }
  }

  @Test
  void corpusCoversEveryViolationCodeAndDialect() {
    Set<String> codes = new HashSet<>();
    Set<String> dialects = new HashSet<>();
    for (Case c : cases) {
      dialects.add(c.dialect());
      c.expect().forEach(e -> codes.add(e.code()));
    }
    assertThat(codes)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                Violation.Code.MISSING_PREDICATE.name(),
                Violation.Code.PRIMARY_KEY_LOOKUP.name(),
                Violation.Code.AMBIGUOUS_COLUMN.name(),
                Violation.Code.MISSING_INSERT_COLUMN.name(),
                Violation.Code.NO_WHERE.name(),
                Violation.Code.TAUTOLOGICAL_WHERE.name(),
                Violation.Code.UNSUPPORTED_STATEMENT.name(),
                Violation.Code.UNPARSEABLE.name()));
    assertThat(dialects).containsExactlyInAnyOrderElementsOf(DIALECTS);
  }

  /** Clause ids written in DESIGN.md, expanding ranges such as {@code DW-1..DW-3}. */
  private static Set<String> clausesDefinedInDesign() throws IOException {
    String design = Files.readString(DESIGN);
    Set<String> defined = new TreeSet<>();
    Matcher ranges = Pattern.compile("\\b([A-Z]{2})-(\\d+)\\.\\.\\1-(\\d+)\\b").matcher(design);
    while (ranges.find()) {
      int from = Integer.parseInt(ranges.group(2));
      int to = Integer.parseInt(ranges.group(3));
      for (int i = from; i <= to; i++) {
        defined.add(ranges.group(1) + "-" + i);
      }
    }
    Matcher single = Pattern.compile("\\b([A-Z]{2})-(\\d+)\\b").matcher(design);
    while (single.find()) {
      defined.add(single.group());
    }
    return defined;
  }
}
