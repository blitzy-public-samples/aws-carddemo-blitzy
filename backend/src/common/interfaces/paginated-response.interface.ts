/**
 * Pagination Interface Definitions
 * 
 * Provides TypeScript interfaces and enums for consistent pagination across all list endpoints.
 * Implements pagination standards per Section 0.7.4 API Design Guidelines.
 * 
 * Supports two pagination strategies:
 * - Offset-based: Traditional page number pagination for smaller datasets
 * - Cursor-based: Efficient pagination for large datasets using opaque cursors
 * 
 * Default page size: 25 items
 * Maximum page size: 100 items
 * 
 * @module common/interfaces/paginated-response
 */

/**
 * Pagination Type Enum
 * 
 * Defines the type of pagination being used in the response.
 * Allows clients to understand and handle pagination correctly.
 * 
 * @enum {string}
 * @readonly
 */
export enum PaginationType {
  /**
   * Offset-based pagination using page numbers
   * Best for: Smaller datasets, predictable page counts, UI with page numbers
   * Uses: page and pageSize parameters
   */
  OFFSET = 'offset',

  /**
   * Cursor-based pagination using opaque cursors
   * Best for: Large datasets, real-time data, infinite scroll UIs
   * Uses: cursor parameter instead of page number
   * Prevents issues with data insertion/deletion between requests
   */
  CURSOR = 'cursor',
}

/**
 * Paginated Response Interface
 * 
 * Standard pagination metadata structure returned with all list endpoints.
 * Provides comprehensive information for client-side navigation and UI rendering.
 * 
 * Usage Example (Offset-based):
 * ```typescript
 * {
 *   total: 1000,
 *   page: 1,
 *   pageSize: 25,
 *   totalPages: 40,
 *   hasNext: true,
 *   hasPrevious: false,
 *   nextCursor: null,
 *   previousCursor: null,
 *   type: PaginationType.OFFSET
 * }
 * ```
 * 
 * Usage Example (Cursor-based):
 * ```typescript
 * {
 *   total: 1000,
 *   page: 1,
 *   pageSize: 25,
 *   totalPages: 40,
 *   hasNext: true,
 *   hasPrevious: false,
 *   nextCursor: 'eyJpZCI6MTAwLCJ0aW1lc3RhbXAiOiIyMDI1LTEwLTMxVDEyOjAwOjAwWiJ9',
 *   previousCursor: null,
 *   type: PaginationType.CURSOR
 * }
 * ```
 * 
 * @interface PaginatedResponse
 */
export interface PaginatedResponse {
  /**
   * Total number of items across all pages
   * Useful for displaying "Showing X of Y results" and calculating progress
   * May be expensive to compute for very large datasets
   * 
   * @type {number}
   */
  total: number;

  /**
   * Current page number (1-indexed)
   * Used in offset-based pagination
   * For cursor-based pagination, this represents the relative page position
   * 
   * @type {number}
   * @default 1
   */
  page: number;

  /**
   * Number of items per page
   * Must be between 1 and 100 (inclusive)
   * 
   * @type {number}
   * @default 25
   * @min 1
   * @max 100
   */
  pageSize: number;

  /**
   * Total number of pages
   * Calculated as Math.ceil(total / pageSize)
   * Useful for rendering page number selectors
   * 
   * @type {number}
   */
  totalPages: number;

  /**
   * Whether there is a next page available
   * Clients should disable "Next" button when false
   * Always computed to handle edge cases (e.g., data changes between requests)
   * 
   * @type {boolean}
   */
  hasNext: boolean;

  /**
   * Whether there is a previous page available
   * Clients should disable "Previous" button when false
   * Will be false when page === 1 or at the beginning of cursor pagination
   * 
   * @type {boolean}
   */
  hasPrevious: boolean;

