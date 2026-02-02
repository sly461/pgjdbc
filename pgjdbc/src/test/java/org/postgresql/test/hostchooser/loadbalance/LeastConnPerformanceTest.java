/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser.loadbalance;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.loadbalance.ClusterManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 性能基准测试 - 评估 leastConn 负载均衡策略的性能影响.
 * <p>
 * 测试维度：
 * <ul>
 *   <li>连接创建延迟 - orderHosts() 方法的性能开销</li>
 *   <li>内存占用 - 连接元数据追踪的内存开销</li>
 *   <li>并发性能 - 高并发场景下的吞吐量</li>
 *   <li>负载均衡效果 - 连接分布的均衡性</li>
 * </ul>
 * </p>
 * <p>
 * 测试环境要求：
 * <ul>
 *   <li>主库: 43.136.135.5:5432</li>
 *   <li>从库1: 43.136.135.5:5433</li>
 *   <li>从库2: 43.136.135.5:5434</li>
 * </ul>
 * </p>
 */
public class LeastConnPerformanceTest {

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
  // Performance Benchmark Tests
  // ============================================

  /**
   * 基准测试 1: 连接创建延迟对比.
   * <p>
   * 对比 leastConn 策略和 random 策略的连接创建延迟。
   * </p>
   */
  @Test
  public void benchmarkConnectionCreationLatency() throws SQLException {
    System.out.println("\n========================================");
    System.out.println("基准测试 1: 连接创建延迟对比");
    System.out.println("========================================\n");

    int warmupCount = 50;
    int testCount = 150;

    // 测试 random 策略
    long randomLatency = measureConnectionCreationLatency("random", warmupCount, testCount);

    // 清理连接
    closeAllConnections();

    // 测试 leastConn 策略
    long leastConnLatency = measureConnectionCreationLatency("leastConn", warmupCount, testCount);

    // 输出结果
    System.out.println("\n结果对比:");
    System.out.println("  Random 策略平均延迟: " + randomLatency + " ns");
    System.out.println("  LeastConn 策略平均延迟: " + leastConnLatency + " ns");
    System.out.println("  性能差异: " + (leastConnLatency - randomLatency) + " ns");
    System.out.println("  性能比率: " + String.format("%.2f", (double) leastConnLatency / randomLatency));

    // 性能差异应该小于 10%
    double performanceRatio = (double) leastConnLatency / randomLatency;
    System.out.println("\n性能评估: " + (performanceRatio < 1.1 ? "✅ 通过" : "⚠️ 需要优化"));
  }

  /**
   * 基准测试 2: 并发连接创建吞吐量.
   * <p>
   * 测试高并发场景下的连接创建吞吐量。
   * </p>
   */
  @Test
  public void benchmarkConcurrentThroughput() throws Exception {
    System.out.println("\n========================================");
    System.out.println("基准测试 2: 并发连接创建吞吐量");
    System.out.println("========================================\n");

    int threadCount = 20;
    int connectionsPerThread = 10;

    // 测试 random 策略
    long randomThroughput = measureConcurrentThroughput("random", threadCount, connectionsPerThread);

    // 清理连接
    closeAllConnections();

    // 测试 leastConn 策略
    long leastConnThroughput = measureConcurrentThroughput("leastConn", threadCount, connectionsPerThread);

    // 输出结果
    System.out.println("\n结果对比:");
    System.out.println("  Random 策略吞吐量: " + randomThroughput + " 连接/秒");
    System.out.println("  LeastConn 策略吞吐量: " + leastConnThroughput + " 连接/秒");
    System.out.println("  吞吐量差异: " + (leastConnThroughput - randomThroughput) + " 连接/秒");
    System.out.println("  吞吐量比率: " + String.format("%.2f", (double) leastConnThroughput / randomThroughput));

    // 吞吐量差异应该小于 10%
    double throughputRatio = (double) leastConnThroughput / randomThroughput;
    System.out.println("\n性能评估: " + (throughputRatio > 0.9 ? "✅ 通过" : "⚠️ 需要优化"));
  }

  /**
   * 基准测试 3: 内存占用对比.
   * <p>
   * 测试 leastConn 策略的内存开销。
   * </p>
   */
  @Test
  public void benchmarkMemoryUsage() throws SQLException {
    System.out.println("\n========================================");
    System.out.println("基准测试 3: 内存占用对比");
    System.out.println("========================================\n");

    int connectionCount = 200;

    // 测试 random 策略
    long randomMemory = measureMemoryUsage("random", connectionCount);

    // 清理连接
    closeAllConnections();

    // 测试 leastConn 策略
    long leastConnMemory = measureMemoryUsage("leastConn", connectionCount);

    // 输出结果
    System.out.println("\n结果对比:");
    System.out.println("  Random 策略内存占用: " + formatBytes(randomMemory));
    System.out.println("  LeastConn 策略内存占用: " + formatBytes(leastConnMemory));
    System.out.println("  内存差异: " + formatBytes(leastConnMemory - randomMemory));
    System.out.println("  内存比率: " + String.format("%.2f", (double) leastConnMemory / randomMemory));

    // 内存差异应该小于 20%
    double memoryRatio = (double) leastConnMemory / randomMemory;
    System.out.println("\n性能评估: " + (memoryRatio < 1.2 ? "✅ 通过" : "⚠️ 需要优化"));
  }

