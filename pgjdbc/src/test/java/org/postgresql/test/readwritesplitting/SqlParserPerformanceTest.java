/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.readwritesplitting;

import org.postgresql.readwritesplitting.RegexSqlParser;
import org.postgresql.readwritesplitting.ShardingSphereSqlParser;
import org.postgresql.readwritesplitting.SqlParser;
import org.postgresql.readwritesplitting.SqlType;

import org.junit.Test;

/**
 * Performance test for comparing three SQL parser implementations.
 * This test measures the total time to parse a batch of production-like SQL statements.
 */
public class SqlParserPerformanceTest {

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  // Complete SQL array containing ALL SQL statements for comprehensive testing
  private static final String[] ALL_SQL_STATEMENTS = {
      // Basic DML - SELECT operations
      "SELECT * FROM users",
      "SELECT id, name, email FROM users WHERE status = 'active' AND created_at > '2024-01-01'",
      "SELECT id, name, created_at FROM orders ORDER BY created_at DESC LIMIT 20 OFFSET 100",
      "SELECT u.id, u.name, o.order_id, o.amount FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE o.status = 'completed'",
      "SELECT u.name, o.order_id, p.product_name, oi.quantity FROM users u JOIN orders o ON u.id = o.user_id JOIN order_items oi ON o.order_id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.created_at >= '2024-01-01'",
      "SELECT u.id, u.name, COUNT(o.order_id) as order_count FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name",
      "SELECT department_id, COUNT(*) as emp_count, AVG(salary) as avg_salary, MAX(salary) as max_salary FROM employees GROUP BY department_id HAVING COUNT(*) > 5",
      "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE total_amount > 1000)",
      "SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count FROM users u",
      "SELECT name, CASE WHEN age < 18 THEN 'minor' WHEN age < 65 THEN 'adult' ELSE 'senior' END as age_group FROM users",
      "WITH recent_orders AS (SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days') SELECT user_id, COUNT(*) FROM recent_orders GROUP BY user_id",
      "SELECT id, name, salary, RANK() OVER (PARTITION BY department_id ORDER BY salary DESC) as salary_rank FROM employees",

      // Locking queries
      "SELECT * FROM users WHERE id = 1 FOR UPDATE",
      "SELECT * FROM accounts WHERE account_id = 12345 FOR UPDATE NOWAIT",
      "SELECT * FROM inventory WHERE product_id = 100 FOR SHARE",

      // Write operations
      "INSERT INTO users (id, name, email) VALUES (1, 'test', 'test@example.com')",
      "INSERT INTO logs (timestamp, level, message) VALUES ('2024-01-01 10:00:00', 'INFO', 'Request started'), ('2024-01-01 10:00:01', 'DEBUG', 'Processing'), ('2024-01-01 10:00:02', 'INFO', 'Request completed')",
      "INSERT INTO user_archive (id, name, email, archived_at) SELECT id, name, email, NOW() FROM users WHERE status = 'inactive'",
      "INSERT INTO user_preferences (user_id, preference_key, preference_value) VALUES (1, 'theme', 'dark') ON CONFLICT (user_id, preference_key) DO UPDATE SET preference_value = EXCLUDED.preference_value",
      "UPDATE users SET name = 'test', updated_at = NOW() WHERE id = 1",
      "UPDATE products SET price = price * 1.1 WHERE category_id IN (SELECT id FROM categories WHERE name = 'electronics')",
      "UPDATE orders o SET status = 'shipped' FROM shipping s WHERE o.order_id = s.order_id AND s.shipped_at IS NOT NULL",
      "DELETE FROM users WHERE id = 1",
      "DELETE FROM session_logs WHERE user_id IN (SELECT id FROM users WHERE status = 'deleted')",

      // Function and procedure calls
      "SELECT now()",
      "SELECT date_trunc('month', created_at) as month, COUNT(*) FROM orders GROUP BY 1",
      "SELECT * FROM generate_series(1, 100) as n",
      "CALL process_monthly_report(2024, 1)",
      "{call update_user_stats(?)}",
      "{? = call get_user_balance(?)}",

      // Transaction control
      "BEGIN",
      "START TRANSACTION",
      "START TRANSACTION ISOLATION LEVEL SERIALIZABLE",
      "COMMIT",
      "ROLLBACK",
      "SAVEPOINT my_savepoint",

      // DDL statements
      "CREATE TABLE IF NOT EXISTS user_sessions (id SERIAL PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), token VARCHAR(255) NOT NULL, expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP DEFAULT NOW())",
      "CREATE INDEX CONCURRENTLY idx_orders_user_id ON orders(user_id)",
      "ALTER TABLE users ADD COLUMN phone VARCHAR(20)",
      "DROP INDEX IF EXISTS idx_temp",

      // Utility and special queries
      "SHOW transaction_isolation",
      "EXPLAIN ANALYZE SELECT * FROM users WHERE email = 'test@example.com'",
      "SET statement_timeout = '30s'",
      "/*master*/ SELECT * FROM users WHERE id = 1",
      "/*+master*/ SELECT balance FROM accounts WHERE id = 1",
      "DO $$ DECLARE user_count INTEGER; BEGIN SELECT count(*) INTO user_count FROM users; RAISE NOTICE 'User count: %', user_count; END $$"
  };

