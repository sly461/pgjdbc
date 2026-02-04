/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating SQL parser strategy instances.
 * Provides thread-safe singleton instances for each parser type.
 */
public final class SqlParserFactory {

  private static final Logger LOGGER = Logger.getLogger(SqlParserFactory.class.getName());

  /**
   * Character-based parser singleton.
   */
  private static final SqlParserStrategy CHARACTER_PARSER = new CharacterSqlParserStrategy();

  /**
   * Regex-based parser singleton.
   */
  private static final SqlParserStrategy REGEX_PARSER = new RegexSqlParserStrategy();

  /**
   * ShardingSphere-based parser singleton (lazily initialized).
   */
  private static volatile SqlParserStrategy shardingSphereParser;

  /**
   * Default parser type.
   */
  private static volatile SqlParserType defaultType = SqlParserType.CHARACTER;

  /**
   * Private constructor to prevent instantiation.
   */
  private SqlParserFactory() {
  }

  /**
   * Sets the default parser type.
   *
   * @param type the parser type to use as default
   */
  public static void setDefaultType(SqlParserType type) {
    if (type != null) {
      defaultType = type;
      LOGGER.log(Level.FINE, "Default SQL parser type set to: " + type);
    }
  }

  /**
   * Gets the default parser type.
   *
   * @return the default parser type
   */
  public static SqlParserType getDefaultType() {
    return defaultType;
  }

  /**
   * Gets a parser strategy for the specified type.
   *
   * @param type the parser type
   * @return the parser strategy
   */
  public static SqlParserStrategy getParser(SqlParserType type) {
    if (type == null) {
      type = defaultType;
    }

    switch (type) {
      case REGEX:
        return REGEX_PARSER;
      case SHARDINGSPHERE:
        return getShardingSphereParser();
      case CHARACTER:
      default:
        return CHARACTER_PARSER;
    }
  }

  /**
   * Gets the default parser strategy.
   *
   * @return the default parser strategy
   */
  public static SqlParserStrategy getDefaultParser() {
    return getParser(defaultType);
  }

  /**
   * Gets the character-based parser.
   *
   * @return the character-based parser
   */
  public static SqlParserStrategy getCharacterParser() {
    return CHARACTER_PARSER;
  }

  /**
   * Gets the regex-based parser.
   *
   * @return the regex-based parser
   */
  public static SqlParserStrategy getRegexParser() {
    return REGEX_PARSER;
  }

  /**
   * Gets the ShardingSphere-based parser.
   * Falls back to character-based parser if ShardingSphere is not available.
   *
   * @return the ShardingSphere-based parser or fallback
   */
  public static SqlParserStrategy getShardingSphereParser() {
    if (shardingSphereParser == null) {
      synchronized (SqlParserFactory.class) {
        if (shardingSphereParser == null) {
          if (ShardingSphereSqlParser.isAvailable()) {
            shardingSphereParser = new ShardingSphereSqlParserStrategy();
            LOGGER.log(Level.FINE, "ShardingSphere SQL parser initialized");
          } else {
            // Fall back to character-based parser
            LOGGER.log(Level.WARNING,
                "ShardingSphere SQL parser not available, falling back to character-based parser");
            shardingSphereParser = CHARACTER_PARSER;
          }
        }
      }
    }
    return shardingSphereParser;
  }

  /**
   * Checks if ShardingSphere parser is available.
   *
   * @return true if ShardingSphere dependencies are present
   */
  public static boolean isShardingSphereAvailable() {
    return ShardingSphereSqlParser.isAvailable();
  }

  /**
   * Character-based SQL parser strategy implementation.
   */
  private static class CharacterSqlParserStrategy implements SqlParserStrategy {
    @Override
    public SqlType parseSqlType(String sql) {
      return SqlParser.parseSqlType(sql);
    }

    @Override
    public SqlParserType getType() {
      return SqlParserType.CHARACTER;
    }
  }

  /**
   * Regex-based SQL parser strategy implementation.
   */
  private static class RegexSqlParserStrategy implements SqlParserStrategy {
    @Override
    public SqlType parseSqlType(String sql) {
      return RegexSqlParser.parseSqlType(sql);
    }

    @Override
    public SqlParserType getType() {
      return SqlParserType.REGEX;
    }
  }

  /**
   * ShardingSphere-based SQL parser strategy implementation.
   */
  private static class ShardingSphereSqlParserStrategy implements SqlParserStrategy {
    @Override
    public SqlType parseSqlType(String sql) {
      return ShardingSphereSqlParser.parseSqlType(sql);
    }

    @Override
    public SqlParserType getType() {
      return SqlParserType.SHARDINGSPHERE;
    }

    @Override
    public boolean isAvailable() {
      return ShardingSphereSqlParser.isAvailable();
    }
  }
}
