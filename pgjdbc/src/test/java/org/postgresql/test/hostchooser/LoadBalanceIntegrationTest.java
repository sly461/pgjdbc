/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.HostChooserFactory;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.hostchooser.loadbalance.ClusterStateRegistry;
import org.postgresql.util.HostSpec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Integration tests for load balancing strategies with MultiHostChooser.
 * <p>
 * Tests the integration of load balancing strategies with the actual
 * connection host selection logic, including interaction with targetServerType.
 * </p>
 */
public class LoadBalanceIntegrationTest {

  private HostSpec host1 = new HostSpec("host1", 5432);
  private HostSpec host2 = new HostSpec("host2", 5432);
  private HostSpec host3 = new HostSpec("host3", 5432);

  /**
   * Test that random strategy is used by default when loadBalanceHosts=true
   * and no strategy is specified.
   */
  @Test
  public void testDefaultStrategyIsRandom() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    // Don't set loadBalanceStrategy - should default to random

    HostSpec[] hosts = {host1, host2, host3};

    // Collect first hosts from multiple iterations
    Set<HostSpec> firstHosts = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      Iterator<CandidateHost> chooser =
          HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
      if (chooser.hasNext()) {
        firstHosts.add(chooser.next().hostSpec);
      }
    }

    // Should see multiple different first hosts due to random shuffling
    assertTrue("Random strategy should produce varied first hosts", firstHosts.size() >= 2);
  }

  /**
   * Test that weighted random strategy respects configured weights.
   */
  @Test
  public void testWeightedRandomStrategy() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    // host1: 50%, host2: 25%, host3: 25%
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "2,1,1");

    HostSpec[] hosts = {host1, host2, host3};
    Map<HostSpec, Integer> distribution = new HashMap<>();

    // Run 1000 iterations to check distribution
    for (int i = 0; i < 1000; i++) {
      Iterator<CandidateHost> chooser =
          HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
      if (chooser.hasNext()) {
        HostSpec firstHost = chooser.next().hostSpec;
        distribution.put(firstHost, distribution.getOrDefault(firstHost, 0) + 1);
      }
    }

    // Verify distribution matches weights (with tolerance for randomness)
    int host1Count = distribution.getOrDefault(host1, 0);
    int host2Count = distribution.getOrDefault(host2, 0);
    int host3Count = distribution.getOrDefault(host3, 0);

    double host1Pct = (double) host1Count / 1000;
    double host2Pct = (double) host2Count / 1000;
    double host3Pct = (double) host3Count / 1000;

    // host1 should be ~50% (allow 40-60%)
    assertTrue("host1 should be selected ~50% of time, got " + host1Pct,
        host1Pct > 0.40 && host1Pct < 0.60);

    // host2 and host3 should each be ~25% (allow 15-35%)
    assertTrue("host2 should be selected ~25% of time, got " + host2Pct,
        host2Pct > 0.15 && host2Pct < 0.35);
    assertTrue("host3 should be selected ~25% of time, got " + host3Pct,
        host3Pct > 0.15 && host3Pct < 0.35);
  }

  /**
   * Test that zero-weight hosts are only used as backup.
   */
  @Test
  public void testWeightedRandomZeroWeightAsBackup() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    // host1: 50%, host2: 50%, host3: backup (0)
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "1,1,0");

    HostSpec[] hosts = {host1, host2, host3};

    // Run 50 iterations
    for (int i = 0; i < 50; i++) {
      Iterator<CandidateHost> chooser =
          HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();

      List<HostSpec> orderedHosts = new ArrayList<>();
      while (chooser.hasNext()) {
        orderedHosts.add(chooser.next().hostSpec);
      }

      assertEquals("Should have all 3 hosts", 3, orderedHosts.size());
      // host3 should always be last (backup)
      assertEquals("host3 should always be last", host3, orderedHosts.get(2));
      // host1 or host2 should be first
      assertTrue("host1 or host2 should be first",
          orderedHosts.get(0).equals(host1) || orderedHosts.get(0).equals(host2));
    }
  }

  /**
   * Test that round-robin strategy cycles through hosts sequentially.
   */
  @Test
  public void testRoundRobinStrategy() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    HostSpec[] hosts = {host1, host2, host3};

    // First connection should start at host1
    Iterator<CandidateHost> chooser1 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
    assertEquals("First connection should start at host1",
        host1, chooser1.next().hostSpec);

    // Second connection should start at host2
    Iterator<CandidateHost> chooser2 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
    assertEquals("Second connection should start at host2",
        host2, chooser2.next().hostSpec);

    // Third connection should start at host3
    Iterator<CandidateHost> chooser3 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
    assertEquals("Third connection should start at host3",
        host3, chooser3.next().hostSpec);

    // Fourth connection should cycle back to host1
    Iterator<CandidateHost> chooser4 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
    assertEquals("Fourth connection should cycle back to host1",
        host1, chooser4.next().hostSpec);
  }

  /**
   * Test that round-robin maintains independent state for different clusters.
   */
  @Test
  public void testRoundRobinIndependentClusters() {
    Properties props1 = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props1, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props1, "roundRobin");

    Properties props2 = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props2, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props2, "roundRobin");

    // Two different host sets (different clusters)
    HostSpec[] hosts1 = {host1, host2};
    HostSpec[] hosts2 = {host2, host3}; // Different set

    // Each cluster should have independent counters
    Iterator<CandidateHost> chooser1a =
        HostChooserFactory.createHostChooser(hosts1, HostRequirement.any, props1).iterator();
    Iterator<CandidateHost> chooser2a =
        HostChooserFactory.createHostChooser(hosts2, HostRequirement.any, props2).iterator();

    HostSpec first1 = chooser1a.next().hostSpec;
    HostSpec first2 = chooser2a.next().hostSpec;

    // Both should start at their respective first hosts
    assertEquals("Cluster1 should start at host1", host1, first1);
    assertEquals("Cluster2 should start at host2", host2, first2);
  }

  /**
   * Test that load balancing works correctly with targetServerType.
   */
  @Test
  public void testLoadBalancingWithTargetServerType() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    HostSpec[] hosts = {host1, host2, host3};

    // Round-robin should apply regardless of targetServerType
    Iterator<CandidateHost> chooser1 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.primary, props).iterator();
    Iterator<CandidateHost> chooser2 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.primary, props).iterator();
    Iterator<CandidateHost> chooser3 =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.primary, props).iterator();

    // Should cycle through hosts even with targetServerType=primary
    HostSpec first1 = chooser1.next().hostSpec;
    HostSpec first2 = chooser2.next().hostSpec;
    HostSpec first3 = chooser3.next().hostSpec;

    // All three hosts should be in the sequence
    Set<HostSpec> seen = new HashSet<>();
    seen.add(first1);
    seen.add(first2);
    seen.add(first3);

    assertEquals("Should see all three hosts in round-robin", 3, seen.size());
  }

  /**
   * Test backward compatibility - loadBalanceHosts=false should not use strategies.
   */
  @Test
  public void testBackwardCompatibilityNoLoadBalance() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "false");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    HostSpec[] hosts = {host1, host2, host3};

    // Should always connect in the same order when loadBalance=false
    List<HostSpec> order1 = collectHostOrder(hosts, HostRequirement.any, props);
    List<HostSpec> order2 = collectHostOrder(hosts, HostRequirement.any, props);

    assertEquals("Without load balancing, order should be same", order1, order2);
    assertEquals("First host should always be host1", host1, order1.get(0));
  }

  /**
   * Test that cluster ID is generated correctly (order-independent).
   */
  @Test
  public void testClusterIdOrderIndependent() {
    HostSpec[] hosts1 = {host1, host2, host3};
    HostSpec[] hosts2 = {host3, host1, host2}; // Different order

    String id1 = ClusterStateRegistry.generateClusterId(hosts1, "roundRobin");
    String id2 = ClusterStateRegistry.generateClusterId(hosts2, "roundRobin");

    assertEquals("Cluster IDs should be same regardless of host order", id1, id2);
  }

  /**
   * Test that different strategies for same hosts produce different cluster IDs.
   */
  @Test
  public void testClusterIdStrategyDependent() {
    HostSpec[] hosts = {host1, host2, host3};

    String id1 = ClusterStateRegistry.generateClusterId(hosts, "roundRobin");
    String id2 = ClusterStateRegistry.generateClusterId(hosts, "random");

    assertNotEquals("Different strategies should have different cluster IDs", id1, id2);
  }

  /**
   * Test that invalid strategy name throws exception.
   */
  @Test
  public void testInvalidStrategyThrowsException() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "invalidStrategy");

    HostSpec[] hosts = {host1, host2, host3};

    try {
      HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
      fail("Should throw exception for invalid strategy");
    } catch (Exception e) {
      assertTrue("Exception should mention invalid strategy",
          e.getMessage().toLowerCase().contains("invalid") ||
          e.getCause() != null && e.getCause().getMessage().toLowerCase().contains("invalid"));
    }
  }

  /**
   * Test that empty host list doesn't cause errors.
   */
  @Test
  public void testEmptyHostList() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    HostSpec[] hosts = {};

    Iterator<CandidateHost> chooser =
        HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();

    assertTrue("Empty host list should produce empty iterator", !chooser.hasNext());
  }

  /**
   * Test that single host doesn't cause errors with any strategy.
   */
  @Test
  public void testSingleHost() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    HostSpec[] hosts = {host1};

    // Multiple connections should all return the same single host
    for (int i = 0; i < 5; i++) {
      Iterator<CandidateHost> chooser =
          HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props).iterator();
      assertTrue("Single host should be available", chooser.hasNext());
      assertEquals("Should always return the same host", host1, chooser.next().hostSpec);
    }
  }

  /**
   * Helper method to collect host order from a chooser.
   */
  private List<HostSpec> collectHostOrder(HostSpec[] hosts, HostRequirement requirement,
                                          Properties props) {
    List<HostSpec> order = new ArrayList<>();
    Iterator<CandidateHost> chooser =
        HostChooserFactory.createHostChooser(hosts, requirement, props).iterator();
    while (chooser.hasNext()) {
      order.add(chooser.next().hostSpec);
    }
    return order;
  }
}
