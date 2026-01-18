/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.loadbalance.ClusterManager;
import org.postgresql.hostchooser.loadbalance.LoadBalanceStrategy;
import org.postgresql.hostchooser.loadbalance.LoadBalanceStrategyFactory;
import org.postgresql.hostchooser.loadbalance.RandomLoadBalanceStrategy;
import org.postgresql.hostchooser.loadbalance.RoundRobinLoadBalanceStrategy;
import org.postgresql.hostchooser.loadbalance.WeightedRandomLoadBalanceStrategy;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for load balancing strategies.
 * <p>
 * Tests cover:
 * - Individual strategy implementations
 * - ClusterManager functionality
 * - LoadBalanceStrategyFactory
 * - Weight parsing and validation
 * - Thread safety
 * - Edge cases
 * </p>
 */
public class LoadBalanceStrategyTest {

  private List<HostSpec> hosts;
  private HostSpec host1;
  private HostSpec host2;
  private HostSpec host3;
  private String clusterId;

  @Before
  public void setUp() {
    host1 = new HostSpec("host1", 5432);
    host2 = new HostSpec("host2", 5432);
    host3 = new HostSpec("host3", 5432);
    hosts = Arrays.asList(host1, host2, host3);
    clusterId = "test-cluster";
  }

  // ============================================
  // RandomLoadBalanceStrategy Tests
  // ============================================

  @Test
  public void testRandomStrategy_allHostsIncluded() {
    LoadBalanceStrategy strategy = new RandomLoadBalanceStrategy();
    List<HostSpec> result = strategy.orderHosts(hosts, clusterId);

    assertEquals("All hosts should be included", hosts.size(), result.size());
    assertTrue("Should contain host1", result.contains(host1));
    assertTrue("Should contain host2", result.contains(host2));
    assertTrue("Should contain host3", result.contains(host3));
  }

  @Test
  public void testRandomStrategy_differentOrderings() {
    LoadBalanceStrategy strategy = new RandomLoadBalanceStrategy();
    Set<String> seenOrderings = new HashSet<>();

    // Run 20 times to get different orderings (probabilistic)
    for (int i = 0; i < 20; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);
      seenOrderings.add(result.toString());
    }

