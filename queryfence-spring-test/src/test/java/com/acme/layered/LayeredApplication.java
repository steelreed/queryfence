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
package com.acme.layered;

import com.acme.shop.OrderRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * An application that layers its data sources the way production code often does: a lazy proxy over
 * a routing data source over a Hikari pool, every one of them a bean.
 */
@SpringBootApplication
public class LayeredApplication {

  @Bean
  HikariDataSource pool() {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:h2:mem:layered;DB_CLOSE_DELAY=-1");
    pool.setUsername("sa");
    return pool;
  }

  @Bean
  TenantRouting routing(HikariDataSource pool) {
    TenantRouting routing = new TenantRouting();
    routing.setTargetDataSources(Map.of("only", pool));
    return routing;
  }

  @Bean
  @Primary
  DataSource dataSource(TenantRouting routing) {
    return new LazyConnectionDataSourceProxy(routing);
  }

  @Bean
  OrderRepository orderRepository(DataSource dataSource) {
    return new OrderRepository(new JdbcTemplate(dataSource));
  }

  /** Routes every connection to the single pool; real applications route by tenant or replica. */
  public static class TenantRouting extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
      return "only";
    }
  }
}
