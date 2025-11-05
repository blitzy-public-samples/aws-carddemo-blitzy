/**
 * Transform Interceptor
 * 
 * NestJS interceptor for standardizing all API response formats with consistent structure.
 * Transforms successful responses into standardized format following OpenAPI 3.0 specification.
 * 
 * Features:
 * - Wraps all successful responses in standard envelope with success flag, data, and metadata
 * - Includes ISO 8601 timestamp for response tracking
 * - Includes API version for backward compatibility
 * - Includes request ID for distributed tracing and debugging
 * - Preserves original data structure without modification
 * - Handles null, undefined, arrays, objects, and primitive types
 * 
 * Response Format:
 * {
 *   success: true,
 *   data: <original response data>,
 *   meta: {
 *     timestamp: "2025-10-31T12:00:00.000Z",
 *     version: "v1",
 *     requestId: "abc-123-def-456"
 *   }
 * }
 * 
 * Per Section 0.7.4 API Design Guidelines and Section 0.5.11 Phase 10 API Management.
 * 
 * @module TransformInterceptor
 */

import { randomUUID } from 'crypto';

import { Injectable, NestInterceptor, ExecutionContext, CallHandler } from '@nestjs/common';
import { Request } from 'express';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

/**
 * Standardized API response interface with generic type support.
 * 
 * Used for type-safe response typing in controllers and services,
 * ensuring consistent response format across all endpoints.
 * 
 * @template T - The type of the data payload
 * 
 * @property {boolean} success - Indicates successful response (always true for 2xx responses)
 * @property {T} data - The actual response payload with preserved structure
 * @property {object} meta - Metadata about the response
 * @property {string} meta.timestamp - ISO 8601 formatted timestamp of response generation
 * @property {string} meta.version - API version string for backward compatibility
 * @property {string} [meta.requestId] - Optional unique request identifier for tracing
 * 
 * @example
 * // Single document response
 * const response: Response<Document> = {
 *   success: true,
 *   data: { id: '123', name: 'Invoice.pdf', status: 'processed' },
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1',
 *     requestId: 'abc-123-def-456'
 *   }
 * };
 * 
 * @example
 * // Paginated response
 * const response: Response<{ items: Document[], pagination: PaginationMeta }> = {
 *   success: true,
 *   data: {
 *     items: [...],
 *     pagination: { total: 1000, page: 1, pageSize: 25, totalPages: 40 }
 *   },
 *   meta: {
 *     timestamp: '2025-10-31T12:00:00.000Z',
 *     version: 'v1'
 *   }
 * };
 */
export interface Response<T> {
  success: boolean;
  data: T;
  meta: {
    timestamp: string;
    version: string;
    requestId?: string;
  };
}

/**
 * Transform Interceptor
 * 
 * NestJS interceptor that standardizes all API responses by wrapping them in a
 * consistent envelope structure with success flag, data payload, and metadata.
 * 
 * This interceptor:
 * - Implements the NestInterceptor interface for integration with NestJS framework
 * - Uses RxJS map operator for response transformation
 * - Extracts request ID from execution context or generates new UUID
 * - Preserves original data structure while adding standardization
 * - Supports generic types for type-safe response handling
 * - Configurable API version via environment variable (default: 'v1')
 * 
 * The interceptor is stateless and thread-safe, making it suitable for
 * high-throughput production environments.
 * 
 * Usage:
 * Apply globally in main.ts:
 * ```typescript
 * app.useGlobalInterceptors(new TransformInterceptor());
 * ```
 * 
 * Or apply to specific controllers/routes:
 * ```typescript
 * @UseInterceptors(TransformInterceptor)
 * @Controller('documents')
 * export class DocumentsController { ... }
 * ```
 * 
 * Per Section 0.7.4 API Design Guidelines for consistent response format
 * and Section 0.7.2 NestJS Backend guidelines for interceptor patterns.
 * 
 * @implements {NestInterceptor<T, Response<T>>}
 */
@Injectable()
export class TransformInterceptor<T> implements NestInterceptor<T, Response<T>> {
  /**
   * API version string for response metadata.
   * Read from environment variable API_VERSION, defaults to 'v1'.
   * Used for backward compatibility tracking per Section 0.7.7.
   */
  private readonly apiVersion: string;

