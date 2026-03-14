/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser.loadbalance;

import org.postgresql.PGProperty;
import org.postgresql.util.HostSpec;
import org.postgresql.util.SharedTimer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Heartbeat mechanism for leastConn load balancing strategy.
 * <p>
 * This class manages periodic health checks of database hosts and triggers
 * quick auto-balance when nodes recover from failure. It uses {@link SharedTimer}
 * for scheduling heartbeat tasks with reference counting.
 * </p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Periodic health checks using complete connection detection</li>
 *   <li>Node state tracking (available/unavailable)</li>
 *   <li>Automatic detection of node recovery</li>
 *   <li>Quick auto-balance with gradual connection closing</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe using synchronized methods and concurrent data structures.
 *
 * @see ClusterManager
 * @see LeastConnLoadBalanceStrategy
 */
public class LeastConnHeartbeat {

  private static final Logger LOGGER = Logger.getLogger(LeastConnHeartbeat.class.getName());
  private static final LeastConnHeartbeat INSTANCE = new LeastConnHeartbeat();

  private final SharedTimer sharedTimer = new SharedTimer();
  private volatile TimerTask heartbeatTask;
  private final AtomicInteger refCount = new AtomicInteger(0);
  private final ClusterManager clusterManager = ClusterManager.getInstance();

  // Map from cluster ID to connection properties (for health checks)
  private final ConcurrentHashMap<String, Properties> clusterProperties = new ConcurrentHashMap<>();

  /**
   * Private constructor for singleton pattern.
   */
  private LeastConnHeartbeat() {
  }

  /**
   * Returns the singleton instance of the heartbeat manager.
   *
   * @return The global LeastConnHeartbeat instance
   */
  public static LeastConnHeartbeat getInstance() {
    return INSTANCE;
  }

  /**
   * Starts the heartbeat mechanism for a cluster.
   * <p>
   * This method uses reference counting to manage the heartbeat lifecycle.
   * The heartbeat task is started only when the first cluster registers,
   * and stopped when the last cluster unregisters.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @param info Connection properties (used for health checks)
   * @throws org.postgresql.util.PSQLException if configuration parameters are invalid
   */
  public synchronized void start(String clusterId, Properties info) throws org.postgresql.util.PSQLException {
    // Store cluster properties for health checks
    clusterProperties.put(clusterId, info);

    // Increment reference count
    int count = refCount.incrementAndGet();

    // Start heartbeat task if this is the first cluster
    if (count == 1) {
      int intervalSeconds = PGProperty.HEARTBEAT_INTERVAL_SECONDS.getInt(info);
      if (intervalSeconds <= 0) {
        LOGGER.log(Level.INFO, "Heartbeat is disabled (heartbeatIntervalSeconds={0})", intervalSeconds);
        refCount.decrementAndGet();
        return;
      }

      Timer timer = sharedTimer.getTimer();
      heartbeatTask = new TimerTask() {
        @Override
        public void run() {
          try {
            doHeartbeat();
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during heartbeat execution", e);
          }
        }
      };

      long intervalMs = intervalSeconds * 1000L;
      timer.scheduleAtFixedRate(heartbeatTask, intervalMs, intervalMs);

      LOGGER.log(Level.INFO, "Heartbeat started with interval {0} seconds", intervalSeconds);
    }
  }

  /**
   * Stops the heartbeat mechanism for a cluster.
   * <p>
   * Uses reference counting to determine when to actually stop the heartbeat task.
   * The task is stopped only when the last cluster unregisters.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   */
  public synchronized void stop(String clusterId) {
    // Remove cluster properties
    clusterProperties.remove(clusterId);

    // Decrement reference count
    int count = refCount.decrementAndGet();

    // Stop heartbeat task if this was the last cluster
    if (count == 0) {
      if (heartbeatTask != null) {
        heartbeatTask.cancel();
        heartbeatTask = null;
      }
      sharedTimer.releaseTimer();
      LOGGER.log(Level.INFO, "Heartbeat stopped");
    }
  }

