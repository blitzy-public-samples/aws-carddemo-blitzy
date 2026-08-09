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
package com.carddemo.common.config;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * :purpose: Verify the container-level error valve renders the documented CardDemo
 *  error envelope as JSON. A request the container rejects while parsing - an
 *  encoded path separator, for example - never reaches the Spring dispatcher, so
 *  without this valve it would answer with Tomcat's HTML page, a second
 *  undocumented error shape in the API contract.
 * :output: Asserts the envelope keys and values written for a rejected request,
 *  that no markup or server identity is emitted, that a JSON-breaking character in
 *  the URI is escaped, and that a response which already carries content is left
 *  untouched.
 */
class ContainerErrorReportConfigTest {

    /**
     * :purpose: Build the valve under test.
     * :returns: a fresh {@link ContainerErrorReportConfig.SilentErrorReportValve}.
     */
    private ContainerErrorReportConfig.SilentErrorReportValve newValve() {
        return new ContainerErrorReportConfig.SilentErrorReportValve();
    }

    /**
     * :purpose: A container-level rejection is rendered as the documented envelope,
     *  not as an HTML page.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("a rejected request is rendered as the JSON envelope, not HTML")
    void rejectedRequestRendersJsonEnvelope() throws Exception {
        StringWriter sink = new StringWriter();
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getRequestURI()).thenReturn("/users/..%2F..%2Fetc%2Fpasswd");
        when(response.getStatus()).thenReturn(400);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getReporter()).thenReturn(new PrintWriter(sink));

        newValve().report(request, response, null);

        String body = sink.toString();
        assertThat(body).contains("\"status\":400")
                .contains("\"error\":\"Bad Request\"")
                .contains("\"message\":\"Bad Request\"")
                .contains("\"path\":\"/users/..%2F..%2Fetc%2Fpasswd\"")
                .contains("\"errorCode\":null")
                .contains("\"correlationId\":null")
                .contains("\"traceId\":null")
                .contains("\"correlationId\":null")
                .contains("\"fieldErrors\":null")
                .contains("\"timestamp\":\"");
        // Pinned as the exact nine-member set, in the ErrorResponse declaration order: this
        // valve is one of four components that can answer a failure, and the envelope is only
        // a contract if a reader finds the same members whichever one answered. The valve runs
        // after the request has unwound, so traceId and correlationId are genuinely
        // unavailable here — written as null rather than omitted.
        assertThat(readMemberNames(body)).containsExactly("timestamp", "status", "error",
                "errorCode", "message", "path", "traceId", "correlationId", "fieldErrors");
        assertThat(body).doesNotContain("<html").doesNotContain("Tomcat").doesNotContain("<h1>");
        verify(response).setContentType("application/json");
    }

    /**
     * :purpose: A URI carrying a JSON-breaking character is escaped, so the body
     *  cannot be corrupted or injected into.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("a quote in the URI is escaped")
    void quoteInUriIsEscaped() throws Exception {
        StringWriter sink = new StringWriter();
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getRequestURI()).thenReturn("/users/\"drop\"");
        when(response.getStatus()).thenReturn(400);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getReporter()).thenReturn(new PrintWriter(sink));

        newValve().report(request, response, null);

        assertThat(sink.toString()).contains("\\\"drop\\\"");
    }

    /**
     * :purpose: A response that already carries a body - the normal case when the
     *  application answered - is left untouched, so the valve can never overwrite
     *  an envelope the advice already produced.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("a response that already has content is left untouched")
    void committedResponseIsLeftUntouched() throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(400);
        when(response.getContentWritten()).thenReturn(120L);

        newValve().report(request, response, null);

        verify(response, never()).setContentType(anyString());
    }

    /**
     * :purpose: A successful response is never reported on.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("a successful response is never reported on")
    void successfulResponseIsNotReported() throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);

        newValve().report(request, response, null);

        verify(response, never()).setContentType(anyString());
    }

    /**
     * :purpose: The valve suppresses Tomcat's own report and server-info rendering
     *  so no markup or server identity can leak from the superclass.
     */
    @Test
    @DisplayName("Tomcat's own report and server info are disabled")
    void tomcatReportingIsDisabled() {
        ContainerErrorReportConfig.SilentErrorReportValve valve = newValve();

        assertThat(valve.isShowReport()).isFalse();
        assertThat(valve.isShowServerInfo()).isFalse();
    }
    /**
     * :purpose: When a filter DID run before the container took the response over, the envelope
     *  must carry the SAME correlation id that filter published and echoed to the caller, so a
     *  container-rendered body is correlatable against the access log.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("the established correlation id is rendered when a filter published one")
    void establishedCorrelationIdIsRendered() throws Exception {
        StringWriter sink = new StringWriter();
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getRequestURI()).thenReturn("/accounts/1");
        when(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE))
                .thenReturn("established-id-42");
        when(response.getStatus()).thenReturn(500);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getReporter()).thenReturn(new PrintWriter(sink));

        newValve().report(request, response, null);

        assertThat(sink.toString()).contains("\"correlationId\":\"established-id-42\"");
    }

    /**
     * :purpose: A URI the container refuses while parsing the request line runs NO filter at all,
     *  so the server never mints an id for it. A caller that supplied its own id must still get it
     *  back, and the value must be sanitized on the way through -- the raw header is attacker
     *  controlled and is being written into a JSON body and a log line.
     * :raises Exception: propagated from the mocked reporter.
     */
    @Test
    @DisplayName("an inbound correlation id is honoured and sanitized when no filter ran")
    void inboundCorrelationIdIsHonouredAndSanitized() throws Exception {
        StringWriter sink = new StringWriter();
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getRequestURI()).thenReturn("/cards/..%2F..%2Fetc%2Fpasswd");
        when(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE)).thenReturn(null);
        when(request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .thenReturn("caller\r\nInjected: 1");
        when(response.getStatus()).thenReturn(400);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getReporter()).thenReturn(new PrintWriter(sink));

        newValve().report(request, response, null);

        String body = sink.toString();
        assertThat(body).contains("\"correlationId\":\"caller")
                .doesNotContain("\r").doesNotContain("\n")
                .doesNotContain("Injected: 1");
    }

    /**
     * :purpose: Read the member names of a flat JSON document in document order.
     * :param json: the rendered envelope.
     * :returns: the member names, in the order they were written.
     */
    private static java.util.List<String> readMemberNames(String json) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"([A-Za-z]+)\"\\s*:").matcher(json);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
