/**
 * Error Code Constants
 * 
 * Standardized error codes for the OCR Processing Application backend API.
 * These codes are used in error responses to provide consistent, programmatic
 * error handling for API consumers.
 * 
 * Error Response Format (per Section 0.7.4 API Design Guidelines):
 * {
 *   "success": false,
 *   "error": {
 *     "code": "VALIDATION_ERROR",
 *     "message": "Invalid document format",
 *     "details": [...]
 *   }
 * }
 * 
 * @module error-codes.constants
 */

// ============================================================================
// Authentication Error Codes
// ============================================================================

/**
 * Wrong username or password provided during authentication
 */
export const ERR_INVALID_CREDENTIALS = 'INVALID_CREDENTIALS' as const;

/**
 * JWT access token has expired and needs to be refreshed
 */
export const ERR_TOKEN_EXPIRED = 'TOKEN_EXPIRED' as const;

/**
 * JWT token is invalid, malformed, or tampered with
 */
export const ERR_TOKEN_INVALID = 'TOKEN_INVALID' as const;

/**
 * Authentication is required to access this resource
 */
export const ERR_UNAUTHORIZED_ACCESS = 'UNAUTHORIZED_ACCESS' as const;

/**
 * User lacks the required role or permission to perform this action
 */
export const ERR_INSUFFICIENT_PERMISSIONS = 'INSUFFICIENT_PERMISSIONS' as const;

/**
 * Multi-factor authentication is required for this operation
 */
export const ERR_MFA_REQUIRED = 'MFA_REQUIRED' as const;

// ============================================================================
// Validation Error Codes
// ============================================================================

/**
 * Generic validation failure for request data
 */
export const ERR_VALIDATION_FAILED = 'VALIDATION_ERROR' as const;

/**
 * Input data format is invalid or cannot be parsed
 */
export const ERR_INVALID_INPUT = 'INVALID_INPUT' as const;

/**
 * A required field is missing from the request
 */
export const ERR_MISSING_REQUIRED_FIELD = 'MISSING_REQUIRED_FIELD' as const;

/**
 * Uploaded file type is not supported (must be PDF, JPG, PNG, etc.)
 */
export const ERR_INVALID_FILE_TYPE = 'INVALID_FILE_TYPE' as const;

/**
 * Uploaded file exceeds the maximum allowed size limit
 */
export const ERR_FILE_TOO_LARGE = 'FILE_TOO_LARGE' as const;

/**
 * Date range parameters are invalid (end date before start date, etc.)
 */
export const ERR_INVALID_DATE_RANGE = 'INVALID_DATE_RANGE' as const;

// ============================================================================
// Resource Error Codes
// ============================================================================

/**
 * The requested resource (document, template, user, etc.) does not exist
 */
export const ERR_RESOURCE_NOT_FOUND = 'RESOURCE_NOT_FOUND' as const;

/**
 * Resource already exists or conflicts with existing data
 */
export const ERR_RESOURCE_CONFLICT = 'RESOURCE_CONFLICT' as const;

/**
 * Resource is currently locked by another user or process
 */
export const ERR_RESOURCE_LOCKED = 'RESOURCE_LOCKED' as const;

/**
 * Resource has been deleted and is no longer available
 */
export const ERR_RESOURCE_DELETED = 'RESOURCE_DELETED' as const;

// ============================================================================
// Document Processing Error Codes
// ============================================================================

/**
 * OCR text extraction failed for the document
 */
export const ERR_OCR_PROCESSING_FAILED = 'OCR_PROCESSING_FAILED' as const;

/**
 * General document processing error during upload or extraction
 */
export const ERR_DOCUMENT_PROCESSING_ERROR = 'DOCUMENT_PROCESSING_ERROR' as const;

/**
 * Batch processing job failed to complete successfully
 */
export const ERR_BATCH_PROCESSING_FAILED = 'BATCH_PROCESSING_FAILED' as const;

/**
 * Malware or virus detected in the uploaded file
 */
export const ERR_VIRUS_DETECTED = 'VIRUS_DETECTED' as const;

/**
 * Document file is corrupted and cannot be processed
 */
export const ERR_DOCUMENT_CORRUPTED = 'DOCUMENT_CORRUPTED' as const;

// ============================================================================
// Integration Error Codes
// ============================================================================

/**
 * External third-party API call failed (QuickBooks, Salesforce, etc.)
 */
export const ERR_EXTERNAL_API_ERROR = 'EXTERNAL_API_ERROR' as const;

/**
 * Webhook delivery to subscriber endpoint was unsuccessful
 */
export const ERR_WEBHOOK_DELIVERY_FAILED = 'WEBHOOK_DELIVERY_FAILED' as const;

/**
 * Integration is not configured or credentials are missing
 */
export const ERR_INTEGRATION_NOT_CONFIGURED = 'INTEGRATION_NOT_CONFIGURED' as const;

/**
 * OAuth authentication or token refresh failed
 */
export const ERR_OAUTH_ERROR = 'OAUTH_ERROR' as const;

// ============================================================================
// System Error Codes
// ============================================================================

/**
 * Unexpected internal server error occurred
 */
