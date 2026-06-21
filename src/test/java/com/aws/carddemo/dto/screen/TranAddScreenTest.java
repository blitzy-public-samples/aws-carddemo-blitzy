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
package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TranAddScreen}, the screen view contract migrated from the legacy
 * CICS/BMS mapset {@code COTRN02} (CICS transaction {@code CT02}). The authoritative field contract
 * comes from the symbolic copybook {@code legacy/app/cpy-bms/COTRN02.CPY} (input map {@code 01
 * COTRN2AI}) and the {@code LENGTH} attributes of {@code legacy/app/bms/COTRN02.bms}.
 *
 * <p>These tests are intentionally framework-light: there is no Spring context, database,
 * Testcontainers or Mockito. A {@link TranAddScreen} is constructed directly and its plain
 * getter/setter contract is exercised with AssertJ, guarding three parity properties:
 *
 * <ul>
 *   <li><b>Getter/setter round-trip</b> for every one of the 21 logical fields (AAP &sect;0.4.1).
 *   <li><b>Raw-amount fidelity</b>: {@code trnAmt} is the raw screen string ({@code TRNAMTI},
 *       {@code PIC X(12)}) carried verbatim — including malformed and negative text — so the
 *       service layer can validate it character-by-character and convert it to a scale-2 {@code
 *       BigDecimal} downstream without a binding failure (AAP &sect;0.6.1).
 *   <li><b>Fixed-width fidelity</b>: each {@code String} field round-trips the exact COBOL {@code
 *       PIC X(n)} / BMS {@code LENGTH} width without truncation (AAP &sect;0.4.1).
 * </ul>
 *
 * <p>The features that distinguish this add screen from the transaction-view screen {@code COTRN01}
 * are pinned explicitly: it carries the account-id input {@code actIdIn} ({@code X(11)}) and the
 * card-number input {@code cardNin} ({@code X(16)}) instead of a single transaction-id lookup, and
 * it adds the {@code confirm} ({@code X(1)}) add-confirmation flag.
 */
class TranAddScreenTest {

  /** The six screen header/label fields round-trip unchanged at their BMS widths. */
  @Test
  void header_label_fields_round_trip() {
    TranAddScreen screen = new TranAddScreen();

    screen.setTrnName("CT02");
    screen.setTitle01("AWS CARDDEMO CREDIT CARD MGMT SYSTEM 1.0");
    screen.setCurDate("08/22/22");
    screen.setPgmName("COTRN02C");
    screen.setTitle02("ADD TRANSACTION TO ACCOUNT OR CARD INPUT");
    screen.setCurTime("17:02:43");

    assertThat(screen.getTrnName()).isEqualTo("CT02").hasSize(4);
    assertThat(screen.getTitle01())
        .isEqualTo("AWS CARDDEMO CREDIT CARD MGMT SYSTEM 1.0")
        .hasSize(40);
    assertThat(screen.getCurDate()).isEqualTo("08/22/22").hasSize(8);
    assertThat(screen.getPgmName()).isEqualTo("COTRN02C").hasSize(8);
    assertThat(screen.getTitle02())
        .isEqualTo("ADD TRANSACTION TO ACCOUNT OR CARD INPUT")
        .hasSize(40);
    assertThat(screen.getCurTime()).isEqualTo("17:02:43").hasSize(8);
  }

  /**
   * The account-id and card-number inputs round-trip at their BMS widths. These two inputs are the
   * key feature distinguishing the transaction-add screen from the view screen, which instead looks
   * up a single transaction by its id.
   */
  @Test
  void account_and_card_input_fields_round_trip() {
    TranAddScreen screen = new TranAddScreen();

    screen.setActIdIn("00000000011");
    screen.setCardNin("4111111111111111");

    assertThat(screen.getActIdIn()).isEqualTo("00000000011").hasSize(11);
    assertThat(screen.getCardNin()).isEqualTo("4111111111111111").hasSize(16);
  }

  /** The transaction type code, category code and source round-trip at their BMS widths. */
  @Test
  void transaction_classification_fields_round_trip() {
    TranAddScreen screen = new TranAddScreen();

    screen.setTtypCd("01");
    screen.setTcatCd("0001");
    screen.setTrnSrc("POS-WEB-01");

    assertThat(screen.getTtypCd()).isEqualTo("01").hasSize(2);
    assertThat(screen.getTcatCd()).isEqualTo("0001").hasSize(4);
    assertThat(screen.getTrnSrc()).isEqualTo("POS-WEB-01").hasSize(10);
  }

