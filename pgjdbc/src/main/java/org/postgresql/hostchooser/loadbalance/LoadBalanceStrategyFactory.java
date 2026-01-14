/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Properties;

/**
 * Factory for creating appropriate {@link LoadBalanceStrategy} instances based on
 * connection properties.
 * <p>
 * This factory parses the {@code loadBalanceStrategy} parameter and instantiates
 * the corresponding strategy implementation. It also handles parsing of strategy-specific
 * parameters such as {@code loadBalanceWeightFactor} for weighted random strategy.
 * </p>
 * <p>
 * <b>Supported Strategies:</b>
 * <ul>
 *   <li><b>random</b> (default): Randomly shuffle hosts</li>
 *   <li><b>weightedRandom</b>: Select hosts with probability proportional to weights</li>
 *   <li><b>roundRobin</b>: Cycle through hosts in order</li>
 * </ul>
 * </p>
 *
 * @see LoadBalanceStrategy
 * @see PGProperty#LOAD_BALANCE_STRATEGY
 * @see PGProperty#LOAD_BALANCE_WEIGHT_FACTOR
 */
public class LoadBalanceStrategyFactory {

  private static final ClusterStateRegistry CLUSTER_REGISTRY = ClusterStateRegistry.getInstance();

  /**
   * Creates a LoadBalanceStrategy based on connection properties.
   * <p>
   * The strategy is determined by the {@code loadBalanceStrategy} property.
   * If not specified or invalid, defaults to {@code random} strategy.
   * </p>
   *
   * @param info Connection properties
   * @return LoadBalanceStrategy instance
   * @throws PSQLException if strategy name is invalid or weight parameters are malformed
   */
  public static LoadBalanceStrategy createStrategy(Properties info) throws PSQLException {
    String strategyName = PGProperty.LOAD_BALANCE_STRATEGY.get(info);

    // Default to random if not specified
    if (strategyName == null || strategyName.trim().isEmpty()) {
      strategyName = "random";
    }

    strategyName = strategyName.trim();

    if ("random".equalsIgnoreCase(strategyName)) {
      return new RandomLoadBalanceStrategy();
    }

    if ("weightedRandom".equalsIgnoreCase(strategyName)) {
      int[] weights = parseWeights(info);
      return new WeightedRandomLoadBalanceStrategy(weights);
    }

    if ("roundRobin".equalsIgnoreCase(strategyName)) {
      return new RoundRobinLoadBalanceStrategy(CLUSTER_REGISTRY);
    }

    throw new PSQLException(
        GT.tr("Invalid loadBalanceStrategy value: {0}. Valid values are: random, weightedRandom, roundRobin",
            strategyName),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  /**
   * Parses the weight factor parameter into an array of integers.
   * <p>
   * <b>Format:</b> Comma-separated integers, e.g., {@code "3,1,2,0"}
   * </p>
   * <p>
   * <b>Validation Rules:</b>
   * <ul>
   *   <li>Weights must be non-negative integers</li>
   *   <li>If parameter is null or empty, returns empty array (all weights default to 1)</li>
   *   <li>Extra weights beyond host count are ignored (handled by strategy)</li>
   *   <li>Missing weights default to 1 (handled by strategy)</li>
   * </ul>
   * </p>
   *
   * @param info Connection properties
   * @return Array of weight values, or empty array if not specified
   * @throws PSQLException if weight format is invalid or values are negative
   */
  static int[] parseWeights(Properties info) throws PSQLException {
    String weightStr = PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.get(info);

    // No weights specified - all hosts default to weight 1
    if (weightStr == null || weightStr.trim().isEmpty()) {
      return new int[0];
    }

    String[] parts = weightStr.split(",");
    int[] weights = new int[parts.length];

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].trim();
      try {
        weights[i] = Integer.parseInt(part);
        if (weights[i] < 0) {
          throw new PSQLException(
              GT.tr("Weight values must be non-negative integers. Got: {0} at position {1}",
                  part, i + 1),
              PSQLState.INVALID_PARAMETER_VALUE);
        }
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Invalid weight value: {0} at position {1}. Must be a non-negative integer.",
                part, i + 1),
            PSQLState.INVALID_PARAMETER_VALUE, e);
      }
    }

    return weights;
  }

  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private LoadBalanceStrategyFactory() {
    throw new UnsupportedOperationException("Utility class");
  }
}
