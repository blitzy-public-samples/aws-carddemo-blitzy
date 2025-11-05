/**
 * Global HTTP Exception Filter
 * 
 * This filter intercepts all HttpException instances thrown across the application
 * and transforms them into a consistent JSON response format. It provides:
 * - Standardized error response structure per Section 0.7.4 API Design Guidelines
 * - Proper HTTP status code handling (400, 401, 403, 404, 409, 429, 500)
 * - Comprehensive error logging with request context
 * - Security-compliant stack trace exclusion in production
 * - Detailed validation error information for client debugging
 * 
 * Usage:
 * This filter is registered globally in main.ts using app.useGlobalFilters()
 * and automatically catches all HttpException instances throughout the application.
 * 
 * Error Response Format:
 * {
 *   success: false,
 *   error: {
 *     code: "ERROR_CODE",
 *     message: "Human-readable error message",
 *     details: [...] // Optional array of detailed validation errors
 *   },
 *   meta: {
 *     timestamp: "ISO 8601 timestamp",
 *     version: "API version"
 *   }
 * }
 * 
 * @see Section 0.7.4 - API Design Guidelines
 * @see Section 0.7.1 - Security Requirements (stack trace exclusion)
 * @see Section 0.7.2 - NestJS Backend Guidelines
 */

import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import { Request, Response } from 'express';

/**
 * Interface for structured error response
 */
interface ErrorResponse {
  success: false;
  error: {
    code: string;
    message: string;
    details?: Array<{
      field?: string;
      message: string;
      constraint?: string;
    }>;
    stack?: string;
  };
  meta: {
    timestamp: string;
    version: string;
  };
}

/**
 * Interface for validation error details
 */
interface ValidationErrorDetail {
  field?: string;
  message: string;
  constraint?: string;
}

/**
 * Global exception filter that catches all HttpException instances
 * and formats them into standardized error responses.
 * 
 * This filter ensures consistent error handling across the entire application
 * and provides comprehensive logging for monitoring and debugging purposes.
 * 
 * @implements {ExceptionFilter}
 */