  /** The description and the original/processing dates round-trip at their BMS widths. */
  @Test
  void description_and_date_fields_round_trip() {
    TranAddScreen screen = new TranAddScreen();

    screen.setTDesc("ONLINE PURCHASE OF ELECTRONIC GOODS FROM THE WEB STORE ORDER");
    screen.setTOrigDt("2022-08-22");
    screen.setTProcDt("2022-08-23");

    assertThat(screen.getTDesc())
        .isEqualTo("ONLINE PURCHASE OF ELECTRONIC GOODS FROM THE WEB STORE ORDER")
        .hasSize(60);
    assertThat(screen.getTOrigDt()).isEqualTo("2022-08-22").hasSize(10);
    assertThat(screen.getTProcDt()).isEqualTo("2022-08-23").hasSize(10);
  }

  /** The merchant id, name, city and zip round-trip at their BMS widths. */
  @Test
  void merchant_fields_round_trip() {
    TranAddScreen screen = new TranAddScreen();

    screen.setMid("123456789");
    screen.setMName("ACME ELECTRONICS SUPERSTORE LP");
    screen.setMCity("SAN FRANCISCO, CALIF USA.");
    screen.setMZip("94105-6789");

    assertThat(screen.getMid()).isEqualTo("123456789").hasSize(9);
    assertThat(screen.getMName()).isEqualTo("ACME ELECTRONICS SUPERSTORE LP").hasSize(30);
    assertThat(screen.getMCity()).isEqualTo("SAN FRANCISCO, CALIF USA.").hasSize(25);
    assertThat(screen.getMZip()).isEqualTo("94105-6789").hasSize(10);
  }

  /**
   * The single-character add-confirmation flag round-trips at its BMS width. This {@code confirm}
   * field is unique to the add screen's confirmation step and has no counterpart on the view
   * screen.
   */
  @Test
  void confirm_flag_round_trips() {
    TranAddScreen screen = new TranAddScreen();

    screen.setConfirm("Y");

    assertThat(screen.getConfirm()).isEqualTo("Y").hasSize(1);
  }

  /** The error/feedback message line round-trips at its full BMS width of 78. */
  @Test
  void error_message_field_round_trips() {
    TranAddScreen screen = new TranAddScreen();

    screen.setErrMsg(
        "ACCOUNT ID OR CARD NUMBER IS REQUIRED BEFORE ANY NEW TRANSACTION CAN BE ADDED.");

    assertThat(screen.getErrMsg())
        .isEqualTo("ACCOUNT ID OR CARD NUMBER IS REQUIRED BEFORE ANY NEW TRANSACTION CAN BE ADDED.")
        .hasSize(78);
  }

  /**
   * The transaction amount is carried as the raw screen string and round-trips verbatim. Decimal
   * fidelity (AAP &sect;0.6.1) is enforced by the service layer, which validates the text and
   * converts it to a scale-2 {@code BigDecimal}; the DTO itself is a pure transport contract.
   */
  @Test
  void trn_amt_round_trips_as_raw_string() {
    TranAddScreen screen = new TranAddScreen();

    screen.setTrnAmt("1234567.89");

    assertThat(screen.getTrnAmt()).isEqualTo("1234567.89");
  }

  /**
   * Malformed amount text (the exact non-numeric input that previously broke data binding) is
   * carried verbatim so the service layer can reject it with the COBOL field message rather than
   * the request abending. Negative amounts permitted by the BMS mask {@code (-99999999.99)}
   * likewise round-trip unchanged.
   */
  @Test
  void trn_amt_carries_malformed_and_negative_text_verbatim() {
    TranAddScreen screen = new TranAddScreen();

    screen.setTrnAmt("ABCDEFGH");
    assertThat(screen.getTrnAmt()).isEqualTo("ABCDEFGH");

    screen.setTrnAmt("-99.99");
    assertThat(screen.getTrnAmt()).isEqualTo("-99.99");
  }

  /** A freshly constructed screen has every field unset (null), including the amount. */
  @Test
  void fresh_instance_defaults_are_null() {
    TranAddScreen screen = new TranAddScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getActIdIn()).isNull();
    assertThat(screen.getCardNin()).isNull();
    assertThat(screen.getTtypCd()).isNull();
    assertThat(screen.getTcatCd()).isNull();
    assertThat(screen.getTrnSrc()).isNull();
    assertThat(screen.getTDesc()).isNull();
    assertThat(screen.getTrnAmt()).isNull();
    assertThat(screen.getTOrigDt()).isNull();
    assertThat(screen.getTProcDt()).isNull();
    assertThat(screen.getMid()).isNull();
    assertThat(screen.getMName()).isNull();
    assertThat(screen.getMCity()).isNull();
    assertThat(screen.getMZip()).isNull();
    assertThat(screen.getConfirm()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }
}