  /**
   * Opaque cursor for fetching the next page
   * Only populated in cursor-based pagination
   * Base64-encoded JSON containing position information (e.g., last item ID, timestamp)
   * Null when there is no next page
   * 
   * Client usage:
   * ```typescript
   * const response = await fetch(`/api/v1/documents?cursor=${nextCursor}`);
   * ```
   * 
   * @type {string | null}
   * @optional
   */
  nextCursor?: string | null;

  /**
   * Opaque cursor for fetching the previous page
   * Only populated in cursor-based pagination
   * Base64-encoded JSON containing position information
   * Null when there is no previous page
   * 
   * @type {string | null}
   * @optional
   */
  previousCursor?: string | null;

  /**
   * Type of pagination being used
   * Informs clients how to interpret pagination metadata
   * 
   * @type {PaginationType}
   * @default PaginationType.OFFSET
   * @optional
   */
  type?: PaginationType;
}

/**
 * Pagination Query Interface
 * 
 * Standard query parameters for list endpoints that support pagination.
 * All parameters are optional with sensible defaults.
 * 
 * Usage Example (Offset-based):
 * ```typescript
 * const query: PaginationQuery = {
 *   page: 2,
 *   pageSize: 50,
 *   sortBy: 'createdAt',
 *   sortOrder: 'desc'
 * };
 * // Fetches items 51-100, sorted by creation date (newest first)
 * ```
 * 
 * Usage Example (Cursor-based):
 * ```typescript
 * const query: PaginationQuery = {
 *   cursor: 'eyJpZCI6MTAwfQ==',
 *   pageSize: 25,
 *   sortBy: 'updatedAt',
 *   sortOrder: 'asc'
 * };
 * // Fetches next 25 items after cursor position, sorted by update date (oldest first)
 * ```
 * 
 * Validation Rules:
 * - page must be >= 1
 * - pageSize must be between 1 and 100
 * - If cursor is provided, page parameter is ignored
 * - sortBy should be a valid field name from the entity
 * - sortOrder must be either 'asc' or 'desc'
 * 
 * @interface PaginationQuery
 */
export interface PaginationQuery {
  /**
   * Page number for offset-based pagination
   * Ignored when cursor is provided
   * 
   * @type {number}
   * @default 1
   * @min 1
   * @optional
   */
  page?: number;

  /**
   * Number of items to return per page
   * Enforced maximum of 100 to prevent performance issues
   * 
   * @type {number}
   * @default 25
   * @min 1
   * @max 100
   * @optional
   */
  pageSize?: number;

  /**
   * Opaque cursor for cursor-based pagination
   * When provided, indicates continuation point for fetching next/previous page
   * Takes precedence over page parameter
   * 
   * Format: Base64-encoded JSON string
   * Example: 'eyJpZCI6MTAwLCJ0aW1lc3RhbXAiOiIyMDI1LTEwLTMxVDEyOjAwOjAwWiJ9'
   * 
   * @type {string}
   * @optional
   */
  cursor?: string;

  /**
   * Field name to sort results by
   * Should be a valid field from the entity being queried
   * Common values: 'createdAt', 'updatedAt', 'name', 'id'
   * 
   * Backend should validate that sortBy is an allowed field to prevent injection
   * 
   * @type {string}
   * @optional
   */
  sortBy?: string;

  /**
   * Sort direction
   * 
   * - 'asc': Ascending order (A-Z, 0-9, oldest first)
   * - 'desc': Descending order (Z-A, 9-0, newest first)
   * 
   * @type {'asc' | 'desc'}
   * @default 'desc'
   * @optional
   */
  sortOrder?: 'asc' | 'desc';
}

/**
 * Helper type for paginated data responses
 * Generic type that combines data array with pagination metadata
 * 
 * Usage:
 * ```typescript
 * interface DocumentListResponse {
 *   success: boolean;
 *   data: Document[];
 *   pagination: PaginatedResponse;
 * }
 * ```
 * 
 * @template T The type of items in the data array
 */
export interface PaginatedResult<T> {
  /**
   * Array of items for the current page
   */
  data: T[];

  /**
   * Pagination metadata
   */
  pagination: PaginatedResponse;
}
