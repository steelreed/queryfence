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
package com.steelreed.queryfence.it.mybatis;

import com.acme.shop.mybatis.MyBatisApp;
import com.steelreed.queryfence.it.Databases;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** MyBatis against Postgres. */
@SpringBootTest(classes = MyBatisApp.class)
class MyBatisPostgresTest extends MyBatisTests {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    Databases.postgres(registry);
  }
}
