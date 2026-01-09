/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;

/**
 * Oracle兼容模式的Statement实现
 * 继承PgStatement，后续可以override特定方法以实现Oracle特定行为
 */
public class OracleStatement extends PgStatement {

  public OracleStatement(PgConnection connection, int resultSetType,
                         int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    super(connection, resultSetType, resultSetConcurrency, resultSetHoldability);
  }

  // 后续在这里添加Oracle特定的行为override
  // 例如：
  // - executeQuery/executeUpdate的特殊处理
  // - SQL语法转换（Oracle特定语法）
  // - 游标处理
}