  /**
   * Constructor for TransformInterceptor.
   * 
   * Initializes API version from environment variable or uses default.
   * No other dependencies needed as this interceptor is stateless.
   */
  constructor() {
    this.apiVersion = process.env.API_VERSION || 'v1';
  }

  /**
   * Intercepts the request/response pipeline and transforms the response.
   * 
   * This method is called by NestJS for every request handled by controllers
   * where this interceptor is applied. It extracts request context, generates
   * or retrieves request ID, and transforms the response into standardized format.
   * 
   * Flow:
   * 1. Extract HTTP request from execution context
   * 2. Get request ID from header, request object, or generate new UUID
   * 3. Pass control to next handler in chain
   * 4. Apply RxJS map operator to transform response
   * 5. Wrap response in standard envelope with metadata
   * 6. Return transformed observable
   * 
   * The transformation preserves the original data structure completely,
   * only adding the standardization wrapper around it.
   * 
   * @param {ExecutionContext} context - NestJS execution context with request/response
   * @param {CallHandler} next - Next handler in the interceptor chain
   * @returns {Observable<Response<T>>} Observable of standardized response
   * 
   * @example
   * // Before transformation:
   * { id: '123', name: 'Invoice.pdf', status: 'processed' }
   * 
   * // After transformation:
   * {
   *   success: true,
   *   data: { id: '123', name: 'Invoice.pdf', status: 'processed' },
   *   meta: {
   *     timestamp: '2025-10-31T12:00:00.000Z',
   *     version: 'v1',
   *     requestId: 'abc-123-def-456'
   *   }
   * }
   */
  intercept(context: ExecutionContext, next: CallHandler): Observable<Response<T>> {
    // Extract HTTP request object from execution context
    const httpContext = context.switchToHttp();
    const request = httpContext.getRequest<Request & { requestId?: string }>();

    // Extract or generate request ID for tracing
    // Priority: X-Request-ID header > request.requestId property > generate new UUID
    const requestId = this.extractRequestId(request);

    // Pass control to next handler and transform the response
    return next.handle().pipe(
      map((data: T) => this.transformResponse(data, requestId))
    );
  }

  /**
   * Extracts request ID from the request object.
   * 
   * Attempts to find request ID in the following order:
   * 1. X-Request-ID custom header (standard for distributed tracing)
   * 2. request.requestId property (set by LoggingInterceptor)
   * 3. Generate new UUID using crypto.randomUUID()
   * 
   * This ensures consistent request tracking across the entire request lifecycle
   * and supports distributed tracing when request ID is propagated from upstream.
   * 
   * @param {Request & { requestId?: string }} request - Express request object with optional requestId property
   * @returns {string} Request ID (existing or newly generated)
   * 
   * @private
   */
  private extractRequestId(request: Request & { requestId?: string }): string {
    // Check for request ID in custom header (standard for distributed tracing)
    const headerRequestId = request.headers['x-request-id'];
    if (headerRequestId) {
      // Handle both string and string[] types from headers
      const id = Array.isArray(headerRequestId) ? headerRequestId[0] : headerRequestId;
      if (id) {
        return id;
      }
    }

    // Check for request ID set by LoggingInterceptor or other middleware
    if (request.requestId) {
      return request.requestId;
    }

    // Generate new UUID if no request ID found
    return randomUUID();
  }

  /**
   * Transforms the original response data into standardized format.
   * 
   * Wraps the original response data in a standard envelope with:
   * - success: boolean flag (always true for successful responses)
   * - data: original response payload (completely preserved)
   * - meta: metadata object with timestamp, version, and request ID
   * 
   * This method preserves the original data structure completely:
   * - null/undefined values are preserved
   * - Arrays remain arrays
   * - Objects retain all properties
   * - Primitive types are wrapped as-is
   * 
   * The transformation is pure and does not modify the original data.
   * 
   * @param {T} data - Original response data from controller
   * @param {string} requestId - Unique request identifier
   * @returns {Response<T>} Standardized response object
   * 
   * @private
   */
  private transformResponse(data: T, requestId: string): Response<T> {
    return {
      success: true,
      data,
      meta: {
        timestamp: new Date().toISOString(),
        version: this.apiVersion,
        requestId
      }
    };
  }
}
