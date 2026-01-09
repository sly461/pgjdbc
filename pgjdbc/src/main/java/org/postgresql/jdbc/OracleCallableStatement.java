/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;

/**
 * Oracle兼容模式的CallableStatement实现
 * 继承PgCallableStatement，后续可以override特定方法以实现Oracle特定行为
 */
public class OracleCallableStatement extends PgCallableStatement {

  public OracleCallableStatement(PgConnection connection, String sql, int resultSetType,
                                 int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    super(connection, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
  }

  // 后续在这里添加Oracle特定的存储过程调用行为
  // 例如：
  // - OUT参数处理
  // - INOUT参数处理
  // - Oracle包调用语法
  // - REF CURSOR处理
  // - 存储过程返回值处理
}
