/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.loadbalance.ClusterManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 真实数据库连接的 leastConn 负载均衡测试.
 * <p>
 * 测试环境要求：
 * <ul>
 *   <li>主库: 43.136.135.5:5432</li>
 *   <li>从库1: 43.136.135.5:5433</li>
 *   <li>从库2: 43.136.135.5:5434</li>
 * </ul>
 * </p>
 * <p>
 * 运行前请确保：
 * <ul>
 *   <li>三个PostgreSQL实例都在运行</li>
 *   <li>已配置主从复制</li>
 *   <li>设置环境变量 PG_TEST_USER 和 PG_TEST_PASSWORD</li>
 * </ul>
 * </p>
 */
public class LeastConnRealConnectionTest {

  private static final String PRIMARY_HOST = "43.136.135.5";
  private static final int PRIMARY_PORT = 5432;
  private static final String REPLICA1_HOST = "43.136.135.5";
  private static final int REPLICA1_PORT = 5433;
  private static final String REPLICA2_HOST = "43.136.135.5";
  private static final int REPLICA2_PORT = 5434;
  private static final String DATABASE = "test";

  private String testUser;
  private String testPassword;
  private List<Connection> connections;
  private Map<String, String> internalToExternalHostMap;
  private ClusterManager clusterManager;

  @Before
  public void setUp() throws SQLException {
    testUser = "test";
    testPassword = "test";

    if (testUser == null || testPassword == null) {
      testUser = "postgres";
      testPassword = "";
    }

    connections = new ArrayList<>();
    clusterManager = ClusterManager.getInstance();
    internalToExternalHostMap = buildHostMapping();
  }

  @After
  public void tearDown() {
    for (Connection conn : connections) {
      try {
        if (conn != null && !conn.isClosed()) {
          conn.close();
        }
      } catch (SQLException e) {
        // 忽略关闭异常
      }
    }
    connections.clear();
  }

  // ============================================
  // Basic LeastConn Strategy Tests
  // ============================================

  /**
   * 测试 leastConn 策略 - 连接应该均匀分布到所有主机
   */
  @Test
  public void testLeastConnBasicDistribution() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    String url = buildConnectionUrl();
    Map<String, Integer> distribution = new HashMap<>();

    // 创建30个连接，统计分布
    for (int i = 0; i < 30; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
    }

    // 验证：应该连接到所有3个主机
    assertEquals("LeastConn策略应该连接到3个主机", 3, distribution.size());

