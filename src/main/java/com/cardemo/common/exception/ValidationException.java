/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.common.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exception indicating one or more field validation errors in the CardDemo application.
 *
 * <p>This exception maps to the BMS map field validation logic used throughout
 * the CICS online programs. In COBOL, BMS map fields have attributes
 * (DFHBMSCA) that control highlight, color, and protection — when validation
 * fails, the COBOL program sets error attributes and moves an error message
 * to the message area (CSMSG01Y.cpy). This exception encapsulates those
 * field-level validation errors for the Java service layer.</p>
 *
 * <h2>COBOL Validation Patterns Covered</h2>
 * <ul>
 *   <li><strong>Numeric field validation</strong> — {@code IF NOT NUMERIC}
 *       checks on account IDs, card numbers, amounts, and dates across all
 *       online programs (COACTUPC, COTRN02C, etc.)</li>
 *   <li><strong>Date validation</strong> — CCYYMMDD format checks via
 *       CSUTLDPY.cpy and CSUTLDTC.cbl subroutine (year, month, day,
 *       day-month combination, leap year, future-date-of-birth)</li>
 *   <li><strong>Required field validation</strong> — blank/spaces checks on
 *       mandatory BMS map fields before VSAM I/O operations</li>
 *   <li><strong>Length validation</strong> — field length checks matching
 *       COBOL PIC X(n) specifications</li>
 *   <li><strong>Range validation</strong> — value range checks
 *       (e.g., transaction type codes, category codes, account status)</li>
 *   <li><strong>Cross-field validation</strong> — card expiry date must be
 *       in the future, credit limit must be positive, etc.</li>
 * </ul>
 *
 * <h2>Exception Hierarchy Position</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── ValidationException   (this class)
 * </pre>
 *
 * @see com.cardemo.common.exception.CardDemoException
 * @see com.cardemo.common.validation.FieldValidator
 */
public class ValidationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * The name of the field that failed validation.
     * Corresponds to the COBOL BMS map field name (e.g., "ACCTIDI", "CARDNUMI",
     * "TRNAMTI"). May be {@code null} if the exception represents multiple
     * field errors (use {@link #getFieldErrors()} in that case).
     */
    private final String fieldName;

    /**
     * The invalid value that triggered the validation failure.
     * May be {@code null} if the value is sensitive (e.g., password) or
     * if this exception represents multiple field errors.
     */
    private final String rejectedValue;

    /**
     * List of individual field-level validation errors when multiple
     * fields fail simultaneously. Each entry is a human-readable
     * description of one field error (e.g., "Account ID: must be numeric").
     * Declared as {@code ArrayList} (not {@code List}) to satisfy Java
     * serialization requirements for this {@code Serializable} exception class.
     */
    private final ArrayList<String> fieldErrors;

    /**
     * Constructs a {@code ValidationException} for a single field validation failure.
     *
     * @param fieldName     the name of the field that failed validation
     * @param rejectedValue the value that was rejected (may be null for sensitive fields)
     * @param message       a human-readable description of the validation error
     */
    public ValidationException(String fieldName, String rejectedValue, String message) {
        super(message);
        this.fieldName = fieldName;
        this.rejectedValue = rejectedValue;
        this.fieldErrors = new ArrayList<>();
    }

    /**
     * Constructs a {@code ValidationException} for multiple field validation failures.
     *
     * @param fieldErrors list of field-level error descriptions
     * @param message     a summary message describing the overall validation failure
     */
    public ValidationException(List<String> fieldErrors, String message) {
        super(message);
        this.fieldName = null;
        this.rejectedValue = null;
        this.fieldErrors = fieldErrors != null
                ? new ArrayList<>(fieldErrors)
                : new ArrayList<>();
    }

    /**
     * Constructs a {@code ValidationException} with only a detail message.
     *
     * @param message a human-readable description of the validation error
     */
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.rejectedValue = null;
        this.fieldErrors = new ArrayList<>();
    }

    /**
     * Returns the name of the field that failed validation.
     *
     * @return the field name, or {@code null} if not applicable
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns the value that was rejected by validation.
     *
     * @return the rejected value, or {@code null} if not available or sensitive
     */
    public String getRejectedValue() {
        return rejectedValue;
    }

    /**
     * Returns the list of field-level validation errors.
     *
     * @return an unmodifiable list of error descriptions; empty if this
     *         exception represents a single-field error (use {@link #getFieldName()})
     */
    public List<String> getFieldErrors() {
        return Collections.unmodifiableList(fieldErrors);
    }

    /**
     * Indicates whether this exception contains multiple field errors.
     *
     * @return {@code true} if there are multiple field errors, {@code false} otherwise
     */
    public boolean hasMultipleErrors() {
        return !fieldErrors.isEmpty();
    }
}
