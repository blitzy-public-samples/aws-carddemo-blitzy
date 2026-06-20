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

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Screen view contract for the CardDemo <strong>Report submit</strong> screen.
 *
 * <p>This plain, framework-light POJO migrates the legacy CICS/BMS mapset {@code CORPT00} (online
 * CICS transaction {@code CR00}) into the modernized Spring Boot application. It is a screen-level
 * data-transfer object only: it carries the raw field values exchanged between the presentation
 * layer and {@code com.aws.carddemo.service.online.ReportService}, which performs the actual
 * report-type resolution and triggers the {@code TransactionReport} Spring Batch job. It is
 * deliberately <em>not</em> a JPA entity and holds no persistence, domain, or monetary state.
 *
 * <p>The screen lets the user choose a report type and (optionally) a date range, then confirm
 * submission:
 *
 * <ul>
 *   <li>{@code monthly} / {@code yearly} / {@code custom} act as mutually-exclusive, radio-style
 *       selectors (rendered as BMS {@code UNPROT} inputs).
 *   <li>Choosing {@code custom} enables the split start-date ({@code sdtMm} / {@code sdtDd} /
 *       {@code sdtYyyy}) and end-date ({@code edtMm} / {@code edtDd} / {@code edtYyyy}) component
 *       inputs.
 *   <li>{@code confirm} captures the {@code Y}/{@code N} submission confirmation.
 * </ul>
 *
 * <p>Every field preserves the exact fixed width declared by the originating COBOL {@code PIC X(n)}
 * clause so the field contract remains faithful to the legacy 24x80 3270 screen.
 *
 * <p><strong>Origin:</strong> {@code legacy/app/cpy-bms/CORPT00.CPY} (authoritative field
 * names/lengths) and {@code legacy/app/bms/CORPT00.bms} (rendering reference).
 *
 * <p><strong>Authority:</strong> AAP section 0.4.1 ("dto/screen/*.java (17) sourced from
 * app/cpy-bms/*.CPY + app/bms/*.bms, preserving field lengths") and section 0.3.4 (User Interface
 * Design).
 */
@Getter
@Setter
@NoArgsConstructor
public class ReportScreen {

  /** Transaction identifier displayed in the screen header. COBOL CORPT00: TRNNAME PIC X(4). */
  @Size(max = 4)
  private String trnName;

  /** Primary screen title line. COBOL CORPT00: TITLE01 PIC X(40). */
  @Size(max = 40)
  private String title01;

  /** Current-date header label (formatted {@code mm/dd/yy}). COBOL CORPT00: CURDATE PIC X(8). */
  @Size(max = 8)
  private String curDate;

  /** Owning program name displayed in the header. COBOL CORPT00: PGMNAME PIC X(8). */
  @Size(max = 8)
  private String pgmName;

  /** Secondary screen title line. COBOL CORPT00: TITLE02 PIC X(40). */
  @Size(max = 40)
  private String title02;

  /** Current-time header label (formatted {@code hh:mm:ss}). COBOL CORPT00: CURTIME PIC X(8). */
  @Size(max = 8)
  private String curTime;

  /**
   * Monthly (current month) report selector; mutually exclusive with {@link #yearly} and {@link
   * #custom}. Typically {@code "Y"} when chosen or a space otherwise (BMS UNPROT input). COBOL
   * CORPT00: MONTHLY PIC X(1).
   */
  @Size(max = 1)
  private String monthly;

  /**
   * Yearly (current year) report selector; mutually exclusive with {@link #monthly} and {@link
   * #custom}. Typically {@code "Y"} when chosen or a space otherwise (BMS UNPROT input). COBOL
   * CORPT00: YEARLY PIC X(1).
   */
  @Size(max = 1)
  private String yearly;

  /**
   * Custom date-range report selector; mutually exclusive with {@link #monthly} and {@link
   * #yearly}. When chosen, the start/end date component fields below become applicable (BMS UNPROT
   * input). COBOL CORPT00: CUSTOM PIC X(1).
   */
  @Size(max = 1)
  private String custom;

  /** Custom-range start-date month component (MM). COBOL CORPT00: SDTMM PIC X(2). */
  @Size(max = 2)
  private String sdtMm;

  /** Custom-range start-date day component (DD). COBOL CORPT00: SDTDD PIC X(2). */
  @Size(max = 2)
  private String sdtDd;

  /** Custom-range start-date year component (YYYY). COBOL CORPT00: SDTYYYY PIC X(4). */
  @Size(max = 4)
  private String sdtYyyy;

  /** Custom-range end-date month component (MM). COBOL CORPT00: EDTMM PIC X(2). */
  @Size(max = 2)
  private String edtMm;

  /** Custom-range end-date day component (DD). COBOL CORPT00: EDTDD PIC X(2). */
  @Size(max = 2)
  private String edtDd;

  /** Custom-range end-date year component (YYYY). COBOL CORPT00: EDTYYYY PIC X(4). */
  @Size(max = 4)
  private String edtYyyy;

  /** Submission confirmation flag ({@code Y}/{@code N}). COBOL CORPT00: CONFIRM PIC X(1). */
  @Size(max = 1)
  private String confirm;

  /** Error / informational message line shown to the user. COBOL CORPT00: ERRMSG PIC X(78). */
  @Size(max = 78)
  private String errMsg;
}
