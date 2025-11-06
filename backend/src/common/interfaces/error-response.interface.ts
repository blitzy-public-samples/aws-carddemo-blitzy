/**
 * Error Response Interface
 * 
 * Standardized error response structures for the OCR Processing Application API.
 * Implements the error format specified in Section 0.7.4 API Design Guidelines.
 * 
 * All API endpoints must use these interfaces to ensure consistent error handling
 * across the application, enabling clients to handle errors predictably.
 * 
 * @module ErrorResponseInterface
 */

/**
 * Error Code Enum
 * 
 * Machine-readable error codes that categorize different types of errors.
 * Each error code maps to specific HTTP status codes and indicates the type
 * of error that occurred.
 * 
 * HTTP Status Code Mappings:
 * - VALIDATION_ERROR: 400 Bad Request
 * - AUTHENTICATION_ERROR: 401 Unauthorized
 * - AUTHORIZATION_ERROR: 403 Forbidden
 * - NOT_FOUND: 404 Not Found
 * - CONFLICT: 409 Conflict
 * - RATE_LIMIT_EXCEEDED: 429 Too Many Requests
 * - INVALID_REQUEST: 400 Bad Request
 * - PROCESSING_ERROR: 422 Unprocessable Entity
 * - EXTERNAL_SERVICE_ERROR: 502 Bad Gateway
 * - INTERNAL_SERVER_ERROR: 500 Internal Server Error
 * - SERVICE_UNAVAILABLE: 503 Service Unavailable
 * 
 * @enum {string}
 */
export enum ErrorCode {
  /**
   * Validation error - Request data failed validation rules
   * Use when: Input data doesn't meet required format, type, or business rules
   * HTTP Status: 400 Bad Request
   */
  VALIDATION_ERROR = 'VALIDATION_ERROR',

  /**
   * Authentication error - Missing or invalid authentication credentials
   * Use when: No auth token provided, token expired, or token invalid
   * HTTP Status: 401 Unauthorized
   */
  AUTHENTICATION_ERROR = 'AUTHENTICATION_ERROR',

  /**
   * Authorization error - Authenticated user lacks required permissions
   * Use when: User is authenticated but doesn't have permission for the action
   * HTTP Status: 403 Forbidden
   */
  AUTHORIZATION_ERROR = 'AUTHORIZATION_ERROR',

  /**
   * Not found error - Requested resource does not exist
   * Use when: Document, user, template, or other resource cannot be found
   * HTTP Status: 404 Not Found
   */
  NOT_FOUND = 'NOT_FOUND',

  /**
   * Conflict error - Request conflicts with current state
   * Use when: Resource already exists, version mismatch, or concurrent modification
   * HTTP Status: 409 Conflict
   */
  CONFLICT = 'CONFLICT',

  /**
   * Rate limit exceeded - Too many requests from client
   * Use when: Client exceeds rate limit (default: 1000 requests/hour per account)
   * HTTP Status: 429 Too Many Requests
   */
  RATE_LIMIT_EXCEEDED = 'RATE_LIMIT_EXCEEDED',

  /**
   * Internal server error - Unexpected server error
   * Use when: Unhandled exception or unexpected error condition
   * HTTP Status: 500 Internal Server Error
   */
  INTERNAL_SERVER_ERROR = 'INTERNAL_SERVER_ERROR',

  /**
   * Service unavailable - Service temporarily unavailable
   * Use when: Maintenance mode, database unavailable, or system overload
   * HTTP Status: 503 Service Unavailable
   */
  SERVICE_UNAVAILABLE = 'SERVICE_UNAVAILABLE',

  /**
   * Invalid request - Malformed request that cannot be processed
   * Use when: Invalid JSON, missing required headers, or unsupported content type
   * HTTP Status: 400 Bad Request
   */
  INVALID_REQUEST = 'INVALID_REQUEST',

  /**
   * Processing error - Error during document processing
   * Use when: OCR extraction fails, document format unsupported, or virus detected
   * HTTP Status: 422 Unprocessable Entity
   */
  PROCESSING_ERROR = 'PROCESSING_ERROR',

  /**
   * External service error - Third-party service error
   * Use when: Google Vision API fails, AWS Textract unavailable, or integration error
   * HTTP Status: 502 Bad Gateway
   */
  EXTERNAL_SERVICE_ERROR = 'EXTERNAL_SERVICE_ERROR',
}

