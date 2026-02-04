/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

/**
 * Enumeration of SQL parser types for read-write splitting.
 *
 * <p>Each parser type has different characteristics:
 *
 * <table border="1">
 *   <tr><th>Type</th><th>Speed</th><th>Accuracy</th><th>Dependencies</th></tr>
 *   <tr><td>CHARACTER</td><td>Fastest</td><td>Good</td><td>None</td></tr>
 *   <tr><td>REGEX</td><td>Fast</td><td>Good</td><td>None</td></tr>
 *   <tr><td>SHARDINGSPHERE</td><td>Moderate</td><td>Excellent</td><td>ShardingSphere</td></tr>
 * </table>
 */
public enum SqlParserType {

  /**
   * Character-based parsing using direct character comparison.
   * <ul>
   *   <li>Fastest implementation</li>
   *   <li>Zero GC (no regex, no substring)</li>
   *   <li>No external dependencies</li>
   *   <li>Good accuracy for common SQL patterns</li>
   * </ul>
   */
  CHARACTER("character"),

  /**
   * Regular expression-based parsing.
   * <ul>
   *   <li>Pre-compiled regex patterns for performance</li>
   *   <li>No external dependencies</li>
   *   <li>Good accuracy for common SQL patterns</li>
   *   <li>Easier to maintain and extend</li>
   * </ul>
   */
  REGEX("regex"),

  /**
   * ShardingSphere SQL parser using ANTLR4.
   * <ul>
   *   <li>Full SQL grammar analysis</li>
   *   <li>Most accurate detection</li>
   *   <li>Requires ShardingSphere dependencies</li>
   *   <li>Built-in caching for performance</li>
   * </ul>
   */
  SHARDINGSPHERE("shardingsphere");

  private final String value;

  SqlParserType(String value) {
    this.value = value;
  }

  /**
   * Gets the string value of this parser type.
   *
   * @return the string value
   */
  public String getValue() {
    return value;
  }

  /**
   * Parses a string value to SqlParserType.
   *
   * @param value the string value
   * @return the parser type, defaults to CHARACTER if not recognized
   */
  public static SqlParserType fromValue(String value) {
    if (value == null || value.isEmpty()) {
      return CHARACTER;
    }
    for (SqlParserType type : values()) {
      if (type.value.equalsIgnoreCase(value)) {
        return type;
      }
    }
    return CHARACTER;
  }
}
