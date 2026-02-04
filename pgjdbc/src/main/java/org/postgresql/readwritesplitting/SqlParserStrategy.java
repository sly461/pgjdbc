/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

/**
 * Strategy interface for SQL parsing in read-write splitting.
 * Allows pluggable SQL parsing implementations with different trade-offs.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link SqlParserType#CHARACTER} - Character-based parsing (default, fastest)</li>
 *   <li>{@link SqlParserType#REGEX} - Regular expression-based parsing</li>
 *   <li>{@link SqlParserType#SHARDINGSPHERE} - ShardingSphere SQL parser (most accurate)</li>
 * </ul>
 */
public interface SqlParserStrategy {

  /**
   * Parses a SQL statement and returns its type.
   *
   * @param sql the SQL statement to parse
   * @return the SQL type
   */
  SqlType parseSqlType(String sql);

  /**
   * Gets the parser type.
   *
   * @return the parser type
   */
  SqlParserType getType();

  /**
   * Checks if this parser is available (e.g., dependencies are present).
   *
   * @return true if the parser is available
   */
  default boolean isAvailable() {
    return true;
  }
}
