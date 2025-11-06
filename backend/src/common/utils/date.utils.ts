/**
 * Date Utility Functions
 * 
 * Provides comprehensive date manipulation and formatting utilities for the OCR Processing Application.
 * All functions operate in UTC timezone to ensure consistency across the system.
 * 
 * @module date.utils
 * @category Utilities
 * 
 * Requirements:
 * - Section 0.7.2: ALL timestamps MUST use UTC (no local time)
 * - Section 0.7.1: ALL functions MUST have JSDoc comments
 * - Section 0.7.1: Use strict TypeScript types (no any)
 */

/**
 * Formats a Date object to ISO 8601 string format in UTC timezone.
 * 
 * The output format is: YYYY-MM-DDTHH:mm:ss.sssZ
 * 
 * @param date - The Date object to format
 * @returns ISO 8601 formatted date string in UTC timezone
 * @throws {Error} If the input is not a valid Date object
 * 
 * @example
 * ```typescript
 * const date = new Date('2025-10-31T12:30:45.123Z');
 * const formatted = formatToISO8601(date);
 * // Returns: "2025-10-31T12:30:45.123Z"
 * ```
 */
export function formatToISO8601(date: Date): string {
  if (!isValidDate(date)) {
    throw new Error('Invalid Date object provided to formatToISO8601');
  }

  return date.toISOString();
}

/**
 * Safely parses a date from various input formats with comprehensive error handling.
 * 
 * Supports:
 * - ISO 8601 strings
 * - Unix timestamps (milliseconds)
 * - Date objects
 * - Date strings parseable by Date constructor
 * 
 * @param input - The date input to parse (string, number, or Date)
 * @returns Parsed Date object in UTC, or null if parsing fails
 * 
 * @example
 * ```typescript
 * const date1 = parseDate('2025-10-31T12:30:45Z');
 * const date2 = parseDate(1730379045000);
 * const date3 = parseDate(new Date());
 * ```
 */
export function parseDate(input: string | number | Date): Date | null {
  try {
    // If already a Date object, validate and return
    if (input instanceof Date) {
      return isValidDate(input) ? input : null;
    }

    // If numeric input, treat as Unix timestamp in milliseconds
    if (typeof input === 'number') {
      if (!Number.isFinite(input) || input < 0) {
        return null;
      }
      const date = new Date(input);
      return isValidDate(date) ? date : null;
    }

    // If string input, parse using Date constructor
    if (typeof input === 'string') {
      const trimmedInput = input.trim();
      if (trimmedInput === '') {
        return null;
      }
      const date = new Date(trimmedInput);
      return isValidDate(date) ? date : null;
    }

    return null;
  } catch (error) {
    // Catch any unexpected errors during parsing
    return null;
  }
}

/**
 * Returns the current date and time in UTC timezone.
 * 
 * This function ensures all timestamps are created in UTC, preventing
 * timezone-related issues across the system.
 * 
 * @returns Current Date object representing UTC time
 * 
 * @example
 * ```typescript
 * const now = getCurrentUTCDate();
 * console.log(now.toISOString()); // "2025-10-31T12:30:45.123Z"
 * ```
 */
export function getCurrentUTCDate(): Date {
  return new Date();
}

/**
 * Validates whether a given value is a valid Date object.
 * 
 * Checks that the value is a Date instance and not an Invalid Date.
 * 
 * @param date - The value to validate
 * @returns True if the date is valid, false otherwise
 * 
 * @example
 * ```typescript
 * isValidDate(new Date()); // true
 * isValidDate(new Date('invalid')); // false
 * isValidDate('2025-10-31'); // false (not a Date object)
 * ```
 */
export function isValidDate(date: unknown): date is Date {
  return date instanceof Date && !isNaN(date.getTime());
}

/**
 * Validates whether a date range is valid.
 * 
 * A valid date range requires:
 * - Both dates are valid Date objects
 * - Start date is before or equal to end date
 * 
 * @param startDate - The start date of the range
 * @param endDate - The end date of the range
 * @returns True if the date range is valid, false otherwise
 * 
 * @example
 * ```typescript
 * const start = new Date('2025-01-01');
 * const end = new Date('2025-12-31');
 * isValidDateRange(start, end); // true
 * isValidDateRange(end, start); // false (end before start)
 * ```
 */
export function isValidDateRange(startDate: Date, endDate: Date): boolean {
  if (!isValidDate(startDate) || !isValidDate(endDate)) {
    return false;
  }

  return startDate.getTime() <= endDate.getTime();
}

/**
 * Checks if the first date is after the second date.
 * 
 * Comparison is performed at millisecond precision.
 * 
 * @param date1 - The first date to compare
 * @param date2 - The second date to compare
 * @returns True if date1 is strictly after date2, false otherwise
 * @throws {Error} If either date is invalid
 * 
 * @example
 * ```typescript
 * const later = new Date('2025-12-31');
 * const earlier = new Date('2025-01-01');
 * isAfter(later, earlier); // true
 * isAfter(earlier, later); // false
 * ```
 */
