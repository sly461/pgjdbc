/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

/**
 * High-performance SQL parser using pure character comparison instead of regex.
 * Design goals:
 * <ul>
 *   <li>Zero-GC: No String.toLowerCase(), trim(), substring(), or regex</li>
 *   <li>Fast path: O(1) first-character dispatch</li>
 *   <li>Lazy evaluation: Only check FOR UPDATE when needed</li>
 *   <li>Backward scanning: FOR UPDATE detection from end is faster</li>
 * </ul>
 */
public class SqlParser {

  /**
   * Checks if SQL parsing is available.
   * This method is kept for backward compatibility.
   * Character-based parsing is always available with no external dependencies.
   */
  public static void checkAvailable() {
    // Character-based parsing is always available, no dependencies required
  }

  /**
   * Parses a SQL statement and returns its type.
   *
   * @param sql the SQL statement to parse
   * @return the SQL type, or UNKNOWN if parsing fails
   */
  public static SqlType parseSqlType(String sql) {
    if (sql == null || sql.isEmpty()) {
      return SqlType.UNKNOWN;
    }

    int pos = 0;
    int len = sql.length();

    // Skip leading whitespace and check for master hint
    boolean hasMasterHint = false;
    while (pos < len) {
      char ch = sql.charAt(pos);

      if (Character.isWhitespace(ch)) {
        pos++;
        continue;
      }

      // Check for comments
      if (ch == '/' && pos + 1 < len && sql.charAt(pos + 1) == '*') {
        int commentEnd = sql.indexOf("*/", pos + 2);
        if (commentEnd < 0) {
          return SqlType.UNKNOWN;  // Unclosed comment
        }

        // Check for master hint
        String comment = sql.substring(pos + 2, commentEnd).trim();
        if (comment.startsWith("+")) {
          comment = comment.substring(1).trim();
        }
        if (comment.equalsIgnoreCase("master")) {
          hasMasterHint = true;
        }

        pos = commentEnd + 2;
        continue;
      }

      // Check for line comments
      if (ch == '-' && pos + 1 < len && sql.charAt(pos + 1) == '-') {
        // Skip to end of line
        while (pos < len && sql.charAt(pos) != '\n') {
          pos++;
        }
        continue;
      }

      // Found first non-whitespace, non-comment character
      break;
    }

    if (hasMasterHint) {
      return SqlType.MASTER_HINT;
    }

    if (pos >= len) {
      return SqlType.UNKNOWN;  // Only whitespace/comments
    }

    // Fast path: dispatch based on first character
    char firstChar = Character.toLowerCase(sql.charAt(pos));

    switch (firstChar) {
      case 's':
        return parseS(sql, pos, len);
      case 'i':
        return parseI(sql, pos, len);
      case 'u':
        return parseU(sql, pos, len);
      case 'd':
        return parseD(sql, pos, len);
      case 'r':
        return parseR(sql, pos, len);
      case 'm':
        return parseM(sql, pos, len);
      case 't':
        return parseT(sql, pos, len);
      case 'c':
        return parseC(sql, pos, len);
      case 'a':
        return parseA(sql, pos, len);
      case 'g':
        return parseG(sql, pos, len);
      case 'b':
        return parseB(sql, pos, len);
      case 'e':
        return parseE(sql, pos, len);
      case 'v':
        return parseV(sql, pos, len);
      case '{':
        return parseJdbcEscape(sql, pos, len);
      default:
        return SqlType.UNKNOWN;
    }
  }