/**
 * Error Detail Interface
 * 
 * Detailed information about a specific field validation error.
 * Used within ErrorResponse to provide field-level error details
 * for validation errors.
 * 
 * Example:
 * ```json
 * {
 *   "field": "file_type",
 *   "message": "Must be PDF, JPG, or PNG",
 *   "value": "docx",
 *   "constraint": "fileType"
 * }
 * ```
 * 
 * @interface ErrorDetail
 */
export interface ErrorDetail {
  /**
   * Name of the field that failed validation
   * 
   * Use dot notation for nested fields (e.g., "address.zipCode")
   * Use array notation for array elements (e.g., "documents[0].fileName")
   * 
   * @example "file_type"
   * @example "template.fields[2].name"
   */
  field: string;

  /**
   * Human-readable error message for this field
   * 
   * Should be clear, actionable, and specific to the validation failure.
   * Provide guidance on how to fix the error.
   * 
   * @example "Must be PDF, JPG, or PNG"
   * @example "File size must not exceed 10MB"
   * @example "Email address is not valid"
   */
  message: string;

  /**
   * The invalid value that was provided (optional)
   * 
   * Include when it's safe to return the value (not sensitive data).
   * Omit for passwords, tokens, or other sensitive fields.
   * 
   * @example "docx"
   * @example 15728640 (for file size)
   * @example "invalid-email"
   */
  value?: any;

  /**
   * Name of the validation constraint that was violated (optional)
   * 
   * Useful for programmatic error handling on the client side.
   * Use standard constraint names from class-validator where applicable.
   * 
   * @example "fileType"
   * @example "maxLength"
   * @example "isEmail"
   * @example "min"
   */
  constraint?: string;
}

/**
 * Error Response Interface
 * 
 * Standardized error response structure for all API endpoints.
 * Implements the format specified in Section 0.7.4 API Design Guidelines.
 * 
 * All error responses must follow this structure:
 * ```json
 * {
 *   "success": false,
 *   "error": {
 *     "code": "VALIDATION_ERROR",
 *     "message": "Invalid document format",
 *     "details": [...],
 *     "timestamp": "2025-10-31T12:00:00Z",
 *     "path": "/api/v1/documents",
 *     "requestId": "req_abc123"
 *   }
 * }
 * ```
 * 
 * **Client Error Handling Guidelines:**
 * - Check `code` field for error type categorization
 * - Display `message` to end users
 * - Parse `details` array for field-specific validation errors
 * - Use `requestId` when reporting issues to support
 * 
 * **Security Notes:**
 * - NEVER include sensitive data in error messages
 * - NEVER expose internal system details
 * - NEVER include stack traces in production (`stackTrace` is development-only)
 * - Sanitize all error messages to prevent information disclosure
 * 
 * @interface ErrorResponse
 */
export interface ErrorResponse {
  /**
   * Machine-readable error code
   * 
   * Use ErrorCode enum values for standard errors.
   * For custom application-specific errors, use descriptive string codes
   * (e.g., "TEMPLATE_VALIDATION_FAILED", "INSUFFICIENT_CREDITS").
   * 
   * @example ErrorCode.VALIDATION_ERROR
   * @example "INSUFFICIENT_CREDITS"
   */
  code: ErrorCode | string;

  /**
   * Human-readable error message
   * 
   * Should be:
   * - Clear and concise (1-2 sentences max)
   * - Actionable (tell user what went wrong and how to fix)
   * - User-friendly (avoid technical jargon)
   * - Safe to display to end users
   * 
   * @example "Invalid document format"
   * @example "You do not have permission to access this resource"
   * @example "Document processing failed due to unsupported file type"
   */
  message: string;

  /**
   * Array of field-level validation error details (optional)
   * 
   * Required for VALIDATION_ERROR responses.
   * Provides specific information about which fields failed validation.
   * Helps clients display field-specific error messages in forms.
   * 
   * @example
   * [
   *   { field: "file_type", message: "Must be PDF, JPG, or PNG" },
   *   { field: "file_size", message: "Must not exceed 10MB" }
   * ]
   */
  details?: ErrorDetail[];

  /**
   * ISO 8601 timestamp when the error occurred (optional)
   * 
   * Use UTC timezone.
   * Helpful for debugging and correlating errors with logs.
   * 
   * @example "2025-10-31T12:00:00.000Z"
   */
  timestamp?: string;

