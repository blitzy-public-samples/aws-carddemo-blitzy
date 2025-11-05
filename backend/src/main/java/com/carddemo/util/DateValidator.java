package com.carddemo.util;

import java.time.LocalDate;
import java.time.Month;

/**
 * Date validation utility class providing comprehensive date validation rules
 * transformed from COBOL CSUTLDPY copybook date validation procedures.
 * 
 * <p>This class preserves COBOL validation logic including:
 * <ul>
 *   <li>Century restrictions (19xx and 20xx only) per Y2K-era design</li>
 *   <li>Month validation (01-12)</li>
 *   <li>Day-of-month validation considering month-specific day counts</li>
 *   <li>Leap year handling for February 29th validation</li>
 *   <li>Date-of-birth validation (cannot be in future)</li>
 * </ul>
 * 
 * <p>Source: CSUTLDPY.cpy paragraphs:
 * <ul>
 *   <li>EDIT-DATE-CCYYMMDD (lines 18-331)</li>
 *   <li>EDIT-YEAR-CCYY (lines 25-88)</li>
 *   <li>EDIT-MONTH (lines 91-147)</li>
 *   <li>EDIT-DAY (lines 150-207)</li>
 *   <li>EDIT-DAY-MONTH-YEAR (lines 209-282)</li>
 *   <li>EDIT-DATE-OF-BIRTH (lines 341-372)</li>
 * </ul>
 * 
 * @version CardDemo_v1.0 COBOL-to-Java Migration
 */
public final class DateValidator {
    
    /**
     * Minimum valid century value (19 for 1900s).
     * Corresponds to COBOL LAST-CENTURY condition (line 71).
     */
    private static final int MIN_VALID_CENTURY = 19;
    
    /**
     * Maximum valid century value (20 for 2000s).
     * Corresponds to COBOL THIS-CENTURY condition (line 70).
     */
    private static final int MAX_VALID_CENTURY = 20;
    
    /**
     * Minimum valid year (1900).
     * Derived from MIN_VALID_CENTURY * 100.
     */
    private static final int MIN_VALID_YEAR = 1900;
    
    /**
     * Maximum valid year (2099).
     * Derived from MAX_VALID_CENTURY * 100 + 99.
     */
    private static final int MAX_VALID_YEAR = 2099;
    
    /**
     * Minimum valid month (January = 1).
     * Corresponds to COBOL WS-VALID-MONTH condition VALUES 1 THROUGH 12 (line 111).
     */
    private static final int MIN_VALID_MONTH = 1;
    
    /**
     * Maximum valid month (December = 12).
     * Corresponds to COBOL WS-VALID-MONTH condition VALUES 1 THROUGH 12 (line 111).
     */
    private static final int MAX_VALID_MONTH = 12;
    
    /**
     * Minimum valid day (1).
     * Corresponds to COBOL WS-VALID-DAY condition VALUES 1 THROUGH 31 (line 187).
     */
    private static final int MIN_VALID_DAY = 1;
    
    /**
     * Maximum valid day (31).
     * Corresponds to COBOL WS-VALID-DAY condition VALUES 1 THROUGH 31 (line 187).
     */
    private static final int MAX_VALID_DAY = 31;
    
    /**
     * February month number.
     * Corresponds to COBOL WS-FEBRUARY condition VALUE 2 (line 228).
     */
    private static final int FEBRUARY = 2;
    
    /**
     * Days in February for non-leap year.
     */
    private static final int FEBRUARY_DAYS_NON_LEAP = 28;
    
    /**
     * Days in February for leap year.
     */
    private static final int FEBRUARY_DAYS_LEAP = 29;
    
    /**
     * Maximum days in any month (31).
     */
    private static final int MAX_DAYS_IN_MONTH = 31;
    
    /**
     * Days in months with 30 days.
     */
    private static final int DAYS_30 = 30;
    
    /**
     * Divisor for leap year calculation (year divisible by 4).
     * Corresponds to COBOL WS-DIV-BY = 4 (line 248).
     */
    private static final int LEAP_YEAR_DIVISOR_4 = 4;
    
    /**
     * Divisor for century leap year calculation (year divisible by 400).
     * Corresponds to COBOL WS-DIV-BY = 400 (line 246).
     */
    private static final int LEAP_YEAR_DIVISOR_400 = 400;
    
    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private DateValidator() {
        throw new UnsupportedOperationException("DateValidator is a utility class and cannot be instantiated");
    }
    
