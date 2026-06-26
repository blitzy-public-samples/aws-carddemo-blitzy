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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionHeader1} — the column-title header row of the Daily
 * Transaction Report, migrated verbatim from the legacy COBOL copybook {@code CVTRA07Y.cpy},
 * 01-level {@code TRANSACTION-HEADER-1} (lines 33-46).
 *
 * <p>Every elementary item in the source 01-level is a {@code FILLER} carrying a fixed-width {@code
 * VALUE} literal, so the entire row is a set of constant column titles with no variable data. The
 * parity contract this suite enforces is therefore purely structural and byte-faithful (Agent
 * Action Plan §0.6.1): the per-column widths must equal the COBOL {@code PIC X(n)} sizes, the title
 * literals must match the copybook character-for-character (including leading and embedded spaces),
 * and the fully assembled {@link TransactionHeader1#HEADER_LINE} must be the exact 114-character
 * fixed-width line the legacy program prints above the detail rows.
 *
 * <p>The headline regression trap is {@link TransactionHeader1#TITLE_AMOUNT}: the COBOL {@code
 * VALUE} literal is eight spaces immediately followed by {@code Amount} (14 characters total).
 * Miscounting those leading spaces silently shifts the {@code Amount} column out of alignment with
 * the detail-row amount field, so the expected value is built with {@code " ".repeat(8)} rather
 * than a hand-typed run of spaces.
 *
 * <p>The suite is deliberately Spring-free, database-free and mock-free: it exercises only the
 * static constants of a non-instantiable constant holder using AssertJ fluent assertions. The
 * column offsets are pinned segment-by-segment via {@code String.format("%-Ns", ...)} so that the
 * assembled header line can never drift from the published column geometry.
 *
 * @see TransactionHeader1
 * @see <a href="file:legacy/app/cpy/CVTRA07Y.cpy">legacy/app/cpy/CVTRA07Y.cpy (lines 33-46)</a>
 */
class TransactionHeader1Test {

  /**
   * Verifies that every column-width constant equals the width of its COBOL {@code PIC X(n)} clause
   * in {@code TRANSACTION-HEADER-1}. These widths are the single source of truth for the report
   * geometry; any drift would misalign a title over its detail-row data column.
   */
  @Test
  void width_constants_match_cobol_pics() {
    assertThat(TransactionHeader1.W_TRANSACTION_ID).isEqualTo(17);
    assertThat(TransactionHeader1.W_ACCOUNT_ID).isEqualTo(12);
    assertThat(TransactionHeader1.W_TRANSACTION_TYPE).isEqualTo(19);
    assertThat(TransactionHeader1.W_TRAN_CATEGORY).isEqualTo(35);
    assertThat(TransactionHeader1.W_TRAN_SOURCE).isEqualTo(14);
    assertThat(TransactionHeader1.W_GAP).isEqualTo(1);
    assertThat(TransactionHeader1.W_AMOUNT).isEqualTo(16);
  }

  /**
   * Verifies that the raw, untrimmed title literals match the copybook {@code VALUE} clauses
   * exactly (the {@code TITLE_AMOUNT} literal, which carries leading spaces, is asserted
   * separately).
   */
  @Test
  void title_literals_match_copybook() {
    assertThat(TransactionHeader1.TITLE_TRANSACTION_ID).isEqualTo("Transaction ID");
    assertThat(TransactionHeader1.TITLE_ACCOUNT_ID).isEqualTo("Account ID");
    assertThat(TransactionHeader1.TITLE_TRANSACTION_TYPE).isEqualTo("Transaction Type");
    assertThat(TransactionHeader1.TITLE_TRAN_CATEGORY).isEqualTo("Tran Category");
    assertThat(TransactionHeader1.TITLE_TRAN_SOURCE).isEqualTo("Tran Source");
  }

  /**
   * Pins the {@code TITLE_AMOUNT} regression trap: the COBOL {@code VALUE} literal is eight spaces
   * followed by {@code Amount} for a total length of 14. The expected value is built from {@code "
   * ".repeat(8)} so the leading-space count can never be miscounted, and the assertions confirm
   * both the exact value and that the string begins with precisely eight spaces.
   */
  @Test
  void title_amount_has_eight_leading_spaces_and_length_14() {
    assertThat(TransactionHeader1.TITLE_AMOUNT)
        .hasSize(14)
        .isEqualTo(" ".repeat(8) + "Amount")
        .startsWith(" ".repeat(8));
  }

  /**
   * Confirms the assembled header line is exactly 114 characters wide ({@code 17 + 12 + 19 + 35 +
   * 14 + 1 + 16}), matching the total record width of the legacy {@code TRANSACTION-HEADER-1}
   * 01-level.
   */
  @Test
  void header_line_is_114_characters_wide() {
    assertThat(TransactionHeader1.HEADER_LINE).hasSize(114);
  }

  /**
   * Verifies that the assembled header line places each title left-justified within its COBOL field
   * width, pinning every column by absolute offset. Reproducing the per-segment padding with {@code
   * String.format("%-Ns", ...)} both documents the layout and proves byte-faithful alignment with
   * the detail rows. The amount segment {@code [98, 114)} resolves to the 14-character {@code
   * TITLE_AMOUNT} — eight leading spaces, the word {@code Amount} — left-justified into its {@code
   * X(16)} field, which appends two trailing pad spaces for a 16-character column.
   */
  @Test
  void header_line_columns_are_left_justified_to_their_widths() {
    String hl = TransactionHeader1.HEADER_LINE;
    assertThat(hl).startsWith("Transaction ID");
    assertThat(hl.substring(0, 17)).isEqualTo(String.format("%-17s", "Transaction ID"));
    assertThat(hl.substring(17, 29)).isEqualTo(String.format("%-12s", "Account ID"));
    assertThat(hl.substring(29, 48)).isEqualTo(String.format("%-19s", "Transaction Type"));
    assertThat(hl.substring(48, 83)).isEqualTo(String.format("%-35s", "Tran Category"));
    assertThat(hl.substring(83, 97)).isEqualTo(String.format("%-14s", "Tran Source"));
    assertThat(hl.substring(97, 98)).isEqualTo(" ");
    assertThat(hl.substring(98, 114)).isEqualTo(String.format("%-16s", " ".repeat(8) + "Amount"));
  }

  /**
   * Ties the published column-width constants to the assembled line: the sum of all {@code W_*}
   * widths must equal 114 and must equal the actual {@link TransactionHeader1#HEADER_LINE} length.
   * This cross-check catches drift in either direction — a changed width that is not reflected in
   * the assembled line, or an assembled line that no longer honours the declared geometry.
   */
  @Test
  void header_line_width_equals_sum_of_column_widths() {
    int sumOfWidths =
        TransactionHeader1.W_TRANSACTION_ID
            + TransactionHeader1.W_ACCOUNT_ID
            + TransactionHeader1.W_TRANSACTION_TYPE
            + TransactionHeader1.W_TRAN_CATEGORY
            + TransactionHeader1.W_TRAN_SOURCE
            + TransactionHeader1.W_GAP
            + TransactionHeader1.W_AMOUNT;
    assertThat(sumOfWidths).isEqualTo(114);
    assertThat(TransactionHeader1.HEADER_LINE).hasSize(sumOfWidths);
  }

  /**
   * Confirms {@link TransactionHeader1} is a non-instantiable constant holder: its sole constructor
   * is {@code private} and guards against reflective instantiation by throwing an {@link
   * AssertionError}. Reflectively invoking it therefore surfaces an {@link
   * InvocationTargetException} whose cause is that {@code AssertionError}. Exercising the
   * constructor here also gives JaCoCo coverage of the guard clause.
   */
  @Test
  void class_is_a_non_instantiable_constant_holder() throws Exception {
    var ctor = TransactionHeader1.class.getDeclaredConstructor();
    assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
    ctor.setAccessible(true);
    assertThatThrownBy(ctor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(AssertionError.class);
  }
}
