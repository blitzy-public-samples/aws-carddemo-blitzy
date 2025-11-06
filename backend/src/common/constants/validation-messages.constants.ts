/**
 * Validation Error Message Constants
 *
 * This file defines standardized, user-friendly validation error messages used throughout
 * the OCR Processing Application backend API. These constants ensure consistent error
 * messaging across all DTOs, controllers, and validation logic.
 *
 * Message templates support placeholders (e.g., {min}, {max}, {types}) that should be
 * replaced with actual values at runtime to provide specific feedback to users.
 *
 * Usage Example:
 * ```typescript
 * @MinLength(8, { message: MSG_PASSWORD_LENGTH })
 * password: string;
 *
 * // Or with dynamic values:
 * throw new BadRequestException(
 *   MSG_FILE_TOO_LARGE.replace('{maxSize}', '10')
 * );
 * ```
 *
 * @see Section 0.7.4 API Design Guidelines
 * @see Section 0.7.11 UX Directives
 */

/* eslint-disable @typescript-eslint/no-unnecessary-type-assertion */
// Note: 'as const' assertions are intentionally used here to create literal types
// for better type safety, even though ESLint considers them unnecessary. This follows
// TypeScript best practices for constant definitions as specified in requirements.

// ============================================================================
// Required Field Messages
// ============================================================================

/**
 * Generic required field message for any mandatory field
 */
export const MSG_FIELD_REQUIRED = 'This field is required' as const;

/**
 * Email address is required
 */
export const MSG_EMAIL_REQUIRED = 'Email address is required' as const;

/**
 * Password is required for authentication
 */
export const MSG_PASSWORD_REQUIRED = 'Password is required' as const;

/**
 * Username is required for account creation/login
 */
export const MSG_USERNAME_REQUIRED = 'Username is required' as const;

/**
 * File upload is required for document processing
 */
export const MSG_FILE_REQUIRED = 'File upload is required' as const;

/**
 * Account ID is required for multi-tenant operations
 */
export const MSG_ACCOUNT_ID_REQUIRED = 'Account ID is required' as const;

// ============================================================================
// Format Validation Messages
// ============================================================================

/**
 * Email address format is invalid
 */
export const MSG_INVALID_EMAIL = 'Please enter a valid email address' as const;

/**
 * Phone number format is invalid
 */
export const MSG_INVALID_PHONE = 'Please enter a valid phone number' as const;

/**
 * URL format is invalid
 */
export const MSG_INVALID_URL = 'Please enter a valid URL' as const;

/**
 * Date format is invalid
 */
export const MSG_INVALID_DATE = 'Please enter a valid date' as const;

/**
 * UUID/identifier format is invalid
 */
export const MSG_INVALID_UUID = 'Invalid identifier format' as const;

/**
 * JSON format is invalid
 */
export const MSG_INVALID_JSON = 'Invalid JSON format' as const;

// ============================================================================
// String Length Validation Messages
// ============================================================================

/**
 * String length is below minimum requirement
 * Placeholder: {min} - minimum character count
 */
export const MSG_MIN_LENGTH = 'Must be at least {min} characters' as const;

/**
 * String length exceeds maximum allowed
 * Placeholder: {max} - maximum character count
 */
export const MSG_MAX_LENGTH = 'Must not exceed {max} characters' as const;

/**
 * String length must be exact
 * Placeholder: {length} - required character count
 */
export const MSG_EXACT_LENGTH = 'Must be exactly {length} characters' as const;

/**
 * Username length validation message
 */
export const MSG_USERNAME_LENGTH = 'Username must be between 3 and 30 characters' as const;

/**
 * Password minimum length requirement
 */
export const MSG_PASSWORD_LENGTH = 'Password must be at least 8 characters' as const;

// ============================================================================
// Numeric Validation Messages
// ============================================================================

/**
 * Numeric value is below minimum
 * Placeholder: {min} - minimum value
 */
export const MSG_MIN_VALUE = 'Value must be at least {min}' as const;

/**
 * Numeric value exceeds maximum
 * Placeholder: {max} - maximum value
 */
export const MSG_MAX_VALUE = 'Value must not exceed {max}' as const;

/**
 * Value must be positive (greater than zero)
 */
export const MSG_POSITIVE_NUMBER = 'Value must be a positive number' as const;

/**
 * Value must be an integer (no decimals)
 */
export const MSG_INTEGER_REQUIRED = 'Value must be an integer' as const;

/**
 * Value must fall within specified range
 * Placeholders: {min} - minimum value, {max} - maximum value
 */
export const MSG_INVALID_RANGE = 'Value must be between {min} and {max}' as const;

