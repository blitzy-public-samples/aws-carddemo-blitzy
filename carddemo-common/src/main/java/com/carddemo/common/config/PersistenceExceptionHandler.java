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
import com.carddemo.common.exception.PiiEncryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * :purpose: Translate the persistence-layer failures that need Spring ORM on the
 *     classpath into the CardDemo error envelope. A value that is not valid ciphertext
 *     in a column mapped with ``CryptoConverter`` fails CLOSED: Hibernate wraps the
 *     converter's {@code PiiEncryptionException} in a {@link JpaSystemException}, which
 *     would otherwise be reported by the generic ``DataAccessException`` mapping and
 *     disclose a persistence-layer message instead of the frozen text (AAP 0.6.7).
 * :output: HTTP 500 carrying the standard {@code ErrorResponse} envelope with the frozen
 *     non-disclosing message, for every service whose classpath includes Spring's ORM
 *     support.
 * :note: Declared separately from {@link GlobalExceptionHandler} and guarded by
 *     {@link ConditionalOnClass} because the API gateway carries no persistence
 *     dependencies; loading a handler whose signature references a Spring ORM type
 *     there would fail context initialization. {@code @Order(0)} places this advice
 *     ahead of the generic mapping, which would otherwise match the same throwable
 *     through {@code DataAccessException}. The optimistic-locking outcome is mapped by
 *     {@link GlobalExceptionHandler}, whose ``org.springframework.dao`` signature loads
 *     in every service, so exactly one advice owns it.
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.orm.ObjectOptimisticLockingFailureException")
@Order(0)
public class PersistenceExceptionHandler {

    /** :purpose: Logger for protected-data conversion failures. */
    private static final Logger log = LoggerFactory.getLogger(PersistenceExceptionHandler.class);

    /**
     * :purpose: Translate a protected-column conversion failure into the fail-closed
     *     response. Hibernate wraps a converter failure in a {@link JpaSystemException},
     *     which would otherwise reach the generic ``DataAccessException`` mapping and
     *     disclose a persistence-layer message.
     * :param ex: the wrapped persistence failure.
     * :param request: the current request, used for the envelope path.
     * :returns: HTTP 500 carrying the frozen non-disclosing message.
     * :raises JpaSystemException: re-thrown unchanged when the cause is not an at-rest
     *     encryption failure, so the generic mapping handles it.
     */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<ErrorResponse> handleJpaSystemException(JpaSystemException ex,
                                                                 WebRequest request) {
        if (!carriesPiiEncryptionFailure(ex)) {
            throw ex;
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        // The cause is logged for the operator; the response discloses no column,
        // key material or cryptographic detail.
        log.error("Protected-data conversion failed at {}", ErrorResponseFactory.path(request), ex);
        return ResponseEntity.status(status)
                .body(ErrorResponseFactory.build(status, PiiEncryptionException.MESSAGE, request));
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

    /**
     * :purpose: Derive the request path for the error envelope from the ``WebRequest``
     *     description, mirroring {@link GlobalExceptionHandler}.
     * :param request: the current web request.
     * :returns: the request URI, or the raw description when it carries no ``uri=`` prefix.
     */

}