  /**
   * API path where the error occurred (optional)
   * 
   * Include the full path including path parameters.
   * Helps identify which endpoint caused the error.
   * 
   * @example "/api/v1/documents"
   * @example "/api/v1/documents/123/fields"
   */
  path?: string;

  /**
   * Unique request identifier for tracking and debugging (optional)
   * 
   * Generated by the API gateway or logging interceptor.
   * Clients should include this ID when reporting issues to support.
   * Use format: "req_" + UUID or similar unique identifier.
   * 
   * @example "req_abc123def456"
   * @example "req_7f3a8c9d-4e2f-4b1a-9c8d-3e2f4b1a9c8d"
   */
  requestId?: string;

  /**
   * Stack trace for debugging (DEVELOPMENT ONLY - NEVER in production)
   * 
   * **CRITICAL SECURITY REQUIREMENT:**
   * - MUST be omitted in production environment
   * - ONLY include in development and staging environments
   * - NEVER expose internal code structure to clients
   * - Filter this field in global exception filter for production
   * 
   * @example "Error: Invalid document format\n    at DocumentService.validate (/app/src/documents/documents.service.ts:45:15)"
   */
  stackTrace?: string;
}

/**
 * Validation Error Response Type
 * 
 * Specialized type for validation error responses.
 * Enforces that validation errors must include the details array
 * with field-level error information.
 * 
 * Use this type when:
 * - Request data fails DTO validation (class-validator)
 * - Business logic validation fails
 * - File upload validation fails
 * 
 * HTTP Status: 400 Bad Request
 * 
 * Example:
 * ```typescript
 * const validationError: ValidationErrorResponse = {
 *   code: ErrorCode.VALIDATION_ERROR,
 *   message: "Request validation failed",
 *   details: [
 *     { field: "email", message: "Must be a valid email address" },
 *     { field: "password", message: "Must be at least 8 characters" }
 *   ],
 *   timestamp: new Date().toISOString(),
 *   path: "/api/v1/auth/register"
 * };
 * ```
 * 
 * @type ValidationErrorResponse
 */
export type ValidationErrorResponse = ErrorResponse & {
  code: ErrorCode.VALIDATION_ERROR;
  details: ErrorDetail[];
};

/**
 * Authentication Error Response Type
 * 
 * Specialized type for authentication error responses.
 * Used when authentication credentials are missing, invalid, or expired.
 * 
 * Use this type when:
 * - No authentication token provided
 * - JWT token expired
 * - JWT token invalid or malformed
 * - JWT signature verification fails
 * - API key is invalid
 * 
 * HTTP Status: 401 Unauthorized
 * 
 * Common messages:
 * - "Authentication required"
 * - "Invalid or expired authentication token"
 * - "Invalid API key"
 * 
 * Example:
 * ```typescript
 * const authError: AuthenticationErrorResponse = {
 *   code: ErrorCode.AUTHENTICATION_ERROR,
 *   message: "Invalid or expired authentication token",
 *   timestamp: new Date().toISOString(),
 *   path: "/api/v1/documents"
 * };
 * ```
 * 
 * @type AuthenticationErrorResponse
 */
export type AuthenticationErrorResponse = ErrorResponse & {
  code: ErrorCode.AUTHENTICATION_ERROR;
};

/**
 * Not Found Error Response Type
 * 
 * Specialized type for resource not found error responses.
 * Used when a requested resource (document, user, template, etc.) does not exist.
 * 
 * Use this type when:
 * - Document ID not found
 * - User ID not found
 * - Template ID not found
 * - Any other resource lookup fails
 * 
 * HTTP Status: 404 Not Found
 * 
 * Best practices:
 * - Be specific about what resource was not found
 * - Include the resource type and ID in the message
 * - Don't reveal whether resource exists if user lacks permissions
 * 
 * Example:
 * ```typescript
 * const notFoundError: NotFoundErrorResponse = {
 *   code: ErrorCode.NOT_FOUND,
 *   message: "Document with ID '123' not found",
 *   timestamp: new Date().toISOString(),
 *   path: "/api/v1/documents/123"
 * };
 * ```
 * 
 * @type NotFoundErrorResponse
 */
export type NotFoundErrorResponse = ErrorResponse & {
  code: ErrorCode.NOT_FOUND;
};
