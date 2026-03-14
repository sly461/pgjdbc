/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.StatementCancelState;
import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages multiple database clusters for load balancing.
 * <p>
 * This is the central manager that coordinates all cluster operations including:
 * <ul>
 *   <li>Cluster lifecycle management</li>
 *   <li>Connection registration and tracking</li>
 *   <li>Load balancing support (round-robin, least connection)</li>
 *   <li>Node availability monitoring</li>
 *   <li>Quick auto-balance coordination</li>
 * </ul>
 * </p>
 *
 * <h3>Design Pattern</h3>
 * This class follows the Singleton pattern and uses a hierarchical structure:
 * <pre>
 * ClusterManager (Singleton)
 *   └── Cluster (per cluster ID)
 *         └── DataNode (per host)
 *               └── ConnectionInfo (per connection)
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe using concurrent data structures.
 *
 * @see Cluster
 * @see DataNode
 * @see ConnectionInfo
 */
public class ClusterManager {

  private static final ClusterManager INSTANCE = new ClusterManager();

  private final ConcurrentHashMap<String, Cluster> clusters;

  /**
   * Private constructor for singleton pattern.
   */
  private ClusterManager() {
    this.clusters = new ConcurrentHashMap<>();
  }

  /**
   * Returns the singleton instance of the cluster manager.
   *
   * @return The global ClusterManager instance
   */
  public static ClusterManager getInstance() {
    return INSTANCE;
  }

  /**
   * Gets or creates a cluster with the specified ID.
   *
   * @param clusterId Unique cluster identifier
   * @return The Cluster object
   */
  public Cluster getOrCreateCluster(String clusterId) {
    return clusters.computeIfAbsent(clusterId, Cluster::new);
  }

  /**
   * Gets a cluster by ID.
   *
   * @param clusterId Unique cluster identifier
   * @return The Cluster object, or null if not found
   */
  public Cluster getCluster(String clusterId) {
    return clusters.get(clusterId);
  }

  // ==================== Round-Robin Support ====================

  /**
   * Gets and increments the counter for a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @return The next counter value
   */
  public int getAndIncrementCounter(String clusterId) {
    Cluster cluster = getOrCreateCluster(clusterId);
    return cluster.getAndIncrementCounter();
  }

  // ==================== Connection Management ====================

  /**
   * Registers a connection for a specific host in a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification for the connection
   * @param executor Query executor for the connection
   */
  public void registerConnection(String clusterId, HostSpec host, QueryExecutor executor) {
    Cluster cluster = getOrCreateCluster(clusterId);
    cluster.registerConnection(host, executor);
  }

  /**
   * Updates the connection state for a specific QueryExecutor.
   *
   * @param executor Query executor whose state to update
   * @param newState New connection state
   */
  public void setConnectionState(QueryExecutor executor, StatementCancelState newState) {
    // Search all clusters for the executor
    for (Cluster cluster : clusters.values()) {
      if (cluster.updateConnectionState(executor, newState)) {
        return; // Found and updated
      }
    }
  }

  // ==================== Pending Count (Pre-allocation) ====================

  /**
   * Increments the pending connection count for a specific host.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   */
  public void incrementPendingCount(String clusterId, HostSpec host) {
    Cluster cluster = getOrCreateCluster(clusterId);
    cluster.incrementPendingCount(host);
  }

  /**
   * Decrements the pending connection count for a specific host.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   */
  public void decrementPendingCount(String clusterId, HostSpec host) {
    Cluster cluster = getCluster(clusterId);
    if (cluster != null) {
      cluster.decrementPendingCount(host);
    }
  }

