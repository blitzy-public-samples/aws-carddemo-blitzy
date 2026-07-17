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
package com.aws.carddemo.service.rule;

/**
 * Strategy contract for a single-field validation rule, the Java abstraction of one COBOL
 * "edit" paragraph. The legacy online-edit program {@code legacy/cbl/COACTUPC.cbl} performs
 * field validation through a family of {@code 12xx-EDIT-*} paragraphs (for example
 * {@code 1215-EDIT-MANDATORY}, {@code 1225-EDIT-ALPHA-REQD}, {@code 1245-EDIT-NUM-REQD},
 * {@code 1250-EDIT-SIGNED-9V2}); each such paragraph becomes one discrete implementation of
 * this interface. Detailed rationale lives in the decision log.
 *
 * <p>Each rule accepts a human-readable field label and the field's candidate value and
 * returns a {@link ValidationResult}: {@link ValidationResult#valid()} when the value passes,
 * or {@link ValidationResult#invalid(String)} carrying the exact COBOL screen message when it
 * fails. Rules are stateless, side-effect free, and safe to share as singleton Spring beans;
 * they never throw for a validation failure (an unmet rule is reported through the returned
 * {@code ValidationResult}, not an exception) and never log a raw sensitive value.</p>
 *
 * <p>The simple, single-field edit paragraphs implement this interface directly (for example
 * {@code NumericRequiredRule}, {@code MandatoryFieldRule}, {@code YesNoRule},
 * {@code AlphaRequiredRule}, {@code AlphanumRequiredRule}, {@code AlphaOptionalRule},
 * {@code AlphanumOptionalRule}, {@code AccountIdRule}, {@code SignedAmountRule},
 * {@code UsStateCodeRule}, {@code FicoScoreRule}, {@code ExpiryMonthRule},
 * {@code ExpiryYearRule}). The composite, multi-argument edit paragraphs
 * ({@code 1260-EDIT-US-PHONE-NUM}, {@code 1265-EDIT-US-SSN}, {@code 1280-EDIT-US-STATE-ZIP-CD},
 * and the date-range check) validate several sub-fields at once, so their public signatures
 * differ; those rules ({@code UsPhoneRule}, {@code UsSsnRule}, {@code UsStateZipRule},
 * {@code DateRangeRule}) do not implement this interface but reuse the shared
 * {@code static} edit primitives declared below.</p>
 *
 * <p>This interface therefore doubles as the home of the four COBOL edit "primitives" that the
 * {@code 12xx} paragraphs repeat verbatim &mdash; the not-supplied (blank) test, the
 * {@code IS NUMERIC} test, the {@code FUNCTION NUMVAL(...) = 0} zero test, and the
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} field-label trim. Centralizing them as
 * {@code static} helpers keeps every rule DRY and behaviorally consistent while each rule
 * class stays small and independently reviewable. The interface is a foundational leaf: it
 * depends only on {@link ValidationResult} (same package) and the JDK.</p>
 */
public interface ValidationRule {

    /**
     * Validates a single field's candidate value, reproducing one COBOL edit paragraph.
     *
     * <p>{@code fieldName} is the human-readable label (the COBOL {@code WS-EDIT-VARIABLE-NAME},
     * {@code PIC X(25)} at {@code legacy/cbl/COACTUPC.cbl:L53}) that is substituted into the
     * outcome message; {@code value} is the field content exactly as entered (a fixed-width
     * buffer slice in the legacy program, so leading/trailing spaces are significant to the
     * primitive tests). Returns {@link ValidationResult#valid()} when the value satisfies the
     * rule, or {@link ValidationResult#invalid(String)} carrying the exact COBOL message when
     * it does not.</p>
     *
     * <p>Implementations never throw to signal a validation failure and never log a raw
     * sensitive value (for example an SSN, CVV, or password); the failure is conveyed solely by
     * the returned {@link ValidationResult}.</p>
     *
     * @param fieldName the human-readable field label used to build the outcome message
     *                  (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be {@code null} or padded,
     *                  in which case {@link #label(String)} normalizes it
     * @param value     the field content as entered; may be {@code null} (modeling COBOL
     *                  {@code LOW-VALUES}) or blank (modeling {@code SPACES})
     * @return {@link ValidationResult#valid()} if the value passes this rule, otherwise
     *         {@link ValidationResult#invalid(String)} with the COBOL screen message
     */
    ValidationResult validate(String fieldName, String value);

