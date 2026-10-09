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
import com.steelreed.queryfence.jdbc.CaptureSettings;
import com.steelreed.queryfence.jdbc.FencedDataSource;
import com.steelreed.queryfence.jdbc.QueryFence;
import com.steelreed.queryfence.report.internal.Disabled;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/** Wraps every {@code DataSource} bean of a test context, so no test has to call wrap(). */
final class QueryFenceContextCustomizer implements ContextCustomizer {

  private final String resource;
  private final Policy policy;
  private final CaptureSettings captureSettings;

  QueryFenceContextCustomizer(String resource, Policy policy, CaptureSettings captureSettings) {
    this.resource = resource;
    this.policy = policy;
    this.captureSettings = captureSettings;
  }

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    var environment = context.getEnvironment();
    if (!environment.getProperty(Disabled.PROPERTY, Boolean.class, Boolean.TRUE)) {
      Disabled.announce(
          "the Spring test context of " + resource,
          environment.getProperty(Disabled.ALLOW_IN_CI, Boolean.class, Boolean.FALSE));
      return;
    }
    FencedDataSources dataSources = new FencedDataSources(policy, resource);
    context.getBeanFactory().registerSingleton(FencedDataSources.BEAN_NAME, dataSources);
    context.getBeanFactory().addBeanPostProcessor(new WrappingBeanPostProcessor(dataSources));
  }

  /**
   * Two customizers are equal when they carry the same policy resource, so Spring keeps caching
   * contexts across test classes.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof QueryFenceContextCustomizer customizer
        && resource.equals(customizer.resource);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resource);
  }

  private final class WrappingBeanPostProcessor implements BeanPostProcessor {

    private final FencedDataSources dataSources;

    private WrappingBeanPostProcessor(FencedDataSources dataSources) {
      this.dataSources = dataSources;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (!(bean instanceof DataSource dataSource) || bean instanceof FencedDataSource) {
        return bean;
      }
      FencedDataSource fenced = QueryFence.wrap(dataSource, policy, captureSettings);
      dataSources.add(fenced);
      return fenced;
    }
  }
}
