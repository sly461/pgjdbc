/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.readwritesplitting;

import static org.junit.Assert.assertEquals;

import org.postgresql.readwritesplitting.RouteTarget;
import org.postgresql.readwritesplitting.SqlRouteEngine;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for SqlRouteEngine.
 * Tests routing logic, transaction state management, and caching.
 */
public class SqlRouteEngineTest {

  private SqlRouteEngine engine;

  @Before
  public void setUp() {
    engine = new SqlRouteEngine();
  }

  @Test
  public void testAutoCommitFalseRoutesToMaster() {
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", false, null));
    assertEquals(RouteTarget.MASTER, engine.route("INSERT INTO users VALUES (1)", false, null));
  }

  @Test
  public void testExplicitTargetServerType() {
    // Explicit master
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, "master"));
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, "primary"));

    // Explicit replica
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, "secondary"));
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, "replica"));
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, "slave"));
  }

  @Test
  public void testBasicSqlRouting() {
    // Read operations
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, null));
    assertEquals(RouteTarget.REPLICA, engine.route("SHOW transaction_isolation", true, null));
    assertEquals(RouteTarget.REPLICA, engine.route("EXPLAIN SELECT * FROM users", true, null));

    // Write operations
    assertEquals(RouteTarget.MASTER, engine.route("INSERT INTO users VALUES (1)", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("UPDATE users SET name='test'", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("DELETE FROM users", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("CREATE TABLE test (id INT)", true, null));
  }

  @Test
  public void testSelectForUpdateRoutesToMaster() {
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users FOR UPDATE", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users FOR SHARE", true, null));
  }

  @Test
  public void testFunctionCallsRouteToMaster() {
    assertEquals(RouteTarget.MASTER, engine.route("SELECT now()", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM generate_series(1, 10)", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("CALL my_procedure()", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("{call my_procedure()}", true, null));
  }

  @Test
  public void testMasterHintRoutesToMaster() {
    assertEquals(RouteTarget.MASTER, engine.route("/*master*/ SELECT * FROM users", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("/* master */ SELECT * FROM users", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("/*+master*/ SELECT * FROM users", true, null));
  }

  @Test
  public void testTransactionBlockManagement() {
    // BEGIN starts explicit transaction
    assertEquals(RouteTarget.MASTER, engine.route("BEGIN", true, null));

    // All subsequent queries go to master
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("INSERT INTO users VALUES (1)", true, null));

    // COMMIT ends explicit transaction
    assertEquals(RouteTarget.MASTER, engine.route("COMMIT", true, null));

    // After COMMIT, SELECT goes to replica again
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, null));
  }

  @Test
  public void testStartTransactionBlock() {
    // START TRANSACTION starts explicit transaction
    assertEquals(RouteTarget.MASTER, engine.route("START TRANSACTION", true, null));

    // All subsequent queries go to master
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, null));

    // ROLLBACK ends explicit transaction
    assertEquals(RouteTarget.MASTER, engine.route("ROLLBACK", true, null));

    // After ROLLBACK, SELECT goes to replica again
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, null));
  }

  @Test
  public void testResetTransactionState() {
    // Start transaction
    engine.route("BEGIN", true, null);
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, null));

    // Reset state
    engine.resetTransactionState();

    // After reset, SELECT goes to replica
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, null));
  }

  @Test
  public void testCaching() {
    String sql = "SELECT * FROM users WHERE id = 1";

    // First call
    assertEquals(RouteTarget.REPLICA, engine.route(sql, true, null));
    assertEquals(1, engine.getCacheSize());

    // Second call should use cache
    assertEquals(RouteTarget.REPLICA, engine.route(sql, true, null));
    assertEquals(1, engine.getCacheSize());

    // Clear cache
    engine.clearCache();
    assertEquals(0, engine.getCacheSize());
  }

  @Test
  public void testUnknownSqlRoutesToMaster() {
    assertEquals(RouteTarget.MASTER, engine.route(null, true, null));
    assertEquals(RouteTarget.MASTER, engine.route("", true, null));
    assertEquals(RouteTarget.MASTER, engine.route("   ", true, null));
  }

  @Test
  public void testPriorityOrder() {
    // autoCommit=false overrides SQL type
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", false, null));

    // Explicit target overrides SQL type
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, "master"));
    assertEquals(RouteTarget.REPLICA, engine.route("INSERT INTO users VALUES (1)", true, "replica"));

    // Transaction block overrides everything except explicit target
    engine.route("BEGIN", true, null);
    assertEquals(RouteTarget.MASTER, engine.route("SELECT * FROM users", true, null));
    assertEquals(RouteTarget.REPLICA, engine.route("SELECT * FROM users", true, "replica"));
    engine.route("COMMIT", true, null);
  }

  @Test
  public void testAnonymousBlock() {
    assertEquals(RouteTarget.MASTER, engine.route("DO $$\n" +
        "DECLARE\n" +
        "    -- 变量声明区域\n" +
        "    user_count INTEGER;\n" +
        "BEGIN\n" +
        "    -- 执行代码逻辑\n" +
        "    SELECT count(*) INTO user_count FROM pg_user;\n" +
        "    RAISE NOTICE '用户总数: %', user_count;\n" +
        "END $$;", true, null));

    assertEquals(RouteTarget.MASTER, engine.route("DO LANGUAGE plpgsql $$\n" + "BEGIN\n" + "  -- 逻辑代码\n" + "EXECUTE 'SELECT * FROM users WHERE id = 1';" + "END$$;", true, null));
  }
}
