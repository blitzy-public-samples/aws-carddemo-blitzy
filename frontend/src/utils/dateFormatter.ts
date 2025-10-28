/**
 * Date manipulation and formatting utility functions
 * 
 * Converted from COBOL CSUTLDTC.cbl date utility program and CSDAT01Y.cpy
 * date formatting copybook per Agent Action Plan Section 0.4.21.
 * 
 * Original COBOL functionality:
 * - CSUTLDTC.cbl: Uses IBM CEEDAYS API to convert dates to Lilian format
 *   (days since October 15, 1582) and validate date strings
 * - CSDAT01Y.cpy: Defines date format structures (WS-CURDATE-MM-DD-YY,
 *   WS-TIMESTAMP) for display and API communication
 * 
 * TypeScript implementation:
 * - Uses date-fns library for robust date handling
 * - Preserves COBOL date validation logic (FC-INVALID-DATE,
 *   FC-INSUFFICIENT-DATA, FC-BAD-DATE-VALUE, FC-INVALID-MONTH,
 *   FC-UNSUPP-RANGE checks)
 * - Maintains compatibility with backend YYYY-MM-DD API format from
 *   Java LocalDate
 * 
 * All functions are pure (no side effects), handle edge cases (leap years,
 * invalid dates, timezone issues, null/undefined inputs), and maintain
 * date precision to day level (no time component) matching COBOL behavior.
 * 
 * Per Section 0.7.5: Preserves COBOL date calculation semantics while
 * using modern JavaScript date library for reliability.
 */

import {
  format,
  parse,
  addDays as addDaysFns,
  subDays,
  differenceInDays,
  isValid as isValidFns,
} from 'date-fns';
import { DATE_FORMATS } from './constants';

/**
 * Formats Date object to MM/DD/YYYY display string
 * 
 * COBOL equivalent: WS-CURDATE-MM-DD-YY from CSDAT01Y.cpy
 * Original format: MM/DD/YY (2-digit year)
 * Enhanced to: MM/DD/YYYY (4-digit year for Y2K compliance)
 * 
 * @param date - Date object to format
 * @returns Formatted string 'MM/DD/YYYY' or empty string if invalid
 * 
 * @example
 * ```typescript
 * formatDate(new Date('2024-03-15')); // Returns '03/15/2024'
 * formatDate(null); // Returns ''
 * formatDate(new Date('invalid')); // Returns ''
 * ```
 */
export const formatDate = (date: Date | null | undefined): string => {
  if (!date || !isValidFns(date)) {
    return '';
  }
  return format(date, DATE_FORMATS.DISPLAY); // 'MM/DD/YYYY'
};

/**
 * Formats Date object to YYYY-MM-DD API string
 * 
 * COBOL equivalent: WS-TIMESTAMP date portion from CSDAT01Y.cpy
 * Original format: YYYY-MM-DD (PIC X(10) in COBOL)
 * Matches Java LocalDate format from backend REST API
 * 
 * Per Section 0.7.4: Maintain COBOL date format (YYYY-MM-DD as PIC X(10))
 * in external interfaces
 * 
 * @param date - Date object to format
 * @returns Formatted string 'YYYY-MM-DD' or empty string if invalid
 * 
 * @example
 * ```typescript
 * formatDateForApi(new Date('2024-03-15')); // Returns '2024-03-15'
 * formatDateForApi(null); // Returns ''
 * ```
 */
export const formatDateForApi = (date: Date | null | undefined): string => {
  if (!date || !isValidFns(date)) {
    return '';
  }
  return format(date, DATE_FORMATS.API); // 'YYYY-MM-DD'
};

/**
 * Formats Date object to full timestamp string
 * 
 * COBOL equivalent: WS-TIMESTAMP from CSDAT01Y.cpy
 * Original format: YYYY-MM-DD HH:MM:SS.MMMMMM
 * Simplified to: YYYY-MM-DD HH:mm:ss (without microseconds)
 * 
 * @param date - Date object to format
 * @returns Formatted string 'YYYY-MM-DD HH:mm:ss' or empty string if invalid
 * 
 * @example
 * ```typescript
 * formatDateTime(new Date('2024-03-15T14:30:45')); // Returns '2024-03-15 14:30:45'
 * formatDateTime(null); // Returns ''
 * ```
 */
export const formatDateTime = (date: Date | null | undefined): string => {
  if (!date || !isValidFns(date)) {
    return '';
  }
  return format(date, DATE_FORMATS.TIMESTAMP); // 'YYYY-MM-DD HH:mm:ss'
};

