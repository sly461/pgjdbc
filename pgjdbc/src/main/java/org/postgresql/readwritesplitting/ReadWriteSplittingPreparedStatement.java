/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

/**
 * PreparedStatement wrapper that routes SQL execution to the appropriate connection
 * (master or replica) based on SQL type determined at prepare time.
 */
public class ReadWriteSplittingPreparedStatement implements PreparedStatement {

  private final PgConnectionManager connectionManager;
  private final SqlRouteEngine routeEngine;
  private final ReadWriteSplittingConnection parentConnection;
  private final String sql;

  // The routed connection and prepared statement (determined at prepare time)
  private Connection routedConnection;
  private PreparedStatement delegateStatement;

  /**
   * Creates a new read-write splitting prepared statement.
   *
   * @param connectionManager the connection manager
   * @param routeEngine the SQL route engine
   * @param parentConnection the parent connection
   * @param sql the SQL statement to prepare
   * @throws SQLException if routing or preparation fails
   */
  public ReadWriteSplittingPreparedStatement(
      PgConnectionManager connectionManager,
      SqlRouteEngine routeEngine,
      ReadWriteSplittingConnection parentConnection,
      String sql) throws SQLException {
    this.connectionManager = connectionManager;
    this.routeEngine = routeEngine;
    this.parentConnection = parentConnection;
    this.sql = sql;

    // Route at prepare time
    routeAndPrepare();
  }

  /**
   * Routes the SQL and prepares the statement on the appropriate connection.
   *
   * @throws SQLException if routing or preparation fails
   */
  private void routeAndPrepare() throws SQLException {
    RouteTarget target = routeEngine.route(
        sql,
        parentConnection.getAutoCommit(),
        parentConnection.getTargetServerType());

    if (target == RouteTarget.MASTER) {
      routedConnection = connectionManager.getMasterConnection();
    } else {
      routedConnection = connectionManager.getReplicaConnection();
    }

    delegateStatement = routedConnection.prepareStatement(sql);
  }

  // Execute methods
  @Override
  public ResultSet executeQuery() throws SQLException {
    return delegateStatement.executeQuery();
  }

  @Override
  public int executeUpdate() throws SQLException {
    return delegateStatement.executeUpdate();
  }

  @Override
  public boolean execute() throws SQLException {
    return delegateStatement.execute();
  }

  @Override
  public void addBatch() throws SQLException {
    delegateStatement.addBatch();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    return delegateStatement.executeBatch();
  }

  @Override
  public void clearBatch() throws SQLException {
    delegateStatement.clearBatch();
  }

