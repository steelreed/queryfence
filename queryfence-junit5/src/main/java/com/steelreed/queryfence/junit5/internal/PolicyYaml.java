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

import com.steelreed.queryfence.core.Mode;
import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.internal.RequirePredicateRule;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a {@code queryfence.yml} policy. Every problem is a configuration error reported with the
 * resource name and the offending key, because a policy nobody can load is worse than no policy.
 */
public final class PolicyYaml {

  private static final int SUPPORTED_VERSION = 1;
  private static final Set<String> ROOT_KEYS =
      Set.of("version", "mode", "onUnparseable", "basePackages", "rules", "suppressions");
  private static final Set<String> RULE_KEYS =
      Set.of("id", "type", "column", "tables", "allowedFunctions", "primaryKey");
  private static final Set<String> SUPPRESSION_KEYS = Set.of("rule", "origin", "reason");

  private final String resource;

  public PolicyYaml(String resource) {
    this.resource = resource;
  }

  /**
   * What one policy file declares: the policy itself, and the base packages the capture side uses
   * to resolve origins. The packages are not part of {@link Policy} on purpose — the engine knows
   * nothing about stack traces.
   *
   * @param policy the policy
   * @param basePackages the base packages, empty when the file names none
   */
  public record Parsed(Policy policy, List<String> basePackages) {}

  /**
   * Reads the policy, naming the file in every error. Errors raised by the policy model itself are
   * wrapped too: a mistake in a hand-written file must say which file it is in.
   */
  public Parsed read(InputStream in) {
    try {
      return parse(in);
    } catch (IllegalStateException alreadyNamesTheFile) {
      throw alreadyNamesTheFile;
    } catch (RuntimeException e) {
      throw invalid(e);
    }
  }

  private Parsed parse(InputStream in) {
    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    if (loaded == null) {
      throw error("is empty");
    }
    Map<String, Object> root = asMap(loaded, "the document");
    reject(root.keySet(), ROOT_KEYS, "the document");

    int version = intValue(root.get("version"), "version");
    if (version != SUPPORTED_VERSION) {
      throw error(
          "declares version " + version + "; only version " + SUPPORTED_VERSION + " exists");
    }

    Policy.Builder builder =
        Policy.builder()
            .mode(mode(root.get("mode"), "mode"))
            .onUnparseable(mode(root.get("onUnparseable"), "onUnparseable"));

    Object rules = root.get("rules");
    if (rules == null) {
      throw error("has no 'rules'");
    }
    List<?> ruleList = asList(rules, "rules");
    if (ruleList.isEmpty()) {
      throw error("declares no rule");
    }
    for (Object element : ruleList) {
      addRule(builder, asMap(element, "a rule"));
    }

    Object suppressions = root.get("suppressions");
    if (suppressions != null) {
      for (Object element : asList(suppressions, "suppressions")) {
        Map<String, Object> suppression = asMap(element, "a suppression");
        reject(suppression.keySet(), SUPPRESSION_KEYS, "a suppression");
        builder.suppress(
            required(suppression, "rule", "a suppression"),
            required(suppression, "origin", "a suppression"),
            required(suppression, "reason", "a suppression"));
      }
    }
    List<String> basePackages =
        root.get("basePackages") == null
            ? List.of()
            : strings(root.get("basePackages"), "basePackages");
    try {
      return new Parsed(builder.build(), basePackages);
    } catch (IllegalArgumentException e) {
      throw error(e.getMessage());
    }
  }

  private void addRule(Policy.Builder builder, Map<String, Object> rule) {
    reject(rule.keySet(), RULE_KEYS, "a rule");
    String id = required(rule, "id", "a rule");
    String type = required(rule, "type", "rule '" + id + "'");
    try {
      switch (type) {
        case "require-predicate" -> {
          Object tables = rule.get("tables");
          if (tables == null) {
            throw error("rule '" + id + "' has no 'tables'");
          }
          builder.requirePredicate(
              id,
              required(rule, "column", "rule '" + id + "'"),
              strings(tables, "tables of rule '" + id + "'"),
              rule.get("allowedFunctions") == null
                  ? List.of()
                  : strings(rule.get("allowedFunctions"), "allowedFunctions of rule '" + id + "'"),
              rule.get("primaryKey") == null
                  ? RequirePredicateRule.DEFAULT_PRIMARY_KEY
                  : required(rule, "primaryKey", "rule '" + id + "'"));
        }
        case "update-without-where" -> builder.updateWithoutWhere(id);
        case "delete-without-where" -> builder.deleteWithoutWhere(id);
        default ->
            throw error(
                "rule '"
                    + id
                    + "' has unknown type '"
                    + type
                    + "'; expected require-predicate, update-without-where or"
                    + " delete-without-where");
      }
    } catch (IllegalArgumentException e) {
      throw error(e.getMessage());
    }
  }

  private Mode mode(Object value, String key) {
    if (value == null) {
      return Mode.FAIL;
    }
    String text = String.valueOf(value);
    try {
      return Mode.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw error("has " + key + ": " + text + "; expected FAIL or REPORT");
    }
  }

  private String required(Map<String, Object> map, String key, String what) {
    Object value = map.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw error(what + " has no '" + key + "'");
    }
    return String.valueOf(value);
  }

  private int intValue(Object value, String key) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw error("has no numeric '" + key + "'");
  }

  private List<String> strings(Object value, String what) {
    List<?> list = asList(value, what);
    if (list.isEmpty()) {
      throw error(what + " is empty");
    }
    return list.stream().map(String::valueOf).toList();
  }

  private void reject(Set<String> actual, Set<String> allowed, String what) {
    for (String key : actual) {
      if (!allowed.contains(key)) {
        throw error("has unknown key '" + key + "' in " + what + "; expected one of " + allowed);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value, String what) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw error("expects a mapping for " + what);
  }

  private List<?> asList(Object value, String what) {
    if (value instanceof List<?> list) {
      return list;
    }
    throw error("expects a list for " + what);
  }

  private IllegalStateException error(String problem) {
    return new IllegalStateException("QueryFence policy " + resource + " " + problem);
  }

  private IllegalStateException invalid(RuntimeException cause) {
    String problem = cause.getMessage() == null ? cause.toString() : cause.getMessage();
    return new IllegalStateException(
        "QueryFence policy " + resource + " is invalid: " + problem, cause);
  }
}
