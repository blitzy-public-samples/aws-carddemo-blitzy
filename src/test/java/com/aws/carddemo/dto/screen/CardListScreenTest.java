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

import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link CardListScreen}, the screen view contract migrated
 * from the legacy CICS/BMS card-list map {@code COCRDLI} (CICS transaction {@code CCLI}).
 *
 * <p>The legacy screen is a paged list with a fixed 3270 geometry of <strong>seven</strong>
 * repeating card rows. These tests pin the behavioural-parity invariants that matter for the
 * migration (Agent Action Plan &sect;0.1.1 / &sect;0.7.3):
 *
 * <ul>
 *   <li><b>Field round-trip</b> &mdash; every header, filter/paging and message scalar, and every
 *       logical row field, stores and returns its value unchanged.
 *   <li><b>Nested row type</b> &mdash; {@link CardListScreen.CardListRow} round-trips via its
 *       no-arg constructor, setters and getters.
 *   <li><b>List cardinality &times;7</b> &mdash; the fixed page geometry is preserved; the {@code
 *       rows} list holds exactly seven rows and index order maps to legacy row order.
 *   <li><b>Field-width fidelity</b> &mdash; the {@code @Size(max = n)} constraints match the BMS
 *       {@code LENGTH} metadata from {@code legacy/app/cpy-bms/COCRDLI.CPY} and {@code
 *       legacy/app/bms/COCRDLI.bms} (AAP &sect;0.4.1, "preserving field lengths").
 *   <li><b>Fresh-instance defaults</b> &mdash; a new screen has null scalars and a non-null, empty
 *       {@code rows} list.
 * </ul>
 *
 * <p>This is intentionally a framework-light unit test: no Spring context, database, Testcontainers
 * or mocking is involved &mdash; only plain construction, setters/getters and AssertJ assertions.
 */
class CardListScreenTest {

  /**
   * 3a. Every header, filter/paging and message scalar must round-trip through its setter/getter
   * pair without mutation (COCRDLI symbolic fields {@code TRNNAMEI}, {@code TITLE01I}, {@code
   * CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I}, {@code CURTIMEI}, {@code PAGENOI}, {@code
   * ACCTSIDI}, {@code CARDSIDI}, {@code INFOMSGO}, {@code ERRMSGO}).
   */
  @Test
  void header_and_filter_paging_fields_round_trip() {
    CardListScreen screen = new CardListScreen();

    screen.setTrnName("CCLI");
    screen.setTitle01("AWS Mainframe Modernization - CardDemo");
    screen.setCurDate("07/18/22");
    screen.setPgmName("COCRDLIC");
    screen.setTitle02("List Credit Cards");
    screen.setCurTime("12:30:45");
    screen.setPageNo("001");
    screen.setAcctSid("00000000011");
    screen.setCardSid("4111111111111111");
    screen.setInfoMsg("Type an account or card filter and press Enter.");
    screen.setErrMsg("Account number filter must be all numeric.");

    assertThat(screen.getTrnName()).isEqualTo("CCLI");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization - CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("07/18/22");
    assertThat(screen.getPgmName()).isEqualTo("COCRDLIC");
    assertThat(screen.getTitle02()).isEqualTo("List Credit Cards");
    assertThat(screen.getCurTime()).isEqualTo("12:30:45");
    assertThat(screen.getPageNo()).isEqualTo("001");
    assertThat(screen.getAcctSid()).isEqualTo("00000000011");
    assertThat(screen.getCardSid()).isEqualTo("4111111111111111");
    assertThat(screen.getInfoMsg()).isEqualTo("Type an account or card filter and press Enter.");
    assertThat(screen.getErrMsg()).isEqualTo("Account number filter must be all numeric.");
  }

