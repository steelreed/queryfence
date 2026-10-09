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

import com.steelreed.queryfence.jdbc.FencedDataSource;
import com.steelreed.queryfence.jdbc.QueryRecorder;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Adds {@link FencedDataSource} to the datasource-proxy data source that does the capturing. */
public final class DelegatingFencedDataSource implements FencedDataSource {

  private final DataSource proxy;
  private final DataSource delegate;
  private final QueryRecorder recorder;

  public DelegatingFencedDataSource(DataSource proxy, DataSource delegate, QueryRecorder recorder) {
    this.proxy = Objects.requireNonNull(proxy, "proxy");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  @Override
  public QueryRecorder recorder() {
    return recorder;
  }

  @Override
  public DataSource delegate() {
    return delegate;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return proxy.getConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return proxy.getConnection(username, password);
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return proxy.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    proxy.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    proxy.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return proxy.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return proxy.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    return proxy.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this) || proxy.isWrapperFor(iface);
  }
}
