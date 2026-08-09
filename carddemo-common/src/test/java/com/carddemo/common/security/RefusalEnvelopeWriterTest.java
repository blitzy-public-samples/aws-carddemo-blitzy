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
package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.config.CorrelationIdContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Pin the refusal envelope written by the components that answer BEFORE a
 *     controller is reached: the shape is the one every other error response uses, the
 *     content names nothing about the resource, and a card number in the path is redacted.
 * :output: JUnit assertions only.
 */
class RefusalEnvelopeWriterTest {

    /** :purpose: Reader for the written document; the writer emits its JSON by hand. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearCorrelationId() {
        CorrelationIdContext.clear();
    }

    @Test
    @DisplayName("the 401 envelope carries every member of the shared shape")
    void writesFullShapeForUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/00000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorrelationIdContext.setCorrelationId("corr-1234");

        RefusalEnvelopeWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        // The writer authors no cache directive: see doesNotAuthorTheCacheDirective below.
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(response.getContentLength()).isGreaterThan(0);

        Map<String, Object> body = readBody(response);
        // Pinned as the exact nine-member set IN ORDER: four components can answer a failure
        // (this writer, the exception handler, the error controller and the container valve),
        // and the envelope is only a contract if a reader finds the same members, written the
        // same way, whichever one answered.
        assertThat(body.keySet()).containsExactly("timestamp", "status", "error", "errorCode",
                "message", "path", "traceId", "correlationId", "fieldErrors");
        assertThat(body.get("status")).isEqualTo(401);
        assertThat(body.get("error")).isEqualTo("Unauthorized");
        assertThat(body.get("errorCode")).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.get("message")).isEqualTo("Your session has ended. Please sign on again.");
        assertThat(body.get("path")).isEqualTo("/accounts/00000000001");
        assertThat(body.get("correlationId")).isEqualTo("corr-1234");
        assertThat(body.get("timestamp")).asString().endsWith("Z");
    }

    @Test
    @DisplayName("the 403 envelope reports a recoverable refusal")
    void writesFullShapeForDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefusalEnvelopeWriter.write(new MockHttpServletRequest("POST", "/users"), response,
                HttpStatus.FORBIDDEN,
                RefusalEnvelopeWriter.CODE_AUTHORIZATION_DENIED,
                RefusalEnvelopeWriter.MSG_AUTHORIZATION_DENIED);

        Map<String, Object> body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("errorCode")).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(body.get("message"))
                .isEqualTo("Request could not be authorized. Please try again.");
        assertThat(body.get("path")).isEqualTo("/users");
    }

    @Test
    @DisplayName("a card number in the refused path is reduced to its last four digits")
    void redactsPanInPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefusalEnvelopeWriter.write(
                new MockHttpServletRequest("GET", "/cards/9680294154603697"), response,
                HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);

        assertThat(readBody(response).get("path")).asString()
                .doesNotContain("9680294154603697")
                .endsWith("3697");
    }

    @Test
    @DisplayName("an absent id is a null member, so the shape does not vary with what happened")
    void writesNullForAbsentIds() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefusalEnvelopeWriter.write(new MockHttpServletRequest("GET", "/session"), response,
                HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);

        Map<String, Object> body = readBody(response);
        assertThat(body).containsEntry("traceId", null).containsEntry("correlationId", null);
    }

    @Test
    @DisplayName("a committed response is left exactly as it was")
    void leavesCommittedResponseAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("already sent");
        response.flushBuffer();

        RefusalEnvelopeWriter.write(new MockHttpServletRequest("GET", "/session"), response,
                HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);

        assertThat(response.getContentAsString()).isEqualTo("already sent");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * :purpose: The writer must not author a cache directive. Spring Security writes its
     *   hardening headers lazily, at commit time, and ``CacheControlHeadersWriter`` returns
     *   immediately if ANY of ``Cache-Control``, ``Expires`` or ``Pragma`` is already set — so
     *   a bare ``no-store`` set here before the flush would suppress the whole hardened triple
     *   rather than add to it. An already-present directive is likewise left untouched. The one
     *   path with no header writer behind it is the firewall rejection, and
     *   {@link RequestRejectedEnvelopeHandler} supplies the directive itself there.
     */
    @Test
    @DisplayName("the writer authors no cache directive and overwrites none")
    void doesNotAuthorTheCacheDirective() throws Exception {
        MockHttpServletResponse untouched = new MockHttpServletResponse();
        RefusalEnvelopeWriter.write(new MockHttpServletRequest("GET", "/auth/profile"), untouched,
                HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);
        assertThat(untouched.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(untouched.getHeader(HttpHeaders.PRAGMA)).isNull();
        assertThat(untouched.getHeader(HttpHeaders.EXPIRES)).isNull();
        assertThat(readBody(untouched).get("status")).isEqualTo(401);

        MockHttpServletResponse preset = new MockHttpServletResponse();
        preset.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
        RefusalEnvelopeWriter.write(new MockHttpServletRequest("GET", "/auth/profile"), preset,
                HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);
        assertThat(preset.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }

    /**
     * :purpose: The outside-the-chain entry point supplies the two headers ``HeaderWriterFilter``
     *   would have written, because on the firewall and source-address-budget paths that filter
     *   never runs — observed on both: the response carried only the correlation id. It still
     *   leaves an already-present directive alone, so it can never weaken one.
     */
    @Test
    @DisplayName("the outside-the-chain entry point supplies no-store and nosniff")
    void suppliesHeadersOutsideTheSecurityChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefusalEnvelopeWriter.writeOutsideSecurityChain(
                new MockHttpServletRequest("GET", "/accounts"), response,
                HttpStatus.BAD_REQUEST,
                RefusalEnvelopeWriter.CODE_REQUEST_REJECTED,
                RefusalEnvelopeWriter.MSG_REQUEST_REJECTED);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(readBody(response).get("status")).isEqualTo(400);

        MockHttpServletResponse preset = new MockHttpServletResponse();
        preset.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
        RefusalEnvelopeWriter.writeOutsideSecurityChain(
                new MockHttpServletRequest("GET", "/accounts"), preset,
                HttpStatus.BAD_REQUEST,
                RefusalEnvelopeWriter.CODE_REQUEST_REJECTED,
                RefusalEnvelopeWriter.MSG_REQUEST_REJECTED);
        assertThat(preset.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }

    @Test
    @DisplayName("the firewall rejection describes nothing about which check refused it")
    void writesOpaqueMessageForRejectedRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefusalEnvelopeWriter.write(new MockHttpServletRequest("GET", "/accounts"), response,
                HttpStatus.BAD_REQUEST,
                RefusalEnvelopeWriter.CODE_REQUEST_REJECTED,
                RefusalEnvelopeWriter.MSG_REQUEST_REJECTED);

        Map<String, Object> body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("errorCode")).isEqualTo("REQUEST_REJECTED");
        assertThat(body.get("message")).isEqualTo("The request could not be processed");
    }

    /**
     * :purpose: Parse the written document.
     * :param response: the completed response.
     * :returns: the envelope as a map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(MockHttpServletResponse response) throws Exception {
        return MAPPER.readValue(response.getContentAsString(), Map.class);
    }
}