    // 打印分布情况
    System.out.println("LeastConn策略分布:");
    for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
      System.out.println("  " + entry.getKey() + ": " + entry.getValue() + " 连接");
    }

    // 验证负载均衡（每个主机应该约10个连接，允许±3的误差）
    for (int count : distribution.values()) {
      assertTrue("每个主机应该约10个连接，实际: " + count,
          count >= 7 && count <= 13);
    }
  }

  /**
   * 测试动态负载均衡 - 关闭部分连接后，新连接应该优先选择连接数少的主机.
   */
  @Test
  public void testDynamicLoadRebalancing() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    String url = buildConnectionUrl();

    // 第一阶段：创建15个连接
    System.out.println("\n第一阶段：创建15个连接");
    Map<String, Integer> phase1Distribution = new HashMap<>();
    for (int i = 0; i < 15; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      phase1Distribution.put(externalHost, phase1Distribution.getOrDefault(externalHost, 0) + 1);
    }

    System.out.println("第一阶段分布: " + phase1Distribution);

    // 第二阶段：关闭前10个连接
    System.out.println("\n第二阶段：关闭前10个连接");
    for (int i = 0; i < 10; i++) {
      connections.get(i).close();
    }

    // 第三阶段：创建10个新连接
    System.out.println("\n第三阶段：创建10个新连接");
    Map<String, Integer> phase3Distribution = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      phase3Distribution.put(externalHost, phase3Distribution.getOrDefault(externalHost, 0) + 1);
    }

    System.out.println("第三阶段新连接分布: " + phase3Distribution);

    // 验证：新连接应该相对均匀分布
    assertTrue("应该连接到至少2个主机", phase3Distribution.size() >= 2);
  }

  /**
   * 测试并发连接场景 - 多线程同时创建连接时，负载应该均衡.
   */
  @Test
  public void testConcurrentConnections() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");

    String url = buildConnectionUrl();
    final Map<String, Integer> distribution = new HashMap<>();
    final List<Connection> concurrentConnections = new ArrayList<>();

    // 创建10个线程，每个线程创建10个连接
    Thread[] threads = new Thread[10];
    for (int i = 0; i < 10; i++) {
      threads[i] = new Thread(() -> {
        try {
          for (int j = 0; j < 10; j++) {
            Connection conn = DriverManager.getConnection(url, props);
            synchronized (concurrentConnections) {
              concurrentConnections.add(conn);
            }

            String internalHost = getConnectedHost(conn);
            String externalHost = normalizeHost(internalHost);
            synchronized (distribution) {
              distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
            }
          }
        } catch (SQLException e) {
          e.printStackTrace();
        }
      });
      threads[i].start();
    }

    // 等待所有线程完成
    for (Thread thread : threads) {
      thread.join();
    }

    connections.addAll(concurrentConnections);

    System.out.println("\n并发连接分布: " + distribution);

    // 验证：应该连接到所有3个主机
    assertEquals("应该连接到3个主机", 3, distribution.size());

    // 验证负载均衡（每个主机应该约33个连接，允许±4的误差）
    for (int count : distribution.values()) {
      assertTrue("每个主机应该约10个连接，实际: " + count,
          count >= 29 && count <= 37);
    }
  }

  /**
   * 长运行测试 - 模拟真实生产环境的连接模式.
   * <p>
   * 测试场景：
   * <ul>
   *   <li>持续运行5分钟</li>
   *   <li>模拟多个客户端不断创建和关闭连接</li>
   *   <li>连接持有时间随机（1-10秒）</li>
   *   <li>每秒创建2-5个新连接</li>
   *   <li>实时监控负载分布</li>
   * </ul>
   * </p>
   */
  @Test
  @org.junit.Ignore("Long running test - run manually to observe leastConn behavior")
  public void testLongRunningProductionSimulation() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "leastConn");
    PGProperty.ENABLE_QUICK_AUTO_BALANCE.set(props, "true");

    String url = buildConnectionUrl();

    // 测试运行时长（毫秒）
    final long testDurationMs = 5 * 60 * 1000; // 5分钟
    final long startTime = System.currentTimeMillis();

    // 用于跟踪活跃连接
    final List<ConnectionHolder> activeConnections = new ArrayList<>();
    final Object lock = new Object();

    // 统计信息
    final Map<String, Integer> totalConnectionsCreated = new HashMap<>();
    final java.util.Random random = new java.util.Random();

    System.out.println("\n========================================");
    System.out.println("开始长运行测试 - 模拟生产环境");
    System.out.println("测试时长: 5分钟");
    System.out.println("========================================\n");

    // 创建连接管理线程
    Thread connectionCreator = new Thread(() -> {
      try {
        while (System.currentTimeMillis() - startTime < testDurationMs) {
          // 每5秒创建17-20个连接
          int connectionsToCreate = 17 + random.nextInt(4);

          for (int i = 0; i < connectionsToCreate; i++) {
            Connection conn = DriverManager.getConnection(url, props);
            String internalHost = getConnectedHost(conn);
            String externalHost = normalizeHost(internalHost);

            // 随机持有时间：30-60秒
            long holdTime = (30 + random.nextInt(30)) * 1000;

            synchronized (lock) {
              activeConnections.add(new ConnectionHolder(conn, externalHost, holdTime));
              totalConnectionsCreated.put(externalHost,
                  totalConnectionsCreated.getOrDefault(externalHost, 0) + 1);
            }
          }

          Thread.sleep(5000); // 每5秒创建一批
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    // 连接清理线程 - 关闭超时的连接
    Thread connectionCleaner = new Thread(() -> {
      try {
        while (System.currentTimeMillis() - startTime < testDurationMs) {
          Thread.sleep(500); // 每0.5秒检查一次

          synchronized (lock) {
            long currentTime = System.currentTimeMillis();
            List<ConnectionHolder> toRemove = new ArrayList<>();

            for (ConnectionHolder holder : activeConnections) {
              if (currentTime - holder.createTime >= holder.holdTime) {
                try {
                  holder.connection.close();
                  toRemove.add(holder);
                } catch (SQLException e) {
                  // 忽略关闭异常
                }
              }
            }

            activeConnections.removeAll(toRemove);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    // 监控线程 - 每5秒打印一次状态
    Thread monitor = new Thread(() -> {
      try {
        int reportCount = 0;
        while (System.currentTimeMillis() - startTime < testDurationMs) {
          Thread.sleep(5000); // 每5秒报告一次
          reportCount++;

          synchronized (lock) {
            Map<String, Integer> currentDistribution = new HashMap<>();
            for (ConnectionHolder holder : activeConnections) {
              currentDistribution.put(holder.host,
                  currentDistribution.getOrDefault(holder.host, 0) + 1);
            }

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(String.format("\n[%d秒] 报告 #%d:", elapsed, reportCount));
            System.out.println("  活跃连接数: " + activeConnections.size());
            System.out.println("  当前分布:");
            for (Map.Entry<String, Integer> entry : currentDistribution.entrySet()) {
              System.out.println("    " + entry.getKey() + ": " + entry.getValue() + " 连接");
            }
            System.out.println("  累计创建:");
            for (Map.Entry<String, Integer> entry : totalConnectionsCreated.entrySet()) {
              System.out.println("    " + entry.getKey() + ": " + entry.getValue() + " 连接");
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    // 启动所有线程
    connectionCreator.start();
    connectionCleaner.start();
    monitor.start();

    // 等待测试完成
    connectionCreator.join();
    connectionCleaner.join();
    monitor.join();

    // 清理剩余连接
    synchronized (lock) {
      for (ConnectionHolder holder : activeConnections) {
        try {
          holder.connection.close();
        } catch (SQLException e) {
          // 忽略
        }
      }
      activeConnections.clear();
    }

    // 最终报告
    System.out.println("\n========================================");
    System.out.println("测试完成 - 最终统计");
    System.out.println("========================================");
    System.out.println("总计创建连接:");
    int totalCreated = 0;
    for (Map.Entry<String, Integer> entry : totalConnectionsCreated.entrySet()) {
      System.out.println("  " + entry.getKey() + ": " + entry.getValue() + " 连接");
      totalCreated += entry.getValue();
    }
    System.out.println("  总计: " + totalCreated + " 连接");

    // 验证负载均衡
    assertTrue("应该连接到所有3个主机", totalConnectionsCreated.size() == 3);

    // 计算分布的标准差，验证负载相对均衡
    double avg = totalCreated / 3.0;
    double variance = 0;
    for (int count : totalConnectionsCreated.values()) {
      variance += Math.pow(count - avg, 2);
    }
    double stdDev = Math.sqrt(variance / 3);
    double cvPercent = (stdDev / avg) * 100;

    System.out.println(String.format("\n负载均衡指标:"));
    System.out.println(String.format("  平均值: %.2f", avg));
    System.out.println(String.format("  标准差: %.2f", stdDev));
    System.out.println(String.format("  变异系数: %.2f%%", cvPercent));

    // 变异系数应该小于30%（表示负载相对均衡）
    assertTrue("负载应该相对均衡，变异系数: " + cvPercent + "%", cvPercent < 30);
  }

  /**
   * 连接持有者 - 用于跟踪连接的创建时间和持有时长.
   */
  private static class ConnectionHolder {
    final Connection connection;
    final String host;
    final long createTime;
    final long holdTime;

    ConnectionHolder(Connection connection, String host, long holdTime) {
      this.connection = connection;
      this.host = host;
      this.createTime = System.currentTimeMillis();
      this.holdTime = holdTime;
    }
  }

  // ============================================
  // Helper Methods
  // ============================================

  /**
   * 构建连接URL.
   */
  private String buildConnectionUrl() {
    return String.format("jdbc:postgresql://%s:%d,%s:%d,%s:%d/%s",
        PRIMARY_HOST, PRIMARY_PORT,
        REPLICA1_HOST, REPLICA1_PORT,
        REPLICA2_HOST, REPLICA2_PORT,
        DATABASE);
  }

  /**
   * 获取实际连接的主机和端口.
   */
  private String getConnectedHost(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      ResultSet rs = stmt.executeQuery(
          "SELECT inet_server_addr() || ':' || inet_server_port() as host_port");
      if (rs.next()) {
        return rs.getString("host_port");
      }
    }
    return "unknown";
  }

  /**
   * 检查连接的服务器是否为主库.
   */
  private boolean isPrimaryServer(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      ResultSet rs = stmt.executeQuery("SELECT pg_is_in_recovery()");
      if (rs.next()) {
        // pg_is_in_recovery() 返回 true 表示是从库，false 表示是主库
        return !rs.getBoolean(1);
      }
    }
    return false;
  }

  /**
   * 建立内部IP到外部端口的映射.
   * 由于Docker容器的内部IP与外部映射端口不同，需要建立映射关系.
   */
  private Map<String, String> buildHostMapping() throws SQLException {
    Map<String, String> mapping = new HashMap<>();
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);

    // 分别连接到每个外部端口，获取其内部IP
    String[][] hostPorts = {
        {PRIMARY_HOST, String.valueOf(PRIMARY_PORT)},
        {REPLICA1_HOST, String.valueOf(REPLICA1_PORT)},
        {REPLICA2_HOST, String.valueOf(REPLICA2_PORT)}
    };

    for (String[] hostPort : hostPorts) {
      String host = hostPort[0];
      String port = hostPort[1];
      String url = String.format("jdbc:postgresql://%s:%s/%s", host, port, DATABASE);

      try (Connection conn = DriverManager.getConnection(url, props)) {
        String internalHost = getConnectedHost(conn);
        String externalHost = host + ":" + port;
        mapping.put(internalHost, externalHost);
      }
    }

    return mapping;
  }

  /**
   * 将内部主机标识转换为外部主机标识.
   * 如果找不到映射，返回原始值.
   */
  private String normalizeHost(String internalHost) {
    return internalToExternalHostMap.getOrDefault(internalHost, internalHost);
  }
}
