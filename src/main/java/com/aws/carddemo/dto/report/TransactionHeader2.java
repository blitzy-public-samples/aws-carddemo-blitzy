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
 * Migrated COBOL {@code 01 TRANSACTION-HEADER-2} from copybook {@code app/cpy/CVTRA07Y.cpy} (line
 * 48), declared as {@code PIC X(133) VALUE ALL '-'}.
 *
 * <p>This 01-level is a single fixed-width field — a 133-character separator line composed entirely
 * of hyphen ({@code '-'}) characters. The legacy Daily Transaction Report program ({@code
 * CBTRN03C}) prints this rule between the column-title header ({@code TRANSACTION-HEADER-1}) and
 * the transaction detail rows.
 *
 * <p>Because the COBOL field carries no variable data (it is a pure {@code VALUE ALL '-'} literal),
 * this type is modeled as a non-instantiable constant holder rather than a data-carrying DTO. The
 * fixed width of exactly 133 characters is part of the report line contract and must be preserved
 * byte-for-byte so golden-file parity tests match the mainframe output exactly (Agent Action Plan
 * §0.4.1 — one Java type per copybook 01-level; §0.6.1 — byte-faithful report output).
 *
 * <p>Rendering/printing of report lines is performed by the report service and the {@code
 * com.aws.carddemo.util} formatters; this type only supplies the constant separator line.
 */
public final class TransactionHeader2 {

  /**
   * Fixed character width of the separator line, matching the COBOL declaration {@code PIC X(133)}
   * in {@code CVTRA07Y.cpy} (line 48).
   */
  public static final int LENGTH = 133;

  /**
   * The report separator line: exactly 133 hyphen characters. This is the Java equivalent of the
   * COBOL {@code VALUE ALL '-'} clause applied to a {@code PIC X(133)} field, so the rendered line
   * is byte-for-byte identical to the mainframe output. Generated with {@link String#repeat(int)}
   * over {@link #LENGTH} to guarantee the exact length rather than hand-typing 133 dashes.
   */
  public static final String SEPARATOR_LINE = "-".repeat(LENGTH);

  private TransactionHeader2() {
    // constant holder
  }
}
