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
package com.carddemo.util;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Business date utility service providing comprehensive date calculation logic including
 * CEEDAYS Lillian date format conversion, business day calculations, and statement cycle
 * date operations.
 * 
 * <p>This service transforms COBOL date utility programs:
 * <ul>
 *   <li>CSUTLDTC.cbl - CEEDAYS API calls for Lillian date conversion (lines 116-120)</li>
 *   <li>CSUTLDPY.cpy - Date validation procedures for conversion eligibility (lines 18-372)</li>
 * </ul>
 * 
 * <p><strong>Lillian Date Format</strong>: Integer day count since October 15, 1582
 * (Gregorian calendar adoption). COBOL CEEDAYS function OUTPUT-LILLIAN is PIC S9(9) BINARY
 * representing days in Lillian format. This service provides mathematically equivalent
 * conversion methods per Section 0.1 requirements.</p>
 * 
 * <p><strong>Business Date Calculations</strong>: Methods for adding/subtracting business
 * days (excluding weekends) used by batch jobs for interest calculation (CBACT04C),
 * statement generation (CBSTM03A), and account aging operations.</p>
 * 
 * <p><strong>Statement Cycle Dates</strong>: Calculates monthly statement generation
 * dates matching COBOL statement batch job (CBSTM03A) date calculation patterns.</p>
 * 
 * <p>All date operations maintain exact equivalence with COBOL date arithmetic per
 * Section 0.9 requirements. CEEDAYS error conditions (FC-INVALID-DATE, FC-BAD-DATE-VALUE,
 * FC-INVALID-MONTH) are handled through Java exceptions with descriptive error messages.</p>
 * 
 * @see DateUtils for core date operations and Lillian epoch constant
 * @see DateValidator for comprehensive date validation matching COBOL CSUTLDPY logic
 * @see DateConverter for date format conversions
 * @version CardDemo_v1.0 COBOL-to-Java Migration
 */
@Service
public class DateUtilityService {

    private static final Logger logger = LoggerFactory.getLogger(DateUtilityService.class);

    /**
     * Lillian date epoch: October 15, 1582 (day 1 in Lillian date system).
     * Matches COBOL CEEDAYS function base date.
     */
    private static final LocalDate LILLIAN_EPOCH = LocalDate.of(1582, 10, 15);

    /**
     * Default statement cycle day (e.g., 1st of month for monthly statements).
     * Used when specific cycle day is not provided.
     */
    private static final int DEFAULT_STATEMENT_CYCLE_DAY = 1;

