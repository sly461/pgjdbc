/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.StatementCancelState;

import java.lang.ref.WeakReference;

/**
 * Encapsulates metadata and state information for a single database connection.
 * <p>
 * This class tracks the lifecycle and state of a connection, including:
 * <ul>
 *   <li>Connection state (IDLE, IN_QUERY, CANCELING, CANCELLED)</li>
 *   <li>Creation and last active timestamps</li>
 *   <li>Idle time calculation</li>
 *   <li>Connection validity checking</li>
 * </ul>
 * </p>
 * <p>
 * Uses {@link WeakReference} to hold the QueryExecutor to avoid preventing
 * garbage collection of closed connections.
 * </p>
 *
 * <h3>Thread Safety</h3>
 * This class is thread-safe. State updates are synchronized and timestamps
 * use volatile fields for visibility.
 *
 * @see StatementCancelState
 * @see QueryExecutor
 */
public class ConnectionInfo {

  private final WeakReference<QueryExecutor> executorRef;
  private final long createTime;
  private volatile long lastActiveTime;
  private volatile long stateLastChangedTime;
  private volatile StatementCancelState connectionState;

  /**
   * Creates a new ConnectionInfo for the given executor.
   * <p>
   * Initial state is set to IDLE with timestamps set to current time.
   * </p>
   *
   * @param executor The query executor for this connection
   */
  public ConnectionInfo(QueryExecutor executor) {
    this.executorRef = new WeakReference<>(executor);
    this.createTime = System.currentTimeMillis();
    this.lastActiveTime = this.createTime;
    this.stateLastChangedTime = this.createTime;
    this.connectionState = StatementCancelState.IDLE;
  }

  /**
   * Gets the query executor for this connection.
   *
   * @return The query executor, or null if it has been garbage collected
   */
  public QueryExecutor getExecutor() {
    return executorRef.get();
  }

  /**
   * Gets the current connection state.
   *
   * @return The current state
   */
  public StatementCancelState getConnectionState() {
    return connectionState;
  }

  /**
   * Updates the connection state.
   * <p>
   * When transitioning to IDLE, the last active time is updated.
   * The state change timestamp is always updated.
   * </p>
   *
   * @param newState The new connection state
   */
  public synchronized void setConnectionState(StatementCancelState newState) {
    if (newState != null && connectionState != newState) {
      this.connectionState = newState;
      this.stateLastChangedTime = System.currentTimeMillis();

      // Update last active time when transitioning to IDLE
      if (newState == StatementCancelState.IDLE) {
        this.lastActiveTime = this.stateLastChangedTime;
      }
    }
  }

  /**
   * Calculates the idle time for this connection.
   * <p>
   * Returns the time elapsed since the last state change if the connection
   * is in IDLE state. Returns 0 if the connection is executing a query or
   * in any other non-IDLE state.
   * </p>
   *
   * @return Idle time in milliseconds, or 0 if not idle
   */
  public long getIdleTime() {
    if (connectionState == StatementCancelState.IDLE) {
      return System.currentTimeMillis() - stateLastChangedTime;
    }
    return 0;
  }

  /**
   * Checks if this connection is valid (not closed and not garbage collected).
   *
   * @return true if the connection is valid, false otherwise
   */
  public boolean isValid() {
    QueryExecutor executor = executorRef.get();
    return executor != null && !executor.isClosed();
  }

  /**
   * Checks if this connection can be closed based on idle time threshold.
   * <p>
   * A connection can be closed if:
   * <ul>
   *   <li>It is valid (not already closed)</li>
   *   <li>It is in IDLE state (not executing a query)</li>
   *   <li>Its idle time exceeds the specified threshold</li>
   * </ul>
   * </p>
   *
   * @param maxIdleTimeMs Maximum idle time threshold in milliseconds
   * @return true if the connection can be closed, false otherwise
   */
  public boolean canBeClosed(long maxIdleTimeMs) {
    if (!isValid()) {
      return false;
    }
    if (connectionState != StatementCancelState.IDLE) {
      return false;
    }
    return getIdleTime() >= maxIdleTimeMs;
  }

  @Override
  public String toString() {
    return "ConnectionInfo{"
        + "state=" + connectionState
        + ", idleTime=" + getIdleTime() + "ms"
        + ", valid=" + isValid()
        + '}';
  }
}
