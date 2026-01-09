/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;

/**
 * Oracle兼容模式的PreparedStatement实现
 * 继承PgPreparedStatement，后续可以override特定方法以实现Oracle特定行为
 */
public class OraclePreparedStatement extends PgPreparedStatement {

  public OraclePreparedStatement(PgConnection connection, String sql, int resultSetType,
                                 int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    super(connection, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
  }

  // 后续在这里添加Oracle特定的行为override
  // 例如：
  // - 参数绑定行为（setXXX方法）
  // - 日期时间类型处理（setDate, setTimestamp等）
  // - BLOB/CLOB处理
  // - 类型转换（Oracle vs PostgreSQL类型映射）
  // - 绑定变量的NULL值处理
}
