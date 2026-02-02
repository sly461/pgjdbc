package org.postgresql.mytest;

import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;

import java.sql.*;
import java.util.*;

public class Test2 {

  private static final String PRIMARY_HOST = "43.136.135.5";
  private static final int PRIMARY_PORT = 5432;
  private static final String REPLICA1_HOST = "43.136.135.5";
  private static final int REPLICA1_PORT = 5433;
  private static final String REPLICA2_HOST = "43.136.135.5";
  private static final int REPLICA2_PORT = 5434;
  private static final String DATABASE = "test";

  private static final String testUser =  "test";
  private static final String testPassword = "test";


  public static void testPrepare() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, testUser);
    PGProperty.PASSWORD.set(props, testPassword);
    String url = buildConnectionUrl();

    Connection conn = DriverManager.getConnection(url, props);
    Statement stmt = conn.createStatement();
    PreparedStatement ps = conn.prepareStatement("select * from test where id = ? and val = ?");
    for (int i = 0; i < 10; i++) {
      if (i <= 6) {
        ps.setObject(1, null);
        ps.setString(2, "test");
        ps.execute();
      } else {
        ps.setObject(1, null);
        ps.setString(2, "test");
        ps.execute();
      }
    }
  }

  // ============================================
  // Helper Methods
  // ============================================

  private static String buildConnectionUrl() {
    return String.format("jdbc:postgresql://%s:%d/%s",
        PRIMARY_HOST, PRIMARY_PORT,
        DATABASE);
  }

  public static void main(String[] args) throws Exception {
    testPrepare();
  }
}
