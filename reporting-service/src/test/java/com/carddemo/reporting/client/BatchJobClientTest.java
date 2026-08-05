/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.reporting.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.exception.CardDemoException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Tests how the report hand-off reports its outcome. ``CORPT00C`` emits exactly one
 *   hand-off failure message -- ``Unable to Write TDQ (JOBS)...`` on any non-normal
 *   ``WRITEQ TD QUEUE('JOBS')`` response (L517-531) -- so every failure, whether
 *   batch-service refused the run or could not be reached at all, must surface that frozen
 *   literal and nothing else. The refusal reason belongs in the log stream.
 * :output: Assertions over the exception messages and the accepted execution handle.
 * :note: A real loopback HTTP server is used rather than a stubbed ``RestClient``, because the
 *   component builds its own client from the configured base URI and the response-status
 *   handling under test lives inside that client.
 */
@DisplayName("BatchJobClient report hand-off outcomes")
class BatchJobClientTest {

    /** Loopback server standing in for batch-service; started per test. */
    private HttpServer server;

    /**
     * :purpose: Stop the loopback server after each case so no port is left bound.
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * :purpose: Start a loopback server that answers every request with one fixed status and
     *     body, standing in for batch-service.
     * :param status: the HTTP status to answer with.
     * :param body: the response body to write.
     * :returns: the base URI of the running server.
     * :raises IOException: if the server cannot be started.
     */
    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, status, body));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * :purpose: Write one fixed response and close the exchange.
     * :param exchange: the exchange to answer.
     * :param status: the HTTP status to send.
     * :param body: the body to write.
     * :raises IOException: if the response cannot be written.
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * :purpose: Reserve and release a port so nothing is listening on it, modelling an
     *     unreachable batch-service.
     * :returns: a base URI whose port has no listener.
     * :raises IOException: if no port can be reserved.
     */
    private static String unusedBaseUri() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    /**
     * :purpose: Build the component under test against a base URI, with a request carrying a
     *     session cookie so the forwarded ``Cookie`` header is exercised.
     * :param baseUri: the batch-service base URI.
     * :returns: the client under test.
     */
    private static BatchJobClient clientFor(String baseUri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("SESSION", "abc123")});
        return new BatchJobClient(request, baseUri);
    }

    /**
     * :purpose: An accepted submission returns the durable execution handle unchanged.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("an accepted submission returns the execution handle")
    void acceptedSubmissionReturnsTheExecutionHandle() throws IOException {
        String baseUri = startServer(202, "{\"jobName\":\"transactionDetailReportJob\","
                + "\"jobExecutionId\":7,\"jobInstanceId\":3,\"status\":\"STARTING\","
                + "\"exitCode\":\"UNKNOWN\",\"exitMessage\":\"\"}");

        BatchJobExecutionDto accepted = clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31");

        assertThat(accepted.jobExecutionId()).isEqualTo(7L);
        assertThat(accepted.status()).isEqualTo("STARTING");
    }

    /**
     * :purpose: batch-service answered and refused the run, so the caller still receives the
     *     single frozen ``CORPT00C`` hand-off message and none of the downstream detail.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("a refusal surfaces the frozen TDQ literal, not the downstream reason")
    void refusalSurfacesTheFrozenLiteral() throws IOException {
        String reason = "Unable to submit batch job transactionDetailReportJob: "
                + "A job instance already exists and is complete for identifying parameters";
        String baseUri = startServer(400, "{\"status\":400,\"error\":\"Bad Request\","
                + "\"message\":\"" + reason + "\",\"path\":\"/batch/jobs\"}");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMIT_FAILURE_MESSAGE)
                .hasMessageNotContaining("already exists");
    }

    /**
     * :purpose: A refusal whose body carries no message also surfaces the frozen literal.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("a refusal without a message body also surfaces the frozen TDQ literal")
    void refusalWithoutMessageAlsoSurfacesTheFrozenLiteral() throws IOException {
        String baseUri = startServer(503, "");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMIT_FAILURE_MESSAGE);
    }

    /**
     * :purpose: An unreachable batch-service means the hand-off never landed, which is exactly
     *     what the frozen ``CORPT00C`` literal reports.
     * :raises IOException: if no free port can be reserved.
     */
    @Test
    @DisplayName("an unreachable batch-service keeps the frozen TDQ literal")
    void unreachableBatchServiceKeepsTheFrozenLiteral() throws IOException {
        String baseUri = unusedBaseUri();

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMIT_FAILURE_MESSAGE);
    }

    /**
     * :purpose: An answer that carries no execution id is not an accepted submission, so the
     *     frozen literal applies: nothing was handed off.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("an answer without an execution id keeps the frozen TDQ literal")
    void answerWithoutExecutionIdKeepsTheFrozenLiteral() throws IOException {
        String baseUri = startServer(202, "{\"jobName\":\"transactionDetailReportJob\"}");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMIT_FAILURE_MESSAGE);
    }
}
