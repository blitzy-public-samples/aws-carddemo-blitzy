/**
 * API Response Format Constants and Types
 * 
 * This file defines standardized response structures for all API endpoints
 * following the guidelines in Section 0.7.4 API Design Guidelines.
 * 
 * Usage:
 * - Import these interfaces/types in controllers to ensure consistent responses
 * - Use in exception filters for standardized error responses
 * - Reference in API documentation and OpenAPI specifications
 * 
 * @module api-response.constants
 */

// =============================================================================
// API VERSION AND FIELD NAME CONSTANTS
// =============================================================================

/**
 * Current API version identifier
 * Used in response meta field to indicate API version
 */
export const API_VERSION = 'v1';

/**
 * Field name for success indicator in all responses
 */
export const RESPONSE_SUCCESS_FIELD = 'success';

/**
 * Field name for data payload in success responses
 */
export const RESPONSE_DATA_FIELD = 'data';

/**
 * Field name for metadata in all responses
 */
export const RESPONSE_META_FIELD = 'meta';

/**
 * Field name for error object in error responses
 */
export const RESPONSE_ERROR_FIELD = 'error';

/**
 * Field name for timestamp in metadata
 */
export const RESPONSE_TIMESTAMP_FIELD = 'timestamp';

// =============================================================================
// TIMESTAMP FORMAT CONSTANTS
// =============================================================================

/**
 * Timestamp format specification for all API responses
 * Follows ISO 8601 standard with UTC timezone
 * 
 * Format: YYYY-MM-DDTHH:mm:ss.sssZ
 * Example: 2025-10-31T12:00:00.000Z
 */
export const TIMESTAMP_FORMAT = 'ISO 8601';

// =============================================================================
// META FIELD KEY CONSTANTS
// =============================================================================

/**
 * Key for timestamp field within meta object
 */
export const META_TIMESTAMP_KEY = 'timestamp';

/**
 * Key for version field within meta object
 */
export const META_VERSION_KEY = 'version';

/**
 * Key for request ID field within meta object
 * Used for request tracing and monitoring per Section 0.7.5
 */
export const META_REQUEST_ID_KEY = 'request_id';

// =============================================================================
// PAGINATION CONSTANTS
// =============================================================================

/**
 * Default number of items per page
 * Per Section 0.7.4: Default page size is 25
 */
export const DEFAULT_PAGE_SIZE = 25;

/**
 * Maximum allowed items per page
 * Per Section 0.7.4: Max page size is 100
 */
export const MAX_PAGE_SIZE = 100;

/**
 * Minimum allowed items per page
 */
export const MIN_PAGE_SIZE = 1;

/**
 * Default page number (1-indexed)
 */
export const DEFAULT_PAGE = 1;

// =============================================================================
// TYPESCRIPT INTERFACES
// =============================================================================

/**
 * Metadata included in all API responses
 * 
 * @interface ResponseMeta
 */
export interface ResponseMeta {
  /**
   * ISO 8601 timestamp when the response was generated
   * Example: "2025-10-31T12:00:00.000Z"
   */
  readonly timestamp: string;

  /**
   * API version identifier
   * Example: "v1"
   */
  readonly version: string;

  /**
   * Unique identifier for request tracing
   * Optional: Only included when request tracking is enabled
   */
  readonly request_id?: string;
}

/**
 * Standard success response structure
 * 
 * All successful API responses follow this structure per Section 0.7.4
 * 
 * @template T - Type of the data payload
 * 
 * @example
 * ```typescript
 * const response: ApiSuccessResponse<User> = {
 *   success: true,
 *   data: { id: '123', email: 'user@example.com' },
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1'
 *   }
 * };
 * ```
 */
export interface ApiSuccessResponse<T> {
  /**
   * Success indicator (always true for success responses)
   */
  readonly success: true;

  /**
   * Response data payload
   */
  readonly data: T;

  /**
   * Response metadata
   * Optional: May be omitted in some minimal responses
   */
  readonly meta?: ResponseMeta;
}

/**
 * Detailed error information for validation and field-level errors
 * 
 * @interface ErrorDetail
 * 
 * @example
 * ```typescript
 * const errorDetail: ErrorDetail = {
 *   field: 'email',
 *   message: 'Must be a valid email address',
 *   code: 'INVALID_EMAIL_FORMAT',
 *   value: 'invalid-email'
 * };
 * ```
 */
export interface ErrorDetail {
  /**
   * Field name that caused the error
   * Optional: Used for validation errors
   */
  readonly field?: string;

  /**
   * Human-readable error message
   */
  readonly message: string;

  /**
   * Machine-readable error code
   * Optional: Used for programmatic error handling
   * Should reference error codes from error-codes.constants.ts
   */
  readonly code?: string;

  /**
   * The invalid value that caused the error
   * Optional: Useful for debugging
   */
  readonly value?: any;
}

/**
 * Error object structure included in error responses
 * 
 * @interface ErrorObject
 */
export interface ErrorObject {
  /**
   * Machine-readable error code
   * Should use constants from error-codes.constants.ts
   * Examples: 'VALIDATION_ERROR', 'NOT_FOUND', 'UNAUTHORIZED'
   */
  readonly code: string;

  /**
   * Human-readable error message
   */
  readonly message: string;

  /**
   * Detailed error information
   * Optional: Used for validation errors with multiple field errors
   */
  readonly details?: readonly ErrorDetail[];

