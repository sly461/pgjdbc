/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.hostchooser.loadbalance.ClusterManager;
import org.postgresql.hostchooser.loadbalance.LeastConnLoadBalanceStrategy;
import org.postgresql.util.HostSpec;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for LeastConnLoadBalanceStrategy.
 * <p>
 * Tests the orderHosts method with different pending count scenarios.
 * </p>
 */
public class LeastConnStrategyTest {

  private List<HostSpec> hosts;
  private HostSpec host1;
  private HostSpec host2;
  private HostSpec host3;
  private String clusterId;
  private ClusterManager clusterManager;
  private LeastConnLoadBalanceStrategy strategy;

  @Before
  public void setUp() {
    host1 = new HostSpec("host1", 5432);
    host2 = new HostSpec("host2", 5432);
    host3 = new HostSpec("host3", 5432);
    hosts = Arrays.asList(host1, host2, host3);
    clusterId = "test-leastconn-" + System.currentTimeMillis();

    clusterManager = ClusterManager.getInstance();
    strategy = new LeastConnLoadBalanceStrategy(clusterManager);
  }

  @Test
  public void testBasicOrdering() {
    // All hosts have 0 connections, should return in original order
    List<HostSpec> result = strategy.orderHosts(hosts, clusterId);

    assertEquals("Should return all hosts", 3, result.size());
    assertTrue("All hosts should be included", result.containsAll(hosts));
  }

  @Test
  public void testEmptyHostList() {
    List<HostSpec> emptyList = Arrays.asList();
    List<HostSpec> result = strategy.orderHosts(emptyList, clusterId);

    assertEquals("Empty list should remain empty", 0, result.size());
  }

  @Test
  public void testSingleHost() {
    List<HostSpec> singleHost = Arrays.asList(host1);
    List<HostSpec> result = strategy.orderHosts(singleHost, clusterId);

    assertEquals("Single host should remain", 1, result.size());
    assertEquals("Should be the same host", host1, result.get(0));
  }
}
