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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.exception.RecordNotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * :purpose: Verifies that a card number embedded in a request path never reaches a log
 *     record, while the API response still reports the path the caller asked for. The
 *     legacy card screens display the full sixteen-digit number, so the response field
 *     contract is preserved; a log file has no legacy analogue and must not retain a PAN.
 */
class GlobalExceptionHandlerLoggingTest {

    /** :purpose: A seeded sixteen-digit card number, used as a path variable. */
    private static final String PAN = "0500024453765740";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Logger handlerLogger;

    private ListAppender<ILoggingEvent> appender;

    /**
     * :purpose: Attach a capturing appender to the handler's logger.
     */
    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        handlerLogger.setLevel(Level.DEBUG);
    }

    /**
     * :purpose: Detach the capturing appender so it cannot leak into another test.
     */
    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    /**
     * :purpose: Render every captured record with its arguments substituted.
     */
    private String capturedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    @DisplayName("a card number in the request path is masked in the log but kept in the response body")
    void panInPathIsMaskedInLogsOnly() {
        ServletWebRequest request = new ServletWebRequest(
                new MockHttpServletRequest("GET", "/cards/" + PAN));

        ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(
                new RecordNotFoundException("Did not find cards for this search condition"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPath())
                .as("the response reports the path the caller requested")
                .isEqualTo("/cards/" + PAN);

        String logged = capturedText();
        assertThat(logged).contains("Record not found at");
        assertThat(logged).doesNotContain(PAN);
        assertThat(logged).contains("****5740");
    }

    @Test
    @DisplayName("a card number echoed in a detail message is masked in the log")
    void panInMessageIsMaskedInLogs() {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/cards"));

        handler.handleRecordNotFound(
                new RecordNotFoundException("No card " + PAN + " for this search condition"), request);

        assertThat(capturedText()).doesNotContain(PAN).contains("****5740");
    }

    @Test
    @DisplayName("the data-access log path masks both the request path and the driver message")
    void panIsMaskedOnTheDataAccessPath() {
        ServletWebRequest request = new ServletWebRequest(
                new MockHttpServletRequest("PUT", "/cards/" + PAN));

        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement; parameters were [" + PAN + "]"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String logged = capturedText();
        assertThat(logged).contains("Data access failure at");
        assertThat(logged)
                .as("neither the path nor the failing statement may retain the PAN")
                .doesNotContain(PAN);
        assertThat(logged).contains("****5740");
    }

    @Test
    @DisplayName("the optimistic-lock log path masks the request path")
    void panIsMaskedOnTheOptimisticLockPath() {
        ServletWebRequest request = new ServletWebRequest(
                new MockHttpServletRequest("PUT", "/cards/" + PAN));

        handler.handleOptimisticLockConflict(
                new com.carddemo.common.exception.OptimisticLockConflictException(), request);

        assertThat(capturedText()).doesNotContain(PAN).contains("****5740");
    }
}