  /**
   * Stack trace for debugging
   * Optional: Only included in development environment
   * MUST be excluded in production per security guidelines
   */
  readonly stack?: string;
}

/**
 * Standard error response structure
 * 
 * All error responses follow this structure per Section 0.7.4
 * 
 * @example
 * ```typescript
 * const response: ApiErrorResponse = {
 *   success: false,
 *   error: {
 *     code: 'VALIDATION_ERROR',
 *     message: 'Invalid document format',
 *     details: [
 *       {
 *         field: 'file_type',
 *         message: 'Must be PDF, JPG, or PNG'
 *       }
 *     ]
 *   },
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1'
 *   }
 * };
 * ```
 */
export interface ApiErrorResponse {
  /**
   * Success indicator (always false for error responses)
   */
  readonly success: false;

  /**
   * Error object containing error details
   */
  readonly error: ErrorObject;

  /**
   * Response metadata
   * Optional: May be omitted in some error responses
   */
  readonly meta?: ResponseMeta;
}

/**
 * Pagination metadata for paginated responses
 * 
 * @interface PaginationMeta
 */
export interface PaginationMeta {
  /**
   * Total number of items across all pages
   */
  readonly total: number;

  /**
   * Current page number (1-indexed)
   */
  readonly page: number;

  /**
   * Number of items per page
   */
  readonly page_size: number;

  /**
   * Total number of pages
   */
  readonly total_pages: number;

  /**
   * Whether there is a next page
   */
  readonly has_next: boolean;

  /**
   * Whether there is a previous page
   */
  readonly has_previous: boolean;

  /**
   * Cursor for next page (cursor-based pagination)
   * Optional: Used for cursor-based pagination
   */
  readonly next_cursor?: string;

  /**
   * Cursor for previous page (cursor-based pagination)
   * Optional: Used for cursor-based pagination
   */
  readonly previous_cursor?: string;
}

/**
 * Standard paginated response structure
 * 
 * Used for endpoints that return lists of items with pagination
 * Per Section 0.7.4 API Design Guidelines
 * 
 * @template T - Type of items in the data array
 * 
 * @example
 * ```typescript
 * const response: PaginatedResponse<Document> = {
 *   success: true,
 *   data: [
 *     { id: '1', name: 'Document 1' },
 *     { id: '2', name: 'Document 2' }
 *   ],
 *   pagination: {
 *     total: 1000,
 *     page: 1,
 *     page_size: 25,
 *     total_pages: 40,
 *     has_next: true,
 *     has_previous: false
 *   },
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1'
 *   }
 * };
 * ```
 */
export interface PaginatedResponse<T> {
  /**
   * Success indicator (always true for successful paginated responses)
   */
  readonly success: true;

  /**
   * Array of items for current page
   */
  readonly data: readonly T[];

  /**
   * Pagination metadata
   */
  readonly pagination: PaginationMeta;

  /**
   * Response metadata
   * Optional: May be omitted in some responses
   */
  readonly meta?: ResponseMeta;
}

// =============================================================================
// TYPE ALIASES
// =============================================================================

/**
 * Union type representing any API response (success or error)
 * 
 * Use this type when handling responses that could be either success or error
 * 
 * @template T - Type of the data payload for success responses
 * 
 * @example
 * ```typescript
 * function handleResponse<T>(response: ApiResponse<T>) {
 *   if (response.success) {
 *     console.log('Success:', response.data);
 *   } else {
 *     console.error('Error:', response.error.message);
 *   }
 * }
 * ```
 */
export type ApiResponse<T> = ApiSuccessResponse<T> | ApiErrorResponse;

/**
 * Success response with no data payload
 * 
 * Used for operations that succeed but don't return data
 * Typically used with HTTP 204 No Content status
 * 
 * @example
 * ```typescript
 * const response: EmptyResponse = {
 *   success: true,
 *   data: null,
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1'
 *   }
 * };
 * ```
 */
export type EmptyResponse = ApiSuccessResponse<null>;

// =============================================================================
// USAGE NOTES
// =============================================================================

/**
 * HTTP Status Code Mapping (reference http-status.constants.ts)
 * 
 * Success Responses:
 * - 200 OK: GET, PUT, PATCH operations with data
 * - 201 Created: POST operations that create resources
 * - 204 No Content: DELETE operations (use EmptyResponse)
 * 
 * Error Responses:
 * - 400 Bad Request: Validation errors (use VALIDATION_ERROR code)
 * - 401 Unauthorized: Missing/invalid authentication
 * - 403 Forbidden: Insufficient permissions
 * - 404 Not Found: Resource doesn't exist
 * - 409 Conflict: Resource conflict
 * - 429 Too Many Requests: Rate limit exceeded
 * - 500 Internal Server Error: Unexpected errors
 */

/**
 * Error Code Reference (see error-codes.constants.ts)
 * 
 * Common error codes to use in ApiErrorResponse:
 * - VALIDATION_ERROR: Input validation failures
 * - NOT_FOUND: Resource not found
 * - UNAUTHORIZED: Authentication required
 * - FORBIDDEN: Insufficient permissions
 * - CONFLICT: Resource already exists or conflicts
 * - RATE_LIMIT_EXCEEDED: Too many requests
 * - INTERNAL_ERROR: Unexpected server errors
 */
