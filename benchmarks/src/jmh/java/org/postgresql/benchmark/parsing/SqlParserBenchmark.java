/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.parsing;

import org.postgresql.readwritesplitting.SqlParser;
import org.postgresql.readwritesplitting.SqlType;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark for SQL parser performance.
 * Compares character-based parsing vs regex-based parsing.
 */
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SqlParserBenchmark {

  private static final String SELECT_SIMPLE = "SELECT * FROM users";
  private static final String SELECT_FOR_UPDATE = "SELECT * FROM users WHERE id = 1 FOR UPDATE";
  private static final String INSERT = "INSERT INTO users (id, name) VALUES (1, 'test')";
  private static final String UPDATE = "UPDATE users SET name = 'test' WHERE id = 1";
  private static final String DELETE = "DELETE FROM users WHERE id = 1";
  private static final String SELECT_FUNCTION = "SELECT now()";
  private static final String SELECT_WITH_HINT = "/*master*/ SELECT * FROM users";

  @Benchmark
  public SqlType parseSelectSimple() {
    return SqlParser.parseSqlType(SELECT_SIMPLE);
  }

  @Benchmark
  public SqlType parseSelectForUpdate() {
    return SqlParser.parseSqlType(SELECT_FOR_UPDATE);
  }

  @Benchmark
  public SqlType parseInsert() {
    return SqlParser.parseSqlType(INSERT);
  }

  @Benchmark
  public SqlType parseUpdate() {
    return SqlParser.parseSqlType(UPDATE);
  }

  @Benchmark
  public SqlType parseDelete() {
    return SqlParser.parseSqlType(DELETE);
  }

  @Benchmark
  public SqlType parseSelectFunction() {
    return SqlParser.parseSqlType(SELECT_FUNCTION);
  }

  @Benchmark
  public SqlType parseSelectWithHint() {
    return SqlParser.parseSqlType(SELECT_WITH_HINT);
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(SqlParserBenchmark.class.getSimpleName())
        .build();

    new Runner(opt).run();
  }
}
