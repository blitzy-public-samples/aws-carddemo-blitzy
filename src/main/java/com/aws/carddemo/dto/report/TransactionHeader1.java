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

/**
 * Column-title header row for the Daily Transaction Report, migrated verbatim from the legacy AWS
 * CardDemo COBOL copybook {@code CVTRA07Y.cpy}, 01-level {@code TRANSACTION-HEADER-1} (lines
 * 33-46).
 *
 * <p>In the legacy batch program {@code CBTRN03C} this 01-level is printed once per report page as
 * the heading row that sits directly above the transaction detail rows produced from {@code
 * TRANSACTION-DETAIL-REPORT}. Every elementary item is a {@code FILLER} carrying a fixed-width
 * {@code VALUE} literal — there is no variable data — so the row is, in effect, a set of constant
 * column titles.
 *
 * <p>COBOL stores each {@code VALUE} literal left-justified and space-padded to the declared {@code
 * PIC X(n)} width. To preserve byte-faithful report output (golden-file parity, Agent Action Plan
 * §0.6.1), this class exposes both the raw, untrimmed title literals and the fully assembled,
 * fixed-width {@link #HEADER_LINE}. The line is reconstructed by left-justifying each title to its
 * COBOL field width via {@link String#format(String, Object...)} ({@code "%-Ns"}), which reproduces
 * the trailing-space padding exactly and avoids hand-counting the many trailing spaces (notably the
 * 22 spaces that follow {@code Tran Category}).
 *
 * <p>The width constants ({@code W_*}) are the single source of truth for the column geometry and
 * MUST stay aligned with the corresponding detail-row column widths so that each title sits above
 * its data column. The assembled width is {@code 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114} characters.
 *
 * <p>Per Agent Action Plan §0.4.1, exactly one Java type is produced for this copybook 01-level.
 * This is a non-instantiable constant holder; every member is {@code public static final}.
 *
 * @see <a href="file:legacy/app/cpy/CVTRA07Y.cpy">legacy/app/cpy/CVTRA07Y.cpy (lines 33-46)</a>
 */
public final class TransactionHeader1 {

  /**
   * Width, in characters, of the {@code Transaction ID} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(17)}.
   */
  public static final int W_TRANSACTION_ID = 17;

  /**
   * Width, in characters, of the {@code Account ID} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(12)}.
   */
  public static final int W_ACCOUNT_ID = 12;

  /**
   * Width, in characters, of the {@code Transaction Type} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(19)}.
   */
  public static final int W_TRANSACTION_TYPE = 19;

  /**
   * Width, in characters, of the {@code Tran Category} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(35)}.
   */
  public static final int W_TRAN_CATEGORY = 35;

  /**
   * Width, in characters, of the {@code Tran Source} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(14)}.
   */
  public static final int W_TRAN_SOURCE = 14;

  /**
   * Width, in characters, of the single-space gap between {@code Tran Source} and {@code Amount}.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X VALUE SPACES} (an implicit {@code
   * X(1)}).
   */
  public static final int W_GAP = 1;

  /**
   * Width, in characters, of the {@code Amount} column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(16)}.
   */
  public static final int W_AMOUNT = 16;

  /**
   * Raw, untrimmed title text for the transaction-id column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(17) VALUE 'Transaction ID'}. The literal
   * is 14 characters; left-justifying it into the {@code X(17)} field adds 3 trailing spaces.
   */
  public static final String TITLE_TRANSACTION_ID = "Transaction ID";

  /**
   * Raw, untrimmed title text for the account-id column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(12) VALUE 'Account ID'}. The literal is
   * 10 characters; left-justifying it into the {@code X(12)} field adds 2 trailing spaces.
   */
  public static final String TITLE_ACCOUNT_ID = "Account ID";

  /**
   * Raw, untrimmed title text for the transaction-type column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(19) VALUE 'Transaction Type'}. The
   * literal is 16 characters; left-justifying it into the {@code X(19)} field adds 3 trailing
   * spaces.
   */
  public static final String TITLE_TRANSACTION_TYPE = "Transaction Type";

  /**
   * Raw, untrimmed title text for the transaction-category column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(35) VALUE 'Tran Category'}. The literal
   * is 13 characters; left-justifying it into the {@code X(35)} field adds 22 trailing spaces.
   */
  public static final String TITLE_TRAN_CATEGORY = "Tran Category";

  /**
   * Raw, untrimmed title text for the transaction-source column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(14) VALUE 'Tran Source'}. The literal is
   * 11 characters; left-justifying it into the {@code X(14)} field adds 3 trailing spaces.
   */
  public static final String TITLE_TRAN_SOURCE = "Tran Source";

  /**
   * Raw, untrimmed title text for the amount column.
   *
   * <p>Source: {@code CVTRA07Y.cpy} {@code 05 FILLER PIC X(16)} whose {@code VALUE} is eight spaces
   * immediately followed by {@code Amount}. The literal is 14 characters — 8 leading spaces
   * followed by {@code "Amount"} — which right-aligns the word within the printed column once the
   * field's 2 trailing spaces are added. The 8 leading spaces are part of the COBOL {@code VALUE}
   * literal itself and are reproduced byte-for-byte.
   */
  public static final String TITLE_AMOUNT = "        Amount";

  /**
   * The fully assembled, byte-faithful column-title header line, exactly as the COBOL {@code
   * TRANSACTION-HEADER-1} 01-level renders it.
   *
   * <p>Each title is left-justified into its COBOL field width ({@link #W_TRANSACTION_ID}, {@link
   * #W_ACCOUNT_ID}, {@link #W_TRANSACTION_TYPE}, {@link #W_TRAN_CATEGORY}, {@link #W_TRAN_SOURCE},
   * {@link #W_AMOUNT}) and the single {@link #W_GAP}-wide space separates {@code Tran Source} from
   * {@code Amount}. Building the line from the {@code W_*} constants guarantees that the assembled
   * row can never drift from the published column geometry. The result is a fixed-width line of
   * {@code 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114} characters.
   */
  public static final String HEADER_LINE =
      String.format("%-" + W_TRANSACTION_ID + "s", TITLE_TRANSACTION_ID)
          + String.format("%-" + W_ACCOUNT_ID + "s", TITLE_ACCOUNT_ID)
          + String.format("%-" + W_TRANSACTION_TYPE + "s", TITLE_TRANSACTION_TYPE)
          + String.format("%-" + W_TRAN_CATEGORY + "s", TITLE_TRAN_CATEGORY)
          + String.format("%-" + W_TRAN_SOURCE + "s", TITLE_TRAN_SOURCE)
          + " "
          + String.format("%-" + W_AMOUNT + "s", TITLE_AMOUNT);

  /** Constant holder — not meant to be instantiated. */
  private TransactionHeader1() {
    // constant holder
    throw new AssertionError("TransactionHeader1 is a non-instantiable constant holder");
  }
}
