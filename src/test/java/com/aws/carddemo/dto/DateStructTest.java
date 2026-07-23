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
package com.aws.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 unit tests for {@link DateStruct}, the Java translation of the
 * COBOL working-storage copybook group {@code WS-DATE-TIME}.
 *
 * <p><strong>Origin oracle: legacy/cpy/CSDAT01Y.cpy (WS-DATE-TIME)</strong>
 *
 * <p>The COBOL online screens display the current date and time through three
 * fixed edit masks defined in the copybook:
 * <ul>
 *   <li>{@code WS-CURDATE-MM-DD-YY} &rarr; {@code "MM/DD/YY"}</li>
 *   <li>{@code WS-CURTIME-HH-MM-SS} &rarr; {@code "HH:MM:SS"}</li>
 *   <li>{@code WS-TIMESTAMP} &rarr; {@code "yyyy-MM-dd HH:mm:ss.ffffff"}</li>
 * </ul>
 * The parity-critical behaviours locked down here are the exact zero-padding
 * widths ({@code %02d}, {@code %04d}, {@code %06d}), the {@code year % 100}
 * two-digit-year rule, and the derived numeric views
 * ({@code WS-CURDATE-N}/{@code WS-CURTIME-N}). The tests also confirm that the
 * two-digit {@code WS-CURTIME-MILSEC} field and the six-digit
 * {@code WS-TIMESTAMP-TM-MS6} field are never conflated, and that formatting is
 * a pure, read-only operation that leaves the stored components untouched.
 *
 * <p>This is a Surefire unit test ({@code *Test}); it uses no Spring context,
 * no database, and no Testcontainers base class.
 */
class DateStructTest {

    // ---------------------------------------------------------------------
    // Phase A - WS-CURDATE-MM-DD-YY edit mask ("MM/DD/YY")
    // ---------------------------------------------------------------------

