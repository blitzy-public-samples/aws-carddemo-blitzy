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
package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link TransactionDetailReport}, the plain
 * data-transfer object migrated from the COBOL 01-level {@code TRANSACTION-DETAIL-REPORT} in {@code
 * legacy/app/cpy/CVTRA07Y.cpy} (lines 15-31). That record models ONE detail row of the Daily
 * Transaction Report and is populated by batch program {@code CBTRN03C}, paragraph {@code
 * 1120-WRITE-DETAIL}.
 *
 * <p>These tests pin the two parity invariants that the report layer depends on (Agent Action Plan
 * &sect;0.6.1 &mdash; decimal fidelity):
 *
 * <ul>
 *   <li>the monetary field {@code tranReportAmt} is a {@link java.math.BigDecimal} (never {@code
 *       float}/{@code double}) that stores the RAW signed amount at scale 2; and
 *   <li>the category-code field {@code tranReportCatCd} is an {@link Integer} (the PIC-faithful
 *       mapping of {@code PIC 9(04)}).
 * </ul>
 *
 * <p>The compile-time bindings below ({@code BigDecimal stored = detail.getTranReportAmt()} and
 * {@code Integer catCd = detail.getTranReportCatCd()}) are themselves the enforcement mechanism: if
 * the production types ever drift to a floating-point or {@code String} representation the test
 * stops compiling, failing the build. The six remaining {@code X(n)} fields are verified with
 * getter/setter round-trips using representative COBOL-width values.
 *
 * <p>This DTO holds only the raw value &mdash; the COBOL edited picture {@code -ZZZ,ZZZ,ZZZ.ZZ} is
 * a DISPLAY-ONLY mask applied at print time by {@code com.aws.carddemo.util.NumberFormatter}; that
 * formatting parity is verified in the {@code util} package, never here. Accordingly these tests
 * assert no edited/formatted strings.
 *
 * <p>The test is deliberately framework-light: the POJO is constructed directly with {@code new}
 * and every assertion uses AssertJ only. There is no Spring context, database, Testcontainers, or
 * Mockito.
 */
class TransactionDetailReportTest {

  // ===== 3a. The six X(n) String fields round-trip (CVTRA07Y.cpy L16,18,20,22,26,28) ============

  @Test
  void string_fields_round_trip() {
    TransactionDetailReport detail = new TransactionDetailReport();
    detail.setTranReportTransId("0000000000000001"); // X(16)
    detail.setTranReportAccountId("00000000001"); // X(11)
    detail.setTranReportTypeCd("DB"); // X(02)
    detail.setTranReportTypeDesc("Debit"); // X(15)
    detail.setTranReportCatDesc("Purchase"); // X(29)
    detail.setTranReportSource("POS"); // X(10)

    assertThat(detail.getTranReportTransId()).isEqualTo("0000000000000001");
    assertThat(detail.getTranReportAccountId()).isEqualTo("00000000001");
    assertThat(detail.getTranReportTypeCd()).isEqualTo("DB");
    assertThat(detail.getTranReportTypeDesc()).isEqualTo("Debit");
    assertThat(detail.getTranReportCatDesc()).isEqualTo("Purchase");
    assertThat(detail.getTranReportSource()).isEqualTo("POS");
  }

  // ===== 3b. tranReportCatCd is Integer and round-trips (CVTRA07Y.cpy L24 — PIC 9(04)) ==========

  @Test
  void cat_cd_is_integer_and_round_trips() {
    TransactionDetailReport detail = new TransactionDetailReport();
    detail.setTranReportCatCd(1234); // 9(04)

    // Binding to an Integer variable is the compile-time proof the getter returns Integer (and not
    // a primitive int or a String); if the production type drifted this would fail to compile.
    Integer catCd = detail.getTranReportCatCd();
    assertThat(catCd).isEqualTo(1234);
  }

  // ===== 3c. tranReportAmt is BigDecimal scale 2 and round-trips (CVTRA07Y.cpy L30 — §0.6.1) ====

  @Test
  void amount_is_bigdecimal_scale_2_and_round_trips() {
    TransactionDetailReport detail = new TransactionDetailReport();
    BigDecimal amount = new BigDecimal("12345.67");
    detail.setTranReportAmt(amount);

    // Binding to a BigDecimal variable is the compile-time proof: the amount is BigDecimal, never
    // float/double (the single most important decimal-fidelity parity rule, AAP §0.6.1).
    BigDecimal stored = detail.getTranReportAmt();
    assertThat(stored).isEqualByComparingTo("12345.67");
    assertThat(stored.scale()).isEqualTo(2); // plain POJO stores the scale-2 value as-is
  }

  @Test
  void amount_supports_negative_signed_values() {
    // The COBOL source field is signed (TRAN-AMT PIC S9(09)V99 / edited -ZZZ,ZZZ,ZZZ.ZZ), so the
    // DTO must faithfully carry negative amounts.
    TransactionDetailReport detail = new TransactionDetailReport();
    detail.setTranReportAmt(new BigDecimal("-9999999.99"));

    assertThat(detail.getTranReportAmt()).isEqualByComparingTo("-9999999.99");
    assertThat(detail.getTranReportAmt()).isNegative();
  }

  // ===== 3d. Fixed-width line-contract constants (CVTRA07Y.cpy L16-31 PIC widths + '-' FILLER) ==

  @Test
  void constants_match_cobol_widths_and_separator() {
    // The literal hyphen emitted by the two FILLER PIC X(01) VALUE '-' items (L21, L25).
    assertThat(TransactionDetailReport.SEP_DASH).isEqualTo("-");

    // The COBOL PIC widths driving byte-faithful, fixed-width report rendering (golden-file
    // parity).
    assertThat(TransactionDetailReport.W_TRANS_ID).isEqualTo(16);
    assertThat(TransactionDetailReport.W_ACCOUNT_ID).isEqualTo(11);
    assertThat(TransactionDetailReport.W_TYPE_CD).isEqualTo(2);
    assertThat(TransactionDetailReport.W_TYPE_DESC).isEqualTo(15);
    assertThat(TransactionDetailReport.W_CAT_CD).isEqualTo(4);
    assertThat(TransactionDetailReport.W_CAT_DESC).isEqualTo(29);
    assertThat(TransactionDetailReport.W_SOURCE).isEqualTo(10);
    assertThat(TransactionDetailReport.W_AMT_EDITED).isEqualTo(15);
  }
}
