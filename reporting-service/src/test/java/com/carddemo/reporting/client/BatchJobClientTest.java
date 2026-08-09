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

import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.config.CorrelationIdFilter;
import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.UpstreamUnavailableException;
import com.sun.net.httpserver.Headers;
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
import org.springframework.web.client.RestClient;

/**
 * :purpose: Tests how the report hand-off reports its outcome. ``CORPT00C``'s
 *   ``Unable to Write TDQ (JOBS)...`` literal means one specific thing -- the hand-off
 *   itself could not be written -- so using it for a submission batch-service ANSWERED and
 *   REFUSED told the user the wrong cause and invited a retry of a request that could never
 *   succeed. A refusal must therefore carry its reason, while an unreachable batch-service
 *   keeps the frozen literal.
 * :output: Assertions over the exception messages and the accepted execution handle.
 * :note: A real loopback HTTP server is used rather than a stubbed ``RestClient``, because the
 *   component builds its own client from the configured base URI and the response-status
 *   handling under test lives inside that client.
 */
@DisplayName("BatchJobClient report hand-off outcomes")
class BatchJobClientTest {

    /** Loopback server standing in for batch-service; started per test. */
    private HttpServer server;

    /** Request headers recorded by {@link #startCapturingServer()}. */
    private Headers capturedHeaders;

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
        return new BatchJobClient(RestClient.builder(), request, baseUri);
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
     * :purpose: batch-service answered and refused, so the reason reaches the caller instead
     *     of the TDQ literal, which would misstate the cause.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("a refusal carries its reason, not the frozen TDQ literal")
    void refusalCarriesItsReason() throws IOException {
        String reason = "Unable to submit batch job transactionDetailReportJob: "
                + "A job instance already exists and is complete for identifying parameters";
        String baseUri = startServer(400, "{\"status\":400,\"error\":\"Bad Request\","
                + "\"message\":\"" + reason + "\",\"path\":\"/batch/jobs\"}");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMISSION_REFUSED_PREFIX + reason);
    }

    /**
     * :purpose: A refusal whose body carries no message still reports a refusal rather than a
     *     failed hand-off.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("a refusal without a message body still reports a refusal")
    void refusalWithoutMessageStillReportsARefusal() throws IOException {
        String baseUri = startServer(503, "");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessageStartingWith(BatchJobClient.SUBMISSION_REFUSED_PREFIX)
                .hasMessageNotContaining(BatchJobClient.SUBMIT_FAILURE_MESSAGE);
    }

    /**
     * :purpose: An unreachable batch-service means the hand-off never landed, which is exactly
     *     what the frozen ``CORPT00C`` literal reports, and it is raised as an
     *     {@link UpstreamUnavailableException} so the outcome reports ``503`` rather than the
     *     ``400`` a caller-fault would report -- while the message stays byte-identical.
     * :raises IOException: if no free port can be reserved.
     */
    @Test
    @DisplayName("an unreachable batch-service keeps the frozen TDQ literal and reports it as unavailable")
    void unreachableBatchServiceKeepsTheFrozenLiteral() throws IOException {
        String baseUri = unusedBaseUri();

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(UpstreamUnavailableException.class)
                .isInstanceOf(CardDemoException.class)
                .hasMessage(BatchJobClient.SUBMIT_FAILURE_MESSAGE);
    }

    /**
     * :purpose: A refusal is NOT an unavailable upstream: batch-service answered, so the
     *     outcome stays a caller-facing ``400`` and must not be widened to the ``503``
     *     mapping.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("a refusal is not reported as an unavailable upstream")
    void refusalIsNotReportedAsUnavailable() throws IOException {
        String baseUri = startServer(400, "{\"status\":400,\"message\":\"Unusable parameters\"}");

        assertThatThrownBy(() -> clientFor(baseUri)
                .submitTransactionDetailReport("2026-08-01", "2026-08-31"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(UpstreamUnavailableException.class);
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

    /**
     * :purpose: The submission must carry the correlation id that is in scope for the report
     *     request, so batch-service logs the run it launches under the SAME business
     *     identifier the caller was given. Without it the two sides of the only hop that
     *     crosses a service boundary could not be tied together in the log stream.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("the submission carries the in-scope X-Correlation-Id")
    void submissionCarriesTheInScopeCorrelationId() throws IOException {
        String baseUri = startCapturingServer();
        CorrelationIdContext.setCorrelationId("report-corr-42");
        try {
            clientFor(baseUri).submitTransactionDetailReport("2026-08-01", "2026-08-31");
        } finally {
            CorrelationIdContext.clear();
        }

        assertThat(capturedHeaders.getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .as("outbound %s", CorrelationIdFilter.CORRELATION_ID_HEADER)
                .isEqualTo("report-corr-42");
        // The forwarded session cookie must survive alongside the new header.
        assertThat(capturedHeaders.getFirst("Cookie")).contains("SESSION=abc123");
    }

    /**
     * :purpose: With no correlation id in scope the header is omitted entirely rather than
     *     sent empty, so batch-service's own filter mints a real id instead of adopting a
     *     blank one.
     * :raises IOException: if the loopback server cannot be started.
     */
    @Test
    @DisplayName("no correlation id in scope sends no correlation header")
    void absentCorrelationIdSendsNoHeader() throws IOException {
        String baseUri = startCapturingServer();
        CorrelationIdContext.clear();

        clientFor(baseUri).submitTransactionDetailReport("2026-08-01", "2026-08-31");

        assertThat(capturedHeaders.get(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
    }

    /**
     * :purpose: Start a loopback server that records the inbound request headers and accepts
     *     the submission, so the outbound header set is observable.
     * :returns: the base URI of the running server.
     * :raises IOException: if the server cannot be started.
     */
    private String startCapturingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedHeaders = exchange.getRequestHeaders();
            respond(exchange, 202, "{\"jobName\":\"transactionDetailReportJob\","
                    + "\"jobExecutionId\":9,\"jobInstanceId\":4,\"status\":\"STARTING\","
                    + "\"exitCode\":\"UNKNOWN\",\"exitMessage\":\"\"}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
