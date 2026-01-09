/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

/**
 * Oracle兼容模式的DatabaseMetaData实现
 * 继承PgDatabaseMetaData，后续可以override特定方法以实现Oracle特定行为
 */
public class OracleDatabaseMetaData extends PgDatabaseMetaData {

  public OracleDatabaseMetaData(PgConnection connection) {
    super(connection);
  }

  // 后续在这里添加Oracle特定的元数据行为override
  // 例如：
  // - getDatabaseProductName(): 返回"Oracle"而非"PostgreSQL"
  // - getDatabaseProductVersion(): 返回Oracle兼容的版本号
  // - getTables(): 适配Oracle的系统表结构
  // - getColumns(): 适配Oracle的列信息查询
  // - getPrimaryKeys(): 适配Oracle的主键查询
  // - getIndexInfo(): 适配Oracle的索引信息
  // - getTypeInfo(): 返回Oracle的类型信息
  // - 支持Oracle特有的系统表和视图
}