/**
 * Parses date string to Date object
 * 
 * COBOL equivalent: CALL 'CEEDAYS' USING WS-DATE-TO-TEST, WS-DATE-FORMAT
 * from CSUTLDTC.cbl
 * 
 * Original COBOL logic:
 * - Accepts date string and format string
 * - Uses IBM CEEDAYS API to validate and convert to Lilian date
 * - Returns feedback code indicating success/failure
 * 
 * TypeScript implementation:
 * - Uses date-fns parse() for flexible date parsing
 * - Supports both display format (MM/DD/YYYY) and API format (YYYY-MM-DD)
 * - Returns null for invalid dates (matches COBOL FC-INVALID-DATE)
 * 
 * @param dateString - String in 'MM/DD/YYYY' or 'YYYY-MM-DD' format
 * @param formatPattern - Optional format pattern (defaults to DISPLAY format)
 * @returns Date object or null if invalid
 * 
 * @example
 * ```typescript
 * parseDate('03/15/2024'); // Returns Date object for March 15, 2024
 * parseDate('2024-03-15', DATE_FORMATS.API); // Returns Date object
 * parseDate('invalid'); // Returns null
 * parseDate(null); // Returns null
 * ```
 */
export const parseDate = (
  dateString: string | null | undefined,
  formatPattern: string = DATE_FORMATS.DISPLAY
): Date | null => {
  if (!dateString || dateString.trim() === '') {
    return null; // FC-INSUFFICIENT-DATA equivalent
  }

  try {
    const referenceDate = new Date();
    const parsed = parse(dateString, formatPattern, referenceDate);
    
    if (!isValidFns(parsed)) {
      return null; // FC-BAD-DATE-VALUE equivalent
    }

    return parsed;
  } catch (error) {
    return null; // FC-INVALID-DATE equivalent
  }
};

/**
 * Validates date string matching COBOL CSUTLDTC validation logic
 * 
 * COBOL validation checks from CSUTLDTC.cbl FEEDBACK-CODE:
 * - FC-INVALID-DATE: Date validation passed (severity 0000)
 * - FC-INSUFFICIENT-DATA: Empty or missing date string
 * - FC-BAD-DATE-VALUE: Invalid date value (e.g., Feb 30)
 * - FC-INVALID-MONTH: Month out of range (1-12)
 * - FC-UNSUPP-RANGE: Year out of supported range
 * 
 * Per Section 0.7.2: Preserve all field-level validations exactly as
 * implemented in COBOL
 * 
 * @param dateString - Date string to validate
 * @param formatPattern - Expected format pattern (defaults to DISPLAY format)
 * @returns true if valid, false otherwise
 * 
 * @example
 * ```typescript
 * isValidDate('03/15/2024'); // Returns true
 * isValidDate('13/01/2024'); // Returns false (invalid month)
 * isValidDate('02/30/2024'); // Returns false (invalid day)
 * isValidDate(''); // Returns false (insufficient data)
 * isValidDate('12/31/1899'); // Returns false (unsupp range)
 * ```
 */
export const isValidDate = (
  dateString: string | null | undefined,
  formatPattern: string = DATE_FORMATS.DISPLAY
): boolean => {
  // FC-INSUFFICIENT-DATA check
  if (!dateString || dateString.trim() === '') {
    return false;
  }

  // Attempt to parse the date
  const parsed = parseDate(dateString, formatPattern);
  
  // FC-BAD-DATE-VALUE check
  if (!parsed || !isValidFns(parsed)) {
    return false;
  }

  // FC-UNSUPP-RANGE check
  // COBOL CEEDAYS supports dates from 1582-10-15 to 3000-12-31
  // We limit to practical range: 1900-2100 for business applications
  const year = parsed.getFullYear();
  if (year < 1900 || year > 2100) {
    return false;
  }

  // FC-INVALID-MONTH check (implicitly validated by parse, but explicit check)
  const month = parsed.getMonth() + 1; // getMonth() is 0-based
  if (month < 1 || month > 12) {
    return false;
  }

  // Additional validation: ensure the parsed date matches the input
  // This catches cases like '02/30/2024' that might parse incorrectly
  const reformatted = format(parsed, formatPattern);
  if (reformatted !== dateString) {
    return false; // FC-BAD-DATE-VALUE
  }

  return true; // FC-INVALID-DATE (severity 0000 = success)
};