    /**
     * Validates a complete date including year, month, and day components.
     * 
     * <p>Implements COBOL EDIT-DATE-CCYYMMDD paragraph logic (lines 18-331):
     * <ul>
     *   <li>Validates year is in valid century range (19xx or 20xx)</li>
     *   <li>Validates month is between 1 and 12</li>
     *   <li>Validates day is valid for the given month and year</li>
     *   <li>Validates leap year logic for February 29th</li>
     * </ul>
     * 
     * @param date the LocalDate to validate, must not be null
     * @return true if the date is valid according to COBOL validation rules, false otherwise
     * @throws IllegalArgumentException if date is null
     */
    public static boolean isValidDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        
        // Validate year - corresponds to EDIT-YEAR-CCYY paragraph (lines 25-88)
        if (!isValidYear(year)) {
            return false;
        }
        
        // Validate month - corresponds to EDIT-MONTH paragraph (lines 91-147)
        if (!isValidMonth(month)) {
            return false;
        }
        
        // Validate day for the given month and year - corresponds to EDIT-DAY and EDIT-DAY-MONTH-YEAR (lines 150-282)
        if (!isValidDay(year, month, day)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates that a date falls within the specified range (inclusive).
     * 
     * <p>The date must be greater than or equal to minDate and less than or equal to maxDate.
     * All dates must be valid according to COBOL validation rules.
     * 
     * @param date the date to validate, must not be null
     * @param minDate the minimum allowed date (inclusive), must not be null
     * @param maxDate the maximum allowed date (inclusive), must not be null
     * @return true if the date is within the valid range, false otherwise
     * @throws IllegalArgumentException if any parameter is null or if minDate is after maxDate
     */
    public static boolean isValidDateRange(LocalDate date, LocalDate minDate, LocalDate maxDate) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        if (minDate == null) {
            throw new IllegalArgumentException("Minimum date must not be null");
        }
        if (maxDate == null) {
            throw new IllegalArgumentException("Maximum date must not be null");
        }
        if (minDate.isAfter(maxDate)) {
            throw new IllegalArgumentException("Minimum date must not be after maximum date");
        }
        
        // First validate the date itself according to COBOL rules
        if (!isValidDate(date)) {
            return false;
        }
        
        // Check if date is within range
        return !date.isBefore(minDate) && !date.isAfter(maxDate);
    }
    
    /**
     * Validates a year according to COBOL century restrictions.
     * 
     * <p>Implements COBOL EDIT-YEAR-CCYY paragraph logic (lines 25-88).
     * Per Y2K-era design constraints (lines 66-69), only years in the 19xx and 20xx
     * centuries are considered valid:
     * <ul>
     *   <li>1900 through 1999 (19th century - LAST-CENTURY)</li>
     *   <li>2000 through 2099 (20th century - THIS-CENTURY)</li>
     * </ul>
     * 
     * <p>This intentionally limits valid years to maintain compatibility with the
     * original COBOL validation logic which states:
     * "Not having learnt our lesson from history and Y2K, and being unable to imagine
     * COBOL in the 2100s, we code only 19 and 20 as valid century values."
     * 
     * @param year the year to validate (4-digit format)
     * @return true if year is between 1900-1999 or 2000-2099, false otherwise
     */
    public static boolean isValidYear(int year) {
        // Corresponds to COBOL lines 70-84: IF THIS-CENTURY OR LAST-CENTURY
        // THIS-CENTURY = 20, LAST-CENTURY = 19
        int century = year / 100;
        return century == MIN_VALID_CENTURY || century == MAX_VALID_CENTURY;
    }
    
    /**
     * Validates a month value.
     * 
     * <p>Implements COBOL EDIT-MONTH paragraph logic (lines 91-147).
     * Month must be a numeric value between 1 and 12 (inclusive).
     * 
     * <p>Corresponds to COBOL 88-level condition:
     * <pre>
     * 88 WS-VALID-MONTH VALUES 1 THROUGH 12. (line 111)
     * </pre>
     * 
     * @param month the month to validate (1-12, where 1=January, 12=December)
     * @return true if month is between 1 and 12 (inclusive), false otherwise
     */
    public static boolean isValidMonth(int month) {
        // Corresponds to COBOL WS-VALID-MONTH condition (line 111)
        return month >= MIN_VALID_MONTH && month <= MAX_VALID_MONTH;
    }
    
