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

package com.carddemo.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Report-type selector for report generation submitted via {@code POST /api/reports}.
 *
 * <p>This enum consolidates three mutually-exclusive BMS X(1) flags from
 * {@code app/cpy-bms/CORPT00.CPY} into a single type-safe selector. The original
 * COBOL screen used three separate single-character fields that the user populated
 * to indicate the report period:
 *
 * <pre>
 *   COBOL field (CORPT00.CPY)               Java enum value
 *   ─────────────────────────               ───────────────
 *   MONTHLYI PIC X(1)  (line 60)     ↦      {@link #MONTHLY}
 *   YEARLYI  PIC X(1)  (line 66)     ↦      {@link #YEARLY}
 *   CUSTOMI  PIC X(1)  (line 72)     ↦      {@link #CUSTOM}
 * </pre>
 *
 * <p>The COBOL business logic in {@code CORPT00C.cbl} (lines 213-303) uses
 * {@code EVALUATE TRUE} to dispatch on which of the three flags is non-blank:
 *
 * <pre>{@code
 *   EVALUATE TRUE
 *       WHEN MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
 *           MOVE 'Monthly' TO WS-REPORT-NAME ...
 *       WHEN YEARLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
 *           MOVE 'Yearly' TO WS-REPORT-NAME ...
 *       WHEN CUSTOMI OF CORPT0AI NOT = SPACES AND LOW-VALUES
 *           [validates user-supplied dates] ...
 *   END-EVALUATE
 * }</pre>
 *
 * <p><b>Modernization improvement (AAP §0.4.1.7):</b> The Java enum eliminates the
 * COBOL design ambiguity (what if a user enters values in multiple flags?) by allowing
 * exactly one of three named values. Jackson deserialization will reject any value not
 * in {@code {MONTHLY, YEARLY, CUSTOM}} with an HTTP 400 Bad Request, ensuring clean input.
 *
 * <p>Period-derivation semantics (preserved from COBOL):
 * <ul>
 *   <li>{@link #MONTHLY} — {@code startDate} aligns to month start, {@code endDate} to
 *       month end. The COBOL paragraph at lines 213-238 of {@code CORPT00C.cbl} computes
 *       start = current-year/current-month/01 and end = next-month/01 − 1 day.</li>
 *   <li>{@link #YEARLY} — {@code startDate} = Jan 1, {@code endDate} = Dec 31 of the
 *       current year (COBOL lines 239-255 of {@code CORPT00C.cbl}).</li>
 *   <li>{@link #CUSTOM} — Caller supplies arbitrary {@code startDate} and {@code endDate};
 *       service-layer validation enforces {@code endDate >= startDate} and valid date format
 *       (COBOL lines 256-303 of {@code CORPT00C.cbl}, which uses {@code CSUTLDTC} utility).</li>
 * </ul>
 *
 * <p>Jackson serializes/deserializes enum values by name (default behavior). Clients send the
 * literal string {@code "MONTHLY"}, {@code "YEARLY"}, or {@code "CUSTOM"} in the JSON request
 * body; other values produce a deserialization error (HTTP 400 Bad Request).
 *
 * <p>This enum lives in the same package as {@link ReportRequest} and therefore does not
 * require an explicit import from {@code ReportRequest}.
 *
 * @see ReportRequest
 * @see OutputFormat
 */
@Schema(
    description = "Report-type selector consolidating three mutually-exclusive COBOL BMS X(1) flags " +
                  "(MONTHLYI, YEARLYI, CUSTOMI) into a single type-safe enum. " +
                  "MONTHLY/YEARLY auto-derive date ranges; CUSTOM requires explicit startDate/endDate.",
    allowableValues = {"MONTHLY", "YEARLY", "CUSTOM"},
    example = "MONTHLY",
    requiredMode = Schema.RequiredMode.REQUIRED
)
public enum ReportType {

    /**
     * Monthly report — covers the current calendar month.
     *
     * <p>Per COBOL {@code CORPT00C.cbl} lines 213-238, when the user populates
     * {@code MONTHLYI}, the program computes:
     * <ul>
     *   <li>{@code startDate} = current-year / current-month / 01</li>
     *   <li>{@code endDate} = next-month / 01 minus one day</li>
     * </ul>
     *
     * <p>The Java {@code ReportService} replicates this derivation when
     * {@link ReportRequest#getReportType()} returns {@code MONTHLY}; the client may
     * still pass {@code startDate}/{@code endDate} for validation, but the service
     * may normalize them to the month boundaries.
     */
    MONTHLY,

    /**
     * Yearly report — covers the current calendar year.
     *
     * <p>Per COBOL {@code CORPT00C.cbl} lines 239-255, when the user populates
     * {@code YEARLYI}, the program computes:
     * <ul>
     *   <li>{@code startDate} = current-year / 01 / 01</li>
     *   <li>{@code endDate}   = current-year / 12 / 31</li>
     * </ul>
     *
     * <p>The Java {@code ReportService} replicates this derivation when
     * {@link ReportRequest#getReportType()} returns {@code YEARLY}.
     */
    YEARLY,

    /**
     * Custom date-range report — covers an arbitrary {@code startDate} to {@code endDate} window.
     *
     * <p>Per COBOL {@code CORPT00C.cbl} lines 256-303, when the user populates
     * {@code CUSTOMI}, the program validates the user-supplied dates:
     * <ul>
     *   <li>All six date parts (SDTMMI, SDTDDI, SDTYYYYI, EDTMMI, EDTDDI, EDTYYYYI) must be non-blank</li>
     *   <li>Months must be in 01-12 range, days in 01-31 range, years numeric</li>
     *   <li>Final date validation via {@code CSUTLDTC} utility (mapped to
     *       {@code DateConversionUtil} in Java per AAP §0.6.5)</li>
     * </ul>
     *
     * <p>The Java {@code ReportRequest} class-level {@code @AssertTrue} cross-field
     * validator enforces {@code endDate >= startDate} when {@code reportType == CUSTOM}.
     * Additional date-validity checks happen in {@code ReportService} via
     * {@code DateConversionUtil}.
     */
    CUSTOM
}