export const ERR_INTERNAL_ERROR = 'INTERNAL_ERROR' as const;

/**
 * Database operation failed (connection, query, transaction error)
 */
export const ERR_DATABASE_ERROR = 'DATABASE_ERROR' as const;

/**
 * Service is temporarily unavailable (maintenance, high load, etc.)
 */
export const ERR_SERVICE_UNAVAILABLE = 'SERVICE_UNAVAILABLE' as const;

/**
 * Rate limit exceeded - too many requests from this account/IP
 */
export const ERR_RATE_LIMIT_EXCEEDED = 'RATE_LIMIT_EXCEEDED' as const;

/**
 * Processing queue is at capacity and cannot accept new jobs
 */
export const ERR_QUEUE_FULL = 'QUEUE_FULL' as const;

// ============================================================================
// Account/Tenant Error Codes
// ============================================================================

/**
 * Account has been suspended and cannot perform operations
 */
export const ERR_ACCOUNT_SUSPENDED = 'ACCOUNT_SUSPENDED' as const;

/**
 * Account has reached its usage limit (documents, users, API calls, etc.)
 */
export const ERR_ACCOUNT_LIMIT_REACHED = 'ACCOUNT_LIMIT_REACHED' as const;

/**
 * Invalid or non-existent account identifier
 */
export const ERR_INVALID_ACCOUNT = 'INVALID_ACCOUNT' as const;

// ============================================================================
// Grouped Error Codes Object
// ============================================================================

/**
 * Grouped error codes object for convenient access and iteration.
 * Provides all error codes in a single object for use in error handling
 * logic, validation, and documentation generation.
 * 
 * @example
 * ```typescript
 * import { ErrorCodes } from './error-codes.constants';
 * 
 * if (errorCode === ErrorCodes.INVALID_CREDENTIALS) {
 *   // Handle authentication error
 * }
 * ```
 */
export const ErrorCodes = {
  // Authentication
  INVALID_CREDENTIALS: ERR_INVALID_CREDENTIALS,
  TOKEN_EXPIRED: ERR_TOKEN_EXPIRED,
  TOKEN_INVALID: ERR_TOKEN_INVALID,
  UNAUTHORIZED_ACCESS: ERR_UNAUTHORIZED_ACCESS,
  INSUFFICIENT_PERMISSIONS: ERR_INSUFFICIENT_PERMISSIONS,
  MFA_REQUIRED: ERR_MFA_REQUIRED,

  // Validation
  VALIDATION_FAILED: ERR_VALIDATION_FAILED,
  INVALID_INPUT: ERR_INVALID_INPUT,
  MISSING_REQUIRED_FIELD: ERR_MISSING_REQUIRED_FIELD,
  INVALID_FILE_TYPE: ERR_INVALID_FILE_TYPE,
  FILE_TOO_LARGE: ERR_FILE_TOO_LARGE,
  INVALID_DATE_RANGE: ERR_INVALID_DATE_RANGE,

  // Resources
  RESOURCE_NOT_FOUND: ERR_RESOURCE_NOT_FOUND,
  RESOURCE_CONFLICT: ERR_RESOURCE_CONFLICT,
  RESOURCE_LOCKED: ERR_RESOURCE_LOCKED,
  RESOURCE_DELETED: ERR_RESOURCE_DELETED,

  // Document Processing
  OCR_PROCESSING_FAILED: ERR_OCR_PROCESSING_FAILED,
  DOCUMENT_PROCESSING_ERROR: ERR_DOCUMENT_PROCESSING_ERROR,
  BATCH_PROCESSING_FAILED: ERR_BATCH_PROCESSING_FAILED,
  VIRUS_DETECTED: ERR_VIRUS_DETECTED,
  DOCUMENT_CORRUPTED: ERR_DOCUMENT_CORRUPTED,

  // Integrations
  EXTERNAL_API_ERROR: ERR_EXTERNAL_API_ERROR,
  WEBHOOK_DELIVERY_FAILED: ERR_WEBHOOK_DELIVERY_FAILED,
  INTEGRATION_NOT_CONFIGURED: ERR_INTEGRATION_NOT_CONFIGURED,
  OAUTH_ERROR: ERR_OAUTH_ERROR,

  // System
  INTERNAL_ERROR: ERR_INTERNAL_ERROR,
  DATABASE_ERROR: ERR_DATABASE_ERROR,
  SERVICE_UNAVAILABLE: ERR_SERVICE_UNAVAILABLE,
  RATE_LIMIT_EXCEEDED: ERR_RATE_LIMIT_EXCEEDED,
  QUEUE_FULL: ERR_QUEUE_FULL,

  // Account/Tenant
  ACCOUNT_SUSPENDED: ERR_ACCOUNT_SUSPENDED,
  ACCOUNT_LIMIT_REACHED: ERR_ACCOUNT_LIMIT_REACHED,
  INVALID_ACCOUNT: ERR_INVALID_ACCOUNT,
} as const;

/**
 * Type representing all valid error code values
 */
export type ErrorCodeValue = typeof ErrorCodes[keyof typeof ErrorCodes];