    /**
     * Converts Lillian date (integer day count) to Java LocalDate.
     * 
     * <p>Implements COBOL CEEDAYS reverse operation equivalent to:
     * <pre>
     * COMPUTE YYYYMMDD = FUNCTION DATE-OF-INTEGER(LILLIAN-DATE)
     * </pre>
     * 
     * <p>This method provides mathematical equivalence with COBOL CEEDAYS API call from
     * CSUTLDTC.cbl (lines 116-120). The Lillian date system uses October 15, 1582 as day 1,
     * matching the adoption of the Gregorian calendar.</p>
     * 
     * <p><strong>COBOL Source Mapping</strong>:
     * <ul>
     *   <li>CSUTLDTC.cbl line 41: OUTPUT-LILLIAN PIC S9(9) BINARY → lillianDate parameter</li>
     *   <li>CSUTLDTC.cbl lines 116-120: CALL "CEEDAYS" → conversion logic</li>
     *   <li>CSUTLDTC.cbl lines 62-70: FEEDBACK-CODE conditions → exception mapping</li>
     * </ul>
     * 
     * <p><strong>Example Conversions</strong>:
     * <ul>
     *   <li>lillianDate = 1 → October 15, 1582 (epoch)</li>
     *   <li>lillianDate = 152385 → January 1, 2000</li>
     *   <li>lillianDate = 161542 → March 15, 2023</li>
     * </ul>
     * 
     * <p><strong>Error Handling</strong>: Throws IllegalArgumentException for invalid
     * Lillian dates, equivalent to COBOL CEEDAYS feedback codes:
     * <ul>
     *   <li>FC-BAD-DATE-VALUE (line 64) - Lillian date &lt; 1</li>
     *   <li>FC-UNSUPP-RANGE (line 66) - Lillian date exceeds supported range</li>
     * </ul>
     * 
     * @param lillianDate the Lillian date integer (days since October 15, 1582), must be &gt;= 1
     * @return LocalDate representing the converted date
     * @throws IllegalArgumentException if lillianDate &lt; 1 (before Lillian epoch)
     */
    public LocalDate convertFromLillianDate(int lillianDate) {
        logger.debug("Converting Lillian date to LocalDate: {}", lillianDate);
        
        if (lillianDate < 1) {
            String errorMsg = String.format(
                    "Invalid Lillian date: %d. Lillian dates must be >= 1 (October 15, 1582). " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-BAD-DATE-VALUE.", 
                    lillianDate);
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        try {
            // Lillian date 1 = October 15, 1582, so we add (lillianDate - 1) days to epoch
            // This maintains mathematical equivalence with COBOL CEEDAYS conversion
            LocalDate resultDate = LILLIAN_EPOCH.plusDays((long) lillianDate - 1);
            
            logger.debug("Lillian date {} converted to LocalDate: {}", lillianDate, resultDate);
            return resultDate;
            
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Error converting Lillian date %d to LocalDate. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-UNSUPP-RANGE. Cause: %s",
                    lillianDate, e.getMessage());
            logger.error(errorMsg, e);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }

    /**
     * Converts Java LocalDate to Lillian date (integer day count).
     * 
     * <p>Implements COBOL CEEDAYS operation equivalent to:
     * <pre>
     * CALL "CEEDAYS" USING WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN, FEEDBACK-CODE
     * </pre>
     * 
     * <p>This method provides mathematical equivalence with COBOL CEEDAYS API call from
     * CSUTLDTC.cbl (lines 116-120). The conversion calculates the number of days between
     * the Lillian epoch (October 15, 1582) and the provided date.</p>
     * 
     * <p><strong>COBOL Source Mapping</strong>:
     * <ul>
     *   <li>CSUTLDTC.cbl line 24-31: WS-DATE-TO-TEST → date parameter</li>
     *   <li>CSUTLDTC.cbl line 41: OUTPUT-LILLIAN PIC S9(9) BINARY → return value</li>
     *   <li>CSUTLDTC.cbl lines 116-120: CALL "CEEDAYS" → conversion logic</li>
     *   <li>CSUTLDTC.cbl lines 128-149: EVALUATE feedback codes → error handling</li>
     * </ul>
     * 
     * <p><strong>Example Conversions</strong>:
     * <ul>
     *   <li>October 15, 1582 → lillianDate = 1 (epoch)</li>
     *   <li>January 1, 2000 → lillianDate = 152385</li>
     *   <li>March 15, 2023 → lillianDate = 161542</li>
     * </ul>
     * 
     * <p><strong>Validation</strong>: Date is validated using DateValidator before
     * conversion to ensure it meets COBOL CSUTLDPY validation requirements (century
     * restrictions, valid month/day combinations, leap year handling).</p>
     * 
     * <p><strong>Error Handling</strong>: Throws IllegalArgumentException for invalid
     * dates, equivalent to COBOL CEEDAYS feedback codes:
     * <ul>
     *   <li>FC-INVALID-DATE (line 62) - Null date</li>
     *   <li>FC-BAD-DATE-VALUE (line 64) - Date before Lillian epoch</li>
     *   <li>FC-INVALID-MONTH (line 67) - Invalid month value (not 1-12)</li>
     * </ul>
     * 
     * @param date the LocalDate to convert, must not be null
     * @return int representing the Lillian date (days since October 15, 1582)
     * @throws IllegalArgumentException if date is null, invalid per COBOL rules, or before Lillian epoch
     */
    public int convertToLillianDate(LocalDate date) {
        logger.debug("Converting LocalDate to Lillian date: {}", date);
        
        // Validate date is not null - equivalent to FC-INVALID-DATE
        if (date == null) {
            String errorMsg = "Date cannot be null for Lillian conversion. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-INVALID-DATE.";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // Validate date against COBOL validation rules before conversion
        validateDateForConversion(date);
        
        // Check date is not before Lillian epoch - equivalent to FC-BAD-DATE-VALUE
        if (DateUtils.isBefore(date, LILLIAN_EPOCH)) {
            String errorMsg = String.format(
                    "Date %s is before Lillian epoch (October 15, 1582). " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-BAD-DATE-VALUE.",
                    DateUtils.formatCCYYMMDD(date));
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        try {
            // Calculate days between epoch and provided date, then add 1 (epoch is day 1)
            // This maintains mathematical equivalence with COBOL CEEDAYS OUTPUT-LILLIAN
            long daysSinceEpoch = ChronoUnit.DAYS.between(LILLIAN_EPOCH, date);
            int lillianDate = (int) (daysSinceEpoch + 1);
            
            logger.debug("LocalDate {} converted to Lillian date: {}", date, lillianDate);
            return lillianDate;
            
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Error converting LocalDate %s to Lillian date. Cause: %s",
                    DateUtils.formatCCYYMMDD(date), e.getMessage());
            logger.error(errorMsg, e);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }

    /**
     * Adds specified number of business days to a date, skipping weekends.
     * 
     * <p>Business day is defined as Monday through Friday (excluding Saturday and Sunday).
     * This method is used by batch jobs for calculating due dates, interest accrual periods,
     * and payment processing dates.</p>
     * 
     * <p><strong>Business Logic</strong>:
     * <ul>
     *   <li>If businessDays &gt; 0: Move forward, skipping weekends</li>
     *   <li>If businessDays = 0: Return the same date</li>
     *   <li>If businessDays &lt; 0: Use subtractBusinessDays for backward calculation</li>
     *   <li>If start date is weekend, first adjust to next business day</li>
     * </ul>
     * 
     * <p><strong>Example Calculations</strong>:
     * <ul>
     *   <li>Friday + 1 business day = Monday (skips weekend)</li>
     *   <li>Thursday + 3 business days = Tuesday next week</li>
     *   <li>Saturday + 1 business day = Tuesday (adjusts Saturday to Monday, then adds 1)</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>CBACT04C - Interest calculation batch job (interest accrual period)</li>
     *   <li>CBTRN02C - Daily transaction processing (transaction effective date)</li>
     *   <li>COBIL00C - Bill payment service (payment due date calculation)</li>
     * </ul>
     * 
     * @param startDate the base date, must not be null
     * @param businessDays the number of business days to add (can be 0 or positive)
     * @return LocalDate representing the result after adding business days
     * @throws IllegalArgumentException if startDate is null or businessDays is negative
     */
    public LocalDate addBusinessDays(LocalDate startDate, int businessDays) {
        logger.debug("Adding {} business days to date: {}", businessDays, startDate);
        
        if (startDate == null) {
            throw new IllegalArgumentException("Start date cannot be null");
        }
        
        if (businessDays < 0) {
            throw new IllegalArgumentException(
                    "Business days to add must be >= 0. Use subtractBusinessDays() for negative values.");
        }
        
        if (businessDays == 0) {
            return startDate;
        }
        
        LocalDate currentDate = startDate;
        
        // If start date is weekend, adjust to next Monday first
        if (!isBusinessDay(currentDate)) {
            currentDate = currentDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        }
        
        // Add business days, skipping weekends
        int daysAdded = 0;
        while (daysAdded < businessDays) {
            currentDate = DateUtils.addDays(currentDate, 1);
            
            // Only count business days (Monday-Friday)
            if (isBusinessDay(currentDate)) {
                daysAdded++;
            }
        }
        
        logger.debug("Result after adding {} business days: {}", businessDays, currentDate);
        return currentDate;
    }

    /**
     * Subtracts specified number of business days from a date, skipping weekends.
     * 
     * <p>Business day is defined as Monday through Friday (excluding Saturday and Sunday).
     * This method is used by batch jobs for calculating lookback periods, aging analysis,
     * and historical date calculations.</p>
     * 
     * <p><strong>Business Logic</strong>:
     * <ul>
     *   <li>If businessDays &gt; 0: Move backward, skipping weekends</li>
     *   <li>If businessDays = 0: Return the same date</li>
     *   <li>If businessDays &lt; 0: Invalid (must be positive)</li>
     *   <li>If start date is weekend, first adjust to previous business day</li>
     * </ul>
     * 
     * <p><strong>Example Calculations</strong>:
     * <ul>
     *   <li>Monday - 1 business day = Friday (skips weekend)</li>
     *   <li>Tuesday - 3 business days = Thursday previous week</li>
     *   <li>Sunday - 1 business day = Thursday (adjusts Sunday to Friday, then subtracts 1)</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>CBACT03C - Account balance calculation (lookback period)</li>
     *   <li>COACTVWC - Account view service (aging analysis)</li>
     *   <li>CBSTM03A - Statement generation (billing cycle start date)</li>
     * </ul>
     * 
     * @param startDate the base date, must not be null
     * @param businessDays the number of business days to subtract (must be positive)
     * @return LocalDate representing the result after subtracting business days
     * @throws IllegalArgumentException if startDate is null or businessDays is negative
     */
    public LocalDate subtractBusinessDays(LocalDate startDate, int businessDays) {
        logger.debug("Subtracting {} business days from date: {}", businessDays, startDate);
        
        if (startDate == null) {
            throw new IllegalArgumentException("Start date cannot be null");
        }
        
        if (businessDays < 0) {
            throw new IllegalArgumentException(
                    "Business days to subtract must be >= 0. Use addBusinessDays() for negative values.");
        }
        
        if (businessDays == 0) {
            return startDate;
        }
        
        LocalDate currentDate = startDate;
        
        // If start date is weekend, adjust to previous Friday first
        if (!isBusinessDay(currentDate)) {
            currentDate = currentDate.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
        }
        
        // Subtract business days, skipping weekends
        int daysSubtracted = 0;
        while (daysSubtracted < businessDays) {
            currentDate = DateUtils.subtractDays(currentDate, 1);
            
            // Only count business days (Monday-Friday)
            if (isBusinessDay(currentDate)) {
                daysSubtracted++;
            }
        }
        
        logger.debug("Result after subtracting {} business days: {}", businessDays, currentDate);
        return currentDate;
    }

    /**
     * Calculates the number of calendar days between two dates (inclusive of start, exclusive of end).
     * 
     * <p>This method calculates the total number of calendar days (including weekends and
     * holidays) between two dates. The calculation is inclusive of the start date and
     * exclusive of the end date, matching Java ChronoUnit.DAYS.between behavior.</p>
     * 
     * <p><strong>Calculation Method</strong>:
     * <ul>
     *   <li>If endDate = startDate: Returns 0 days</li>
     *   <li>If endDate &gt; startDate: Returns positive day count</li>
     *   <li>If endDate &lt; startDate: Returns negative day count</li>
     * </ul>
     * 
     * <p><strong>Example Calculations</strong>:
     * <ul>
     *   <li>January 1, 2023 to January 5, 2023 = 4 days</li>
     *   <li>January 1, 2023 to January 1, 2023 = 0 days</li>
     *   <li>January 5, 2023 to January 1, 2023 = -4 days</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>CBACT04C - Interest calculation batch (days in interest period)</li>
     *   <li>CBSTM03A - Statement generation (billing cycle days)</li>
     *   <li>COACTVWC - Account aging calculation (days past due)</li>
     * </ul>
     * 
     * @param startDate the start date (inclusive), must not be null
     * @param endDate the end date (exclusive), must not be null
     * @return long representing the number of days between dates (can be negative)
     * @throws IllegalArgumentException if either date is null
     */
    public long calculateDaysBetween(LocalDate startDate, LocalDate endDate) {
        logger.debug("Calculating days between start: {} and end: {}", startDate, endDate);
        
        if (startDate == null) {
            throw new IllegalArgumentException("Start date cannot be null");
        }
        
        if (endDate == null) {
            throw new IllegalArgumentException("End date cannot be null");
        }
        
        // Calculate days using ChronoUnit (inclusive start, exclusive end)
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);
        
        logger.debug("Days between {} and {}: {}", startDate, endDate, daysBetween);
        return daysBetween;
    }

    /**
     * Calculates the next statement generation date based on monthly cycle.
     * 
     * <p>This method calculates the next statement date for monthly billing cycles.
     * If the current date is before the cycle day in the current month, returns the
     * cycle day in the current month. Otherwise, returns the cycle day in the next month.</p>
     * 
     * <p><strong>Statement Cycle Logic</strong>:
     * <ul>
     *   <li>Statement cycle day defaults to 1st of each month</li>
     *   <li>If current day &lt; cycle day: Return cycle day in current month</li>
     *   <li>If current day &gt;= cycle day: Return cycle day in next month</li>
     *   <li>Handles month-end scenarios (e.g., cycle day 31 in months with 30 days)</li>
     * </ul>
     * 
     * <p><strong>Example Calculations</strong> (cycle day = 1):
     * <ul>
     *   <li>January 15, 2023 → February 1, 2023</li>
     *   <li>December 31, 2023 → January 1, 2024</li>
     *   <li>January 1, 2023 → February 1, 2023</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>CBSTM03A - Statement generation batch job</li>
     *   <li>COBIL00C - Bill payment processing (next due date)</li>
     *   <li>COACTVWC - Account view (next statement date display)</li>
     * </ul>
     * 
     * @param currentDate the current date, must not be null
     * @return LocalDate representing the next statement generation date
     * @throws IllegalArgumentException if currentDate is null
     */
    public LocalDate getNextStatementDate(LocalDate currentDate) {
        return getNextStatementDate(currentDate, DEFAULT_STATEMENT_CYCLE_DAY);
    }

    /**
     * Calculates the next statement generation date with specified cycle day.
     * 
     * <p>This method calculates the next statement date for monthly billing cycles with
     * a configurable cycle day. Supports cycle days from 1-31.</p>
     * 
     * <p><strong>Month-End Handling</strong>:
     * <ul>
     *   <li>If cycle day exceeds days in target month, uses last day of month</li>
     *   <li>Example: cycle day 31 in February → February 28 (or 29 in leap year)</li>
     * </ul>
     * 
     * @param currentDate the current date, must not be null
     * @param cycleDay the day of month for statement generation (1-31)
     * @return LocalDate representing the next statement generation date
     * @throws IllegalArgumentException if currentDate is null or cycleDay is invalid
     */
    public LocalDate getNextStatementDate(LocalDate currentDate, int cycleDay) {
        logger.debug("Calculating next statement date from: {} with cycle day: {}", currentDate, cycleDay);
        
        if (currentDate == null) {
            throw new IllegalArgumentException("Current date cannot be null");
        }
        
        if (cycleDay < 1 || cycleDay > 31) {
            throw new IllegalArgumentException("Cycle day must be between 1 and 31");
        }
        
        // Get cycle date in current month
        LocalDate currentMonthCycleDate = getStatementCycleDate(currentDate, cycleDay);
        
        // If current date is before cycle date in current month, return it
        if (DateUtils.isBefore(currentDate, currentMonthCycleDate)) {
            logger.debug("Next statement date (current month): {}", currentMonthCycleDate);
            return currentMonthCycleDate;
        }
        
        // Otherwise, return cycle date in next month
        LocalDate nextMonth = DateUtils.addMonths(currentDate, 1);
        LocalDate nextMonthCycleDate = getStatementCycleDate(nextMonth, cycleDay);
        
        logger.debug("Next statement date (next month): {}", nextMonthCycleDate);
        return nextMonthCycleDate;
    }

    /**
     * Calculates the previous statement generation date based on monthly cycle.
     * 
     * <p>This method calculates the previous statement date for monthly billing cycles.
     * If the current date is after the cycle day in the current month, returns the
     * cycle day in the current month. Otherwise, returns the cycle day in the previous month.</p>
     * 
     * <p><strong>Statement Cycle Logic</strong>:
     * <ul>
     *   <li>Statement cycle day defaults to 1st of each month</li>
     *   <li>If current day &gt; cycle day: Return cycle day in current month</li>
     *   <li>If current day &lt;= cycle day: Return cycle day in previous month</li>
     *   <li>Handles month-end scenarios (e.g., cycle day 31 in months with 30 days)</li>
     * </ul>
     * 
     * <p><strong>Example Calculations</strong> (cycle day = 1):
     * <ul>
     *   <li>January 15, 2023 → January 1, 2023</li>
     *   <li>January 1, 2023 → December 1, 2022</li>
     *   <li>March 1, 2024 → February 1, 2024</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>CBSTM03A - Statement generation batch job (previous billing period)</li>
     *   <li>CBACT03C - Account balance calculation (previous statement balance)</li>
     *   <li>COACTVWC - Account view (last statement date display)</li>
     * </ul>
     * 
     * @param currentDate the current date, must not be null
     * @return LocalDate representing the previous statement generation date
     * @throws IllegalArgumentException if currentDate is null
     */
    public LocalDate getPreviousStatementDate(LocalDate currentDate) {
        return getPreviousStatementDate(currentDate, DEFAULT_STATEMENT_CYCLE_DAY);
    }

    /**
     * Calculates the previous statement generation date with specified cycle day.
     * 
     * <p>This method calculates the previous statement date for monthly billing cycles with
     * a configurable cycle day. Supports cycle days from 1-31.</p>
     * 
     * <p><strong>Month-End Handling</strong>:
     * <ul>
     *   <li>If cycle day exceeds days in target month, uses last day of month</li>
     *   <li>Example: cycle day 31 in February → February 28 (or 29 in leap year)</li>
     * </ul>
     * 
     * @param currentDate the current date, must not be null
     * @param cycleDay the day of month for statement generation (1-31)
     * @return LocalDate representing the previous statement generation date
     * @throws IllegalArgumentException if currentDate is null or cycleDay is invalid
     */
    public LocalDate getPreviousStatementDate(LocalDate currentDate, int cycleDay) {
        logger.debug("Calculating previous statement date from: {} with cycle day: {}", currentDate, cycleDay);
        
        if (currentDate == null) {
            throw new IllegalArgumentException("Current date cannot be null");
        }
        
        if (cycleDay < 1 || cycleDay > 31) {
            throw new IllegalArgumentException("Cycle day must be between 1 and 31");
        }
        
        // Get cycle date in current month
        LocalDate currentMonthCycleDate = getStatementCycleDate(currentDate, cycleDay);
        
        // If current date is after cycle date in current month, return it
        if (DateUtils.isAfter(currentDate, currentMonthCycleDate)) {
            logger.debug("Previous statement date (current month): {}", currentMonthCycleDate);
            return currentMonthCycleDate;
        }
        
        // Otherwise, return cycle date in previous month
        LocalDate previousMonth = DateUtils.subtractMonths(currentDate, 1);
        LocalDate previousMonthCycleDate = getStatementCycleDate(previousMonth, cycleDay);
        
        logger.debug("Previous statement date (previous month): {}", previousMonthCycleDate);
        return previousMonthCycleDate;
    }

    /**
     * Gets the statement cycle date for the month containing the specified date.
     * 
     * <p>This method returns the specific cycle day within the month of the provided date.
     * Handles month-end scenarios where the cycle day may exceed the number of days in the month.</p>
     * 
     * <p><strong>Month-End Handling Examples</strong>:
     * <ul>
     *   <li>Cycle day 31 in February 2023 → February 28, 2023</li>
     *   <li>Cycle day 31 in February 2024 (leap year) → February 29, 2024</li>
     *   <li>Cycle day 31 in April 2023 → April 30, 2023</li>
     *   <li>Cycle day 15 in any month → [Month] 15, [Year]</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>getNextStatementDate() - Calculate next billing cycle</li>
     *   <li>getPreviousStatementDate() - Calculate previous billing cycle</li>
     *   <li>CBSTM03A - Statement generation batch job</li>
     * </ul>
     * 
     * @param referenceDate the date within the target month, must not be null
     * @param cycleDay the day of month for statement generation (1-31)
     * @return LocalDate representing the statement cycle date in the reference month
     * @throws IllegalArgumentException if referenceDate is null or cycleDay is invalid (not 1-31)
     */
    public LocalDate getStatementCycleDate(LocalDate referenceDate, int cycleDay) {
        logger.debug("Getting statement cycle date for reference: {} with cycle day: {}", 
                referenceDate, cycleDay);
        
        if (referenceDate == null) {
            throw new IllegalArgumentException("Reference date cannot be null");
        }
        
        if (cycleDay < 1 || cycleDay > 31) {
            throw new IllegalArgumentException("Cycle day must be between 1 and 31");
        }
        
        // Get first day of month from reference date
        LocalDate firstDayOfMonth = referenceDate.with(TemporalAdjusters.firstDayOfMonth());
        
        // Get last day of month to handle month-end scenarios
        LocalDate lastDayOfMonth = referenceDate.with(TemporalAdjusters.lastDayOfMonth());
        int daysInMonth = lastDayOfMonth.getDayOfMonth();
        
        // If cycle day exceeds days in month, use last day of month
        int effectiveCycleDay = Math.min(cycleDay, daysInMonth);
        
        // Calculate the cycle date
        LocalDate cycleDate = firstDayOfMonth.withDayOfMonth(effectiveCycleDay);
        
        logger.debug("Statement cycle date: {} (requested cycle day: {}, effective: {})",
                cycleDate, cycleDay, effectiveCycleDay);
        
        return cycleDate;
    }

    /**
     * Checks if a date is a business day (Monday through Friday).
     * 
     * <p>Business days are defined as Monday through Friday. Saturday and Sunday are
     * not considered business days. This method does not account for holidays.</p>
     * 
     * <p><strong>Day of Week Classification</strong>:
     * <ul>
     *   <li>MONDAY through FRIDAY → true (business day)</li>
     *   <li>SATURDAY, SUNDAY → false (weekend)</li>
     * </ul>
     * 
     * <p><strong>Used By</strong>:
     * <ul>
     *   <li>addBusinessDays() - Skip weekends when adding business days</li>
     *   <li>subtractBusinessDays() - Skip weekends when subtracting business days</li>
     *   <li>CBTRN02C - Daily transaction processing (validate business day)</li>
     *   <li>COBIL00C - Bill payment processing (business day validation)</li>
     * </ul>
     * 
     * @param date the date to check, must not be null
     * @return true if the date is Monday through Friday, false if Saturday or Sunday
     * @throws IllegalArgumentException if date is null
     */
    public boolean isBusinessDay(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        
        // Business days are Monday through Friday (exclude Saturday and Sunday)
        boolean isBusinessDay = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
        
        logger.debug("Date {} is business day: {} ({})", date, isBusinessDay, dayOfWeek);
        return isBusinessDay;
    }

    /**
     * Validates a date for Lillian conversion eligibility per COBOL CSUTLDPY validation rules.
     * 
     * <p>This method applies comprehensive COBOL date validation logic from CSUTLDPY.cpy
     * EDIT-DATE-CCYYMMDD paragraph (lines 18-331) before allowing Lillian date conversion.
     * Ensures the date meets all COBOL validation requirements including century restrictions,
     * month/day validation, and leap year handling.</p>
     * 
     * <p><strong>Validation Rules Applied</strong>:
     * <ul>
     *   <li>EDIT-YEAR-CCYY (lines 25-88): Century must be 19 or 20 (1900-2099)</li>
     *   <li>EDIT-MONTH (lines 91-147): Month must be 1-12</li>
     *   <li>EDIT-DAY (lines 150-207): Day must be valid for month</li>
     *   <li>EDIT-DAY-MONTH-YEAR (lines 209-282): Month-day combinations, leap year</li>
     * </ul>
     * 
     * <p><strong>Equivalent to COBOL</strong>:
     * <pre>
     * PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT
     * IF WS-EDIT-DATE-IS-VALID
     *    (proceed with CEEDAYS conversion)
     * ELSE
     *    (error handling)
     * </pre>
     * 
     * <p><strong>Error Conditions Map to CEEDAYS Feedback Codes</strong>:
     * <ul>
     *   <li>Invalid year (not 1900-2099) → FC-BAD-DATE-VALUE</li>
     *   <li>Invalid month (not 1-12) → FC-INVALID-MONTH</li>
     *   <li>Invalid day for month → FC-BAD-DATE-VALUE</li>
     *   <li>Invalid leap year date → FC-BAD-DATE-VALUE</li>
     * </ul>
     * 
     * @param date the date to validate, must not be null
     * @throws IllegalArgumentException if date is null or fails COBOL validation rules
     */
    public void validateDateForConversion(LocalDate date) {
        logger.debug("Validating date for Lillian conversion: {}", date);
        
        if (date == null) {
            String errorMsg = "Date cannot be null for validation. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-INVALID-DATE.";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // Apply comprehensive COBOL CSUTLDPY validation
        if (!DateValidator.isValidDate(date)) {
            String errorMsg = String.format(
                    "Date %s failed COBOL validation rules from CSUTLDPY EDIT-DATE-CCYYMMDD. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-BAD-DATE-VALUE.",
                    DateUtils.formatCCYYMMDD(date));
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // Additional century restriction check (19xx or 20xx only per COBOL lines 66-84)
        int year = DateUtils.getYear(date);
        if (!DateValidator.isValidYear(year)) {
            String errorMsg = String.format(
                    "Date %s has invalid century. COBOL CSUTLDPY restricts to 19xx and 20xx only. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-BAD-DATE-VALUE.",
                    DateUtils.formatCCYYMMDD(date));
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // Validate month range per COBOL WS-VALID-MONTH (line 111)
        int month = DateUtils.getMonth(date);
        if (!DateValidator.isValidMonth(month)) {
            String errorMsg = String.format(
                    "Date %s has invalid month (%d). Month must be 1-12 per COBOL CSUTLDPY EDIT-MONTH. " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-INVALID-MONTH.",
                    DateUtils.formatCCYYMMDD(date), month);
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // Validate day for month and year per COBOL EDIT-DAY-MONTH-YEAR (lines 209-282)
        int day = DateUtils.getDay(date);
        if (!DateValidator.isValidDay(year, month, day)) {
            String errorMsg = String.format(
                    "Date %s has invalid day (%d) for month %d and year %d. " +
                    "Per COBOL CSUTLDPY EDIT-DAY-MONTH-YEAR validation (including leap year logic). " +
                    "Equivalent to COBOL CEEDAYS feedback code FC-BAD-DATE-VALUE.",
                    DateUtils.formatCCYYMMDD(date), day, month, year);
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        logger.debug("Date {} passed all COBOL validation rules for Lillian conversion", date);
    }
}
