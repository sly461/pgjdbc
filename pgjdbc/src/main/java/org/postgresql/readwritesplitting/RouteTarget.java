/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

/**
 * Represents the target server type for SQL routing in read-write splitting mode.
 */
public enum RouteTarget {
  /**
   * Route to the master (primary) server.
   * Used for write operations (INSERT, UPDATE, DELETE, DDL) and transactions.
   */
  MASTER,

  /**
   * Route to a replica (secondary) server.
   * Used for read operations (SELECT) when not in a transaction.
   */
  REPLICA
}
