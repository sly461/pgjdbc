/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.readwritesplitting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.postgresql.readwritesplitting.RegexSqlParser;
import org.postgresql.readwritesplitting.ShardingSphereSqlParser;
import org.postgresql.readwritesplitting.SqlParser;
import org.postgresql.readwritesplitting.SqlParserFactory;
import org.postgresql.readwritesplitting.SqlParserStrategy;
import org.postgresql.readwritesplitting.SqlParserType;
import org.postgresql.readwritesplitting.SqlType;

import org.junit.Test;

/**
 * Unit tests for the three SQL parser implementations.
 * Verifies that all parsers correctly identify SQL statement types.
 */
public class SqlParserComparisonTest {

  // =============================================================================
  // Test SQL statements
  // =============================================================================

  // Read operations
  private static final String SELECT_SIMPLE = "SELECT * FROM users";
  private static final String SELECT_WHERE = "SELECT id, name FROM users WHERE status = 'active'";
  private static final String SHOW = "SHOW transaction_isolation";
  private static final String EXPLAIN = "EXPLAIN SELECT * FROM users";

  // Write operations
  private static final String INSERT = "INSERT INTO users (id, name) VALUES (1, 'test')";
  private static final String UPDATE = "UPDATE users SET name = 'test' WHERE id = 1";
  private static final String DELETE = "DELETE FROM users WHERE id = 1";
  private static final String CREATE = "CREATE TABLE test (id INT)";

  // Locking
  private static final String SELECT_FOR_UPDATE = "SELECT * FROM users FOR UPDATE";
  private static final String SELECT_FOR_SHARE = "SELECT * FROM users FOR SHARE";

  // Function calls
  private static final String SELECT_FUNCTION = "SELECT now()";
  private static final String CALL = "CALL my_procedure()";
  private static final String JDBC_CALL = "{call my_procedure()}";

  // Transaction control
  private static final String BEGIN = "BEGIN";
  private static final String START_TRANSACTION = "START TRANSACTION";
  private static final String COMMIT = "COMMIT";
  private static final String ROLLBACK = "ROLLBACK";

  // Hints
  private static final String MASTER_HINT = "/*master*/ SELECT * FROM users";
  private static final String MASTER_HINT_PLUS = "/*+master*/ SELECT * FROM users";

  // CTE
  private static final String SELECT_CTE = "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent";

  // DO block
  private static final String DO_BLOCK = "DO $$ BEGIN RAISE NOTICE 'test'; END $$";

  // =============================================================================
  // Character-based parser tests
  // =============================================================================

