/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.readwritesplitting;

import static org.junit.Assert.assertEquals;

import org.postgresql.readwritesplitting.SqlParser;
import org.postgresql.readwritesplitting.SqlType;

import org.junit.Test;

/**
 * Unit tests for SqlParser.
 * Tests cover basic SQL types, SELECT variants, function calls, hints, comments, and edge cases.
 */
public class SqlParserTest {

  @Test
  public void testBasicSelectStatement() {
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("SELECT * FROM users"));
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("select * from users"));
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("  SELECT * FROM users  "));
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("\n\tSELECT * FROM users"));
  }

  @Test
  public void testSelectForUpdate() {
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType("SELECT * FROM users FOR UPDATE"));
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType("select * from users for update"));
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType("SELECT * FROM users WHERE id=1 FOR UPDATE"));
  }

  @Test
  public void testSelectForShare() {
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType("SELECT * FROM users FOR SHARE"));
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType("select * from users for share"));
  }

  @Test
  public void testInsertStatement() {
    assertEquals(SqlType.INSERT, SqlParser.parseSqlType("INSERT INTO users VALUES (1, 'test')"));
    assertEquals(SqlType.INSERT, SqlParser.parseSqlType("insert into users values (1, 'test')"));
  }

  @Test
  public void testUpdateStatement() {
    assertEquals(SqlType.UPDATE, SqlParser.parseSqlType("UPDATE users SET name='test' WHERE id=1"));
    assertEquals(SqlType.UPDATE, SqlParser.parseSqlType("update users set name='test'"));
  }

  @Test
  public void testDeleteStatement() {
    assertEquals(SqlType.DELETE, SqlParser.parseSqlType("DELETE FROM users WHERE id=1"));
    assertEquals(SqlType.DELETE, SqlParser.parseSqlType("delete from users"));
  }

  @Test
  public void testDdlStatements() {
    assertEquals(SqlType.CREATE, SqlParser.parseSqlType("CREATE TABLE users (id INT)"));
    assertEquals(SqlType.ALTER, SqlParser.parseSqlType("ALTER TABLE users ADD COLUMN name VARCHAR(100)"));
    assertEquals(SqlType.DROP, SqlParser.parseSqlType("DROP TABLE users"));
    assertEquals(SqlType.TRUNCATE, SqlParser.parseSqlType("TRUNCATE TABLE users"));
  }

  @Test
  public void testTransactionControl() {
    assertEquals(SqlType.BEGIN, SqlParser.parseSqlType("BEGIN"));
    assertEquals(SqlType.BEGIN, SqlParser.parseSqlType("begin"));
    assertEquals(SqlType.START_TRANSACTION, SqlParser.parseSqlType("START TRANSACTION"));
    assertEquals(SqlType.COMMIT, SqlParser.parseSqlType("COMMIT"));
    assertEquals(SqlType.ROLLBACK, SqlParser.parseSqlType("ROLLBACK"));
  }

  @Test
  public void testShowStatement() {
    assertEquals(SqlType.SHOW, SqlParser.parseSqlType("SHOW transaction_isolation"));
    assertEquals(SqlType.SHOW, SqlParser.parseSqlType("show all"));
  }

  @Test
  public void testExplainStatement() {
    assertEquals(SqlType.EXPLAIN, SqlParser.parseSqlType("EXPLAIN SELECT * FROM users"));
    assertEquals(SqlType.EXPLAIN, SqlParser.parseSqlType("explain analyze select * from users"));
  }

  @Test
  public void testFunctionCalls() {
    // SELECT func() - no FROM
    assertEquals(SqlType.SELECT_FUNCTION, SqlParser.parseSqlType("SELECT now()"));
    assertEquals(SqlType.SELECT_FUNCTION, SqlParser.parseSqlType("SELECT current_timestamp()"));

    // SELECT * FROM func()
    assertEquals(SqlType.SELECT_FUNCTION, SqlParser.parseSqlType("SELECT * FROM generate_series(1, 10)"));

    // CALL statement
    assertEquals(SqlType.CALL, SqlParser.parseSqlType("CALL my_procedure()"));
  }

  @Test
  public void testJdbcEscapeSyntax() {
    // {call proc()}
    assertEquals(SqlType.CALL, SqlParser.parseSqlType("{call my_procedure()}"));

    // {? = call func()}
    assertEquals(SqlType.CALL, SqlParser.parseSqlType("{? = call my_function()}"));
  }

  @Test
  public void testMasterHint() {
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType("/*master*/ SELECT * FROM users"));
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType("/* master */ SELECT * FROM users"));
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType("/*+master*/ SELECT * FROM users"));
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType("/*+ master */ SELECT * FROM users"));
    assertEquals(SqlType.MASTER_HINT, SqlParser.parseSqlType("/*MASTER*/ SELECT * FROM users"));
  }

  @Test
  public void testCommentsWithoutHint() {
    // Regular comments should not affect parsing
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("/* comment */ SELECT * FROM users"));
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("-- comment\nSELECT * FROM users"));
  }

  @Test
  public void testPostgreSqlSpecificCommands() {
    assertEquals(SqlType.VACUUM, SqlParser.parseSqlType("VACUUM"));
    assertEquals(SqlType.ANALYZE, SqlParser.parseSqlType("ANALYZE users"));
    assertEquals(SqlType.COPY, SqlParser.parseSqlType("COPY users FROM '/tmp/data.csv'"));
  }

  @Test
  public void testDclStatements() {
    assertEquals(SqlType.GRANT, SqlParser.parseSqlType("GRANT SELECT ON users TO user1"));
    assertEquals(SqlType.REVOKE, SqlParser.parseSqlType("REVOKE SELECT ON users FROM user1"));
  }

  @Test
  public void testEdgeCases() {
    // Null and empty
    assertEquals(SqlType.UNKNOWN, SqlParser.parseSqlType(null));
    assertEquals(SqlType.UNKNOWN, SqlParser.parseSqlType(""));
    assertEquals(SqlType.UNKNOWN, SqlParser.parseSqlType("   "));

    // Only comments
    assertEquals(SqlType.UNKNOWN, SqlParser.parseSqlType("/* comment */"));
    assertEquals(SqlType.UNKNOWN, SqlParser.parseSqlType("-- comment"));
  }

  @Test
  public void testMixedCase() {
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType("SeLeCt * FrOm users"));
    assertEquals(SqlType.INSERT, SqlParser.parseSqlType("InSeRt InTo users VALUES (1)"));
  }

  @Test
  public void testMultilineStatements() {
    String multiline = "SELECT *\nFROM users\nWHERE id = 1";
    assertEquals(SqlType.SELECT, SqlParser.parseSqlType(multiline));

    String multilineForUpdate = "SELECT *\nFROM users\nWHERE id = 1\nFOR UPDATE";
    assertEquals(SqlType.SELECT_FOR_UPDATE, SqlParser.parseSqlType(multilineForUpdate));
  }

  @Test
  public void testSetStatement() {
    assertEquals(SqlType.SET, SqlParser.parseSqlType("SET search_path TO public"));
    assertEquals(SqlType.SET, SqlParser.parseSqlType("set transaction isolation level serializable"));
  }

  @Test
  public void testSavepoint() {
    assertEquals(SqlType.SAVEPOINT, SqlParser.parseSqlType("SAVEPOINT my_savepoint"));
    assertEquals(SqlType.RELEASE, SqlParser.parseSqlType("RELEASE SAVEPOINT my_savepoint"));
  }
}
