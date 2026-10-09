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

import com.steelreed.queryfence.jdbc.FencedDataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.target.EmptyTargetSource;

/**
 * Turns a {@code DataSource} bean into a fenced bean <em>of the same class</em>.
 *
 * <p>Replacing a {@code HikariDataSource} bean with a plain {@link FencedDataSource} breaks every
 * injection point that asks for the concrete type ({@code @Autowired HikariDataSource}). So the
 * bean is replaced by a class-based (CGLIB) proxy that extends the bean's own class and also
 * implements {@link FencedDataSource}: {@code getConnection} goes through the capturing data
 * source, {@link FencedDataSource#recorder()} and {@link FencedDataSource#delegate()} answer from
 * it, and every other method (pool settings, {@code close}, metrics) reaches the original bean
 * unchanged.
 *
 * <p>A class that cannot be subclassed safely (final class, final {@code getConnection}, a JDK
 * proxy or an existing Spring AOP proxy) keeps the plain {@link FencedDataSource}: it is still
 * fenced, it only cannot be injected by its concrete type.
 */
final class FencedBeans {

  private FencedBeans() {}

  /**
   * Returns the bean to register in place of {@code original}.
   *
   * @param original the data source bean as the application created it
   * @param fenced the capturing data source wrapping {@code original}
   * @return a proxy of the bean's class when possible, otherwise {@code fenced} itself
   */
  static FencedDataSource replace(DataSource original, FencedDataSource fenced) {
    if (!canKeepTheClass(original.getClass())) {
      return fenced;
    }
    try {
      // No AOP target: the advice calls the bean itself. With the bean as target, Spring would
      // swap a returned bean for the proxy, and delegate() would no longer return the original.
      ProxyFactory factory = new ProxyFactory();
      factory.setTargetSource(EmptyTargetSource.forClass(original.getClass()));
      factory.setProxyTargetClass(true);
      factory.setOpaque(true); // nothing may unwrap the proxy and bypass the capture
      factory.addInterface(FencedDataSource.class);
      factory.addAdvice(new Fence(original, fenced));
      return (FencedDataSource) factory.getProxy(original.getClass().getClassLoader());
    } catch (RuntimeException | LinkageError e) {
      return fenced;
    }
  }

  static boolean canKeepTheClass(Class<?> type) {
    if (Modifier.isFinal(type.getModifiers())
        || Proxy.isProxyClass(type)
        || Advised.class.isAssignableFrom(type)) {
      return false;
    }
    try {
      return !Modifier.isFinal(type.getMethod("getConnection").getModifiers())
          && !Modifier.isFinal(
              type.getMethod("getConnection", String.class, String.class).getModifiers());
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  /** Sends connections through the capturing data source, everything else to the bean. */
  private static final class Fence implements MethodInterceptor {

    private final DataSource original;
    private final FencedDataSource fenced;

    private Fence(DataSource original, FencedDataSource fenced) {
      this.original = original;
      this.fenced = fenced;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
      Object[] args = invocation.getArguments();
      String name = invocation.getMethod().getName();
      if (name.equals("getConnection") && args.length == 0) {
        return fenced.getConnection();
      }
      if (name.equals("getConnection")
          && args.length == 2
          && invocation.getMethod().getParameterTypes()[0] == String.class
          && invocation.getMethod().getParameterTypes()[1] == String.class) {
        return fenced.getConnection((String) args[0], (String) args[1]);
      }
      if (name.equals("recorder") && args.length == 0) {
        return fenced.recorder();
      }
      if (name.equals("delegate") && args.length == 0) {
        return fenced.delegate();
      }
      // unwrap must not hand out the bare bean: code using it would bypass the capture
      Object proxy = ((ProxyMethodInvocation) invocation).getProxy();
      if (args.length == 1 && args[0] instanceof Class<?> type && type.isInstance(proxy)) {
        if (name.equals("isWrapperFor")) {
          return true;
        }
        if (name.equals("unwrap")) {
          return proxy;
        }
      }
      return AopUtils.invokeJoinpointUsingReflection(original, invocation.getMethod(), args);
    }
  }
}
