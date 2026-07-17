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
 * Account-id edit rule &mdash; the Java migration of the account-number validation that appears
 * in two legacy online programs with <em>identical logic</em> but <em>two distinct screen
 * messages</em>:
 * <ul>
 *   <li>{@code legacy/cbl/COACTUPC.cbl} paragraph {@code 1210-EDIT-ACCOUNT} (L1783&ndash;L1822)
 *       &mdash; the account-<strong>update</strong> path.</li>
 *   <li>{@code legacy/cbl/COACTVWC.cbl} paragraph {@code 2210-EDIT-ACCOUNT} (L649&ndash;L685)
 *       &mdash; the account-<strong>filter</strong> path shared by the account view, card list,
 *       and card-select screens.</li>
 * </ul>
 *
 * <p>In the legacy programs the account key is the field {@code CC-ACCT-ID}
 * ({@code PIC X(11)}) with a numeric redefinition {@code CC-ACCT-ID-N} ({@code PIC 9(11)}). Both
 * paragraphs perform exactly three steps:</p>
 * <ol>
 *   <li><strong>Not supplied / blank</strong> ({@code CC-ACCT-ID EQUAL LOW-VALUES OR SPACES}):
 *       the program latches a soft "prompt for account" / "no search criteria" state
 *       ({@code WS-PROMPT-FOR-ACCT} / {@code FLG-ACCTFILTER-BLANK}), moves zeroes to the working
 *       key, and exits <em>without</em> emitting the format-error message. Blank is deliberately
 *       <em>not</em> this rule's error; owning that soft prompt state is the responsibility of the
 *       calling service (for example {@code AccountService} / {@code CardService}). This rule
 *       therefore reports a blank value as {@link ValidationResult#valid()}.</li>
 *   <li><strong>Invalid format</strong> ({@code CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID-N EQUAL
 *       ZEROS}): the program emits the format-error message and moves zeroes to the working key.
 *       Because {@code CC-ACCT-ID} is the {@code PIC X(11)} field and {@code CC-ACCT-ID-N} is its
 *       {@code PIC 9(11)} numeric redefinition, this test reduces to "the eleven-character field
 *       is all digits <em>and</em> its numeric value is non-zero".</li>
 *   <li><strong>Valid</strong>: the account id is accepted and moved to the working key.</li>
 * </ol>
 *
 * <p><strong>Message parity (verbatim contracts &mdash; do not normalize).</strong> The two
 * programs use two different literals, both preserved byte-for-byte:</p>
 * <ul>
 *   <li>Filter path (COACTVWC {@code 2210} at L672): {@value #FILTER_MESSAGE}. This literal
 *       contains a legacy typo &mdash; there are <em>two</em> spaces between {@code must} and
 *       {@code be}. The double space is intentional and must be reproduced exactly; it is
 *       <em>not</em> a defect to be "fixed" here.</li>
 *   <li>Update path (COACTUPC {@code 1210} at L1806&ndash;L1808, two concatenated literals):
 *       {@value #UPDATE_MESSAGE}.</li>
 * </ul>
 *
 * <p><strong>Fixed-width input contract.</strong> The account id is an eleven-digit fixed-width
 * key and COBOL zero-fills numeric map fields, so callers must present the value already
 * normalized to eleven characters &mdash; the raw eleven-character screen field, or a logical
 * numeric value left-padded with zeroes (for example {@code String.format("%011d", acctId)}).
 * A shorter or longer string fails the length check and is reported as an invalid format, exactly
 * as the legacy {@code "Not 11 characters"} comment intends.</p>
 *
 * <p>The rule is stateless and side-effect free (its only members are the {@code static final}
 * constants below), so it is safe to share as a singleton Spring bean and across threads. It
 * never throws to signal a validation failure &mdash; every outcome is conveyed through the
 * returned {@link ValidationResult} &mdash; and it reuses the shared COBOL-edit primitives
 * declared on {@link ValidationRule} ({@link ValidationRule#isBlank(String)},
 * {@link ValidationRule#isAllDigits(String)}, {@link ValidationRule#isZeroValue(String)}) rather
 * than re-implementing them. Detailed rationale lives in the decision log.</p>
 *
 * @see ValidationRule
 * @see ValidationResult
 */
@Component
public final class AccountIdRule implements ValidationRule {

    /**
     * The fixed width of the account-id key ({@code CC-ACCT-ID}, {@code PIC X(11)} /
     * {@code CC-ACCT-ID-N}, {@code PIC 9(11)}). A candidate value must be exactly this many
     * characters to be a well-formed account id; this reproduces the legacy
     * {@code "Not 11 characters"} intent.
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Filter-path screen message from {@code legacy/cbl/COACTVWC.cbl} {@code 2210-EDIT-ACCOUNT}
     * (L672). Reproduced verbatim, including the legacy typo of a <em>double</em> space between
     * {@code must} and {@code be}; do not collapse it to a single space.
     */
    // verbatim legacy text incl. double space between "must" and "be"
    private static final String FILTER_MESSAGE = "Account Filter must  be a non-zero 11 digit number";

    /**
     * Update-path screen message from {@code legacy/cbl/COACTUPC.cbl} {@code 1210-EDIT-ACCOUNT}
     * (L1806&ndash;L1808, two literals concatenated {@code DELIMITED BY SIZE}). Reproduced
     * verbatim.
     */
    private static final String UPDATE_MESSAGE = "Account Number if supplied must be a 11 digit Non-Zero Number";

    /**
     * Validates an account id using the <strong>filter</strong>-path semantics (the common path
     * shared by the account view, card list, and card-select screens), honoring the
     * {@link ValidationRule} strategy contract. The {@code fieldName} label is intentionally
     * unused because the legacy filter message is a fixed literal that does not embed a field
     * name; the parameter is retained to satisfy the interface.
     *
     * @param fieldName the human-readable field label (unused here; the COBOL filter message is a
     *                  fixed literal). May be {@code null}.
     * @param value     the candidate account id, expected already normalized to eleven characters
     *                  (see the class Javadoc); may be {@code null} (COBOL {@code LOW-VALUES}) or
     *                  blank (COBOL {@code SPACES})
     * @return {@link ValidationResult#valid()} when the value is blank (soft prompt state) or is a
     *         valid eleven-digit non-zero account id; otherwise
     *         {@link ValidationResult#invalid(String)} carrying {@link #FILTER_MESSAGE}
     * @see #validateAccountFilter(String)
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        return validateAccountFilter(value);
    }

    /**
     * Validates an account id using the <strong>filter</strong>-path message from COACTVWC
     * {@code 2210-EDIT-ACCOUNT}. A blank value is treated as {@link ValidationResult#valid()}
     * because blank models the soft "no search criteria" / prompt state that the legacy program
     * hands back to the caller rather than a format error (see the class Javadoc).
     *
     * @param value the candidate account id, expected already normalized to eleven characters;
     *              may be {@code null} or blank
     * @return {@link ValidationResult#valid()} when blank or a valid eleven-digit non-zero id;
     *         otherwise {@link ValidationResult#invalid(String)} with {@link #FILTER_MESSAGE}
     */
    public ValidationResult validateAccountFilter(String value) {
        if (ValidationRule.isBlank(value)) {
            // blank filter = soft "no search criteria"/prompt state, owned by the calling service
            return ValidationResult.valid();
        }
        return isElevenDigitNonZero(value)
                ? ValidationResult.valid()
                : ValidationResult.invalid(FILTER_MESSAGE);
    }

    /**
     * Validates an account id using the <strong>update</strong>-path message from COACTUPC
     * {@code 1210-EDIT-ACCOUNT}. A blank value is treated as {@link ValidationResult#valid()}
     * because blank models the soft "prompt for account" state that the legacy program hands back
     * to the caller rather than a format error (see the class Javadoc).
     *
     * @param value the candidate account id, expected already normalized to eleven characters;
     *              may be {@code null} or blank
     * @return {@link ValidationResult#valid()} when blank or a valid eleven-digit non-zero id;
     *         otherwise {@link ValidationResult#invalid(String)} with {@link #UPDATE_MESSAGE}
     */
    public ValidationResult validateAccountNumber(String value) {
        if (ValidationRule.isBlank(value)) {
            // blank = prompt-for-account state, owned by the calling service
            return ValidationResult.valid();
        }
        return isElevenDigitNonZero(value)
                ? ValidationResult.valid()
                : ValidationResult.invalid(UPDATE_MESSAGE);
    }

    /**
     * Reports whether {@code value} is a well-formed, non-zero eleven-digit account id,
     * reproducing the legacy format test {@code CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID-N EQUAL
     * ZEROS} evaluated over the {@code PIC X(11)} field. A value passes only when it is exactly
     * eleven characters long, consists solely of ASCII digits, and is not entirely zeroes.
     *
     * @param value the candidate account id; may be {@code null}
     * @return {@code true} if {@code value} is a non-{@code null}, exactly eleven-character,
     *         all-ASCII-digit string whose numeric value is non-zero; {@code false} otherwise
     */
    public boolean isElevenDigitNonZero(String value) {
        if (value == null) {
            return false;
        }
        if (value.length() != ACCOUNT_ID_LENGTH) {
            return false;
        }
        return ValidationRule.isAllDigits(value) && !ValidationRule.isZeroValue(value);
    }
}
