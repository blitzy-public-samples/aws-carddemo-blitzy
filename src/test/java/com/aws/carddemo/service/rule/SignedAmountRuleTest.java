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

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SignedAmountRule}, the Java migration of COBOL edit paragraph
 * {@code 1250-EDIT-SIGNED-9V2} (see {@code legacy/cbl/COACTUPC.cbl}, source-branch
 * {@code app/cbl/COACTUPC.cbl}, lines {@code L2180}&ndash;{@code L2223}) used for the
 * {@code PIC S9(10)V99} monetary fields (credit limit, cash credit limit, current balance,
 * current-cycle credit, current-cycle debit).
 *
 * <p>These tests lock down the verbatim COBOL parity that defines the rule's contract:
 * <ul>
 *   <li>a not-supplied value yields {@code "<field> must be supplied."} <em>with</em> a trailing
 *       period (COBOL {@code L2191});</li>
 *   <li>a value rejected by {@code FUNCTION TEST-NUMVAL-C} yields {@code "<field> is not valid"}
 *       <em>without</em> a trailing period (COBOL {@code L2209}) &mdash; the deliberate message
 *       asymmetry is asserted explicitly;</li>
 *   <li>zero is a <em>valid</em> signed amount (paragraph {@code 1250} performs no zero check,
 *       unlike {@code 1245-EDIT-NUM-REQD});</li>
 *   <li>{@link SignedAmountRule#parseAmount(String)} yields a {@link BigDecimal} at scale&nbsp;2,
 *       truncating excess fractional digits toward zero to mirror a COBOL {@code MOVE} into
 *       {@code PIC S9(10)V99}.</li>
 * </ul>
 * The representative field label is {@code "Credit Limit"}. This is a pure JUnit&nbsp;5 + AssertJ
 * unit test &mdash; no Spring context, no Mockito, and no database.
 */
class SignedAmountRuleTest {

    /** The representative field label substituted into every outcome message under test. */
    private static final String FIELD = "Credit Limit";

    /** The rule under test; stateless, so a plain instance suffices (no Spring context needed). */
    private final SignedAmountRule rule = new SignedAmountRule();

    // ---------------------------------------------------------------------------------------------
    // (a) Not supplied -> "<field> must be supplied." (WITH trailing period)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(a) null value -> 'Credit Limit must be supplied.' (with period)")
    void nullValue_mustBeSupplied() {
        ValidationResult result = rule.validate(FIELD, null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Credit Limit must be supplied.");
        assertThat(rule.parseAmount(null)).isNull();
    }

    @Test
    @DisplayName("(a) all-spaces value -> 'Credit Limit must be supplied.' (with period)")
    void spacesValue_mustBeSupplied() {
        ValidationResult result = rule.validate(FIELD, "   ");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Credit Limit must be supplied.");
        assertThat(rule.parseAmount("   ")).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // (b) Well-formed positive amount -> valid, parseAmount == new BigDecimal("1000.00")
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(b) '1000.00' -> valid and parseAmount == new BigDecimal(\"1000.00\")")
    void positiveAmount_isValidAndParses() {
        ValidationResult result = rule.validate(FIELD, "1000.00");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();

        BigDecimal parsed = rule.parseAmount("1000.00");
        assertThat(parsed).isEqualTo(new BigDecimal("1000.00"));
        assertThat(parsed.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // (c) Negative amount -> valid, parses to -500.00
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(c) '-500.00' -> valid and parseAmount == new BigDecimal(\"-500.00\")")
    void negativeAmount_isValidAndParses() {
        ValidationResult result = rule.validate(FIELD, "-500.00");

        assertThat(result.isValid()).isTrue();

        BigDecimal parsed = rule.parseAmount("-500.00");
        assertThat(parsed).isEqualTo(new BigDecimal("-500.00"));
        assertThat(parsed.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // (d) Zero -> VALID (no zero rejection in paragraph 1250)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(d) '0.00' -> VALID (zero is allowed; no zero check unlike NumericRequiredRule)")
    void zeroAmount_isValid() {
        ValidationResult result = rule.validate(FIELD, "0.00");

        assertThat(result.isValid()).isTrue();

        BigDecimal parsed = rule.parseAmount("0.00");
        assertThat(parsed).isEqualTo(new BigDecimal("0.00"));
        assertThat(parsed.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // (e) Malformed -> "<field> is not valid" (NO trailing period)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(e) '12.3X' -> 'Credit Limit is not valid' (NO trailing period)")
    void malformedAmount_isNotValid_noTrailingPeriod() {
        ValidationResult result = rule.validate(FIELD, "12.3X");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Credit Limit is not valid");
        // The malformed message deliberately carries NO trailing period (COBOL L2209).
        assertThat(result.message()).doesNotEndWith(".");
        assertThat(rule.parseAmount("12.3X")).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // (f) Thousands-grouped amount -> valid, parses to 1234.56
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(f) '1,234.56' -> valid and parseAmount == new BigDecimal(\"1234.56\")")
    void groupedAmount_isValidAndParses() {
        ValidationResult result = rule.validate(FIELD, "1,234.56");

        assertThat(result.isValid()).isTrue();

        BigDecimal parsed = rule.parseAmount("1,234.56");
        assertThat(parsed).isEqualTo(new BigDecimal("1234.56"));
        assertThat(parsed.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // (g) Excess fractional digits -> valid, TRUNCATED (RoundingMode.DOWN) to 1.23
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(g) '1.239' -> valid and parseAmount truncates (DOWN) to 1.23")
    void excessFraction_truncatesTowardZero() {
        ValidationResult result = rule.validate(FIELD, "1.239");

        assertThat(result.isValid()).isTrue();

        BigDecimal parsed = rule.parseAmount("1.239");
        // RoundingMode.DOWN truncates (does not round): 1.239 -> 1.23, NOT 1.24.
        assertThat(parsed).isEqualTo(new BigDecimal("1.23"));
        assertThat(parsed.scale()).isEqualTo(2);

        // Truncation is toward zero for negatives too: -1.239 -> -1.23 (not -1.24).
        assertThat(rule.parseAmount("-1.239")).isEqualTo(new BigDecimal("-1.23"));
    }

    // ---------------------------------------------------------------------------------------------
    // (h) Returned BigDecimal always has scale() == 2
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(h) parseAmount always returns scale() == 2 across integer, grouped, and long forms")
    void parseAmount_alwaysScaleTwo() {
        assertThat(rule.parseAmount("1000.00").scale()).isEqualTo(2);
        assertThat(rule.parseAmount("1234").scale()).isEqualTo(2);          // no decimal part
        assertThat(rule.parseAmount("1,234,567.89").scale()).isEqualTo(2);  // grouped long form
        assertThat(rule.parseAmount("0.00").scale()).isEqualTo(2);
        assertThat(rule.parseAmount("-500.00").scale()).isEqualTo(2);
        assertThat(rule.parseAmount(".45").scale()).isEqualTo(2);           // leading-decimal form
    }

    // ---------------------------------------------------------------------------------------------
    // Additional NUMVAL-C parity cases (signs, currency, grouping) — reinforce behavioral parity.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("trailing 'CR' denotes a negative amount (NUMVAL-C): '500.00CR' -> -500.00")
    void trailingCr_isNegative() {
        assertThat(rule.validate(FIELD, "500.00CR").isValid()).isTrue();
        assertThat(rule.parseAmount("500.00CR")).isEqualTo(new BigDecimal("-500.00"));
    }

    @Test
    @DisplayName("trailing 'DB' denotes a negative amount (NUMVAL-C): '500.00DB' -> -500.00")
    void trailingDb_isNegative() {
        assertThat(rule.validate(FIELD, "500.00DB").isValid()).isTrue();
        assertThat(rule.parseAmount("500.00DB")).isEqualTo(new BigDecimal("-500.00"));
    }

    @Test
    @DisplayName("trailing minus denotes a negative amount: '1000.00-' -> -1000.00")
    void trailingMinus_isNegative() {
        assertThat(rule.validate(FIELD, "1000.00-").isValid()).isTrue();
        assertThat(rule.parseAmount("1000.00-")).isEqualTo(new BigDecimal("-1000.00"));
    }

    @Test
    @DisplayName("leading currency symbol is accepted: '$1,234.56' -> 1234.56")
    void leadingCurrency_isAccepted() {
        assertThat(rule.validate(FIELD, "$1,234.56").isValid()).isTrue();
        assertThat(rule.parseAmount("$1,234.56")).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("malformed thousands grouping is rejected: '12,34' -> 'Credit Limit is not valid'")
    void badGrouping_isNotValid() {
        ValidationResult result = rule.validate(FIELD, "12,34");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Credit Limit is not valid");
        assertThat(rule.parseAmount("12,34")).isNull();
    }

    @Test
    @DisplayName("a value with both leading and trailing sign is rejected (single-sign grammar)")
    void doubleSign_isNotValid() {
        assertThat(rule.validate(FIELD, "+5-").isInvalid()).isTrue();
        assertThat(rule.parseAmount("+5-")).isNull();
    }

    @Test
    @DisplayName("field label is trimmed before substitution (FUNCTION TRIM parity)")
    void fieldLabel_isTrimmed() {
        ValidationResult result = rule.validate("  Credit Limit  ", "12.3X");

        assertThat(result.message()).isEqualTo("Credit Limit is not valid");
    }
}
