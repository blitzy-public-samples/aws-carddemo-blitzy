import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

/**
 * Pagination Metadata DTO
 *
 * Provides standardized pagination response structure for all paginated API endpoints.
 * Supports both offset-based pagination (page/pageSize) and cursor-based pagination (nextCursor).
 * This ensures consistent pagination metadata across all API responses per Section 0.7.4
 * API Design Guidelines.
 *
 * @example
 * ```typescript
 * const metadata = PaginationMetadataDto.create({
 *   total: 1000,
 *   page: 1,
 *   pageSize: 25,
 *   nextCursor: 'abc123'
 * });
 * ```
 *
 * @example Response format
 * ```json
 * {
 *   "data": [...],
 *   "pagination": {
 *     "total": 1000,
 *     "page": 1,
 *     "page_size": 25,
 *     "total_pages": 40,
 *     "has_next": true,
 *     "next_cursor": "abc123"
 *   }
 * }
 * ```
 */
export class PaginationMetadataDto {
  /**
   * Total number of records available across all pages.
   * Used to calculate total pages and determine if pagination is needed.
   *
   * @example 1000
   */
  @ApiProperty({
    description: 'Total number of records available across all pages',
    example: 1000,
    type: Number,
    minimum: 0,
  })
  total!: number;

  /**
   * Current page number (1-indexed).
   * First page is 1, not 0.
   *
   * @example 1
   */
  @ApiProperty({
    description: 'Current page number (1-indexed)',
    example: 1,
    type: Number,
    minimum: 1,
  })
  page!: number;

  /**
   * Number of records per page.
   * Default: 25, Maximum: 100 per Section 0.7.4 API Design Guidelines.
   *
   * @example 25
   */
  @ApiProperty({
    description: 'Number of records per page',
    example: 25,
    type: Number,
    minimum: 1,
    maximum: 100,
  })
  pageSize!: number;

  /**
   * Total number of pages available.
   * Calculated as Math.ceil(total / pageSize).
   * Returns 0 if total is 0.
   *
   * @example 40
   */
  @ApiProperty({
    description: 'Total number of pages available',
    example: 40,
    type: Number,
    minimum: 0,
  })
  totalPages!: number;

  /**
   * Indicates whether there are more pages available after the current page.
   * Useful for "Load More" buttons and infinite scroll implementations.
   *
   * @example true
   */
  @ApiProperty({
    description: 'Indicates whether there are more pages available',
    example: true,
    type: Boolean,
  })
  hasNext!: boolean;

  /**
   * Optional cursor for cursor-based pagination.
   * Used for large datasets where offset-based pagination becomes inefficient.
   * The cursor is an opaque string that should be passed to the next request
   * to retrieve the next page of results.
   *
   * @example "abc123"
   */
  @ApiPropertyOptional({
    description:
      'Optional cursor for cursor-based pagination of large datasets',
    example: 'abc123',
    type: String,
    nullable: true,
  })
  nextCursor?: string;

  /**
   * Static factory method to create PaginationMetadataDto from query results.
   * Automatically calculates totalPages and hasNext based on provided values.
   *
   * @param params - Pagination parameters
   * @param params.total - Total number of records
   * @param params.page - Current page number (1-indexed)
   * @param params.pageSize - Number of records per page
   * @param params.nextCursor - Optional cursor for cursor-based pagination
   * @returns Fully constructed PaginationMetadataDto instance
   *
   * @example
   * ```typescript
   * const metadata = PaginationMetadataDto.create({
   *   total: 1000,
   *   page: 1,
   *   pageSize: 25
   * });
   * // Returns: { total: 1000, page: 1, pageSize: 25, totalPages: 40, hasNext: true }
   * ```
   *
   * @example With cursor
   * ```typescript
   * const metadata = PaginationMetadataDto.create({
   *   total: 1000,
   *   page: 1,
   *   pageSize: 25,
   *   nextCursor: 'cursor_abc123'
   * });
   * // Returns: { total: 1000, page: 1, pageSize: 25, totalPages: 40, hasNext: true, nextCursor: 'cursor_abc123' }
   * ```
   */
  static create(params: {
    total: number;
    page: number;
    pageSize: number;
    nextCursor?: string;
  }): PaginationMetadataDto {
    const metadata = new PaginationMetadataDto();

    metadata.total = params.total;
    metadata.page = params.page;
    metadata.pageSize = params.pageSize;

    // Calculate total pages: Math.ceil(total / pageSize)
    // Returns 0 if total is 0 to avoid division by zero edge case
    metadata.totalPages =
      params.total === 0 ? 0 : Math.ceil(params.total / params.pageSize);

    // Determine if there are more pages available
    // hasNext is true if current page is less than total pages
    metadata.hasNext = params.page < metadata.totalPages;

    // Include optional cursor for cursor-based pagination
    if (params.nextCursor !== undefined) {
      metadata.nextCursor = params.nextCursor;
    }

    return metadata;
  }
}
