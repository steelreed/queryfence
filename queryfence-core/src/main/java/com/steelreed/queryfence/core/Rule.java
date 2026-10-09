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

/**
 * A rule of a {@link Policy}. Rules are created through {@link Policy.Builder}; this interface only
 * exposes what reports need.
 */
public interface Rule {

  /**
   * The rule id declared in the policy.
   *
   * @return the id, for example {@code tenant-isolation}
   */
  String id();

  /**
   * The kind of rule this is.
   *
   * @return {@code require-predicate}, {@code update-without-where} or {@code delete-without-where}
   */
  String type();
}
