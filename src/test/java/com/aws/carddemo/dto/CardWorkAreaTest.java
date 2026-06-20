/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CardWorkArea}, the Java migration of the COBOL {@code CC-WORK-AREAS}
 * copybook ({@code legacy/app/cpy/CVCRD01Y.cpy}).
 *
 * <p>These tests pin the fixed COBOL parity invariants that cannot vary across implementations:
 *
 * <ul>
 *   <li>the {@link CardWorkArea.Aid} enum has EXACTLY 16 constants whose codes are the exact
 *       5-character 3270 AID literals — including the two trailing spaces on {@code PA1}/{@code
 *       PA2} (AAP &sect;0.6.5);
 *   <li>{@link CardWorkArea#isReturnMsgOff()} mirrors the {@code 88 CCARD-RETURN-MSG-OFF VALUE
 *       LOW-VALUES} condition; and
 *   <li>the three REDEFINES id pairs keep the SPACES (unset) state distinguishable from a real
 *       {@code 0}: the numeric overlay returns {@code null} on SPACES rather than collapsing to
 *       {@code 0} (AAP &sect;0.6.6).
 * </ul>
 *
 * <p>The test is intentionally framework-light: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new CardWorkArea()} / {@code CardWorkArea.Aid} access and AssertJ fluent
 * assertions, exactly matching the production class's framework-free design.
 */
class CardWorkAreaTest {

  // -----------------------------------------------------------------------------------------------
  // 3a. AID / PF-key enum — EXACTLY 16 constants with EXACT 5-character codes (CVCRD01Y L3-19).
  // -----------------------------------------------------------------------------------------------

  @Test
  void aid_enum_has_exactly_sixteen_constants() {
    assertThat(CardWorkArea.Aid.values()).hasSize(16);
  }

  @Test
  void aid_codes_match_exact_cobol_literals() {
    assertThat(CardWorkArea.Aid.ENTER.getCode()).isEqualTo("ENTER");
    assertThat(CardWorkArea.Aid.CLEAR.getCode()).isEqualTo("CLEAR");
    // PA1 / PA2 are 3 characters + two trailing spaces (the classic regression trap).
    assertThat(CardWorkArea.Aid.PA1.getCode()).isEqualTo("PA1  ");
    assertThat(CardWorkArea.Aid.PA2.getCode()).isEqualTo("PA2  ");
    assertThat(CardWorkArea.Aid.PFK01.getCode()).isEqualTo("PFK01");
    assertThat(CardWorkArea.Aid.PFK02.getCode()).isEqualTo("PFK02");
    assertThat(CardWorkArea.Aid.PFK03.getCode()).isEqualTo("PFK03");
    assertThat(CardWorkArea.Aid.PFK04.getCode()).isEqualTo("PFK04");
    assertThat(CardWorkArea.Aid.PFK05.getCode()).isEqualTo("PFK05");
    assertThat(CardWorkArea.Aid.PFK06.getCode()).isEqualTo("PFK06");
    assertThat(CardWorkArea.Aid.PFK07.getCode()).isEqualTo("PFK07");
    assertThat(CardWorkArea.Aid.PFK08.getCode()).isEqualTo("PFK08");
    assertThat(CardWorkArea.Aid.PFK09.getCode()).isEqualTo("PFK09");
    assertThat(CardWorkArea.Aid.PFK10.getCode()).isEqualTo("PFK10");
    assertThat(CardWorkArea.Aid.PFK11.getCode()).isEqualTo("PFK11");
    assertThat(CardWorkArea.Aid.PFK12.getCode()).isEqualTo("PFK12");
  }

  @Test
  void every_aid_code_is_exactly_five_characters_wide() {
    // The 3270 AID field is a fixed-width PIC X(5); no code may be trimmed or padded differently.
    for (CardWorkArea.Aid aid : CardWorkArea.Aid.values()) {
      assertThat(aid.getCode())
          .as("AID %s code must be a fixed-width 5-character 3270 code", aid.name())
          .hasSize(5);
    }
  }

  @Test
  void pa1_and_pa2_retain_two_trailing_spaces() {
    // Pinned explicitly because dropping the trailing spaces is the most common parity defect.
    assertThat(CardWorkArea.Aid.PA1.getCode()).isEqualTo("PA1  ").hasSize(5);
    assertThat(CardWorkArea.Aid.PA2.getCode()).isEqualTo("PA2  ").hasSize(5);
  }

  @Test
  void from_code_round_trips_for_every_aid_constant() {
    for (CardWorkArea.Aid aid : CardWorkArea.Aid.values()) {
      assertThat(CardWorkArea.Aid.fromCode(aid.getCode())).isSameAs(aid);
    }
  }

  @Test
  void from_code_returns_null_for_unknown_or_null_code() {
    // Mirrors COBOL's fall-through when no CCARD-AID 88-level matches.
    assertThat(CardWorkArea.Aid.fromCode("ZZZZZ")).isNull();
    assertThat(CardWorkArea.Aid.fromCode(null)).isNull();
    assertThat(CardWorkArea.Aid.fromCode("")).isNull();
    // The bare 3-character "PA1" must NOT match the 5-character "PA1  " (trailing-space contract).
    assertThat(CardWorkArea.Aid.fromCode("PA1")).isNull();
  }

