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

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable working date/time structure used by the online screens to hold the
 * current date/time and render the {@code CURDATE}/{@code CURTIME} header fields
 * that appear on every BMS map.
 *
 * <p>This is a faithful, plain-Java translation of the COBOL working-storage
 * copybook group {@code WS-DATE-TIME}. The original group carries the raw
 * numeric date/time components plus three pre-formatted display groups (edit
 * masks). This class stores the raw components as {@code int} counters and
 * reproduces the three edit masks through display-only accessors, so no
 * formatted state is ever persisted.
 *
 * <p><strong>Origin: legacy/cpy/CSDAT01Y.cpy (WS-DATE-TIME)</strong>
 *
 * <p>Verified COBOL layout:
 * <pre>
 * 01 WS-DATE-TIME.
 *   05 WS-CURDATE-DATA.
 *     10  WS-CURDATE.
 *       15  WS-CURDATE-YEAR      PIC 9(04).
 *       15  WS-CURDATE-MONTH     PIC 9(02).
 *       15  WS-CURDATE-DAY       PIC 9(02).
 *     10  WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).   (yyyymmdd)
 *     10  WS-CURTIME.
 *       15  WS-CURTIME-HOURS     PIC 9(02).
 *       15  WS-CURTIME-MINUTE    PIC 9(02).
 *       15  WS-CURTIME-SECOND    PIC 9(02).
 *       15  WS-CURTIME-MILSEC    PIC 9(02).
 *     10  WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).   (hhmmssSS)
 *   05 WS-CURDATE-MM-DD-YY.        edit mask "MM/DD/YY"
 *   05 WS-CURTIME-HH-MM-SS.        edit mask "HH:MM:SS"
 *   05 WS-TIMESTAMP.               edit mask "yyyy-MM-dd HH:mm:ss.ffffff"
 * </pre>
 *
 * <p>Design notes preserving parity with the copybook:
 * <ul>
 *   <li>The {@code REDEFINES} items {@code WS-CURDATE-N} and {@code WS-CURTIME-N}
 *       are alternate numeric views of the same bytes; they are exposed as the
 *       derived accessors {@link #getCurdateNumeric()} and
 *       {@link #getCurtimeNumeric()} rather than as separate stored state.</li>
 *   <li>{@code WS-CURTIME-MILSEC} is a two-digit {@code PIC 9(02)} field
 *       (hundredths of a second, range 0-99), <em>not</em> milliseconds; it is
 *       kept at two digits by {@link #millisec}.</li>
 *   <li>{@code WS-TIMESTAMP-TM-MS6} is a distinct six-digit {@code PIC 9(06)}
 *       fractional field (range 0-999999); it is stored separately in
 *       {@link #microOfSecond} and must not be conflated with the two-digit
 *       {@link #millisec}. The {@code WS-TIMESTAMP} year/month/day and
 *       hour/minute/second sub-fields describe the same instant as
 *       {@code WS-CURDATE}/{@code WS-CURTIME} (all populated together from the
 *       current date/time), so this model reuses the shared components and only
 *       carries the additional six-digit fraction.</li>
 *   <li>Formatting is <strong>display-only</strong>: the formatted accessors
 *       never mutate the stored numeric components.</li>
 *   <li>No floating-point types are used. Raw components are {@code int}, the
 *       numeric views are {@code long}, and formatted output is {@code String}.</li>
 * </ul>
 *
 * <p>This DTO is self-contained and imports nothing from other application
 * packages.
 */
public final class DateStruct {

    /** Four-digit calendar year. COBOL {@code WS-CURDATE-YEAR PIC 9(04)}. */
    private int year;

    /** Month of year, 1-12. COBOL {@code WS-CURDATE-MONTH PIC 9(02)}. */
    private int month;

    /** Day of month, 1-31. COBOL {@code WS-CURDATE-DAY PIC 9(02)}. */
    private int day;

    /** Hour of day, 0-23. COBOL {@code WS-CURTIME-HOURS PIC 9(02)}. */
    private int hours;

    /** Minute of hour, 0-59. COBOL {@code WS-CURTIME-MINUTE PIC 9(02)}. */
    private int minute;

    /** Second of minute, 0-59. COBOL {@code WS-CURTIME-SECOND PIC 9(02)}. */
    private int second;

    /**
     * Hundredths of a second, 0-99 (two digits). COBOL
     * {@code WS-CURTIME-MILSEC PIC 9(02)}. This is deliberately two digits and
     * is not the same as the six-digit {@link #microOfSecond} timestamp fraction.
     */
    private int millisec;

    /**
     * Six-digit fractional-seconds value, 0-999999, used only by the
     * {@code WS-TIMESTAMP} edit mask. COBOL {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)}.
     */
    private int microOfSecond;

    /**
     * Creates an empty structure with every component initialized to zero,
     * mirroring a freshly cleared COBOL {@code WS-DATE-TIME} numeric group.
     */
    public DateStruct() {
        // All int components default to 0, matching a cleared COBOL numeric group.
    }

    /**
     * Creates a fully populated structure from raw numeric components.
     *
     * @param year          four-digit calendar year ({@code WS-CURDATE-YEAR})
     * @param month         month of year, 1-12 ({@code WS-CURDATE-MONTH})
     * @param day           day of month, 1-31 ({@code WS-CURDATE-DAY})
     * @param hours         hour of day, 0-23 ({@code WS-CURTIME-HOURS})
     * @param minute        minute of hour, 0-59 ({@code WS-CURTIME-MINUTE})
     * @param second        second of minute, 0-59 ({@code WS-CURTIME-SECOND})
     * @param millisec      hundredths of a second, 0-99 ({@code WS-CURTIME-MILSEC})
     * @param microOfSecond six-digit fractional seconds, 0-999999
     *                      ({@code WS-TIMESTAMP-TM-MS6})
     */
    public DateStruct(int year, int month, int day, int hours, int minute,
                      int second, int millisec, int microOfSecond) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.hours = hours;
        this.minute = minute;
        this.second = second;
        this.millisec = millisec;
        this.microOfSecond = microOfSecond;
    }

    /**
     * Builds a new {@code DateStruct} whose components are taken from the supplied
     * {@link LocalDateTime}. This mirrors how the COBOL programs obtain and store
     * the current date/time before formatting it for display.
     *
     * @param dateTime the instant to convert; must not be {@code null}
     * @return a populated {@code DateStruct}
     * @throws NullPointerException if {@code dateTime} is {@code null}
     */
    public static DateStruct from(LocalDateTime dateTime) {
        DateStruct struct = new DateStruct();
        struct.populate(dateTime);
        return struct;
    }

    /**
     * Populates every stored component from the supplied {@link LocalDateTime}.
     *
     * <p>The sub-second fields are derived from {@link LocalDateTime#getNano()}:
     * {@link #millisec} is the hundredths-of-second value
     * ({@code nanoOfSecond / 10_000_000}, range 0-99), while
     * {@link #microOfSecond} is the six-digit microsecond value
     * ({@code nanoOfSecond / 1_000}, range 0-999999).
     *
     * @param dateTime the instant to copy from; must not be {@code null}
     * @throws NullPointerException if {@code dateTime} is {@code null}
     */
    public void populate(LocalDateTime dateTime) {
        Objects.requireNonNull(dateTime, "dateTime must not be null");
        this.year = dateTime.getYear();
        this.month = dateTime.getMonthValue();
        this.day = dateTime.getDayOfMonth();
        this.hours = dateTime.getHour();
        this.minute = dateTime.getMinute();
        this.second = dateTime.getSecond();
        int nanoOfSecond = dateTime.getNano();
        this.millisec = nanoOfSecond / 10_000_000;
        this.microOfSecond = nanoOfSecond / 1_000;
    }

    /**
     * Returns the four-digit year.
     *
     * @return the year ({@code WS-CURDATE-YEAR})
     */
    public int getYear() {
        return year;
    }

    /**
     * Sets the four-digit year.
     *
     * @param year the year to store ({@code WS-CURDATE-YEAR})
     */
    public void setYear(int year) {
        this.year = year;
    }

    /**
     * Returns the month of year (1-12).
     *
     * @return the month ({@code WS-CURDATE-MONTH})
     */
    public int getMonth() {
        return month;
    }

    /**
     * Sets the month of year (1-12).
     *
     * @param month the month to store ({@code WS-CURDATE-MONTH})
     */
    public void setMonth(int month) {
        this.month = month;
    }

    /**
     * Returns the day of month (1-31).
     *
     * @return the day ({@code WS-CURDATE-DAY})
     */
    public int getDay() {
        return day;
    }

    /**
     * Sets the day of month (1-31).
     *
     * @param day the day to store ({@code WS-CURDATE-DAY})
     */
    public void setDay(int day) {
        this.day = day;
    }

    /**
     * Returns the hour of day (0-23).
     *
     * @return the hours ({@code WS-CURTIME-HOURS})
     */
    public int getHours() {
        return hours;
    }

    /**
     * Sets the hour of day (0-23).
     *
     * @param hours the hours to store ({@code WS-CURTIME-HOURS})
     */
    public void setHours(int hours) {
        this.hours = hours;
    }

    /**
     * Returns the minute of hour (0-59).
     *
     * @return the minute ({@code WS-CURTIME-MINUTE})
     */
    public int getMinute() {
        return minute;
    }

    /**
     * Sets the minute of hour (0-59).
     *
     * @param minute the minute to store ({@code WS-CURTIME-MINUTE})
     */
    public void setMinute(int minute) {
        this.minute = minute;
    }

    /**
     * Returns the second of minute (0-59).
     *
     * @return the second ({@code WS-CURTIME-SECOND})
     */
    public int getSecond() {
        return second;
    }

    /**
     * Sets the second of minute (0-59).
     *
     * @param second the second to store ({@code WS-CURTIME-SECOND})
     */
    public void setSecond(int second) {
        this.second = second;
    }

    /**
     * Returns the two-digit hundredths-of-second value (0-99).
     *
     * @return the hundredths of a second ({@code WS-CURTIME-MILSEC})
     */
    public int getMillisec() {
        return millisec;
    }

    /**
     * Sets the two-digit hundredths-of-second value (0-99).
     *
     * @param millisec the hundredths of a second to store ({@code WS-CURTIME-MILSEC})
     */
    public void setMillisec(int millisec) {
        this.millisec = millisec;
    }

    /**
     * Returns the six-digit fractional-seconds value (0-999999) used by the
     * timestamp mask.
     *
     * @return the microsecond fraction ({@code WS-TIMESTAMP-TM-MS6})
     */
    public int getMicroOfSecond() {
        return microOfSecond;
    }

    /**
     * Sets the six-digit fractional-seconds value (0-999999) used by the
     * timestamp mask.
     *
     * @param microOfSecond the microsecond fraction to store
     *                      ({@code WS-TIMESTAMP-TM-MS6})
     */
    public void setMicroOfSecond(int microOfSecond) {
        this.microOfSecond = microOfSecond;
    }

    /**
     * Returns the packed eight-digit numeric date view {@code yyyymmdd}.
     *
     * <p>Reproduces the COBOL alternate view
     * {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)}. This is a derived
     * value computed from the stored components and does not mutate any field.
     *
     * @return the date as {@code yyyymmdd} (for example 20220718)
     */
    public long getCurdateNumeric() {
        return year * 10000L + month * 100L + day;
    }

    /**
     * Returns the packed eight-digit numeric time view {@code hhmmssSS}, where
     * {@code SS} is the two-digit hundredths-of-second value.
     *
     * <p>Reproduces the COBOL alternate view
     * {@code WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)}. This is a derived
     * value computed from the stored components and does not mutate any field.
     *
     * @return the time as {@code hhmmssSS} (for example 09050300)
     */
    public long getCurtimeNumeric() {
        return hours * 1_000_000L + minute * 10_000L + second * 100L + millisec;
    }

    /**
     * Returns the date formatted as {@code "MM/DD/YY"}, reproducing the COBOL
     * {@code WS-CURDATE-MM-DD-YY} edit mask. The year is rendered as its two
     * low-order digits ({@code year % 100}). This accessor is display-only and
     * does not mutate any stored component.
     *
     * @return the formatted date, for example {@code "07/18/22"}
     */
    public String getFormattedDateMmDdYy() {
        return String.format(Locale.ROOT, "%02d/%02d/%02d", month, day, year % 100);
    }

    /**
     * Returns the time formatted as {@code "HH:MM:SS"}, reproducing the COBOL
     * {@code WS-CURTIME-HH-MM-SS} edit mask. This accessor is display-only and
     * does not mutate any stored component.
     *
     * @return the formatted time, for example {@code "09:05:03"}
     */
    public String getFormattedTimeHhMmSs() {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minute, second);
    }

    /**
     * Returns the timestamp formatted as {@code "yyyy-MM-dd HH:mm:ss.ffffff"},
     * reproducing the COBOL {@code WS-TIMESTAMP} edit mask with a six-digit
     * fractional-seconds suffix. This accessor is display-only and does not
     * mutate any stored component.
     *
     * @return the formatted timestamp, for example
     *         {@code "2022-07-18 09:05:03.000123"}
     */
    public String getFormattedTimestamp() {
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d.%06d",
                year, month, day, hours, minute, second, microOfSecond);
    }


    /**
     * Returns a human-readable representation combining the three formatted views
     * and the stored sub-second components. This method is display-only and does
     * not mutate any stored component.
     *
     * @return a diagnostic string describing this structure
     */
    @Override
    public String toString() {
        return "DateStruct{"
                + "date=" + getFormattedDateMmDdYy()
                + ", time=" + getFormattedTimeHhMmSs()
                + ", timestamp=" + getFormattedTimestamp()
                + ", curdateNumeric=" + getCurdateNumeric()
                + ", curtimeNumeric=" + getCurtimeNumeric()
                + ", millisec=" + millisec
                + ", microOfSecond=" + microOfSecond
                + '}';
    }

    /**
     * Compares this structure with another for equality across all stored
     * components (year, month, day, hours, minute, second, hundredths, and the
     * six-digit fraction). The derived numeric and formatted views are functions
     * of these components and are therefore not compared separately.
     *
     * @param obj the object to compare with
     * @return {@code true} if {@code obj} is a {@code DateStruct} with identical
     *         components
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DateStruct other)) {
            return false;
        }
        return year == other.year
                && month == other.month
                && day == other.day
                && hours == other.hours
                && minute == other.minute
                && second == other.second
                && millisec == other.millisec
                && microOfSecond == other.microOfSecond;
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}, derived from
     * all stored components.
     *
     * @return the hash code for this structure
     */
    @Override
    public int hashCode() {
        return Objects.hash(year, month, day, hours, minute, second, millisec,
                microOfSecond);
    }
}

