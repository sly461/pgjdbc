/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.mytest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.*;
import java.util.Properties;

/**
 * 测试数据库连接是否正常
 */
public class DatabaseConnectionTest {
  private Connection connection;

  @Before
  public void setUp() throws Exception {
    // 打开数据库连接
    Properties props = new Properties();
    props.put("reWriteBatchedInserts", "true");
    connection = TestUtil.openDB(props);
  }

  @After
  public void tearDown() throws Exception {
    // 关闭数据库连接
    TestUtil.closeDB(connection);
  }

  /**
   * 测试基本连接是否成功
   */
  @Test
  public void testConnection() throws SQLException {
    assertNotNull("连接不应该为 null", connection);
    assertFalse("连接不应该已关闭", connection.isClosed());
  }

  /**
   * 测试连接是否有效
   */
  @Test
  public void testConnectionValid() throws SQLException {
    boolean isValid = connection.isValid(5);
    assertTrue("连接应该是有效的", isValid);
  }

  /**
   * 测试能否获取数据库元数据
   */
  @Test
  public void testDatabaseMetaData() throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    assertNotNull("数据库元数据不应该为 null", metaData);

    String databaseProductName = metaData.getDatabaseProductName();
    assertNotNull("数据库产品名称不应该为 null", databaseProductName);
    assertTrue("应该是 PostgreSQL 数据库",
        databaseProductName.toLowerCase().contains("postgresql"));

    String databaseProductVersion = metaData.getDatabaseProductVersion();
    assertNotNull("数据库版本不应该为 null", databaseProductVersion);
    System.out.println("数据库产品: " + databaseProductName);
    System.out.println("数据库版本: " + databaseProductVersion);
  }

  /**
   * 测试能否执行简单查询
   */
  @Test
  public void testSimpleQuery() throws SQLException {
    Statement stmt = connection.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT 1 as test_value");

    assertTrue("结果集应该有数据", rs.next());
    int value = rs.getInt("test_value");
    assertTrue("查询结果应该为 1", value == 1);

    rs.close();
    stmt.close();
  }

  /**
   * 测试能否获取 PostgreSQL 版本
   */
  @Test
  public void testPostgreSQLVersion() throws SQLException {
    Statement stmt = connection.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT version()");

    assertTrue("应该能获取版本信息", rs.next());
    String version = rs.getString(1);
    assertNotNull("版本信息不应该为 null", version);
    System.out.println("PostgreSQL 版本: " + version);

    rs.close();
    stmt.close();
  }

  /**
   * 测试能否获取当前数据库名称
   */
  @Test
  public void testCurrentDatabase() throws SQLException {
    Statement stmt = connection.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT current_database()");

    assertTrue("应该能获取当前数据库名称", rs.next());
    String databaseName = rs.getString(1);
    assertNotNull("数据库名称不应该为 null", databaseName);
    System.out.println("当前数据库: " + databaseName);

    rs.close();
    stmt.close();
  }

  /**
   * 测试能否获取当前用户
   */
  @Test
  public void testCurrentUser() throws SQLException {
    Statement stmt = connection.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT current_user");

    assertTrue("应该能获取当前用户", rs.next());
    String userName = rs.getString(1);
    assertNotNull("用户名不应该为 null", userName);
    System.out.println("当前用户: " + userName);

    rs.close();
    stmt.close();
  }

  @Test
  public void testCurrentTimeZone() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = connection.prepareStatement("insert into test(id1) values(?); insert into test(id1) values(?)");

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch(); // initial pass
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();// preparedQuery should be wrapped
      pstmt.executeBatch();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }
}
