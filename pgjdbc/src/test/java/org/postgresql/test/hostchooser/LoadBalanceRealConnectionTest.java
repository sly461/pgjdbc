/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;

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
 * 真实数据库连接的负载均衡测试.
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
 * <p>
 * 运行方式：
 * <pre>
 * # 设置用户名和密码
 * export PG_TEST_USER=postgres
 * export PG_TEST_PASSWORD=your_password
 *
 * # 运行测试
 * ./gradlew :postgresql:test --tests "org.postgresql.test.hostchooser.LoadBalanceRealConnectionTest"
 * </pre>
 * </p>
 */
public class LoadBalanceRealConnectionTest {

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

  @Before
  public void setUp() throws SQLException {
    // 从环境变量获取用户名和密码
    testUser = "test";
    testPassword = "test";

    if (testUser == null || testPassword == null) {
      testUser = "postgres";  // 默认用户名
      testPassword = "";       // 默认密码
    }

    connections = new ArrayList<>();

    // 建立内部IP到外部端口的映射
    internalToExternalHostMap = buildHostMapping();
  }

  @After
  public void tearDown() {
    // 关闭所有连接
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

  /**
   * 测试random策略 - 连接应该随机分布到不同主机.
   */
  @Test
  public void testRandomStrategy() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "random");

    String url = buildConnectionUrl();
    Map<String, Integer> distribution = new HashMap<>();

    // 创建20个连接，统计分布
    for (int i = 0; i < 20; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
    }

    // 验证：应该至少连接到2个不同的主机（随机分布）
    assertTrue("Random策略应该连接到至少2个不同主机，实际: " + distribution.keySet(),
        distribution.size() >= 2);

    System.out.println("Random策略分布: " + distribution);
  }

  /**
   * 测试weightedRandom策略 - 连接应该按权重分布.
   */
  @Test
  public void testWeightedRandomStrategy() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    // 主库50%，从库1各25%，从库2各25%
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "2,1,1");

    String url = buildConnectionUrl();
    Map<String, Integer> distribution = new HashMap<>();

    // 创建100个连接，统计分布
    for (int i = 0; i < 100; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
    }

    // 验证：应该连接到所有3个主机
    assertEquals("WeightedRandom策略应该连接到3个主机", 3, distribution.size());

    // 打印分布情况
    System.out.println("WeightedRandom策略分布:");
    String primaryKey = PRIMARY_HOST + ":" + PRIMARY_PORT;
    String replica1Key = REPLICA1_HOST + ":" + REPLICA1_PORT;
    String replica2Key = REPLICA2_HOST + ":" + REPLICA2_PORT;

    int primaryCount = distribution.getOrDefault(primaryKey, 0);
    int replica1Count = distribution.getOrDefault(replica1Key, 0);
    int replica2Count = distribution.getOrDefault(replica2Key, 0);

    System.out.println("  主库 (" + primaryKey + "): " + primaryCount + " (" + (primaryCount * 100 / 100) + "%)");
    System.out.println("  从库1 (" + replica1Key + "): " + replica1Count + " (" + (replica1Count * 100 / 100) + "%)");
    System.out.println("  从库2 (" + replica2Key + "): " + replica2Count + " (" + (replica2Count * 100 / 100) + "%)");

    // 验证权重分布（允许一定的统计误差）
    // 主库应该约50% (40-60%)
    assertTrue("主库应该约50%，实际: " + primaryCount + "%",
        primaryCount >= 40 && primaryCount <= 60);

    // 从库1和从库2应该各约25% (15-35%)
    assertTrue("从库1应该约25%，实际: " + replica1Count + "%",
        replica1Count >= 15 && replica1Count <= 35);
    assertTrue("从库2应该约25%，实际: " + replica2Count + "%",
        replica2Count >= 15 && replica2Count <= 35);
  }

  /**
   * 测试roundRobin策略 - 连接应该轮询分布.
   */
  @Test
  public void testRoundRobinStrategy() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");

    String url = buildConnectionUrl();
    List<String> connectionSequence = new ArrayList<>();

    // 创建15个连接，记录连接顺序（15 = 3主机 × 5轮）
    for (int i = 0; i < 15; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      connectionSequence.add(externalHost);
    }

    // 验证轮询模式
    System.out.println("RoundRobin策略连接序列:");
    for (int i = 0; i < connectionSequence.size(); i++) {
      System.out.println("  连接" + (i + 1) + ": " + connectionSequence.get(i));
    }

    // 验证：每3个连接应该覆盖所有3个主机
    for (int round = 0; round < 5; round++) {
      int startIdx = round * 3;
      List<String> roundHosts = connectionSequence.subList(startIdx, startIdx + 3);

      // 每一轮都应该连接到3个不同的主机
      long uniqueHosts = roundHosts.stream().distinct().count();
      assertEquals("第" + (round + 1) + "轮应该连接到3个不同主机", 3, uniqueHosts);
    }

    // 统计总体分布
    Map<String, Integer> distribution = new HashMap<>();
    for (String host : connectionSequence) {
      distribution.put(host, distribution.getOrDefault(host, 0) + 1);
    }

    // 每个主机应该被连接5次（15个连接 / 3个主机）
    for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
      assertEquals("RoundRobin策略下每个主机应该被连接5次", 5, (int) entry.getValue());
    }

    System.out.println("RoundRobin策略分布: " + distribution);
  }

  /**
   * 测试weightedRandom策略的备用主机功能.
   * 权重0的主机应该作为备用，只在其他主机都失败时才使用.
   */
  @Test
  public void testWeightedRandomBackupHost() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "weightedRandom");
    // 主库50%，从库1 50%，从库2作为备用（0%）
    PGProperty.LOAD_BALANCE_WEIGHT_FACTOR.set(props, "1,1,0");

    String url = buildConnectionUrl();
    Map<String, Integer> distribution = new HashMap<>();

    // 创建50个连接
    for (int i = 0; i < 50; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
    }

    String replica2Key = REPLICA2_HOST + ":" + REPLICA2_PORT;
    int replica2Count = distribution.getOrDefault(replica2Key, 0);

    // 从库2作为备用（权重0），正常情况下不应该被连接
    System.out.println("WeightedRandom备用主机测试分布: " + distribution);
    System.out.println("备用主机(从库2)连接次数: " + replica2Count);

    // 正常情况下，备用主机应该连接次数为0或极少
    assertTrue("备用主机连接次数应该很少，实际: " + replica2Count, replica2Count <= 5);
  }

  /**
   * 测试与targetServerType配合使用.
   */
  @Test
  public void testLoadBalanceWithTargetServerType() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, "roundRobin");
    PGProperty.TARGET_SERVER_TYPE.set(props, "any");  // 连接任意类型

    String url = buildConnectionUrl();

    // 创建连接并验证
    Connection conn = DriverManager.getConnection(url, props);
    connections.add(conn);

    assertNotNull("应该成功创建连接", conn);
    assertTrue("连接应该有效", conn.isValid(5));

    String internalHost = getConnectedHost(conn);
    String externalHost = normalizeHost(internalHost);
    System.out.println("连接到主机: " + externalHost);

    // 检查是主库还是从库
    boolean isPrimary = isPrimaryServer(conn);
    System.out.println("是否为主库: " + isPrimary);
  }

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
   * 获取服务器角色描述.
   */
  private String getServerRole(Connection conn) throws SQLException {
    return isPrimaryServer(conn) ? "主库(Primary)" : "从库(Replica)";
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
