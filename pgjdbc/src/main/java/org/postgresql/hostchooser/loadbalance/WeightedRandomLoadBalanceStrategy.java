/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Weighted random load balancing strategy.
 * <p>
 * Hosts are selected randomly with probability proportional to their weights.
 * Higher weight values increase the likelihood of selecting a host.
 * </p>
 * <p>
 * <b>Weight Handling:</b>
 * <ul>
 *   <li><b>Weight &gt; 0:</b> Normal hosts, selected according to weight distribution</li>
 *   <li><b>Weight = 0:</b> Backup hosts, only tried after all weighted hosts fail</li>
 *   <li><b>Missing weights:</b> Default to 1 (equal weight)</li>
 *   <li><b>Extra weights:</b> Silently ignored if more weights than hosts</li>
 * </ul>
 * <p>
 * <b>Example:</b>
 * <pre>
 * Hosts: [host1, host2, host3]
 * Weights: [3, 1, 0]
 * Result: host1 has 75% chance, host2 has 25% chance, host3 is backup only
 * </pre>
 *
 * @see LoadBalanceStrategyFactory#parseWeights
 */
public class WeightedRandomLoadBalanceStrategy implements LoadBalanceStrategy {

  private final int[] weights;
  private final Random random;

  /**
   * Constructs a weighted random strategy with the specified weights.
   *
   * @param weights Array of weights corresponding to hosts.
   *                If null or empty, all hosts default to weight 1.
   *                If shorter than host list, missing values default to 1.
   *                Weight 0 means host is backup only.
   */
  public WeightedRandomLoadBalanceStrategy(int[] weights) {
    this.weights = weights != null ? weights.clone() : new int[0];
    this.random = new Random();
  }

  /**
   * Orders hosts using weighted random selection.
   * <p>
   * Algorithm:
   * <ol>
   *   <li>Separate hosts into weighted (weight &gt; 0) and backup (weight = 0) lists</li>
   *   <li>Repeatedly select from weighted hosts based on probability distribution</li>
   *   <li>Append backup hosts at the end (shuffled)</li>
   * </ol>
   * <p>
   * The cluster ID parameter is ignored as this strategy is stateless.
   * </p>
   *
   * @param hosts List of candidate hosts
   * @param clusterId Cluster identifier (ignored by this strategy)
   * @return Ordered list with weighted hosts first, backup hosts last
   */
  @Override
  public List<HostSpec> orderHosts(List<HostSpec> hosts, String clusterId) {
    if (hosts.isEmpty()) {
      return hosts;
    }

    // Separate hosts into weighted and zero-weight (backup) lists
    List<HostSpec> weightedHosts = new ArrayList<>();
    List<Integer> effectiveWeights = new ArrayList<>();
    List<HostSpec> backupHosts = new ArrayList<>();

    for (int i = 0; i < hosts.size(); i++) {
      int weight = getWeight(i);
      if (weight > 0) {
        weightedHosts.add(hosts.get(i));
        effectiveWeights.add(weight);
      } else {
        backupHosts.add(hosts.get(i));
      }
    }

    // Order weighted hosts by weighted random selection
    List<HostSpec> result = new ArrayList<>();
    while (!weightedHosts.isEmpty()) {
      int selectedIndex = selectByWeight(effectiveWeights);
      result.add(weightedHosts.remove(selectedIndex));
      effectiveWeights.remove(selectedIndex);
    }

    // Add backup hosts at the end (shuffled for randomness)
    Collections.shuffle(backupHosts, random);
    result.addAll(backupHosts);

    return result;
  }

  /**
   * Gets the weight for the host at the specified index.
   *
   * @param index Host index
   * @return Weight value (defaults to 1 if index beyond weights array)
   */
  private int getWeight(int index) {
    if (index >= weights.length) {
      return 1; // Default weight for hosts without explicit weight
    }
    return Math.max(0, weights[index]); // Ensure non-negative
  }

  /**
   * Selects an index from the weights list based on weighted probability.
   * <p>
   * Uses the cumulative weight approach:
   * <ul>
   *   <li>Calculate total weight = sum of all weights</li>
   *   <li>Generate random number in [0, totalWeight)</li>
   *   <li>Find the index where cumulative weight exceeds random number</li>
   * </ul>
   * </p>
   *
   * @param weights List of positive weights
   * @return Selected index (0-based)
   */
  private int selectByWeight(List<Integer> weights) {
    int totalWeight = weights.stream().mapToInt(Integer::intValue).sum();

    // Handle edge case where all weights are 0 (should not happen in practice)
    if (totalWeight == 0) {
      return random.nextInt(weights.size());
    }

    int randomValue = random.nextInt(totalWeight);

    int cumulativeWeight = 0;
    for (int i = 0; i < weights.size(); i++) {
      cumulativeWeight += weights.get(i);
      if (randomValue < cumulativeWeight) {
        return i;
      }
    }

    // Fallback (should not reach here)
    return weights.size() - 1;
  }

  @Override
  public String getName() {
    return "weightedRandom";
  }
}
