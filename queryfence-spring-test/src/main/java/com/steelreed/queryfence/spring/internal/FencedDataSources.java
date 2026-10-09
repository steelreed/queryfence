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
package com.steelreed.queryfence.spring.internal;

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.core.Suppression;
import com.steelreed.queryfence.jdbc.FencedDataSource;
import com.steelreed.queryfence.jdbc.QueryRecorder.Finding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** The wrapped data sources of one Spring test context. */
public final class FencedDataSources {

  /** Name of the singleton registered in the test application context. */
  public static final String BEAN_NAME = "queryFenceDataSources";

  private final Policy policy;
  private final String policyName;
  private final List<FencedDataSource> dataSources = new CopyOnWriteArrayList<>();

  FencedDataSources(Policy policy, String policyName) {
    this.policy = policy;
    this.policyName = policyName;
  }

  public Policy policy() {
    return policy;
  }

  /** How the report names this policy: the resource it was loaded from. */
  public String policyName() {
    return policyName;
  }

  void add(FencedDataSource dataSource) {
    dataSources.add(dataSource);
  }

  /** Forgets the statements recorded so far, so a test starts from an empty list. */
  public void clear() {
    dataSources.forEach(dataSource -> dataSource.recorder().clear());
  }

  public List<Finding> findings() {
    List<Finding> findings = new ArrayList<>();
    dataSources.forEach(dataSource -> findings.addAll(dataSource.recorder().findings()));
    return List.copyOf(findings);
  }

  /** Every suppression of the policy that has silenced a violation so far. */
  public Set<Suppression> matchedSuppressions() {
    Set<Suppression> matched = new LinkedHashSet<>();
    dataSources.forEach(dataSource -> matched.addAll(dataSource.recorder().matchedSuppressions()));
    return matched;
  }

  public int statementCount() {
    return dataSources.stream().mapToInt(d -> d.recorder().statements().size()).sum();
  }
}
