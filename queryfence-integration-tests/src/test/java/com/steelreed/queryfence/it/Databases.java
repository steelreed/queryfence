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
package com.steelreed.queryfence.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MySQL and one Postgres container for the whole run: starting a database per test class would
 * dominate the build time, and the tests never write conflicting data.
 */
public final class Databases {

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("shop")
          .withUsername("shop")
          .withPassword("shop");

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("shop")
          .withUsername("shop")
          .withPassword("shop");

  private Databases() {}

  /** Points Spring at MySQL, starting it on first use. */
  public static void mysql(DynamicPropertyRegistry registry) {
    register(registry, start(MYSQL));
  }

  /** Points Spring at Postgres, starting it on first use. */
  public static void postgres(DynamicPropertyRegistry registry) {
    register(registry, start(POSTGRES));
  }

  private static JdbcDatabaseContainer<?> start(JdbcDatabaseContainer<?> container) {
    if (!container.isRunning()) {
      container.start();
    }
    return container;
  }

  private static void register(DynamicPropertyRegistry registry, JdbcDatabaseContainer<?> db) {
    registry.add("spring.datasource.url", db::getJdbcUrl);
    registry.add("spring.datasource.username", db::getUsername);
    registry.add("spring.datasource.password", db::getPassword);
    registry.add("spring.datasource.driver-class-name", db::getDriverClassName);
  }
}
