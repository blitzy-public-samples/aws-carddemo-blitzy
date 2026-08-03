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
                .contains("\"traceId\":null")
                .contains("\"fieldErrors\":null")
                .contains("\"timestamp\":\"");
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
}
