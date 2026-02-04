/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQL parser implementation using Apache ShardingSphere SQL Parser.
 * This parser provides accurate SQL parsing based on full SQL grammar analysis.
 *
 * <p>ShardingSphere SQL Parser uses ANTLR4 to parse SQL statements and provides
 * rich semantic information about the parsed SQL.
 *
 * <p>Features:
 * <ul>
 *   <li>Full SQL grammar support for PostgreSQL dialect</li>
 *   <li>Accurate detection of SELECT FOR UPDATE/SHARE</li>
 *   <li>Built-in caching for performance</li>
 *   <li>Graceful fallback for unparseable SQL</li>
 * </ul>
 *
 * <p>Note: This parser requires ShardingSphere dependencies:
 * <ul>
 *   <li>shardingsphere-sql-parser-engine</li>
 *   <li>shardingsphere-sql-parser-postgresql</li>
 * </ul>
 */
public class ShardingSphereSqlParser {

  private static final Logger LOGGER = Logger.getLogger(ShardingSphereSqlParser.class.getName());

  /**
   * SQL parser engine (lazily initialized).
   */
  private static volatile Object parserEngine;

  /**
   * SQL visitor engine (lazily initialized).
   */
  private static volatile Object visitorEngine;

  /**
   * Parse method reference.
   */
  private static volatile Method parseMethod;

  /**
   * Visit method reference.
   */
  private static volatile Method visitMethod;

  /**
   * Flag indicating whether ShardingSphere is available.
   */
  private static volatile Boolean available;

  /**
   * Database type for PostgreSQL.
   */
  private static final String DATABASE_TYPE = "PostgreSQL";

  /**
   * Visitor type for statement parsing.
   */
  private static final String VISITOR_TYPE = "STATEMENT";

  /**
   * Private constructor to prevent instantiation.
   */
  private ShardingSphereSqlParser() {
  }

  /**
   * Checks if ShardingSphere SQL parser is available.
   *
   * @return true if ShardingSphere is available
   */
  public static boolean isAvailable() {
    if (available == null) {
      synchronized (ShardingSphereSqlParser.class) {
        if (available == null) {
          try {
            // Check for key ShardingSphere classes
            Class.forName("org.apache.shardingsphere.sql.parser.api.CacheOption");
            Class.forName("org.apache.shardingsphere.sql.parser.api.SQLParserEngine");
            Class.forName("org.apache.shardingsphere.sql.parser.api.SQLVisitorEngine");
            Class.forName("org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement");
            available = true;
            LOGGER.log(Level.FINE, "ShardingSphere SQL parser is available");
          } catch (ClassNotFoundException e) {
            available = false;
            LOGGER.log(Level.FINE, "ShardingSphere SQL parser is not available: " + e.getMessage());
          }
        }
      }
    }
    return available;
  }

  /**
   * Initializes the parser and visitor engines.
   * Uses reflection to avoid compile-time dependency.
   *
   * @throws Exception if initialization fails
   */
  private static void initializeEngines() throws Exception {
    if (parserEngine == null) {
      synchronized (ShardingSphereSqlParser.class) {
        if (parserEngine == null) {
          // Create CacheOption
          Class<?> cacheOptionClass = Class.forName("org.apache.shardingsphere.sql.parser.api.CacheOption");
          Object cacheOption = cacheOptionClass.getConstructor(int.class, long.class)
              .newInstance(128, 1024L);

          // Create SQLParserEngine
          Class<?> parserEngineClass = Class.forName("org.apache.shardingsphere.sql.parser.api.SQLParserEngine");
          parserEngine = parserEngineClass.getConstructor(String.class, cacheOptionClass)
              .newInstance(DATABASE_TYPE, cacheOption);

          // Get parse method
          parseMethod = parserEngineClass.getMethod("parse", String.class, boolean.class);

          // Create SQLVisitorEngine
          // Constructor: SQLVisitorEngine(String databaseType, String visitorType, boolean isParseComment, Properties props)
          Class<?> visitorEngineClass = Class.forName("org.apache.shardingsphere.sql.parser.api.SQLVisitorEngine");
          visitorEngine = visitorEngineClass.getConstructor(String.class, String.class, boolean.class, java.util.Properties.class)
              .newInstance(DATABASE_TYPE, VISITOR_TYPE, false, new java.util.Properties());

          // Get visit method - it takes ParseASTNode
          Class<?> parseASTNodeClass = Class.forName("org.apache.shardingsphere.sql.parser.core.ParseASTNode");
          visitMethod = visitorEngineClass.getMethod("visit", parseASTNodeClass);

          LOGGER.log(Level.FINE, "ShardingSphere engines initialized successfully");
        }
      }
    }
  }

