/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.List;

/**
 * Interface for load balancing strategies that determine the order of hosts to connect to.
 * <p>
 * Implementations of this interface define different algorithms for ordering candidate hosts,
 * such as random selection, weighted random, round-robin, etc.
 * </p>
 * <p>
 * Load balancing strategies are applied after hosts have been filtered by
 * {@code GlobalHostStatusTracker} based on {@code targetServerType} and other criteria.
 * </p>
 */
public interface LoadBalanceStrategy {

  /**
   * Orders the given list of candidate hosts according to the load balancing strategy.
   * <p>
   * This method is called each time a new connection is being established to determine
   * the order in which hosts should be tried.
   * </p>
   *
   * @param hosts List of candidate hosts that have already been filtered by target server type.
   *              The input list should not be modified.
   * @param clusterId Unique identifier for the cluster, used by stateful strategies
   *                  (e.g., round-robin) to maintain state across connections.
   * @return Ordered list of hosts to attempt connection. The first host in the list
   *         will be tried first, followed by the second, etc.
   */
  List<HostSpec> orderHosts(List<HostSpec> hosts, String clusterId);

  /**
   * Returns the name of this strategy for logging and debugging purposes.
   *
   * @return Strategy name (e.g., "random", "weightedRandom", "roundRobin")
   */
  String getName();
}