    // With 3 hosts, there are 6 possible orderings
    // We should see at least 2 different ones in 20 attempts
    assertTrue("Should produce randomized orderings", seenOrderings.size() >= 2);
  }

  @Test
  public void testRandomStrategy_emptyList() {
    LoadBalanceStrategy strategy = new RandomLoadBalanceStrategy();
    List<HostSpec> result = strategy.orderHosts(new ArrayList<>(), clusterId);
    assertEquals("Empty list should remain empty", 0, result.size());
  }

  @Test
  public void testRandomStrategy_singleHost() {
    LoadBalanceStrategy strategy = new RandomLoadBalanceStrategy();
    List<HostSpec> singleHost = Arrays.asList(host1);
    List<HostSpec> result = strategy.orderHosts(singleHost, clusterId);
    assertEquals("Single host should remain", 1, result.size());
    assertEquals("Should be the same host", host1, result.get(0));
  }

  // ============================================
  // WeightedRandomLoadBalanceStrategy Tests
  // ============================================

  @Test
  public void testWeightedRandomStrategy_allEqualWeights() {
    int[] weights = {1, 1, 1};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    Map<HostSpec, Integer> distribution = new HashMap<>();
    int iterations = 1000;

    for (int i = 0; i < iterations; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);
      HostSpec firstHost = result.get(0);
      distribution.put(firstHost, distribution.getOrDefault(firstHost, 0) + 1);
    }

    // With equal weights, each host should be selected roughly 1/3 of the time
    // Allow for statistical variance (between 25% and 40%)
    for (HostSpec host : hosts) {
      int count = distribution.getOrDefault(host, 0);
      double percentage = (double) count / iterations;
      assertTrue("Host " + host + " should be selected ~33% of time, got " + percentage,
          percentage > 0.25 && percentage < 0.40);
    }
  }

  @Test
  public void testWeightedRandomStrategy_differentWeights() {
    // host1: 50%, host2: 25%, host3: 25%
    int[] weights = {2, 1, 1};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    Map<HostSpec, Integer> distribution = new HashMap<>();
    int iterations = 1000;

    for (int i = 0; i < iterations; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);
      HostSpec firstHost = result.get(0);
      distribution.put(firstHost, distribution.getOrDefault(firstHost, 0) + 1);
    }

    // host1 should be selected ~50% of time
    double host1Percentage = (double) distribution.getOrDefault(host1, 0) / iterations;
    assertTrue("host1 should be selected ~50% of time, got " + host1Percentage,
        host1Percentage > 0.40 && host1Percentage < 0.60);

    // host2 and host3 should each be ~25%
    double host2Percentage = (double) distribution.getOrDefault(host2, 0) / iterations;
    assertTrue("host2 should be selected ~25% of time, got " + host2Percentage,
        host2Percentage > 0.15 && host2Percentage < 0.35);

    double host3Percentage = (double) distribution.getOrDefault(host3, 0) / iterations;
    assertTrue("host3 should be selected ~25% of time, got " + host3Percentage,
        host3Percentage > 0.15 && host3Percentage < 0.35);
  }

  @Test
  public void testWeightedRandomStrategy_zeroWeightAsBackup() {
    // host1: 50%, host2: 50%, host3: backup (0)
    int[] weights = {1, 1, 0};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    for (int i = 0; i < 50; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);

      // host3 should always be last (backup)
      assertEquals("host3 should always be last", host3, result.get(2));

      // host1 and host2 should be in first two positions
      assertTrue("host1 or host2 should be first",
          result.get(0).equals(host1) || result.get(0).equals(host2));
      assertTrue("host1 or host2 should be second",
          result.get(1).equals(host1) || result.get(1).equals(host2));
    }
  }

  @Test
  public void testWeightedRandomStrategy_allZeroWeights() {
    // All hosts are backup - should shuffle them
    int[] weights = {0, 0, 0};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    List<HostSpec> result = strategy.orderHosts(hosts, clusterId);

    // All hosts should be included
    assertEquals("All hosts should be included", 3, result.size());
    assertTrue("Should contain all hosts", result.containsAll(hosts));
  }

  @Test
  public void testWeightedRandomStrategy_missingWeights() {
    // Only 2 weights provided for 3 hosts - third should default to 1
    int[] weights = {2, 1};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    Map<HostSpec, Integer> distribution = new HashMap<>();
    int iterations = 1000;

    for (int i = 0; i < iterations; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);
      HostSpec firstHost = result.get(0);
      distribution.put(firstHost, distribution.getOrDefault(firstHost, 0) + 1);
    }

    // host1: 50%, host2: 25%, host3: 25% (default weight 1)
    double host1Percentage = (double) distribution.getOrDefault(host1, 0) / iterations;
    assertTrue("host1 should be selected ~50% of time", host1Percentage > 0.40 && host1Percentage < 0.60);

    // host3 should still be selected even though weight is missing
    assertTrue("host3 should be selected with default weight",
        distribution.containsKey(host3) && distribution.get(host3) > 100);
  }

  @Test
  public void testWeightedRandomStrategy_emptyWeights() {
    // No weights - all should default to 1
    int[] weights = {};
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(weights);

    Map<HostSpec, Integer> distribution = new HashMap<>();
    int iterations = 1000;

    for (int i = 0; i < iterations; i++) {
      List<HostSpec> result = strategy.orderHosts(hosts, clusterId);
      HostSpec firstHost = result.get(0);
      distribution.put(firstHost, distribution.getOrDefault(firstHost, 0) + 1);
    }

    // All hosts should be selected roughly equally
    for (HostSpec host : hosts) {
      double percentage = (double) distribution.getOrDefault(host, 0) / iterations;
      assertTrue("Each host should be selected ~33% of time", percentage > 0.25 && percentage < 0.40);
    }
  }

  @Test
  public void testWeightedRandomStrategy_nullWeights() {
    LoadBalanceStrategy strategy = new WeightedRandomLoadBalanceStrategy(null);
    List<HostSpec> result = strategy.orderHosts(hosts, clusterId);

    assertEquals("All hosts should be included", 3, result.size());
    assertTrue("Should contain all hosts", result.containsAll(hosts));
  }

  // ============================================
  // RoundRobinLoadBalanceStrategy Tests
  // ============================================

  @Test
  public void testRoundRobinStrategy_sequentialOrdering() {
    ClusterManager registry = ClusterManager.getInstance();
    LoadBalanceStrategy strategy = new RoundRobinLoadBalanceStrategy(registry);
    String testClusterId = "test-roundrobin-" + System.currentTimeMillis();

    // First call should start at index 0
    List<HostSpec> result1 = strategy.orderHosts(hosts, testClusterId);
    assertEquals("First call starts at host1", host1, result1.get(0));
    assertEquals("Then host2", host2, result1.get(1));
    assertEquals("Then host3", host3, result1.get(2));

    // Second call should start at index 1
    List<HostSpec> result2 = strategy.orderHosts(hosts, testClusterId);
    assertEquals("Second call starts at host2", host2, result2.get(0));
    assertEquals("Then host3", host3, result2.get(1));
    assertEquals("Then host1", host1, result2.get(2));

    // Third call should start at index 2
    List<HostSpec> result3 = strategy.orderHosts(hosts, testClusterId);
    assertEquals("Third call starts at host3", host3, result3.get(0));
    assertEquals("Then host1", host1, result3.get(1));
    assertEquals("Then host2", host2, result3.get(2));

    // Fourth call should cycle back to index 0
    List<HostSpec> result4 = strategy.orderHosts(hosts, testClusterId);
    assertEquals("Fourth call cycles back to host1", host1, result4.get(0));
  }

  @Test
  public void testRoundRobinStrategy_differentClusters() {
    ClusterManager registry = ClusterManager.getInstance();
    LoadBalanceStrategy strategy = new RoundRobinLoadBalanceStrategy(registry);

    String cluster1 = "cluster1-" + System.currentTimeMillis();
    String cluster2 = "cluster2-" + System.currentTimeMillis();

    // Each cluster should have independent counters
    List<HostSpec> result1a = strategy.orderHosts(hosts, cluster1);
    List<HostSpec> result2a = strategy.orderHosts(hosts, cluster2);

    assertEquals("Cluster1 first call starts at host1", host1, result1a.get(0));
    assertEquals("Cluster2 first call starts at host1", host1, result2a.get(0));

    List<HostSpec> result1b = strategy.orderHosts(hosts, cluster1);
    List<HostSpec> result2b = strategy.orderHosts(hosts, cluster2);

    assertEquals("Cluster1 second call starts at host2", host2, result1b.get(0));
    assertEquals("Cluster2 second call starts at host2", host2, result2b.get(0));
  }

  @Test
  public void testRoundRobinStrategy_threadSafety() throws InterruptedException {
    ClusterManager registry = ClusterManager.getInstance();
    LoadBalanceStrategy strategy = new RoundRobinLoadBalanceStrategy(registry);
    String testClusterId = "test-threadsafe-" + System.currentTimeMillis();

    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    Map<Integer, AtomicInteger> positionCounts = new HashMap<>();
    for (int i = 0; i < hosts.size(); i++) {
      positionCounts.put(i, new AtomicInteger(0));
    }

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          List<HostSpec> result = strategy.orderHosts(hosts, testClusterId);
          // Count which position each host appeared first
          int firstIndex = hosts.indexOf(result.get(0));
          positionCounts.get(firstIndex).incrementAndGet();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown(); // Start all threads
    doneLatch.await(); // Wait for all to complete
    executor.shutdown();

    // Each position should be used roughly equally (within 20% variance)
    int expectedCount = threadCount / hosts.size();
    for (int i = 0; i < hosts.size(); i++) {
      int count = positionCounts.get(i).get();
      assertTrue("Position " + i + " should be used ~" + expectedCount + " times, got " + count,
          count > expectedCount * 0.8 && count < expectedCount * 1.5);
    }
  }

  @Test
  public void testRoundRobinStrategy_emptyList() {
    ClusterManager registry = ClusterManager.getInstance();
    LoadBalanceStrategy strategy = new RoundRobinLoadBalanceStrategy(registry);
    List<HostSpec> result = strategy.orderHosts(new ArrayList<>(), clusterId);
    assertEquals("Empty list should remain empty", 0, result.size());
  }

  @Test
  public void testRoundRobinStrategy_singleHost() {
    ClusterManager registry = ClusterManager.getInstance();
    LoadBalanceStrategy strategy = new RoundRobinLoadBalanceStrategy(registry);
    String testClusterId = "test-single-" + System.currentTimeMillis();
    List<HostSpec> singleHost = Arrays.asList(host1);

    // Should always return the same host
    for (int i = 0; i < 5; i++) {
      List<HostSpec> result = strategy.orderHosts(singleHost, testClusterId);
      assertEquals("Should always return single host", 1, result.size());
      assertEquals("Should be host1", host1, result.get(0));
    }
  }

  // ============================================
  // ClusterManager Tests
  // ============================================

  @Test
  public void testClusterManager_singleton() {
    ClusterManager registry1 = ClusterManager.getInstance();
    ClusterManager registry2 = ClusterManager.getInstance();
    assertTrue("Should return same instance", registry1 == registry2);
  }

  @Test
  public void testClusterManager_generateClusterId() {
    HostSpec[] hosts1 = {
        new HostSpec("host1", 5432),
        new HostSpec("host2", 5433),
        new HostSpec("host3", 5434)
    };

    HostSpec[] hosts2 = {
        new HostSpec("host3", 5434),
        new HostSpec("host1", 5432),
        new HostSpec("host2", 5433)
    };

    String id1 = ClusterManager.generateClusterId(hosts1, "roundRobin");
    String id2 = ClusterManager.generateClusterId(hosts2, "roundRobin");

    assertEquals("Cluster IDs should be same regardless of order", id1, id2);
    assertTrue("Cluster ID should contain strategy", id1.contains("roundRobin"));
  }

  @Test
  public void testClusterManager_differentStrategiesDifferentIds() {
    HostSpec[] hosts = {new HostSpec("host1", 5432), new HostSpec("host2", 5433)};

    String id1 = ClusterManager.generateClusterId(hosts, "roundRobin");
    String id2 = ClusterManager.generateClusterId(hosts, "random");

    assertTrue("Different strategies should have different IDs", !id1.equals(id2));
  }

  @Test
  public void testClusterManager_counterIncrement() {
    ClusterManager registry = ClusterManager.getInstance();
    String testClusterId = "test-counter-" + System.currentTimeMillis();

    int counter1 = registry.getAndIncrementCounter(testClusterId);
    int counter2 = registry.getAndIncrementCounter(testClusterId);
    int counter3 = registry.getAndIncrementCounter(testClusterId);

    assertEquals("Counters should increment", counter1 + 1, counter2);
    assertEquals("Counters should increment", counter2 + 1, counter3);
  }

  @Test
  public void testClusterManager_independentCounters() {
    ClusterManager registry = ClusterManager.getInstance();
    String cluster1 = "test-cluster1-" + System.currentTimeMillis();
    String cluster2 = "test-cluster2-" + System.currentTimeMillis();

    registry.getAndIncrementCounter(cluster1);
    registry.getAndIncrementCounter(cluster1);
    int cluster2Counter = registry.getAndIncrementCounter(cluster2);

    assertEquals("cluster2 should start at 0", 0, cluster2Counter);
  }

  // ============================================
  // LoadBalanceStrategyFactory Tests
  // ============================================

  @Test
  public void testFactory_createRandomStrategy() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "random");

    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);

    assertNotNull("Strategy should not be null", strategy);
    assertTrue("Should be RandomLoadBalanceStrategy",
        strategy instanceof RandomLoadBalanceStrategy);
    assertEquals("Strategy name should be random", "random", strategy.getName());
  }

  @Test
  public void testFactory_createWeightedRandomStrategy() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "3,1,2");

    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);

    assertNotNull("Strategy should not be null", strategy);
    assertTrue("Should be WeightedRandomLoadBalanceStrategy",
        strategy instanceof WeightedRandomLoadBalanceStrategy);
    assertEquals("Strategy name should be weightedRandom", "weightedRandom", strategy.getName());
  }

  @Test
  public void testFactory_createRoundRobinStrategy() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);

    assertNotNull("Strategy should not be null", strategy);
    assertTrue("Should be RoundRobinLoadBalanceStrategy",
        strategy instanceof RoundRobinLoadBalanceStrategy);
    assertEquals("Strategy name should be roundRobin", "roundRobin", strategy.getName());
  }

  @Test
  public void testFactory_defaultToRandom() throws PSQLException {
    Properties props = new Properties();
    // Don't set loadBalanceStrategy - should default to random

    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);

    assertTrue("Should default to RandomLoadBalanceStrategy",
        strategy instanceof RandomLoadBalanceStrategy);
  }

  @Test
  public void testFactory_caseInsensitive() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "RoUnDrObIn");

    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);

    assertTrue("Should handle case-insensitive strategy names",
        strategy instanceof RoundRobinLoadBalanceStrategy);
  }

  @Test
  public void testFactory_invalidStrategy() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "invalidStrategy");

    try {
      LoadBalanceStrategyFactory.createStrategy(props);
      fail("Should throw PSQLException for invalid strategy");
    } catch (PSQLException e) {
      assertTrue("Exception message should mention valid values",
          e.getMessage().contains("random") && e.getMessage().contains("weightedRandom"));
    }
  }

  @Test
  public void testFactory_weightsWithValidFormat() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "3,1,2,0");

    // Should not throw exception - valid weights
    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);
    assertNotNull("Strategy should be created successfully", strategy);
    assertTrue("Should be WeightedRandomLoadBalanceStrategy",
        strategy instanceof WeightedRandomLoadBalanceStrategy);
  }

  @Test
  public void testFactory_weightsWithSpaces() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, " 3 , 1 , 2 ");

    // Should handle spaces correctly
    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);
    assertNotNull("Strategy should be created successfully", strategy);
  }

  @Test
  public void testFactory_emptyWeights() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "");

    // Empty weights should be acceptable (all default to 1)
    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);
    assertNotNull("Strategy should be created successfully", strategy);
  }

  @Test
  public void testFactory_nullWeights() throws PSQLException {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    // Don't set weight factor

    // Null weights should be acceptable (all default to 1)
    LoadBalanceStrategy strategy = LoadBalanceStrategyFactory.createStrategy(props);
    assertNotNull("Strategy should be created successfully", strategy);
  }

  @Test
  public void testFactory_negativeWeight() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "3,-1,2");

    try {
      LoadBalanceStrategyFactory.createStrategy(props);
      fail("Should throw PSQLException for negative weight");
    } catch (PSQLException e) {
      assertTrue("Exception message should mention non-negative",
          e.getMessage().toLowerCase().contains("non-negative"));
    }
  }

  @Test
  public void testFactory_invalidWeightFormat() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "3,abc,2");

    try {
      LoadBalanceStrategyFactory.createStrategy(props);
      fail("Should throw PSQLException for invalid format");
    } catch (PSQLException e) {
      assertTrue("Exception message should mention invalid",
          e.getMessage().toLowerCase().contains("invalid"));
    }
  }
}