  // -----------------------------------------------------------------------------------------------
  // 3b. AID predicates (88-level condition names).
  // -----------------------------------------------------------------------------------------------

  @Test
  void aid_predicates_reflect_current_aid() {
    CardWorkArea wa = new CardWorkArea();

    // Set via the enum-typed setter: exactly one predicate is true.
    wa.setAid(CardWorkArea.Aid.ENTER);
    assertThat(wa.getAid()).isSameAs(CardWorkArea.Aid.ENTER);
    assertThat(wa.getCcardAid()).isEqualTo("ENTER");
    assertThat(wa.isAidEnter()).isTrue();
    assertThat(wa.isAidClear()).isFalse();
    assertThat(wa.isAidPa1()).isFalse();
    assertThat(wa.isAidPfk12()).isFalse();

    // A function PF key.
    wa.setAid(CardWorkArea.Aid.PFK07);
    assertThat(wa.isAidPfk07()).isTrue();
    assertThat(wa.isAidEnter()).isFalse();
    assertThat(wa.isAidPfk06()).isFalse();
    assertThat(wa.isAidPfk08()).isFalse();

    // The trailing-space PA keys, set via the raw-string storage path.
    wa.setCcardAid("PA1  ");
    assertThat(wa.isAidPa1()).isTrue();
    assertThat(wa.isAidPa2()).isFalse();
    assertThat(wa.getAid()).isSameAs(CardWorkArea.Aid.PA1);

    wa.setCcardAid("PA2  ");
    assertThat(wa.isAidPa2()).isTrue();
    assertThat(wa.isAidPa1()).isFalse();
  }

  @Test
  void fresh_instance_has_no_active_aid() {
    CardWorkArea wa = new CardWorkArea();

    // Default CCARD-AID is five spaces — matches no 88-level (the "no key pressed" state).
    assertThat(wa.getCcardAid()).isEqualTo(" ".repeat(CardWorkArea.CCARD_AID_LEN)).hasSize(5);
    assertThat(wa.getAid()).isNull();
    assertThat(wa.isAidEnter()).isFalse();
    assertThat(wa.isAidClear()).isFalse();
    assertThat(wa.isAidPa1()).isFalse();
    assertThat(wa.isAidPa2()).isFalse();
    assertThat(wa.isAidPfk01()).isFalse();
    assertThat(wa.isAidPfk12()).isFalse();
  }

  // -----------------------------------------------------------------------------------------------
  // 3c. Active navigation + message fields round-trip (CVCRD01Y L21-29).
  // -----------------------------------------------------------------------------------------------

  @Test
  void navigation_and_message_fields_round_trip() {
    CardWorkArea wa = new CardWorkArea();

    wa.setCcardNextProg("COMEN01C"); // CCARD-NEXT-PROG   PIC X(8)
    wa.setCcardNextMapset("COMEN01"); // CCARD-NEXT-MAPSET PIC X(7)
    wa.setCcardNextMap("COMEN1A"); // CCARD-NEXT-MAP    PIC X(7)
    wa.setCcardErrorMsg("Account ID must be numeric."); // CCARD-ERROR-MSG  PIC X(75)
    wa.setCcardReturnMsg("Press ENTER to continue."); // CCARD-RETURN-MSG PIC X(75)

    assertThat(wa.getCcardNextProg()).isEqualTo("COMEN01C");
    assertThat(wa.getCcardNextMapset()).isEqualTo("COMEN01");
    assertThat(wa.getCcardNextMap()).isEqualTo("COMEN1A");
    assertThat(wa.getCcardErrorMsg()).isEqualTo("Account ID must be numeric.");
    assertThat(wa.getCcardReturnMsg()).isEqualTo("Press ENTER to continue.");
  }

  // -----------------------------------------------------------------------------------------------
  // 3d. Return-message-off helper (88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES — L30).
  // -----------------------------------------------------------------------------------------------

  @Test
  void return_msg_off_is_true_when_null_empty_or_blank() {
    CardWorkArea wa = new CardWorkArea();

    // Fresh instance: CCARD-RETURN-MSG is unset (mirrors LOW-VALUES = "off").
    assertThat(wa.getCcardReturnMsg()).isNull();
    assertThat(wa.isReturnMsgOff()).isTrue();

    wa.setCcardReturnMsg(null);
    assertThat(wa.isReturnMsgOff()).isTrue();

    wa.setCcardReturnMsg("");
    assertThat(wa.isReturnMsgOff()).isTrue();

    wa.setCcardReturnMsg("     ");
    assertThat(wa.isReturnMsgOff()).isTrue();
  }

  @Test
  void return_msg_off_is_false_when_message_present() {
    CardWorkArea wa = new CardWorkArea();

    wa.setCcardReturnMsg("Invalid card number");
    assertThat(wa.isReturnMsgOff()).isFalse();
  }

  // -----------------------------------------------------------------------------------------------
  // 3e. REDEFINES paired accessors — SPACES-vs-numeric distinction (CVCRD01Y L34-42, §0.6.6).
  // -----------------------------------------------------------------------------------------------

