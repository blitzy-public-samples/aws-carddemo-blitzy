/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Part of the Java / Spring Boot re-platform of the AWS CardDemo mainframe
 * application. This hand-written mapper re-expresses the field-level contract of
 * a BMS 3270 screen as request/response DTO translation, with no feature
 * expansion and no mapping framework (MapStruct is intentionally not used).
 */
package com.aws.carddemo.mapper;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.dto.TransactionReportRequest;
import com.aws.carddemo.dto.TransactionReportResponse;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Hand-written mapper for the Transaction Reports screen (BMS map {@code CORPT00},
 * mapset {@code CORPT0A}) &mdash; the Java re-platform of the field-assembly logic in
 * COBOL program {@code CORPT00C} (online transaction {@code CR00}, source
 * {@code legacy/cbl/CORPT00C.cbl}). The screen lets an operator request a transaction
 * report over one of three mutually exclusive ranges (the current month, the current
 * year, or a custom start/end date range) and confirm submission of the report job.
 *
 * <p><strong>Deliberately thin.</strong> The report itself is produced by the batch
 * layer ({@code service.ReportService} &rarr; {@code batch.TransactionReportJob}); this
 * mapper performs only two field-level translations and holds <em>no</em> business
 * logic:</p>
 * <ol>
 *   <li>{@link #toResponse(String, LocalDateTime)} &mdash; assembles the screen header
 *       (transaction name, title lines, program name) plus the current date/time and a
 *       status/error message, mirroring the {@code CORPT00C} header population at
 *       {@code legacy/cbl/CORPT00C.cbl:L613-L616}; and</li>
 *   <li>{@link #startDate(TransactionReportRequest)} /
 *       {@link #endDate(TransactionReportRequest)} &mdash; compose the discrete
 *       month/day/year parts the DTO preserves (mirroring the BMS map) into a single
 *       {@link LocalDate} for the service to consume.</li>
 * </ol>
 *
 * <p><strong>Report-type semantics preserved.</strong> On the legacy screen the
 * {@code monthly} / {@code yearly} / {@code custom} markers are mutually exclusive
 * report-type selectors, and only the {@code custom} path uses the explicit
 * start/end range. The date-window defaulting for the monthly and yearly selections,
 * and the enforcement of mutual exclusivity, are business rules that live in
 * {@code service.ReportService}; they are intentionally <em>not</em> reproduced here.
 * This mapper only offers a faithful part&rarr;date composition for the custom range.</p>
 *
 * <p><strong>Date handling.</strong> All date parsing, validation, and formatting is
 * delegated to {@link DateUtils} (the migration of the {@code CSDAT01Y} /
 * {@code CSUTLDPY} / {@code CSUTLDWY} date copybooks); no date is parsed or formatted
 * inline. {@link DateUtils} operates on the compact eight-digit {@code CCYYMMDD} form,
 * so the composition helpers assemble {@code YYYYMMDD} (year followed by a two-digit
 * month and a two-digit day) before delegating to
 * {@link DateUtils#isValidCcyyMmDd(String)} and {@link DateUtils#parseCcyyMmDd(String)}.</p>
 *
 * <p><strong>Statelessness.</strong> This is a stateless Spring {@link Component}: it
 * holds no mutable fields and is safe to share as a singleton across threads.</p>
 */
@Component
public class ReportMapper {

    /**
     * Screen (transaction) name shown in the header. Sourced from
     * {@code WS-TRANID PIC X(04) VALUE 'CR00'} ({@code legacy/cbl/CORPT00C.cbl:L38})
     * and moved to {@code TRNNAMEO} of symbolic map {@code CORPT0AO}.
     */
    public static final String TRANSACTION_NAME = "CR00";

    /**
     * Originating program name shown in the header. Sourced from
     * {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} ({@code legacy/cbl/CORPT00C.cbl:L37})
     * and moved to {@code PGMNAMEO} of symbolic map {@code CORPT0AO}.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * First application title line. The logical text of {@code CCDA-TITLE01}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}), moved to {@code TITLE01O}
     * of map {@code CORPT0AO}. The copybook centres the value within the 40-column
     * field purely for 3270 display; the re-platformed application performs no
     * terminal rendering, so the un-padded logical text is carried here.
     */
    public static final String TITLE_01 = "AWS Mainframe Modernization";

    /**
     * Second application title line. The logical text of {@code CCDA-TITLE02}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}), moved to {@code TITLE02O}
     * of map {@code CORPT0AO}. See {@link #TITLE_01} for the padding note.
     */
    public static final String TITLE_02 = "CardDemo";

    /**
     * The length at which a zero-padded month or day part is already complete
     * ({@code MM} / {@code DD} are two characters wide on the screen).
     */
    private static final int PART_WIDTH_MM_DD = 2;

    /**
     * Assembles the {@link TransactionReportResponse} header projection for the
     * Transaction Reports screen, reproducing the header population of
     * {@code CORPT00C} ({@code legacy/cbl/CORPT00C.cbl:L613-L616}): the constant
     * transaction name, both title lines, and the program name, together with the
     * current date and time rendered in the legacy display masks.
     *
     * <p>The {@code currentDate} field uses the {@code MM/DD/YY} mask
     * ({@link DateUtils#formatDateMmDdYy(LocalDate)}) and {@code currentTime} uses the
     * {@code HH:MM:SS} mask ({@link DateUtils#formatTimeHhMmSs(java.time.LocalTime)}),
     * both derived from {@code CSDAT01Y.cpy}. The {@code errorMessage} is passed through
     * verbatim to {@code ERRMSGO} ({@code PIC X(78)}); a {@code null} message denotes a
     * screen with no status text and is preserved as {@code null}.</p>
     *
     * @param errorMessage the status or error message to surface in the screen footer;
     *                     may be {@code null} when there is no message to display
     * @param now          the reference date-time from which the header date and time
     *                     are rendered (the {@code FUNCTION CURRENT-DATE} equivalent);
     *                     must not be {@code null}
     * @return a fully populated {@link TransactionReportResponse} header projection
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public TransactionReportResponse toResponse(String errorMessage, LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        String currentDate = DateUtils.formatDateMmDdYy(now.toLocalDate());
        String currentTime = DateUtils.formatTimeHhMmSs(now.toLocalTime());
        return new TransactionReportResponse(
                TRANSACTION_NAME,
                TITLE_01,
                currentDate,
                PROGRAM_NAME,
                TITLE_02,
                currentTime,
                errorMessage);
    }

    /**
     * Composes the custom-range <em>start</em> date from the discrete
     * {@code startYear} / {@code startMonth} / {@code startDay} parts of the request
     * (map fields {@code SDTYYYYI} / {@code SDTMMI} / {@code SDTDDI}).
     *
     * <p>Returns {@code null} when the custom range is not populated (any of the three
     * parts is {@code null} or blank) or when the assembled value is not a real calendar
     * date. Distinguishing "range required but missing/invalid" from "range not
     * requested" is a business rule owned by {@code service.ReportService}; this mapper
     * only reports the faithfully-composed date, or {@code null} when one cannot be
     * formed.</p>
     *
     * @param request the report request; may be {@code null}
     * @return the composed start {@link LocalDate}, or {@code null} if the parts are
     *         absent, blank, or do not form a valid {@code CCYYMMDD} calendar date
     */
    public LocalDate startDate(TransactionReportRequest request) {
        if (request == null) {
            return null;
        }
        return composeCcyyMmDd(request.startYear(), request.startMonth(), request.startDay());
    }

    /**
     * Composes the custom-range <em>end</em> date from the discrete
     * {@code endYear} / {@code endMonth} / {@code endDay} parts of the request
     * (map fields {@code EDTYYYYI} / {@code EDTMMI} / {@code EDTDDI}). Behaves exactly
     * as {@link #startDate(TransactionReportRequest)} for the end parts.
     *
     * @param request the report request; may be {@code null}
     * @return the composed end {@link LocalDate}, or {@code null} if the parts are
     *         absent, blank, or do not form a valid {@code CCYYMMDD} calendar date
     */
    public LocalDate endDate(TransactionReportRequest request) {
        if (request == null) {
            return null;
        }
        return composeCcyyMmDd(request.endYear(), request.endMonth(), request.endDay());
    }

    /**
     * Assembles a compact {@code CCYYMMDD} string from the discrete year, month, and
     * day parts and delegates validation and parsing to {@link DateUtils}. The month
     * and day parts are zero-padded to two digits (so an operator entry of {@code "1"}
     * becomes {@code "01"}); the year part is used as supplied (the screen captures it
     * as a four-character field). If the composed value is not exactly eight digits or
     * is not a real calendar date, {@link DateUtils#isValidCcyyMmDd(String)} rejects it
     * and this method returns {@code null} rather than throwing, keeping the mapper
     * non-failing and free of embedded validation policy.
     *
     * @param year  the four-digit year part; may be {@code null} or blank
     * @param month the one- or two-digit month part; may be {@code null} or blank
     * @param day   the one- or two-digit day part; may be {@code null} or blank
     * @return the parsed {@link LocalDate}, or {@code null} when the parts are absent,
     *         blank, or do not form a valid calendar date
     */
    private static LocalDate composeCcyyMmDd(String year, String month, String day) {
        if (isBlank(year) || isBlank(month) || isBlank(day)) {
            return null;
        }
        String ccyymmdd = year.trim() + padToTwo(month.trim()) + padToTwo(day.trim());
        if (!DateUtils.isValidCcyyMmDd(ccyymmdd)) {
            return null;
        }
        return DateUtils.parseCcyyMmDd(ccyymmdd);
    }

    /**
     * Left-pads a single-character month or day part with a leading zero so it occupies
     * the two-column width the {@code CCYYMMDD} form requires. Values that are already
     * two (or more) characters are returned unchanged; over-length or non-numeric values
     * are left intact so the downstream {@link DateUtils#isValidCcyyMmDd(String)} guard
     * can reject them uniformly.
     *
     * @param value a trimmed, non-empty month or day part
     * @return the value padded to two characters when it was a single character
     */
    private static String padToTwo(String value) {
        return value.length() < PART_WIDTH_MM_DD ? "0" + value : value;
    }

    /**
     * Null-safe blank test used to detect an unpopulated date part (the space-filled
     * 3270 buffer maps to blank strings).
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is {@code null}, empty, or all whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
