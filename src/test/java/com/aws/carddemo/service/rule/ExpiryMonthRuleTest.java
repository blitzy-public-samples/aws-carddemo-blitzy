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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExpiryMonthRule}, the Java migration of the COBOL edit paragraph
 * {@code 1250-EDIT-EXPIRY-MON} in {@code legacy/cbl/COCRDUPC.cbl} (source branch
 * {@code app/cbl/COCRDUPC.cbl}) of the CardDemo Card-Update transaction (CCUP).
 *
 * <p>The tests assert <em>verbatim behavioral parity</em> with the legacy paragraph and its
 * fixed screen message. The COBOL logic is:</p>
 * <pre>
 *     1250-EDIT-EXPIRY-MON.
 *         SET FLG-CARDEXPMON-NOT-OK TO TRUE
 *         IF CCUP-NEW-EXPMON EQUAL LOW-VALUES OR SPACES OR ZEROS
 *            SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE   GO TO EXIT   (legacy L883-L891)
 *         END-IF
 *         MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK               (PIC X(2) redefined PIC 9(2), L896)
 *         IF VALID-MONTH                                          (88-level VALUES 1 THRU 12, L95/L898)
 *            SET FLG-CARDEXPMON-ISVALID TO TRUE
 *         ELSE
 *            SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
 *         END-IF.
 * </pre>
 *
 * <p>The single failure message is taken verbatim from the COBOL 88-level constant
 * {@code CARD-EXPIRY-MONTH-NOT-VALID} at {@code legacy/cbl/COCRDUPC.cbl:L197-L198}:
 * {@code "Card expiry month must be between 1 and 12"}. Every rejected value &mdash; blank
 * ({@code null}/empty/whitespace, modeling COBOL {@code LOW-VALUES}/{@code SPACES}), an all-zero
 * value (COBOL {@code ZEROS}, so {@code "00"} counts as blank), a non-numeric value, a value wider
 * than the two-digit field, and a numeric value outside {@code 1}&ndash;{@code 12} &mdash; must
 * yield that identical fixed message with no field-name substitution. A valid two-digit month
 * {@code 01}&ndash;{@code 12} yields the shared valid outcome whose message is the empty string.</p>
 *
 * <p>The rule is stateless, so no Spring context, no Mockito, and no database are required: the
 * class under test is exercised through a plain {@code new ExpiryMonthRule()} instance. The rule
 * never throws to signal a validation failure, so the failure-path tests invoke it directly and
 * assert on the returned {@link ValidationResult}; any thrown exception would fail the test.</p>
 */
class ExpiryMonthRuleTest {

    /**
     * The class under test, constructed directly because the rule is stateless and free of
     * dependencies (no Spring wiring is needed to exercise it).
     */
    private final ExpiryMonthRule rule = new ExpiryMonthRule();

    /**
     * The exact COBOL screen message from {@code legacy/cbl/COCRDUPC.cbl:L197-L198}; every failure
     * path must reproduce it verbatim (it is a fixed literal that never substitutes a field name).
     */
    private static final String MESSAGE = "Card expiry month must be between 1 and 12";

    /**
     * An arbitrary human-readable field label. Because the COBOL message is a fixed literal, the
     * {@code fieldName} argument of {@link ExpiryMonthRule#validate(String, String)} is ignored;
     * the tests pass this constant to honor the method contract and prove the argument has no
     * effect on the outcome message.
     */
    private static final String LABEL = "Expiry Month";

    // ---------------------------------------------------------------------------------------------
    // Valid months (VALID-MONTH: VALUES 1 THRU 12)
    // ---------------------------------------------------------------------------------------------

    @Test
    void validLowerBoundaryMonthIsAccepted() {
        // "01" is the inclusive lower boundary of VALID-MONTH (1 THRU 12).
        ValidationResult result = rule.validate(LABEL, "01");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        // A valid outcome carries the empty message (COBOL WS-RETURN-MSG-OFF / VALUE SPACES).
        assertThat(result.message()).isEmpty();
    }