  // Parameter setting methods
  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    delegateStatement.setNull(parameterIndex, sqlType);
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    delegateStatement.setBoolean(parameterIndex, x);
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    delegateStatement.setByte(parameterIndex, x);
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    delegateStatement.setShort(parameterIndex, x);
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    delegateStatement.setInt(parameterIndex, x);
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    delegateStatement.setLong(parameterIndex, x);
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    delegateStatement.setFloat(parameterIndex, x);
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    delegateStatement.setDouble(parameterIndex, x);
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    delegateStatement.setBigDecimal(parameterIndex, x);
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    delegateStatement.setString(parameterIndex, x);
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    delegateStatement.setBytes(parameterIndex, x);
  }

  @Override
  public void setDate(int parameterIndex, Date x) throws SQLException {
    delegateStatement.setDate(parameterIndex, x);
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    delegateStatement.setTime(parameterIndex, x);
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    delegateStatement.setTimestamp(parameterIndex, x);
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegateStatement.setAsciiStream(parameterIndex, x, length);
  }

  @Override
  @Deprecated
  public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegateStatement.setUnicodeStream(parameterIndex, x, length);
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegateStatement.setBinaryStream(parameterIndex, x, length);
  }

  @Override
  public void clearParameters() throws SQLException {
    delegateStatement.clearParameters();
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    delegateStatement.setObject(parameterIndex, x, targetSqlType);
  }

  @Override
  public void setObject(int parameterIndex, Object x) throws SQLException {
    delegateStatement.setObject(parameterIndex, x);
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
    delegateStatement.setCharacterStream(parameterIndex, reader, length);
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    delegateStatement.setRef(parameterIndex, x);
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    delegateStatement.setBlob(parameterIndex, x);
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    delegateStatement.setClob(parameterIndex, x);
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    delegateStatement.setArray(parameterIndex, x);
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    delegateStatement.setDate(parameterIndex, x, cal);
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    delegateStatement.setTime(parameterIndex, x, cal);
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    delegateStatement.setTimestamp(parameterIndex, x, cal);
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    delegateStatement.setNull(parameterIndex, sqlType, typeName);
  }

  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    delegateStatement.setURL(parameterIndex, x);
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    delegateStatement.setRowId(parameterIndex, x);
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    delegateStatement.setNString(parameterIndex, value);
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
    delegateStatement.setNCharacterStream(parameterIndex, value, length);
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    delegateStatement.setNClob(parameterIndex, value);
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    delegateStatement.setClob(parameterIndex, reader, length);
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
    delegateStatement.setBlob(parameterIndex, inputStream, length);
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    delegateStatement.setNClob(parameterIndex, reader, length);
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    delegateStatement.setSQLXML(parameterIndex, xmlObject);
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
    delegateStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    delegateStatement.setAsciiStream(parameterIndex, x, length);
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    delegateStatement.setBinaryStream(parameterIndex, x, length);
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
    delegateStatement.setCharacterStream(parameterIndex, reader, length);
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    delegateStatement.setAsciiStream(parameterIndex, x);
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    delegateStatement.setBinaryStream(parameterIndex, x);
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    delegateStatement.setCharacterStream(parameterIndex, reader);
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    delegateStatement.setNCharacterStream(parameterIndex, value);
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    delegateStatement.setClob(parameterIndex, reader);
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    delegateStatement.setBlob(parameterIndex, inputStream);
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    delegateStatement.setNClob(parameterIndex, reader);
  }

  // Metadata methods
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return delegateStatement.getMetaData();
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    return delegateStatement.getParameterMetaData();
  }

  // Statement interface methods (inherited)
  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return delegateStatement.executeQuery(sql);
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return delegateStatement.executeUpdate(sql);
  }

  @Override
  public void close() throws SQLException {
    if (delegateStatement != null) {
      delegateStatement.close();
    }
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return delegateStatement.getMaxFieldSize();
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    delegateStatement.setMaxFieldSize(max);
  }

  @Override
  public int getMaxRows() throws SQLException {
    return delegateStatement.getMaxRows();
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    delegateStatement.setMaxRows(max);
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    delegateStatement.setEscapeProcessing(enable);
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return delegateStatement.getQueryTimeout();
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    delegateStatement.setQueryTimeout(seconds);
  }

  @Override
  public void cancel() throws SQLException {
    delegateStatement.cancel();
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return delegateStatement.getWarnings();
  }

  @Override
  public void clearWarnings() throws SQLException {
    delegateStatement.clearWarnings();
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    delegateStatement.setCursorName(name);
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return delegateStatement.execute(sql);
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return delegateStatement.getResultSet();
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return delegateStatement.getUpdateCount();
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return delegateStatement.getMoreResults();
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    delegateStatement.setFetchDirection(direction);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return delegateStatement.getFetchDirection();
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    delegateStatement.setFetchSize(rows);
  }

  @Override
  public int getFetchSize() throws SQLException {
    return delegateStatement.getFetchSize();
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return delegateStatement.getResultSetConcurrency();
  }

  @Override
  public int getResultSetType() throws SQLException {
    return delegateStatement.getResultSetType();
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    delegateStatement.addBatch(sql);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return parentConnection;
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return delegateStatement.getMoreResults(current);
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    return delegateStatement.getGeneratedKeys();
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return delegateStatement.executeUpdate(sql, autoGeneratedKeys);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return delegateStatement.executeUpdate(sql, columnIndexes);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return delegateStatement.executeUpdate(sql, columnNames);
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return delegateStatement.execute(sql, autoGeneratedKeys);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return delegateStatement.execute(sql, columnIndexes);
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return delegateStatement.execute(sql, columnNames);
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return delegateStatement.getResultSetHoldability();
  }

  @Override
  public boolean isClosed() throws SQLException {
    return delegateStatement.isClosed();
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    delegateStatement.setPoolable(poolable);
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return delegateStatement.isPoolable();
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    delegateStatement.closeOnCompletion();
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return delegateStatement.isCloseOnCompletion();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    return delegateStatement.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass()) || delegateStatement.isWrapperFor(iface);
  }
}