  /**
   * Completely shuts down the heartbeat mechanism.
   * <p>
   * This method is primarily for testing purposes to force cleanup.
   * In production, use {@link #stop(String)} instead.
   * </p>
   */
  public synchronized void shutdown() {
    clusterProperties.clear();
    if (heartbeatTask != null) {
      heartbeatTask.cancel();
      heartbeatTask = null;
    }
    sharedTimer.releaseTimer();
    refCount.set(0);
    LOGGER.log(Level.INFO, "Heartbeat shutdown complete");
  }

  /**
   * Executes heartbeat checks for all registered clusters.
   * <p>
   * This method is called periodically by the heartbeat timer task.
   * It checks the availability of all hosts in each cluster and
   * detects state changes to trigger quick auto-balance.
   * </p>
   * <p>
   * Optimization: Hosts are deduplicated across clusters to avoid redundant checks.
   * Each unique host is checked only once per heartbeat cycle, and the result is
   * shared across all clusters that contain that host.
   * </p>
   */
  private void doHeartbeat() {
    // Step 1: Collect all unique hosts across all clusters and build cluster-host mapping
    Map<HostSpec, Properties> uniqueHostsMap = new HashMap<>();
    Map<String, List<HostSpec>> clusterHostsMap = new HashMap<>();
    Map<String, Map<HostSpec, Boolean>> clusterOldStatesMap = new HashMap<>();

    for (Map.Entry<String, Properties> entry : clusterProperties.entrySet()) {
      String clusterId = entry.getKey();
      Properties info = entry.getValue();

      try {
        // Get all hosts for this cluster
        List<HostSpec> hosts = clusterManager.getAllHosts(clusterId);
        if (hosts.isEmpty()) {
          continue;
        }

        // Store cluster-host mapping
        clusterHostsMap.put(clusterId, hosts);

        // Get current states before checking
        Map<HostSpec, Boolean> oldStates = clusterManager.getHostStates(clusterId);
        clusterOldStatesMap.put(clusterId, oldStates);

        // Collect unique hosts (use first encountered properties for each host)
        for (HostSpec host : hosts) {
          if (!uniqueHostsMap.containsKey(host)) {
            uniqueHostsMap.put(host, info);
          }
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error during heartbeat preparation for cluster " + clusterId, e);
      }
    }

    // Step 2: Check availability of each unique host only once
    Map<HostSpec, Boolean> hostAvailabilityMap = new HashMap<>();
    for (Map.Entry<HostSpec, Properties> entry : uniqueHostsMap.entrySet()) {
      HostSpec host = entry.getKey();
      Properties info = entry.getValue();
      boolean available = checkHostAvailable(host, info);
      hostAvailabilityMap.put(host, available);
    }

    // Step 3: Update availability for all clusters and detect state changes
    for (Map.Entry<String, List<HostSpec>> entry : clusterHostsMap.entrySet()) {
      String clusterId = entry.getKey();
      List<HostSpec> hosts = entry.getValue();

      try {
        // Update availability for each host in this cluster
        for (HostSpec host : hosts) {
          Boolean available = hostAvailabilityMap.get(host);
          if (available != null) {
            clusterManager.setHostAvailable(clusterId, host, available);
          }
        }

        // Get new states after checking
        Map<HostSpec, Boolean> newStates = clusterManager.getHostStates(clusterId);
        Map<HostSpec, Boolean> oldStates = clusterOldStatesMap.get(clusterId);

        // Detect state changes and trigger quick auto-balance if needed
        if (oldStates != null) {
          detectStateChanges(clusterId, oldStates, newStates);
        }

        // Clean up stale connections for this cluster
        int removed = clusterManager.cleanupStaleConnections(clusterId);
        if (removed > 0) {
          LOGGER.log(Level.INFO, "Cleaned up {0} stale connections for cluster {1}",
              new Object[]{removed, clusterId});
        }

      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error during heartbeat check for cluster " + clusterId, e);
      }
    }
  }

  /**
   * Checks if a host is available by attempting a complete connection.
   * <p>
   * This method performs a full connection test including:
   * <ul>
   *   <li>Establishing TCP connection</li>
   *   <li>Completing authentication</li>
   *   <li>Executing SELECT 1 query</li>
   * </ul>
   * The test connection is closed immediately after verification.
   * </p>
   *
   * @param host Host specification to check
   * @param info Connection properties
   * @return true if host is available, false otherwise
   */
  private boolean checkHostAvailable(HostSpec host, Properties info) {
    java.sql.Connection conn = null;
    java.sql.Statement stmt = null;
    java.sql.ResultSet rs = null;

    try {
      // Build JDBC URL
      String url = buildJdbcUrl(host, info);

      // Create a copy of properties for test connection
      Properties testProps = new Properties();
      testProps.putAll(info);

      // Set connection timeout for heartbeat (shorter than normal connections)
      testProps.setProperty("connectTimeout", "5");
      testProps.setProperty("socketTimeout", "5");

      // Remove load balance properties to avoid recursive heartbeat
      testProps.remove("loadBalanceStrategy");
      testProps.remove("loadBalanceHosts");

      // Remove read-write splitting properties to avoid master detection on single host
      testProps.remove("enableReadWriteSplitting");
      testProps.remove("writeDataSourceAddress");
      testProps.remove("readDataSourceAddress");

      // Create test connection
      conn = java.sql.DriverManager.getConnection(url, testProps);

      // Execute SELECT 1 to verify connection
      stmt = conn.createStatement();
      stmt.setQueryTimeout(5);
      rs = stmt.executeQuery("SELECT 1");

      // Check if query returned result
      boolean hasResult = rs.next();

      if (hasResult) {
        LOGGER.log(Level.INFO, "Host {0} is available", host);
        return true;
      } else {
        LOGGER.log(Level.WARNING, "Host {0} query returned no result", host);
        return false;
      }

    } catch (Exception e) {
      LOGGER.log(Level.INFO, "Host {0} is unavailable: {1}",
          new Object[]{host, e.getMessage()});
      return false;
    } finally {
      // Close resources
      if (rs != null) {
        try {
          rs.close();
        } catch (Exception e) {
          // Ignore
        }
      }
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Exception e) {
          // Ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (Exception e) {
          // Ignore
        }
      }
    }
  }

  /**
   * Builds JDBC URL for a specific host.
   *
   * @param host Host specification
   * @param info Connection properties
   * @return JDBC URL string
   */
  private String buildJdbcUrl(HostSpec host, Properties info) {
    StringBuilder url = new StringBuilder("jdbc:postgresql://");
    url.append(host.getHost());
    url.append(":");
    url.append(host.getPort());
    url.append("/");

    // Get database name from properties
    String database = PGProperty.PG_DBNAME.get(info);
    if (database != null && !database.isEmpty()) {
      url.append(database);
    }

    return url.toString();
  }

  /**
   * Detects state changes between old and new host states.
   * <p>
   * When a host transitions from unavailable to available (recovery),
   * this method triggers quick auto-balance to redistribute connections.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @param oldStates Previous host states
   * @param newStates Current host states
   */
  private void detectStateChanges(String clusterId, Map<HostSpec, Boolean> oldStates,
                                   Map<HostSpec, Boolean> newStates) {
    boolean hasRecovery = false;
    List<HostSpec> recoveredHosts = new java.util.ArrayList<>();
    List<HostSpec> failedHosts = new java.util.ArrayList<>();

    for (Map.Entry<HostSpec, Boolean> entry : newStates.entrySet()) {
      HostSpec host = entry.getKey();
      boolean newAvailable = entry.getValue();
      boolean oldAvailable = oldStates.getOrDefault(host, true);

      // Detect recovery: unavailable -> available
      if (!oldAvailable && newAvailable) {
        LOGGER.log(Level.INFO, "Host {0} recovered in cluster {1}",
            new Object[]{host, clusterId});
        hasRecovery = true;
        recoveredHosts.add(host);
      }
      // Detect failure: available -> unavailable
      else if (oldAvailable && !newAvailable) {
        LOGGER.log(Level.WARNING, "Host {0} became unavailable in cluster {1}",
            new Object[]{host, clusterId});
        failedHosts.add(host);
      }
    }

    // Trigger quick auto-balance only once if any host recovered
    if (hasRecovery) {
      LOGGER.log(Level.INFO, "Triggering quick auto-balance for cluster {0} due to {1} host(s) recovery: {2}",
          new Object[]{clusterId, recoveredHosts.size(), recoveredHosts});
      triggerQuickAutoBalance(clusterId);
    }

    // Abort all connections on failed hosts to release half-open sockets
    if (!failedHosts.isEmpty()) {
      int aborted = 0;
      for (HostSpec host : failedHosts) {
        aborted += clusterManager.abortConnectionsOnHost(clusterId, host);
      }
      if (aborted > 0) {
        LOGGER.log(Level.INFO, "Aborted {0} connections in cluster {1} on failed hosts {2}",
            new Object[]{aborted, clusterId, failedHosts});
      }
    }
  }

  /**
   * Triggers quick auto-balance when hosts recover.
   * <p>
   * This method selects idle connections from other hosts and marks them
   * for gradual closing. New connections will automatically flow to the
   * recovered hosts due to their lower connection counts.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   */
  private void triggerQuickAutoBalance(String clusterId) {
    Properties info = clusterProperties.get(clusterId);
    if (info == null) {
      return;
    }

    try {
      // Check if quick auto-balance is enabled
      boolean enabled = PGProperty.ENABLE_QUICK_AUTO_BALANCE.getBoolean(info);
      if (!enabled) {
        LOGGER.log(Level.INFO, "Quick auto-balance is disabled for cluster {0}", clusterId);
        return;
      }

      // Get configuration parameters
      int minReservedPercent = PGProperty.MIN_RESERVED_CONNECTION_PERCENT.getInt(info);
      int maxIdleTimeSeconds = PGProperty.MAX_IDLE_TIME_BEFORE_CLOSE.getInt(info);
      long maxIdleTimeMs = maxIdleTimeSeconds * 1000L;

      // Select connections to close
      List<ConnectionInfo> connectionsToClose = selectConnectionsToClose(
          clusterId, minReservedPercent, maxIdleTimeMs);

      if (connectionsToClose.isEmpty()) {
        LOGGER.log(Level.INFO, "No idle connections found for quick auto-balance in cluster {0}",
            clusterId);
        return;
      }

      // Mark connections for gradual closing
      clusterManager.markConnectionsForClose(clusterId, connectionsToClose);

      LOGGER.log(Level.INFO, "Marked {0} connections for gradual closing in cluster {1}",
          new Object[]{connectionsToClose.size(), clusterId});

      // Start gradual closing process
      closeConnectionsGradually(clusterId);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error during quick auto-balance for cluster " + clusterId, e);
    }
  }

  /**
   * Selects idle connections to close during quick auto-balance.
   * <p>
   * This method identifies idle connections from available hosts (excluding
   * the recovered host) that meet the idle time threshold. It respects the
   * minimum reserved connection percentage to avoid closing too many connections.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   * @param minReservedPercent Minimum percentage of connections to keep (0-100)
   * @param maxIdleTimeMs Maximum idle time threshold in milliseconds
   * @return List of connections selected for closing
   */
  private List<ConnectionInfo> selectConnectionsToClose(String clusterId,
                                                         int minReservedPercent,
                                                         long maxIdleTimeMs) {
    List<ConnectionInfo> result = new java.util.ArrayList<>();

    // Step 1: Collect cluster statistics
    List<HostSpec> hosts = clusterManager.getAllHosts(clusterId);
    if (hosts.isEmpty()) {
      return result;
    }

    // Data structures for statistics
    java.util.Map<HostSpec, Integer> totalConnectionsMap = new java.util.HashMap<>();
    java.util.Map<HostSpec, Integer> idleConnectionsCountMap = new java.util.HashMap<>();
    java.util.Map<HostSpec, List<ConnectionInfo>> idleConnectionsMap = new java.util.HashMap<>();

    int availableNodeCount = 0;
    int totalClusterConnections = 0;

    // Collect data for each node
    for (HostSpec host : hosts) {
      if (!clusterManager.isHostAvailable(clusterId, host)) {
        continue; // Skip unavailable nodes
      }

      int totalConns = clusterManager.getTotalConnectionCount(clusterId, host);
      List<ConnectionInfo> idleConns = clusterManager.getIdleConnections(
          clusterId, host, maxIdleTimeMs);

      totalConnectionsMap.put(host, totalConns);
      idleConnectionsCountMap.put(host, idleConns.size());
      idleConnectionsMap.put(host, idleConns);

      availableNodeCount++;
      totalClusterConnections += totalConns;
    }

    if (availableNodeCount == 0) {
      return result;
    }

    // Step 2: Calculate target connections per node (for balanced distribution)
    int targetConnectionsPerNode = totalClusterConnections / availableNodeCount;

    // Step 3-4: Calculate how many connections should be closed for each node
    java.util.Map<HostSpec, Integer> closeCountMap = new java.util.HashMap<>();

    for (java.util.Map.Entry<HostSpec, Integer> entry : totalConnectionsMap.entrySet()) {
      HostSpec host = entry.getKey();
      int totalConns = entry.getValue();
      int idleConns = idleConnectionsCountMap.get(host);

      // Step 3a: Calculate excess connections beyond target
      int excessConnections = Math.max(0, totalConns - targetConnectionsPerNode);

      // Step 3b: Apply minReservedPercent constraint
      int maxAllowedClose = totalConns * (100 - minReservedPercent) / 100;
      int constrainedClose = Math.min(excessConnections, maxAllowedClose);

      // Step 3c: Apply IDLE connection count constraint
      int actualClose = Math.min(constrainedClose, idleConns);

      closeCountMap.put(host, actualClose);
    }

    // Step 5: Collect connections to close
    for (java.util.Map.Entry<HostSpec, Integer> entry : closeCountMap.entrySet()) {
      HostSpec host = entry.getKey();
      int closeCount = entry.getValue();

      if (closeCount > 0) {
        List<ConnectionInfo> idleConns = idleConnectionsMap.get(host);
        // Select first closeCount IDLE connections
        for (int i = 0; i < closeCount && i < idleConns.size(); i++) {
          result.add(idleConns.get(i));
        }

        LOGGER.log(Level.INFO,
            "Selected {0} connections to close from host {1} (total={2}, idle={3}, target={4})",
            new Object[]{closeCount, host,
                totalConnectionsMap.get(host),
                idleConnectionsCountMap.get(host),
                targetConnectionsPerNode});
      }
    }

    return result;
  }

  /**
   * Closes marked connections gradually to avoid performance impact.
   * <p>
   * This method closes 20% of the marked connections per iteration.
   * It schedules itself to run again if there are more connections to close.
   * </p>
   * <p>
   * The gradual closing process runs asynchronously using a timer task,
   * with a 5-second delay between iterations to minimize performance impact.
   * </p>
   *
   * @param clusterId Unique cluster identifier
   */
  private void closeConnectionsGradually(String clusterId) {
    // Get the total number of connections pending close
    ConcurrentLinkedQueue<ConnectionInfo> queue =
        clusterManager.getPendingCloseQueue(clusterId);

    if (queue == null || queue.isEmpty()) {
      return;
    }

    int totalPending = queue.size();
    // Close 20% of pending connections per iteration (minimum 1)
    int toClose = Math.max(1, totalPending / 5);

    int closedCount = clusterManager.closeMarkedConnections(clusterId, toClose);

    if (closedCount > 0) {
      LOGGER.log(Level.INFO, "Closed {0} connections in cluster {1} during quick auto-balance ({2} remaining)",
          new Object[]{closedCount, clusterId, queue.size()});

      // Schedule next iteration if there are more connections to close
      if (!queue.isEmpty()) {
        Timer timer = sharedTimer.getTimer();
        timer.schedule(new TimerTask() {
          @Override
          public void run() {
            closeConnectionsGradually(clusterId);
          }
        }, 5000); // 5 seconds delay between iterations
      }
    }
  }
}
