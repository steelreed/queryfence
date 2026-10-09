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
package com.steelreed.queryfence.report.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Says out loud that QueryFence is switched off.
 *
 * <p>Turning the checks off is an emergency exit, and an emergency exit nobody notices is how a
 * project ends up unprotected for months. So switching off is loud on the console, recorded in the
 * report, and on CI it fails the build unless someone deliberately allowed it.
 */
public final class Disabled {

  /** The property that switches QueryFence off. */
  public static final String PROPERTY = "queryfence.enabled";

  /** The property that allows a switched-off run on CI. */
  public static final String ALLOW_IN_CI = "queryfence.allowDisabledInCi";

  private static final ConcurrentMap<String, Boolean> announced = new ConcurrentHashMap<>();

  private Disabled() {}

  /**
   * Announces that QueryFence is off for {@code where}: prints the warning once, records it in the
   * report, and fails on CI.
   *
   * @param allowedInCi value of {@value #ALLOW_IN_CI} in the context that switched QueryFence off
   * @throws IllegalStateException on CI when the run was not explicitly allowed to be unprotected
   */
  public static void announce(String where, boolean allowedInCi) {
    announce(where, allowedInCi, System.getenv("CI"));
  }

  /** Same, with the CI marker passed in, so the behaviour can be tested. */
  static void announce(String where, boolean allowedInCi, String ci) {
    String reason = PROPERTY + "=false for " + where;
    RunReport.instance().disabled(reason);
    if (announced.putIfAbsent(where, Boolean.TRUE) == null) {
      System.err.println(warning(where));
    }
    if (ci != null && !ci.isBlank() && !allowedInCi) {
      throw new IllegalStateException(
          "QueryFence is disabled ("
              + reason
              + ") but CI="
              + ci
              + ". A pipeline that checks nothing should not look green:"
              + " set "
              + ALLOW_IN_CI
              + "=true to state that this is deliberate, or better, keep QueryFence on and"
              + " suppress the single query you need to accept, with a reason.");
    }
  }

  private static String warning(String where) {
    String line = "=".repeat(78);
    return "\n"
        + line
        + "\nQueryFence is disabled ("
        + PROPERTY
        + "=false) for "
        + where
        + "."
        + "\nNo SQL policy is checked in this run: tenant leaks will not fail the build."
        + "\nThis is an emergency exit. To accept a single known query instead, add a"
        + "\nsuppression with a reason to the policy file.\n"
        + line
        + "\n";
  }

  /** For QueryFence's own tests. */
  public static void reset() {
    announced.clear();
  }
}
