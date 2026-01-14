/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Round-robin load balancing strategy.
 * <p>
 * Maintains a counter per cluster and distributes connections evenly across hosts
 * by cycling through them in order. Each connection increments the counter, and
 * the next host is selected based on {@code counter % hostCount}.
 * </p>
 * <p>
 * <b>Characteristics:</b>
 * <ul>
 *   <li>Stateful - maintains a counter per cluster using {@link ClusterStateRegistry}</li>
 *   <li>Fair distribution - ensures even load distribution over time</li>
 *   <li>Thread-safe - uses {@link java.util.concurrent.atomic.AtomicInteger}</li>
 *   <li>Non-persistent - counter resets on JVM restart</li>
 * </ul>
 * </p>
 * <p>
 * <b>Example:</b>
 * <pre>
 * Hosts: [host1, host2, host3]
 * Connection 1: [host1, host2, host3]  (starts at host1)
 * Connection 2: [host2, host3, host1]  (starts at host2)
 * Connection 3: [host3, host1, host2]  (starts at host3)
 * Connection 4: [host1, host2, host3]  (cycles back to host1)
 * </pre>
 * </p>
 * <p>
 * <b>Counter Overflow:</b> The counter uses {@link java.util.concurrent.atomic.AtomicInteger}
 * which wraps around at {@link Integer#MAX_VALUE}. The modulo operation ensures valid
 * host selection even after overflow.
 * </p>
 *
 * @see ClusterStateRegistry
 */
public class RoundRobinLoadBalanceStrategy implements LoadBalanceStrategy {

  private final ClusterStateRegistry stateRegistry;

  /**
   * Constructs a round-robin strategy using the specified state registry.
   *
   * @param stateRegistry Registry for maintaining cluster counters
   */
  public RoundRobinLoadBalanceStrategy(ClusterStateRegistry stateRegistry) {
    this.stateRegistry = stateRegistry;
  }

  /**
   * Orders hosts using round-robin selection.
   * <p>
   * The algorithm:
   * <ol>
   *   <li>Get the next counter value for this cluster</li>
   *   <li>Calculate start index: {@code counter % hostCount}</li>
   *   <li>Rotate the host list to start from that index</li>
   * </ol>
   * </p>
   * <p>
   * Thread Safety: This method is thread-safe. Multiple threads can call this
   * concurrently for the same cluster, and each will receive a unique ordering
   * based on the atomically incremented counter.
   * </p>
   *
   * @param hosts List of candidate hosts
   * @param clusterId Unique identifier for the cluster (required for state lookup)
   * @return Ordered list starting from the next host in rotation
   */
  @Override
  public List<HostSpec> orderHosts(List<HostSpec> hosts, String clusterId) {
    if (hosts.isEmpty()) {
      return hosts;
    }

    // Get the next index for this cluster (thread-safe)
    int counter = stateRegistry.getAndIncrementCounter(clusterId);
    // 修复前（有bug） 假设counter已经溢出为 -2147483648，-2147483648%3=-2
    // int startIndex = counter % hosts.size();
    // 修复后，Math.floorMod（数学取模）
    int startIndex = Math.floorMod(counter, hosts.size());

    // Rotate the list starting from startIndex
    List<HostSpec> result = new ArrayList<>(hosts.size());
    for (int i = 0; i < hosts.size(); i++) {
      result.add(hosts.get((startIndex + i) % hosts.size()));
    }

    return result;
  }

  @Override
  public String getName() {
    return "roundRobin";
  }
}