  /**
   * 基准测试 4: 负载均衡效果对比.
   * <p>
   * 测试 leastConn 策略的负载均衡效果（连接分布的均衡性）。
   * </p>
   */
  @Test
  public void benchmarkLoadBalancingEffectiveness() throws SQLException {
    System.out.println("\n========================================");
    System.out.println("基准测试 4: 负载均衡效果对比");
    System.out.println("========================================\n");

    int connectionCount = 200;

    // 测试 random 策略
    double randomCV = measureLoadBalancingEffectiveness("random", connectionCount);

    // 清理连接
    closeAllConnections();

    // 测试 leastConn 策略
    double leastConnCV = measureLoadBalancingEffectiveness("leastConn", connectionCount);

    // 输出结果
    System.out.println("\n结果对比:");
    System.out.println("  Random 策略变异系数: " + String.format("%.2f%%", randomCV));
    System.out.println("  LeastConn 策略变异系数: " + String.format("%.2f%%", leastConnCV));
    System.out.println("  均衡性改善: " + String.format("%.2f%%", randomCV - leastConnCV));

    // leastConn 策略应该更均衡（变异系数更小）
    System.out.println("\n性能评估: " + (leastConnCV < randomCV ? "✅ 通过" : "⚠️ 需要优化"));
  }

  // ============================================
  // Helper Methods
  // ============================================