/**
 * Gets current date with time set to midnight
 * 
 * COBOL equivalent: ACCEPT DATE FROM DATE YYYYMMDD
 * from CSDAT01Y.cpy WS-CURDATE
 * 
 * Per Agent Action Plan: Maintain date precision to day level (no time
 * component) matching COBOL date handling
 * 
 * @returns Current date with time set to midnight (00:00:00.000)
 * 
 * @example
 * ```typescript
 * const today = getCurrentDate();
 * console.log(today.getHours()); // 0
 * console.log(today.getMinutes()); // 0
 * console.log(today.getSeconds()); // 0
 * ```
 */
export const getCurrentDate = (): Date => {
  const now = new Date();
  // Set time to midnight to match COBOL date-only behavior
  now.setHours(0, 0, 0, 0);
  return now;
};

/**
 * Adds days to a date (COBOL Lilian date arithmetic)
 * 
 * COBOL equivalent: OUTPUT-LILLIAN + days calculation from CSUTLDTC.cbl
 * 
 * Original COBOL logic:
 * - Converts date to Lilian format (days since October 15, 1582)
 * - Performs arithmetic: OUTPUT-LILLIAN + number-of-days
 * - Converts back to Gregorian calendar date
 * 
 * TypeScript implementation uses date-fns addDays() which handles:
 * - Leap years automatically
 * - Month boundaries (e.g., Jan 31 + 1 day = Feb 1)
 * - Year boundaries (e.g., Dec 31 + 1 day = Jan 1 next year)
 * 
 * Per Section 0.7.5: Preserve COBOL date calculation semantics
 * 
 * @param date - Base date
 * @param days - Number of days to add (can be negative for subtraction)
 * @returns New date with days added
 * 
 * @example
 * ```typescript
 * addDays(new Date('2024-01-31'), 1); // Returns Feb 1, 2024
 * addDays(new Date('2024-02-28'), 1); // Returns Feb 29, 2024 (leap year)
 * addDays(new Date('2024-12-31'), 1); // Returns Jan 1, 2025
 * ```
 */
export const addDays = (date: Date, days: number): Date => {
  if (!date || !isValidFns(date)) {
    throw new Error('Invalid date provided to addDays');
  }
  return addDaysFns(date, days);
};

/**
 * Subtracts days from a date (COBOL Lilian date arithmetic)
 * 
 * COBOL equivalent: OUTPUT-LILLIAN - days calculation from CSUTLDTC.cbl
 * 
 * Uses date-fns subDays() for reliable date arithmetic with proper
 * handling of month/year boundaries and leap years.
 * 
 * @param date - Base date
 * @param days - Number of days to subtract
 * @returns New date with days subtracted
 * 
 * @example
 * ```typescript
 * subtractDays(new Date('2024-03-01'), 1); // Returns Feb 29, 2024
 * subtractDays(new Date('2024-01-01'), 1); // Returns Dec 31, 2023
 * ```
 */
export const subtractDays = (date: Date, days: number): Date => {
  if (!date || !isValidFns(date)) {
    throw new Error('Invalid date provided to subtractDays');
  }
  return subDays(date, days);
};

/**
 * Calculates difference in days between two dates
 * 
 * COBOL equivalent: OUTPUT-LILLIAN1 - OUTPUT-LILLIAN2 from CSUTLDTC.cbl
 * 
 * Original COBOL logic:
 * - Converts both dates to Lilian format using CEEDAYS
 * - Subtracts: OUTPUT-LILLIAN1 - OUTPUT-LILLIAN2
 * - Result is number of days difference
 * 
 * TypeScript implementation:
 * - Uses date-fns differenceInDays()
 * - Returns positive number if date1 > date2
 * - Returns negative number if date1 < date2
 * - Returns 0 if dates are the same
 * 
 * Used for:
 * - Account aging calculations
 * - Transaction date range calculations
 * - Billing period computations
 * - Interest accrual calculations
 * 
 * Per Section 0.7.2: Maintain identical calculation methods from COBOL
 * 
 * @param date1 - First date
 * @param date2 - Second date
 * @returns Number of days between dates (positive if date1 > date2)
 * 
 * @example
 * ```typescript
 * dateDifference(new Date('2024-03-15'), new Date('2024-03-10')); // Returns 5
 * dateDifference(new Date('2024-03-10'), new Date('2024-03-15')); // Returns -5
 * dateDifference(new Date('2024-03-15'), new Date('2024-03-15')); // Returns 0
 * ```
 */
export const dateDifference = (date1: Date, date2: Date): number => {
  if (!date1 || !isValidFns(date1)) {
    throw new Error('Invalid date1 provided to dateDifference');
  }
  if (!date2 || !isValidFns(date2)) {
    throw new Error('Invalid date2 provided to dateDifference');
  }
  return differenceInDays(date1, date2);
};
