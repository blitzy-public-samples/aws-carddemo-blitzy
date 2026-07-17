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
 * Composite validation rule for a United States Social Security Number, the Java reproduction of
 * the COBOL edit paragraph {@code 1265-EDIT-US-SSN} of {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl}, lines L2431&ndash;L2491). The legacy program models the
 * SSN in the {@code xxx-xx-xxxx} format as three fixed-width sub-fields
 * (see {@code legacy/cbl/COACTUPC.cbl:L117-L129} and {@code L830-L833}):
 * <ul>
 *   <li>{@code WS-EDIT-US-SSN-PART1} &mdash; first 3 digits, {@code PIC X(3)} (redefined {@code 9(3)});</li>
 *   <li>{@code WS-EDIT-US-SSN-PART2} &mdash; 4th &amp; 5th digits, {@code PIC X(2)} (redefined {@code 9(2)});</li>
 *   <li>{@code WS-EDIT-US-SSN-PART3} &mdash; last 4 digits, {@code PIC X(4)} (redefined {@code 9(4)}).</li>
 * </ul>
 * Detailed rationale lives in the decision log.
 *
 * <p><strong>Sensitive data.</strong> The SSN is sensitive. This class never logs the SSN value and
 * never places any raw SSN digit into an outcome message, exception, or {@code toString}: it holds
 * no logger and every message it produces is built solely from a <em>fixed</em> field label plus
 * <em>fixed</em> text, so no digit of the caller's input can escape through a message. The three
 * field labels are internal COBOL literals (the paragraph's {@code MOVE '...' TO
 * WS-EDIT-VARIABLE-NAME} statements), not the SSN itself.</p>
 *
 * <p><strong>Composite signature.</strong> Because the SSN is a composite of three sub-fields, this
 * rule cannot use the single-argument {@link ValidationRule#validate(String, String)} Strategy
 * signature; it therefore does <em>not</em> implement {@link ValidationRule} and instead exposes a
 * three-argument {@link #validate(String, String, String)} entry point. Unlike the other composite
 * rules it takes no caller-supplied field label, because the legacy paragraph hard-codes a distinct
 * label for each part ({@code 'SSN: First 3 chars'}, {@code 'SSN 4th &amp; 5th chars'},
 * {@code 'SSN Last 4 chars'} at {@code legacy/cbl/COACTUPC.cbl:L2439,L2469,L2481}); those labels are
 * reproduced verbatim as {@link #PART1_LABEL}, {@link #PART2_LABEL}, and {@link #PART3_LABEL}.</p>
 *
 * <p><strong>Reuse of the numeric-required edit.</strong> Each part is edited by the legacy
 * {@code PERFORM 1245-EDIT-NUM-REQD} (the supplied &rarr; numeric &rarr; non-zero edit). This class
 * mirrors that reuse by constructor-injecting {@link NumericRequiredRule} &mdash; the Java
 * reproduction of {@code 1245-EDIT-NUM-REQD} &mdash; and delegating each part's numeric-required
 * edit to it rather than re-implementing the numeric logic. Part 1 additionally applies the
 * {@code 88 INVALID-SSN-PART1} range check ({@code VALUES 0, 666, 900 THRU 999} at
 * {@code legacy/cbl/COACTUPC.cbl:L121-L123}).</p>
 *
 * <p><strong>Sequential, first-message-wins.</strong> The legacy paragraph edits the parts in order
 * (part 1 &rarr; part 2 &rarr; part 3). Each sub-edit sets {@code INPUT-ERROR} on failure but writes
 * its screen message only while the message latch is still off ({@code WS-RETURN-MSG-OFF}), so the
 * <em>first</em> failing part's message is the caller-visible outcome. This class reproduces that
 * behaviour by returning immediately on the first failing part.</p>
 *
 * <p><strong>Fixed-width digit-count parity.</strong> Each part is a fixed-width field (3 / 2 / 4
 * characters). The COBOL {@code IS NUMERIC} class test runs over the whole fixed-width slice, so a
 * short entry is space-padded and fails as "must be all numeric." (a space is not numeric). This
 * class reproduces that: after a part passes the numeric-required edit it additionally requires the
 * part's significant length to equal the expected digit count, and a mismatch yields the same
 * {@code "<label> must be all numeric."} message.</p>
 *
 * <p>The rule is stateless apart from the injected {@link NumericRequiredRule} collaborator and the
 * label constants; it mutates nothing, so the single instance is safe to share as a singleton Spring
 * bean. It depends only on {@link NumericRequiredRule} and {@link ValidationResult} (same package)
 * and the Spring stereotype annotation.</p>
 */
@Component
public final class UsSsnRule {

    /**
     * Fixed label for SSN part 1 (the first three digits), reproduced verbatim from the legacy
     * {@code MOVE 'SSN: First 3 chars' TO WS-EDIT-VARIABLE-NAME} at
     * {@code legacy/cbl/COACTUPC.cbl:L2439}. The label already contains its own colon, so the
     * numeric-required messages read, for example, {@code "SSN: First 3 chars must be supplied."}.
     */
    private static final String PART1_LABEL = "SSN: First 3 chars";

    /**
     * Fixed label for SSN part 2 (the 4th and 5th digits), reproduced verbatim from the legacy
     * {@code MOVE 'SSN 4th & 5th chars' TO WS-EDIT-VARIABLE-NAME} at
     * {@code legacy/cbl/COACTUPC.cbl:L2469}.
     */
    private static final String PART2_LABEL = "SSN 4th & 5th chars";

    /**
     * Fixed label for SSN part 3 (the last four digits), reproduced verbatim from the legacy
     * {@code MOVE 'SSN Last 4 chars' TO WS-EDIT-VARIABLE-NAME} at
     * {@code legacy/cbl/COACTUPC.cbl:L2481}.
     */
    private static final String PART3_LABEL = "SSN Last 4 chars";

    /**
     * The numeric-required edit reused for each SSN part, the Java reproduction of the legacy
     * {@code PERFORM 1245-EDIT-NUM-REQD}. Injected (never re-implemented) so the supplied /
     * {@code IS NUMERIC} / non-zero semantics stay identical to every other numeric field edit.
     */
    private final NumericRequiredRule numericRequiredRule;

    /**
     * Creates the rule with its collaborating numeric-required edit, mirroring the legacy reuse of
     * {@code 1245-EDIT-NUM-REQD}. Constructor injection is used (there is no field injection and no
     * Lombok), so the collaborator is {@code final} and the instance is fully initialized once
     * constructed.
     *
     * @param numericRequiredRule the shared numeric-required edit rule (Java reproduction of COBOL
     *                            {@code 1245-EDIT-NUM-REQD}); must not be {@code null}
     */
    public UsSsnRule(NumericRequiredRule numericRequiredRule) {
        this.numericRequiredRule = numericRequiredRule;
    }

    /**
     * Validates a United States SSN supplied as three fixed-width parts, reproducing the COBOL
     * paragraph {@code 1265-EDIT-US-SSN} ({@code legacy/cbl/COACTUPC.cbl:L2431-L2491}).
     *
     * <p>The parts are edited in order and the <em>first</em> failing part's message is returned
     * (the legacy first-message-wins latch, {@code WS-RETURN-MSG-OFF}):</p>
     * <ol>
     *   <li><strong>Part 1</strong> (3 digits): the numeric-required edit (supplied &rarr; numeric
     *       &rarr; non-zero); then, only if that passed, the {@code 88 INVALID-SSN-PART1} range
     *       check &mdash; {@code 0}, {@code 666}, or {@code 900}&ndash;{@code 999} &rarr;
     *       {@code "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"}. Value
     *       {@code 000} is caught first by the non-zero check ("must not be zero."), so the range
     *       message is only ever reached for {@code 666} and {@code 900}&ndash;{@code 999}.</li>
     *   <li><strong>Part 2</strong> (2 digits): the numeric-required edit only (the 2-digit width
     *       plus non-zero effectively enforces {@code 01}&ndash;{@code 99}).</li>
     *   <li><strong>Part 3</strong> (4 digits): the numeric-required edit only (the 4-digit width
     *       plus non-zero effectively enforces {@code 0001}&ndash;{@code 9999}).</li>
     * </ol>
     *
     * @param part1 the first three digits ({@code WS-EDIT-US-SSN-PART1}); may be {@code null}
     *              (COBOL {@code LOW-VALUES}) or blank (COBOL {@code SPACES})
     * @param part2 the 4th and 5th digits ({@code WS-EDIT-US-SSN-PART2}); may be {@code null} or blank
     * @param part3 the last four digits ({@code WS-EDIT-US-SSN-PART3}); may be {@code null} or blank
     * @return {@link ValidationResult#valid()} when every part passes its edit; otherwise
     *         {@link ValidationResult#invalid(String)} carrying the first failing part's exact
     *         COBOL screen message (never containing any SSN digit)
     */
    public ValidationResult validate(String part1, String part2, String part3) {
        ValidationResult r1 = validatePart(PART1_LABEL, part1, 3);
        if (r1.isInvalid()) {
            return r1;
        }
        // Part 1 passed the numeric edit; apply the additional 88 INVALID-SSN-PART1 range check.
        if (isInvalidSsnPart1(part1)) {
            return ValidationResult.invalid(
                PART1_LABEL + ": should not be 000, 666, or between 900 and 999");
        }
        ValidationResult r2 = validatePart(PART2_LABEL, part2, 2);
        if (r2.isInvalid()) {
            return r2;
        }
        return validatePart(PART3_LABEL, part3, 4);
    }

    /**
     * Edits a single SSN part with the reused numeric-required rule and the fixed-width digit-count
     * check. The numeric-required edit runs first ({@code PERFORM 1245-EDIT-NUM-REQD}); if it fails,
     * its message is returned unchanged. Otherwise the part must be exactly {@code digits} characters
     * long once stripped: a short (space-padded) entry fails the COBOL {@code IS NUMERIC} class test,
     * so a length mismatch yields the same {@code "<label> must be all numeric."} message.
     *
     * <p>Because the numeric-required edit rejects a {@code null} or blank value first, {@code value}
     * is guaranteed non-{@code null} by the time the length check runs, so {@code value.strip()} is
     * safe.</p>
     *
     * @param label  the fixed part label used to build the message
     * @param value  the part content as entered; may be {@code null} or blank
     * @param digits the exact number of digits the part must contain (3 for part 1, 2 for part 2,
     *               4 for part 3)
     * @return {@link ValidationResult#valid()} when the part is supplied, all-numeric, non-zero, and
     *         exactly {@code digits} long; otherwise the first failing check's message
     */
    private ValidationResult validatePart(String label, String value, int digits) {
        ValidationResult numeric = numericRequiredRule.validate(label, value);
        if (numeric.isInvalid()) {
            return numeric;
        }
        // Fixed-width field: a short entry is space-padded and is therefore not numeric in COBOL.
        if (value.strip().length() != digits) {
            return ValidationResult.invalid(label + " must be all numeric.");
        }
        return ValidationResult.valid();
    }

    /**
     * Reports whether SSN part 1 matches the COBOL {@code 88 INVALID-SSN-PART1} condition
     * ({@code VALUES 0, 666, 900 THRU 999} on {@code PIC 9(3)} at
     * {@code legacy/cbl/COACTUPC.cbl:L121-L123}).
     *
     * <p>This is only called after {@link #validatePart(String, String, int)} has confirmed part 1
     * is supplied, all ASCII digits, non-zero, and exactly three characters long, so
     * {@link Integer#parseInt(String)} on the stripped value cannot throw and cannot overflow (the
     * value is at most {@code 999}). The {@code 0} arm of the condition is therefore unreachable here
     * (the numeric edit already rejected {@code 000} with "must not be zero."); it is retained for
     * an exact, literal transcription of the 88-level.</p>
     *
     * @param part1 the first-three-digits part, already validated as three ASCII digits
     * @return {@code true} when the numeric value is {@code 0}, {@code 666}, or in
     *         {@code 900}&ndash;{@code 999}; {@code false} otherwise
     */
    private boolean isInvalidSsnPart1(String part1) {
        int p = Integer.parseInt(part1.strip());
        return p == 0 || p == 666 || (p >= 900 && p <= 999);
    }
}