export function isAfter(date1: Date, date2: Date): boolean {
  if (!isValidDate(date1) || !isValidDate(date2)) {
    throw new Error('Invalid Date object(s) provided to isAfter');
  }

  return date1.getTime() > date2.getTime();
}

/**
 * Checks if the first date is before the second date.
 * 
 * Comparison is performed at millisecond precision.
 * 
 * @param date1 - The first date to compare
 * @param date2 - The second date to compare
 * @returns True if date1 is strictly before date2, false otherwise
 * @throws {Error} If either date is invalid
 * 
 * @example
 * ```typescript
 * const earlier = new Date('2025-01-01');
 * const later = new Date('2025-12-31');
 * isBefore(earlier, later); // true
 * isBefore(later, earlier); // false
 * ```
 */
export function isBefore(date1: Date, date2: Date): boolean {
  if (!isValidDate(date1) || !isValidDate(date2)) {
    throw new Error('Invalid Date object(s) provided to isBefore');
  }

  return date1.getTime() < date2.getTime();
}

/**
 * Checks if two dates represent the same calendar day in UTC timezone.
 * 
 * Ignores time components and only compares year, month, and day.
 * 
 * @param date1 - The first date to compare
 * @param date2 - The second date to compare
 * @returns True if both dates are on the same calendar day (UTC), false otherwise
 * @throws {Error} If either date is invalid
 * 
 * @example
 * ```typescript
 * const morning = new Date('2025-10-31T08:00:00Z');
 * const evening = new Date('2025-10-31T20:00:00Z');
 * const nextDay = new Date('2025-11-01T08:00:00Z');
 * 
 * isSameDay(morning, evening); // true
 * isSameDay(morning, nextDay); // false
 * ```
 */
export function isSameDay(date1: Date, date2: Date): boolean {
  if (!isValidDate(date1) || !isValidDate(date2)) {
    throw new Error('Invalid Date object(s) provided to isSameDay');
  }

  return (
    date1.getUTCFullYear() === date2.getUTCFullYear() &&
    date1.getUTCMonth() === date2.getUTCMonth() &&
    date1.getUTCDate() === date2.getUTCDate()
  );
}

/**
 * Calculates the number of complete days between two dates.
 * 
 * The result is always positive (absolute difference).
 * Partial days are rounded down to complete days.
 * 
 * @param date1 - The first date
 * @param date2 - The second date
 * @returns Number of complete days between the two dates (always positive)
 * @throws {Error} If either date is invalid
 * 
 * @example
 * ```typescript
 * const start = new Date('2025-01-01T00:00:00Z');
 * const end = new Date('2025-01-10T12:00:00Z');
 * 
 * daysBetween(start, end); // 9 (complete days)
 * daysBetween(end, start); // 9 (order doesn't matter)
 * ```
 */
export function daysBetween(date1: Date, date2: Date): number {
  if (!isValidDate(date1) || !isValidDate(date2)) {
    throw new Error('Invalid Date object(s) provided to daysBetween');
  }

  const millisecondsPerDay = 24 * 60 * 60 * 1000;
  const timeDifference = Math.abs(date1.getTime() - date2.getTime());
  
  return Math.floor(timeDifference / millisecondsPerDay);
}

/**
 * Adds a specified number of days to a date.
 * 
 * Returns a new Date object without modifying the original.
 * 
 * @param date - The base date
 * @param days - Number of days to add (can be negative to subtract)
 * @returns New Date object with days added
 * @throws {Error} If the date is invalid or days is not a finite number
 * 
 * @example
 * ```typescript
 * const start = new Date('2025-10-31T12:00:00Z');
 * const future = addDays(start, 7);
 * // future: 2025-11-07T12:00:00Z
 * 
 * const past = addDays(start, -7);
 * // past: 2025-10-24T12:00:00Z
 * ```
 */
export function addDays(date: Date, days: number): Date {
  if (!isValidDate(date)) {
    throw new Error('Invalid Date object provided to addDays');
  }

  if (!Number.isFinite(days)) {
    throw new Error('Days parameter must be a finite number');
  }

  const result = new Date(date);
  result.setUTCDate(result.getUTCDate() + days);
  
  return result;
}

/**
 * Subtracts a specified number of days from a date.
 * 
 * Returns a new Date object without modifying the original.
 * This is a convenience function equivalent to addDays(date, -days).
 * 
 * @param date - The base date
 * @param days - Number of days to subtract (must be positive)
 * @returns New Date object with days subtracted
 * @throws {Error} If the date is invalid or days is not a finite number
 * 
 * @example
 * ```typescript
 * const start = new Date('2025-10-31T12:00:00Z');
 * const past = subtractDays(start, 7);
 * // past: 2025-10-24T12:00:00Z
 * ```
 */
export function subtractDays(date: Date, days: number): Date {
  if (!isValidDate(date)) {
    throw new Error('Invalid Date object provided to subtractDays');
  }

  if (!Number.isFinite(days)) {
    throw new Error('Days parameter must be a finite number');
  }

  return addDays(date, -days);
}
