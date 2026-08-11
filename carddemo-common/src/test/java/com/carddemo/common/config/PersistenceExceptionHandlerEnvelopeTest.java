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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.exception.OptimisticLockConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * :purpose: Verifies that the commit-time concurrency conflict answers with the SAME envelope as
 *     the service-detected one. Both are the legacy ``DATA-WAS-CHANGED-BEFORE-UPDATE`` outcome, so
 *     both must carry the frozen COBOL message, the conflict error code and the request's
 *     correlation id — otherwise a caller can branch on a card conflict but not on an account or
 *     bill-payment conflict, which reach this advice instead because the version mismatch is only
 *     detected when the transaction flushes.
 */
@DisplayName("PersistenceExceptionHandler — commit-time conflict envelope (409)")
class PersistenceExceptionHandlerEnvelopeTest {

    private final PersistenceExceptionHandler handler = new PersistenceExceptionHandler();

    private final GlobalExceptionHandler globalHandler = new GlobalExceptionHandler();

    private static ServletWebRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return new ServletWebRequest(request);
    }

    @AfterEach
    void clearCorrelationId() {
        CorrelationIdContext.clear();
    }

    @Test
    @DisplayName("a commit-time conflict carries the conflict code and the correlation id")
    void commitTimeConflictCarriesTheFullEnvelope() {
        CorrelationIdContext.setCorrelationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailure(
                new ObjectOptimisticLockingFailureException("Account", 11L),
                request("/billpay"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(OptimisticLockConflictException.MESSAGE);
        assertThat(body.getErrorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        assertThat(body.getCorrelationId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(body.getPath()).isEqualTo("/billpay");
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getError()).isEqualTo("Conflict");
    }

    /**
     * :purpose: The two advices answer one outcome, so their envelopes must agree field for field
     *  apart from the request path. This is the assertion that would have caught the original
     *  divergence.
     */
    @Test
    @DisplayName("the commit-time envelope matches the service-detected one field for field")
    void bothConflictPathsAgree() {
        CorrelationIdContext.setCorrelationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        ErrorResponse commitTime = handler.handleOptimisticLockingFailure(
                new ObjectOptimisticLockingFailureException("Account", 11L),
                request("/accounts/00000000011")).getBody();
        ErrorResponse serviceDetected = globalHandler.handleOptimisticLockConflict(
                new OptimisticLockConflictException(),
                request("/accounts/00000000011")).getBody();

        assertThat(commitTime).isNotNull();
        assertThat(serviceDetected).isNotNull();
        assertThat(commitTime.getStatus()).isEqualTo(serviceDetected.getStatus());
        assertThat(commitTime.getError()).isEqualTo(serviceDetected.getError());
        assertThat(commitTime.getMessage()).isEqualTo(serviceDetected.getMessage());
        assertThat(commitTime.getErrorCode()).isEqualTo(serviceDetected.getErrorCode());
        assertThat(commitTime.getCorrelationId()).isEqualTo(serviceDetected.getCorrelationId());
        assertThat(commitTime.getPath()).isEqualTo(serviceDetected.getPath());
    }

    /**
     * :purpose: The framework-level fallback inside {@link GlobalExceptionHandler}, which serves a
     *  service whose classpath carries no Spring ORM support, must publish the same code as well.
     */
    @Test
    @DisplayName("the framework-level fallback publishes the same conflict code")
    void frameworkFallbackPublishesTheSameCode() {
        ResponseEntity<ErrorResponse> response = globalHandler.handleOptimisticLockingFailure(
                new OptimisticLockingFailureException("stale state"), request("/cards"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        assertThat(response.getBody().getMessage()).isEqualTo(OptimisticLockConflictException.MESSAGE);
    }

    /**
     * :purpose: The shared factory redacts the card-number segment of the request path, so a
     *  conflict on a card write cannot echo a PAN back to the caller. Assembling the envelope by
     *  hand bypassed that redaction on this path.
     */
    @Test
    @DisplayName("a card-number path segment is redacted in the envelope")
    void cardNumberInThePathIsRedacted() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailure(
                new ObjectOptimisticLockingFailureException("Card", "4859452612877065"),
                request("/cards/4859452612877065"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPath()).doesNotContain("4859452612877065");
        assertThat(response.getBody().getPath()).endsWith("7065");
    }
}