    @Test
    void formattedDate_zeroPadsMonthAndDayAndUsesTwoDigitYear() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);

        assertThat(ds.getFormattedDateMmDdYy()).isEqualTo("07/18/22");
    }

    @Test
    void formattedDate_rendersYear2000AsDoubleZero() {
        DateStruct ds = new DateStruct();
        ds.setYear(2000);
        ds.setMonth(12);
        ds.setDay(1);

        assertThat(ds.getFormattedDateMmDdYy()).isEqualTo("12/01/00");
    }

    @Test
    void formattedDate_rendersYear1999AsNineNine() {
        DateStruct ds = new DateStruct();
        ds.setYear(1999);
        ds.setMonth(1);
        ds.setDay(5);

        assertThat(ds.getFormattedDateMmDdYy()).isEqualTo("01/05/99");
    }

    @Test
    void formattedDate_isAlwaysEightCharactersWide() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);

        assertThat(ds.getFormattedDateMmDdYy()).hasSize(8);
    }

    // ---------------------------------------------------------------------
    // Phase B - WS-CURTIME-HH-MM-SS edit mask ("HH:MM:SS")
    // ---------------------------------------------------------------------

    @Test
    void formattedTime_zeroPadsSingleDigitComponents() {
        DateStruct ds = new DateStruct();
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);

        assertThat(ds.getFormattedTimeHhMmSs()).isEqualTo("09:05:03");
    }

    @Test
    void formattedTime_rendersEndOfDay() {
        DateStruct ds = new DateStruct();
        ds.setHours(23);
        ds.setMinute(59);
        ds.setSecond(59);

        assertThat(ds.getFormattedTimeHhMmSs()).isEqualTo("23:59:59");
    }

    @Test
    void formattedTime_rendersMidnight() {
        DateStruct ds = new DateStruct();
        ds.setHours(0);
        ds.setMinute(0);
        ds.setSecond(0);

        assertThat(ds.getFormattedTimeHhMmSs()).isEqualTo("00:00:00");
    }

    @Test
    void formattedTime_isAlwaysEightCharactersWide() {
        DateStruct ds = new DateStruct();
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);

        assertThat(ds.getFormattedTimeHhMmSs()).hasSize(8);
    }

    // ---------------------------------------------------------------------
    // Phase C - WS-TIMESTAMP edit mask ("yyyy-MM-dd HH:mm:ss.ffffff")
    // The fractional field is the six-digit WS-TIMESTAMP-TM-MS6, stored as
    // microOfSecond - distinct from the two-digit WS-CURTIME-MILSEC.
    // ---------------------------------------------------------------------

    @Test
    void formattedTimestamp_reproducesSixDigitFraction() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);
        ds.setMicroOfSecond(123456);

        assertThat(ds.getFormattedTimestamp()).isEqualTo("2022-07-18 09:05:03.123456");
    }

    @Test
    void formattedTimestamp_zeroPadsFractionToSixDigits() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);
        ds.setMicroOfSecond(7);

        assertThat(ds.getFormattedTimestamp())
                .isEqualTo("2022-07-18 09:05:03.000007")
                .endsWith(".000007");
    }

    @Test
    void formattedTimestamp_hasTwentySixCharsAndMatchesMask() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);
        ds.setMicroOfSecond(123456);

        assertThat(ds.getFormattedTimestamp())
                .hasSize(26)
                .matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$");
    }

    // ---------------------------------------------------------------------
    // Phase D - derived numeric views (WS-CURDATE-N / WS-CURTIME-N REDEFINES)
    // ---------------------------------------------------------------------

    @Test
    void curdateNumeric_packsYearMonthDayAsYyyymmdd() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);

        assertThat(ds.getCurdateNumeric()).isEqualTo(20220718L);
    }

    @Test
    void curtimeNumeric_packsHoursMinuteSecondMillisecAsHhmmssSs() {
        DateStruct ds = new DateStruct();
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);
        ds.setMillisec(42);

        // hours=09, minute=05, second=03, millisec(hundredths)=42 -> 9050342.
        assertThat(ds.getCurtimeNumeric()).isEqualTo(9050342L);
    }

    // ---------------------------------------------------------------------
    // Phase E - formatting and numeric views are pure / non-mutating
    // ---------------------------------------------------------------------

    @Test
    void formattingAndNumericViews_doNotMutateStoredComponents() {
        DateStruct ds = new DateStruct();
        ds.setYear(2022);
        ds.setMonth(7);
        ds.setDay(18);
        ds.setHours(9);
        ds.setMinute(5);
        ds.setSecond(3);
        ds.setMillisec(42);
        ds.setMicroOfSecond(123456);

        // Capture the outputs, then invoke every accessor a second time. A
        // display-only accessor must be idempotent and must never write back to
        // the stored numeric components.
        String date1 = ds.getFormattedDateMmDdYy();
        String time1 = ds.getFormattedTimeHhMmSs();
        String timestamp1 = ds.getFormattedTimestamp();
        long curdate1 = ds.getCurdateNumeric();
        long curtime1 = ds.getCurtimeNumeric();

        assertThat(ds.getFormattedDateMmDdYy()).isEqualTo(date1);
        assertThat(ds.getFormattedTimeHhMmSs()).isEqualTo(time1);
        assertThat(ds.getFormattedTimestamp()).isEqualTo(timestamp1);
        assertThat(ds.getCurdateNumeric()).isEqualTo(curdate1);
        assertThat(ds.getCurtimeNumeric()).isEqualTo(curtime1);

        // Every stored component still holds its original value.
        assertThat(ds.getYear()).isEqualTo(2022);
        assertThat(ds.getMonth()).isEqualTo(7);
        assertThat(ds.getDay()).isEqualTo(18);
        assertThat(ds.getHours()).isEqualTo(9);
        assertThat(ds.getMinute()).isEqualTo(5);
        assertThat(ds.getSecond()).isEqualTo(3);
        assertThat(ds.getMillisec()).isEqualTo(42);
        assertThat(ds.getMicroOfSecond()).isEqualTo(123456);
    }

    // ---------------------------------------------------------------------
    // Phase F - static from(LocalDateTime) populate helper
    // millisec = nanoOfSecond / 10_000_000 (hundredths, 0-99);
    // microOfSecond = nanoOfSecond / 1_000 (six-digit fraction, 0-999999).
    // ---------------------------------------------------------------------

    @Test
    void from_localDateTime_populatesComponentsAndFormatsIdentically() {
        DateStruct ds = DateStruct.from(LocalDateTime.of(2022, 7, 18, 9, 5, 3, 123_456_000));

        assertThat(ds.getYear()).isEqualTo(2022);
        assertThat(ds.getMonth()).isEqualTo(7);
        assertThat(ds.getDay()).isEqualTo(18);
        assertThat(ds.getHours()).isEqualTo(9);
        assertThat(ds.getMinute()).isEqualTo(5);
        assertThat(ds.getSecond()).isEqualTo(3);
        // 123_456_000 ns -> hundredths = 12, six-digit fraction = 123456.
        assertThat(ds.getMillisec()).isEqualTo(12);
        assertThat(ds.getMicroOfSecond()).isEqualTo(123456);

        assertThat(ds.getFormattedDateMmDdYy()).isEqualTo("07/18/22");
        assertThat(ds.getFormattedTimeHhMmSs()).isEqualTo("09:05:03");
        assertThat(ds.getFormattedTimestamp()).isEqualTo("2022-07-18 09:05:03.123456");
        assertThat(ds.getCurdateNumeric()).isEqualTo(20220718L);
        assertThat(ds.getCurtimeNumeric()).isEqualTo(9050312L);
    }
}
