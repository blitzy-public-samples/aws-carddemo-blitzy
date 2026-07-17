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
package com.aws.carddemo.account.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unchecked exception raised when an account request violates a business rule.
 *
 * <p>This exception is thrown by the service-layer validation collaborators
 * ({@code AccountValidator} and {@code AccountService}) whenever inbound data
 * fails one of the edits migrated from the legacy CardDemo account-update
 * program {@code COACTUPC}. Representative failures include:</p>
 * <ul>
 *   <li>Active status that is neither {@code Y} nor {@code N}
 *       (legacy paragraph {@code 1220-EDIT-YESNO}).</li>
 *   <li>Monetary amount outside the signed range
 *       &plusmn;9,999,999,999.99, or carrying more than two fraction digits
 *       (a scale greater than {@code 2}). A value with a scale of {@code 0},
 *       {@code 1}, or {@code 2} is accepted and normalized to scale {@code 2}
 *       (legacy paragraph {@code 1250-EDIT-SIGNED-9V2}).</li>
 *   <li>Invalid date failing the month, day, leap-year or century checks
 *       (legacy date edits reimplemented with {@code java.time}).</li>
 *   <li>Account identifier that is not an 11-digit non-zero number
 *       (legacy paragraph {@code 1210-EDIT-ACCOUNT}).</li>
 * </ul>
 *
 * <p>The account identifier is supplied <em>solely</em> through the URL path
 * variable of {@code PUT /api/v1/accounts/{accountId}}; the update request body
 * intentionally omits {@code accountId} (and {@code groupId}), so there is no
 * body identifier to reconcile against the path and therefore no path/body
 * mismatch condition.</p>
 *
 * <p>This type does <em>not</em> perform any of the checks itself; it merely
 * carries the failure message and, optionally, a structured collection of
 * field&rarr;message details that the caller has already assembled. A single
 * summary message is used for scalar failures, while the optional
 * {@code fieldErrors} map is used to report multiple field-level problems in a
 * single response.</p>
 *
 * <p><strong>Sanitization contract.</strong> Consistent with the legacy edits
 * &mdash; which build their text from the offending field <em>name</em>
 * (COBOL {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}) and never from the
 * value &mdash; and with the security requirements of the migration, every
 * message routed through this exception MUST reference the field or condition
 * only. Messages MUST NEVER echo the raw monetary value, balance, credit
 * limit, or a full card/account number. Because this class stores exactly the
 * strings its callers supply, it never fabricates or embeds such values.</p>
 *
 * <p>{@code GlobalExceptionHandler} maps this exception to an
 * <strong>HTTP 400 (Bad Request)</strong> response, copying
 * {@link #getFieldErrors()} into the structured error body when present.</p>
 */
public class ValidationException extends RuntimeException {

    /**
     * Serialization version identifier for this exception type.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Optional field&rarr;message details describing individual validation
     * failures. Stored as an unmodifiable, insertion-ordered copy when the
     * caller supplies a non-empty map, and {@code null} for a single-message
     * (scalar) failure.
     */
    private final Map<String, String> fieldErrors;

    /**
     * Creates a validation exception carrying a single summary message and no
     * structured field detail.
     *
     * <p>The supplied message MUST describe the field or condition that failed
     * and MUST NOT contain the offending value (see the sanitization contract
     * in the class documentation).</p>
     *
     * @param message human-readable, sanitized description of the failure
     */
    public ValidationException(String message) {
        this(message, null);
    }

    /**
     * Creates a validation exception carrying a summary message and an optional
     * collection of field-level failure details.
     *
     * <p>When {@code fieldErrors} is non-{@code null} and non-empty, an
     * unmodifiable, insertion-ordered defensive copy is retained so that the
     * details cannot be mutated after construction and are reported in a
     * stable order. When {@code fieldErrors} is {@code null} or empty, the
     * stored detail collection is {@code null}, denoting a single-message
     * failure.</p>
     *
     * <p>Both the summary message and every value in {@code fieldErrors} MUST
     * reference the field or condition only and MUST NOT echo the raw value
     * (see the sanitization contract in the class documentation).</p>
     *
     * @param message     human-readable, sanitized description of the failure
     * @param fieldErrors optional field&rarr;message detail map; may be
     *                    {@code null} or empty
     */
    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = (fieldErrors == null || fieldErrors.isEmpty())
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /**
     * Returns the optional field&rarr;message validation details.
     *
     * <p>{@code GlobalExceptionHandler} copies the returned map into the
     * {@code ApiError.fieldErrors} of the rendered HTTP 400 response.</p>
     *
     * @return an unmodifiable, insertion-ordered map of field&rarr;message
     *         details, or {@code null} when this is a single-message failure
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
