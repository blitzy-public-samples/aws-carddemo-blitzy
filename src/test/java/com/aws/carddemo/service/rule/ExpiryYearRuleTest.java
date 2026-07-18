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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExpiryYearRule}, the Java migration of the COBOL edit paragraph
 * {@code 1260-EDIT-EXPIRY-YEAR} in the card-update online program {@code legacy/cbl/COCRDUPC.cbl}
 * (source-branch {@code app/cbl/COCRDUPC.cbl}, lines L913&ndash;L945).
 *
 * <p>These tests assert verbatim behavioral parity with the legacy paragraph, which edits the
 * fixed-width {@code CCUP-NEW-EXPYEAR} field ({@code PIC X(4)} at
 * {@code legacy/cbl/COCRDUPC.cbl:L310}) in two ordered steps:</p>
 * <ol>
 *   <li><strong>Not supplied</strong> &mdash; {@code IF CCUP-NEW-EXPYEAR EQUAL LOW-VALUES OR SPACES
 *       OR ZEROS} the field is rejected ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}). {@code LOW-VALUES}
 *       is modeled by {@code null}, {@code SPACES} by a blank/all-whitespace value, and {@code ZEROS}
 *       by an all-{@code '0'} value such as {@code "0000"}.</li>
 *   <li><strong>Valid range</strong> &mdash; the field is moved into {@code CARD-YEAR-CHECK}
 *       ({@code PIC X(4)} at {@code legacy/cbl/COCRDUPC.cbl:L96}) whose numeric redefinition
 *       {@code CARD-YEAR-CHECK-N} ({@code PIC 9(4)} at {@code legacy/cbl/COCRDUPC.cbl:L97-L98})
 *       carries {@code 88 VALID-YEAR VALUES 1950 THRU 2099} ({@code legacy/cbl/COCRDUPC.cbl:L99}).
 *       Because the work item is exactly four bytes and {@code VALID-YEAR} tests its {@code PIC 9(4)}
 *       redefinition, a value passes only when it is exactly four ASCII digits naming a year in the
 *       inclusive range {@code 1950}&ndash;{@code 2099}; a two-digit entry such as {@code "50"} fails
 *       the fixed field width, and a non-numeric entry fails the numeric class test.</li>
 * </ol>
 *
 * <p>Both failure paths latch the same fixed screen message &mdash; the COBOL 88-level constant
 * {@code CARD-EXPIRY-YEAR-NOT-VALID VALUE 'Invalid card expiry year'} at
 * {@code legacy/cbl/COCRDUPC.cbl:L199-L200}. Unlike several sibling edit paragraphs, this message is
 * a fixed literal with no field-label substitution, so the {@code fieldName} argument is accepted (to
 * honor the {@link ValidationRule} contract) but never influences the emitted message.</p>
 *
 * <p>The rule is stateless, so it is exercised through a directly constructed instance
 * ({@code new ExpiryYearRule()}). This is a pure JUnit&nbsp;5 + AssertJ unit test &mdash; no Spring
 * context, no Mockito, and no database.</p>
 */
class ExpiryYearRuleTest {

    /**
     * The rule under test. It is stateless (no mutable fields), so a single directly constructed
     * instance is reused across every test method.
     */
    private final ExpiryYearRule rule = new ExpiryYearRule();

    /**
     * The exact COBOL screen message; every failure path must reproduce it verbatim. It is the
     * 88-level constant {@code CARD-EXPIRY-YEAR-NOT-VALID VALUE 'Invalid card expiry year'} at
     * {@code legacy/cbl/COCRDUPC.cbl:L199-L200}.
     */
    private static final String MESSAGE = "Invalid card expiry year";

    /**
     * An arbitrary field label. The rule must ignore it because the COBOL message is a fixed literal
     * that never substitutes a field name.
     */
    private static final String FIELD = "Expiry Year";

    /**
     * The inclusive lower boundary {@code 1950} of {@code 88 VALID-YEAR VALUES 1950 THRU 2099}
     * ({@code legacy/cbl/COCRDUPC.cbl:L99}) passes and yields the empty-message valid outcome.
     */
    @Test
    void validLowerBoundaryYear1950_isValid() {
        ValidationResult result = rule.validate(FIELD, "1950");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEqualTo("");
    }

    /**
     * The inclusive upper boundary {@code 2099} of {@code 88 VALID-YEAR VALUES 1950 THRU 2099}
     * ({@code legacy/cbl/COCRDUPC.cbl:L99}) passes and yields the empty-message valid outcome.
     */
    @Test
    void validUpperBoundaryYear2099_isValid() {
        ValidationResult result = rule.validate(FIELD, "2099");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEqualTo("");
    }