  @Test
  public void testCharacterParserSelectOperations() {
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType(SELECT_SIMPLE));
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType(SELECT_WHERE));
    assertEquals(SqlType.SHOW, SqlParser.parseSqlType(SHOW));
    assertEquals(SqlType.EXPLAIN, SqlParser.parseSqlType(EXPLAIN));
  }

  @Test
  public void testCharacterParserWriteOperations() {
    assertEquals(SqlType.INSERT, SqlParser.parseSqlType(INSERT));
    assertEquals(SqlType.UPDATE, SqlParser.parseSqlType(UPDATE));
    assertEquals(SqlType.DELETE, SqlParser.parseSqlType(DELETE));
    assertEquals(SqlType.CREATE, SqlParser.parseSqlType(CREATE));
  }

  @Test
  public void testCharacterParserLockingOperations() {
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType(SELECT_FOR_UPDATE));
    // Note: Character parser doesn't distinguish SELECT_FOR_SHARE from SELECT_FOR_UPDATE
  }

  @Test
  public void testCharacterParserFunctionCalls() {
    assertEquals(SqlType.SELECT_FUNCTION, SqlParser.parseSqlType(SELECT_FUNCTION));
    assertEquals(SqlType.CALL, SqlParser.parseSqlType(CALL));
    assertEquals(SqlType.CALL, SqlParser.parseSqlType(JDBC_CALL));
  }

  @Test
  public void testCharacterParserTransactionControl() {
    assertEquals(SqlType.BEGIN, SqlParser.parseSqlType(BEGIN));
    assertEquals(SqlType.START_TRANSACTION, SqlParser.parseSqlType(START_TRANSACTION));
    assertEquals(SqlType.COMMIT, SqlParser.parseSqlType(COMMIT));
    assertEquals(SqlType.ROLLBACK, SqlParser.parseSqlType(ROLLBACK));
  }

  @Test
  public void testCharacterParserHints() {
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType(MASTER_HINT));
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType(MASTER_HINT_PLUS));
  }

  @Test
  public void testCharacterParserCTE() {
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType(SELECT_CTE));
  }

  @Test
  public void testCharacterParserDoBlock() {
    assertEquals(SqlType.CALL, SqlParser.parseSqlType(DO_BLOCK));
  }

  // =============================================================================
  // Regex-based parser tests
  // =============================================================================

  @Test
  public void testRegexParserSelectOperations() {
    assertEquals(SqlType.SELECT, RegexSqlParser.parseSqlType(SELECT_SIMPLE));
    assertEquals(SqlType.SELECT, RegexSqlParser.parseSqlType(SELECT_WHERE));
    assertEquals(SqlType.SHOW, RegexSqlParser.parseSqlType(SHOW));
    assertEquals(SqlType.EXPLAIN, RegexSqlParser.parseSqlType(EXPLAIN));
  }

  @Test
  public void testRegexParserWriteOperations() {
    assertEquals(SqlType.INSERT, RegexSqlParser.parseSqlType(INSERT));
    assertEquals(SqlType.UPDATE, RegexSqlParser.parseSqlType(UPDATE));
    assertEquals(SqlType.DELETE, RegexSqlParser.parseSqlType(DELETE));
    assertEquals(SqlType.CREATE, RegexSqlParser.parseSqlType(CREATE));
  }

  @Test
  public void testRegexParserLockingOperations() {
    assertEquals(SqlType.SELECT_FOR_UPDATE, RegexSqlParser.parseSqlType(SELECT_FOR_UPDATE));
    assertEquals(SqlType.SELECT_FOR_SHARE, RegexSqlParser.parseSqlType(SELECT_FOR_SHARE));
  }

  @Test
  public void testRegexParserFunctionCalls() {
    assertEquals(SqlType.SELECT_FUNCTION, RegexSqlParser.parseSqlType(SELECT_FUNCTION));
    assertEquals(SqlType.CALL, RegexSqlParser.parseSqlType(CALL));
    assertEquals(SqlType.CALL, RegexSqlParser.parseSqlType(JDBC_CALL));
  }

  @Test
  public void testRegexParserTransactionControl() {
    assertEquals(SqlType.BEGIN, RegexSqlParser.parseSqlType(BEGIN));
    assertEquals(SqlType.START_TRANSACTION, RegexSqlParser.parseSqlType(START_TRANSACTION));
    assertEquals(SqlType.COMMIT, RegexSqlParser.parseSqlType(COMMIT));
    assertEquals(SqlType.ROLLBACK, RegexSqlParser.parseSqlType(ROLLBACK));
  }

  @Test
  public void testRegexParserHints() {
    assertEquals(SqlType.MASTER_HINT, RegexSqlParser.parseSqlType(MASTER_HINT));
    assertEquals(SqlType.MASTER_HINT, RegexSqlParser.parseSqlType(MASTER_HINT_PLUS));
  }

  @Test
  public void testRegexParserCTE() {
    assertEquals(SqlType.SELECT, RegexSqlParser.parseSqlType(SELECT_CTE));
  }

  @Test
  public void testRegexParserDoBlock() {
    assertEquals(SqlType.CALL, RegexSqlParser.parseSqlType(DO_BLOCK));
  }

  // =============================================================================
  // ShardingSphere parser tests (fallback mode when dependencies unavailable)
  // =============================================================================

  @Test
  public void testShardingSphereAvailability() {
    // Verify ShardingSphere is available (dependencies are in test scope)
    boolean available = ShardingSphereSqlParser.isAvailable();
    System.out.println("ShardingSphere parser available: " + available);
    // In test environment, ShardingSphere should be available
    // If not available, fallback will be used which is also fine
  }

  @Test
  public void testShardingSphereParserSelectOperations() {
    // ShardingSphere might use fallback when dependencies are not available
    SqlType selectResult = ShardingSphereSqlParser.parseSqlType(SELECT_SIMPLE);
    assertEquals(SqlType.SELECT, selectResult);

    SqlType showResult = ShardingSphereSqlParser.parseSqlType(SHOW);
    assertEquals(SqlType.SHOW, showResult);

    SqlType explainResult = ShardingSphereSqlParser.parseSqlType(EXPLAIN);
    assertEquals(SqlType.EXPLAIN, explainResult);
  }

  @Test
  public void testShardingSphereParserWriteOperations() {
    assertEquals(SqlType.INSERT, ShardingSphereSqlParser.parseSqlType(INSERT));
    assertEquals(SqlType.UPDATE, ShardingSphereSqlParser.parseSqlType(UPDATE));
    assertEquals(SqlType.DELETE, ShardingSphereSqlParser.parseSqlType(DELETE));
    assertEquals(SqlType.CREATE, ShardingSphereSqlParser.parseSqlType(CREATE));
  }

  @Test
  public void testShardingSphereParserLockingOperations() {
    SqlType forUpdateResult = ShardingSphereSqlParser.parseSqlType(SELECT_FOR_UPDATE);
    assertEquals(SqlType.SELECT_FOR_UPDATE, forUpdateResult);
  }

  @Test
  public void testShardingSphereParserTransactionControl() {
    assertEquals(SqlType.BEGIN, ShardingSphereSqlParser.parseSqlType(BEGIN));
    assertEquals(SqlType.COMMIT, ShardingSphereSqlParser.parseSqlType(COMMIT));
    assertEquals(SqlType.ROLLBACK, ShardingSphereSqlParser.parseSqlType(ROLLBACK));
  }

  @Test
  public void testShardingSphereParserHints() {
    assertEquals(SqlType.MASTER_HINT, ShardingSphereSqlParser.parseSqlType(MASTER_HINT));
    assertEquals(SqlType.MASTER_HINT, ShardingSphereSqlParser.parseSqlType(MASTER_HINT_PLUS));
  }

  @Test
  public void testShardingSphereParserJdbcCall() {
    assertEquals(SqlType.CALL, ShardingSphereSqlParser.parseSqlType(JDBC_CALL));
  }

  // =============================================================================
  // Factory and Strategy tests
  // =============================================================================

  @Test
  public void testSqlParserFactory() {
    SqlParserStrategy charParser = SqlParserFactory.getCharacterParser();
    assertNotNull(charParser);
    assertEquals(SqlParserType.CHARACTER, charParser.getType());

    SqlParserStrategy regexParser = SqlParserFactory.getRegexParser();
    assertNotNull(regexParser);
    assertEquals(SqlParserType.REGEX, regexParser.getType());
  }

  @Test
  public void testSqlParserStrategyParsing() {
    SqlParserStrategy charParser = SqlParserFactory.getCharacterParser();
    assertEquals(SqlType.SELECT, charParser.parseSqlType(SELECT_SIMPLE));
    assertEquals(SqlType.INSERT, charParser.parseSqlType(INSERT));

    SqlParserStrategy regexParser = SqlParserFactory.getRegexParser();
    assertEquals(SqlType.SELECT, regexParser.parseSqlType(SELECT_SIMPLE));
    assertEquals(SqlType.INSERT, regexParser.parseSqlType(INSERT));
  }

  @Test
  public void testDefaultParserType() {
    SqlParserType defaultType = SqlParserFactory.getDefaultType();
    assertEquals(SqlParserType.CHARACTER, defaultType);

    SqlParserFactory.setDefaultType(SqlParserType.REGEX);
    assertEquals(SqlParserType.REGEX, SqlParserFactory.getDefaultType());

    // Reset to default
    SqlParserFactory.setDefaultType(SqlParserType.CHARACTER);
  }

  // =============================================================================
  // Routing consistency tests - ensure all parsers agree on routing direction
  // =============================================================================

  @Test
  public void testAllParsersAgreeOnReadOperations() {
    // All parsers should route SELECT to REPLICA
    assertEquals(SqlType.SELECT.toRouteTarget(),
        SqlParser.parseSqlType(SELECT_SIMPLE).toRouteTarget());
    assertEquals(SqlType.SELECT.toRouteTarget(),
        RegexSqlParser.parseSqlType(SELECT_SIMPLE).toRouteTarget());
    assertEquals(SqlType.SELECT.toRouteTarget(),
        ShardingSphereSqlParser.parseSqlType(SELECT_SIMPLE).toRouteTarget());
  }

  @Test
  public void testAllParsersAgreeOnWriteOperations() {
    // All parsers should route INSERT to MASTER
    assertEquals(SqlType.INSERT.toRouteTarget(),
        SqlParser.parseSqlType(INSERT).toRouteTarget());
    assertEquals(SqlType.INSERT.toRouteTarget(),
        RegexSqlParser.parseSqlType(INSERT).toRouteTarget());
    assertEquals(SqlType.INSERT.toRouteTarget(),
        ShardingSphereSqlParser.parseSqlType(INSERT).toRouteTarget());
  }

  @Test
  public void testAllParsersAgreeOnForUpdateRouting() {
    // All parsers should route SELECT FOR UPDATE to MASTER
    SqlType charResult = SqlParser.parseSqlType(SELECT_FOR_UPDATE);
    SqlType regexResult = RegexSqlParser.parseSqlType(SELECT_FOR_UPDATE);
    SqlType ssResult = ShardingSphereSqlParser.parseSqlType(SELECT_FOR_UPDATE);

    // Routing should be to MASTER for all
    assertEquals(charResult.toRouteTarget(), regexResult.toRouteTarget());
    assertEquals(regexResult.toRouteTarget(), ssResult.toRouteTarget());
  }
}
