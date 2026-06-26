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

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CardDemoCommarea}, the Java migration of the COBOL {@code
 * CARDDEMO-COMMAREA} communication area (copybook {@code COCOM01Y}, legacy source {@code
 * legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>The COMMAREA carries pseudo-conversational session/navigation state across CICS {@code
 * RECEIVE}/{@code SEND} cycles. These tests pin the <strong>fixed COBOL parity invariants</strong>
 * that the modernized online layer depends on (Agent Action Plan &sect;0.6.5):
 *
 * <ul>
 *   <li>the {@code CDEMO-USER-TYPE} 88-level role flags resolve to <em>exactly</em> the uppercase
 *       literals {@code 'A'} (admin) and {@code 'U'} (user) and are case-sensitive;
 *   <li>the {@code CDEMO-PGM-CONTEXT} 88-level flags resolve to {@code 0} (enter) and {@code 1}
 *       (re-enter); and
 *   <li>the from/to program-tranid-mapset routing fields round-trip verbatim across cycles.
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ only. There is no Spring context, database, Testcontainers, or Mockito.
 */
class CardDemoCommareaTest {

  // ===== 3a. Role-flag parity (CDEMO-USER-TYPE 88-levels — COCOM01Y L26-28) ====================

  @Test
  void isAdmin_returns_true_only_when_user_type_is_uppercase_A() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserType("A");

    assertThat(commarea.isAdmin()).isTrue();
    assertThat(commarea.isUser()).isFalse();
  }

  @Test
  void isUser_returns_true_only_when_user_type_is_uppercase_U() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserType("U");

    assertThat(commarea.isUser()).isTrue();
    assertThat(commarea.isAdmin()).isFalse();
  }

  @Test
  void role_flags_are_mutually_exclusive() {
    CardDemoCommarea admin = new CardDemoCommarea();
    admin.setUserType("A");
    assertThat(admin.isAdmin()).isTrue();
    assertThat(admin.isUser()).isFalse();

    CardDemoCommarea user = new CardDemoCommarea();
    user.setUserType("U");
    assertThat(user.isUser()).isTrue();
    assertThat(user.isAdmin()).isFalse();
  }

  @Test
  void isAdmin_and_isUser_are_false_for_null_empty_lowercase_and_other_values() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    // Fresh instance: userType is null.
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();

    // Explicit null.
    commarea.setUserType(null);
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();

    // Empty string.
    commarea.setUserType("");
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();

    // Case sensitivity: the COBOL 88-level matches the EXACT uppercase literal, so the
    // lowercase variants must NOT satisfy the role flags.
    commarea.setUserType("a");
    assertThat(commarea.isAdmin()).isFalse();
    commarea.setUserType("u");
    assertThat(commarea.isUser()).isFalse();

    // Any other value.
    commarea.setUserType("X");
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();
  }

  @Test
  void setUsrTypAdmin_sets_user_type_to_uppercase_A() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();

    assertThat(commarea.getUserType()).isEqualTo("A");
    assertThat(commarea.isAdmin()).isTrue();
    assertThat(commarea.isUser()).isFalse();
  }

  @Test
  void setUsrTypUser_sets_user_type_to_uppercase_U() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();

    assertThat(commarea.getUserType()).isEqualTo("U");
    assertThat(commarea.isUser()).isTrue();
    assertThat(commarea.isAdmin()).isFalse();
  }

  @Test
  void user_type_constants_carry_the_cobol_88_level_literals() {
    assertThat(CardDemoCommarea.USER_TYPE_ADMIN).isEqualTo("A");
    assertThat(CardDemoCommarea.USER_TYPE_USER).isEqualTo("U");
  }

  // ===== 3b. Program-context parity (CDEMO-PGM-CONTEXT 88-levels — COCOM01Y L29-31) ============

  @Test
  void isPgmEnter_is_true_only_when_context_is_zero() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmContext(0);

    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.isPgmReenter()).isFalse();
  }

  @Test
  void isPgmReenter_is_true_only_when_context_is_one() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmContext(1);

    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(commarea.isPgmEnter()).isFalse();
  }

  @Test
  void pgm_context_other_value_makes_both_enter_and_reenter_false() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmContext(2);

    assertThat(commarea.isPgmEnter()).isFalse();
    assertThat(commarea.isPgmReenter()).isFalse();
  }

  @Test
  void setPgmEnter_and_setPgmReenter_set_context_to_zero_and_one() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    commarea.setPgmReenter();
    assertThat(commarea.getPgmContext()).isEqualTo(1);
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(commarea.isPgmEnter()).isFalse();

    commarea.setPgmEnter();
    assertThat(commarea.getPgmContext()).isZero();
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.isPgmReenter()).isFalse();
  }

  @Test
  void pgm_context_constants_carry_the_cobol_88_level_values() {
    assertThat(CardDemoCommarea.PGM_CONTEXT_ENTER).isZero();
    assertThat(CardDemoCommarea.PGM_CONTEXT_REENTER).isEqualTo(1);
  }

  // ===== 3c. Navigation-state round-trip (CDEMO-GENERAL-INFO / MORE-INFO — §0.6.5) =============

  @Test
  void navigation_routing_fields_round_trip_exactly_as_set() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setFromTranId("CC00"); // PIC X(04)
    commarea.setFromProgram("COSGN00C"); // PIC X(08)
    commarea.setToTranId("CM00"); // PIC X(04)
    commarea.setToProgram("COMEN01C"); // PIC X(08)
    commarea.setUserId("ADMIN001"); // PIC X(08)
    commarea.setLastMap("COSGN0A"); // PIC X(07)
    commarea.setLastMapset("COSGN00"); // PIC X(07)

    assertThat(commarea.getFromTranId()).isEqualTo("CC00");
    assertThat(commarea.getFromProgram()).isEqualTo("COSGN00C");
    assertThat(commarea.getToTranId()).isEqualTo("CM00");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getUserId()).isEqualTo("ADMIN001");
    assertThat(commarea.getLastMap()).isEqualTo("COSGN0A");
    assertThat(commarea.getLastMapset()).isEqualTo("COSGN00");
  }

  @Test
  void navigation_fields_are_stored_verbatim_without_padding_or_truncation() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    // The DTO stores the String exactly as supplied; it neither right-pads to the COBOL
    // fixed width nor truncates over-length values. Callers own the fixed-width contract.
    commarea.setFromTranId("X");
    commarea.setFromProgram("LONGER-THAN-EIGHT-CHARS");

    assertThat(commarea.getFromTranId()).isEqualTo("X");
    assertThat(commarea.getFromProgram()).isEqualTo("LONGER-THAN-EIGHT-CHARS");
  }

  // ===== 3d. Customer / account / card field round-trip ========================================

  @Test
  void customer_account_and_card_fields_round_trip_exactly_as_set() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setCustId(123456789L); // PIC 9(09)
    commarea.setCustFName("JOHN");
    commarea.setCustMName("Q");
    commarea.setCustLName("PUBLIC");
    commarea.setAcctId(12345678901L); // PIC 9(11)
    commarea.setAcctStatus("Y");
    commarea.setCardNum(4111111111111111L); // PIC 9(16)

    assertThat(commarea.getCustId()).isEqualTo(123456789L);
    assertThat(commarea.getCustFName()).isEqualTo("JOHN");
    assertThat(commarea.getCustMName()).isEqualTo("Q");
    assertThat(commarea.getCustLName()).isEqualTo("PUBLIC");
    assertThat(commarea.getAcctId()).isEqualTo(12345678901L);
    assertThat(commarea.getAcctStatus()).isEqualTo("Y");
    assertThat(commarea.getCardNum()).isEqualTo(4111111111111111L);
  }

  // ===== 3e. Default / fresh-instance state ====================================================

  @Test
  void fresh_instance_has_cobol_low_value_defaults() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    // String fields default to null (the no-args constructor performs no initialization).
    assertThat(commarea.getFromTranId()).isNull();
    assertThat(commarea.getFromProgram()).isNull();
    assertThat(commarea.getToTranId()).isNull();
    assertThat(commarea.getToProgram()).isNull();
    assertThat(commarea.getUserId()).isNull();
    assertThat(commarea.getUserType()).isNull();
    assertThat(commarea.getCustFName()).isNull();
    assertThat(commarea.getCustMName()).isNull();
    assertThat(commarea.getCustLName()).isNull();
    assertThat(commarea.getAcctStatus()).isNull();
    assertThat(commarea.getLastMap()).isNull();
    assertThat(commarea.getLastMapset()).isNull();

    // Boxed numeric identifiers default to null.
    assertThat(commarea.getCustId()).isNull();
    assertThat(commarea.getAcctId()).isNull();
    assertThat(commarea.getCardNum()).isNull();

    // The primitive program context defaults to 0, so a fresh COMMAREA is in "enter" state.
    assertThat(commarea.getPgmContext()).isZero();
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.isPgmReenter()).isFalse();

    // No role is selected by default.
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();
  }

  // ===== Object contract (equals / hashCode / toString PII masking) ============================

  @Test
  void equals_and_hashCode_follow_value_semantics() {
    CardDemoCommarea a = populatedCommarea();
    CardDemoCommarea b = populatedCommarea();

    // Reflexive.
    assertThat(a).isEqualTo(a);

    // Equal by value, with a consistent hash code.
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);

    // Not equal to null or to an unrelated type (exercises the instanceof guard).
    assertThat(a).isNotEqualTo(null);
    assertThat(a).isNotEqualTo("not a commarea");

    // A single differing field breaks equality.
    b.setUserId("OTHER999");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void toString_masks_card_number_to_last_four_digits() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setCardNum(4111111111111111L);
    commarea.setUserId("ADMIN001");

    String rendered = commarea.toString();

    // Only the last four digits are revealed, behind a run of mask characters (PII hygiene,
    // AAP §0.6.6); the full 16-digit PAN must never appear in the rendering.
    assertThat(rendered).containsPattern("cardNum=\\*+1111");
    assertThat(rendered).doesNotContain("4111111111111111");

    // Non-secret navigation context remains visible for diagnostics.
    assertThat(rendered).contains("userId=ADMIN001");
  }

  @Test
  void toString_renders_null_card_number_without_failing() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    assertThat(commarea.toString()).contains("cardNum=null");
  }

  /**
   * Builds a fully populated communication area for value-semantics assertions. Each invocation
   * returns a distinct instance, so callers can compare two equal-by-value objects.
   *
   * @return a new, fully populated {@link CardDemoCommarea}
   */
  private static CardDemoCommarea populatedCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setFromTranId("CC00");
    commarea.setFromProgram("COSGN00C");
    commarea.setToTranId("CM00");
    commarea.setToProgram("COMEN01C");
    commarea.setUserId("ADMIN001");
    commarea.setUserType("A");
    commarea.setPgmContext(1);
    commarea.setCustId(123456789L);
    commarea.setCustFName("JOHN");
    commarea.setCustMName("Q");
    commarea.setCustLName("PUBLIC");
    commarea.setAcctId(12345678901L);
    commarea.setAcctStatus("Y");
    commarea.setCardNum(4111111111111111L);
    commarea.setLastMap("COSGN0A");
    commarea.setLastMapset("COSGN00");
    return commarea;
  }
}
