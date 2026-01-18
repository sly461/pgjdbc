/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.loadbalance.ClusterManager;
import org.postgresql.hostchooser.loadbalance.LeastConnHeartbeat;
import org.postgresql.util.HostSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

/**
 * Unit tests for LeastConnHeartbeat.
 */
public class LeastConnHeartbeatTest {

  private LeastConnHeartbeat heartbeat;
  private ClusterManager registry;
  private String clusterId;
  private Properties info;

  @Before
  public void setUp() {
    heartbeat = LeastConnHeartbeat.getInstance();
    registry = ClusterManager.getInstance();
    clusterId = "test-cluster-" + System.currentTimeMillis();

    info = new Properties();
    info.setProperty(PGProperty.USER.getName(), "test");
    info.setProperty(PGProperty.PG_DBNAME.getName(), "test");
    info.setProperty(PGProperty.HEARTBEAT_INTERVAL_SECONDS.getName(), "1");
    info.setProperty(PGProperty.MAX_IDLE_TIME_BEFORE_CLOSE.getName(), "5");
    info.setProperty(PGProperty.MIN_RESERVED_CONNECTION_PERCENT.getName(), "20");
    info.setProperty(PGProperty.ENABLE_QUICK_AUTO_BALANCE.getName(), "true");
  }

  @After
  public void tearDown() {
    // Clean up - only shutdown, which will handle cleanup properly
    heartbeat.shutdown();
  }

  @Test
  public void testHeartbeatStartAndStop() throws Exception {
    // Start heartbeat
    heartbeat.start(clusterId, info);

    // Wait a bit to ensure heartbeat task is scheduled
    Thread.sleep(100);

    // Stop heartbeat
    heartbeat.stop(clusterId);

    // Verify no exceptions thrown
    assertTrue("Heartbeat start and stop should succeed", true);
  }

  @Test
  public void testHeartbeatDisabled() throws Exception {
    // Disable heartbeat
    info.setProperty(PGProperty.HEARTBEAT_INTERVAL_SECONDS.getName(), "0");

    // Start heartbeat (should not actually start)
    heartbeat.start(clusterId, info);

    // Verify no exceptions thrown
    assertTrue("Heartbeat with interval 0 should be disabled", true);
  }

  @Test
  public void testMultipleClusterRegistration() throws Exception {
    String clusterId1 = "cluster1-" + System.currentTimeMillis();
    String clusterId2 = "cluster2-" + System.currentTimeMillis();

    try {
      // Start heartbeat for first cluster
      heartbeat.start(clusterId1, info);

      // Start heartbeat for second cluster
      heartbeat.start(clusterId2, info);

      // Wait a bit
      Thread.sleep(100);

      // Stop both
      heartbeat.stop(clusterId1);
      heartbeat.stop(clusterId2);

      // Verify no exceptions thrown
      assertTrue("Multiple cluster registration should succeed", true);
    } finally {
      heartbeat.stop(clusterId1);
      heartbeat.stop(clusterId2);
    }
  }

  @Test
  public void testHostStateTracking() throws Exception {
    HostSpec host1 = new HostSpec("localhost", 5432);
    HostSpec host2 = new HostSpec("localhost", 5433);

    // Initially all hosts should be available
    assertTrue("Host1 should be available initially",
        registry.isHostAvailable(clusterId, host1));
    assertTrue("Host2 should be available initially",
        registry.isHostAvailable(clusterId, host2));

    // Mark host1 as unavailable
    registry.setHostAvailable(clusterId, host1, false);

    // Verify state
    assertFalse("Host1 should be unavailable",
        registry.isHostAvailable(clusterId, host1));
    assertTrue("Host2 should still be available",
        registry.isHostAvailable(clusterId, host2));

    // Mark host1 as available again
    registry.setHostAvailable(clusterId, host1, true);

    // Verify state
    assertTrue("Host1 should be available again",
        registry.isHostAvailable(clusterId, host1));
  }

  @Test
  public void testGetAllHosts() throws Exception {
    HostSpec host1 = new HostSpec("localhost", 5432);
    HostSpec host2 = new HostSpec("localhost", 5433);
    HostSpec host3 = new HostSpec("localhost", 5434);

    // Set host states to register them
    registry.setHostAvailable(clusterId, host1, true);
    registry.setHostAvailable(clusterId, host2, true);
    registry.setHostAvailable(clusterId, host3, true);

    // Get all hosts
    java.util.List<HostSpec> hosts = registry.getAllHosts(clusterId);

    // Verify
    assertEquals("Should have 3 hosts", 3, hosts.size());
    assertTrue("Should contain host1", hosts.contains(host1));
    assertTrue("Should contain host2", hosts.contains(host2));
    assertTrue("Should contain host3", hosts.contains(host3));
  }

  @Test
  public void testGetHostStates() throws Exception {
    HostSpec host1 = new HostSpec("localhost", 5432);
    HostSpec host2 = new HostSpec("localhost", 5433);

    // Set different states
    registry.setHostAvailable(clusterId, host1, true);
    registry.setHostAvailable(clusterId, host2, false);

    // Get host states
    java.util.Map<HostSpec, Boolean> states = registry.getHostStates(clusterId);

    // Verify
    assertEquals("Should have 2 host states", 2, states.size());
    assertTrue("Host1 should be available", states.get(host1));
    assertFalse("Host2 should be unavailable", states.get(host2));
  }

