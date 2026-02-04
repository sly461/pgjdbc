/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL parser implementation using regular expressions.
 * This parser uses pre-compiled regex patterns to determine SQL statement types.
 *
 * <p>Design considerations:
 * <ul>
 *   <li>Pre-compiled patterns for performance</li>
 *   <li>Case-insensitive matching</li>
 *   <li>Support for SQL comments and hints</li>
 *   <li>Handle edge cases like SELECT FOR UPDATE</li>
 * </ul>
 */
public class RegexSqlParser {

  // Pattern flags for case-insensitive and multiline matching
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

  // Master hint patterns: /*master*/, /* master */, /*+master*/
  private static final Pattern MASTER_HINT_PATTERN =
      Pattern.compile("^\\s*/\\*\\+?\\s*master\\s*\\*/", FLAGS);

  // Transaction control patterns
  private static final Pattern BEGIN_PATTERN =
      Pattern.compile("^\\s*BEGIN\\b", FLAGS);
  private static final Pattern START_TRANSACTION_PATTERN =
      Pattern.compile("^\\s*START\\s+TRANSACTION\\b", FLAGS);
  private static final Pattern COMMIT_PATTERN =
      Pattern.compile("^\\s*COMMIT\\b", FLAGS);
  private static final Pattern ROLLBACK_PATTERN =
      Pattern.compile("^\\s*ROLLBACK\\b", FLAGS);
  private static final Pattern SAVEPOINT_PATTERN =
      Pattern.compile("^\\s*SAVEPOINT\\b", FLAGS);
  private static final Pattern RELEASE_PATTERN =
      Pattern.compile("^\\s*RELEASE\\b", FLAGS);

  // DML patterns
  private static final Pattern SELECT_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*SELECT\\b", FLAGS);
  private static final Pattern INSERT_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*INSERT\\b", FLAGS);
  private static final Pattern UPDATE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*UPDATE\\b", FLAGS);
  private static final Pattern DELETE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*DELETE\\b", FLAGS);
  private static final Pattern REPLACE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*REPLACE\\b", FLAGS);
  private static final Pattern MERGE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*MERGE\\b", FLAGS);

  // DDL patterns
  private static final Pattern CREATE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*CREATE\\b", FLAGS);
  private static final Pattern ALTER_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*ALTER\\b", FLAGS);
  private static final Pattern DROP_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*DROP\\b", FLAGS);
  private static final Pattern TRUNCATE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*TRUNCATE\\b", FLAGS);
  private static final Pattern RENAME_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*RENAME\\b", FLAGS);

  // DCL patterns
  private static final Pattern GRANT_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*GRANT\\b", FLAGS);
  private static final Pattern REVOKE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*REVOKE\\b", FLAGS);

  // Utility patterns
  private static final Pattern SHOW_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*SHOW\\b", FLAGS);
  private static final Pattern EXPLAIN_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*EXPLAIN\\b", FLAGS);
  private static final Pattern DESCRIBE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*DESCRIBE\\b", FLAGS);
  private static final Pattern DESC_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*DESC\\b", FLAGS);
  private static final Pattern SET_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*SET\\b", FLAGS);

  // Maintenance patterns
  private static final Pattern VACUUM_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*VACUUM\\b", FLAGS);
  private static final Pattern ANALYZE_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*ANALYZE\\b", FLAGS);
  private static final Pattern COPY_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*COPY\\b", FLAGS);

  // Procedure call patterns
  private static final Pattern CALL_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*CALL\\b", FLAGS);
  private static final Pattern JDBC_CALL_PATTERN =
      Pattern.compile("^\\s*\\{\\s*(?:\\?\\s*=\\s*)?call\\b", FLAGS);

  // DO block pattern (anonymous code block)
  private static final Pattern DO_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*DO\\b", FLAGS);

  // SELECT variants patterns
  private static final Pattern FOR_UPDATE_PATTERN =
      Pattern.compile("\\bFOR\\s+UPDATE\\b", FLAGS);
  private static final Pattern FOR_SHARE_PATTERN =
      Pattern.compile("\\bFOR\\s+SHARE\\b", FLAGS);

  // Function call detection in SELECT: SELECT func() or SELECT * FROM func()
  private static final Pattern SELECT_FUNCTION_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*SELECT\\s+[^(]*\\([^)]*\\)", FLAGS);

  // WITH (CTE) pattern
  private static final Pattern WITH_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*WITH\\b", FLAGS);

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

    // Check for master hint first (highest priority)
    if (MASTER_HINT_PATTERN.matcher(sql).find()) {
      return SqlType.MASTER_HINT;
    }

    // Transaction control
    if (BEGIN_PATTERN.matcher(sql).find()) {
      return SqlType.BEGIN;
    }
    if (START_TRANSACTION_PATTERN.matcher(sql).find()) {
      return SqlType.START_TRANSACTION;
    }
    if (COMMIT_PATTERN.matcher(sql).find()) {
      return SqlType.COMMIT;
    }
    if (ROLLBACK_PATTERN.matcher(sql).find()) {
      return SqlType.ROLLBACK;
    }
    if (SAVEPOINT_PATTERN.matcher(sql).find()) {
      return SqlType.SAVEPOINT;
    }
    if (RELEASE_PATTERN.matcher(sql).find()) {
      return SqlType.RELEASE;
    }

