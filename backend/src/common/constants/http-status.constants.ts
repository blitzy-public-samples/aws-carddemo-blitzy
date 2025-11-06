/**
 * HTTP Status Code Constants
 * 
 * This module provides standardized HTTP status codes and descriptive messages
 * for consistent API responses throughout the OCR Processing Application backend.
 * 
 * Aligns with Section 0.7.4 API Design Guidelines:
 * - 200 OK - Successful GET, PUT, PATCH
 * - 201 Created - Successful POST
 * - 204 No Content - Successful DELETE
 * - 400 Bad Request - Validation error
 * - 401 Unauthorized - Missing/invalid auth
 * - 403 Forbidden - Insufficient permissions
 * - 404 Not Found - Resource doesn't exist
 * - 409 Conflict - Resource conflict
 * - 429 Too Many Requests - Rate limit exceeded
 * - 500 Internal Server Error - Unexpected error
 * 
 * @module http-status.constants
 */

// ============================================================================
// HTTP 2xx Success Status Codes
// ============================================================================

/**
 * HTTP 200 OK
 * 
 * Use for successful GET, PUT, and PATCH requests.
 * Indicates that the request has succeeded and the response contains the requested data.
 * 
 * @example
 * // Successful document retrieval
 * return res.status(HTTP_STATUS_OK).json({ data: document });
 */
export const HTTP_STATUS_OK = 200 as const;

/**
 * HTTP 201 Created
 * 
 * Use for successful POST requests that create a new resource.
 * Indicates that the request has succeeded and a new resource has been created.
 * 
 * @example
 * // Successful document upload
 * return res.status(HTTP_STATUS_CREATED).json({ data: newDocument });
 */
export const HTTP_STATUS_CREATED = 201 as const;

/**
 * HTTP 202 Accepted
 * 
 * Use for requests that have been accepted for processing but not yet completed.
 * Typically used for asynchronous operations like batch processing or OCR jobs.
 * 
 * @example
 * // Batch processing job accepted
 * return res.status(HTTP_STATUS_ACCEPTED).json({ data: { jobId: '123' } });
 */
export const HTTP_STATUS_ACCEPTED = 202 as const;

/**
 * HTTP 204 No Content
 * 
 * Use for successful DELETE requests or updates that don't return data.
 * Indicates that the request succeeded but there is no response body.
 * 
 * @example
 * // Successful document deletion
 * return res.status(HTTP_STATUS_NO_CONTENT).send();
 */
export const HTTP_STATUS_NO_CONTENT = 204 as const;

// ============================================================================
// HTTP 4xx Client Error Status Codes
// ============================================================================

/**
 * HTTP 400 Bad Request
 * 
 * Use when the request contains invalid parameters or malformed syntax.
 * Indicates a validation error or incorrect request format.
 * 
 * @example
 * // Invalid document format
 * throw new BadRequestException('Invalid document format');
 */
export const HTTP_STATUS_BAD_REQUEST = 400 as const;

/**
 * HTTP 401 Unauthorized
 * 
 * Use when authentication is required but missing or invalid.
 * Indicates that the request requires valid authentication credentials.
 * 
 * @example
 * // Missing or expired JWT token
 * throw new UnauthorizedException('Authentication required');
 */
export const HTTP_STATUS_UNAUTHORIZED = 401 as const;

/**
 * HTTP 403 Forbidden
 * 
 * Use when the authenticated user lacks sufficient permissions.
 * Indicates that the user is authenticated but not authorized for this resource.
 * 
 * @example
 * // User without admin role trying to access admin endpoint
 * throw new ForbiddenException('Insufficient permissions');
 */
export const HTTP_STATUS_FORBIDDEN = 403 as const;

/**
 * HTTP 404 Not Found
 * 
 * Use when the requested resource does not exist.
 * Indicates that the server cannot find the requested resource.
 * 
 * @example
 * // Document with specified ID not found
 * throw new NotFoundException('Document not found');
 */
export const HTTP_STATUS_NOT_FOUND = 404 as const;

/**
 * HTTP 409 Conflict
 * 
 * Use when there is a conflict with the current state of the resource.
 * Typically used for duplicate resources or conflicting updates.
 * 
 * @example
 * // Attempting to create a template with duplicate name
 * throw new ConflictException('Template name already exists');
 */
export const HTTP_STATUS_CONFLICT = 409 as const;

/**
 * HTTP 422 Unprocessable Entity
 * 
 * Use when the request is well-formed but contains semantic errors.
 * Indicates that validation rules failed on the business logic level.
 * 
 * @example
 * // Document field validation failed
 * throw new UnprocessableEntityException('Field validation failed');
 */
export const HTTP_STATUS_UNPROCESSABLE_ENTITY = 422 as const;

/**
 * HTTP 429 Too Many Requests
 * 
 * Use when the client has exceeded the rate limit.
 * Indicates that the user has sent too many requests in a given time period.
 * 
 * @example
 * // API rate limit exceeded (default: 1000 requests/hour per account)
 * throw new TooManyRequestsException('Rate limit exceeded');
 */
export const HTTP_STATUS_TOO_MANY_REQUESTS = 429 as const;