  /**
   * 测量连接创建延迟.
   */
  private long measureConnectionCreationLatency(String strategy, int warmupCount, int testCount)
      throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, strategy);

    String url = buildConnectionUrl();

    // Warmup
    System.out.println("预热阶段 (" + strategy + "): 创建 " + warmupCount + " 个连接...");
    for (int i = 0; i < warmupCount; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);
    }

    // 测试阶段
    System.out.println("测试阶段 (" + strategy + "): 创建 " + testCount + " 个连接...");
    long startTime = System.nanoTime();
    for (int i = 0; i < testCount; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);
    }
    long endTime = System.nanoTime();

    long totalLatency = endTime - startTime;
    long avgLatency = totalLatency / testCount;

    System.out.println("  总耗时: " + (totalLatency / 1_000_000) + " ms");
    System.out.println("  平均延迟: " + avgLatency + " ns");

    return avgLatency;
  }

  /**
   * 测量并发吞吐量.
   */
  private long measureConcurrentThroughput(String strategy, int threadCount, int connectionsPerThread)
      throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, strategy);

    String url = buildConnectionUrl();

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Connection> concurrentConnections = new ArrayList<>();

    System.out.println("测试阶段 (" + strategy + "): " + threadCount + " 个线程，每个创建 "
        + connectionsPerThread + " 个连接...");

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          for (int j = 0; j < connectionsPerThread; j++) {
            Connection conn = DriverManager.getConnection(url, props);
            synchronized (concurrentConnections) {
              concurrentConnections.add(conn);
            }
            successCount.incrementAndGet();
          }
        } catch (SQLException e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await();
    long endTime = System.currentTimeMillis();

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    connections.addAll(concurrentConnections);

    long totalTime = endTime - startTime;
    long throughput = (successCount.get() * 1000L) / totalTime;

    System.out.println("  总耗时: " + totalTime + " ms");
    System.out.println("  成功连接数: " + successCount.get());
    System.out.println("  吞吐量: " + throughput + " 连接/秒");

    return throughput;
  }

  /**
   * 测量内存占用（改进版 - 多次测量取平均值）.
   */
  private long measureMemoryUsage(String strategy, int connectionCount) throws SQLException {
    int rounds = 3; // 测量 3 轮取平均值
    long[] measurements = new long[rounds];

    System.out.println("测试阶段 (" + strategy + "): 创建 " + connectionCount + " 个连接，测量 " + rounds + " 轮...");

    for (int round = 0; round < rounds; round++) {
      // 清理之前的连接
      closeAllConnections();

      // 强制多次 GC 确保内存稳定
      forceGC();

      Runtime runtime = Runtime.getRuntime();
      long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

      // 创建连接
      Properties props = new Properties();
      PGProperty.USER.set(props, testUser);
      PGProperty.PASSWORD.set(props, testPassword);
      PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
      PGProperty.LOAD_BALANCE_STRATEGY.set(props, strategy);
      String url = buildConnectionUrl();

      for (int i = 0; i < connectionCount; i++) {
        Connection conn = DriverManager.getConnection(url, props);
        connections.add(conn);
      }

      // 强制多次 GC 确保内存稳定
      forceGC();

      long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
      long memoryUsed = memoryAfter - memoryBefore;

      measurements[round] = memoryUsed;
      System.out.println("  第 " + (round + 1) + " 轮: " + formatBytes(memoryUsed));
    }

    // 计算平均值（排除异常值）
    long avgMemory = calculateAverageExcludingOutliers(measurements);
    System.out.println("  平均内存占用: " + formatBytes(avgMemory));

    return avgMemory;
  }

  /**
   * 测量负载均衡效果（使用变异系数）.
   */
  private double measureLoadBalancingEffectiveness(String strategy, int connectionCount)
      throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    PGProperty.LOAD_BALANCE_HOSTS.set(props, "true");
    PGProperty.LOAD_BALANCE_STRATEGY.set(props, strategy);

    String url = buildConnectionUrl();
    Map<String, Integer> distribution = new HashMap<>();

    System.out.println("测试阶段 (" + strategy + "): 创建 " + connectionCount + " 个连接...");

    // 创建连接并统计分布
    for (int i = 0; i < connectionCount; i++) {
      Connection conn = DriverManager.getConnection(url, props);
      connections.add(conn);

      String internalHost = getConnectedHost(conn);
      String externalHost = normalizeHost(internalHost);
      distribution.put(externalHost, distribution.getOrDefault(externalHost, 0) + 1);
    }

    // 计算变异系数
    double cv = calculateCoefficientOfVariation(distribution);

    System.out.println("  连接分布: " + distribution);
    System.out.println("  变异系数: " + String.format("%.2f%%", cv));

    return cv;
  }

  /**
   * 计算变异系数（Coefficient of Variation）.
   * 变异系数 = (标准差 / 平均值) * 100%
   * 用于衡量负载均衡的效果，值越小表示分布越均匀。
   */
  private double calculateCoefficientOfVariation(Map<String, Integer> distribution) {
    if (distribution.isEmpty()) {
      return 0.0;
    }

    // 计算平均值
    double sum = 0;
    for (int count : distribution.values()) {
      sum += count;
    }
    double avg = sum / distribution.size();

    // 计算标准差
    double variance = 0;
    for (int count : distribution.values()) {
      variance += Math.pow(count - avg, 2);
    }
    double stdDev = Math.sqrt(variance / distribution.size());

    // 计算变异系数
    return (stdDev / avg) * 100;
  }

  /**
   * 强制执行 GC（多次尝试确保内存稳定）.
   */
  private void forceGC() {
    // 执行多次 GC 并等待，确保内存稳定
    for (int i = 0; i < 3; i++) {
      System.gc();
      System.runFinalization();
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        // Ignore
      }
    }
  }

  /**
   * 计算平均值（排除异常值）.
   * 使用中位数方法，排除最大值和最小值后取平均。
   */
  private long calculateAverageExcludingOutliers(long[] values) {
    if (values.length <= 2) {
      // 数据太少，直接返回平均值
      long sum = 0;
      for (long value : values) {
        sum += value;
      }
      return sum / values.length;
    }

    // 找出最大值和最小值
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    long sum = 0;

    for (long value : values) {
      sum += value;
      if (value < min) min = value;
      if (value > max) max = value;
    }

    // 排除最大值和最小值后计算平均值
    return (sum - min - max) / (values.length - 2);
  }

  /**
   * 格式化字节数.
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    } else if (bytes < 1024 * 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    } else {
      return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
  }

  /**
   * 关闭所有连接.
   */
  private void closeAllConnections() {
    for (Connection conn : connections) {
      try {
        if (conn != null && !conn.isClosed()) {
          conn.close();
        }
      } catch (SQLException e) {
        // Ignore
      }
    }
    connections.clear();
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
    try (java.sql.Statement stmt = conn.createStatement()) {
      java.sql.ResultSet rs = stmt.executeQuery(
          "SELECT inet_server_addr() || ':' || inet_server_port() as host_port");
      if (rs.next()) {
        return rs.getString("host_port");
      }
    }
    return "unknown";
  }

  /**
   * 建立内部IP到外部端口的映射.
   */
  private Map<String, String> buildHostMapping() throws SQLException {
    Map<String, String> mapping = new HashMap<>();
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);

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
   */
  private String normalizeHost(String internalHost) {
    return internalToExternalHostMap.getOrDefault(internalHost, internalHost);
  }
}