    /**
     * Reports whether a field was "not supplied", reproducing the COBOL not-supplied guard that
     * opens every {@code 12xx} edit paragraph of {@code legacy/cbl/COACTUPC.cbl} &mdash;
     * {@code EQUAL LOW-VALUES OR EQUAL SPACES OR FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}
     * (see {@code 1245-EDIT-NUM-REQD} at {@code legacy/cbl/COACTUPC.cbl:L2115,L2117,L2118}).
     *
     * <p>A {@code null} value models COBOL {@code LOW-VALUES}; an empty or all-whitespace value
     * models {@code SPACES} / a trim length of zero. {@link String#strip()} is used (rather than
     * {@link String#trim()}) so the full Unicode notion of whitespace is removed, matching the
     * intent of {@code FUNCTION TRIM}.</p>
     *
     * @param value the field content to test; may be {@code null}
     * @return {@code true} if {@code value} is {@code null}, empty, or entirely whitespace;
     *         {@code false} otherwise
     */
    static boolean isBlank(String value) {
        return value == null || value.strip().isEmpty();
    }

    /**
     * Reports whether every character of a field is an ASCII digit, reproducing the COBOL
     * {@code IS NUMERIC} class test applied to an unsigned {@code PIC 9} display item
     * (see {@code 1245-EDIT-NUM-REQD} at {@code legacy/cbl/COACTUPC.cbl:L2138}).
     *
     * <p>Because the legacy test runs over a fixed-width buffer slice, any embedded or trailing
     * space &mdash; and any sign, decimal point, or other non-digit character &mdash; makes the
     * item non-numeric; a {@code null} or empty value is likewise non-numeric. Only ASCII
     * {@code '0'}&ndash;{@code '9'} are accepted: {@link Character#isDigit(char)} is deliberately
     * avoided because it also accepts non-ASCII Unicode digits, which COBOL {@code IS NUMERIC}
     * does not. A character scan is used in preference to a regular expression for clarity and
     * predictable performance.</p>
     *
     * @param value the field content to test; may be {@code null}
     * @return {@code true} if {@code value} is non-empty and consists solely of ASCII digits
     *         {@code '0'}&ndash;{@code '9'}; {@code false} otherwise
     */
    static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a digit string is numerically zero, reproducing the COBOL
     * {@code FUNCTION NUMVAL(...) = 0} test used to reject a zero entry
     * (see {@code 1245-EDIT-NUM-REQD} at {@code legacy/cbl/COACTUPC.cbl:L2156}).
     *
     * <p>Precondition: the caller has already confirmed {@link #isAllDigits(String)} is
     * {@code true} for {@code digits}. Under that precondition a value is numerically zero if
     * and only if every character is {@code '0'}. A character scan is used rather than parsing
     * to a numeric type so that arbitrarily long fixed-width numeric fields cannot overflow.</p>
     *
     * @param digits an all-ASCII-digit string (as validated by {@link #isAllDigits(String)})
     * @return {@code true} if every character of {@code digits} is {@code '0'}; {@code false}
     *         if any character is a non-zero digit
     */
    static boolean isZeroValue(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Normalizes a field label for substitution into an outcome message, reproducing
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} as used throughout the {@code 12xx} edit
     * paragraphs of {@code legacy/cbl/COACTUPC.cbl}
     * (for example at {@code legacy/cbl/COACTUPC.cbl:L2125,L2145,L2162}).
     *
     * <p>COBOL {@code FUNCTION TRIM} removes both leading and trailing spaces; {@link String#strip()}
     * is used for the same effect over the full Unicode whitespace set. A {@code null} label
     * normalizes to the empty string so callers can build a message without a null check.</p>
     *
     * @param fieldName the raw field label (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be {@code null}
     * @return the label with leading and trailing whitespace removed, or the empty string when
     *         {@code fieldName} is {@code null}
     */
    static String label(String fieldName) {
        return fieldName == null ? "" : fieldName.strip();
    }
}