  @Test
  void acct_id_redefines_pair_preserves_spaces_and_numeric_views() {
    CardWorkArea wa = new CardWorkArea();

    // Default = SPACES (unset): string view is blank/width-sized; numeric view signals unset.
    assertThat(wa.getCcAcctId()).isBlank().hasSize(CardWorkArea.CC_ACCT_ID_LEN);
    assertThat(wa.isCcAcctIdSpaces()).isTrue();
    assertThat(wa.getCcAcctIdN()).isNull();

    // Numeric setter zero-pads right-justified to the field width (11).
    wa.setCcAcctIdN(123L);
    assertThat(wa.getCcAcctId()).isEqualTo("00000000123");
    assertThat(wa.getCcAcctIdN()).isEqualTo(123L);
    assertThat(wa.isCcAcctIdSpaces()).isFalse();

    // String view set to a numeric string stays consistent with the numeric view.
    CardWorkArea other = new CardWorkArea();
    other.setCcAcctId("00000000123");
    assertThat(other.getCcAcctIdN()).isEqualTo(123L);
    assertThat(other.isCcAcctIdSpaces()).isFalse();
  }

  @Test
  void card_num_redefines_pair_preserves_spaces_and_numeric_views() {
    CardWorkArea wa = new CardWorkArea();

    assertThat(wa.getCcCardNum()).isBlank().hasSize(CardWorkArea.CC_CARD_NUM_LEN);
    assertThat(wa.isCcCardNumSpaces()).isTrue();
    assertThat(wa.getCcCardNumN()).isNull();

    // Numeric setter zero-pads right-justified to the field width (16).
    wa.setCcCardNumN(123L);
    assertThat(wa.getCcCardNum()).isEqualTo("0000000000000123");
    assertThat(wa.getCcCardNumN()).isEqualTo(123L);
    assertThat(wa.isCcCardNumSpaces()).isFalse();

    CardWorkArea other = new CardWorkArea();
    other.setCcCardNum("0000000000000123");
    assertThat(other.getCcCardNumN()).isEqualTo(123L);
    assertThat(other.isCcCardNumSpaces()).isFalse();
  }

  @Test
  void cust_id_redefines_pair_preserves_spaces_and_numeric_views() {
    CardWorkArea wa = new CardWorkArea();

    assertThat(wa.getCcCustId()).isBlank().hasSize(CardWorkArea.CC_CUST_ID_LEN);
    assertThat(wa.isCcCustIdSpaces()).isTrue();
    assertThat(wa.getCcCustIdN()).isNull();

    // Numeric setter zero-pads right-justified to the field width (9).
    wa.setCcCustIdN(123L);
    assertThat(wa.getCcCustId()).isEqualTo("000000123");
    assertThat(wa.getCcCustIdN()).isEqualTo(123L);
    assertThat(wa.isCcCustIdSpaces()).isFalse();

    CardWorkArea other = new CardWorkArea();
    other.setCcCustId("000000123");
    assertThat(other.getCcCustIdN()).isEqualTo(123L);
    assertThat(other.isCcCustIdSpaces()).isFalse();
  }

  @Test
  void fresh_numeric_views_signal_unset_with_null_not_zero() {
    CardWorkArea wa = new CardWorkArea();

    // The core REDEFINES parity point (AAP §0.6.6): an uninitialized (SPACES) id must stay
    // distinguishable from a real zero. The numeric overlay returns null, never 0.
    assertThat(wa.getCcAcctIdN()).isNull();
    assertThat(wa.getCcCardNumN()).isNull();
    assertThat(wa.getCcCustIdN()).isNull();
  }

  // -----------------------------------------------------------------------------------------------
  // Numeric overlay edge contracts (unsigned, fixed-width, numeric-only) — documented by the class.
  // -----------------------------------------------------------------------------------------------

  @Test
  void numeric_setter_rejects_negative_values() {
    CardWorkArea wa = new CardWorkArea();

    // COBOL PIC 9(n) is unsigned; a negative value cannot be represented.
    assertThatThrownBy(() -> wa.setCcAcctIdN(-1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wa.setCcCardNumN(-1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wa.setCcCustIdN(-1L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void numeric_setter_rejects_values_exceeding_field_width() {
    CardWorkArea wa = new CardWorkArea();

    // CC-CUST-ID-N is PIC 9(9); a 10-digit value does not fit the fixed-width field.
    assertThatThrownBy(() -> wa.setCcCustIdN(1_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
    // CC-ACCT-ID-N is PIC 9(11); a 12-digit value does not fit.
    assertThatThrownBy(() -> wa.setCcAcctIdN(100_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void numeric_getter_throws_when_string_view_is_non_numeric() {
    CardWorkArea wa = new CardWorkArea();

    // A non-blank, non-numeric string view cannot be interpreted as the PIC 9(n) overlay.
    wa.setCcAcctId("ABCDEFGHIJK"); // 11 non-numeric chars, fills the X(11) field
    assertThatThrownBy(() -> wa.getCcAcctIdN()).isInstanceOf(NumberFormatException.class);
  }
}