    /**
     * A representative four-digit year inside the valid range passes, confirming the mid-range case
     * as well as the boundaries.
     */
    @Test
    void validMidRangeYear2000_isValid() {
        ValidationResult result = rule.validate(FIELD, "2000");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEqualTo("");
    }

    /**
     * A four-digit year one below the lower boundary ({@code 1949}) is rejected with the fixed
     * message, reproducing the {@code ELSE} branch of {@code IF VALID-YEAR}
     * ({@code legacy/cbl/COCRDUPC.cbl:L934-L943}).
     */
    @Test
    void belowLowerBoundaryYear1949_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "1949");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * A four-digit year one above the upper boundary ({@code 2100}) is rejected with the fixed
     * message, reproducing the {@code ELSE} branch of {@code IF VALID-YEAR}
     * ({@code legacy/cbl/COCRDUPC.cbl:L934-L943}).
     */
    @Test
    void aboveUpperBoundaryYear2100_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "2100");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * A {@code null} value models COBOL {@code LOW-VALUES} and is rejected with the fixed message
     * via the not-supplied guard ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}).
     */
    @Test
    void nullValue_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * An empty value models a zero-length {@code SPACES}/{@code FUNCTION TRIM} field and is rejected
     * with the fixed message ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}).
     */
    @Test
    void emptyValue_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * An all-whitespace value models COBOL {@code SPACES} and is rejected with the fixed message
     * via the not-supplied guard ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}).
     */
    @Test
    void spacesValue_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "    ");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * The all-zeros value {@code "0000"} models COBOL {@code ZEROS} and is rejected with the fixed
     * message via the not-supplied guard ({@code legacy/cbl/COCRDUPC.cbl:L916-L924}); it is caught by
     * the {@code ZEROS} branch rather than the range check.
     */
    @Test
    void allZeros_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "0000");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * A value containing a non-digit character ({@code "20X5"}) fails the {@code PIC 9(4)} numeric
     * class test of the {@code CARD-YEAR-CHECK} redefinition and is rejected with the fixed message
     * ({@code legacy/cbl/COCRDUPC.cbl:L96-L98,L932}).
     */
    @Test
    void nonNumericValue_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "20X5");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * A two-digit entry ({@code "50"}) is narrower than the fixed four-byte {@code CARD-YEAR-CHECK}
     * field and is therefore rejected with the fixed message, enforcing the {@code PIC X(4)} /
     * {@code PIC 9(4)} field width ({@code legacy/cbl/COCRDUPC.cbl:L96-L98,L932}).
     */
    @Test
    void wrongWidthTwoDigits_isInvalidWithFixedMessage() {
        ValidationResult result = rule.validate(FIELD, "50");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(MESSAGE);
    }

    /**
     * The {@code fieldName} argument is ignored: regardless of the label supplied, an invalid value
     * yields the identical fixed message, proving the COBOL 88-level constant
     * ({@code legacy/cbl/COCRDUPC.cbl:L199-L200}) is emitted verbatim with no name substitution.
     */
    @Test
    void fieldNameArgumentIsIgnored_messageIsAlwaysFixedLiteral() {
        assertThat(rule.validate(null, "1949").message()).isEqualTo(MESSAGE);
        assertThat(rule.validate("", "1949").message()).isEqualTo(MESSAGE);
        assertThat(rule.validate("Some Other Label", "1949").message()).isEqualTo(MESSAGE);
    }

    /**
     * A passing value returns the shared cached {@link ValidationResult#valid()} singleton, matching
     * the way the sibling rules signal success and confirming no per-call allocation on the valid
     * path.
     */
    @Test
    void validOutcomeIsSharedSingleton() {
        assertThat(rule.validate(FIELD, "1950")).isSameAs(ValidationResult.valid());
    }

    /**
     * The rule is stateless and safe to reuse across successive, mixed invocations: valid and invalid
     * calls do not influence one another, and a valid call still returns the canonical singleton.
     */
    @Test
    void ruleIsStatelessAcrossMixedInvocations() {
        assertThat(rule.validate(FIELD, "1950").isValid()).isTrue();
        assertThat(rule.validate(FIELD, "2100").isInvalid()).isTrue();
        assertThat(rule.validate(FIELD, "2099").isValid()).isTrue();
        assertThat(rule.validate(FIELD, null).isInvalid()).isTrue();
        assertThat(rule.validate(FIELD, "2000")).isSameAs(ValidationResult.valid());
    }
}
