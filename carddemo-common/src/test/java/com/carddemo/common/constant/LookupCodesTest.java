package com.carddemo.common.constant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the :class:`LookupCodes` validation lookup sets.
 *
 * :purpose: Lock the exact size, element shape, boundary membership and
 *     immutability of the five ``CSLKPCDY`` copybook-derived lookup sets so that
 *     phone-area, US-state and state+ZIP validation stays behavior-identical to
 *     the legacy COBOL ``88``-level membership tests.
 * :output: JUnit 5 / AssertJ assertions only; pure-logic with no Spring context,
 *     database, or other external resource.
 */
class LookupCodesTest {

    @Test
    void setSizesMatchCopybook() {
        assertThat(LookupCodes.VALID_PHONE_AREA_CODE).hasSize(490);
        assertThat(LookupCodes.VALID_GENERAL_PURP_CODE).hasSize(410);
        assertThat(LookupCodes.VALID_EASY_RECOG_AREA_CODE).hasSize(80);
        assertThat(LookupCodes.VALID_US_STATE_CODE).hasSize(56);
        assertThat(LookupCodes.VALID_US_STATE_ZIP_CD2_COMBO).hasSize(240);
    }

    @Test
    void phoneCodesAreThreeDigits() {
        assertThat(LookupCodes.VALID_PHONE_AREA_CODE).allMatch(s -> s.matches("\\d{3}"));
        assertThat(LookupCodes.VALID_GENERAL_PURP_CODE).allMatch(s -> s.matches("\\d{3}"));
        assertThat(LookupCodes.VALID_EASY_RECOG_AREA_CODE).allMatch(s -> s.matches("\\d{3}"));
    }

    @Test
    void stateCodesAreTwoUppercaseLetters() {
        assertThat(LookupCodes.VALID_US_STATE_CODE).allMatch(s -> s.matches("[A-Z]{2}"));
    }

    @Test
    void zipCombosAreTwoLettersTwoDigits() {
        assertThat(LookupCodes.VALID_US_STATE_ZIP_CD2_COMBO).allMatch(s -> s.matches("[A-Z]{2}\\d{2}"));
    }

    @Test
    void containsKnownBoundaryValues() {
        assertThat(LookupCodes.VALID_PHONE_AREA_CODE).contains("201", "999");
        assertThat(LookupCodes.VALID_US_STATE_CODE).contains("AL", "DC", "PR", "VI");
        assertThat(LookupCodes.VALID_US_STATE_ZIP_CD2_COMBO).contains("AA34", "WY83");
    }

    @Test
    void setsAreUnmodifiable() {
        assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_CODE.add("ZZ"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_PHONE_AREA_CODE.add("000"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_ZIP_CD2_COMBO.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
