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
package com.steelreed.queryfence.jdbc.internal;

import com.steelreed.queryfence.jdbc.CaptureSettings;
import com.steelreed.queryfence.jdbc.Origin;
import java.lang.StackWalker.StackFrame;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Finds the application frame that produced a statement (DESIGN.md "Origin resolution"). */
public final class OriginResolver {

  /**
   * Frames that are never the origin: the JDK, the drivers, the ORMs, the frameworks and QueryFence
   * itself.
   */
  private static final List<String> INFRASTRUCTURE =
      List.of(
          "java.",
          "javax.sql.",
          "jakarta.persistence.",
          "jdk.",
          "sun.",
          "com.sun.",
          "org.h2.",
          "org.hsqldb.",
          "org.postgresql.",
          "com.mysql.",
          "org.mariadb.",
          "oracle.jdbc.",
          "com.microsoft.sqlserver.",
          "org.hibernate.",
          "jakarta.persistence.",
          "org.springframework.",
          "org.apache.ibatis.",
          "org.mybatis.",
          "com.baomidou.",
          "org.jooq.",
          "com.zaxxer.",
          "org.apache.commons.dbcp",
          "net.ttddyy.",
          "com.steelreed.queryfence.");

  private static final String LAMBDA_PREFIX = "lambda$";

  private static final StackWalker WALKER = StackWalker.getInstance();

  private final List<String> basePackages;

  public OriginResolver(CaptureSettings settings) {
    this.basePackages = settings.basePackages();
  }

  /** Walks the current stack and returns the first application frame. */
  public Origin resolve() {
    return WALKER.walk(this::firstApplicationFrame);
  }

  private Origin firstApplicationFrame(Stream<StackFrame> frames) {
    Optional<StackFrame> frame = frames.filter(this::isApplicationFrame).findFirst();
    return frame.map(OriginResolver::toOrigin).orElseGet(Origin::unknown);
  }

  private boolean isApplicationFrame(StackFrame frame) {
    String className = frame.getClassName();
    if (isGeneratedProxy(className)) {
      return false;
    }
    if (!basePackages.isEmpty()) {
      for (String basePackage : basePackages) {
        if (className.startsWith(basePackage)) {
          return true;
        }
      }
      return false;
    }
    for (String prefix : INFRASTRUCTURE) {
      if (className.startsWith(prefix)) {
        return false;
      }
    }
    return true;
  }

  /** Proxies and lambdas of the frameworks above carry no useful location. */
  private static boolean isGeneratedProxy(String className) {
    return className.contains("$$")
        || className.contains("$Proxy")
        || className.contains("$HibernateProxy");
  }

  private static Origin toOrigin(StackFrame frame) {
    return new Origin(
        frame.getClassName(),
        enclosingMethod(frame.getMethodName()),
        frame.getFileName(),
        frame.getLineNumber());
  }

  /**
   * The method a user would name. A lambda compiles to a synthetic method called {@code
   * lambda$theEnclosingMethod$0}, which is accurate and unreadable, so we report the enclosing
   * method instead; the line number already points inside the lambda.
   */
  private static String enclosingMethod(String methodName) {
    String name = methodName;
    while (name != null && name.startsWith(LAMBDA_PREFIX)) {
      String inner = name.substring(LAMBDA_PREFIX.length());
      int lastDollar = inner.lastIndexOf('$');
      if (lastDollar <= 0 || !isDigits(inner.substring(lastDollar + 1))) {
        return name;
      }
      name = inner.substring(0, lastDollar);
    }
    return name;
  }

  private static boolean isDigits(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (!Character.isDigit(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