  /**
   * Gets the pending connection count for a specific host.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   * @return The number of pending connections
   */
  public int getPendingCount(String clusterId, HostSpec host) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? 0 : cluster.getPendingCount(host);
  }

  // ==================== Host Availability ====================

  /**
   * Sets the availability state of a host in a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   * @param available true if host is available, false otherwise
   */
  public void setHostAvailable(String clusterId, HostSpec host, boolean available) {
    Cluster cluster = getOrCreateCluster(clusterId);
    cluster.setHostAvailable(host, available);
  }

  /**
   * Checks if a host is currently available in a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   * @return true if host is available, false otherwise
   */
  public boolean isHostAvailable(String clusterId, HostSpec host) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null || cluster.isHostAvailable(host);
  }

  /**
   * Gets the availability states of all hosts in a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @return Map of host to availability state
   */
  public Map<HostSpec, Boolean> getHostStates(String clusterId) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? new HashMap<HostSpec, Boolean>() : cluster.getHostStates();
  }

  /**
   * Gets all hosts in a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @return List of all hosts
   */
  public List<HostSpec> getAllHosts(String clusterId) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? new ArrayList<HostSpec>() : cluster.getAllHosts();
  }

  // ==================== Connection Statistics ====================

  /**
   * Gets the total connection count for a specific host.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   * @return Total connection count (active + pending)
   */
  public int getTotalConnectionCount(String clusterId, HostSpec host) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? 0 : cluster.getTotalConnectionCount(host);
  }

  // ==================== Quick Auto-Balance ====================

  /**
   * Gets idle connections for a specific host.
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification
   * @param maxIdleTimeMs Minimum idle time in milliseconds
   * @return List of idle connections
   */
  public List<ConnectionInfo> getIdleConnections(String clusterId, HostSpec host, long maxIdleTimeMs) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? new ArrayList<ConnectionInfo>() : cluster.getIdleConnections(host, maxIdleTimeMs);
  }

  /**
   * Marks connections for closing during quick auto-balance.
   *
   * @param clusterId Unique cluster identifier
   * @param connections List of connections to mark for closing
   */
  public void markConnectionsForClose(String clusterId, List<ConnectionInfo> connections) {
    Cluster cluster = getCluster(clusterId);
    if (cluster != null) {
      cluster.markConnectionsForClose(connections);
    }
  }

  /**
   * Gets the pending close queue for a cluster.
   *
   * @param clusterId Unique cluster identifier
   * @return The pending close queue, or null if no connections are pending
   */
  public ConcurrentLinkedQueue<ConnectionInfo> getPendingCloseQueue(String clusterId) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? null : cluster.getPendingCloseQueue();
  }

  /**
   * Closes marked connections gradually during quick auto-balance.
   *
   * @param clusterId Unique cluster identifier
   * @param maxCount Maximum number of connections to close in this iteration
   * @return Number of connections actually closed
   */
  public int closeMarkedConnections(String clusterId, int maxCount) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? 0 : cluster.closeMarkedConnections(maxCount);
  }

  // ==================== Cleanup ====================

  /**
   * Forcibly aborts all connections on a specific host in a cluster.
   * <p>
   * Called when heartbeat detects that a host has failed, to release
   * underlying sockets for half-open connections.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @param host Host specification of the failed node
   * @return The number of connections aborted
   */
  public int abortConnectionsOnHost(String clusterId, HostSpec host) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? 0 : cluster.abortConnectionsOnHost(host);
  }

  /**
   * Cleans up stale connections for a specific cluster.
   *
   * @param clusterId Unique cluster identifier
   * @return Number of stale connections removed
   */
  public int cleanupStaleConnections(String clusterId) {
    Cluster cluster = getCluster(clusterId);
    return cluster == null ? 0 : cluster.cleanupStaleConnections();
  }

  // ==================== Utility ====================

  /**
   * Generates a unique cluster ID based on hosts and load balance strategy.
   *
   * @param hosts Array of host specifications
   * @param strategy Load balance strategy name
   * @return Unique cluster identifier
   */
  public static String generateClusterId(HostSpec[] hosts, String strategy) {
    return Cluster.generateClusterId(hosts, strategy);
  }
}
