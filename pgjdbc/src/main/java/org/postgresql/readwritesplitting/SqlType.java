/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

/**
 * Enumeration of SQL statement types for routing decisions.
 * Each type has associated read/write properties that determine routing behavior.
 */
public enum SqlType {
  // Read operations (route to REPLICA)
  SELECT(true, false),
  SHOW(true, false),
  EXPLAIN(true, false),
  DESCRIBE(true, false),
  DESC(true, false),

  // Write operations (route to MASTER)
  INSERT(false, true),
  UPDATE(false, true),
  DELETE(false, true),
  REPLACE(false, true),
  MERGE(false, true),
  TRUNCATE(false, true),

  // DDL operations (route to MASTER)
  CREATE(false, true),
  ALTER(false, true),
  DROP(false, true),
  RENAME(false, true),

  // DCL operations (route to MASTER)
  GRANT(false, true),
  REVOKE(false, true),

  // Transaction control (route to MASTER)
  BEGIN(false, true),
  START_TRANSACTION(false, true),
  COMMIT(false, true),
  ROLLBACK(false, true),
  SAVEPOINT(false, true),
  RELEASE(false, true),
  SET(false, true),

  // Special SELECT variants (route to MASTER)
  SELECT_FOR_UPDATE(false, true),
  SELECT_FOR_SHARE(false, true),
  SELECT_FUNCTION(false, true),  // Function calls may have side effects

  // Maintenance operations (route to MASTER)
  VACUUM(false, true),
  ANALYZE(false, true),
  COPY(false, true),

  // JDBC escape syntax (route to MASTER for function/procedure calls)
  CALL(false, true),

  // Special routing hints
  MASTER_HINT(false, true),  // /*master*/ or /*+master*/

  // Unknown or unparseable SQL (route to MASTER for safety)
  UNKNOWN(false, true);

  private final boolean isRead;
  private final boolean isWrite;

  SqlType(boolean isRead, boolean isWrite) {
    this.isRead = isRead;
    this.isWrite = isWrite;
  }

  /**
   * Returns true if this SQL type is a read operation.
   *
   * @return true if read operation
   */
  public boolean isRead() {
    return isRead;
  }

  /**
   * Returns true if this SQL type is a write operation.
   *
   * @return true if write operation
   */
  public boolean isWrite() {
    return isWrite;
  }

  /**
   * Converts this SQL type to a route target.
   *
   * @return RouteTarget.REPLICA for read operations, RouteTarget.MASTER for write operations
   */
  public RouteTarget toRouteTarget() {
    if (isRead && !isWrite) {
      return RouteTarget.REPLICA;
    }
    return RouteTarget.MASTER;
  }
}
