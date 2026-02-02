package org.postgresql.mytest;

import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;

import java.sql.*;
import java.util.*;

public class Test {

  private static final String PRIMARY_HOST = "43.136.135.5";
  private static final int PRIMARY_PORT = 5432;
  private static final String REPLICA1_HOST = "43.136.135.5";
  private static final int REPLICA1_PORT = 5433;
  private static final String REPLICA2_HOST = "43.136.135.5";
  private static final int REPLICA2_PORT = 5434;
  private static final String DATABASE = "test";

  private static final String testUser =  "test";
  private static final String testPassword = "test";
  private static List<Connection> connections = new ArrayList<>();
  private static Map<String, String> internalToExternalHostMap;

  static {
    try {
      internalToExternalHostMap = buildHostMapping();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }


  public static void testLongRunningProductionSimulation() throws Exception {
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
          // 每10秒创建15-20个连接
          int connectionsToCreate = 15 + random.nextInt(6);

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

          Thread.sleep(10000); // 每10秒创建一批
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

  private static String buildConnectionUrl() {
    return String.format("jdbc:postgresql://%s:%d,%s:%d,%s:%d/%s",
        PRIMARY_HOST, PRIMARY_PORT,
        REPLICA1_HOST, REPLICA1_PORT,
        REPLICA2_HOST, REPLICA2_PORT,
        DATABASE);
  }

  /**
   * 获取实际连接的主机和端口.
   */
  private static String getConnectedHost(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      ResultSet rs = stmt.executeQuery(
          "SELECT inet_server_addr() || ':' || inet_server_port() as host_port");
      if (rs.next()) {
        return rs.getString("host_port");
      }
    }
    return "unknown";
  }

  private static String normalizeHost(String internalHost) {
    return internalToExternalHostMap.getOrDefault(internalHost, internalHost);
  }

  /**
   * 建立内部IP到外部端口的映射.
   * 由于Docker容器的内部IP与外部映射端口不同，需要建立映射关系.
   */
  private static Map<String, String> buildHostMapping() throws SQLException {
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

  public static void tearDown() throws Exception {
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

  public static void main(String[] args) throws Exception {

    testLongRunningProductionSimulation();
  }
}
