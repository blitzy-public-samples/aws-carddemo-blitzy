/**
 * LoggingInterceptor
 * 
 * NestJS interceptor for comprehensive structured HTTP request/response logging.
 * Captures request ID, HTTP method, URL path, query parameters, request headers,
 * status code, response time, user context from JWT, and error details.
 * 
 * Implements:
 * - Unique request ID generation using UUID
 * - High-resolution response time measurement
 * - User context extraction from JWT tokens
 * - Structured JSON logging with Winston
 * - Error capture with stack traces
 * - Security-conscious data sanitization
 * 
 * Per Section 0.7.2 NestJS Backend guidelines and Section 0.7.5 Monitoring requirements
 * 
 * @module LoggingInterceptor
 */

import { randomUUID } from 'crypto';

import {
  Injectable,
  NestInterceptor,
  ExecutionContext,
  CallHandler,
  Inject,
} from '@nestjs/common';
import { Request, Response } from 'express';
import { Observable, throwError } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';
import { Logger } from 'winston';

/**
 * Interface for JWT payload structure
 * Used for extracting user context from Authorization header
 */
interface JwtPayload {
  sub?: string; // User ID
  userId?: string; // Alternative user ID field
  accountId?: string; // Tenant/account ID for multi-tenant isolation
  email?: string; // User email
  iat?: number; // Issued at timestamp
  exp?: number; // Expiration timestamp
}

/**
 * Interface for structured log entry
 */
interface LogEntry {
  level: string;
  message: string;
  requestId: string;
  method: string;
  url: string;
  query?: Record<string, unknown>;
  userAgent?: string;
  contentType?: string;
  userId?: string;
  accountId?: string;
  statusCode?: number;
  responseTime?: number;
  error?: {
    name: string;
    message: string;
    stack?: string;
  };
  timestamp: string;
}

/**
 * LoggingInterceptor
 * 
 * Intercepts all HTTP requests and responses to provide comprehensive logging
 * for debugging, monitoring, performance analysis, and security auditing.
 * 
 * Usage:
 * - Apply globally in main.ts: app.useGlobalInterceptors(new LoggingInterceptor(logger))
 * - Or apply per controller: @UseInterceptors(LoggingInterceptor)
 * 
 * @implements {NestInterceptor}
 */
@Injectable()
export class LoggingInterceptor implements NestInterceptor {
  /**
   * Constructor with Winston logger dependency injection
   * 
   * @param {Logger} logger - Winston logger instance injected from DI container
   */
  constructor(
    @Inject('winston') private readonly logger: Logger,
  ) {}

  /**
   * Intercept method - core interceptor implementation
   * 
   * Captures incoming request details, measures execution time, logs response/errors,
   * and extracts user context for audit trail.
   * 
   * @param {ExecutionContext} context - NestJS execution context with request/response
   * @param {CallHandler} next - Call handler for continuing request processing
   * @returns {Observable<unknown>} Observable with tap/catchError operators for logging
   */
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    // Generate unique request ID for tracking
    const requestId = randomUUID();

    // Extract HTTP context
    const httpContext = context.switchToHttp();
    const request = httpContext.getRequest<Request>();
    const response = httpContext.getResponse<Response>();

    // Extract request details
    const method = request.method;
    const url = request.url;
    const query = request.query;
    const userAgent = request.headers['user-agent'];
    const contentType = request.headers['content-type'];

    // Extract user context from JWT token
    const userContext = this.extractUserContext(request);

    // Record start time with high-resolution timer (nanoseconds)
    const startTime = process.hrtime.bigint();

    // Log incoming request
    this.logRequest({
      level: 'info',
      message: 'Incoming request',
      requestId,
      method,
      url,
      query: this.sanitizeQueryParams(query),
      userAgent,
      contentType,
      userId: userContext.userId,
      accountId: userContext.accountId,
      timestamp: new Date().toISOString(),
    });