  /**
   * 3b. The nested {@link CardListScreen.CardListRow} POJO must round-trip its four logical fields
   * ({@code CRDSEL}, {@code ACCTNO}, {@code CRDNUM}, {@code CRDSTS}) through its no-arg
   * constructor, setters and getters. The BMS protection-control / attribute plumbing fields
   * ({@code CRDSTP}, {@code *C}/{@code *P}/{@code *H}/{@code *V}) are deliberately not modelled.
   */
  @Test
  void card_list_row_round_trips_via_setters_and_getters() {
    CardListScreen.CardListRow row = new CardListScreen.CardListRow();

    row.setCrdSel("S");
    row.setAcctNo("00000000011");
    row.setCrdNum("4111111111111111");
    row.setCrdSts("Y");

    assertThat(row.getCrdSel()).isEqualTo("S");
    assertThat(row.getAcctNo()).isEqualTo("00000000011");
    assertThat(row.getCrdNum()).isEqualTo("4111111111111111");
    assertThat(row.getCrdSts()).isEqualTo("Y");
  }

  /**
   * 3c. CRITICAL parity invariant: the card-list screen has a fixed geometry of exactly seven rows.
   * After populating {@code rows} with seven distinct {@link CardListScreen.CardListRow} instances,
   * the list must report size 7 and preserve index order (index 0 = legacy row 1 &hellip; index 6 =
   * legacy row 7), verified at both ends.
   */
  @Test
  void rows_list_holds_exactly_seven_card_rows() {
    CardListScreen screen = new CardListScreen();

    List<CardListScreen.CardListRow> rows = new ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      // Distinct, fixed-width values per row prove the index -> row mapping is preserved.
      rows.add(
          newRow(
              "S", String.format("%011d", i), String.format("%016d", i), (i % 2 == 0) ? "N" : "Y"));
    }
    screen.setRows(rows);

    assertThat(screen.getRows()).hasSize(7);

    CardListScreen.CardListRow first = screen.getRows().get(0);
    assertThat(first.getCrdSel()).isEqualTo("S");
    assertThat(first.getAcctNo()).isEqualTo("00000000001");
    assertThat(first.getCrdNum()).isEqualTo("0000000000000001");
    assertThat(first.getCrdSts()).isEqualTo("Y");

