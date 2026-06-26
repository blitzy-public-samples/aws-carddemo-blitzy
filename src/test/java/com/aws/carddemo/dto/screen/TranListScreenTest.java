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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TranListScreen}, the screen view contract migrated from the legacy
 * CICS/BMS transaction-list map {@code COTRN00} (CICS transaction {@code CT00}).
 *
 * <p>These tests verify behavioral parity of the screen DTO with the legacy field contract derived
 * from {@code legacy/app/cpy-bms/COTRN00.CPY} and {@code legacy/app/bms/COTRN00.bms}:
 *
 * <ul>
 *   <li>scalar header/paging/message fields round-trip through getters and setters;
 *   <li>the nested {@link TranListScreen.TranListRow} round-trips through both the all-args and the
 *       no-args+setter construction styles;
 *   <li>the monetary row amount {@code tAmt} is a {@link BigDecimal} (Agent Action Plan §0.6.1 — no
 *       floating-point type) and round-trips exactly, including negative values;
 *   <li>the rows list honors the fixed ten-row 3270 page geometry; and
 *   <li>each field preserves its BMS {@code LENGTH} (notably the <em>truncated</em> {@code X(26)}
 *       list-view description, not the {@code X(60)} detail-view description).
 * </ul>
 *
 * <p>The class and its test methods are intentionally package-private and the suite is framework
 * light: no Spring context, database, Testcontainers, or mocking is used.
 */
class TranListScreenTest {

  /** 3a — every header, paging, and message scalar round-trips through its getter/setter pair. */
  @Test
  void header_and_paging_fields_round_trip() {
    TranListScreen screen = new TranListScreen();

    screen.setTrnName("CT00");
    screen.setTitle01("AWS CardDemo - Transaction List");
    screen.setCurDate("01/15/26");
    screen.setPgmName("COTRN00C");
    screen.setTitle02("Credit Card Management System");
    screen.setCurTime("14:30:00");
    screen.setPageNum("00000001");
    screen.setTrnIdIn("0000000000000099");
    screen.setErrMsg("No transactions found.");

    assertThat(screen.getTrnName()).isEqualTo("CT00");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo - Transaction List");
    assertThat(screen.getCurDate()).isEqualTo("01/15/26");
    assertThat(screen.getPgmName()).isEqualTo("COTRN00C");
    assertThat(screen.getTitle02()).isEqualTo("Credit Card Management System");
    assertThat(screen.getCurTime()).isEqualTo("14:30:00");
    assertThat(screen.getPageNum()).isEqualTo("00000001");
    assertThat(screen.getTrnIdIn()).isEqualTo("0000000000000099");
    assertThat(screen.getErrMsg()).isEqualTo("No transactions found.");
  }

  /**
   * 3b — a row built with the all-args constructor exposes its values, with {@code tAmt} as a
   * scale-2 BigDecimal.
   */
  @Test
  void row_round_trips_via_all_args_constructor() {
    TranListScreen.TranListRow row =
        new TranListScreen.TranListRow(
            "S",
            "0000000000000001",
            "01/02/26",
            "Purchase at Store No. 0042",
            new BigDecimal("1234567.89"));

    assertThat(row.getSel()).isEqualTo("S");
    assertThat(row.getTrnId()).isEqualTo("0000000000000001");
    assertThat(row.getTDate()).isEqualTo("01/02/26");
    assertThat(row.getTDesc()).isEqualTo("Purchase at Store No. 0042");
    assertThat(row.getTAmt()).isInstanceOf(BigDecimal.class);
    assertThat(row.getTAmt()).isEqualByComparingTo(new BigDecimal("1234567.89"));
  }

  /**
   * 3b — a row built with the no-args constructor + setters round-trips, including a negative
   * amount.
   */
  @Test
  void row_round_trips_via_setters_and_handles_negative_amount() {
    TranListScreen.TranListRow row = new TranListScreen.TranListRow();

    row.setSel("S");
    row.setTrnId("0000000000000002");
    row.setTDate("02/03/26");
    row.setTDesc("Refund issued");
    row.setTAmt(new BigDecimal("-99.99"));

    assertThat(row.getSel()).isEqualTo("S");
    assertThat(row.getTrnId()).isEqualTo("0000000000000002");
    assertThat(row.getTDate()).isEqualTo("02/03/26");
    assertThat(row.getTDesc()).isEqualTo("Refund issued");
    assertThat(row.getTAmt()).isInstanceOf(BigDecimal.class);
    assertThat(row.getTAmt()).isEqualByComparingTo(new BigDecimal("-99.99"));
  }

