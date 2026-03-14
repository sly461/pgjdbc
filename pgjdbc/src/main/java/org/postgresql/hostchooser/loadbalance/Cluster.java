/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.StatementCancelState;
import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Represents a database cluster consisting of multiple nodes.
 * <p>
 * This class manages all nodes in a cluster, provides load balancing functionality,
 * and coordinates quick auto-balance operations when nodes recover from failure.
 * </p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Node management (add, remove, lookup)</li>
 *   <li>Round-robin counter for load balancing</li>
 *   <li>Connection registration and state tracking</li>
 *   <li>Quick auto-balance coordination</li>
 *   <li>Cluster-wide statistics</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe using concurrent data structures.
 *
 * @see DataNode
 * @see ConnectionInfo
 */
public class Cluster {

  private final String clusterId;
  private final ConcurrentHashMap<HostSpec, DataNode> nodes;
  private final AtomicInteger roundRobinCounter;
  private final ConcurrentLinkedQueue<ConnectionInfo> pendingCloseConnections;
  private final ConcurrentHashMap<QueryExecutor, HostSpec> executorToHost;

  /**
   * Creates a new Cluster with the specified ID.
   *
   * @param clusterId Unique cluster identifier
   */
  public Cluster(String clusterId) {
    this.clusterId = clusterId;
    this.nodes = new ConcurrentHashMap<>();
    this.roundRobinCounter = new AtomicInteger(0);
    this.pendingCloseConnections = new ConcurrentLinkedQueue<>();
    this.executorToHost = new ConcurrentHashMap<>();
  }

  /**
   * Gets the cluster ID.
   *
   * @return The cluster ID
   */
  public String getClusterId() {
    return clusterId;
  }

  /**
   * Gets or creates a DataNode for the specified host.
   *
   * @param host The host specification
   * @return The DataNode for the host
   */
  public DataNode getOrCreateNode(HostSpec host) {
    return nodes.computeIfAbsent(host, DataNode::new);
  }

  /**
   * Gets a DataNode for the specified host.
   *
   * @param host The host specification
   * @return The DataNode, or null if not found
   */
  public DataNode getNode(HostSpec host) {
    return nodes.get(host);
  }

  /**
   * Gets all host specifications in this cluster.
   *
   * @return List of all HostSpecs
   */
  public List<HostSpec> getAllHosts() {
    return new ArrayList<>(nodes.keySet());
  }