    CardListScreen.CardListRow last = screen.getRows().get(6);
    assertThat(last.getCrdSel()).isEqualTo("S");
    assertThat(last.getAcctNo()).isEqualTo("00000000007");
    assertThat(last.getCrdNum()).isEqualTo("0000000000000007");
    assertThat(last.getCrdSts()).isEqualTo("Y");
  }

  /**
   * 3d. Field-width fidelity (BMS {@code LENGTH} parity): the {@code @Size(max = n)} constraints on
   * every screen scalar and on every logical row field must equal the legacy fixed widths declared
   * in {@code COCRDLI.CPY} / {@code COCRDLI.bms}. Verified via reflection so the assertion fails if
   * a width ever drifts from the legacy contract.
   */
  @Test
  void size_constraints_match_bms_field_widths() throws NoSuchFieldException {
    // Header + filter/paging + message scalars.
    assertThat(sizeMaxOf(CardListScreen.class, "trnName")).isEqualTo(4);
    assertThat(sizeMaxOf(CardListScreen.class, "title01")).isEqualTo(40);
    assertThat(sizeMaxOf(CardListScreen.class, "curDate")).isEqualTo(8);
    assertThat(sizeMaxOf(CardListScreen.class, "pgmName")).isEqualTo(8);
    assertThat(sizeMaxOf(CardListScreen.class, "title02")).isEqualTo(40);
    assertThat(sizeMaxOf(CardListScreen.class, "curTime")).isEqualTo(8);
    assertThat(sizeMaxOf(CardListScreen.class, "pageNo")).isEqualTo(3);
    assertThat(sizeMaxOf(CardListScreen.class, "acctSid")).isEqualTo(11);
    assertThat(sizeMaxOf(CardListScreen.class, "cardSid")).isEqualTo(16);
    assertThat(sizeMaxOf(CardListScreen.class, "infoMsg")).isEqualTo(45);
    assertThat(sizeMaxOf(CardListScreen.class, "errMsg")).isEqualTo(78);

    // The four logical repeating-row fields (CardListRow).
    assertThat(sizeMaxOf(CardListScreen.CardListRow.class, "crdSel")).isEqualTo(1);
    assertThat(sizeMaxOf(CardListScreen.CardListRow.class, "acctNo")).isEqualTo(11);
    assertThat(sizeMaxOf(CardListScreen.CardListRow.class, "crdNum")).isEqualTo(16);
    assertThat(sizeMaxOf(CardListScreen.CardListRow.class, "crdSts")).isEqualTo(1);
  }

  /**
   * 3d (round-trip form). Each field must accept and return a value at its full legacy width,
   * documenting the BMS {@code LENGTH} parity with concrete maximum-width payloads: the
   * 16-character card number and 11-character account number on a row, and the 78-character error
   * line, 45-character information line, 16-character card filter, 11-character account filter and
   * 3-character page number on the screen.
   */
  @Test
  void field_widths_accept_full_length_bms_values() {
    CardListScreen.CardListRow row = new CardListScreen.CardListRow();
    String fullCardNumber = "4".repeat(16);
    String fullAccountNumber = "9".repeat(11);
    row.setCrdNum(fullCardNumber);
    row.setAcctNo(fullAccountNumber);
    assertThat(row.getCrdNum()).isEqualTo(fullCardNumber).hasSize(16);
    assertThat(row.getAcctNo()).isEqualTo(fullAccountNumber).hasSize(11);

    CardListScreen screen = new CardListScreen();
    String fullErrMsg = "E".repeat(78);
    String fullInfoMsg = "I".repeat(45);
    String fullCardSid = "4".repeat(16);
    String fullAcctSid = "9".repeat(11);
    String fullPageNo = "999";
    screen.setErrMsg(fullErrMsg);
    screen.setInfoMsg(fullInfoMsg);
    screen.setCardSid(fullCardSid);
    screen.setAcctSid(fullAcctSid);
    screen.setPageNo(fullPageNo);
    assertThat(screen.getErrMsg()).isEqualTo(fullErrMsg).hasSize(78);
    assertThat(screen.getInfoMsg()).isEqualTo(fullInfoMsg).hasSize(45);
    assertThat(screen.getCardSid()).isEqualTo(fullCardSid).hasSize(16);
    assertThat(screen.getAcctSid()).isEqualTo(fullAcctSid).hasSize(11);
    assertThat(screen.getPageNo()).isEqualTo(fullPageNo).hasSize(3);
  }

  /**
   * 3e. A freshly constructed screen has all scalar fields unset (null) and a {@code rows} list
   * that is non-null and empty &mdash; the production DTO initializes {@code rows} to an empty,
   * mutable list so callers can populate rows without a null check.
   */
  @Test
  void fresh_instance_has_null_scalars_and_empty_rows() {
    CardListScreen screen = new CardListScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getPageNo()).isNull();
    assertThat(screen.getAcctSid()).isNull();
    assertThat(screen.getCardSid()).isNull();
    assertThat(screen.getInfoMsg()).isNull();
    assertThat(screen.getErrMsg()).isNull();

    assertThat(screen.getRows()).isNotNull().isEmpty();
  }

  /** Builds a {@link CardListScreen.CardListRow} from its four logical field values. */
  private static CardListScreen.CardListRow newRow(
      String crdSel, String acctNo, String crdNum, String crdSts) {
    CardListScreen.CardListRow row = new CardListScreen.CardListRow();
    row.setCrdSel(crdSel);
    row.setAcctNo(acctNo);
    row.setCrdNum(crdNum);
    row.setCrdSts(crdSts);
    return row;
  }

  /**
   * Reads the {@code max} attribute of the {@link Size} constraint declared on the named field of
   * the given type, asserting first that the constraint is present.
   */
  private static int sizeMaxOf(Class<?> declaringType, String fieldName)
      throws NoSuchFieldException {
    Field field = declaringType.getDeclaredField(fieldName);
    Size size = field.getAnnotation(Size.class);
    assertThat(size)
        .as(
            "field '%s' on %s must declare a @Size constraint",
            fieldName, declaringType.getSimpleName())
        .isNotNull();
    return size.max();
  }
}
