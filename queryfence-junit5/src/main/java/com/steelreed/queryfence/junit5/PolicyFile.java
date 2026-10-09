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
package com.steelreed.queryfence.junit5;

import com.steelreed.queryfence.core.Policy;
import com.steelreed.queryfence.jdbc.CaptureSettings;
import com.steelreed.queryfence.junit5.internal.PolicyYaml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a policy from the test classpath.
 *
 * <p>A file is read once per JVM: a suite with a hundred test classes parses {@code queryfence.yml}
 * once, and an invalid file is diagnosed once instead of once per test class.
 */
public final class PolicyFile {

  /** Where the policy is read from unless another resource is named. */
  public static final String DEFAULT_RESOURCE = "queryfence.yml";

  /** Per resource: the parsed file, or the failure that reading it produced. */
  private static final Map<String, Object> LOADED = new ConcurrentHashMap<>();

  private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

  private PolicyFile() {}

  /**
   * Reads {@value #DEFAULT_RESOURCE} from the classpath.
   *
   * @return the policy it declares
   * @throws IllegalStateException when the resource is missing or the policy is invalid
   */
  public static Policy fromClasspath() {
    return fromClasspath(DEFAULT_RESOURCE);
  }

  /**
   * Reads a policy from the classpath.
   *
   * @param resource the resource path, with or without a leading slash
   * @return the policy it declares
   * @throws IllegalStateException when the resource is missing or the policy is invalid
   */
  public static Policy fromClasspath(String resource) {
    return parsed(resource).policy();
  }

  /**
   * The capture settings {@value #DEFAULT_RESOURCE} declares through {@code basePackages}.
   *
   * @return the settings, or {@link CaptureSettings#defaults()} when the file names no package
   * @throws IllegalStateException when the resource is missing or the policy is invalid
   */
  public static CaptureSettings captureSettings() {
    return captureSettings(DEFAULT_RESOURCE);
  }

  /**
   * The capture settings a policy file declares through {@code basePackages}. Origins are resolved
   * inside those packages only, which makes them exact in an application whose framework layers
   * QueryFence does not know.
   *
   * @param resource the resource path, with or without a leading slash
   * @return the settings, or {@link CaptureSettings#defaults()} when the file names no package
   * @throws IllegalStateException when the resource is missing or the policy is invalid
   */
  public static CaptureSettings captureSettings(String resource) {
    List<String> basePackages = parsed(resource).basePackages();
    return basePackages.isEmpty()
        ? CaptureSettings.defaults()
        : CaptureSettings.ofBasePackages(basePackages.toArray(new String[0]));
  }

  private static PolicyYaml.Parsed parsed(String resource) {
    Objects.requireNonNull(resource, "resource");
    Object outcome = LOADED.computeIfAbsent(resource, PolicyFile::load);
    if (outcome instanceof RuntimeException failure) {
      throw failure;
    }
    return (PolicyYaml.Parsed) outcome;
  }

  private static Object load(String resource) {
    String path = resource.startsWith("/") ? resource : "/" + resource;
    try (InputStream in = PolicyFile.class.getResourceAsStream(path)) {
      if (in == null) {
        return announce(
            new IllegalStateException(
                "QueryFence policy " + resource + " was not found on the classpath"));
      }
      return new PolicyYaml(resource).read(in);
    } catch (IOException e) {
      return announce(new UncheckedIOException("Could not read QueryFence policy " + resource, e));
    } catch (RuntimeException e) {
      return announce(e);
    }
  }

  /** Says once, loudly, what is wrong; the tests that need the policy then fail as usual. */
  private static RuntimeException announce(RuntimeException failure) {
    String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
    if (ANNOUNCED.add(message)) {
      System.err.println(
          "\nQueryFence could not load its policy:\n  "
              + message
              + "\nEvery test that needs the policy fails until the file is valid.\n");
    }
    return failure;
  }

  /** Forgets what was loaded; for QueryFence's own tests, which write policy files as they go. */
  public static void clearCache() {
    LOADED.clear();
    ANNOUNCED.clear();
  }
}
