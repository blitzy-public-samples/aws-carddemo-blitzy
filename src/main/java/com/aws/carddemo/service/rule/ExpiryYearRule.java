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
 * Validates a card-expiry <em>year</em>, the Java reproduction of the COBOL edit paragraph
 * {@code 1260-EDIT-EXPIRY-YEAR} in the card-update online program
 * {@code legacy/cbl/COCRDUPC.cbl} (source {@code app/cbl/COCRDUPC.cbl}, lines L913&ndash;L947).
 * Detailed rationale lives in the decision log.
 *
 * <p>The legacy paragraph edits the {@code CCUP-NEW-EXPYEAR} field, a fixed-width
 * {@code PIC X(4)} item (declared at {@code legacy/cbl/COCRDUPC.cbl:L310}), in two ordered
 * steps that this rule preserves exactly:</p>
 * <ol>
 *   <li><strong>Not supplied.</strong> {@code IF CCUP-NEW-EXPYEAR EQUAL LOW-VALUES OR SPACES
 *       OR ZEROS} the field is rejected and the paragraph exits
 *       ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}). {@code LOW-VALUES} is modeled by a
 *       {@code null} value, {@code SPACES} by a blank/all-whitespace value (via
 *       {@link ValidationRule#isBlank(String)}), and {@code ZEROS} by an all-{@code '0'}
 *       value (via {@link ValidationRule#isZeroValue(String)}).</li>
 *   <li><strong>Valid range.</strong> The field is moved into the {@code CARD-YEAR-CHECK}
 *       work item ({@code PIC X(4)} at {@code legacy/cbl/COCRDUPC.cbl:L96}) whose numeric
 *       redefinition {@code CARD-YEAR-CHECK-N} ({@code PIC 9(4)} at
 *       {@code legacy/cbl/COCRDUPC.cbl:L97-L98}) carries the {@code 88 VALID-YEAR VALUES 1950
 *       THRU 2099} condition ({@code legacy/cbl/COCRDUPC.cbl:L99}). {@code IF VALID-YEAR} the
 *       value passes; otherwise it is rejected ({@code legacy/cbl/COCRDUPC.cbl:L932-L943}).
 *       Because {@code CARD-YEAR-CHECK} is exactly four bytes and {@code VALID-YEAR} tests its
 *       {@code PIC 9(4)} redefinition, a value passes only when it is exactly four ASCII digits
 *       (enforced by {@link ValidationRule#isAllDigits(String)} together with a length of four)
 *       naming a year in the inclusive range {@code 1950}&ndash;{@code 2099}. A two-digit entry
 *       such as {@code "50"} therefore fails, matching the fixed field width.</li>
 * </ol>
 *
 * <p>Both failure paths latch the same fixed screen message &mdash; the {@code 88}-level
 * constant {@code CARD-EXPIRY-YEAR-NOT-VALID VALUE 'Invalid card expiry year'} at
 * {@code legacy/cbl/COCRDUPC.cbl:L199-L200}. Unlike several sibling edit paragraphs, this
 * message is a fixed literal with no field-label substitution, so the {@code fieldName}
 * argument is accepted (to honor the {@link ValidationRule} contract) but does not influence
 * the emitted message.</p>
 *
 * <p>The component is stateless apart from its compile-time constants and holds no mutable
 * fields, so the single Spring-managed singleton instance is safe to share across threads. In
 * keeping with the {@link ValidationRule} contract it never throws to signal a validation
 * failure &mdash; every outcome is conveyed through the returned {@link ValidationResult} &mdash;
 * and it depends only on {@link ValidationRule} and {@link ValidationResult} from the same
 * package plus the Spring stereotype annotation.</p>
 */
@Component
public final class ExpiryYearRule implements ValidationRule {

    /**
     * The fixed screen message emitted on every failure path, reproducing the COBOL
     * {@code 88 CARD-EXPIRY-YEAR-NOT-VALID VALUE 'Invalid card expiry year'} constant at
     * {@code legacy/cbl/COCRDUPC.cbl:L199-L200}. It is a fixed literal with no field-label
     * substitution.
     */
    private static final String MESSAGE = "Invalid card expiry year";

    /**
     * Inclusive lower bound of the valid expiry year, the {@code 1950} in the COBOL
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099} condition
     * ({@code legacy/cbl/COCRDUPC.cbl:L99}).
     */
    private static final int MIN_YEAR = 1950;

    /**
     * Inclusive upper bound of the valid expiry year, the {@code 2099} in the COBOL
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099} condition
     * ({@code legacy/cbl/COCRDUPC.cbl:L99}).
     */
    private static final int MAX_YEAR = 2099;

    /**
     * Validates a candidate card-expiry year, reproducing {@code 1260-EDIT-EXPIRY-YEAR}
     * ({@code legacy/cbl/COCRDUPC.cbl:L913-L947}) in the exact evaluation order of the legacy
     * paragraph.
     *
     * <p>The checks are applied in the following order, each returning immediately on failure
     * so the first unmet condition wins (mirroring the COBOL {@code GO TO
     * 1260-EDIT-EXPIRY-YEAR-EXIT} short-circuits):</p>
     * <ol>
     *   <li>Not supplied &mdash; {@code null}/blank ({@code LOW-VALUES}/{@code SPACES}) or an
     *       all-zero value ({@code ZEROS}) &mdash; is rejected
     *       ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}).</li>
     *   <li>A value that is not exactly four ASCII digits is rejected: the four-digit width
     *       reproduces the {@code PIC 9(4)} redefinition of {@code CARD-YEAR-CHECK} and the
     *       digit test reproduces its numeric interpretation
     *       ({@code legacy/cbl/COCRDUPC.cbl:L96-L98,L932}).</li>
     *   <li>A four-digit year outside {@code 1950}&ndash;{@code 2099} is rejected; a year inside
     *       the range passes, reproducing {@code 88 VALID-YEAR}
     *       ({@code legacy/cbl/COCRDUPC.cbl:L99,L934}).</li>
     * </ol>
     *
     * <p>Every failure returns {@link ValidationResult#invalid(String)} carrying the fixed
     * {@link #MESSAGE}; a passing value returns {@link ValidationResult#valid()}. The
     * {@code fieldName} argument is part of the {@link ValidationRule} contract but is unused
     * here because the COBOL message is a fixed literal without label substitution.</p>
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME});
     *                  accepted to honor the {@link ValidationRule} contract but not used, as
     *                  this rule's message is a fixed literal
     * @param value     the candidate expiry-year value as entered ({@code CCUP-NEW-EXPYEAR});
     *                  may be {@code null} (modeling COBOL {@code LOW-VALUES}) or blank
     *                  (modeling {@code SPACES})
     * @return {@link ValidationResult#valid()} when {@code value} is exactly four digits naming
     *         a year in {@code 1950}&ndash;{@code 2099}; otherwise
     *         {@link ValidationResult#invalid(String)} carrying {@link #MESSAGE}
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        // Step 1 (COCRDUPC L916-L924): reject LOW-VALUES/SPACES (blank) or ZEROS (all '0').
        if (ValidationRule.isBlank(value) || ValidationRule.isZeroValue(value)) {
            return ValidationResult.invalid(MESSAGE);
        }
        // Step 2 (COCRDUPC L96-L98,L932): the MOVE into CARD-YEAR-CHECK / PIC 9(4) redefinition
        // only yields a valid numeric year when the field is exactly four ASCII digits; a
        // non-numeric entry or a wrong-width value (for example "50") is rejected here.
        String year = value.strip();
        if (!ValidationRule.isAllDigits(year) || year.length() != 4) {
            return ValidationResult.invalid(MESSAGE);
        }
        // Step 3 (COCRDUPC L99,L934): reproduce 88 VALID-YEAR VALUES 1950 THRU 2099.
        int y = Integer.parseInt(year);
        if (y < MIN_YEAR || y > MAX_YEAR) {
            return ValidationResult.invalid(MESSAGE);
        }
        return ValidationResult.valid();
    }
}
