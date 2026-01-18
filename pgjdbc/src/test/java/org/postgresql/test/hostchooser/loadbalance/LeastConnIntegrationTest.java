/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostChooserFactory;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.hostchooser.loadbalance.ClusterManager;
import org.postgresql.util.HostSpec;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * Integration tests for leastConn load balancing strategy with MultiHostChooser.
 */
public class LeastConnIntegrationTest {

  private HostSpec host1;
  private HostSpec host2;
  private HostSpec host3;

  @Before
  public void setUp() {
    host1 = new HostSpec("host1", 5432);
    host2 = new HostSpec("host2", 5432);
    host3 = new HostSpec("host3", 5432);
  }

  @Test
  public void testLeastConnStrategyWithHostChooserFactory() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    HostSpec[] hosts = {host1, host2, host3};

    HostChooser hostChooser = HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props);
    String clusterId = hostChooser.getClusterId();
    assertNotNull("Cluster ID should not be null", clusterId);

    Iterator<CandidateHost> iter = hostChooser.iterator();
    assertTrue("Should have hosts", iter.hasNext());
    HostSpec selected = iter.next().hostSpec;
    assertEquals("First host should be host1", host1, selected);
  }

  @Test
  public void testClusterIdGeneration() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    HostSpec[] hosts = {host1, host2, host3};

    HostChooser chooser1 = HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props);
    HostChooser chooser2 = HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props);

    String clusterId1 = chooser1.getClusterId();
    String clusterId2 = chooser2.getClusterId();

    assertNotNull("Cluster ID 1 should not be null", clusterId1);
    assertNotNull("Cluster ID 2 should not be null", clusterId2);
    assertEquals("Same configuration should produce same cluster ID", clusterId1, clusterId2);
  }

  @Test
  public void testLeastConnWithTargetServerType() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    HostSpec[] hosts = {host1, host2, host3};

    HostChooser chooser = HostChooserFactory.createHostChooser(hosts, HostRequirement.primary, props);
    String clusterId = chooser.getClusterId();

    assertNotNull("Cluster ID should not be null", clusterId);

    Iterator<CandidateHost> iter = chooser.iterator();
    assertTrue("Should have hosts", iter.hasNext());
    CandidateHost candidate = iter.next();
    assertEquals("Should require primary", HostRequirement.primary, candidate.targetServerType);
  }

  @Test
  public void testLeastConnDisabledWhenLoadBalanceHostsFalse() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "false");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    HostSpec[] hosts = {host1, host2, host3};

    HostChooser chooser = HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props);
    String clusterId = chooser.getClusterId();

    assertEquals("Cluster ID should be null when load balance is disabled", null, clusterId);
  }

  @Test
  public void testSingleHostWithLeastConn() {
    Properties props = new Properties();
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    HostSpec[] hosts = {host1};

    HostChooser chooser = HostChooserFactory.createHostChooser(hosts, HostRequirement.any, props);

    // Single host may use SingleHostChooser which doesn't have clusterId
    Iterator<CandidateHost> iter = chooser.iterator();
    assertTrue("Should have host", iter.hasNext());
    assertEquals("Should return the single host", host1, iter.next().hostSpec);
  }
}