// ============================================================================
// File Upload Validation Messages
// ============================================================================

/**
 * Uploaded file type is not supported
 * Placeholder: {types} - comma-separated list of allowed file types
 */
export const MSG_INVALID_FILE_TYPE = 'File type not supported. Allowed types: {types}' as const;

/**
 * File size exceeds maximum limit
 * Placeholder: {maxSize} - maximum file size in MB
 */
export const MSG_FILE_TOO_LARGE = 'File size exceeds maximum of {maxSize} MB' as const;

/**
 * File size is below minimum requirement
 * Placeholder: {minSize} - minimum file size in KB
 */
export const MSG_FILE_TOO_SMALL = 'File size is below minimum of {minSize} KB' as const;

/**
 * File format does not match expected format
 * Placeholder: {format} - expected file format
 */
export const MSG_INVALID_FILE_FORMAT = 'Invalid file format. Expected: {format}' as const;

/**
 * File failed virus/malware security scan
 */
export const MSG_VIRUS_DETECTED = 'File failed security scan' as const;

// ============================================================================
// Password Validation Messages
// ============================================================================

/**
 * Password does not meet strength requirements
 */
export const MSG_PASSWORD_WEAK =
  'Password must contain uppercase, lowercase, number, and special character' as const;

/**
 * Password and confirmation password do not match
 */
export const MSG_PASSWORD_MISMATCH = 'Passwords do not match' as const;

/**
 * Password is too common or easily guessable
 */
export const MSG_PASSWORD_COMMON =
  'This password is too common. Please choose a stronger password' as const;

/**
 * Password has been used recently and cannot be reused
 */
export const MSG_PASSWORD_REUSE = 'Cannot reuse a recent password' as const;

// ============================================================================
// Date/Time Validation Messages
// ============================================================================

/**
 * Date format does not match ISO 8601 standard
 */
export const MSG_INVALID_DATE_FORMAT = 'Date must be in ISO 8601 format (YYYY-MM-DD)' as const;

/**
 * Date must be in the past
 */
export const MSG_DATE_IN_PAST = 'Date must be in the past' as const;

/**
 * Date must be in the future
 */
export const MSG_DATE_IN_FUTURE = 'Date must be in the future' as const;

/**
 * Date range is invalid (end date before start date)
 */
export const MSG_INVALID_DATE_RANGE = 'End date must be after start date' as const;

// ============================================================================
// Business Rule Validation Messages
// ============================================================================

/**
 * A record with the provided unique value already exists
 */
export const MSG_DUPLICATE_ENTRY = 'A record with this value already exists' as const;

/**
 * Authentication credentials are invalid
 */
export const MSG_INVALID_CREDENTIALS = 'Invalid email or password' as const;

/**
 * Account has been locked due to security reasons
 */
export const MSG_ACCOUNT_LOCKED =
  'Account has been locked due to multiple failed login attempts' as const;

/**
 * Rate limit has been exceeded for API requests
 * Placeholder: {minutes} - time until rate limit resets
 */
export const MSG_RATE_LIMIT_EXCEEDED =
  'Too many requests. Please try again in {minutes} minutes' as const;

/**
 * Batch operation size exceeds maximum allowed
 * Placeholder: {max} - maximum batch size
 */
export const MSG_BATCH_SIZE_EXCEEDED = 'Batch size exceeds maximum of {max} items' as const;

// ============================================================================
// Array/Collection Validation Messages
// ============================================================================

/**
 * Array contains fewer items than minimum required
 * Placeholder: {min} - minimum number of items
 */
export const MSG_ARRAY_MIN_SIZE = 'Must contain at least {min} items' as const;

/**
 * Array contains more items than maximum allowed
 * Placeholder: {max} - maximum number of items
 */
export const MSG_ARRAY_MAX_SIZE = 'Must not exceed {max} items' as const;

/**
 * Array is empty but must contain at least one item
 */
export const MSG_ARRAY_EMPTY = 'Array cannot be empty' as const;

/**
 * Array contains duplicate values which are not allowed
 */
export const MSG_DUPLICATE_VALUES = 'Duplicate values are not allowed' as const;

// ============================================================================
// Relationship Validation Messages
// ============================================================================

/**
 * Referenced resource (foreign key) does not exist
 */
export const MSG_INVALID_REFERENCE = 'Referenced resource does not exist' as const;

/**
 * Relationship between resources is invalid or not allowed
 */
export const MSG_INVALID_RELATIONSHIP = 'Invalid relationship between resources' as const;

/**
 * Circular reference detected in resource relationships
 */
export const MSG_CIRCULAR_REFERENCE = 'Circular reference detected' as const;

