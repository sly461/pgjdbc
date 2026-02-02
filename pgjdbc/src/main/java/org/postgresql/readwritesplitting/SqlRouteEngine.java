/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.readwritesplitting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQL routing engine that determines whether a SQL statement should be routed to
 * the master or replica server based on SQL type, transaction state, and explicit routing hints.
 * Routing rules (in priority order):
 * <ol>
 *   <li>Explicit targetServerType=master -&gt; MASTER</li>
 *   <li>Explicit targetServerType=secondary -&gt; REPLICA</li>
 *   <li>Explicit transaction block (BEGIN...END) -&gt; MASTER</li>
 *   <li>Transaction mode (autoCommit=false) -&gt; MASTER</li>
 *   <li>SQL parsing:
 *     <ul>
 *       <li>Master hint comment -&gt; MASTER</li>
 *       <li>INSERT/UPDATE/DELETE/DDL -&gt; MASTER</li>
 *       <li>SELECT FOR UPDATE/FOR SHARE -&gt; MASTER</li>
 *       <li>Function/procedure calls -&gt; MASTER</li>
 *       <li>Plain SELECT/SHOW/EXPLAIN -&gt; REPLICA (with load balancing)</li>
 *     </ul>
 *   </li>
 *   <li>Parse failure -&gt; MASTER (fail-safe)</li>
 * </ol>
 */
public class SqlRouteEngine {

  private static final Logger LOGGER = Logger.getLogger(SqlRouteEngine.class.getName());

  /**
   * LRU cache for parsed SQL statements (256 entries).
   */
  private static final int CACHE_SIZE = 256;
  private final Map<String, SqlType> routeCache = new LinkedHashMap<String, SqlType>(CACHE_SIZE, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, SqlType> eldest) {
      return size() > CACHE_SIZE;
    }
  };

  /**
   * Explicit transaction block state (BEGIN...END).
   * This is separate from autoCommit mode.
   */
  private boolean inExplicitTransaction = false;

  /**
   * Creates a new SQL route engine.
   */
  public SqlRouteEngine() {
    // No initialization needed
  }

  /**
   * Determines the route target for a SQL statement.
   *
   * @param sql the SQL statement to route
   * @param autoCommit whether auto-commit is enabled
   * @param targetServerType explicit target server type (master/secondary/null)
   * @return the route target (MASTER or REPLICA)
   */
  public RouteTarget route(String sql, boolean autoCommit, String targetServerType) {
    // Parse SQL type first to detect transaction control
    SqlType sqlType = parseSqlType(sql);

    // Handle transaction control statements
    if (sqlType == SqlType.BEGIN || sqlType == SqlType.START_TRANSACTION) {
      inExplicitTransaction = true;
      return RouteTarget.MASTER;
    }

    if (sqlType == SqlType.COMMIT || sqlType == SqlType.ROLLBACK) {
      inExplicitTransaction = false;
      return RouteTarget.MASTER;
    }

    // Rule 1: Explicit targetServerType=master -> MASTER (highest priority)
    if ("master".equalsIgnoreCase(targetServerType) || "primary".equalsIgnoreCase(targetServerType)) {
      return RouteTarget.MASTER;
    }

    // Rule 2: Explicit targetServerType=secondary -> REPLICA (overrides transaction state)
    if ("secondary".equalsIgnoreCase(targetServerType) || "replica".equalsIgnoreCase(targetServerType)
        || "slave".equalsIgnoreCase(targetServerType)) {
      return RouteTarget.REPLICA;
    }

    // Rule 3: Explicit transaction block or autoCommit=false -> MASTER
    if (inExplicitTransaction || !autoCommit) {
      return RouteTarget.MASTER;
    }

    // Rule 4: SQL type-based routing
    return sqlType.toRouteTarget();
  }

  /**
   * Routes SQL based on its type by parsing the statement.
   *
   * @param sql the SQL statement to parse and route
   * @return the SQL type
   */
  private SqlType parseSqlType(String sql) {
    // Check cache first
    SqlType cached = routeCache.get(sql);
    if (cached != null) {
      return cached;
    }

    try {
      SqlType sqlType = SqlParser.parseSqlType(sql);

      // Cache the result
      routeCache.put(sql, sqlType);

      return sqlType;
    } catch (Exception e) {
      // Parse failure → MASTER (fail-safe)
      LOGGER.log(Level.WARNING, "Failed to parse SQL, routing to MASTER: " + sql, e);
      SqlType sqlType = SqlType.UNKNOWN;
      routeCache.put(sql, sqlType);
      return sqlType;
    }
  }

  /**
   * Resets the transaction state. Should be called when connection is reset or reused.
   */
  public void resetTransactionState() {
    inExplicitTransaction = false;
  }

  /**
   * Clears the route cache. Useful for testing.
   */
  public void clearCache() {
    routeCache.clear();
  }

  /**
   * Gets the current cache size. Useful for testing.
   *
   * @return the number of cached routes
   */
  public int getCacheSize() {
    return routeCache.size();
  }
}