  // Parse keywords starting with 'S'
  private static SqlType parseS(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "SELECT")) {
      return parseSelect(sql, pos + 6, len);
    }
    if (matchKeyword(sql, pos, len, "SHOW")) {
      return SqlType.SHOW;
    }
    if (matchKeyword(sql, pos, len, "SET")) {
      return SqlType.SET;
    }
    if (matchKeyword(sql, pos, len, "SAVEPOINT")) {
      return SqlType.SAVEPOINT;
    }
    if (matchKeyword(sql, pos, len, "START")) {
      // Check for START TRANSACTION
      int nextPos = skipWhitespace(sql, pos + 5, len);
      if (matchKeyword(sql, nextPos, len, "TRANSACTION")) {
        return SqlType.START_TRANSACTION;
      }
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'I'
  private static SqlType parseI(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "INSERT")) {
      return SqlType.INSERT;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'U'
  private static SqlType parseU(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "UPDATE")) {
      return SqlType.UPDATE;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'D'
  private static SqlType parseD(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "DELETE")) {
      return SqlType.DELETE;
    }
    if (matchKeyword(sql, pos, len, "DROP")) {
      return SqlType.DROP;
    }
    if (matchKeyword(sql, pos, len, "DESC")) {
      return SqlType.DESC;
    }
    if (matchKeyword(sql, pos, len, "DESCRIBE")) {
      return SqlType.DESCRIBE;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'R'
  private static SqlType parseR(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "REPLACE")) {
      return SqlType.REPLACE;
    }
    if (matchKeyword(sql, pos, len, "REVOKE")) {
      return SqlType.REVOKE;
    }
    if (matchKeyword(sql, pos, len, "ROLLBACK")) {
      return SqlType.ROLLBACK;
    }
    if (matchKeyword(sql, pos, len, "RELEASE")) {
      return SqlType.RELEASE;
    }
    if (matchKeyword(sql, pos, len, "RENAME")) {
      return SqlType.RENAME;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'M'
  private static SqlType parseM(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "MERGE")) {
      return SqlType.MERGE;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'T'
  private static SqlType parseT(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "TRUNCATE")) {
      return SqlType.TRUNCATE;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'C'
  private static SqlType parseC(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "CREATE")) {
      return SqlType.CREATE;
    }
    if (matchKeyword(sql, pos, len, "COMMIT")) {
      return SqlType.COMMIT;
    }
    if (matchKeyword(sql, pos, len, "CALL")) {
      return SqlType.CALL;
    }
    if (matchKeyword(sql, pos, len, "COPY")) {
      return SqlType.COPY;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'A'
  private static SqlType parseA(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "ALTER")) {
      return SqlType.ALTER;
    }
    if (matchKeyword(sql, pos, len, "ANALYZE")) {
      return SqlType.ANALYZE;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'G'
  private static SqlType parseG(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "GRANT")) {
      return SqlType.GRANT;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'B'
  private static SqlType parseB(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "BEGIN")) {
      return SqlType.BEGIN;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'E'
  private static SqlType parseE(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "EXPLAIN")) {
      return SqlType.EXPLAIN;
    }
    return SqlType.UNKNOWN;
  }

  // Parse keywords starting with 'V'
  private static SqlType parseV(String sql, int pos, int len) {
    if (matchKeyword(sql, pos, len, "VACUUM")) {
      return SqlType.VACUUM;
    }
    return SqlType.UNKNOWN;
  }

  // Parse SELECT statement (most complex)
  private static SqlType parseSelect(String sql, int pos, int len) {
    // Check for FOR UPDATE or FOR SHARE (backward scan is faster)
    if (containsForUpdateOrShare(sql, pos, len)) {
      return SqlType.SELECT_FOR_UPDATE;
    }

    // Check for function call
    if (isFunctionCall(sql, pos, len)) {
      return SqlType.SELECT_FUNCTION;
    }

    // Plain SELECT
    return SqlType.SELECT;
  }

  // Check if SELECT contains FOR UPDATE or FOR SHARE
  private static boolean containsForUpdateOrShare(String sql, int pos, int len) {
    // Backward scan from end is faster for FOR UPDATE/SHARE
    int i = len - 1;

    // Skip trailing whitespace
    while (i >= pos && Character.isWhitespace(sql.charAt(i))) {
      i--;
    }

    // Look for "UPDATE" or "SHARE" at the end
    if (i - 5 >= pos) {
      if (matchKeywordAt(sql, i - 5, "UPDATE")) {
        // Check for "FOR" before "UPDATE"
        int forPos = i - 5 - 1;
        while (forPos >= pos && Character.isWhitespace(sql.charAt(forPos))) {
          forPos--;
        }
        if (forPos - 2 >= pos && matchKeywordAt(sql, forPos - 2, "FOR")) {
          return true;
        }
      }
    }

    if (i - 4 >= pos) {
      if (matchKeywordAt(sql, i - 4, "SHARE")) {
        // Check for "FOR" before "SHARE"
        int forPos = i - 4 - 1;
        while (forPos >= pos && Character.isWhitespace(sql.charAt(forPos))) {
          forPos--;
        }
        if (forPos - 2 >= pos && matchKeywordAt(sql, forPos - 2, "FOR")) {
          return true;
        }
      }
    }

    return false;
  }

  // Check if SELECT is a function call
  private static boolean isFunctionCall(String sql, int pos, int len) {
    // Skip whitespace after SELECT
    pos = skipWhitespace(sql, pos, len);

    // Pattern 1: SELECT func() - no FROM clause
    // Pattern 2: SELECT * FROM func()
    // Look for opening parenthesis before FROM or end of statement

    boolean hasFrom = false;
    int parenPos = -1;

    for (int i = pos; i < len; i++) {
      char ch = sql.charAt(i);

      if (ch == '(') {
        parenPos = i;
        break;
      }

      if (ch == 'F' || ch == 'f') {
        if (matchKeyword(sql, i, len, "FROM")) {
          hasFrom = true;
          pos = skipWhitespace(sql, i + 4, len);
          // Continue looking for parenthesis after FROM
          for (int j = pos; j < len; j++) {
            if (sql.charAt(j) == '(') {
              parenPos = j;
              break;
            }
          }
          break;
        }
      }
    }

    // If we found parenthesis, it's likely a function call
    return parenPos > 0;
  }

  // Parse JDBC escape syntax: {call proc()} or {? = call func()}
  private static SqlType parseJdbcEscape(String sql, int pos, int len) {
    pos = skipWhitespace(sql, pos + 1, len);

    // Check for {? = call ...}
    if (pos < len && sql.charAt(pos) == '?') {
      pos = skipWhitespace(sql, pos + 1, len);
      if (pos < len && sql.charAt(pos) == '=') {
        pos = skipWhitespace(sql, pos + 1, len);
      }
    }

    // Check for "call"
    if (matchKeyword(sql, pos, len, "CALL")) {
      return SqlType.CALL;
    }

    return SqlType.UNKNOWN;
  }

  // Match a keyword at the current position
  private static boolean matchKeyword(String sql, int pos, int len, String keyword) {
    int keywordLen = keyword.length();
    if (pos + keywordLen > len) {
      return false;
    }

    // Match each character (case-insensitive)
    for (int i = 0; i < keywordLen; i++) {
      char sqlChar = Character.toLowerCase(sql.charAt(pos + i));
      char keywordChar = Character.toLowerCase(keyword.charAt(i));
      if (sqlChar != keywordChar) {
        return false;
      }
    }

    // Ensure keyword is followed by whitespace or end of string
    if (pos + keywordLen < len) {
      char nextChar = sql.charAt(pos + keywordLen);
      if (Character.isLetterOrDigit(nextChar) || nextChar == '_') {
        return false;  // Part of a longer identifier
      }
    }

    return true;
  }

  // Match a keyword at a specific position (for backward scan)
  private static boolean matchKeywordAt(String sql, int pos, String keyword) {
    int keywordLen = keyword.length();
    if (pos < 0 || pos + keywordLen > sql.length()) {
      return false;
    }

    for (int i = 0; i < keywordLen; i++) {
      char sqlChar = Character.toLowerCase(sql.charAt(pos + i));
      char keywordChar = Character.toLowerCase(keyword.charAt(i));
      if (sqlChar != keywordChar) {
        return false;
      }
    }

    return true;
  }

  // Skip whitespace and return next non-whitespace position
  private static int skipWhitespace(String sql, int pos, int len) {
    while (pos < len && Character.isWhitespace(sql.charAt(pos))) {
      pos++;
    }
    return pos;
  }
}
