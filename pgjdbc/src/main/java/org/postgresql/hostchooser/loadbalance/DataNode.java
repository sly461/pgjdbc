/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.StatementCancelState;
import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a single database node in a cluster.
 * <p>
 * This class manages all connections to a specific host, tracks node availability,
 * and provides connection statistics for load balancing decisions.
 * </p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Connection lifecycle management (register, track, cleanup)</li>
 *   <li>Node availability tracking</li>
 *   <li>Pending connection counting (pre-allocation mechanism)</li>
 *   <li>Connection state statistics</li>
 *   <li>Idle connection identification</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe using concurrent data structures and atomic operations.
 *
 * @see ConnectionInfo
 * @see HostSpec
 */
public class DataNode {

  private final HostSpec hostSpec;
  private final Set<ConnectionInfo> connections;
  private final AtomicInteger pendingCount;
  private volatile boolean available;
  private volatile long lastCheckTime;
  private volatile long lastStateChangeTime;

  /**
   * Creates a new DataNode for the specified host.
   *
   * @param hostSpec The host specification (hostname and port)
   */
  public DataNode(HostSpec hostSpec) {
    this.hostSpec = hostSpec;
    this.connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    this.pendingCount = new AtomicInteger(0);
    this.available = true;
    this.lastCheckTime = System.currentTimeMillis();
    this.lastStateChangeTime = System.currentTimeMillis();
  }

  /**
   * Gets the host specification for this node.
   *
   * @return The host specification
   */
  public HostSpec getHostSpec() {
    return hostSpec;
  }

  /**
   * Registers a new connection to this node.
   *
   * @param executor The query executor for the connection
   * @return The ConnectionInfo object for the registered connection
   */
  public ConnectionInfo registerConnection(QueryExecutor executor) {
    ConnectionInfo info = new ConnectionInfo(executor);
    connections.add(info);
    return info;
  }

  /**
   * Updates the connection state for a specific executor.
   *
   * @param executor The query executor
   * @param newState The new connection state
   * @return true if the connection was found and updated, false otherwise
   */
  public boolean updateConnectionState(QueryExecutor executor, StatementCancelState newState) {
    for (ConnectionInfo info : connections) {
      QueryExecutor infoExecutor = info.getExecutor();
      if (infoExecutor != null && infoExecutor.equals(executor)) {
        info.setConnectionState(newState);
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the number of active (valid) connections to this node.
   * <p>
   * This method also cleans up invalid connections during counting.
   * </p>
   *
   * @return The number of active connections
   */
  public int getActiveConnectionCount() {
    int count = 0;
    Iterator<ConnectionInfo> iter = connections.iterator();
    while (iter.hasNext()) {
      ConnectionInfo info = iter.next();
      if (!info.isValid()) {
        iter.remove(); // Clean up invalid connection
      } else {
        count++;
      }
    }
    return count;
  }

  /**
   * Gets the total connection count including pending connections.
   *
   * @return Total connection count (active + pending)
   */
  public int getTotalConnectionCount() {
    return getActiveConnectionCount() + pendingCount.get();
  }

  /**
   * Gets the pending connection count.
   *
   * @return The number of connections being created
   */
  public int getPendingCount() {
    return pendingCount.get();
  }

  /**
   * Increments the pending connection count.
   * <p>
   * This is part of the pre-allocation mechanism to prevent concurrent
   * connection attempts from all selecting the same node.
   * </p>
   */
  public void incrementPendingCount() {
    pendingCount.incrementAndGet();
  }

  /**
   * Decrements the pending connection count.
   */
  public void decrementPendingCount() {
    pendingCount.decrementAndGet();
  }

  /**
   * Checks if this node is available.
   *
   * @return true if the node is available, false otherwise
   */
  public boolean isAvailable() {
    return available;
  }

  /**
   * Sets the availability state of this node.
   * <p>
   * Updates the state change timestamp if the availability changes.
   * </p>
   *
   * @param available true if the node is available, false otherwise
   */
  public void setAvailable(boolean available) {
    if (this.available != available) {
      this.lastStateChangeTime = System.currentTimeMillis();
    }
    this.available = available;
    this.lastCheckTime = System.currentTimeMillis();
  }

  /**
   * Gets the time when this node was last checked.
   *
   * @return Last check timestamp in milliseconds
   */
  public long getLastCheckTime() {
    return lastCheckTime;
  }

  /**
   * Gets the time when the node availability last changed.
   *
   * @return State change timestamp in milliseconds
   */
  public long getLastStateChangeTime() {
    return lastStateChangeTime;
  }

  /**
   * Gets idle connections that exceed the specified idle time threshold.
   *
   * @param maxIdleTimeMs Maximum idle time threshold in milliseconds
   * @return List of idle connections
   */
  public List<ConnectionInfo> getIdleConnections(long maxIdleTimeMs) {
    List<ConnectionInfo> idleConnections = new ArrayList<>();
    Iterator<ConnectionInfo> iter = connections.iterator();

    while (iter.hasNext()) {
      ConnectionInfo info = iter.next();
      if (!info.isValid()) {
        iter.remove(); // Clean up invalid connection
      } else if (info.canBeClosed(maxIdleTimeMs)) {
        idleConnections.add(info);
      }
    }

    return idleConnections;
  }

  /**
   * Cleans up stale (invalid) connections.
   *
   * @return The number of connections removed
   */
  public int cleanupStaleConnections() {
    int removed = 0;
    Iterator<ConnectionInfo> iter = connections.iterator();

    while (iter.hasNext()) {
      ConnectionInfo info = iter.next();
      if (!info.isValid()) {
        iter.remove();
        removed++;
      }
    }

    return removed;
  }

  @Override
  public String toString() {
    return "DataNode{"
        + "host=" + hostSpec
        + ", available=" + available
        + ", active=" + getActiveConnectionCount()
        + ", pending=" + pendingCount.get()
        + ", total=" + getTotalConnectionCount()
        + '}';
  }
}
