package com.aws.carddemo.dto;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aws.carddemo.dto.CardWorkArea.PfKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link CardWorkArea}. Oracle: legacy/cpy/CVCRD01Y.cpy (CC-WORK-AREAS).
 *
 * <p>This is a pure JUnit 5 (Jupiter) unit test in the COBOL&#8594;Java/Spring Boot
 * migration of AWS CardDemo. It exercises the plain DTO
 * {@link com.aws.carddemo.dto.CardWorkArea} - the translation of the COBOL
 * working-storage copybook {@code CVCRD01Y} (group {@code CC-WORK-AREAS}) - with no
 * Spring context and no database.</p>
 *
 * <p>The behaviors locked down here, in order of importance, are:</p>
 * <ol>
 *   <li>The nested {@link CardWorkArea.PfKey} enumeration - a published cross-package
 *       contract consumed by {@code com.aws.carddemo.util.PfKeyHandler}: exactly 17
 *       constants, {@code ENTER} first and {@code OTHER} last, in a fixed order.</li>
 *   <li>The tolerant {@link CardWorkArea.PfKey#fromAid(String)} mapping: it trims the
 *       (possibly blank-padded) AID token, maps the recognized COBOL 88-level values, and
 *       falls back to {@code OTHER} for {@code null}, blank or unrecognized input without
 *       ever throwing.</li>
 *   <li>The null-safe numeric-view accessors ({@code getAcctIdNumeric},
 *       {@code getCardNumNumeric}, {@code getCustIdNumeric}) which mirror the COBOL
 *       {@code REDEFINES ... PIC 9(n)} clauses and must never throw, because the
 *       {@code PIC X(n) VALUE SPACES} fields legitimately hold spaces before assignment.</li>
 *   <li>The plain getter/setter state and the empty-string identifier defaults that mirror
 *       the COBOL {@code VALUE SPACES} initialization.</li>
 * </ol>
 */
@DisplayName("CardWorkArea DTO (CVCRD01Y / CC-WORK-AREAS)")
class CardWorkAreaTest {

    // ---------------------------------------------------------------------------------
    // Phase A - PfKey enum shape (17 constants, fixed order, OTHER last)
    // ---------------------------------------------------------------------------------

    @Test
    void pfKey_has_exactly_seventeen_constants() {
        assertThat(PfKey.values()).hasSize(17);
    }

    @Test
    void pfKey_first_constant_is_enter_and_last_is_other() {
        assertThat(PfKey.values()[0]).isEqualTo(PfKey.ENTER);
        assertThat(PfKey.values()[16]).isEqualTo(PfKey.OTHER);
    }

    @Test
    void pfKey_constants_declared_in_exact_contract_order() {
        assertThat(PfKey.values()).containsExactly(
                PfKey.ENTER, PfKey.CLEAR, PfKey.PA1, PfKey.PA2,
                PfKey.PFK01, PfKey.PFK02, PfKey.PFK03, PfKey.PFK04,
                PfKey.PFK05, PfKey.PFK06, PfKey.PFK07, PfKey.PFK08,
                PfKey.PFK09, PfKey.PFK10, PfKey.PFK11, PfKey.PFK12,
                PfKey.OTHER);
    }

    @Test
    void pfKey_named_and_function_key_constants_are_all_present() {
        assertThat(PfKey.valueOf("ENTER")).isEqualTo(PfKey.ENTER);
        assertThat(PfKey.valueOf("CLEAR")).isEqualTo(PfKey.CLEAR);
        assertThat(PfKey.valueOf("PA1")).isEqualTo(PfKey.PA1);
        assertThat(PfKey.valueOf("PA2")).isEqualTo(PfKey.PA2);
        assertThat(PfKey.valueOf("PFK12")).isEqualTo(PfKey.PFK12);
        for (int i = 1; i <= 12; i++) {
            String constantName = String.format("PFK%02d", i);
            assertThatCode(() -> PfKey.valueOf(constantName)).doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------------------------
    // Phase B - PfKey.fromAid mapping (core behavior: trim + tolerant OTHER fallback)
    // ---------------------------------------------------------------------------------

    /**
     * Supplies the {@code (rawAid, expectedPfKey)} cases for
     * {@link #fromAid_maps_recognized_and_unrecognized_tokens(String, CardWorkArea.PfKey)}.
     *
     * <p>Every recognized token is covered so that each arm of the production {@code switch}
     * is exercised (JaCoCo line coverage), along with blank-padded tokens (verifying the
     * trim), and several unrecognized tokens that must resolve to {@code OTHER}. The COBOL
     * 88-level values are upper-case, so {@code "enter"} confirms the mapping is
     * case-sensitive.</p>
     *
     * @return a stream of {@code (String aid, PfKey expected)} argument pairs
     */
    private static Stream<Arguments> pfKeyMappingCases() {
        return Stream.of(
                Arguments.of("ENTER", PfKey.ENTER),
                Arguments.of("CLEAR", PfKey.CLEAR),
                Arguments.of("PA1  ", PfKey.PA1),
                Arguments.of("PA2  ", PfKey.PA2),
                Arguments.of("PA1", PfKey.PA1),
                Arguments.of("PA2", PfKey.PA2),
                Arguments.of("PFK01", PfKey.PFK01),
                Arguments.of("PFK02", PfKey.PFK02),
                Arguments.of("PFK03", PfKey.PFK03),
                Arguments.of("PFK04", PfKey.PFK04),
                Arguments.of("PFK05", PfKey.PFK05),
                Arguments.of("PFK06", PfKey.PFK06),
                Arguments.of("PFK07", PfKey.PFK07),
                Arguments.of("PFK08", PfKey.PFK08),
                Arguments.of("PFK09", PfKey.PFK09),
                Arguments.of("PFK10", PfKey.PFK10),
                Arguments.of("PFK11", PfKey.PFK11),
                Arguments.of("PFK12", PfKey.PFK12),
                Arguments.of("  PFK03  ", PfKey.PFK03),
                Arguments.of("", PfKey.OTHER),
                Arguments.of("   ", PfKey.OTHER),
                Arguments.of("ZZZ", PfKey.OTHER),
                Arguments.of("PFK99", PfKey.OTHER),
                Arguments.of("PFK00", PfKey.OTHER),
                Arguments.of("enter", PfKey.OTHER));
    }

    @ParameterizedTest(name = "fromAid(\"{0}\") -> {1}")
    @MethodSource("pfKeyMappingCases")
    @DisplayName("PfKey.fromAid maps recognized tokens and falls back to OTHER")
    void fromAid_maps_recognized_and_unrecognized_tokens(String aid, PfKey expected) {
        assertThat(PfKey.fromAid(aid)).isEqualTo(expected);
    }

    @Test
    void fromAid_null_returns_other_and_never_throws() {
        assertThatCode(() -> PfKey.fromAid(null)).doesNotThrowAnyException();
        assertThat(PfKey.fromAid(null)).isEqualTo(PfKey.OTHER);
    }

    // ---------------------------------------------------------------------------------
    // Phase C - getPfKey() derives from the aid field via PfKey.fromAid
    // ---------------------------------------------------------------------------------

    @Test
    void getPfKey_returns_pfk03_when_aid_is_pfk03() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAid("PFK03");
        assertThat(wa.getPfKey()).isEqualTo(PfKey.PFK03);
    }

    @Test
    void getPfKey_returns_pa1_when_aid_is_space_padded() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAid("PA1  ");
        assertThat(wa.getPfKey()).isEqualTo(PfKey.PA1);
    }

    @Test
    void getPfKey_returns_enter_when_aid_is_enter() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAid("ENTER");
        assertThat(wa.getPfKey()).isEqualTo(PfKey.ENTER);
    }

    @Test
    void getPfKey_returns_other_for_unmapped_or_default_aid() {
        CardWorkArea unmapped = new CardWorkArea();
        unmapped.setAid("XYZ");
        assertThat(unmapped.getPfKey()).isEqualTo(PfKey.OTHER);

        CardWorkArea fresh = new CardWorkArea();
        assertThatCode(() -> fresh.getPfKey()).doesNotThrowAnyException();
        assertThat(fresh.getPfKey()).isEqualTo(PfKey.OTHER);
    }

    // ---------------------------------------------------------------------------------
    // Phase D - numeric-view helpers (Long, null-safe, never throw)
    // ---------------------------------------------------------------------------------

    @Test
    void getAcctIdNumeric_parses_zero_padded_value() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAcctId("00000012345");
        assertThat(wa.getAcctIdNumeric()).isEqualTo(12345L);
    }

    @Test
    void getCardNumNumeric_parses_sixteen_digit_value() {
        CardWorkArea wa = new CardWorkArea();
        wa.setCardNum("4111111111111111");
        assertThat(wa.getCardNumNumeric()).isEqualTo(4111111111111111L);
    }

    @Test
    void getCustIdNumeric_parses_zero_padded_value() {
        CardWorkArea wa = new CardWorkArea();
        wa.setCustId("000000042");
        assertThat(wa.getCustIdNumeric()).isEqualTo(42L);
    }

    @Test
    void numeric_helpers_return_null_for_default_and_blank_ids() {
        CardWorkArea fresh = new CardWorkArea();
        assertThat(fresh.getAcctIdNumeric()).isNull();
        assertThat(fresh.getCardNumNumeric()).isNull();
        assertThat(fresh.getCustIdNumeric()).isNull();

        CardWorkArea blank = new CardWorkArea();
        blank.setAcctId("   ");
        blank.setCardNum("");
        blank.setCustId("       ");
        assertThat(blank.getAcctIdNumeric()).isNull();
        assertThat(blank.getCardNumNumeric()).isNull();
        assertThat(blank.getCustIdNumeric()).isNull();
    }

    @Test
    void numeric_helpers_return_null_for_non_numeric_ids() {
        CardWorkArea wa = new CardWorkArea();
        wa.setCustId("ABC");
        wa.setAcctId("12A45");
        wa.setCardNum("4111-1111");
        assertThatCode(() -> wa.getCustIdNumeric()).doesNotThrowAnyException();
        assertThat(wa.getCustIdNumeric()).isNull();
        assertThat(wa.getAcctIdNumeric()).isNull();
        assertThat(wa.getCardNumNumeric()).isNull();
    }

    @Test
    void numeric_helper_returns_null_for_null_field_without_throwing() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAcctId(null);
        assertThatCode(() -> wa.getAcctIdNumeric()).doesNotThrowAnyException();
        assertThat(wa.getAcctIdNumeric()).isNull();
    }

    @Test
    void getCardNumNumeric_returns_null_when_value_exceeds_long_range() {
        CardWorkArea wa = new CardWorkArea();
        // 20 all-digit characters exceed Long.MAX_VALUE (19 digits); the helper must yield
        // null rather than propagate NumberFormatException (COBOL redefinition parity).
        wa.setCardNum("99999999999999999999");
        assertThatCode(() -> wa.getCardNumNumeric()).doesNotThrowAnyException();
        assertThat(wa.getCardNumNumeric()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // Phase E - plain getters/setters round-trip + constructor defaults
    // ---------------------------------------------------------------------------------

    @Test
    void string_fields_round_trip_through_getters_and_setters() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAid("PFK03");
        wa.setNextProg("COMEN01C");
        wa.setNextMapset("COMEN01");
        wa.setNextMap("COMEN1A");
        wa.setErrorMsg("Account not found.");
        wa.setReturnMsg("Returning to menu.");
        wa.setAcctId("00000012345");
        wa.setCardNum("4111111111111111");
        wa.setCustId("000000042");

        assertThat(wa.getAid()).isEqualTo("PFK03");
        assertThat(wa.getNextProg()).isEqualTo("COMEN01C");
        assertThat(wa.getNextMapset()).isEqualTo("COMEN01");
        assertThat(wa.getNextMap()).isEqualTo("COMEN1A");
        assertThat(wa.getErrorMsg()).isEqualTo("Account not found.");
        assertThat(wa.getReturnMsg()).isEqualTo("Returning to menu.");
        assertThat(wa.getAcctId()).isEqualTo("00000012345");
        assertThat(wa.getCardNum()).isEqualTo("4111111111111111");
        assertThat(wa.getCustId()).isEqualTo("000000042");
    }

    @Test
    void constructor_defaults_identifier_fields_to_empty_string_and_others_to_null() {
        CardWorkArea wa = new CardWorkArea();
        // CC-ACCT-ID / CC-CARD-NUM / CC-CUST-ID mirror COBOL VALUE SPACES -> empty string.
        assertThat(wa.getAcctId()).isEmpty();
        assertThat(wa.getCardNum()).isEmpty();
        assertThat(wa.getCustId()).isEmpty();
        // All other fields are unset (null) until populated per request.
        assertThat(wa.getAid()).isNull();
        assertThat(wa.getNextProg()).isNull();
        assertThat(wa.getNextMapset()).isNull();
        assertThat(wa.getNextMap()).isNull();
        assertThat(wa.getErrorMsg()).isNull();
        assertThat(wa.getReturnMsg()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // Phase F - optional methods present in the production source (isReturnMsgOff,
    //           equals/hashCode, toString)
    // ---------------------------------------------------------------------------------

    @Test
    void isReturnMsgOff_is_true_for_null_or_blank_return_message() {
        CardWorkArea wa = new CardWorkArea();
        assertThat(wa.isReturnMsgOff()).isTrue();      // default: returnMsg is null
        wa.setReturnMsg("");
        assertThat(wa.isReturnMsgOff()).isTrue();
        wa.setReturnMsg("    ");
        assertThat(wa.isReturnMsgOff()).isTrue();
    }

    @Test
    void isReturnMsgOff_is_false_when_return_message_present() {
        CardWorkArea wa = new CardWorkArea();
        wa.setReturnMsg("Balance updated.");
        assertThat(wa.isReturnMsgOff()).isFalse();
    }

    @Test
    void equals_and_hashcode_are_consistent_for_equal_instances() {
        CardWorkArea a = new CardWorkArea();
        a.setAid("PFK03");
        a.setAcctId("00000012345");
        CardWorkArea b = new CardWorkArea();
        b.setAid("PFK03");
        b.setAcctId("00000012345");

        assertThat(a).isEqualTo(a);           // reflexive
        assertThat(a).isEqualTo(b);           // equal by value
        assertThat(a).hasSameHashCodeAs(b);   // hashCode consistent with equals
    }

    @Test
    void equals_distinguishes_differing_instances_null_and_other_types() {
        CardWorkArea a = new CardWorkArea();
        a.setAid("PFK03");
        CardWorkArea different = new CardWorkArea();
        different.setAid("PFK08");

        assertThat(a).isNotEqualTo(different);
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("not a work area")).isFalse();
    }

    @Test
    void toString_uses_safe_identity_form() {
        CardWorkArea wa = new CardWorkArea();
        wa.setAid("PFK03");
        wa.setAcctId("00000012345");
        assertThat(wa.toString())
                .isNotNull()
                .startsWith("CardWorkArea@");
    }
}
