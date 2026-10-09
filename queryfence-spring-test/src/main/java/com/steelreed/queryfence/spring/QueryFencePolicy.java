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
package com.steelreed.queryfence.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Uses another policy file for this test class, instead of {@code queryfence.yml}.
 *
 * <pre>{@code
 * @SpringBootTest
 * @QueryFencePolicy("queryfence-legacy.yml")
 * class LegacyReportingTest { ... }
 * }</pre>
 *
 * <p>Tests that use different policies get different Spring contexts, because the policy is part of
 * the context cache key.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface QueryFencePolicy {

  /**
   * The policy resource on the test classpath.
   *
   * @return the resource name, for example {@code queryfence-legacy.yml}
   */
  String value();
}
