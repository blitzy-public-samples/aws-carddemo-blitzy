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
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for the framework client-error handlers added to
 * {@link GlobalExceptionHandler} for QA finding F-1: Spring MVC's
 * {@link NoResourceFoundException} must map to HTTP 404 and Spring Security's
 * {@link RequestRejectedException} must map to HTTP 400, rather than being
 * swallowed by the {@code Exception} catch-all as a 500. The tests also confirm
 * the genuine-500 fallback is preserved and that no untrusted input is echoed
 * into the response body.
 */
class GlobalExceptionHandlerTest {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsNoResourceFoundTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "no-such-thing");

        ProblemDetail problem = handler.handleNoResourceFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getDetail()).isEqualTo("The requested resource was not found.");
    }

    @Test
    void mapsRequestRejectedTo400() {
        RequestRejectedException ex = new RequestRejectedException(
                "The request was rejected because the header has a value \"<untrusted>\" that is not allowed.");

        ProblemDetail problem = handler.handleRequestRejected(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("The request was rejected as malformed.");
        // The untrusted, client-supplied value embedded in the exception message
        // must never be echoed back to the client.
        assertThat(problem.getDetail()).doesNotContain("<untrusted>");
    }

    @Test
    void catchAllStillMapsUnexpectedTo500WithGenericDetail() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("boom-internal-detail"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.getDetail()).doesNotContain("boom-internal-detail");
    }

    @Test
    void attachesCorrelationIdWhenPresentInMdc() {
        MDC.put(CORRELATION_ID_MDC_KEY, "QA-CID-001");

        ProblemDetail problem = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "x"));

        assertThat(problem.getProperties()).containsEntry(CORRELATION_ID_MDC_KEY, "QA-CID-001");
    }

    @Test
    void omitsCorrelationIdWhenAbsentFromMdc() {
        ProblemDetail problem = handler.handleRequestRejected(new RequestRejectedException("rejected"));

        assertThat(problem.getProperties() == null
                || !problem.getProperties().containsKey(CORRELATION_ID_MDC_KEY)).isTrue();
    }
}