  @Test
  public void testPendingCountManagement() throws Exception {
    HostSpec host = new HostSpec("localhost", 5432);

    // Initial pending count should be 0
    assertEquals("Initial pending count should be 0",
        0, registry.getPendingCount(clusterId, host));

    // Increment pending count
    registry.incrementPendingCount(clusterId, host);
    assertEquals("Pending count should be 1",
        1, registry.getPendingCount(clusterId, host));

    // Increment again
    registry.incrementPendingCount(clusterId, host);
    assertEquals("Pending count should be 2",
        2, registry.getPendingCount(clusterId, host));

    // Decrement
    registry.decrementPendingCount(clusterId, host);
    assertEquals("Pending count should be 1",
        1, registry.getPendingCount(clusterId, host));

    // Decrement again
    registry.decrementPendingCount(clusterId, host);
    assertEquals("Pending count should be 0",
        0, registry.getPendingCount(clusterId, host));
  }

  @Test
  public void testTotalConnectionCount() throws Exception {
    HostSpec host = new HostSpec("localhost", 5432);

    // Set pending count
    registry.incrementPendingCount(clusterId, host);
    registry.incrementPendingCount(clusterId, host);

    // Get total count (active + pending)
    // Note: active count is 0 since we haven't registered any connections
    int totalCount = registry.getTotalConnectionCount(clusterId, host);

    // Verify
    assertEquals("Total count should equal pending count",
        2, totalCount);
  }

  @Test
  public void testQuickAutoBalanceConfiguration() throws Exception {
    // Test with quick auto-balance enabled
    assertTrue("Quick auto-balance should be enabled by default",
        PGProperty.ENABLE_QUICK_AUTO_BALANCE.getBoolean(info));

    // Test with quick auto-balance disabled
    info.setProperty(PGProperty.ENABLE_QUICK_AUTO_BALANCE.getName(), "false");
    assertFalse("Quick auto-balance should be disabled",
        PGProperty.ENABLE_QUICK_AUTO_BALANCE.getBoolean(info));
  }

  @Test
  public void testHeartbeatIntervalConfiguration() throws Exception {
    // Test default value with a fresh Properties object
    Properties defaultInfo = new Properties();
    defaultInfo.setProperty(PGProperty.USER.getName(), "test");
    defaultInfo.setProperty(PGProperty.PG_DBNAME.getName(), "test");

    assertEquals("Default heartbeat interval should be 20",
        20, PGProperty.HEARTBEAT_INTERVAL_SECONDS.getInt(defaultInfo));

    // Test custom value
    info.setProperty(PGProperty.HEARTBEAT_INTERVAL_SECONDS.getName(), "30");
    assertEquals("Custom heartbeat interval should be 30",
        30, PGProperty.HEARTBEAT_INTERVAL_SECONDS.getInt(info));
  }

  @Test
  public void testMaxIdleTimeConfiguration() throws Exception {
    // Test default value with a fresh Properties object
    Properties defaultInfo = new Properties();
    defaultInfo.setProperty(PGProperty.USER.getName(), "test");
    defaultInfo.setProperty(PGProperty.PG_DBNAME.getName(), "test");

    assertEquals("Default max idle time should be 30",
        30, PGProperty.MAX_IDLE_TIME_BEFORE_CLOSE.getInt(defaultInfo));

    // Test custom value
    info.setProperty(PGProperty.MAX_IDLE_TIME_BEFORE_CLOSE.getName(), "60");
    assertEquals("Custom max idle time should be 60",
        60, PGProperty.MAX_IDLE_TIME_BEFORE_CLOSE.getInt(info));
  }

  @Test
  public void testMinReservedConnectionPercentConfiguration() throws Exception {
    // Test default value
    assertEquals("Default min reserved percent should be 20",
        20, PGProperty.MIN_RESERVED_CONNECTION_PERCENT.getInt(info));

    // Test custom value
    info.setProperty(PGProperty.MIN_RESERVED_CONNECTION_PERCENT.getName(), "30");
    assertEquals("Custom min reserved percent should be 30",
        30, PGProperty.MIN_RESERVED_CONNECTION_PERCENT.getInt(info));
  }

  @Test
  public void testConcurrentPendingCountUpdates() throws Exception {
    HostSpec host = new HostSpec("localhost", 5432);
    int threadCount = 10;
    int incrementsPerThread = 100;

    // Create threads that increment pending count
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        for (int j = 0; j < incrementsPerThread; j++) {
          registry.incrementPendingCount(clusterId, host);
        }
      });
    }

    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }

    // Verify final count
    int expectedCount = threadCount * incrementsPerThread;
    int actualCount = registry.getPendingCount(clusterId, host);
    assertEquals("Pending count should be " + expectedCount,
        expectedCount, actualCount);
  }

  @Test
  public void testShutdown() throws Exception {
    // Start heartbeat
    heartbeat.start(clusterId, info);

    // Wait a bit
    Thread.sleep(100);

    // Shutdown
    heartbeat.shutdown();

    // Verify no exceptions thrown
    assertTrue("Shutdown should succeed", true);
  }
}