  /**
   * Parses a SQL statement and returns its type.
   *
   * @param sql the SQL statement to parse
   * @return the SQL type, or UNKNOWN if parsing fails
   */
  public static SqlType parseSqlType(String sql) {
    if (sql == null || sql.trim().isEmpty()) {
      return SqlType.UNKNOWN;
    }

    // Check for master hint first (ShardingSphere doesn't recognize this custom hint)
    if (containsMasterHint(sql)) {
      return SqlType.MASTER_HINT;
    }

    // Check for JDBC escape call syntax (not supported by ShardingSphere)
    if (sql.trim().startsWith("{") && containsCallKeyword(sql)) {
      return SqlType.CALL;
    }

    if (!isAvailable()) {
      return fallbackParseSqlType(sql);
    }

    try {
      initializeEngines();

      // Parse SQL to get AST
      Object parseResult = parseMethod.invoke(parserEngine, sql, false);

      // Visit the AST to get SQLStatement
      Object sqlStatement = visitMethod.invoke(visitorEngine, parseResult);

      return mapStatementToSqlType(sqlStatement);
    } catch (Exception e) {
      // If ShardingSphere fails to parse, fall back to simple detection
      LOGGER.log(Level.FINE, "ShardingSphere parsing failed for SQL: " + sql, e);
      return fallbackParseSqlType(sql);
    }
  }

  /**
   * Maps a ShardingSphere SQLStatement to SqlType using reflection.
   *
   * @param statement the parsed statement
   * @return the SQL type
   */
  private static SqlType mapStatementToSqlType(Object statement) {
    if (statement == null) {
      return SqlType.UNKNOWN;
    }

    String className = statement.getClass().getName();

    // TCL (Transaction Control Language)
    if (className.contains("BeginTransaction")) {
      return SqlType.BEGIN;
    }
    if (className.contains("Commit")) {
      return SqlType.COMMIT;
    }
    if (className.contains("Rollback")) {
      return SqlType.ROLLBACK;
    }
    if (className.contains("Savepoint")) {
      return SqlType.SAVEPOINT;
    }
    if (className.contains("ReleaseSavepoint")) {
      return SqlType.RELEASE;
    }
    if (className.contains("tcl.")) {
      return SqlType.BEGIN;  // Generic TCL defaults to MASTER
    }

    // DML - SELECT
    if (className.contains("SelectStatement")) {
      // Check for lock using reflection
      try {
        Object lockOptional = statement.getClass().getMethod("getLock").invoke(statement);
        if (lockOptional != null) {
          Boolean isPresent = (Boolean) lockOptional.getClass().getMethod("isPresent").invoke(lockOptional);
          if (isPresent) {
            return SqlType.SELECT_FOR_UPDATE;
          }
        }
      } catch (Exception e) {
        // getLock method might not exist or have different signature
        LOGGER.log(Level.FINEST, "Could not check lock segment", e);
      }

      // Check for function call (no FROM clause)
      try {
        Object from = statement.getClass().getMethod("getFrom").invoke(statement);
        if (from == null) {
          return SqlType.SELECT_FUNCTION;
        }
      } catch (Exception e) {
        // getFrom method might not exist
        LOGGER.log(Level.FINEST, "Could not check from segment", e);
      }

      return SqlType.SELECT;
    }

    // DML - Write operations
    if (className.contains("InsertStatement")) {
      return SqlType.INSERT;
    }
    if (className.contains("UpdateStatement")) {
      return SqlType.UPDATE;
    }
    if (className.contains("DeleteStatement")) {
      return SqlType.DELETE;
    }
    if (className.contains("MergeStatement")) {
      return SqlType.MERGE;
    }
    if (className.contains("CallStatement")) {
      return SqlType.CALL;
    }
    if (className.contains("CopyStatement")) {
      return SqlType.COPY;
    }
    if (className.contains("DoStatement")) {
      return SqlType.CALL;  // DO block treated as procedure call
    }

    // DDL
    if (className.contains("CreateStatement") || className.contains("Create")) {
      return SqlType.CREATE;
    }
    if (className.contains("AlterStatement") || className.contains("Alter")) {
      return SqlType.ALTER;
    }
    if (className.contains("DropStatement") || className.contains("Drop")) {
      return SqlType.DROP;
    }
    if (className.contains("TruncateStatement")) {
      return SqlType.TRUNCATE;
    }
    if (className.contains("RenameTable")) {
      return SqlType.RENAME;
    }
    if (className.contains("ddl.")) {
      return SqlType.CREATE;  // Generic DDL defaults to MASTER
    }

    // DCL
    if (className.contains("GrantStatement")) {
      return SqlType.GRANT;
    }
    if (className.contains("RevokeStatement")) {
      return SqlType.REVOKE;
    }
    if (className.contains("dcl.")) {
      return SqlType.GRANT;  // Generic DCL defaults to MASTER
    }

    // DAL
    if (className.contains("ShowStatement") || className.contains("Show")) {
      return SqlType.SHOW;
    }
    if (className.contains("ExplainStatement")) {
      return SqlType.EXPLAIN;
    }
    if (className.contains("SetStatement")) {
      return SqlType.SET;
    }
    if (className.contains("dal.")) {
      return SqlType.SHOW;  // Generic DAL defaults to REPLICA
    }

    return SqlType.UNKNOWN;
  }

