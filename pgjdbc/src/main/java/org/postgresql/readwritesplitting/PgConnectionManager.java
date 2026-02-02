/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages physical connections to master and replica servers in read-write splitting mode.
 * <p>
 * This class is responsible for:
 * <ul>
 *   <li>Identifying master servers (either explicitly configured or auto-detected, supports multiple masters in distributed databases)</li>
 *   <li>Caching physical connections for master and replica clusters</li>
 *   <li>Passing multiple hosts to PgConnection to enable leastConn load balancing</li>
 *   <li>Synchronizing connection state across physical connections</li>
 * </ul>
 * </p>
 */
public class PgConnectionManager {

  private static final Logger LOGGER = Logger.getLogger(PgConnectionManager.class.getName());

  private final HostSpec[] hostSpecs;
  private final Properties info;
  private final String url;
  private final MethodInvocationRecorder stateRecorder;

  // Cached connections by role
  private PgConnection masterConnection;
  private PgConnection replicaConnection;

  // Write hosts (supports multiple)
  private List<HostSpec> writeHosts;

  // Read hosts
  private List<HostSpec> readHosts;

  // Master hosts (identified via auto-detection when writeHosts is not configured)
  private List<HostSpec> masterHosts;

  // Current active connection
  private PgConnection currentConnection;

  /**
   * Creates a new connection manager.
   *
   * @param hostSpecs array of host specifications
   * @param info connection properties
   * @param url JDBC URL
   * @throws PSQLException if configuration is invalid
   */
  public PgConnectionManager(HostSpec[] hostSpecs, Properties info, String url) throws PSQLException {
    this.hostSpecs = hostSpecs;
    this.info = info;
    this.url = url;
    this.stateRecorder = new MethodInvocationRecorder();

    // Parse write hosts (supports multiple, comma-separated)
    this.writeHosts = parseWriteHosts(info);

    // Calculate read hosts = all hosts - write hosts
    if (writeHosts != null && !writeHosts.isEmpty()) {
      this.readHosts = new ArrayList<>();
      for (HostSpec host : hostSpecs) {
        if (!containsHostByAddress(writeHosts, host)) {
          readHosts.add(host);
        }
      }
      LOGGER.log(Level.FINE, "Configured writeHosts: {0}, calculated readHosts: {1}",
          new Object[]{writeHosts, readHosts});
    }
  }

