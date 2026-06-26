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

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportNameHeader}, the Java migration of the COBOL {@code 01
 * REPORT-NAME-HEADER} group item of copybook {@code legacy/app/cpy/CVTRA07Y.cpy} (lines 4-13) — the
 * title / date-range header of the Daily Transaction Report produced by batch program {@code
 * CBTRN03C}.
 *
 * <p>These tests pin the byte-faithful COBOL parity invariants (AAP &sect;0.6.1) that must hold
 * regardless of how the production POJO is implemented:
 *
 * <ul>
 *   <li>the fixed title constants carry their exact COBOL {@code VALUE} literals, {@code
 *       'DALYREPT'} and {@code 'Daily Transaction Report'};
 *   <li>the {@code REPT-DATE-HEADER} label ({@code "Date Range: "}, 12 chars with a trailing space)
 *       and the {@code FILLER} separator ({@code " to "}, 4 chars with leading and trailing spaces)
 *       preserve their embedded and trailing spaces byte-for-byte;
 *   <li>the fixed-field width constants equal the copybook {@code PIC X(n)} sizes so the report
 *       formatter can space-pad each column for golden-file parity; and
 *   <li>the only run-time-variable members, {@code REPT-START-DATE} / {@code REPT-END-DATE}, round
 *       trip through their getters and setters with the value stored verbatim.
 * </ul>
 *
 * <p>The test is intentionally framework-light: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new ReportNameHeader()} and static-constant access with AssertJ fluent
 * assertions, matching the production class's framework-free design.
 */
class ReportNameHeaderTest {

  // -----------------------------------------------------------------------------------------------
  // 3a. Fixed name constants carry the exact COBOL VALUE literals (CVTRA07Y.cpy L5-8).
  // -----------------------------------------------------------------------------------------------

  @Test
  void name_constants_match_copybook() {
    assertThat(ReportNameHeader.REPT_SHORT_NAME).isEqualTo("DALYREPT");
    assertThat(ReportNameHeader.REPT_LONG_NAME).isEqualTo("Daily Transaction Report");
  }

  // -----------------------------------------------------------------------------------------------
  // 3b. Date-range label and separator preserve embedded/trailing spaces byte-for-byte
  // (CVTRA07Y.cpy L9-12); these spaces are parity-critical for the printed report line.
  // -----------------------------------------------------------------------------------------------

  @Test
  void date_header_and_separator_match_copybook_including_spaces() {
    // REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: ' includes the single trailing space.
    assertThat(ReportNameHeader.REPT_DATE_HEADER).isEqualTo("Date Range: ").hasSize(12);
    // FILLER PIC X(04) VALUE ' to ' has one leading and one trailing space.
    assertThat(ReportNameHeader.DATE_SEPARATOR).isEqualTo(" to ").hasSize(4);
  }

  // -----------------------------------------------------------------------------------------------
  // 3c. The two run-time-variable date fields (REPT-START-DATE / REPT-END-DATE) round trip through
  // their getters/setters; the DTO stores the String as-is (no padding or truncation).
  // -----------------------------------------------------------------------------------------------

  @Test
  void date_range_fields_round_trip() {
    ReportNameHeader header = new ReportNameHeader();

    header.setReptStartDate("01/01/22");
    header.setReptEndDate("12/31/22");

    assertThat(header.getReptStartDate()).isEqualTo("01/01/22");
    assertThat(header.getReptEndDate()).isEqualTo("12/31/22");
  }

  // -----------------------------------------------------------------------------------------------
  // 3d. Fixed-field width constants equal the copybook PIC X(n) sizes (CVTRA07Y.cpy L5-13) so the
  // report formatter pads each field to its exact column width for golden-file parity.
  // -----------------------------------------------------------------------------------------------

  @Test
  void width_constants_match_copybook() {
    assertThat(ReportNameHeader.W_SHORT_NAME).isEqualTo(38);
    assertThat(ReportNameHeader.W_LONG_NAME).isEqualTo(41);
    assertThat(ReportNameHeader.W_DATE_HEADER).isEqualTo(12);
    assertThat(ReportNameHeader.W_DATE).isEqualTo(10);
  }
}
