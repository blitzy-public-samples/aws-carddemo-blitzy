/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.startup;

import java.time.Duration;

/**
 * :purpose: One unit of start-up warm-up work, executed by {@link ReadinessWarmUp}
 *     before the service reports readiness. A task exercises a code path that would
 *     otherwise be paid for by the first real request — class loading, JIT
 *     compilation, a connection handshake, a query plan — so that the moment
 *     readiness reports UP the service can already serve at its steady-state
 *     latency.
 * :output: Nothing. A task is invoked for its side effects on the running JVM and
 *     must be strictly READ-ONLY with respect to application state: it may open
 *     connections and issue queries, and must never insert, update or delete.
 * :note: A task is invoked once per service start and may throw; the runner logs the
 *     failure and continues, because a warm-up that cannot complete must never stop
 *     a service from starting. Implementations respect the budget they are handed
 *     and return early once it is exhausted.
 */
public interface WarmUpTask {

    /**
     * :purpose: Identify the task in the start-up log.
     * :returns: a short stable name, for example ``database`` or ``http-self``.
     */
    String name();

    /**
     * :purpose: Perform the warm-up.
     * :param budget: the time this task may take; implementations stop early rather
     *     than exceed it, so a slow dependency delays readiness by a bounded amount.
     * :returns: the number of warm-up operations completed, for the start-up log.
     * :raises Exception: any failure, which the runner records and does not rethrow.
     */
    int warmUp(Duration budget) throws Exception;
}