    @Test
    void validUpperBoundaryMonthIsAccepted() {
        // "12" is the inclusive upper boundary of VALID-MONTH (1 THRU 12).
        ValidationResult result = rule.validate(LABEL, "12");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void representativeInRangeMonthsAreAccepted() {
        // Representative two-digit months strictly inside the range are all accepted.
        assertThat(rule.validate(LABEL, "02").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "06").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "09").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "11").isValid()).isTrue();
    }

    @Test
    void validOutcomeIsTheSharedSingleton() {
        // A passing rule returns the cached ValidationResult.valid() instance (identity, not just
        // equality), confirming the rule reuses the shared valid outcome.
        assertThat(rule.validate(LABEL, "01")).isSameAs(ValidationResult.valid());
    }

    // ---------------------------------------------------------------------------------------------
    // Blank / ZEROS rejection (COBOL: EQUAL LOW-VALUES OR SPACES OR ZEROS -> not valid)
    // ---------------------------------------------------------------------------------------------

    @Test
    void zerosValueIsRejectedAsBlank() {
        // COBOL treats the figurative constant ZEROS as "not supplied": "00" is therefore invalid.
        ValidationResult result = rule.validate(LABEL, "00");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    @Test
    void otherAllZeroFormsAreRejected() {
        // Any all-zero digit string is the ZEROS value, regardless of width, and is rejected.
        ValidationResult single = rule.validate(LABEL, "0");
        assertThat(single.isInvalid()).isTrue();
        assertThat(single.message()).isEqualTo(MESSAGE);

        ValidationResult triple = rule.validate(LABEL, "000");
        assertThat(triple.isInvalid()).isTrue();
        assertThat(triple.message()).isEqualTo(MESSAGE);
    }

    @Test
    void nullValueIsRejected() {
        // null models COBOL LOW-VALUES -> the blank guard rejects it with the fixed message.
        ValidationResult result = rule.validate(LABEL, null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    @Test
    void emptyValueIsRejected() {
        // An empty string models COBOL SPACES / a trim length of zero -> rejected.
        ValidationResult result = rule.validate(LABEL, "");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    @Test
    void whitespaceValueIsRejected() {
        // All-whitespace input (single space, multiple spaces, tab) models COBOL SPACES -> rejected.
        ValidationResult oneSpace = rule.validate(LABEL, " ");
        assertThat(oneSpace.isInvalid()).isTrue();
        assertThat(oneSpace.message()).isEqualTo(MESSAGE);

        ValidationResult manySpaces = rule.validate(LABEL, "   ");
        assertThat(manySpaces.isInvalid()).isTrue();
        assertThat(manySpaces.message()).isEqualTo(MESSAGE);

        ValidationResult tab = rule.validate(LABEL, "\t");
        assertThat(tab.isInvalid()).isTrue();
        assertThat(tab.message()).isEqualTo(MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // Out-of-range rejection (numeric value outside 1..12)
    // ---------------------------------------------------------------------------------------------

    @Test
    void aboveRangeMonthIsRejected() {
        // "13" is numeric but greater than 12 -> fails VALID-MONTH with the fixed message.
        ValidationResult result = rule.validate(LABEL, "13");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    @Test
    void farAboveRangeMonthsAreRejected() {
        // Larger two-digit numeric values remain out of range and are rejected.
        ValidationResult twenty = rule.validate(LABEL, "20");
        assertThat(twenty.isInvalid()).isTrue();
        assertThat(twenty.message()).isEqualTo(MESSAGE);

        ValidationResult ninetyNine = rule.validate(LABEL, "99");
        assertThat(ninetyNine.isInvalid()).isTrue();
        assertThat(ninetyNine.message()).isEqualTo(MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // Non-numeric rejection (fails the PIC 9(2) numeric class test behind VALID-MONTH)
    // ---------------------------------------------------------------------------------------------

    @Test
    void nonNumericValueIsRejected() {
        // A trailing letter, a leading letter, a lower-case letter, all letters, a decimal point,
        // and an explicit sign each fail the implicit IS NUMERIC test and are rejected identically.
        for (String value : new String[] {"1A", "A1", "1a", "AB", "1.", "-1", "+1"}) {
            ValidationResult result = rule.validate(LABEL, value);

            assertThat(result.isInvalid())
                    .as("expected non-numeric value '%s' to be invalid", value)
                    .isTrue();
            assertThat(result.message())
                    .as("expected the fixed message for non-numeric value '%s'", value)
                    .isEqualTo(MESSAGE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Overlong input (wider than the two-digit PIC X(2) field) is out of contract and never throws
    // ---------------------------------------------------------------------------------------------

    @Test
    void overlongNumericValueIsRejectedAndNeverThrows() {
        // "001"/"012" would parse to 1/12 if the field width were not enforced; the field is
        // PIC X(2), so anything wider than two digits is out of contract and rejected. The very
        // long values additionally prove the internal parse can never overflow int: the rule
        // never throws (honoring the ValidationRule contract), so invoking it directly and
        // asserting on the result is sufficient -- a thrown exception would fail the test.
        for (String value : new String[] {"001", "012", "99999999999",
                "00000000000000000000000000"}) {
            ValidationResult result = rule.validate(LABEL, value);

            assertThat(result.isInvalid())
                    .as("expected overlong value '%s' to be invalid", value)
                    .isTrue();
            assertThat(result.message())
                    .as("expected the fixed message for overlong value '%s'", value)
                    .isEqualTo(MESSAGE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The field-name argument is ignored: the failure message is always the fixed literal
    // ---------------------------------------------------------------------------------------------

    @Test
    void fieldNameArgumentIsIgnored() {
        // Regardless of the label supplied (including null and blank), an invalid value yields the
        // identical fixed message, proving the COBOL 88-level constant is emitted verbatim with no
        // field-name substitution.
        for (String fieldName : new String[] {null, "", "   ", "IGNORED", "Expiry Month"}) {
            ValidationResult result = rule.validate(fieldName, "00");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.message())
                    .as("the failure message must not depend on the field label")
                    .isEqualTo(MESSAGE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Statelessness: repeated, mixed invocations do not drift
    // ---------------------------------------------------------------------------------------------

    @Test
    void ruleIsStatelessAcrossMixedInvocations() {
        // A sequence of alternating valid and invalid inputs must produce independent, correct
        // outcomes, confirming the rule holds no mutable state between calls.
        assertThat(rule.validate(LABEL, "07").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "13").isInvalid()).isTrue();
        assertThat(rule.validate(LABEL, "12").isValid()).isTrue();
        assertThat(rule.validate(LABEL, null).isInvalid()).isTrue();

        // The final valid call still returns the canonical shared singleton, confirming no drift.
        assertThat(rule.validate(LABEL, "01")).isSameAs(ValidationResult.valid());
    }
}
