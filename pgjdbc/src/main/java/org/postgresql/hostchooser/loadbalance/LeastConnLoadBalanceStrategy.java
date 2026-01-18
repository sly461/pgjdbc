/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Least connections load balancing strategy.
 * <p>
 * This strategy orders candidate hosts by their active connection count (ascending),
 * prioritizing hosts with fewer connections. It tracks connections established through
 * the current driver in the current cluster using the leastConn mode, and checks
 * connection validity during each host ordering operation.
 * </p>
 * <p>
 * Connection tracking uses weak references to avoid preventing garbage collection.
 * Stale connections (closed or garbage collected) are automatically cleaned up during
 * the ordering process.
 * </p>
 *
 * <h3>Thread Safety</h3>
 * This strategy is thread-safe and can be used concurrently by multiple threads.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * jdbc:postgresql://host1:5432,host2:5432,host3:5432/mydb
 *   ?loadBalanceHosts=true
 *   &amp;loadBalanceStrategy=leastConn
 * </pre>
 *
 * @see ClusterManager
 */
public class LeastConnLoadBalanceStrategy implements LoadBalanceStrategy {
  private static final Logger LOGGER = Logger.getLogger(LeastConnLoadBalanceStrategy.class.getName());

  private final ClusterManager clusterManager;

  /**
   * Creates a new least connections load balancing strategy.
   *
   * @param clusterManager The cluster manager for tracking connections
   */
  public LeastConnLoadBalanceStrategy(ClusterManager clusterManager) {
    this.clusterManager = clusterManager;
  }

  /**
   * Orders hosts by availability and total connection count (ascending).
   * <p>
   * Hosts are ordered by:
   * <ol>
   *   <li>Availability (available hosts first, unavailable hosts last)</li>
   *   <li>Total connection count (active + pending connections)</li>
   * </ol>
   * <p>
   * Note: Pre-allocation (pending count increment) is now handled dynamically in
   * ConnectionFactoryImpl before each connection attempt, not here. This ensures
   * that the correct host is pre-allocated even when the first host fails and
   * we need to try subsequent hosts.
   * </p>
   *
   * @param hosts List of candidate hosts
   * @param clusterId Unique cluster identifier
   * @return Ordered list of hosts (available hosts with least connections first)
   */
  @Override
  public List<HostSpec> orderHosts(List<HostSpec> hosts, String clusterId) {
    if (hosts.isEmpty()) {
      return hosts;
    }

    // Get total connection count (active + pending) and availability for each host
    Map<HostSpec, Integer> totalCounts = new HashMap<>();
    Map<HostSpec, Boolean> hostAvailability = new HashMap<>();

    for (HostSpec host : hosts) {
      int totalCount = clusterManager.getTotalConnectionCount(clusterId, host);
      boolean available = clusterManager.isHostAvailable(clusterId, host);
      totalCounts.put(host, totalCount);
      hostAvailability.put(host, available);
    }

    // Sort by availability first (unavailable hosts last), then by total connection count
    List<HostSpec> result = new ArrayList<>(hosts);
    LOGGER.log(Level.INFO, "Ordering hosts: ");
    for (HostSpec host : result) {
      LOGGER.log(Level.INFO, "  {0} (available: {1}, total connections: {2})",
          new Object[]{host, hostAvailability.get(host), totalCounts.get(host)});
    }
    result.sort((h1, h2) -> {
      boolean a1 = hostAvailability.getOrDefault(h1, true);
      boolean a2 = hostAvailability.getOrDefault(h2, true);

      // Unavailable hosts go to the end
      if (!a1 && a2) {
        return 1;
      }
      if (a1 && !a2) {
        return -1;
      }

      // Both have same availability, sort by total connection count
      return Integer.compare(
          totalCounts.getOrDefault(h1, 0),
          totalCounts.getOrDefault(h2, 0)
      );
    });
    LOGGER.log(Level.INFO, "Ordered hosts: {0}", result);

    return result;
  }

  @Override
  public String getName() {
    return "leastConn";
  }
}
