/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.connection;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.config.DriverOption;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.retry.DefaultRetryPolicy;
import com.datastax.oss.driver.api.core.retry.RetryDecision;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.testinfra.cluster.ClusterRule;
import com.datastax.oss.driver.api.testinfra.simulacron.SimulacronRule;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.noRows;
import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.rows;
import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class FrameLengthIT {
  public static @ClassRule SimulacronRule simulacron =
      new SimulacronRule(ClusterSpec.builder().withNodes(1));

  @ClassRule
  public static ClusterRule cluster =
      new ClusterRule(
          simulacron,
          "load-balancing-policy.class = com.datastax.oss.driver.api.testinfra.loadbalancing.SortingLoadBalancingPolicy",
          "request.retry-policy.class = \"com.datastax.oss.driver.api.core.connection.FrameLengthIT$AlwaysRetryAbortedPolicy\"",
          "protocol.max-frame-length = 100 kilobytes");

  private static final SimpleStatement LARGE_QUERY =
      SimpleStatement.newInstance("select * from foo").setIdempotent(true);
  private static final SimpleStatement SLOW_QUERY =
      SimpleStatement.newInstance("select * from bar");

  private static final Buffer ONE_HUNDRED_KB = ByteBuffer.allocate(100 * 1024).limit(100 * 1024);

  @Before
  public void primeQueries() {
    simulacron
        .cluster()
        .prime(
            when(LARGE_QUERY.getQuery())
                .then(rows().row("result", ONE_HUNDRED_KB).columnTypes("result", "blob").build()));
    simulacron
        .cluster()
        .prime(when(SLOW_QUERY.getQuery()).then(noRows()).delay(60, TimeUnit.SECONDS));
  }

  @Test(expected = FrameTooLongException.class)
  public void should_fail_if_request_exceeds_max_frame_length() {
    cluster
        .session()
        .execute(SimpleStatement.newInstance("insert into foo (k) values (?)", ONE_HUNDRED_KB));
  }

  @Test
  public void should_fail_if_response_exceeds_max_frame_length() {
    CompletionStage<AsyncResultSet> slowResultFuture = cluster.session().executeAsync(SLOW_QUERY);
    try {
      cluster.session().execute(LARGE_QUERY);
      fail("Expected a " + FrameTooLongException.class.getSimpleName());
    } catch (FrameTooLongException e) {
      // expected
    }
    // Check that the error does not abort other requests on the same connection
    assertThat(slowResultFuture.toCompletableFuture()).isNotCompleted();
  }

  /**
   * A retry policy that always retries aborted requests.
   *
   * <p>We use this to validate that {@link FrameTooLongException} is never passed to the policy (if
   * it were, then this policy would retry it, and the exception thrown to the client would be an
   * {@link AllNodesFailedException}).
   */
  public static class AlwaysRetryAbortedPolicy extends DefaultRetryPolicy {
    public AlwaysRetryAbortedPolicy(DriverContext context, DriverOption configRoot) {
      super(context, configRoot);
    }

    @Override
    public RetryDecision onRequestAborted(Request request, Throwable error, int retryCount) {
      return RetryDecision.RETRY_NEXT;
    }
  }
}