@Catch(HttpException)
export class HttpExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(HttpExceptionFilter.name);

  /**
   * Catches and processes HttpException instances, formatting them into
   * standardized error responses and logging them with appropriate context.
   * 
   * @param {HttpException} exception - The caught HTTP exception
   * @param {ArgumentsHost} host - The arguments host providing access to request/response
   */
  catch(exception: HttpException, host: ArgumentsHost): void {
    // Extract HTTP context and response object from ArgumentsHost
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();

    // Determine HTTP status code from exception (default to 500 for unknown errors)
    const status = this.getStatusCode(exception);

    // Extract error message and details from exception
    const exceptionResponse = this.getExceptionResponse(exception);
    const errorMessage = this.getErrorMessage(exceptionResponse);
    const errorDetails = this.getErrorDetails(exceptionResponse, status);

    // Map HTTP status code to error code string
    const errorCode = this.getErrorCode(status);

    // Build standardized error response
    const errorResponse: ErrorResponse = {
      success: false,
      error: {
        code: errorCode,
        message: errorMessage,
        ...(errorDetails.length > 0 && { details: errorDetails }),
      },
      meta: {
        timestamp: new Date().toISOString(),
        version: 'v1',
      },
    };

    // Include stack trace only in development environment (security requirement)
    if (process.env.NODE_ENV === 'development') {
      errorResponse.error.stack = exception.stack;
    }

    // Log error with comprehensive context for monitoring
    this.logError(exception, request, status, errorMessage);

    // Send formatted error response
    response.status(status).json(errorResponse);
  }

  /**
   * Extracts the HTTP status code from the exception.
   * Defaults to 500 if status cannot be determined.
   * 
   * @param {HttpException} exception - The HTTP exception
   * @returns {number} The HTTP status code
   */
  private getStatusCode(exception: HttpException): number {
    try {
      return exception.getStatus();
    } catch (error) {
      // If getStatus() fails, default to Internal Server Error
      this.logger.warn('Unable to extract status code from exception, defaulting to 500');
      return HttpStatus.INTERNAL_SERVER_ERROR;
    }
  }

  /**
   * Extracts the response object from the exception.
   * Handles various exception response formats safely.
   * 
   * @param {HttpException} exception - The HTTP exception
   * @returns {any} The exception response object
   */
  private getExceptionResponse(exception: HttpException): any {
    try {
      return exception.getResponse();
    } catch (error) {
      // If getResponse() fails, return a generic error object
      this.logger.warn('Unable to extract response from exception');
      return {
        message: 'An unexpected error occurred',
      };
    }
  }

  /**
   * Extracts a human-readable error message from the exception response.
   * Handles string messages, object messages, and arrays of messages.
   * 
   * @param {any} exceptionResponse - The exception response object
   * @returns {string} The error message
   */
  private getErrorMessage(exceptionResponse: any): string {
    if (typeof exceptionResponse === 'string') {
      return exceptionResponse;
    }

    if (typeof exceptionResponse === 'object' && exceptionResponse !== null) {
      // Handle message as string
      if (typeof exceptionResponse.message === 'string') {
        return exceptionResponse.message;
      }

      // Handle message as array (common in validation errors)
      if (Array.isArray(exceptionResponse.message)) {
        // Return first message or join multiple messages
        return exceptionResponse.message.length > 0
          ? exceptionResponse.message[0]
          : 'Validation failed';
      }

      // Handle error property
      if (typeof exceptionResponse.error === 'string') {
        return exceptionResponse.error;
      }
    }

    // Fallback message
    return 'An error occurred';
  }

  /**
   * Extracts detailed error information, particularly for validation errors.
   * For 400 Bad Request errors, extracts field-level validation details.
   * 
   * @param {any} exceptionResponse - The exception response object
   * @param {number} status - The HTTP status code
   * @returns {ValidationErrorDetail[]} Array of validation error details
   */
  private getErrorDetails(
    exceptionResponse: any,
    status: number,
  ): ValidationErrorDetail[] {
    const details: ValidationErrorDetail[] = [];

    // Extract validation error details for 400 Bad Request responses
    if (status === HttpStatus.BAD_REQUEST && typeof exceptionResponse === 'object' && exceptionResponse !== null) {
      // Handle class-validator error format
      if (Array.isArray(exceptionResponse.message)) {
        exceptionResponse.message.forEach((msg: any) => {
          if (typeof msg === 'string') {
            details.push({ message: msg });
          } else if (typeof msg === 'object' && msg !== null) {
            // Handle structured validation error objects
            details.push({
              field: msg.property || msg.field,
              message: msg.message || msg.error || String(msg),
              constraint: msg.constraint || msg.constraints,
            });
          }
        });
      }

      // Handle errors array property
      if (Array.isArray(exceptionResponse.errors)) {
        exceptionResponse.errors.forEach((error: any) => {
          if (typeof error === 'string') {
            details.push({ message: error });
          } else if (typeof error === 'object' && error !== null) {
            details.push({
              field: error.property || error.field,
              message: error.message || String(error),
              constraint: error.constraint,
            });
          }
        });
      }

      // Handle details array property (custom validation format)
      if (Array.isArray(exceptionResponse.details)) {
        exceptionResponse.details.forEach((detail: any) => {
          if (typeof detail === 'object' && detail !== null) {
            details.push({
              field: detail.field,
              message: detail.message,
              constraint: detail.constraint,
            });
          }
        });
      }
    }

    return details;
  }

  /**
   * Maps HTTP status codes to standardized error code strings.
   * Follows the error code conventions defined in Section 0.7.4.
   * 
   * @param {number} status - The HTTP status code
   * @returns {string} The error code string
   */
  private getErrorCode(status: number): string {
    switch (status) {
      case HttpStatus.BAD_REQUEST:
        return 'VALIDATION_ERROR';
      case HttpStatus.UNAUTHORIZED:
        return 'UNAUTHORIZED';
      case HttpStatus.FORBIDDEN:
        return 'FORBIDDEN';
      case HttpStatus.NOT_FOUND:
        return 'NOT_FOUND';
      case HttpStatus.CONFLICT:
        return 'CONFLICT';
      case HttpStatus.TOO_MANY_REQUESTS:
        return 'RATE_LIMIT_EXCEEDED';
      case HttpStatus.INTERNAL_SERVER_ERROR:
        return 'INTERNAL_SERVER_ERROR';
      default:
        // For any other status codes, use a generic error code
        return status >= 500 ? 'INTERNAL_SERVER_ERROR' : 'BAD_REQUEST';
    }
  }

  /**
   * Logs error information with comprehensive context for monitoring and debugging.
   * Includes HTTP method, URL, status code, error message, and stack trace (development only).
   * 
   * @param {HttpException} exception - The HTTP exception
   * @param {Request} request - The Express request object
   * @param {number} status - The HTTP status code
   * @param {string} message - The error message
   */
  private logError(
    exception: HttpException,
    request: Request,
    status: number,
    message: string,
  ): void {
    const logContext = {
      method: request.method,
      url: request.url,
      status,
      message,
      userAgent: request.get('user-agent') || 'unknown',
      ip: request.ip || request.connection.remoteAddress,
    };

    // Log with appropriate level based on status code
    if (status >= 500) {
      // Server errors are logged as errors with stack trace in development
      this.logger.error(
        `HTTP ${status} Error: ${message}`,
        process.env.NODE_ENV !== 'production' ? exception.stack : undefined,
        JSON.stringify(logContext),
      );
    } else if (status >= 400) {
      // Client errors are logged as warnings
      this.logger.warn(`HTTP ${status} Client Error: ${message}`, JSON.stringify(logContext));
    } else {
      // Other status codes logged as info
      this.logger.log(`HTTP ${status}: ${message}`, JSON.stringify(logContext));
    }
  }
}
