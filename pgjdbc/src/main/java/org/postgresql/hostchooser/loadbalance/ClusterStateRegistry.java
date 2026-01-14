/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for maintaining per-cluster state for load balancing strategies.
 * <p>
 * This registry supports stateful load balancing strategies (e.g., round-robin) by maintaining
 * counters and other state information per cluster. State is not persisted and resets on JVM restart.
 * </p>
 * <p>
 * A cluster is identified by a unique ID generated from the set of hosts and the load balance strategy.
 * This ensures that connections with the same hosts and strategy share the same state.
 * </p>
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe using {@link ConcurrentHashMap} and {@link AtomicInteger}.
 *
 * @see RoundRobinLoadBalanceStrategy
 */
public class ClusterStateRegistry {

  private static final ClusterStateRegistry INSTANCE = new ClusterStateRegistry();

  // Map from cluster ID to round-robin counter
  private final ConcurrentHashMap<String, AtomicInteger> clusterCounters;

  /**
   * Private constructor for singleton pattern.
   */
  private ClusterStateRegistry() {
    this.clusterCounters = new ConcurrentHashMap<>();
  }

  /**
   * Returns the singleton instance of the cluster state registry.
   *
   * @return The global ClusterStateRegistry instance
   */
  public static ClusterStateRegistry getInstance() {
    return INSTANCE;
  }

  /**
   * Gets and increments the counter for a cluster in a thread-safe manner.
   * <p>
   * If the cluster does not have a counter yet, it is initialized to 0 before incrementing.
   * </p>
   * <p>
   * The counter wraps around at {@link Integer#MAX_VALUE} due to AtomicInteger behavior.
   * This is acceptable as the modulo operation in round-robin will still produce valid results.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @return Current counter value before increment (starting from 0)
   */
  public int getAndIncrementCounter(String clusterId) {
    AtomicInteger counter = clusterCounters.computeIfAbsent(
        clusterId,
        k -> new AtomicInteger(0)
    );
    return counter.getAndIncrement();
  }

  /**
   * Generates a unique cluster ID based on hosts and load balance strategy.
   * <p>
   * The cluster ID is deterministic and independent of host order in the connection URL.
   * Hosts are sorted alphabetically by their string representation before concatenation.
   * </p>
   * <p>
   * <b>Format:</b> {@code "host1:port1,host2:port2,...|strategy"}
   * </p>
   * <p>
   * <b>Example:</b>
   * <pre>
   * Hosts: [host2:5432, host1:5433]
   * Strategy: roundRobin
   * Result: "host1:5433,host2:5432|roundRobin"
   * </pre>
   * </p>
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

  /**
   * Clears all cluster state.
   * <p>
   * This method is primarily intended for testing purposes to reset state between test runs.
   * In production, state persists for the lifetime of the JVM.
   * </p>
   */
  void clearAll() {
    clusterCounters.clear();
  }

  /**
   * Returns the current counter value for a cluster without incrementing it.
   * <p>
   * This method is primarily for testing and debugging purposes.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @return Current counter value, or 0 if cluster has no counter yet
   */
  int getCurrentCounter(String clusterId) {
    AtomicInteger counter = clusterCounters.get(clusterId);
    return counter != null ? counter.get() : 0;
  }

  /**
   * Returns the number of clusters currently tracked by this registry.
   * <p>
   * This method is primarily for monitoring and testing purposes.
   * </p>
   *
   * @return Number of clusters with state
   */
  int getClusterCount() {
    return clusterCounters.size();
  }
}
