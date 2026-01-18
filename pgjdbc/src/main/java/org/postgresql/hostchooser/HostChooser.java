/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;

/**
 * Lists connections in preferred order.
 */
public interface HostChooser extends Iterable<CandidateHost> {
  /**
   * Lists connection hosts in preferred order.
   *
   * @return connection hosts in preferred order.
   */
  @Override
  Iterator<CandidateHost> iterator();

  /**
   * Gets the cluster ID for this host chooser.
   * <p>
   * The cluster ID is used for load balancing strategies that need to track
   * cluster-wide state (e.g., leastConn strategy).
   * </p>
   *
   * @return cluster ID, or null if not applicable (e.g., single host or no load balancing)
   */
  @Nullable
  String getClusterId();
}