    /**
     * Validates a day value for the given year and month.
     * 
     * <p>Implements COBOL EDIT-DAY and EDIT-DAY-MONTH-YEAR paragraph logic (lines 150-282).
     * Performs comprehensive day validation including:
     * <ul>
     *   <li>Basic range check (1-31)</li>
     *   <li>Month-specific day count validation (e.g., no 31st day in April)</li>
     *   <li>February special handling (28 or 29 days depending on leap year)</li>
     *   <li>Leap year calculation for February 29th validation</li>
     * </ul>
     * 
     * <p>Corresponds to COBOL validation rules:
     * <ul>
     *   <li>Lines 187-200: Base day range validation (1-31)</li>
     *   <li>Lines 213-226: 31-day month validation</li>
     *   <li>Lines 228-240: February 30-day rejection</li>
     *   <li>Lines 243-272: February 29-day leap year validation</li>
     * </ul>
     * 
     * @param year the year (must be valid according to isValidYear)
     * @param month the month (1-12)
     * @param day the day to validate (1-31)
     * @return true if the day is valid for the given month and year, false otherwise
     */
    public static boolean isValidDay(int year, int month, int day) {
        // Basic range check - corresponds to COBOL WS-VALID-DAY (lines 187-200)
        if (day < MIN_VALID_DAY || day > MAX_VALID_DAY) {
            return false;
        }
        
        // Get the actual number of days in the month
        int daysInMonth = getDaysInMonth(year, month);
        
        // Validate day doesn't exceed days in month
        // Corresponds to COBOL EDIT-DAY-MONTH-YEAR logic (lines 209-282)
        return day <= daysInMonth;
    }
    