  private static final int WARMUP_ITERATIONS = 1000;
  private static final int MEASURE_ITERATIONS = 10000;

  @Test
  public void testComprehensivePerformanceComparison() {
    System.out.println(repeat("=", 80));
    System.out.println("SQL Parser Comprehensive Performance Test");
    System.out.println("SQL statements count: " + ALL_SQL_STATEMENTS.length);
    System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
    System.out.println("Measurement iterations: " + MEASURE_ITERATIONS);
    System.out.println(repeat("=", 80));

    // Warmup
    System.out.println("\nWarming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      parseAllWithCharacter();
      parseAllWithRegex();
      parseAllWithShardingSphere();
    }

    // Measure Character-based parser
    System.out.println("\nMeasuring Character-based parser...");
    long charTotal = 0;
    for (int i = 0; i < MEASURE_ITERATIONS; i++) {
      long start = System.nanoTime();
      parseAllWithCharacter();
      charTotal += System.nanoTime() - start;
    }
    double charAvgUs = (charTotal / MEASURE_ITERATIONS) / 1000.0;
    double charPerSqlNs = (double) charTotal / MEASURE_ITERATIONS / ALL_SQL_STATEMENTS.length;

    // Measure Regex-based parser
    System.out.println("Measuring Regex-based parser...");
    long regexTotal = 0;
    for (int i = 0; i < MEASURE_ITERATIONS; i++) {
      long start = System.nanoTime();
      parseAllWithRegex();
      regexTotal += System.nanoTime() - start;
    }
    double regexAvgUs = (regexTotal / MEASURE_ITERATIONS) / 1000.0;
    double regexPerSqlNs = (double) regexTotal / MEASURE_ITERATIONS / ALL_SQL_STATEMENTS.length;

    // Measure ShardingSphere parser
    System.out.println("Measuring ShardingSphere parser...");
    boolean ssAvailable = ShardingSphereSqlParser.isAvailable();
    long ssTotal = 0;
    double ssAvgUs = 0;
    double ssPerSqlNs = 0;
    if (ssAvailable) {
      for (int i = 0; i < MEASURE_ITERATIONS; i++) {
        long start = System.nanoTime();
        parseAllWithShardingSphere();
        ssTotal += System.nanoTime() - start;
      }
      ssAvgUs = (ssTotal / MEASURE_ITERATIONS) / 1000.0;
      ssPerSqlNs = (double) ssTotal / MEASURE_ITERATIONS / ALL_SQL_STATEMENTS.length;
    }

    // Print results
    System.out.println("\n" + repeat("=", 80));
    System.out.println("RESULTS (parsing " + ALL_SQL_STATEMENTS.length + " SQL statements)");
    System.out.println(repeat("=", 80));

    System.out.println("\n--- Average Time per Batch (lower is better) ---");
    System.out.printf("Character-based:   %10.2f us/batch%n", charAvgUs);
    System.out.printf("Regex-based:       %10.2f us/batch%n", regexAvgUs);
    if (ssAvailable) {
      System.out.printf("ShardingSphere:    %10.2f us/batch%n", ssAvgUs);
    } else {
      System.out.println("ShardingSphere:    N/A (dependencies not available)");
    }

    System.out.println("\n--- Average Time per SQL Statement (lower is better) ---");
    System.out.printf("Character-based:   %10.2f ns/sql%n", charPerSqlNs);
    System.out.printf("Regex-based:       %10.2f ns/sql%n", regexPerSqlNs);
    if (ssAvailable) {
      System.out.printf("ShardingSphere:    %10.2f ns/sql%n", ssPerSqlNs);
    } else {
      System.out.println("ShardingSphere:    N/A (dependencies not available)");
    }

    System.out.println("\n--- Throughput (higher is better) ---");
    System.out.printf("Character-based:   %,10.0f sql/sec%n", 1_000_000_000.0 / charPerSqlNs);
    System.out.printf("Regex-based:       %,10.0f sql/sec%n", 1_000_000_000.0 / regexPerSqlNs);
    if (ssAvailable) {
      System.out.printf("ShardingSphere:    %,10.0f sql/sec%n", 1_000_000_000.0 / ssPerSqlNs);
    } else {
      System.out.println("ShardingSphere:    N/A (dependencies not available)");
    }

    System.out.println("\n--- Performance Ratio (Character-based = 1.0x) ---");
    System.out.printf("Character-based:   %10.1fx%n", 1.0);
    System.out.printf("Regex-based:       %10.1fx slower%n", regexAvgUs / charAvgUs);
    if (ssAvailable) {
      System.out.printf("ShardingSphere:    %10.1fx slower%n", ssAvgUs / charAvgUs);
    } else {
      System.out.println("ShardingSphere:    N/A (dependencies not available)");
    }

    System.out.println("\n" + repeat("=", 80));
  }

