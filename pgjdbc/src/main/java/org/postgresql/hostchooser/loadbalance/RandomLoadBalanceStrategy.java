/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Random load balancing strategy that shuffles hosts randomly.
 * <p>
 * This strategy provides the same behavior as the original {@code loadBalanceHosts=true}
 * implementation, ensuring backward compatibility. Each call to {@link #orderHosts}
 * produces a new random ordering of the input hosts.
 * </p>
 * <p>
 * <b>Characteristics:</b>
 * <ul>
 *   <li>Stateless - no state is maintained between connections</li>
 *   <li>Uniform distribution - all hosts have equal probability over time</li>
 *   <li>Thread-safe - creates a new list for each invocation</li>
 * </ul>
 * </p>
 *
 * @see MultiHostChooser
 */
public class RandomLoadBalanceStrategy implements LoadBalanceStrategy {

  /**
   * Orders hosts by random shuffling.
   * <p>
   * The cluster ID parameter is ignored as this strategy is stateless.
   * </p>
   *
   * @param hosts List of candidate hosts
   * @param clusterId Cluster identifier (ignored by this strategy)
   * @return New list with hosts in random order
   */
  @Override
  public List<HostSpec> orderHosts(List<HostSpec> hosts, String clusterId) {
    // Create a mutable copy and shuffle
    List<HostSpec> result = new ArrayList<>(hosts);
    Collections.shuffle(result);
    return result;
  }

  @Override
  public String getName() {
    return "random";
  }
}
