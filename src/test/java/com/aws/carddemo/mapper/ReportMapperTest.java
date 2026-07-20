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
 */
package com.aws.carddemo.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.dto.TransactionReportRequest;
import com.aws.carddemo.dto.TransactionReportResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * Fast, isolated, pure-logic unit tests for {@link ReportMapper}, the hand-written mapper for the
 * Transaction Reports screen (BMS map {@code CORPT00} / mapset {@code CORPT0A}) &mdash; the Java
 * re-platform of the field-assembly logic in COBOL program {@code CORPT00C} (online transaction
 * {@code CR00}, relocated to {@code legacy/cbl/CORPT00C.cbl}). Field lineage traces to
 * {@code app/cpy-bms/CORPT00.CPY} (the report-request screen) and the report's underlying record
 * layout {@code app/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, referenced only, never mapped here).
 *
 * <p>{@code ReportMapper} is deliberately thin and holds <em>no</em> report business logic. The
 * two behaviors under test are:</p>
 * <ol>
 *   <li>{@link ReportMapper#toResponse(String, LocalDateTime)} &mdash; header projection assembly
 *       (constant transaction/program names, both title lines, and the current date/time rendered
 *       in the legacy {@code MM/DD/YY} and {@code HH:MM:SS} masks), with the status/error message
 *       passed through verbatim; and</li>
 *   <li>{@link ReportMapper#startDate(TransactionReportRequest)} /
 *       {@link ReportMapper#endDate(TransactionReportRequest)} &mdash; composition of the discrete
 *       month/day/year screen parts into a single {@link LocalDate}, zero-padding the two-column
 *       month and day, and returning {@code null} (never throwing) when the custom range is not
 *       populated or does not form a real calendar date.</li>
 * </ol>
 *
 * <p><strong>Test style.</strong> These tests use JUnit 5 (Jupiter) with AssertJ and exercise a
 * plain {@code new ReportMapper()} instance directly. There is <strong>no</strong> Spring context,
 * database, Mockito, or Testcontainers dependency, so the suite runs headlessly and reproducibly.
 * The reference {@link LocalDateTime} is fixed so the header date/time assertions are deterministic
 * regardless of the run date. {@code ReportMapper} lives in the same package and is therefore
 * referenced without an import.</p>
 */
class ReportMapperTest {

    /**
     * The single mapper under test. It is stateless, so one shared instance is reused across every
     * test method (mirroring the singleton Spring {@code @Component} it becomes at runtime).
     */
    private final ReportMapper mapper = new ReportMapper();

    /**
     * Deterministic reference date-time (the {@code FUNCTION CURRENT-DATE} equivalent):
     * 2022-07-19 23:15:58. Chosen to match the migration corpus timestamp and to render
     * unambiguously under both display masks.
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /**
     * Expected {@code MM/DD/YY} rendering of {@link #NOW}'s date ({@code DateUtils.formatDateMmDdYy}).
     */
    private static final String EXPECTED_CURRENT_DATE = "07/19/22";

    /**
     * Expected {@code HH:MM:SS} rendering of {@link #NOW}'s time ({@code DateUtils.formatTimeHhMmSs}).
     */
    private static final String EXPECTED_CURRENT_TIME = "23:15:58";

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /**
     * Builds a {@code custom}-range report request with the supplied discrete date parts, using the
     * record's canonical constructor. The {@code custom} selector is marked while {@code monthly}
     * and {@code yearly} are blank and {@code confirm} is blank. The {@code action} (PF-key)
     * component is left {@code null}: {@code ReportMapper} never reads it, so it is irrelevant to
     * every behavior under test. The date-part component order is month, day, year exactly as the
     * record declares.
     *
     * @param startMonth start month part ({@code SDTMMI})
     * @param startDay   start day part ({@code SDTDDI})
     * @param startYear  start year part ({@code SDTYYYYI})
     * @param endMonth   end month part ({@code EDTMMI})
     * @param endDay     end day part ({@code EDTDDI})
     * @param endYear    end year part ({@code EDTYYYYI})
     * @return a custom-range {@link TransactionReportRequest}
     */
    private static TransactionReportRequest customRequest(
            String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear) {
        return new TransactionReportRequest(
                "",
                "",
                "Y",
                startMonth, startDay, startYear,
                endMonth, endDay, endYear,
                "",
                null);
    }

    /**
     * Builds a {@code monthly}-range report request in which the custom start/end date parts are
     * absent ({@code null}) &mdash; the realistic shape when an operator selects the monthly report
     * type rather than a custom range. The {@code action} (PF-key) component is left {@code null}
     * for the same reason as in {@link #customRequest}.
     *
     * @return a monthly {@link TransactionReportRequest} with no custom date parts
     */
    private static TransactionReportRequest monthlyRequest() {
        return new TransactionReportRequest(
                "Y",
                "",
                "",
                null, null, null,
                null, null, null,
                "",
                null);
    }

    // ------------------------------------------------------------------------
    // Header response — toResponse(String, LocalDateTime)
    // ------------------------------------------------------------------------

    /**
     * The header projection carries the constant transaction/program names and title lines, and
     * renders {@link #NOW} into the legacy {@code MM/DD/YY} and {@code HH:MM:SS} display masks. A
     * {@code null} status message denotes "no message" and is preserved as {@code null}.
     */
    @Test
    void toResponsePopulatesHeaderConstantsAndFormattedDateTime() {
        TransactionReportResponse resp = mapper.toResponse(null, NOW);

        assertThat(resp).isNotNull();
        assertThat(resp.transactionName()).isEqualTo("CR00");
        assertThat(resp.programName()).isEqualTo("CORPT00C");
        assertThat(resp.title01()).isEqualTo("AWS Mainframe Modernization");
        assertThat(resp.title02()).isEqualTo("CardDemo");
        assertThat(resp.currentDate()).isEqualTo(EXPECTED_CURRENT_DATE);
        assertThat(resp.currentTime()).isEqualTo(EXPECTED_CURRENT_TIME);
        assertThat(resp.errorMessage()).isNull();
    }

    /**
     * A supplied status/error message is surfaced verbatim on {@code errorMessage} ({@code ERRMSGO},
     * {@code PIC X(78)}); the header constants and rendered date/time are unaffected.
     */
    @Test
    void toResponsePassesErrorMessageThroughVerbatim() {
        String message = "Date range required";

        TransactionReportResponse resp = mapper.toResponse(message, NOW);

        assertThat(resp.errorMessage()).isEqualTo(message);
        assertThat(resp.transactionName()).isEqualTo("CR00");
        assertThat(resp.programName()).isEqualTo("CORPT00C");
        assertThat(resp.currentDate()).isEqualTo(EXPECTED_CURRENT_DATE);
        assertThat(resp.currentTime()).isEqualTo(EXPECTED_CURRENT_TIME);
    }

    /**
     * The reference date-time is mandatory: a {@code null} {@code now} triggers the mapper's
     * {@code Objects.requireNonNull} guard and yields a {@link NullPointerException}.
     */
    @Test
    void toResponseNullNowThrowsNullPointerException() {
        assertThatThrownBy(() -> mapper.toResponse("any message", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now");
    }

    /**
     * The public header constants match the values the legacy screen displayed
     * ({@code WS-TRANID}, {@code WS-PGMNAME}, and the two {@code CCDA-TITLE} lines).
     */
    @Test
    void headerConstantsMatchLegacyScreenValues() {
        assertThat(ReportMapper.TRANSACTION_NAME).isEqualTo("CR00");
        assertThat(ReportMapper.PROGRAM_NAME).isEqualTo("CORPT00C");
        assertThat(ReportMapper.TITLE_01).isEqualTo("AWS Mainframe Modernization");
        assertThat(ReportMapper.TITLE_02).isEqualTo("CardDemo");
    }

    // ------------------------------------------------------------------------
    // Custom date-range composition — startDate/endDate
    // ------------------------------------------------------------------------

    /**
     * A fully populated custom range composes both endpoints exactly: start {@code 2022-01-01} and
     * end {@code 2022-12-31}. Two-digit month/day parts are used verbatim (no padding needed).
     */
    @Test
    void startAndEndDateComposeExpectedCustomRange() {
        TransactionReportRequest req =
                customRequest("01", "01", "2022", "12", "31", "2022");

        assertThat(mapper.startDate(req)).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(mapper.endDate(req)).isEqualTo(LocalDate.of(2022, 12, 31));
    }

    /**
     * Single-digit month and day parts are left-padded to two columns before the {@code CCYYMMDD}
     * assembly, so an operator entry of month {@code "3"} / day {@code "5"} composes {@code 20220305}
     * &rarr; {@code 2022-03-05}. Proven for both the start and end endpoints.
     */
    @Test
    void startAndEndDateZeroPadSingleDigitMonthAndDay() {
        TransactionReportRequest req =
                customRequest("3", "5", "2022", "4", "7", "2022");

        assertThat(mapper.startDate(req)).isEqualTo(LocalDate.of(2022, 3, 5));
        assertThat(mapper.endDate(req)).isEqualTo(LocalDate.of(2022, 4, 7));
    }

    /**
     * Surrounding whitespace on populated parts is trimmed before assembly, so {@code " 2022 "} /
     * {@code " 6 "} / {@code " 9 "} still composes {@code 2022-06-09}.
     */
    @Test
    void startDateTrimsWhitespaceAroundParts() {
        TransactionReportRequest req =
                customRequest(" 6 ", " 9 ", " 2022 ", " 6 ", " 9 ", " 2022 ");

        assertThat(mapper.startDate(req)).isEqualTo(LocalDate.of(2022, 6, 9));
        assertThat(mapper.endDate(req)).isEqualTo(LocalDate.of(2022, 6, 9));
    }

    // ------------------------------------------------------------------------
    // Null / non-custom / blank / invalid handling
    // ------------------------------------------------------------------------

    /**
     * A monthly (non-custom) request leaves the custom start/end parts absent, so both composition
     * helpers return {@code null} &mdash; the custom range is simply not populated.
     */
    @Test
    void startAndEndDateReturnNullForMonthlyRequest() {
        TransactionReportRequest req = monthlyRequest();

        assertThat(mapper.startDate(req)).isNull();
        assertThat(mapper.endDate(req)).isNull();
    }

    /**
     * A custom request whose date parts are blank (empty or all-whitespace, mirroring the
     * space-filled 3270 buffer) composes to {@code null} on both endpoints and, critically, does
     * <em>not</em> throw &mdash; the mapper is non-failing and holds no validation policy.
     */
    @Test
    void startAndEndDateReturnNullForBlankPartsWithoutThrowing() {
        TransactionReportRequest req =
                customRequest("", "", "", "   ", "   ", "   ");

        assertThatCode(() -> {
            assertThat(mapper.startDate(req)).isNull();
            assertThat(mapper.endDate(req)).isNull();
        }).doesNotThrowAnyException();
    }

    /**
     * A {@code null} request is tolerated: both helpers short-circuit to {@code null} rather than
     * dereferencing it.
     */
    @Test
    void startAndEndDateReturnNullForNullRequest() {
        assertThat(mapper.startDate(null)).isNull();
        assertThat(mapper.endDate(null)).isNull();
    }

    /**
     * A syntactically present but calendar-invalid custom range ({@code 2022-02-30}) is rejected by
     * the {@code DateUtils} strict-calendar guard, so the helper returns {@code null} rather than
     * throwing.
     */
    @Test
    void startAndEndDateReturnNullForInvalidCalendarDate() {
        TransactionReportRequest req =
                customRequest("02", "30", "2022", "02", "30", "2022");

        assertThatCode(() -> {
            assertThat(mapper.startDate(req)).isNull();
            assertThat(mapper.endDate(req)).isNull();
        }).doesNotThrowAnyException();
    }

    /**
     * A partially populated range (year blank while month and day are present) is treated as
     * unpopulated and composes to {@code null}; any one absent part suppresses the whole endpoint.
     */
    @Test
    void startDateReturnsNullWhenAnyPartMissing() {
        TransactionReportRequest req =
                customRequest("01", "01", "", "12", "", "2022");

        assertThat(mapper.startDate(req)).isNull();
        assertThat(mapper.endDate(req)).isNull();
    }
}