  private int parseAllWithCharacter() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += SqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  private int parseAllWithRegex() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += RegexSqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  private int parseAllWithShardingSphere() {
    int result = 0;
    for (String sql : ALL_SQL_STATEMENTS) {
      result += ShardingSphereSqlParser.parseSqlType(sql).ordinal();
    }
    return result;
  }

  @Test
  public void testIndividualSqlTypePerformance() {
    System.out.println("\n" + repeat("=", 80));
    System.out.println("Individual SQL Type Performance Comparison");
    System.out.println(repeat("=", 80));

    String[] testSqls = {
        "SELECT * FROM users",
        "SELECT * FROM users FOR UPDATE",
        "INSERT INTO users VALUES (1)",
        "UPDATE users SET name = 'test'",
        "DELETE FROM users WHERE id = 1",
        "BEGIN",
        "COMMIT",
        "/*master*/ SELECT * FROM users",
        "WITH cte AS (SELECT 1) SELECT * FROM cte"
    };

    System.out.printf("%-45s %15s %15s %15s%n", "SQL", "Char(ns)", "Regex(ns)", "SS(ns)");
    System.out.println(repeat("-", 95));

    boolean ssAvailable = ShardingSphereSqlParser.isAvailable();

    for (String sql : testSqls) {
      // Warmup
      for (int i = 0; i < 10000; i++) {
        SqlParser.parseSqlType(sql);
        RegexSqlParser.parseSqlType(sql);
        if (ssAvailable) {
          ShardingSphereSqlParser.parseSqlType(sql);
        }
      }

      // Measure
      long charTime = measureSingle(() -> SqlParser.parseSqlType(sql));
      long regexTime = measureSingle(() -> RegexSqlParser.parseSqlType(sql));
      long ssTime = ssAvailable ? measureSingle(() -> ShardingSphereSqlParser.parseSqlType(sql)) : -1;

      String displaySql = sql.length() > 42 ? sql.substring(0, 39) + "..." : sql;
      if (ssAvailable) {
        System.out.printf("%-45s %,15d %,15d %,15d%n", displaySql, charTime, regexTime, ssTime);
      } else {
        System.out.printf("%-45s %,15d %,15d %15s%n", displaySql, charTime, regexTime, "N/A");
      }
    }
  }

  private long measureSingle(Runnable task) {
    int iterations = 100000;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      task.run();
    }
    return (System.nanoTime() - start) / iterations;
  }
}