  /**
   * Gets the host availability states.
   *
   * @return Map of host to availability state
   */
  public Map<HostSpec, Boolean> getHostStates() {
    return nodes.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> e.getValue().isAvailable()
        ));
  }

  /**
   * Gets and increments the round-robin counter.
   *
   * @return The next counter value
   */
  public int getAndIncrementCounter() {
    return roundRobinCounter.getAndIncrement();
  }

  /**
   * Registers a connection to a specific host.
   *
   * @param host The host specification
   * @param executor The query executor for the connection
   * @return The ConnectionInfo object for the registered connection
   */
  public ConnectionInfo registerConnection(HostSpec host, QueryExecutor executor) {
    DataNode node = getOrCreateNode(host);
    ConnectionInfo info = node.registerConnection(executor);
    executorToHost.put(executor, host);
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
    HostSpec host = executorToHost.get(executor);
    if (host == null) {
      return false;
    }

    DataNode node = getNode(host);
    if (node == null) {
      return false;
    }

    return node.updateConnectionState(executor, newState);
  }

  /**
   * Increments the pending connection count for a specific host.
   *
   * @param host The host specification
   */
  public void incrementPendingCount(HostSpec host) {
    DataNode node = getOrCreateNode(host);
    node.incrementPendingCount();
  }

  /**
   * Decrements the pending connection count for a specific host.
   *
   * @param host The host specification
   */
  public void decrementPendingCount(HostSpec host) {
    DataNode node = getNode(host);
    if (node != null) {
      node.decrementPendingCount();
    }
  }

  /**
   * Sets the availability state of a host.
   *
   * @param host The host specification
   * @param available true if the host is available, false otherwise
   */
  public void setHostAvailable(HostSpec host, boolean available) {
    DataNode node = getOrCreateNode(host);
    node.setAvailable(available);
  }

  /**
   * Checks if a host is available.
   *
   * @param host The host specification
   * @return true if the host is available, false otherwise
   */
  public boolean isHostAvailable(HostSpec host) {
    DataNode node = getNode(host);
    return node == null || node.isAvailable();
  }

  /**
   * Gets the total connection count for a specific host.
   *
   * @param host The host specification
   * @return The total connection count (active + pending)
   */
  public int getTotalConnectionCount(HostSpec host) {
    DataNode node = getNode(host);
    return node == null ? 0 : node.getTotalConnectionCount();
  }

  /**
   * Gets the pending connection count for a specific host.
   *
   * @param host The host specification
   * @return The number of pending connections
   */
  public int getPendingCount(HostSpec host) {
    DataNode node = getNode(host);
    return node == null ? 0 : node.getPendingCount();
  }

  /**
   * Gets idle connections for a specific host.
   *
   * @param host The host specification
   * @param maxIdleTimeMs Maximum idle time threshold in milliseconds
   * @return List of idle connections
   */
  public List<ConnectionInfo> getIdleConnections(HostSpec host, long maxIdleTimeMs) {
    DataNode node = getNode(host);
    if (node == null) {
      return new ArrayList<>();
    }
    return node.getIdleConnections(maxIdleTimeMs);
  }

  /**
   * Marks connections for closing during quick auto-balance.
   *
   * @param connections List of connections to mark for closing
   */
  public void markConnectionsForClose(List<ConnectionInfo> connections) {
    if (connections != null && !connections.isEmpty()) {
      pendingCloseConnections.addAll(connections);
    }
  }

  /**
   * Gets the pending close queue.
   *
   * @return The pending close queue
   */
  public ConcurrentLinkedQueue<ConnectionInfo> getPendingCloseQueue() {
    return pendingCloseConnections;
  }

  /**
   * Closes marked connections gradually.
   * <p>
   * IMPORTANT: Re-checks connection state before closing to avoid closing
   * connections that are currently in use (race condition protection).
   * </p>
   *
   * @param maxCount Maximum number of connections to close
   * @return Number of connections actually closed
   */
  public int closeMarkedConnections(int maxCount) {
    int closedCount = 0;
    int skippedCount = 0;

    for (int i = 0; i < maxCount; i++) {
      ConnectionInfo info = pendingCloseConnections.poll();
      if (info == null) {
        break; // Queue is empty
      }

      QueryExecutor executor = info.getExecutor();
      if (executor != null && !executor.isClosed()) {
        // CRITICAL: Re-check connection state before closing
        // The connection might have transitioned from IDLE to IN_QUERY
        // between marking and actual close attempt
        StatementCancelState currentState = info.getConnectionState();

        if (currentState == StatementCancelState.IDLE) {
          // Safe to close - connection is still idle
          try {
            executor.close();
            closedCount++;
          } catch (Exception e) {
            // Log but continue closing other connections
          }
        } else {
          // Connection is now active - skip closing and don't re-queue
          // This prevents closing connections that are in use
          skippedCount++;
        }
      }
    }

    if (skippedCount > 0) {
      // Log skipped connections for monitoring
      // These connections were marked for close but became active before closing
    }

    return closedCount;
  }

  /**
   * Forcibly aborts all connections on a specific host.
   * <p>
   * Called when heartbeat detects that a host has failed, to release
   * underlying sockets for half-open connections.
   * </p>
   *
   * @param host The host specification
   * @return The number of connections aborted
   */
  public int abortConnectionsOnHost(HostSpec host) {
    DataNode node = nodes.get(host);
    return node == null ? 0 : node.abortAllConnections();
  }

  /**
   * Cleans up stale connections for all nodes in this cluster.
   *
   * @return Total number of connections removed
   */
  public int cleanupStaleConnections() {
    int totalRemoved = 0;
    for (DataNode node : nodes.values()) {
      totalRemoved += node.cleanupStaleConnections();
    }
    return totalRemoved;
  }

  /**
   * Generates a unique cluster ID based on hosts and strategy.
   *
   * @param hosts Array of host specifications
   * @param strategy Load balance strategy name
   * @return Unique cluster identifier
   */
  public static String generateClusterId(HostSpec[] hosts, String strategy) {
    // Sort hosts to ensure consistent ID regardless of order in URL
    String sortedHosts = Arrays.stream(hosts)
        .map(HostSpec::toString)
        .sorted()
        .collect(Collectors.joining(","));

    return sortedHosts + "|" + strategy;
  }

  @Override
  public String toString() {
    return "Cluster{"
        + "id='" + clusterId + '\''
        + ", nodes=" + nodes.size()
        + ", totalConnections=" + nodes.values().stream()
            .mapToInt(DataNode::getActiveConnectionCount)
            .sum()
        + '}';
  }
}
