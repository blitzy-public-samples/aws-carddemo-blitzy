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
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * :purpose: Verifies that a request refused because the connection pool could not hand out
 *     a connection within its acquisition timeout is reported as a retryable ``503`` with a
 *     ``Retry-After`` header, while every other data-access or transaction failure keeps its
 *     ``500``. The pool-timeout signal arrives wrapped two different ways depending on
 *     whether a transactional boundary was being entered, and both must classify the same.
 */
@DisplayName("GlobalExceptionHandler — connection-pool exhaustion maps to 503 + Retry-After")
class GlobalExceptionHandlerPoolExhaustionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cards");
        request.setRequestURI("/cards");
        return new ServletWebRequest(request);
    }

    /** :purpose: HikariCP's acquisition-timeout signal, as the driver raises it. */
    private static SQLTransientConnectionException poolTimeout() {
        return new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 3000ms");
    }

    @Test
    @DisplayName("a pool timeout entering a transaction reports 503 with Retry-After")
    void poolTimeoutEnteringTransactionReports503() {
        CannotCreateTransactionException ex = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction", poolTimeout());

        ResponseEntity<ErrorResponse> response = handler.handleCannotCreateTransaction(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(503);
        assertThat(response.getBody().getPath()).isEqualTo("/cards");
        assertThat(response.getBody().getMessage()).contains("temporarily at capacity");
        // The driver text can echo credentials and statement detail; it must not be surfaced.
        assertThat(response.getBody().getMessage()).doesNotContain("HikariPool");
    }

    @Test
    @DisplayName("a pool timeout outside a transaction reports 503 with Retry-After")
    void poolTimeoutOutsideTransactionReports503() {
        DataAccessResourceFailureException ex =
                new DataAccessResourceFailureException("Unable to acquire JDBC Connection", poolTimeout());

        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("the signal is recognised through a nested cause chain")
    void poolTimeoutNestedDeeperStillReports503() {
        CannotCreateTransactionException ex = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new IllegalStateException("wrapper", poolTimeout()));

        ResponseEntity<ErrorResponse> response = handler.handleCannotCreateTransaction(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    @DisplayName("a data-access failure that is NOT a pool timeout still reports 500")
    void ordinaryDataAccessFailureStillReports500() {
        JpaSystemException ex = new JpaSystemException(new RuntimeException("constraint violated"));

        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("data access error");
    }

    @Test
    @DisplayName("a transaction that cannot start for another reason still reports 500")
    void otherTransactionFailureStillReports500() {
        CannotCreateTransactionException ex =
                new CannotCreateTransactionException("no transaction manager", new RuntimeException("boom"));

        ResponseEntity<ErrorResponse> response = handler.handleCannotCreateTransaction(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    @DisplayName("a self-referential cause chain terminates instead of looping")
    void selfReferentialCauseChainTerminates() {
        RuntimeException loop = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        CannotCreateTransactionException ex =
                new CannotCreateTransactionException("cyclic", loop);

        ResponseEntity<ErrorResponse> response = handler.handleCannotCreateTransaction(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
}