// ============================================================================
// HTTP 5xx Server Error Status Codes
// ============================================================================

/**
 * HTTP 500 Internal Server Error
 * 
 * Use for unexpected server errors that don't fit other categories.
 * Indicates a generic server error that prevented request fulfillment.
 * 
 * @example
 * // Unexpected exception during processing
 * throw new InternalServerErrorException('An unexpected error occurred');
 */
export const HTTP_STATUS_INTERNAL_SERVER_ERROR = 500 as const;

/**
 * HTTP 502 Bad Gateway
 * 
 * Use when an upstream service returns an invalid response.
 * Indicates that the server received an invalid response from an upstream server.
 * 
 * @example
 * // OCR service returned invalid response
 * throw new BadGatewayException('Invalid response from OCR service');
 */
export const HTTP_STATUS_BAD_GATEWAY = 502 as const;

/**
 * HTTP 503 Service Unavailable
 * 
 * Use when the service is temporarily unavailable.
 * Indicates that the server is not ready to handle the request (maintenance, overload).
 * 
 * @example
 * // Database connection pool exhausted
 * throw new ServiceUnavailableException('Service temporarily unavailable');
 */
export const HTTP_STATUS_SERVICE_UNAVAILABLE = 503 as const;

// ============================================================================
// Grouped HttpStatus Object
// ============================================================================

/**
 * HttpStatus Object
 * 
 * A grouped export of all HTTP status codes for convenient access.
 * Provides both the numeric codes and descriptive messages.
 * 
 * @example
 * // Using grouped object
 * return res.status(HttpStatus.OK).json({ data });
 * 
 * // Using individual constant (recommended for tree-shaking)
 * return res.status(HTTP_STATUS_OK).json({ data });
 */
export const HttpStatus = {
  /**
   * 200 OK - Request succeeded
   */
  OK: HTTP_STATUS_OK,
  
  /**
   * 201 Created - Resource created successfully
   */
  CREATED: HTTP_STATUS_CREATED,
  
  /**
   * 202 Accepted - Request accepted for processing
   */
  ACCEPTED: HTTP_STATUS_ACCEPTED,
  
  /**
   * 204 No Content - Request succeeded with no response body
   */
  NO_CONTENT: HTTP_STATUS_NO_CONTENT,
  
  /**
   * 400 Bad Request - Invalid request parameters
   */
  BAD_REQUEST: HTTP_STATUS_BAD_REQUEST,
  
  /**
   * 401 Unauthorized - Authentication required
   */
  UNAUTHORIZED: HTTP_STATUS_UNAUTHORIZED,
  
  /**
   * 403 Forbidden - Insufficient permissions
   */
  FORBIDDEN: HTTP_STATUS_FORBIDDEN,
  
  /**
   * 404 Not Found - Resource does not exist
   */
  NOT_FOUND: HTTP_STATUS_NOT_FOUND,
  
  /**
   * 409 Conflict - Resource conflict
   */
  CONFLICT: HTTP_STATUS_CONFLICT,
  
  /**
   * 422 Unprocessable Entity - Validation failed
   */
  UNPROCESSABLE_ENTITY: HTTP_STATUS_UNPROCESSABLE_ENTITY,
  
  /**
   * 429 Too Many Requests - Rate limit exceeded
   */
  TOO_MANY_REQUESTS: HTTP_STATUS_TOO_MANY_REQUESTS,
  
  /**
   * 500 Internal Server Error - Unexpected error occurred
   */
  INTERNAL_SERVER_ERROR: HTTP_STATUS_INTERNAL_SERVER_ERROR,
  
  /**
   * 502 Bad Gateway - Invalid response from upstream server
   */
  BAD_GATEWAY: HTTP_STATUS_BAD_GATEWAY,
  
  /**
   * 503 Service Unavailable - Service temporarily unavailable
   */
  SERVICE_UNAVAILABLE: HTTP_STATUS_SERVICE_UNAVAILABLE,
} as const;

// ============================================================================
// Type Definitions
// ============================================================================

/**
 * Type representing all valid HTTP status code values
 */
export type HttpStatusCode = typeof HTTP_STATUS_OK
  | typeof HTTP_STATUS_CREATED
  | typeof HTTP_STATUS_ACCEPTED
  | typeof HTTP_STATUS_NO_CONTENT
  | typeof HTTP_STATUS_BAD_REQUEST
  | typeof HTTP_STATUS_UNAUTHORIZED
  | typeof HTTP_STATUS_FORBIDDEN
  | typeof HTTP_STATUS_NOT_FOUND
  | typeof HTTP_STATUS_CONFLICT
  | typeof HTTP_STATUS_UNPROCESSABLE_ENTITY
  | typeof HTTP_STATUS_TOO_MANY_REQUESTS
  | typeof HTTP_STATUS_INTERNAL_SERVER_ERROR
  | typeof HTTP_STATUS_BAD_GATEWAY
  | typeof HTTP_STATUS_SERVICE_UNAVAILABLE;

/**
 * Type representing the HttpStatus constant object
 */
export type HttpStatusType = typeof HttpStatus;