    /**
     * Checks if a date is in the future (after today).
     * 
     * <p>Compares the provided date against the current system date.
     * Used in date-of-birth validation and other scenarios where future dates
     * are not acceptable.
     * 
     * @param date the date to check, must not be null
     * @return true if the date is after the current date, false otherwise
     * @throws IllegalArgumentException if date is null
     */
    public static boolean isFutureDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        
        LocalDate currentDate = LocalDate.now();
        return date.isAfter(currentDate);
    }
    
    /**
     * Checks if a date is in the past (before today).
     * 
     * <p>Compares the provided date against the current system date.
     * A date equal to today is not considered in the past.
     * 
     * @param date the date to check, must not be null
     * @return true if the date is before the current date, false otherwise
     * @throws IllegalArgumentException if date is null
     */
    public static boolean isPastDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        
        LocalDate currentDate = LocalDate.now();
        return date.isBefore(currentDate);
    }
    
    /**
     * Validates a date of birth with reasonableness checks.
     * 
     * <p>Implements COBOL EDIT-DATE-OF-BIRTH paragraph logic (lines 341-372).
     * Performs the following validations:
     * <ul>
     *   <li>Date must be valid according to standard date validation rules</li>
     *   <li>Date cannot be in the future (time travel was not possible at time of writing)</li>
     * </ul>
     * 
     * <p>Corresponds to COBOL logic:
     * <pre>
     * "At the time of writing this program, time travel was not possible.
     * Date of birth in the future is not acceptable." (lines 336-339)
     * </pre>
     * 
     * <p>COBOL comparison logic (lines 345-368):
     * <pre>
     * COMPUTE WS-EDIT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (WS-EDIT-DATE-CCYYMMDD-N)
     * COMPUTE WS-CURRENT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (WS-CURRENT-DATE-YYYYMMDD-N)
     * IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY
     *    CONTINUE (valid)
     * ELSE
     *    SET INPUT-ERROR TO TRUE (error: "cannot be in the future")
     * </pre>
     * 
     * @param dateOfBirth the date of birth to validate, must not be null
     * @return true if the date of birth is valid and not in the future, false otherwise
     * @throws IllegalArgumentException if dateOfBirth is null
     */
    public static boolean validateDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth must not be null");
        }
        
        // First validate it's a valid date according to COBOL rules
        if (!isValidDate(dateOfBirth)) {
            return false;
        }
        
        // Date of birth cannot be in the future - corresponds to COBOL lines 350-368
        if (isFutureDate(dateOfBirth)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Determines if a year is a leap year according to COBOL leap year calculation logic.
     * 
     * <p>Implements COBOL leap year algorithm from EDIT-DAY-MONTH-YEAR paragraph (lines 243-272).
     * 
     * <p>COBOL logic (lines 245-256):
     * <pre>
     * IF WS-EDIT-DATE-YY-N = 0
     *    MOVE 400 TO WS-DIV-BY    (century years - year ends in 00)
     * ELSE
     *    MOVE 4 TO WS-DIV-BY      (non-century years)
     * END-IF
     * 
     * DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY
     *    GIVING WS-DIVIDEND
     *    REMAINDER WS-REMAINDER
     * 
     * IF WS-REMAINDER = ZEROES
     *    (leap year - February can have 29 days)
     * ELSE
     *    (not a leap year - February cannot have 29 days)
     * </pre>
     * 
     * <p>Leap year rules:
     * <ul>
     *   <li>If year is divisible by 400, it is a leap year (e.g., 2000)</li>
     *   <li>Else if year is divisible by 100, it is NOT a leap year (e.g., 1900)</li>
     *   <li>Else if year is divisible by 4, it is a leap year (e.g., 2004)</li>
     *   <li>Else it is NOT a leap year</li>
     * </ul>
     * 
     * @param year the year to check (4-digit format)
     * @return true if the year is a leap year, false otherwise
     */
    public static boolean isLeapYear(int year) {
        // Check if year ends in 00 (century year)
        // Corresponds to COBOL: IF WS-EDIT-DATE-YY-N = 0 (line 245)
        if (year % 100 == 0) {
            // Century years must be divisible by 400 to be leap years
            // Corresponds to COBOL: MOVE 400 TO WS-DIV-BY (line 246)
            return year % LEAP_YEAR_DIVISOR_400 == 0;
        } else {
            // Non-century years must be divisible by 4 to be leap years
            // Corresponds to COBOL: MOVE 4 TO WS-DIV-BY (line 248)
            return year % LEAP_YEAR_DIVISOR_4 == 0;
        }
    }
    
    /**
     * Returns the number of days in a given month for a given year.
     * 
     * <p>Implements month-specific day count logic from COBOL EDIT-DAY-MONTH-YEAR paragraph.
     * Handles:
     * <ul>
     *   <li>31-day months: January, March, May, July, August, October, December</li>
     *   <li>30-day months: April, June, September, November</li>
     *   <li>February: 28 days (non-leap year) or 29 days (leap year)</li>
     * </ul>
     * 
     * <p>Corresponds to COBOL 88-level conditions and validation logic:
     * <ul>
     *   <li>WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12 (line 213)</li>
     *   <li>WS-FEBRUARY VALUE 2 (line 228)</li>
     *   <li>Leap year calculation for February 29 (lines 243-272)</li>
     * </ul>
     * 
     * <p>Uses java.time.Month enum for month-specific logic:
     * <ul>
     *   <li>Month.of(int) to get Month enum value</li>
     *   <li>Month.length(boolean) to get days in month considering leap year</li>
     * </ul>
     * 
     * @param year the year (used for leap year calculation for February)
     * @param month the month (1-12, where 1=January, 12=December)
     * @return the number of days in the month (28, 29, 30, or 31)
     * @throws IllegalArgumentException if month is not in range 1-12
     */
    public static int getDaysInMonth(int year, int month) {
        if (month < MIN_VALID_MONTH || month > MAX_VALID_MONTH) {
            throw new IllegalArgumentException("Month must be between 1 and 12");
        }
        
        // Use Java 8+ Month enum for month-specific day count
        // Month.of() converts 1-12 to Month enum (JANUARY-DECEMBER)
        // Month.length(boolean) returns days in month (handles leap year for February)
        Month monthEnum = Month.of(month);
        boolean leapYear = isLeapYear(year);
        
        // Returns appropriate day count:
        // - January, March, May, July, August, October, December: 31 days
        // - April, June, September, November: 30 days
        // - February: 28 days (non-leap) or 29 days (leap year)
        return monthEnum.length(leapYear);
    }
}