  /** 3c — the rows list honors the fixed ten-row 3270 page geometry and elements round-trip. */
  @Test
  void rows_list_holds_exactly_ten_rows() {
    TranListScreen screen = new TranListScreen();
    List<TranListScreen.TranListRow> rows = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      String id = String.format("%016d", i);
      rows.add(new TranListScreen.TranListRow("", id, "01/01/26", "Row " + i, new BigDecimal(i)));
    }
    screen.setRows(rows);

    assertThat(screen.getRows()).hasSize(10);
    assertThat(screen.getRows().get(0).getTrnId()).isEqualTo("0000000000000001");
    assertThat(screen.getRows().get(0).getTAmt()).isEqualByComparingTo(new BigDecimal("1"));
    assertThat(screen.getRows().get(9).getTrnId()).isEqualTo("0000000000000010");
    assertThat(screen.getRows().get(9).getTAmt()).isEqualByComparingTo(new BigDecimal("10"));
  }

  /**
   * 3d — fields preserve their BMS {@code LENGTH}; note the truncated {@code X(26)} description.
   */
  @Test
  void field_widths_match_bms_lengths() {
    TranListScreen.TranListRow row = new TranListScreen.TranListRow();
    row.setTrnId("0000000000000001"); // TRNIDxx X(16)
    row.setTDate("01/02/26"); // TDATExx X(8)
    row.setTDesc("A".repeat(26)); // TDESCxx X(26) truncated list width, not the X(60) detail width

    TranListScreen screen = new TranListScreen();
    screen.setErrMsg("E".repeat(78)); // ERRMSG X(78)

    assertThat(row.getTrnId()).hasSize(16);
    assertThat(row.getTDate()).hasSize(8);
    assertThat(row.getTDesc()).hasSize(26);
    assertThat(screen.getErrMsg()).hasSize(78);
  }

  /** 3d — the {@code @Size} max constraints match the BMS field lengths of {@code COTRN00}. */
  @Test
  void size_annotations_match_bms_lengths() throws NoSuchFieldException {
    assertThat(sizeMax(TranListScreen.class, "trnName")).isEqualTo(4);
    assertThat(sizeMax(TranListScreen.class, "title01")).isEqualTo(40);
    assertThat(sizeMax(TranListScreen.class, "curDate")).isEqualTo(8);
    assertThat(sizeMax(TranListScreen.class, "pgmName")).isEqualTo(8);
    assertThat(sizeMax(TranListScreen.class, "title02")).isEqualTo(40);
    assertThat(sizeMax(TranListScreen.class, "curTime")).isEqualTo(8);
    assertThat(sizeMax(TranListScreen.class, "pageNum")).isEqualTo(8);
    assertThat(sizeMax(TranListScreen.class, "trnIdIn")).isEqualTo(16);
    assertThat(sizeMax(TranListScreen.class, "errMsg")).isEqualTo(78);

    assertThat(sizeMax(TranListScreen.TranListRow.class, "sel")).isEqualTo(1);
    assertThat(sizeMax(TranListScreen.TranListRow.class, "trnId")).isEqualTo(16);
    assertThat(sizeMax(TranListScreen.TranListRow.class, "tDate")).isEqualTo(8);
    assertThat(sizeMax(TranListScreen.TranListRow.class, "tDesc")).isEqualTo(26);
  }

  /**
   * 3e — a freshly constructed screen has null scalars and a non-null, empty (mutable) rows list.
   */
  @Test
  void fresh_instance_has_null_scalars_and_empty_rows() {
    TranListScreen screen = new TranListScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getPageNum()).isNull();
    assertThat(screen.getTrnIdIn()).isNull();
    assertThat(screen.getErrMsg()).isNull();
    assertThat(screen.getRows()).isNotNull().isEmpty();
  }

  /** Reads the {@code max} attribute of the {@link Size} constraint on the named declared field. */
  private static int sizeMax(Class<?> type, String fieldName) throws NoSuchFieldException {
    return type.getDeclaredField(fieldName).getAnnotation(Size.class).max();
  }
}
