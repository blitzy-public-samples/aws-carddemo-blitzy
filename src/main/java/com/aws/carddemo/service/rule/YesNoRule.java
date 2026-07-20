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
 * Yes/No single-character edit rule &mdash; the Java migration of the COBOL edit paragraph
 * {@code 1220-EDIT-YESNO} (see {@code legacy/cbl/COACTUPC.cbl:L1856-L1896}, source-branch
 * {@code app/cbl/COACTUPC.cbl}). It validates that a {@code PIC X(1)} indicator field holds the
 * uppercase flag {@code 'Y'} or {@code 'N'}, reproducing the paragraph's exact outcomes and
 * evaluation order. Detailed rationale lives in the decision log.
 *
 * <p>The legacy paragraph operates on {@code WS-EDIT-YES-NO} ({@code PIC X(1)} at
 * {@code legacy/cbl/COACTUPC.cbl:L76}) whose 88-level {@code FLG-YES-NO-ISVALID} carries
 * {@code VALUES 'Y','N'} (L78). It is driven with a caller-supplied field label
 * ({@code WS-EDIT-VARIABLE-NAME}) &mdash; for example {@code 'Account Status'} (L1472) for the
 * account active-status field and {@code 'Primary Card Holder'} (L1657) for the primary
 * card-holder indicator &mdash; and returns a screen message built around that label.</p>
 *
 * <p>Behaviour and evaluation order preserved verbatim from the COBOL:</p>
 * <ol>
 *   <li><b>Not supplied (blank) check</b> &mdash; {@code IF WS-EDIT-YES-NO EQUAL LOW-VALUES OR
 *       EQUAL SPACES OR EQUAL ZEROS} (L1861-1863). A {@code null} value models
 *       {@code LOW-VALUES}, an empty/all-whitespace value models {@code SPACES}, and a value
 *       consisting entirely of {@code '0'} characters models {@code ZEROS}. In this state the
 *       field is treated as <em>not supplied</em> (not as an invalid character), yielding the
 *       message {@code "<field> must be supplied."} (with the trailing period). The
 *       {@code ZEROS}-as-blank branch is a deliberate parity quirk: a lone {@code "0"} reports
 *       "must be supplied.", never "must be Y or N.".</li>
 *   <li><b>Valid-value check</b> &mdash; {@code IF FLG-YES-NO-ISVALID CONTINUE ELSE ...}
 *       (L1878). The value must equal {@code 'Y'} or {@code 'N'}. The 88-level is
 *       <em>case-exact</em>, so lowercase {@code 'y'}/{@code 'n'} do not pass; a failing value
 *       yields {@code "<field> must be Y or N."} (with the trailing period).</li>
 *   <li>Otherwise the field is valid.</li>
 * </ol>
 *
 * <p>The field label substituted into each message is normalized via
 * {@link ValidationRule#label(String)} (the Java form of
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}). The blank test delegates to
 * {@link ValidationRule#isBlank(String)} and {@link ValidationRule#isZeroValue(String)}; because
 * {@code isBlank} is evaluated first with a short-circuiting {@code ||}, {@code isZeroValue} only
 * ever sees a non-{@code null} value that contains at least one non-whitespace character, so
 * neither the {@code null} nor the empty-string edge of that helper is reachable here.
 * {@link String#strip()} is applied before the {@code 'Y'}/{@code 'N'} comparison so that
 * fixed-width trailing padding from a legacy buffer slice is tolerated while the single-character
 * ({@code PIC X(1)}) comparison semantics are preserved.</p>
 *
 * <p>This rule is stateless, side-effect free, and therefore safe to share as a singleton Spring
 * bean. It never throws to signal a validation failure and always builds its own outcome message;
 * the COBOL {@code IF WS-RETURN-MSG-OFF} <em>first-message-wins</em> latching (L1866, L1883) is
 * the responsibility of the consuming service, not of this rule. The rule depends only on
 * {@link ValidationRule} and {@link ValidationResult} (same package) and the JDK.</p>
 */
@Component
public final class YesNoRule implements ValidationRule {

    /**
     * Validates a Yes/No indicator field, reproducing COBOL paragraph {@code 1220-EDIT-YESNO}
     * ({@code legacy/cbl/COACTUPC.cbl:L1856-L1896}).
     *
     * <p>Returns, in COBOL evaluation order: {@link ValidationResult#invalid(String)} with
     * {@code "<field> must be supplied."} when {@code value} is {@code null}, blank
     * ({@code LOW-VALUES}/{@code SPACES}), or entirely {@code '0'} characters ({@code ZEROS});
     * otherwise {@link ValidationResult#invalid(String)} with {@code "<field> must be Y or N."}
     * when the stripped value is not exactly the uppercase flag {@code "Y"} or {@code "N"};
     * otherwise {@link ValidationResult#valid()}. {@code <field>} is the trimmed
     * {@code fieldName}. This method never throws.</p>
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME}) used
     *                  to build the outcome message; may be {@code null} or padded, in which case
     *                  {@link ValidationRule#label(String)} normalizes it to a trimmed / empty
     *                  string
     * @param value     the indicator value as entered ({@code PIC X(1)}); may be {@code null}
     *                  (modeling {@code LOW-VALUES}), blank (modeling {@code SPACES}), or all
     *                  {@code '0'} (modeling {@code ZEROS})
     * @return {@link ValidationResult#valid()} when {@code value} is {@code "Y"} or {@code "N"}
     *         (after {@link String#strip()}), otherwise a {@link ValidationResult#invalid(String)}
     *         carrying the exact COBOL screen message
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);

        // 1220-EDIT-YESNO L1861-1863: LOW-VALUES OR SPACES OR ZEROS -> "not supplied".
        // isBlank() (null/empty/whitespace) is evaluated first; its short-circuit guarantees a
        // non-null, non-blank argument to isZeroValue(), so a value of all '0' characters is the
        // only extra case that reaches the ZEROS branch.
        if (ValidationRule.isBlank(value) || ValidationRule.isZeroValue(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }

        // 1220-EDIT-YESNO L1878: FLG-YES-NO-ISVALID (VALUES 'Y','N') is case-exact. strip()
        // tolerates fixed-width trailing padding while keeping the single-character comparison.
        String v = value.strip();
        if (!"Y".equals(v) && !"N".equals(v)) {
            return ValidationResult.invalid(field + " must be Y or N.");
        }

        // Falls through the COBOL CONTINUE branch: the field is valid.
        return ValidationResult.valid();
    }
}