// ============================================================================
// Grouped Export Object
// ============================================================================

/**
 * ValidationMessages object containing all validation error messages
 *
 * This grouped export provides an alternative way to access validation messages
 * through a single namespace object. Useful for scenarios where you want to
 * import all messages at once or need to iterate through available messages.
 *
 * Usage Example:
 * ```typescript
 * import { ValidationMessages } from './validation-messages.constants';
 *
 * throw new BadRequestException({
 *   message: ValidationMessages.INVALID_EMAIL
 * });
 * ```
 */
export const ValidationMessages = {
  // Required Field Messages
  FIELD_REQUIRED: MSG_FIELD_REQUIRED,
  EMAIL_REQUIRED: MSG_EMAIL_REQUIRED,
  PASSWORD_REQUIRED: MSG_PASSWORD_REQUIRED,
  USERNAME_REQUIRED: MSG_USERNAME_REQUIRED,
  FILE_REQUIRED: MSG_FILE_REQUIRED,
  ACCOUNT_ID_REQUIRED: MSG_ACCOUNT_ID_REQUIRED,

  // Format Validation Messages
  INVALID_EMAIL: MSG_INVALID_EMAIL,
  INVALID_PHONE: MSG_INVALID_PHONE,
  INVALID_URL: MSG_INVALID_URL,
  INVALID_DATE: MSG_INVALID_DATE,
  INVALID_UUID: MSG_INVALID_UUID,
  INVALID_JSON: MSG_INVALID_JSON,

  // String Length Validation Messages
  MIN_LENGTH: MSG_MIN_LENGTH,
  MAX_LENGTH: MSG_MAX_LENGTH,
  EXACT_LENGTH: MSG_EXACT_LENGTH,
  USERNAME_LENGTH: MSG_USERNAME_LENGTH,
  PASSWORD_LENGTH: MSG_PASSWORD_LENGTH,

  // Numeric Validation Messages
  MIN_VALUE: MSG_MIN_VALUE,
  MAX_VALUE: MSG_MAX_VALUE,
  POSITIVE_NUMBER: MSG_POSITIVE_NUMBER,
  INTEGER_REQUIRED: MSG_INTEGER_REQUIRED,
  INVALID_RANGE: MSG_INVALID_RANGE,

  // File Upload Validation Messages
  INVALID_FILE_TYPE: MSG_INVALID_FILE_TYPE,
  FILE_TOO_LARGE: MSG_FILE_TOO_LARGE,
  FILE_TOO_SMALL: MSG_FILE_TOO_SMALL,
  INVALID_FILE_FORMAT: MSG_INVALID_FILE_FORMAT,
  VIRUS_DETECTED: MSG_VIRUS_DETECTED,

  // Password Validation Messages
  PASSWORD_WEAK: MSG_PASSWORD_WEAK,
  PASSWORD_MISMATCH: MSG_PASSWORD_MISMATCH,
  PASSWORD_COMMON: MSG_PASSWORD_COMMON,
  PASSWORD_REUSE: MSG_PASSWORD_REUSE,

  // Date/Time Validation Messages
  INVALID_DATE_FORMAT: MSG_INVALID_DATE_FORMAT,
  DATE_IN_PAST: MSG_DATE_IN_PAST,
  DATE_IN_FUTURE: MSG_DATE_IN_FUTURE,
  INVALID_DATE_RANGE: MSG_INVALID_DATE_RANGE,

  // Business Rule Validation Messages
  DUPLICATE_ENTRY: MSG_DUPLICATE_ENTRY,
  INVALID_CREDENTIALS: MSG_INVALID_CREDENTIALS,
  ACCOUNT_LOCKED: MSG_ACCOUNT_LOCKED,
  RATE_LIMIT_EXCEEDED: MSG_RATE_LIMIT_EXCEEDED,
  BATCH_SIZE_EXCEEDED: MSG_BATCH_SIZE_EXCEEDED,

  // Array/Collection Validation Messages
  ARRAY_MIN_SIZE: MSG_ARRAY_MIN_SIZE,
  ARRAY_MAX_SIZE: MSG_ARRAY_MAX_SIZE,
  ARRAY_EMPTY: MSG_ARRAY_EMPTY,
  DUPLICATE_VALUES: MSG_DUPLICATE_VALUES,

  // Relationship Validation Messages
  INVALID_REFERENCE: MSG_INVALID_REFERENCE,
  INVALID_RELATIONSHIP: MSG_INVALID_RELATIONSHIP,
  CIRCULAR_REFERENCE: MSG_CIRCULAR_REFERENCE,
} as const;
