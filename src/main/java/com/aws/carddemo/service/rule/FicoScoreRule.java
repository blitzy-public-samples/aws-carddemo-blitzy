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
 * FICO-score edit rule &mdash; the Java migration of the COBOL FICO-score validation of the
 * Account-Update transaction (CAUP) in {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl}). Detailed rationale lives in the decision log.
 *
 * <p>In the legacy program the FICO field {@code ACUP-NEW-CUST-FICO-SCORE} is {@code PIC 9(03)}
 * (a three-digit unsigned number) with the 88-level range condition
 * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}
 * ({@code legacy/cbl/COACTUPC.cbl:L845-L849}). The caller edits the field in <strong>two ordered
 * steps</strong> ({@code legacy/cbl/COACTUPC.cbl:L1545-L1556}):</p>
 * <pre>
 *     MOVE 'FICO Score'  TO WS-EDIT-VARIABLE-NAME
 *     PERFORM 1245-EDIT-NUM-REQD          (numeric-required: supplied -&gt; numeric -&gt; non-zero)
 *     IF FLG-FICO-SCORE-ISVALID           (only when the numeric pre-edit passed)
 *        PERFORM 1275-EDIT-FICO-SCORE     (the 300-850 range check)
 *     END-IF
 * </pre>
 * <p>and {@code 1275-EDIT-FICO-SCORE} itself ({@code legacy/cbl/COACTUPC.cbl:L2514-L2533}) is:</p>
 * <pre>
 *     1275-EDIT-FICO-SCORE.
 *         IF FICO-RANGE-IS-VALID
 *            CONTINUE
 *         ELSE
 *            SET INPUT-ERROR TO TRUE
 *            IF WS-RETURN-MSG-OFF
 *               STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
 *                      ': should be between 300 and 850'
 *                      DELIMITED BY SIZE INTO WS-RETURN-MSG
 *            END-IF
 *         END-IF.
 * </pre>
 *
 * <p>This component reproduces the <em>combined</em> two-step edit as a single rule, preserving the
 * exact evaluation order and the verbatim screen messages:</p>
 * <ol>
 *   <li><strong>Numeric-required pre-edit first.</strong> The {@code 1245} checks run before the
 *       range check; if any fails, that paragraph's message is returned <em>unchanged</em> &mdash;
 *       one of {@code "<field> must be supplied."} (blank), {@code "<field> must be all numeric."}
 *       (non-numeric), or {@code "<field> must not be zero."} (zero, for example {@code "000"}).</li>
 *   <li><strong>Range check only if the numeric pre-edit passed.</strong> A value outside the
 *       inclusive range {@value #MIN_FICO}&ndash;{@value #MAX_FICO} yields
 *       {@code "<field>: should be between 300 and 850"} (note the leading colon-space of the
 *       COBOL literal at {@code legacy/cbl/COACTUPC.cbl:L2523}).</li>
 *   <li>Otherwise the field is valid.</li>
 * </ol>
 * <p>The {@code <field>} token is {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} &mdash; the
 * caller-supplied label (normally {@code "FICO Score"}) with leading/trailing spaces removed &mdash;
 * reproduced through {@link ValidationRule#label(String)}. Reordering the two steps would change
 * which message a value receives (for example, {@code "000"} must report the {@code 1245}
 * "must not be zero" message, never the range message), so the order is a behavioral contract and is
 * preserved exactly.</p>
 *
 * <p><strong>Reuse via constructor injection (mirrors {@code PERFORM 1245-EDIT-NUM-REQD}).</strong>
 * Rather than re-implementing the supplied &rarr; numeric &rarr; non-zero logic, this rule
 * constructor-injects {@link NumericRequiredRule} (itself the Java migration of
 * {@code 1245-EDIT-NUM-REQD}) and delegates the numeric pre-check to it, exactly as the COBOL caller
 * performs the shared paragraph. This keeps the numeric semantics single-sourced and behaviorally
 * consistent with every other mandatory-numeric field.</p>
 *
 * <p>The rule is stateless apart from the injected collaborator and the {@code static final}
 * constants below, side-effect free, and therefore safe to share as a singleton Spring bean and
 * across threads. In keeping with the {@link ValidationRule} contract it never throws to signal a
 * validation failure &mdash; the outcome is conveyed solely by the returned
 * {@link ValidationResult}, and even pathologically long numeric input is bounded to the
 * {@code PIC 9(03)} field width so the internal parse can never overflow {@code int}. It depends
 * only on {@link ValidationRule}, {@link ValidationResult}, and {@link NumericRequiredRule} from the
 * same package plus the Spring stereotype annotation.</p>
 *
 * @see ValidationRule
 * @see ValidationResult
 * @see NumericRequiredRule
 */
@Component
public final class FicoScoreRule implements ValidationRule {

    /**
     * The inclusive lower bound of a valid FICO score, from the COBOL
     * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code legacy/cbl/COACTUPC.cbl:L848-L849}.
     */
    private static final int MIN_FICO = 300;

    /**
     * The inclusive upper bound of a valid FICO score, from the COBOL
     * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code legacy/cbl/COACTUPC.cbl:L848-L849}.
     */
    private static final int MAX_FICO = 850;

    /**
     * The fixed width of the FICO field ({@code ACUP-NEW-CUST-FICO-SCORE}, {@code PIC 9(03)} at
     * {@code legacy/cbl/COACTUPC.cbl:L845-L847}). Bounding a candidate to this width honors the
     * three-digit field contract and keeps the subsequent parse safely within {@code int} range so
     * the rule never throws; a value wider than the field is treated as out of range.
     */
    private static final int FICO_SCORE_LENGTH = 3;

    /**
     * The range-failure message suffix, taken verbatim (including the leading colon-space) from the
     * COBOL {@code STRING} literal {@code ': should be between 300 and 850'} at
     * {@code legacy/cbl/COACTUPC.cbl:L2523}. It is appended to the trimmed field label to build the
     * full {@code WS-RETURN-MSG} screen message.
     */
    private static final String RANGE_MESSAGE_SUFFIX = ": should be between 300 and 850";

    /**
     * The numeric-required pre-edit collaborator (the Java migration of {@code 1245-EDIT-NUM-REQD}),
     * injected through the constructor. Delegating to it reproduces the legacy
     * {@code PERFORM 1245-EDIT-NUM-REQD} reuse rather than duplicating the supplied &rarr; numeric
     * &rarr; non-zero logic.
     */
    private final NumericRequiredRule numericRequiredRule;

    /**
     * Creates the rule with its numeric-required pre-edit collaborator. Constructor injection (no
     * field-level {@code @Autowired}) mirrors the COBOL {@code PERFORM 1245-EDIT-NUM-REQD} reuse and
     * keeps the dependency explicit and the class trivially unit-testable
     * (for example {@code new FicoScoreRule(new NumericRequiredRule())}).
     *
     * @param numericRequiredRule the {@code 1245-EDIT-NUM-REQD} migration used for the mandatory,
     *                            all-numeric, non-zero pre-check; must not be {@code null}
     */
    public FicoScoreRule(NumericRequiredRule numericRequiredRule) {
        this.numericRequiredRule = numericRequiredRule;
    }

    /**
     * Validates a candidate FICO score, reproducing the combined {@code 1245-EDIT-NUM-REQD} +
     * {@code 1275-EDIT-FICO-SCORE} edit of {@code legacy/cbl/COACTUPC.cbl} exactly, with the legacy
     * evaluation order preserved.
     *
     * <p>The numeric-required pre-edit runs first (delegated to {@link NumericRequiredRule}); only
     * when it passes is the {@value #MIN_FICO}&ndash;{@value #MAX_FICO} range checked. A pre-edit
     * failure returns that rule's message verbatim; a range failure returns the trimmed field label
     * followed by {@value #RANGE_MESSAGE_SUFFIX}.</p>
     *
     * @param fieldName the human-readable field label used to build the message (COBOL
     *                  {@code WS-EDIT-VARIABLE-NAME}, normally {@code "FICO Score"}); may be
     *                  {@code null} or padded, in which case {@link ValidationRule#label(String)}
     *                  normalizes it
     * @param value     the candidate FICO score as entered; may be {@code null} (COBOL
     *                  {@code LOW-VALUES}) or blank (COBOL {@code SPACES})
     * @return {@link ValidationResult#valid()} when {@code value} is supplied, all-numeric,
     *         non-zero, and within {@value #MIN_FICO}&ndash;{@value #MAX_FICO}; otherwise
     *         {@link ValidationResult#invalid(String)} carrying the exact COBOL screen message for
     *         the first failed check
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        // STEP 1 (COBOL L1549-L1551): PERFORM 1245-EDIT-NUM-REQD before the range check. Delegate to
        // the injected NumericRequiredRule (mirroring the legacy PERFORM reuse) instead of
        // re-implementing supplied -> numeric -> non-zero. On failure its screen message is returned
        // unchanged, exactly as the caller keeps the 1245 outcome when FLG-FICO-SCORE-ISVALID is
        // false and does not perform 1275.
        ValidationResult numericResult = numericRequiredRule.validate(fieldName, value);
        if (numericResult.isInvalid()) {
            return numericResult;
        }

        // The numeric pre-edit guarantees a non-null, all-ASCII-digit value; strip() tolerates any
        // fixed-width padding without changing the digit content (the legacy edit operated on a
        // fixed three-character slice, so in practice no padding survives the numeric check).
        String digits = value.strip();

        // STEP 2 (defensive bound on the PIC 9(03) field contract): a value wider than three digits
        // cannot be a valid FICO score and, if parsed, could overflow int. Per the field contract,
        // treat length > FICO_SCORE_LENGTH as out of range rather than throwing, because a
        // ValidationRule never throws to signal a validation failure.
        if (digits.length() > FICO_SCORE_LENGTH) {
            return ValidationResult.invalid(ValidationRule.label(fieldName) + RANGE_MESSAGE_SUFFIX);
        }

        // STEP 3 (COBOL L2515 1275-EDIT-FICO-SCORE): IF FICO-RANGE-IS-VALID (88-level VALUES 300
        // THROUGH 850) CONTINUE ELSE emit the range message. digits is at most three ASCII digits,
        // so parseInt is safe and never throws.
        int score = Integer.parseInt(digits);
        if (score < MIN_FICO || score > MAX_FICO) {
            return ValidationResult.invalid(ValidationRule.label(fieldName) + RANGE_MESSAGE_SUFFIX);
        }

        // COBOL: FICO-RANGE-IS-VALID -> CONTINUE (the field is accepted).
        return ValidationResult.valid();
    }
}