    // Continue with request processing and handle response/errors
    return next.handle().pipe(
      tap(() => {
        // Calculate response time in milliseconds
        const endTime = process.hrtime.bigint();
        const responseTime = Number(endTime - startTime) / 1_000_000; // Convert nanoseconds to milliseconds

        // Extract status code from response
        const statusCode = response.statusCode;

        // Log successful response
        this.logResponse({
          level: 'info',
          message: 'Request completed',
          requestId,
          method,
          url,
          statusCode,
          responseTime: Math.round(responseTime * 100) / 100, // Round to 2 decimal places
          userId: userContext.userId,
          accountId: userContext.accountId,
          timestamp: new Date().toISOString(),
        });
      }),
      catchError((error: unknown) => {
        // Calculate response time even for errors
        const endTime = process.hrtime.bigint();
        const responseTime = Number(endTime - startTime) / 1_000_000;

        // Extract error details - safely handle unknown error type
        const err = error as { status?: number; statusCode?: number; name?: string; message?: string; stack?: string };
        const statusCode = err.status || err.statusCode || 500;
        const errorName = err.name || 'Error';
        const errorMessage = err.message || 'Unknown error';
        const errorStack = err.stack;

        // Log error with full details
        this.logError({
          level: 'error',
          message: 'Request failed',
          requestId,
          method,
          url,
          statusCode,
          error: {
            name: errorName,
            message: this.sanitizeErrorMessage(errorMessage),
            stack: this.sanitizeStackTrace(errorStack),
          },
          responseTime: Math.round(responseTime * 100) / 100,
          userId: userContext.userId,
          accountId: userContext.accountId,
          timestamp: new Date().toISOString(),
        });

        // Re-throw error to allow HttpExceptionFilter to handle it
        return throwError(() => error);
      }),
    );
  }

  /**
   * Extract user context from JWT token in Authorization header
   * 
   * Parses the JWT token (without full verification since that's done by JwtStrategy)
   * to extract user ID and account ID for logging purposes.
   * 
   * @param {Request} request - Express request object
   * @returns {{ userId?: string; accountId?: string }} User context object
   * @private
   */
  private extractUserContext(request: Request): { userId?: string; accountId?: string } {
    try {
      const authHeader = request.headers.authorization;

      if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return {};
      }

      // Extract token (remove 'Bearer ' prefix)
      const token = authHeader.substring(7);

      // Basic JWT parsing (split by '.' and decode payload)
      const parts = token.split('.');
      if (parts.length !== 3) {
        return {};
      }

      // Decode payload (base64url decode)
      const payload = parts[1];
      if (!payload) {
        return {};
      }
      const decoded = Buffer.from(payload, 'base64').toString('utf-8');
      const payloadObj = JSON.parse(decoded) as JwtPayload;

      // Extract user ID (support multiple field names)
      const userId = payloadObj.sub || payloadObj.userId;
      const accountId = payloadObj.accountId;

      return {
        userId,
        accountId,
      };
    } catch (error) {
      // If token parsing fails, return empty context (token might be invalid)
      // This is acceptable since JWT validation happens in JwtStrategy
      return {};
    }
  }

  /**
   * Sanitize query parameters to remove sensitive data
   * 
   * Removes or masks sensitive query parameters like tokens, passwords, API keys
   * to prevent leaking sensitive information in logs.
   * 
   * @param {unknown} query - Query parameters object
   * @returns {Record<string, unknown>} Sanitized query parameters
   * @private
   */
  private sanitizeQueryParams(query: unknown): Record<string, unknown> {
    if (!query || typeof query !== 'object') {
      return {};
    }

    const sanitized: Record<string, unknown> = {};
    const sensitiveKeys = ['password', 'token', 'api_key', 'apiKey', 'secret', 'authorization'];

    for (const [key, value] of Object.entries(query)) {
      const lowerKey = key.toLowerCase();
      const isSensitive = sensitiveKeys.some((sensitive) => lowerKey.includes(sensitive));

      if (isSensitive) {
        // eslint-disable-next-line security/detect-object-injection -- Safe: key comes from Object.entries iteration
        sanitized[key] = '[REDACTED]';
      } else {
        // eslint-disable-next-line security/detect-object-injection -- Safe: key comes from Object.entries iteration
        sanitized[key] = value;
      }
    }

    return sanitized;
  }

  /**
   * Sanitize error message to remove sensitive information
   * 
   * Removes potential sensitive data like file paths, credentials, tokens
   * from error messages before logging.
   * 
   * @param {string} message - Original error message
   * @returns {string} Sanitized error message
   * @private
   */
  private sanitizeErrorMessage(message: string): string {
    if (!message) {
      return 'Unknown error';
    }

    // Remove file system paths (Linux/Windows)
    let sanitized = message.replace(/\/[^\s]+/g, '[PATH]');
    sanitized = sanitized.replace(/[A-Z]:\\[^\s]+/g, '[PATH]');

    // Remove potential credentials in connection strings
    sanitized = sanitized.replace(/password=[^\s;]+/gi, 'password=[REDACTED]');
    sanitized = sanitized.replace(/pwd=[^\s;]+/gi, 'pwd=[REDACTED]');

    // Remove potential tokens
    sanitized = sanitized.replace(/Bearer\s+[A-Za-z0-9-._~+/]+=*/g, 'Bearer [REDACTED]');

    return sanitized;
  }

  /**
   * Sanitize stack trace to remove sensitive information
   * 
   * Removes absolute file paths and limits stack trace length
   * to prevent exposing internal directory structure.
   * 
   * @param {string} stack - Original stack trace
   * @returns {string | undefined} Sanitized stack trace
   * @private
   */
  private sanitizeStackTrace(stack?: string): string | undefined {
    if (!stack) {
      return undefined;
    }

    // Split stack trace into lines
    const lines = stack.split('\n');

    // Take first 10 lines to limit log size
    const limitedLines = lines.slice(0, 10);

    // Remove absolute paths, keep relative paths
    const sanitizedLines = limitedLines.map((line) => {
      // Replace absolute paths with relative paths
      return line.replace(/\/[^\s)]+\/backend\//g, 'backend/');
    });

    return sanitizedLines.join('\n');
  }

  /**
   * Log request details at INFO level
   * 
   * @param {Partial<LogEntry>} entry - Log entry with request details
   * @private
   */
  private logRequest(entry: Partial<LogEntry>): void {
    this.logger.info(entry.message || 'Request', {
      ...entry,
      type: 'http_request',
    });
  }

  /**
   * Log response details at INFO level
   * 
   * @param {Partial<LogEntry>} entry - Log entry with response details
   * @private
   */
  private logResponse(entry: Partial<LogEntry>): void {
    this.logger.info(entry.message || 'Response', {
      ...entry,
      type: 'http_response',
    });
  }

  /**
   * Log error details at ERROR level
   * 
   * @param {Partial<LogEntry>} entry - Log entry with error details
   * @private
   */
  private logError(entry: Partial<LogEntry>): void {
    this.logger.error(entry.message || 'Error', {
      ...entry,
      type: 'http_error',
    });
  }
}
