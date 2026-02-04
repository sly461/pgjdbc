/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.parsing;

import org.postgresql.readwritesplitting.RegexSqlParser;
import org.postgresql.readwritesplitting.ShardingSphereSqlParser;
import org.postgresql.readwritesplitting.SqlParser;
import org.postgresql.readwritesplitting.SqlType;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark for SQL parser performance comparison.
 * Compares three SQL parsing implementations:
 * <ul>
 *   <li>Character-based parsing (SqlParser) - fastest, zero-GC</li>
 *   <li>Regex-based parsing (RegexSqlParser) - good balance of speed and maintainability</li>
 *   <li>ShardingSphere parsing (ShardingSphereSqlParser) - most accurate, uses ANTLR4</li>
 * </ul>
 *
 * <p>This benchmark includes production-like SQL statements commonly seen in real applications:
 * <ul>
 *   <li>Simple SELECT queries</li>
 *   <li>Complex JOIN queries</li>
 *   <li>Aggregate queries with GROUP BY</li>
 *   <li>Subqueries</li>
 *   <li>INSERT/UPDATE/DELETE operations</li>
 *   <li>Transaction control statements</li>
 *   <li>DDL statements</li>
 *   <li>Function calls and procedures</li>
 *   <li>SELECT FOR UPDATE (pessimistic locking)</li>
 * </ul>
 */
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SqlParserBenchmark {

  // =============================================================================
  // Basic DML statements - high frequency in production
  // =============================================================================

  /** Simple SELECT - most common query type */
  private static final String SELECT_SIMPLE = "SELECT * FROM users";

  /** SELECT with WHERE clause - typical OLTP query */
  private static final String SELECT_WHERE = "SELECT id, name, email FROM users WHERE status = 'active' AND created_at > '2024-01-01'";

  /** SELECT with ORDER BY and LIMIT - pagination query */
  private static final String SELECT_PAGINATED = "SELECT id, name, created_at FROM orders ORDER BY created_at DESC LIMIT 20 OFFSET 100";

  /** SELECT with JOIN - common in normalized databases */
  private static final String SELECT_JOIN = "SELECT u.id, u.name, o.order_id, o.amount FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE o.status = 'completed'";

  /** SELECT with multiple JOINs - complex query */
  private static final String SELECT_MULTI_JOIN = "SELECT u.name, o.order_id, p.product_name, oi.quantity " +
      "FROM users u " +
      "JOIN orders o ON u.id = o.user_id " +
      "JOIN order_items oi ON o.order_id = oi.order_id " +
      "JOIN products p ON oi.product_id = p.id " +
      "WHERE o.created_at >= '2024-01-01'";

  /** SELECT with LEFT JOIN - for optional relationships */
  private static final String SELECT_LEFT_JOIN = "SELECT u.id, u.name, COUNT(o.order_id) as order_count " +
      "FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name";

  /** SELECT with aggregate functions */
  private static final String SELECT_AGGREGATE = "SELECT department_id, COUNT(*) as emp_count, AVG(salary) as avg_salary, MAX(salary) as max_salary " +
      "FROM employees GROUP BY department_id HAVING COUNT(*) > 5";

  /** SELECT with subquery */
  private static final String SELECT_SUBQUERY = "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE total_amount > 1000)";

  /** SELECT with correlated subquery */
  private static final String SELECT_CORRELATED = "SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count FROM users u";

  /** SELECT with CASE expression */
  private static final String SELECT_CASE = "SELECT name, CASE WHEN age < 18 THEN 'minor' WHEN age < 65 THEN 'adult' ELSE 'senior' END as age_group FROM users";

  /** SELECT with CTE (Common Table Expression) */
  private static final String SELECT_CTE = "WITH recent_orders AS (SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days') " +
      "SELECT user_id, COUNT(*) FROM recent_orders GROUP BY user_id";

  /** SELECT with window function */
  private static final String SELECT_WINDOW = "SELECT id, name, salary, RANK() OVER (PARTITION BY department_id ORDER BY salary DESC) as salary_rank FROM employees";

  // =============================================================================
  // Locking queries - important for routing decisions
  // =============================================================================

  /** SELECT FOR UPDATE - pessimistic locking */
  private static final String SELECT_FOR_UPDATE = "SELECT * FROM users WHERE id = 1 FOR UPDATE";

  /** SELECT FOR UPDATE with NOWAIT */
  private static final String SELECT_FOR_UPDATE_NOWAIT = "SELECT * FROM accounts WHERE account_id = 12345 FOR UPDATE NOWAIT";

  /** SELECT FOR SHARE - shared lock */
  private static final String SELECT_FOR_SHARE = "SELECT * FROM inventory WHERE product_id = 100 FOR SHARE";

  // =============================================================================
  // Write operations - must route to master
  // =============================================================================

  /** Simple INSERT */
  private static final String INSERT = "INSERT INTO users (id, name, email) VALUES (1, 'test', 'test@example.com')";

  /** INSERT with multiple rows */
  private static final String INSERT_MULTI = "INSERT INTO logs (timestamp, level, message) VALUES " +
      "('2024-01-01 10:00:00', 'INFO', 'Request started'), " +
      "('2024-01-01 10:00:01', 'DEBUG', 'Processing'), " +
      "('2024-01-01 10:00:02', 'INFO', 'Request completed')";

  /** INSERT with SELECT */
  private static final String INSERT_SELECT = "INSERT INTO user_archive (id, name, email, archived_at) " +
      "SELECT id, name, email, NOW() FROM users WHERE status = 'inactive'";

  /** INSERT with ON CONFLICT (upsert) */
  private static final String INSERT_UPSERT = "INSERT INTO user_preferences (user_id, preference_key, preference_value) " +
      "VALUES (1, 'theme', 'dark') ON CONFLICT (user_id, preference_key) DO UPDATE SET preference_value = EXCLUDED.preference_value";

  /** Simple UPDATE */
  private static final String UPDATE = "UPDATE users SET name = 'test', updated_at = NOW() WHERE id = 1";

  /** UPDATE with subquery */
  private static final String UPDATE_SUBQUERY = "UPDATE products SET price = price * 1.1 " +
      "WHERE category_id IN (SELECT id FROM categories WHERE name = 'electronics')";

  /** UPDATE with JOIN */
  private static final String UPDATE_JOIN = "UPDATE orders o SET status = 'shipped' " +
      "FROM shipping s WHERE o.order_id = s.order_id AND s.shipped_at IS NOT NULL";

  /** Simple DELETE */
  private static final String DELETE = "DELETE FROM users WHERE id = 1";

  /** DELETE with subquery */
  private static final String DELETE_SUBQUERY = "DELETE FROM session_logs WHERE user_id IN (SELECT id FROM users WHERE status = 'deleted')";

  // =============================================================================
  // Function and procedure calls
  // =============================================================================

  /** Simple function call */
  private static final String SELECT_FUNCTION = "SELECT now()";

  /** Function with parameters */
  private static final String SELECT_FUNCTION_PARAMS = "SELECT date_trunc('month', created_at) as month, COUNT(*) FROM orders GROUP BY 1";

  /** Table-returning function */
  private static final String SELECT_GENERATE_SERIES = "SELECT * FROM generate_series(1, 100) as n";

  /** CALL procedure */
  private static final String CALL_PROCEDURE = "CALL process_monthly_report(2024, 1)";

  /** JDBC escape call syntax */
  private static final String JDBC_CALL = "{call update_user_stats(?)}";

  /** JDBC escape call with return value */
  private static final String JDBC_CALL_RETURN = "{? = call get_user_balance(?)}";

  // =============================================================================
  // Transaction control
  // =============================================================================

  /** BEGIN transaction */
  private static final String BEGIN = "BEGIN";

  /** START TRANSACTION */
  private static final String START_TRANSACTION = "START TRANSACTION";

  /** START TRANSACTION with isolation level */
  private static final String START_TRANSACTION_ISOLATION = "START TRANSACTION ISOLATION LEVEL SERIALIZABLE";

  /** COMMIT */
  private static final String COMMIT = "COMMIT";

  /** ROLLBACK */
  private static final String ROLLBACK = "ROLLBACK";

  /** SAVEPOINT */
  private static final String SAVEPOINT = "SAVEPOINT my_savepoint";

  // =============================================================================
  // DDL statements
  // =============================================================================

  /** CREATE TABLE */
  private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS user_sessions (" +
      "id SERIAL PRIMARY KEY, " +
      "user_id INTEGER NOT NULL REFERENCES users(id), " +
      "token VARCHAR(255) NOT NULL, " +
      "expires_at TIMESTAMP NOT NULL, " +
      "created_at TIMESTAMP DEFAULT NOW())";

  /** CREATE INDEX */
  private static final String CREATE_INDEX = "CREATE INDEX CONCURRENTLY idx_orders_user_id ON orders(user_id)";

  /** ALTER TABLE */
  private static final String ALTER_TABLE = "ALTER TABLE users ADD COLUMN phone VARCHAR(20)";

  /** DROP INDEX */
  private static final String DROP_INDEX = "DROP INDEX IF EXISTS idx_temp";

  // =============================================================================
  // Utility and special queries
  // =============================================================================

  /** SHOW command */
  private static final String SHOW = "SHOW transaction_isolation";

  /** EXPLAIN query */
  private static final String EXPLAIN = "EXPLAIN ANALYZE SELECT * FROM users WHERE email = 'test@example.com'";

  /** SET command */
  private static final String SET = "SET statement_timeout = '30s'";

  /** Master hint */
  private static final String SELECT_WITH_HINT = "/*master*/ SELECT * FROM users WHERE id = 1";

  /** Master hint with plus */
  private static final String SELECT_WITH_HINT_PLUS = "/*+master*/ SELECT balance FROM accounts WHERE id = 1";

  /** Anonymous code block (DO) */
  private static final String DO_BLOCK = "DO $$ " +
      "DECLARE user_count INTEGER; " +
      "BEGIN " +
      "SELECT count(*) INTO user_count FROM users; " +
      "RAISE NOTICE 'User count: %', user_count; " +
      "END $$";

  // =============================================================================
  // SQL array for batch testing
  // =============================================================================

  /** Production-like SQL mix for realistic testing */
  private static final String[] PRODUCTION_SQL_MIX = {
      SELECT_SIMPLE,
      SELECT_WHERE,
      SELECT_PAGINATED,
      SELECT_JOIN,
      SELECT_AGGREGATE,
      SELECT_FOR_UPDATE,
      INSERT,
      UPDATE,
      DELETE,
      SELECT_FUNCTION,
      BEGIN,
      COMMIT,
      SHOW,
      EXPLAIN
  };

  /**
   * Complete SQL array containing ALL defined SQL statements.
   * Used for comprehensive batch testing to simulate real production workload.
   * This includes all types of SQL: SELECT, INSERT, UPDATE, DELETE, DDL, DCL, TCL, etc.
   */
  private static final String[] ALL_SQL_STATEMENTS = {
      // Basic DML - SELECT operations
      SELECT_SIMPLE,
      SELECT_WHERE,
      SELECT_PAGINATED,
      SELECT_JOIN,
      SELECT_MULTI_JOIN,
      SELECT_LEFT_JOIN,
      SELECT_AGGREGATE,
      SELECT_SUBQUERY,
      SELECT_CORRELATED,
      SELECT_CASE,
      SELECT_CTE,
      SELECT_WINDOW,

      // Locking queries
      SELECT_FOR_UPDATE,
      SELECT_FOR_UPDATE_NOWAIT,
      SELECT_FOR_SHARE,

      // Write operations
      INSERT,
      INSERT_MULTI,
      INSERT_SELECT,
      INSERT_UPSERT,
      UPDATE,
      UPDATE_SUBQUERY,
      UPDATE_JOIN,
      DELETE,
      DELETE_SUBQUERY,

      // Function and procedure calls
      SELECT_FUNCTION,
      SELECT_FUNCTION_PARAMS,
      SELECT_GENERATE_SERIES,
      CALL_PROCEDURE,
      JDBC_CALL,
      JDBC_CALL_RETURN,

      // Transaction control
      BEGIN,
      START_TRANSACTION,
      START_TRANSACTION_ISOLATION,
      COMMIT,
      ROLLBACK,
      SAVEPOINT,

      // DDL statements
      CREATE_TABLE,
      CREATE_INDEX,
      ALTER_TABLE,
      DROP_INDEX,

      // Utility and special queries
      SHOW,
      EXPLAIN,
      SET,
      SELECT_WITH_HINT,
      SELECT_WITH_HINT_PLUS,
      DO_BLOCK
  };

  // Index for iterating through SQL mix
  private int sqlIndex = 0;

  @Setup
  public void setup() {
    sqlIndex = 0;
  }

  // =============================================================================
  // Character-based parser benchmarks
  // =============================================================================

//   @Benchmark
//   public SqlType char_SelectSimple() {
//     return SqlParser.parseSqlType(SELECT_SIMPLE);
//   }
//
//   @Benchmark
//   public SqlType char_SelectWhere() {
//     return SqlParser.parseSqlType(SELECT_WHERE);
//   }
//
//   @Benchmark
//   public SqlType char_SelectJoin() {
//     return SqlParser.parseSqlType(SELECT_JOIN);
//   }
//
//   @Benchmark
//   public SqlType char_SelectMultiJoin() {
//     return SqlParser.parseSqlType(SELECT_MULTI_JOIN);
//   }
//
//   @Benchmark
//   public SqlType char_SelectAggregate() {
//     return SqlParser.parseSqlType(SELECT_AGGREGATE);
//   }
//
//   @Benchmark
//   public SqlType char_SelectSubquery() {
//     return SqlParser.parseSqlType(SELECT_SUBQUERY);
//   }
//
//   @Benchmark
//   public SqlType char_SelectCTE() {
//     return SqlParser.parseSqlType(SELECT_CTE);
//   }
//
//   @Benchmark
//   public SqlType char_SelectForUpdate() {
//     return SqlParser.parseSqlType(SELECT_FOR_UPDATE);
//   }
//
//   @Benchmark
//   public SqlType char_Insert() {
//     return SqlParser.parseSqlType(INSERT);
//   }
//
//   @Benchmark
//   public SqlType char_InsertMulti() {
//     return SqlParser.parseSqlType(INSERT_MULTI);
//   }
//
//   @Benchmark
//   public SqlType char_Update() {
//     return SqlParser.parseSqlType(UPDATE);
//   }
//
//   @Benchmark
//   public SqlType char_Delete() {
//     return SqlParser.parseSqlType(DELETE);
//   }
//
//   @Benchmark
//   public SqlType char_SelectFunction() {
//     return SqlParser.parseSqlType(SELECT_FUNCTION);
//   }
//
//   @Benchmark
//   public SqlType char_CallProcedure() {
//     return SqlParser.parseSqlType(CALL_PROCEDURE);
//   }
//
//   @Benchmark
//   public SqlType char_JdbcCall() {
//     return SqlParser.parseSqlType(JDBC_CALL);
//   }
//
//   @Benchmark
//   public SqlType char_Begin() {
//     return SqlParser.parseSqlType(BEGIN);
//   }
//
//   @Benchmark
//   public SqlType char_Commit() {
//     return SqlParser.parseSqlType(COMMIT);
//   }
//
//   @Benchmark
//   public SqlType char_CreateTable() {
//     return SqlParser.parseSqlType(CREATE_TABLE);
//   }
//
//   @Benchmark
//   public SqlType char_Show() {
//     return SqlParser.parseSqlType(SHOW);
//   }
//
//   @Benchmark
//   public SqlType char_MasterHint() {
//     return SqlParser.parseSqlType(SELECT_WITH_HINT);
//   }
//
//   @Benchmark
//   public SqlType char_DoBlock() {
//     return SqlParser.parseSqlType(DO_BLOCK);
//   }
//
//   @Benchmark
//   public SqlType char_ProductionMix() {
//     String sql = PRODUCTION_SQL_MIX[sqlIndex % PRODUCTION_SQL_MIX.length];
//     sqlIndex++;
//     return SqlParser.parseSqlType(sql);
//   }
//
//   // =============================================================================
//   // Regex-based parser benchmarks
//   // =============================================================================
//
//   @Benchmark
//   public SqlType regex_SelectSimple() {
//     return RegexSqlParser.parseSqlType(SELECT_SIMPLE);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectWhere() {
//     return RegexSqlParser.parseSqlType(SELECT_WHERE);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectJoin() {
//     return RegexSqlParser.parseSqlType(SELECT_JOIN);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectMultiJoin() {
//     return RegexSqlParser.parseSqlType(SELECT_MULTI_JOIN);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectAggregate() {
//     return RegexSqlParser.parseSqlType(SELECT_AGGREGATE);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectSubquery() {
//     return RegexSqlParser.parseSqlType(SELECT_SUBQUERY);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectCTE() {
//     return RegexSqlParser.parseSqlType(SELECT_CTE);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectForUpdate() {
//     return RegexSqlParser.parseSqlType(SELECT_FOR_UPDATE);
//   }
//
//   @Benchmark
//   public SqlType regex_Insert() {
//     return RegexSqlParser.parseSqlType(INSERT);
//   }
//
//   @Benchmark
//   public SqlType regex_InsertMulti() {
//     return RegexSqlParser.parseSqlType(INSERT_MULTI);
//   }
//
//   @Benchmark
//   public SqlType regex_Update() {
//     return RegexSqlParser.parseSqlType(UPDATE);
//   }
//
//   @Benchmark
//   public SqlType regex_Delete() {
//     return RegexSqlParser.parseSqlType(DELETE);
//   }
//
//   @Benchmark
//   public SqlType regex_SelectFunction() {
//     return RegexSqlParser.parseSqlType(SELECT_FUNCTION);
//   }
//
//   @Benchmark
//   public SqlType regex_CallProcedure() {
//     return RegexSqlParser.parseSqlType(CALL_PROCEDURE);
//   }
//
//   @Benchmark
//   public SqlType regex_JdbcCall() {
//     return RegexSqlParser.parseSqlType(JDBC_CALL);
//   }
//
//   @Benchmark
//   public SqlType regex_Begin() {
//     return RegexSqlParser.parseSqlType(BEGIN);
//   }
//
//   @Benchmark
//   public SqlType regex_Commit() {
//     return RegexSqlParser.parseSqlType(COMMIT);
//   }
//
//   @Benchmark
//   public SqlType regex_CreateTable() {
//     return RegexSqlParser.parseSqlType(CREATE_TABLE);
//   }
//
//   @Benchmark
//   public SqlType regex_Show() {
//     return RegexSqlParser.parseSqlType(SHOW);
//   }
//
//   @Benchmark
//   public SqlType regex_MasterHint() {
//     return RegexSqlParser.parseSqlType(SELECT_WITH_HINT);
//   }
//
//   @Benchmark
//   public SqlType regex_DoBlock() {
//     return RegexSqlParser.parseSqlType(DO_BLOCK);
//   }
//
//   @Benchmark
//   public SqlType regex_ProductionMix() {
//     String sql = PRODUCTION_SQL_MIX[sqlIndex % PRODUCTION_SQL_MIX.length];
//     sqlIndex++;
//     return RegexSqlParser.parseSqlType(sql);
//   }
//
//   // =============================================================================
//   // ShardingSphere parser benchmarks
//   // =============================================================================
//
//   @Benchmark
//   public SqlType ss_SelectSimple() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_SIMPLE);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectWhere() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_WHERE);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectJoin() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_JOIN);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectMultiJoin() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_MULTI_JOIN);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectAggregate() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_AGGREGATE);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectSubquery() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_SUBQUERY);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectCTE() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_CTE);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectForUpdate() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_FOR_UPDATE);
//   }
//
//   @Benchmark
//   public SqlType ss_Insert() {
//     return ShardingSphereSqlParser.parseSqlType(INSERT);
//   }
//
//   @Benchmark
//   public SqlType ss_InsertMulti() {
//     return ShardingSphereSqlParser.parseSqlType(INSERT_MULTI);
//   }
//
//   @Benchmark
//   public SqlType ss_Update() {
//     return ShardingSphereSqlParser.parseSqlType(UPDATE);
//   }
//
//   @Benchmark
//   public SqlType ss_Delete() {
//     return ShardingSphereSqlParser.parseSqlType(DELETE);
//   }
//
//   @Benchmark
//   public SqlType ss_SelectFunction() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_FUNCTION);
//   }
//
//   @Benchmark
//   public SqlType ss_CallProcedure() {
//     return ShardingSphereSqlParser.parseSqlType(CALL_PROCEDURE);
//   }
//
//   @Benchmark
//   public SqlType ss_JdbcCall() {
//     return ShardingSphereSqlParser.parseSqlType(JDBC_CALL);
//   }
//
//   @Benchmark
//   public SqlType ss_Begin() {
//     return ShardingSphereSqlParser.parseSqlType(BEGIN);
//   }
//
//   @Benchmark
//   public SqlType ss_Commit() {
//     return ShardingSphereSqlParser.parseSqlType(COMMIT);
//   }
//
//   @Benchmark
//   public SqlType ss_CreateTable() {
//     return ShardingSphereSqlParser.parseSqlType(CREATE_TABLE);
//   }
//
//   @Benchmark
//   public SqlType ss_Show() {
//     return ShardingSphereSqlParser.parseSqlType(SHOW);
//   }
//
//   @Benchmark
//   public SqlType ss_MasterHint() {
//     return ShardingSphereSqlParser.parseSqlType(SELECT_WITH_HINT);
//   }
//
//   @Benchmark
//   public SqlType ss_DoBlock() {
//     return ShardingSphereSqlParser.parseSqlType(DO_BLOCK);
//   }
//
//   @Benchmark
//   public SqlType ss_ProductionMix() {
//     String sql = PRODUCTION_SQL_MIX[sqlIndex % PRODUCTION_SQL_MIX.length];
//     sqlIndex++;
//     return ShardingSphereSqlParser.parseSqlType(sql);
//   }

  // =============================================================================
  // Comprehensive batch benchmarks - parse ALL SQL statements in one iteration
  // These simulate real production workload by processing all SQL types together
  // =============================================================================

  /**
   * Character-based parser: Parse all SQL statements in a single batch.
   * Measures both average time and throughput for all 46 SQL statements.
   * This simulates processing a batch of mixed SQL in production.
   *
   * @return sum of hash codes to prevent dead code elimination
   */
  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.Throughput})
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int char_AllSqlBatch() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += SqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  /**
   * Regex-based parser: Parse all SQL statements in a single batch.
   * Measures both average time and throughput for all 46 SQL statements.
   * This simulates processing a batch of mixed SQL in production.
   *
   * @return sum of hash codes to prevent dead code elimination
   */
  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.Throughput})
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int regex_AllSqlBatch() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += RegexSqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  /**
   * ShardingSphere parser: Parse all SQL statements in a single batch.
   * Measures both average time and throughput for all 46 SQL statements.
   * This simulates processing a batch of mixed SQL in production.
   *
   * @return sum of hash codes to prevent dead code elimination
   */
  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.Throughput})
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int ss_AllSqlBatch() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += ShardingSphereSqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  // =============================================================================
  // Main method for standalone execution
  // =============================================================================

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(SqlParserBenchmark.class.getSimpleName())
        .build();

    new Runner(opt).run();
  }
}
