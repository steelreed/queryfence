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

import com.steelreed.queryfence.junit5.PolicyFile;
import com.steelreed.queryfence.spring.QueryFencePolicy;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * Enables QueryFence in every Spring test context that has a policy on the classpath. Registered
 * through {@code META-INF/spring.factories}: adding the dependency and {@code queryfence.yml} is
 * the whole setup. {@link QueryFencePolicy} picks another policy file for a test class.
 */
public final class QueryFenceContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    QueryFencePolicy annotation =
        AnnotatedElementUtils.findMergedAnnotation(testClass, QueryFencePolicy.class);
    String resource = annotation == null ? PolicyFile.DEFAULT_RESOURCE : annotation.value();

    if (QueryFenceContextCustomizerFactory.class.getResource("/" + resource) == null) {
      if (annotation != null) {
        throw new IllegalStateException(
            "QueryFence policy "
                + resource
                + ", named by @QueryFencePolicy on "
                + testClass.getName()
                + ", was not found on the classpath");
      }
      return null; // no policy, nothing to check
    }
    return new QueryFenceContextCustomizer(
        resource, PolicyFile.fromClasspath(resource), PolicyFile.captureSettings(resource));
  }
}
