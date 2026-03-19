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
package com.cardemo.common.exception;

/**
 * Exception indicating a field validation failure in the CardDemo application.
 *
 * <p>This exception maps directly to the field-level validation error patterns
 * used throughout the original COBOL CardDemo programs. In COBOL, validation
 * failures are tracked through a combination of per-field flag variables
 * (e.g., {@code FLG-ACCT-STATUS-NOT-OK}, {@code FLG-YEAR-NOT-OK}) and
 * descriptive error messages moved to the message area
 * ({@code WS-VALIDATION-FAIL-REASON-DESC}, {@code WS-RETURN-MSG}).</p>
 *
 * <h2>COBOL Validation Patterns Covered</h2>
 * <ul>
 *   <li><strong>CSUTLDPY.cpy</strong> &mdash; Comprehensive CCYYMMDD date
 *       validation that calls CSUTLDTC. Sets {@code WS-EDIT-DATE-FLGS} and
 *       error indicators when validation fails (year, month, day, leap year,
 *       date-of-birth checks). Each field has a named flag
 *       ({@code FLG-YEAR-NOT-OK}, {@code FLG-MONTH-NOT-OK},
 *       {@code FLG-DAY-NOT-OK}) mapping to the {@link #fieldName} field of
 *       this exception.</li>
 *   <li><strong>CBTRN02C.cbl</strong> &mdash; Batch daily transaction posting
 *       with {@code WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}) and
 *       {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}). Reject
 *       codes: 100 (INVALID CARD NUMBER FOUND / XREF not found), 101
 *       (ACCOUNT RECORD NOT FOUND), 102 (OVERLIMIT TRANSACTION), 103
 *       (TRANSACTION RECEIVED AFTER ACCT EXPIRATION).</li>
 *   <li><strong>COACTUPC.cbl</strong> &mdash; Account update field-level
 *       validation flags ({@code FLG-ACCT-STATUS-ISVALID},
 *       {@code FLG-ACCTFILTER-NOT-OK}) with EVALUATE blocks checking each
 *       field (account ID, customer ID, card number, date, status) and
 *       setting individual error messages.</li>
 *   <li><strong>COTRN02C.cbl</strong> &mdash; Transaction add validation for
 *       date format, transaction type codes, and amount checks with
 *       per-field error flags.</li>
 * </ul>
 *
 * <h2>Exception Hierarchy Position</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── ValidationException   (this class)
 * </pre>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Field-specific validation (maps to COBOL per-field flags)
 * throw new ValidationException("accountId",
 *         "Account ID must be 11 digits");
 *
 * // Batch reject scenario (maps to WS-VALIDATION-FAIL-REASON-DESC)
 * throw new ValidationException("OVERLIMIT TRANSACTION");
 *
 * // Wrapping a cause (maps to file status + validation failure)
 * throw new ValidationException("expiryDate",
 *         "Date format must be CCYYMMDD", cause);
 * }</pre>
 *
 * @see CardDemoException
 */
