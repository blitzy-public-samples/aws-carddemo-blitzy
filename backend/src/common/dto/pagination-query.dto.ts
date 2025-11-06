/**
 * Pagination Query DTO
 * 
 * Base pagination query DTO providing reusable pagination parameters for all paginated
 * API endpoints throughout the backend application. This DTO ensures consistent pagination
 * behavior across the application per Section 0.7.4 API Design Guidelines.
 * 
 * Features:
 * - Standardized pagination parameters (page, pageSize, sortBy, sortOrder)
 * - Input validation with class-validator decorators (Section 0.7.1 Security Requirements)
 * - Type conversion with class-transformer decorators
 * - OpenAPI/Swagger documentation with @ApiPropertyOptional
 * - Default values aligned with API standards (default page size: 25, max: 100)
 * 
 * Usage Example:
 * ```typescript
 * @Get('/documents')
 * findAll(@Query() paginationQuery: PaginationQueryDto) {
 *   // paginationQuery.page, paginationQuery.pageSize, etc.
 * }
 * ```
 * 
 * @see Section 0.7.4 API Design Guidelines
 * @see Section 0.7.2 NestJS Backend Guidelines
 */

import { IsOptional, IsInt, Min, Max, IsString, IsEnum } from 'class-validator';
import { Type } from 'class-transformer';
import { ApiPropertyOptional } from '@nestjs/swagger';

/**
 * Sort Order Enum
 * 
 * Defines valid sorting directions for pagination queries.
 * Restricts sorting to only ascending or descending order to prevent
 * invalid sort directions and potential SQL injection attempts.
 * 
 * @see Section 0.7.1 Security Requirements - Input validation and sanitization
 */
export enum SortOrder {
  /** Ascending sort order (A-Z, 0-9, oldest-newest) */
  ASC = 'ASC',
  /** Descending sort order (Z-A, 9-0, newest-oldest) */
  DESC = 'DESC',
}

/**
 * Pagination Query DTO
 * 
 * Data Transfer Object for pagination, sorting, and filtering query parameters.
 * Provides a reusable base class for all paginated API endpoints ensuring
 * consistent pagination behavior across the application.
 * 
 * All properties are optional with sensible defaults:
 * - page: 1 (first page)
 * - pageSize: 25 (per Section 0.7.4 API Design Guidelines)
 * - sortBy: undefined (no sorting applied)
 * - sortOrder: 'ASC' (ascending order)
 * 
 * Validation Rules:
 * - page: Must be an integer >= 1
 * - pageSize: Must be an integer between 1 and 100 (max per Section 0.7.4)
 * - sortBy: Must be a string (typically a field name)
 * - sortOrder: Must be either 'ASC' or 'DESC'
 * 
 * Security:
 * - All inputs are validated and sanitized per Section 0.7.1
 * - Type conversion prevents type confusion attacks
 * - Enum restriction prevents SQL injection via sort order
 * - Max page size prevents excessive resource consumption
 * 
 * @class PaginationQueryDto
 */
export class PaginationQueryDto {
  /**
   * Page number for pagination (1-indexed)
   * 
   * Specifies which page of results to return. Pages are 1-indexed,
   * meaning the first page is page 1 (not 0).
   * 
   * @type {number}
   * @default 1
   * @minimum 1
   * @example 1
   */
  @ApiPropertyOptional({
    description: 'Page number for pagination (1-indexed). First page is 1.',
    type: Number,
    default: 1,
    minimum: 1,
    example: 1,
  })
  @IsOptional()
  @Type(() => Number)
  @IsInt({ message: 'Page must be an integer' })
  @Min(1, { message: 'Page must be at least 1' })
  page?: number = 1;

  /**
   * Number of items per page
   * 
   * Specifies how many items to return per page. Limited to a maximum of 100
   * to prevent excessive resource consumption and ensure reasonable response times.
   * Default is 25 per Section 0.7.4 API Design Guidelines.
   * 
   * @type {number}
   * @default 25
   * @minimum 1
   * @maximum 100
   * @example 25
   */
  @ApiPropertyOptional({
    description:
      'Number of items per page. Default is 25, maximum is 100 per Section 0.7.4.',
    type: Number,
    default: 25,
    minimum: 1,
    maximum: 100,
    example: 25,
  })
  @IsOptional()
  @Type(() => Number)
  @IsInt({ message: 'Page size must be an integer' })
  @Min(1, { message: 'Page size must be at least 1' })
  @Max(100, { message: 'Page size cannot exceed 100' })
  pageSize?: number = 25;

  /**
   * Field name to sort by
   * 
   * Specifies which field to use for sorting results. The field name should
   * correspond to a valid property on the entity being queried. If not provided,
   * results will be returned in their natural order (typically by ID or creation date).
   * 
   * Implementation Note:
   * Services consuming this DTO should validate that the sortBy field exists
   * on the entity to prevent errors and potential security issues.
   * 
   * @type {string}
   * @optional
   * @example 'createdAt'
   * @example 'name'
   */
  @ApiPropertyOptional({
    description:
      'Field name to sort by. Should be a valid property on the entity being queried.',
    type: String,
    example: 'createdAt',
  })
  @IsOptional()
  @IsString({ message: 'Sort by field must be a string' })
  sortBy?: string;

  /**
   * Sort order direction
   * 
   * Specifies whether to sort in ascending (ASC) or descending (DESC) order.
   * Only has an effect when sortBy is also provided.
   * 
   * - ASC: Ascending order (A-Z, 0-9, oldest-newest)
   * - DESC: Descending order (Z-A, 9-0, newest-oldest)
   * 
   * @type {SortOrder}
   * @default SortOrder.ASC
   * @example SortOrder.ASC
   * @example SortOrder.DESC
   */
  @ApiPropertyOptional({
    description: "Sort order direction. 'ASC' for ascending, 'DESC' for descending.",
    enum: SortOrder,
    default: SortOrder.ASC,
    example: SortOrder.ASC,
  })
  @IsOptional()
  @IsEnum(SortOrder, {
    message: 'Sort order must be either ASC or DESC',
  })
  sortOrder?: SortOrder = SortOrder.ASC;
}