  /**
   * Checks if a host list contains a host by comparing only host and port (not localSocketAddress).
   *
   * @param hosts the list of hosts to search
   * @param target the host to find
   * @return true if the list contains a host with the same host and port
   */
  private boolean containsHostByAddress(List<HostSpec> hosts, HostSpec target) {
    for (HostSpec host : hosts) {
      if (host.getHost().equals(target.getHost()) && host.getPort() == target.getPort()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses write hosts from the configuration property.
   *
   * @param info connection properties
   * @return list of write hosts, or null if not configured
   * @throws PSQLException if address format is invalid
   */
  private List<HostSpec> parseWriteHosts(Properties info) throws PSQLException {
    String writeAddresses = PGProperty.WRITE_DATA_SOURCE_ADDRESS.get(info);
    if (writeAddresses == null || writeAddresses.trim().isEmpty()) {
      return null;  // Need auto-detection
    }

    List<HostSpec> hosts = new ArrayList<>();
    for (String addr : writeAddresses.split(",")) {
      hosts.add(parseHostAddress(addr.trim()));
    }
    return hosts;
  }

  /**
   * Parses a host address in format "host:port".
   *
   * @param address the address string
   * @return the parsed host specification
   * @throws PSQLException if the address format is invalid
   */
  private HostSpec parseHostAddress(String address) throws PSQLException {
    String[] parts = address.split(":");
    if (parts.length != 2) {
      throw new PSQLException(
          "Invalid address format. Expected 'host:port', got: " + address,
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    String host = parts[0].trim();
    int port;
    try {
      port = Integer.parseInt(parts[1].trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          "Invalid port number in address: " + parts[1],
          PSQLState.INVALID_PARAMETER_VALUE, e);
    }

    return new HostSpec(host, port);
  }

  /**
   * Gets or creates a connection to the master server.
   * <p>
   * When multiple write hosts are configured, all of them are passed to PgConnection,
   * allowing the underlying ConnectionFactory to apply leastConn load balancing.
   * </p>
   *
   * @return connection to the master server
   * @throws SQLException if master cannot be identified or connection fails
   */
  public synchronized PgConnection getMasterConnection() throws SQLException {
    // Check if cached connection is still valid
    if (masterConnection != null && !masterConnection.isClosed()) {
      currentConnection = masterConnection;
      return masterConnection;
    }

    // Determine write hosts array
    HostSpec[] writeArray;
    if (writeHosts != null && !writeHosts.isEmpty()) {
      // Explicitly configured write hosts
      writeArray = writeHosts.toArray(new HostSpec[0]);
    } else {
      // Auto-detect mode: identify masters (supports multiple in distributed databases)
      if (masterHosts == null) {
        masterHosts = identifyMasters();
      }
      writeArray = masterHosts.toArray(new HostSpec[0]);
    }

    // Pass multiple hosts to let ConnectionFactory handle leastConn
    masterConnection = new PgConnection(writeArray, info, url);
    stateRecorder.replayOn(masterConnection);
    currentConnection = masterConnection;
    return masterConnection;
  }

  /**
   * Gets or creates a connection to a replica server using load balancing.
   * <p>
   * When multiple read hosts are available, all of them are passed to PgConnection,
   * allowing the underlying ConnectionFactory to apply leastConn load balancing.
   * </p>
   *
   * @return connection to a replica server
   * @throws SQLException if no replica is available or connection fails
   */
  public synchronized PgConnection getReplicaConnection() throws SQLException {
    LOGGER.log(Level.FINE, "getReplicaConnection() called, replicaConnection={0}",
        replicaConnection == null ? "null" : "exists, isClosed=" + replicaConnection.isClosed());

    // Check if cached connection is still valid
    if (replicaConnection != null && !replicaConnection.isClosed()) {
      LOGGER.log(Level.FINE, "Returning cached replicaConnection");
      currentConnection = replicaConnection;
      return replicaConnection;
    }

    // Determine read hosts array
    HostSpec[] readArray;
    if (readHosts != null && !readHosts.isEmpty()) {
      // Explicitly configured mode
      readArray = readHosts.toArray(new HostSpec[0]);
      LOGGER.log(Level.FINE, "Using explicitly configured read hosts: {0}", readHosts);
    } else {
      // Auto-detect mode: all hosts except master
      List<HostSpec> replicas = getReplicaHostsAutoDetect();
      if (replicas.isEmpty()) {
        LOGGER.log(Level.WARNING, "No replica hosts available, falling back to master");
        return getMasterConnection();
      }
      readArray = replicas.toArray(new HostSpec[0]);
      LOGGER.log(Level.FINE, "Auto-detected read hosts: {0}", replicas);
    }

    try {
      // Pass multiple hosts to let ConnectionFactory handle leastConn
      replicaConnection = new PgConnection(readArray, info, url);
      stateRecorder.replayOn(replicaConnection);
      currentConnection = replicaConnection;
      return replicaConnection;
    } catch (SQLException e) {
      LOGGER.log(Level.WARNING, "Failed to connect to replicas, falling back to master", e);
      return getMasterConnection();
    }
  }

  /**
   * Gets the list of replica hosts via auto-detection (all hosts except masters).
   *
   * @return list of replica hosts
   * @throws SQLException if masters cannot be identified
   */
  private List<HostSpec> getReplicaHostsAutoDetect() throws SQLException {
    if (masterHosts == null) {
      masterHosts = identifyMasters();
    }
    List<HostSpec> replicas = new ArrayList<>();
    for (HostSpec host : hostSpecs) {
      if (!containsHostByAddress(masterHosts, host)) {
        replicas.add(host);
      }
    }
    return replicas;
  }

  /**
   * Gets the current active connection.
   *
   * @return the current connection, or null if no connection is active
   */
  public synchronized PgConnection getCurrentConnection() {
    return currentConnection;
  }

  /**
   * Records a state change that should be applied to all connections.
   *
   * @param recorder consumer that records the state change
   */
  public synchronized void recordStateChange(java.util.function.Consumer<MethodInvocationRecorder> recorder) {
    recorder.accept(stateRecorder);
  }

  /**
   * Applies recorded state to all cached connections.
   *
   * @throws SQLException if state replay fails
   */
  public synchronized void syncState() throws SQLException {
    if (masterConnection != null && !masterConnection.isClosed()) {
      stateRecorder.replayOn(masterConnection);
    }
    if (replicaConnection != null && !replicaConnection.isClosed()) {
      stateRecorder.replayOn(replicaConnection);
    }
  }

  /**
   * Checks if the current connection is valid.
   *
   * @param timeout timeout in seconds
   * @return true if the current connection is valid
   */
  public synchronized boolean isValid(int timeout) {
    if (currentConnection != null) {
      try {
        return currentConnection.isValid(timeout);
      } catch (SQLException e) {
        LOGGER.log(Level.WARNING, "Failed to check connection validity", e);
      }
    }
    return false;
  }

  /**
   * Closes all cached connections.
   *
   * @throws SQLException if any connection fails to close
   */
  public synchronized void close() throws SQLException {
    SQLException firstException = null;

    if (masterConnection != null) {
      try {
        masterConnection.close();
      } catch (SQLException e) {
        firstException = e;
      }
      masterConnection = null;
    }

    if (replicaConnection != null) {
      try {
        replicaConnection.close();
      } catch (SQLException e) {
        if (firstException == null) {
          firstException = e;
        }
      }
      replicaConnection = null;
    }

    currentConnection = null;

    if (firstException != null) {
      throw firstException;
    }
  }

  /**
   * Identifies all master servers from the host list via auto-detection.
   * In distributed databases, there may be multiple primary nodes.
   *
   * @return list of master host specifications
   * @throws SQLException if no masters can be identified
   */
  private List<HostSpec> identifyMasters() throws SQLException {
    List<HostSpec> masters = new ArrayList<>();

    // Auto-detect masters by querying each host
    for (HostSpec host : hostSpecs) {
      try {
        if (isPrimary(host)) {
          LOGGER.log(Level.FINE, "Identified master server: {0}", host);
          masters.add(host);
        }
      } catch (SQLException e) {
        LOGGER.log(Level.WARNING, "Failed to check if host is primary: " + host, e);
      }
    }

    if (masters.isEmpty()) {
      throw new PSQLException(
          "Unable to identify any master servers. Please configure writeDataSourceAddress explicitly.",
          PSQLState.CONNECTION_FAILURE);
    }

    LOGGER.log(Level.FINE, "Identified {0} master server(s): {1}",
        new Object[]{masters.size(), masters});
    return masters;
  }

  /**
   * Checks if a host is the primary (master) server.
   *
   * @param host the host to check
   * @return true if the host is primary
   * @throws SQLException if the check fails
   */
  private boolean isPrimary(HostSpec host) throws SQLException {
    PgConnection conn = null;
    try {
      conn = new PgConnection(new HostSpec[] { host }, info, url);
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SHOW transaction_read_only");

      if (rs.next()) {
        String value = rs.getString(1);
        return "off".equalsIgnoreCase(value);
      }

      return false;
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException ignored) {
        }
      }
    }
  }
}
