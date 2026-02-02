/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records connection state changes and replays them on new connections.
 * This ensures that all physical connections have consistent state settings
 * (auto-commit, transaction isolation, read-only, etc.).
 * <p>
 * Only the latest value for each setting type is kept to avoid redundant operations.
 * </p>
 */
public class MethodInvocationRecorder {

  /**
   * Represents a recorded method invocation that can be replayed.
   */
  @FunctionalInterface
  private interface Invocation {
    void replay(Connection connection) throws SQLException;
  }

  // Keys for different setting types
  private static final String KEY_AUTO_COMMIT = "autoCommit";
  private static final String KEY_TRANSACTION_ISOLATION = "transactionIsolation";
  private static final String KEY_READ_ONLY = "readOnly";
  private static final String KEY_CATALOG = "catalog";
  private static final String KEY_SCHEMA = "schema";
  private static final String KEY_HOLDABILITY = "holdability";
  private static final String KEY_NETWORK_TIMEOUT = "networkTimeout";
  private static final String KEY_CLIENT_INFO_PREFIX = "clientInfo:";

  // Use LinkedHashMap to preserve insertion order and keep only the latest value for each key
  private final Map<String, Invocation> invocations = new LinkedHashMap<>();

  /**
   * Records a setAutoCommit invocation.
   *
   * @param autoCommit the auto-commit value
   */
  public void recordSetAutoCommit(boolean autoCommit) {
    invocations.put(KEY_AUTO_COMMIT, conn -> conn.setAutoCommit(autoCommit));
  }

  /**
   * Records a setTransactionIsolation invocation.
   *
   * @param level the transaction isolation level
   */
  public void recordSetTransactionIsolation(int level) {
    invocations.put(KEY_TRANSACTION_ISOLATION, conn -> conn.setTransactionIsolation(level));
  }

  /**
   * Records a setReadOnly invocation.
   *
   * @param readOnly the read-only value
   */
  public void recordSetReadOnly(boolean readOnly) {
    invocations.put(KEY_READ_ONLY, conn -> conn.setReadOnly(readOnly));
  }

  /**
   * Records a setCatalog invocation.
   *
   * @param catalog the catalog name
   */
  public void recordSetCatalog(String catalog) {
    invocations.put(KEY_CATALOG, conn -> conn.setCatalog(catalog));
  }

  /**
   * Records a setSchema invocation.
   *
   * @param schema the schema name
   */
  public void recordSetSchema(String schema) {
    invocations.put(KEY_SCHEMA, conn -> conn.setSchema(schema));
  }

  /**
   * Records a setClientInfo invocation.
   * Each client info property is tracked separately.
   *
   * @param name the property name
   * @param value the property value
   */
  public void recordSetClientInfo(String name, String value) {
    invocations.put(KEY_CLIENT_INFO_PREFIX + name, conn -> conn.setClientInfo(name, value));
  }

  /**
   * Records a setHoldability invocation.
   *
   * @param holdability the result set holdability
   */
  public void recordSetHoldability(int holdability) {
    invocations.put(KEY_HOLDABILITY, conn -> conn.setHoldability(holdability));
  }

  /**
   * Records a setNetworkTimeout invocation.
   *
   * @param executor the executor to use for async operations
   * @param milliseconds the network timeout in milliseconds
   */
  public void recordSetNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) {
    invocations.put(KEY_NETWORK_TIMEOUT, conn -> conn.setNetworkTimeout(executor, milliseconds));
  }

  /**
   * Replays all recorded invocations on the given connection.
   *
   * @param connection the connection to replay invocations on
   * @throws SQLException if any invocation fails
   */
  public void replayOn(Connection connection) throws SQLException {
    for (Invocation invocation : invocations.values()) {
      invocation.replay(connection);
    }
  }

  /**
   * Clears all recorded invocations.
   */
  public void clear() {
    invocations.clear();
  }

  /**
   * Gets the number of recorded invocations.
   *
   * @return the number of recorded invocations
   */
  public int size() {
    return invocations.size();
  }
}
