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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads the golden corpus; see {@code src/test/resources/corpus/README.md} for the format. */
final class Corpus {

  static final String POLICIES = "policies.yml";

  record Expected(String rule, String code, String table, String alias, String message) {}

  record Case(
      String id,
      String file,
      String sql,
      String policy,
      String dialect,
      List<Expected> expect,
      String reason,
      List<String> clause) {}

  private Corpus() {}

  static List<Case> cases() {
    try {
      Path dir = Path.of(Corpus.class.getResource("/corpus").toURI());
      List<Case> cases = new ArrayList<>();
      try (Stream<Path> files = Files.list(dir)) {
        for (Path file :
            files
                .filter(p -> p.getFileName().toString().endsWith(".yml"))
                .filter(p -> !p.getFileName().toString().equals(POLICIES))
                .sorted()
                .toList()) {
          cases.addAll(read(file.getFileName().toString()));
        }
      }
      return List.copyOf(cases);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The named policies of {@code policies.yml}, in declaration order. */
  static Map<String, Policy> policies() {
    Map<String, Policy> policies = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : load("/corpus/" + POLICIES).entrySet()) {
      policies.put(entry.getKey(), toPolicy(asMap(entry.getValue())));
    }
    return policies;
  }

  /** Minimal mapping of the policy schema; the real YAML loader lives in queryfence-junit5. */
  private static Policy toPolicy(Map<String, Object> map) {
    Policy.Builder builder = Policy.builder();
    builder.mode(Mode.valueOf((String) map.getOrDefault("mode", "FAIL")));
    builder.onUnparseable(Mode.valueOf((String) map.getOrDefault("onUnparseable", "FAIL")));
    for (Object o : (List<?>) map.get("rules")) {
      Map<String, Object> rule = asMap(o);
      String id = (String) rule.get("id");
      switch ((String) rule.get("type")) {
        case "require-predicate" ->
            builder.requirePredicate(
                id,
                (String) rule.get("column"),
                strings(rule.get("tables")),
                strings(rule.getOrDefault("allowedFunctions", List.of())),
                (String) rule.getOrDefault("primaryKey", "id"));
        case "update-without-where" -> builder.updateWithoutWhere(id);
        case "delete-without-where" -> builder.deleteWithoutWhere(id);
        default -> throw new IllegalArgumentException("Unknown rule type in " + rule);
      }
    }
    return builder.build();
  }

  private static List<Case> read(String file) {
    Map<String, Object> root = load("/corpus/" + file);
    List<Case> cases = new ArrayList<>();
    for (Object o : (List<?>) root.get("cases")) {
      Map<String, Object> c = asMap(o);
      List<Expected> expect = new ArrayList<>();
      for (Object e : (List<?>) c.get("expect")) {
        Map<String, Object> m = asMap(e);
        expect.add(
            new Expected(
                (String) m.get("rule"),
                (String) m.get("code"),
                (String) m.get("table"),
                (String) m.get("alias"),
                (String) m.get("message")));
      }
      cases.add(
          new Case(
              (String) c.get("id"),
              file,
              (String) c.get("sql"),
              (String) c.get("policy"),
              (String) c.get("dialect"),
              expect,
              (String) c.get("reason"),
              strings(c.get("clause"))));
    }
    return cases;
  }

  private static Map<String, Object> load(String resource) {
    try (InputStream in = Corpus.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing corpus resource " + resource);
      }
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }

  private static List<String> strings(Object o) {
    return ((List<?>) o).stream().map(String::valueOf).toList();
  }
}
