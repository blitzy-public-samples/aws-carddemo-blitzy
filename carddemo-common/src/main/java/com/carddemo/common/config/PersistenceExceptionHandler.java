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
package com.carddemo.common.config;

import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.PiiEncryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * :purpose: Translate persistence-layer concurrency failures that escape a service's
 *     own catch block into the legacy CICS concurrency outcome. ``COACTUPC``
 *     implements a read-snapshot-compare-rewrite pattern whose
 *     ``DATA-WAS-CHANGED-BEFORE-UPDATE`` condition reports
 *     ``'Record changed by some one else. Please review'``
 *     (``app/cbl/COACTUPC.cbl`` L522); the migrated services express that with JPA
 *     ``@Version`` optimistic locking (AAP 0.6.2). When the version mismatch is only
 *     detected at transaction commit — after the service method has returned — the
 *     resulting {@link OptimisticLockingFailureException} would otherwise surface as
 *     an unexpected HTTP 500 instead of that outcome.
 * :output: HTTP 409 carrying the standard {@code ErrorResponse} envelope — the frozen COBOL
 *     message, the conflict error code, the trace id and the correlation id — for every service
 *     whose classpath includes Spring's ORM support.
 * :note: Declared separately from {@link GlobalExceptionHandler} and guarded by
 *     {@link ConditionalOnClass} because the API gateway carries no persistence
 *     dependencies; loading a handler whose signature references a Spring ORM type
 *     there would fail context initialization.
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.orm.ObjectOptimisticLockingFailureException")
@Order(0)
public class PersistenceExceptionHandler {

    /** :purpose: Logger for persistence-layer concurrency failures. */
    private static final Logger log = LoggerFactory.getLogger(PersistenceExceptionHandler.class);

    /**
     * :purpose: Map any Spring data-access optimistic-locking failure — including the
     *     ``ObjectOptimisticLockingFailureException`` Hibernate raises from a
     *     ``StaleStateException`` at flush or commit — onto HTTP 409 and the frozen
     *     COBOL concurrency message.
     * :param ex: the optimistic-locking failure raised by the persistence layer.
     * :param request: the current web request, used for the ``path`` field.
     * :returns: HTTP 409 with the standard error envelope, carrying
     *     {@link GlobalExceptionHandler#ERROR_CODE_CONFLICT}.
     * :note: The envelope is assembled by the shared {@link ErrorResponseFactory}, exactly as
     *     {@link GlobalExceptionHandler} assembles its own conflict response. Building it here by
     *     hand left ``correlationId`` and ``errorCode`` null on this path alone, so an account or
     *     bill-payment conflict — which reaches THIS advice, because the version mismatch is only
     *     detected when the transaction flushes at commit, after the service's catch has already
     *     returned — answered with a thinner envelope than a card conflict, which reaches the other
     *     advice. The message was right either way; what a caller could not do was branch on the
     *     conflict or correlate the response with its request.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex, WebRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse body = ErrorResponseFactory.build(status,
                OptimisticLockConflictException.MESSAGE, request);
        body.setErrorCode(GlobalExceptionHandler.ERROR_CODE_CONFLICT);
        log.warn("Optimistic-locking failure from the persistence layer at {}: {}",
                body.getPath(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Recover the at-rest encryption failure that Hibernate hides. When an
     *     ``AttributeConverter`` throws while a column is being read or written, Hibernate
     *     wraps the cause in its own ``HibernateException`` and Spring re-wraps that as a
     *     {@link JpaSystemException}, so the {@link PiiEncryptionException} raised by
     *     ``CryptoConverter`` never reaches the handler registered for it. This handler walks
     *     the cause chain and, when it finds that domain type, answers with the standard
     *     envelope and the frozen non-disclosing message instead of an opaque ORM error.
     * :param ex: the ORM system failure raised by the persistence layer.
     * :param request: the current web request, used for the ``path`` field.
     * :returns: HTTP 500 with the standard error envelope.
     * :raises JpaSystemException: rethrown unchanged when the cause chain carries no
     *     at-rest encryption failure, so unrelated ORM faults keep their own handling.
     */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<ErrorResponse> handleJpaSystemException(JpaSystemException ex,
                                                                 WebRequest request) {
        if (!carriesPiiEncryptionFailure(ex)) {
            throw ex;
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        // Assembled by the shared factory for the same reason as the conflict response above: one
        // envelope shape, and a correlation id on every failure. No error code is published here,
        // matching GlobalExceptionHandler's own at-rest-encryption response.
        ErrorResponse body = ErrorResponseFactory.build(status, PiiEncryptionException.MESSAGE,
                request);
        // The cause is logged for the operator; the response discloses no column,
        // key material or cryptographic detail.
        log.error("Protected-data conversion failed at {}", body.getPath(), ex);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Report whether an at-rest encryption failure appears anywhere in a
     *     throwable's cause chain.
     * :param throwable: the throwable to inspect.
     * :returns: ``true`` when a {@link PiiEncryptionException} is present.
     */
    private boolean carriesPiiEncryptionFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof PiiEncryptionException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

}
