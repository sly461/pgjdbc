/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

/**
 * Represents {@link PgStatement#cancel()} state.
 * <p>
 * This enum is also used by the load balancing mechanism to track connection activity
 * and determine when connections are idle and can be safely closed during rebalancing.
 * </p>
 */
public enum StatementCancelState {
  /**
   * Statement is idle, not executing any query.
   * <p>
   * This is the initial state and the state after a query completes.
   * Connections in this state are candidates for closing during rebalancing.
   * </p>
   */
  IDLE,

  /**
   * Statement is actively executing a query.
   * <p>
   * Connections in this state should not be closed as it would disrupt the query.
   * </p>
   */
  IN_QUERY,

  /**
   * Statement cancellation is in progress.
   * <p>
   * The cancel request has been sent but not yet completed.
   * </p>
   */
  CANCELING,

  /**
   * Statement has been cancelled.
   * <p>
   * The query was successfully cancelled.
   * </p>
   */
  CANCELLED
}
