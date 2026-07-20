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

import org.springframework.stereotype.Component;

/**
 * Validates the card expiry <em>month</em> field, the Java migration of the COBOL edit
 * paragraph {@code 1250-EDIT-EXPIRY-MON} in {@code legacy/cbl/COCRDUPC.cbl}
 * (source {@code app/cbl/COCRDUPC.cbl}, lines L877&ndash;L912) of the CardDemo Card-Update
 * transaction (CCUP). Detailed rationale lives in the decision log.
 *
 * <p>The legacy paragraph edits {@code CCUP-NEW-EXPMON} ({@code PIC X(2)}) as follows,
 * preserving its exact evaluation order:</p>
 * <pre>
 *     1250-EDIT-EXPIRY-MON.
 *         SET FLG-CARDEXPMON-NOT-OK TO TRUE
 *         IF CCUP-NEW-EXPMON EQUAL LOW-VALUES OR SPACES OR ZEROS
 *            SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE   GO TO EXIT
 *         END-IF
 *         MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK     (PIC X(2) redefined PIC 9(2))
 *         IF VALID-MONTH                               (88-level: VALUES 1 THRU 12)
 *            SET FLG-CARDEXPMON-ISVALID TO TRUE
 *         ELSE
 *            SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
 *         END-IF.
 * </pre>
 *
 * <p>Behavioral parity is therefore:</p>
 * <ul>
 *   <li><strong>Blank</strong> &mdash; {@code null} (COBOL {@code LOW-VALUES}), all-whitespace
 *       (COBOL {@code SPACES}), or an all-zero digit string (COBOL {@code ZEROS}, so {@code "00"}
 *       counts as blank) &rarr; invalid.</li>
 *   <li><strong>Non-numeric</strong> &mdash; any value that is not a run of ASCII digits fails
 *       the implicit numeric class test behind the {@code PIC 9(2)} redefinition and its
 *       {@code VALID-MONTH} 88-level &rarr; invalid.</li>
 *   <li><strong>Out of range</strong> &mdash; a numeric value outside {@code 1}&ndash;{@code 12}
 *       &rarr; invalid.</li>
 *   <li>Otherwise (a two-digit value {@code 01}&ndash;{@code 12}) &rarr; valid.</li>
 * </ul>
 *
 * <p>Every failure path emits the <em>same</em> fixed message taken verbatim from the COBOL
 * 88-level constant {@code CARD-EXPIRY-MONTH-NOT-VALID} at {@code legacy/cbl/COCRDUPC.cbl:L197-L198}:
 * {@value #MESSAGE}. Unlike the field-labelled edits of {@code COACTUPC}, this message is a fixed
 * literal and does <em>not</em> substitute a field name; the {@code fieldName} argument of
 * {@link #validate(String, String)} is consequently accepted (to honor the {@link ValidationRule}
 * contract) but not used.</p>
 *
 * <p>The rule is stateless (its only state is the immutable {@link #MESSAGE} constant),
 * side-effect free, and therefore safe to share as a singleton Spring bean. In keeping with the
 * {@link ValidationRule} contract it never throws to signal a validation failure &mdash; the
 * outcome is conveyed solely by the returned {@link ValidationResult} &mdash; and it depends only
 * on {@link ValidationRule} and {@link ValidationResult} from the same package.</p>
 */
@Component
public final class ExpiryMonthRule implements ValidationRule {

    /**
     * The fixed screen message emitted for every failure of this rule, taken verbatim from the
     * COBOL 88-level {@code CARD-EXPIRY-MONTH-NOT-VALID} constant at
     * {@code legacy/cbl/COCRDUPC.cbl:L197-L198}. It is a fixed literal and never has a field name
     * substituted into it.
     */
    private static final String MESSAGE = "Card expiry month must be between 1 and 12";

    /**
     * The inclusive lower bound of a valid month, from the COBOL {@code 88 VALID-MONTH VALUES 1
     * THRU 12} at {@code legacy/cbl/COCRDUPC.cbl:L95}.
     */
    private static final int MIN_MONTH = 1;

    /**
     * The inclusive upper bound of a valid month, from the COBOL {@code 88 VALID-MONTH VALUES 1
     * THRU 12} at {@code legacy/cbl/COCRDUPC.cbl:L95}.
     */
    private static final int MAX_MONTH = 12;

    /**
     * The fixed width of the {@code CCUP-NEW-EXPMON} / {@code CARD-MONTH-CHECK} field
     * ({@code PIC X(2)} / {@code PIC 9(2)} at {@code legacy/cbl/COCRDUPC.cbl:L92-L94,L311}).
     * Enforcing this bound honors the two-digit field contract and keeps the subsequent parse
     * safely within {@code int} range so the rule never throws.
     */
    private static final int FIELD_WIDTH = 2;

    /**
     * Validates a candidate card expiry month, reproducing {@code 1250-EDIT-EXPIRY-MON} of
     * {@code legacy/cbl/COCRDUPC.cbl} exactly (evaluation order preserved).
     *
     * @param fieldName the human-readable field label required by the {@link ValidationRule}
     *                  contract; intentionally unused because the COBOL failure message is a
     *                  fixed literal ({@value #MESSAGE}) that does not substitute a field name
     * @param value     the candidate month exactly as entered; may be {@code null} (modeling
     *                  COBOL {@code LOW-VALUES}) or blank (modeling {@code SPACES})
     * @return {@link ValidationResult#valid()} when {@code value} is a two-digit month in
     *         {@code 01}&ndash;{@code 12}; otherwise {@link ValidationResult#invalid(String)}
     *         carrying {@value #MESSAGE}
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        // COBOL "Not supplied" guard: IF CCUP-NEW-EXPMON EQUAL LOW-VALUES OR SPACES OR ZEROS
        // (legacy/cbl/COCRDUPC.cbl:L883-L885). null models LOW-VALUES, all-whitespace models
        // SPACES, and an all-zero digit string models ZEROS (so "00" is rejected here, matching
        // the figurative constant ZEROS against the two-byte field). isZeroValue is safe on the
        // raw value: a null short-circuits on isBlank, and any non-digit character yields false.
        if (ValidationRule.isBlank(value) || ValidationRule.isZeroValue(value)) {
            return ValidationResult.invalid(MESSAGE);
        }

        // COBOL: MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK (legacy/cbl/COCRDUPC.cbl:L896), where
        // CARD-MONTH-CHECK is PIC X(2) redefined as PIC 9(2). A non-numeric value fails the
        // implicit numeric class test behind the VALID-MONTH 88-level; isAllDigits reproduces
        // COBOL IS NUMERIC. Bounding the length to the two-digit field width keeps the value
        // within the field contract and guarantees the parse below cannot overflow int.
        String month = value.strip();
        if (!ValidationRule.isAllDigits(month) || month.length() > FIELD_WIDTH) {
            return ValidationResult.invalid(MESSAGE);
        }

        // COBOL: IF VALID-MONTH (88-level VALUES 1 THRU 12 at legacy/cbl/COCRDUPC.cbl:L95, tested
        // at L898). month is guaranteed all-ASCII-digit and at most two characters, so parseInt
        // is safe and never throws.
        int monthValue = Integer.parseInt(month);
        if (monthValue < MIN_MONTH || monthValue > MAX_MONTH) {
            return ValidationResult.invalid(MESSAGE);
        }

        // COBOL: SET FLG-CARDEXPMON-ISVALID TO TRUE (legacy/cbl/COCRDUPC.cbl:L899).
        return ValidationResult.valid();
    }
}