public class ValidationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * The name of the field that failed validation.
     *
     * <p>Maps to the individual field validation flags in the original COBOL
     * programs, such as {@code FLG-ACCT-STATUS-NOT-OK},
     * {@code FLG-YEAR-NOT-OK}, and {@code WS-VALIDATION-FAIL-REASON}. This
     * value identifies which specific input field triggered the validation
     * error.</p>
     *
     * <p>May be {@code null} when the exception represents a general
     * validation error not tied to a specific field (e.g., batch reject
     * scenarios where the entire transaction is rejected with a descriptive
     * message such as "OVERLIMIT TRANSACTION").</p>
     */
    private final String fieldName;

    /**
     * A descriptive message explaining why validation failed.
     *
     * <p>Maps to the COBOL error description fields such as
     * {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}) in
     * CBTRN02C.cbl, {@code WS-RETURN-MSG} in CSUTLDPY.cpy, and
     * {@code WS-MESSAGE} in the online CICS programs. Contains a
     * human-readable explanation of the validation rule that was violated.</p>
     */
    private final String validationMessage;

    /**
     * Constructs a {@code ValidationException} for a specific field validation failure.
     *
     * <p>This is the primary constructor used when a named input field fails
     * validation. It corresponds to the COBOL pattern where a specific flag
     * (e.g., {@code SET FLG-YEAR-NOT-OK TO TRUE}) is set and an error
     * message is built via STRING concatenation (e.g.,
     * {@code STRING variable-name ' : Year must be supplied.'}).</p>
     *
     * <p>The parent exception message is formatted as:
     * {@code "Validation failed for field '<fieldName>': <validationMessage>"}</p>
     *
     * @param fieldName         the name of the field that failed validation;
     *                          corresponds to COBOL field validation flag names
     * @param validationMessage a descriptive explanation of the validation error;
     *                          corresponds to {@code WS-VALIDATION-FAIL-REASON-DESC}
     *                          or {@code WS-RETURN-MSG} in COBOL
     */
    public ValidationException(String fieldName, String validationMessage) {
        super("Validation failed for field '" + fieldName + "': " + validationMessage);
        this.fieldName = fieldName;
        this.validationMessage = validationMessage;
    }

    /**
     * Constructs a {@code ValidationException} for a specific field validation failure
     * with an underlying cause.
     *
     * <p>Use this constructor when a validation check fails due to an underlying
     * exception (e.g., a {@code NumberFormatException} when parsing a numeric field,
     * or a {@code DateTimeParseException} during date validation). This preserves
     * the original exception chain while adding CardDemo-specific validation context.</p>
     *
     * <p>In COBOL, this corresponds to patterns where file I/O status codes trigger
     * validation failures — for example, a failed XREF file READ (status '23')
     * leading to reject code 100 ("INVALID CARD NUMBER FOUND").</p>
     *
     * @param fieldName         the name of the field that failed validation
     * @param validationMessage a descriptive explanation of the validation error
     * @param cause             the underlying cause of the validation failure;
     *                          may be retrieved later by {@link #getCause()}
     */
    public ValidationException(String fieldName, String validationMessage, Throwable cause) {
        super("Validation failed for field '" + fieldName + "': " + validationMessage, cause);
        this.fieldName = fieldName;
        this.validationMessage = validationMessage;
    }

    /**
     * Constructs a {@code ValidationException} with only a descriptive message.
     *
     * <p>This convenience constructor is used for general validation errors that
     * are not tied to a specific named field. In this case, {@link #getFieldName()}
     * returns {@code null}.</p>
     *
     * <p>This maps to the COBOL batch reject pattern in CBTRN02C.cbl where
     * {@code WS-VALIDATION-FAIL-REASON-DESC} contains the full error description
     * without a separate field identifier — for example:
     * {@code "OVERLIMIT TRANSACTION"} (reject code 102) or
     * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"} (reject code 103).</p>
     *
     * @param message a human-readable description of the validation error;
     *                stored as both the parent exception message and the
     *                {@link #validationMessage}
     */
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.validationMessage = message;
    }

    /**
     * Returns the name of the field that failed validation.
     *
     * <p>Corresponds to the COBOL field validation flag names such as
     * {@code FLG-ACCT-STATUS-NOT-OK} (mapped to "accountStatus"),
     * {@code FLG-YEAR-NOT-OK} (mapped to "year"), or
     * {@code WS-VALIDATION-FAIL-REASON} (mapped to the field being
     * validated in the batch context).</p>
     *
     * @return the field name that failed validation, or {@code null} if this
     *         exception represents a general validation error not tied to a
     *         specific field
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns the descriptive validation error message.
     *
     * <p>Corresponds to the COBOL {@code WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code PIC X(76)}) in batch programs, {@code WS-RETURN-MSG} in the
     * date validation copybook (CSUTLDPY.cpy), and {@code WS-MESSAGE} in
     * the online CICS programs. Contains a human-readable explanation of
     * the specific validation rule that was violated.</p>
     *
     * @return the validation error description; never {@code null}
     */
    public String getValidationMessage() {
        return validationMessage;
    }
}