    // DO block (anonymous code block) - routes to MASTER
    if (DO_PATTERN.matcher(sql).find()) {
      return SqlType.CALL;  // Treat DO blocks like procedure calls
    }

    // CALL / JDBC escape call
    if (CALL_PATTERN.matcher(sql).find()) {
      return SqlType.CALL;
    }
    if (JDBC_CALL_PATTERN.matcher(sql).find()) {
      return SqlType.CALL;
    }

    // SELECT needs special handling for FOR UPDATE/SHARE and function calls
    if (SELECT_PATTERN.matcher(sql).find()) {
      // Check FOR UPDATE
      if (FOR_UPDATE_PATTERN.matcher(sql).find()) {
        return SqlType.SELECT_FOR_UPDATE;
      }
      // Check FOR SHARE
      if (FOR_SHARE_PATTERN.matcher(sql).find()) {
        return SqlType.SELECT_FOR_SHARE;
      }
      // Check for function calls in SELECT
      if (containsFunctionCall(sql)) {
        return SqlType.SELECT_FUNCTION;
      }
      return SqlType.SELECT;
    }

    // WITH (CTE) - Common Table Expression, usually a SELECT query
    if (WITH_PATTERN.matcher(sql).find()) {
      // Check FOR UPDATE/SHARE in CTE queries
      if (FOR_UPDATE_PATTERN.matcher(sql).find()) {
        return SqlType.SELECT_FOR_UPDATE;
      }
      if (FOR_SHARE_PATTERN.matcher(sql).find()) {
        return SqlType.SELECT_FOR_SHARE;
      }
      return SqlType.SELECT;
    }

    // DML operations (write)
    if (INSERT_PATTERN.matcher(sql).find()) {
      return SqlType.INSERT;
    }
    if (UPDATE_PATTERN.matcher(sql).find()) {
      return SqlType.UPDATE;
    }
    if (DELETE_PATTERN.matcher(sql).find()) {
      return SqlType.DELETE;
    }
    if (REPLACE_PATTERN.matcher(sql).find()) {
      return SqlType.REPLACE;
    }
    if (MERGE_PATTERN.matcher(sql).find()) {
      return SqlType.MERGE;
    }

    // DDL operations
    if (CREATE_PATTERN.matcher(sql).find()) {
      return SqlType.CREATE;
    }
    if (ALTER_PATTERN.matcher(sql).find()) {
      return SqlType.ALTER;
    }
    if (DROP_PATTERN.matcher(sql).find()) {
      return SqlType.DROP;
    }
    if (TRUNCATE_PATTERN.matcher(sql).find()) {
      return SqlType.TRUNCATE;
    }
    if (RENAME_PATTERN.matcher(sql).find()) {
      return SqlType.RENAME;
    }

    // DCL operations
    if (GRANT_PATTERN.matcher(sql).find()) {
      return SqlType.GRANT;
    }
    if (REVOKE_PATTERN.matcher(sql).find()) {
      return SqlType.REVOKE;
    }

    // Utility operations (read)
    if (SHOW_PATTERN.matcher(sql).find()) {
      return SqlType.SHOW;
    }
    if (EXPLAIN_PATTERN.matcher(sql).find()) {
      return SqlType.EXPLAIN;
    }
    if (DESCRIBE_PATTERN.matcher(sql).find()) {
      return SqlType.DESCRIBE;
    }
    if (DESC_PATTERN.matcher(sql).find()) {
      return SqlType.DESC;
    }

    // SET operations (write - affects session state)
    if (SET_PATTERN.matcher(sql).find()) {
      return SqlType.SET;
    }

    // Maintenance operations
    if (VACUUM_PATTERN.matcher(sql).find()) {
      return SqlType.VACUUM;
    }
    if (ANALYZE_PATTERN.matcher(sql).find()) {
      return SqlType.ANALYZE;
    }
    if (COPY_PATTERN.matcher(sql).find()) {
      return SqlType.COPY;
    }

    return SqlType.UNKNOWN;
  }

  /**
   * Checks if a SELECT statement contains a function call.
   * Function calls are detected by looking for patterns like:
   * - SELECT func()
   * - SELECT * FROM func()
   * - SELECT generate_series(1, 10)
   *
   * @param sql the SQL statement
   * @return true if a function call is detected
   */
  private static boolean containsFunctionCall(String sql) {
    // Remove string literals to avoid false positives
    String cleanedSql = removeStringLiterals(sql);

    // Simple heuristic: look for identifier followed by ()
    // This catches most common cases like now(), generate_series(), etc.
    return SELECT_FUNCTION_PATTERN.matcher(cleanedSql).find();
  }

  /**
   * Removes string literals from SQL to prevent false matches.
   * Replaces 'string' with empty string.
   *
   * @param sql the SQL statement
   * @return SQL with string literals removed
   */
  private static String removeStringLiterals(String sql) {
    // Remove single-quoted strings (PostgreSQL standard)
    // Handle escaped quotes ''
    StringBuilder result = new StringBuilder();
    boolean inString = false;
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\'' && !inString) {
        inString = true;
        i++;
        continue;
      }
      if (c == '\'' && inString) {
        // Check for escaped quote ''
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
          i += 2;
          continue;
        }
        inString = false;
        i++;
        continue;
      }
      if (!inString) {
        result.append(c);
      }
      i++;
    }
    return result.toString();
  }
}