  /**
   * Checks if SQL contains master hint comment.
   *
   * @param sql the SQL string
   * @return true if master hint is present
   */
  private static boolean containsMasterHint(String sql) {
    String trimmed = sql.trim();
    if (!trimmed.startsWith("/*")) {
      return false;
    }

    int endComment = trimmed.indexOf("*/");
    if (endComment < 0) {
      return false;
    }

    String comment = trimmed.substring(2, endComment).trim();
    if (comment.startsWith("+")) {
      comment = comment.substring(1).trim();
    }
    return "master".equalsIgnoreCase(comment);
  }

  /**
   * Checks if SQL contains CALL keyword (for JDBC escape syntax).
   *
   * @param sql the SQL string
   * @return true if CALL keyword is present
   */
  private static boolean containsCallKeyword(String sql) {
    String lower = sql.toLowerCase();
    return lower.contains("call");
  }

  /**
   * Fallback parsing when ShardingSphere is not available or fails.
   * Uses simple keyword detection.
   *
   * @param sql the SQL string
   * @return the SQL type
   */
  private static SqlType fallbackParseSqlType(String sql) {
    String trimmed = sql.trim().toUpperCase();

    // Remove leading comments
    while (trimmed.startsWith("/*")) {
      int endComment = trimmed.indexOf("*/");
      if (endComment < 0) {
        break;
      }
      trimmed = trimmed.substring(endComment + 2).trim();
    }

    // Check for DO block (anonymous PL/pgSQL block)
    if (trimmed.startsWith("DO ") || trimmed.startsWith("DO$")) {
      return SqlType.CALL;
    }

    if (trimmed.startsWith("BEGIN")) {
      return SqlType.BEGIN;
    }
    if (trimmed.startsWith("START TRANSACTION")) {
      return SqlType.START_TRANSACTION;
    }
    if (trimmed.startsWith("COMMIT")) {
      return SqlType.COMMIT;
    }
    if (trimmed.startsWith("ROLLBACK")) {
      return SqlType.ROLLBACK;
    }
    if (trimmed.startsWith("WITH")) {
      // CTE query - check for FOR UPDATE
      if (trimmed.contains("FOR UPDATE") || trimmed.contains("FOR SHARE")) {
        return SqlType.SELECT_FOR_UPDATE;
      }
      return SqlType.SELECT;
    }
    if (trimmed.startsWith("SELECT")) {
      if (trimmed.contains("FOR UPDATE") || trimmed.contains("FOR SHARE")) {
        return SqlType.SELECT_FOR_UPDATE;
      }
      // Simple function detection
      if (!trimmed.contains("FROM") || trimmed.matches(".*SELECT\\s+[A-Z_]+\\s*\\(.*")) {
        return SqlType.SELECT_FUNCTION;
      }
      return SqlType.SELECT;
    }
    if (trimmed.startsWith("INSERT")) {
      return SqlType.INSERT;
    }
    if (trimmed.startsWith("UPDATE")) {
      return SqlType.UPDATE;
    }
    if (trimmed.startsWith("DELETE")) {
      return SqlType.DELETE;
    }
    if (trimmed.startsWith("CREATE")) {
      return SqlType.CREATE;
    }
    if (trimmed.startsWith("ALTER")) {
      return SqlType.ALTER;
    }
    if (trimmed.startsWith("DROP")) {
      return SqlType.DROP;
    }
    if (trimmed.startsWith("TRUNCATE")) {
      return SqlType.TRUNCATE;
    }
    if (trimmed.startsWith("SHOW")) {
      return SqlType.SHOW;
    }
    if (trimmed.startsWith("EXPLAIN")) {
      return SqlType.EXPLAIN;
    }
    if (trimmed.startsWith("CALL")) {
      return SqlType.CALL;
    }

    return SqlType.UNKNOWN;
  }
}
