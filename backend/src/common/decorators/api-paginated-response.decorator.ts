/**
 * API Paginated Response Decorator
 * 
 * Custom NestJS decorator that generates standardized OpenAPI/Swagger documentation
 * for paginated API responses. This decorator automatically adds pagination metadata
 * schema to endpoint documentation, ensuring consistent API documentation and response
 * structure across all paginated endpoints.
 * 
 * Implements pagination standards per Section 0.7.4:
 * - Default page size: 25, max: 100
 * - Supports both cursor-based and offset-based pagination
 * - Includes comprehensive pagination metadata
 * 
 * @example
 * // Usage in a controller:
 * import { ApiPaginatedResponse } from '@/common/decorators/api-paginated-response.decorator';
 * import { DocumentDto } from './dto/document.dto';
 * 
 * @Controller('documents')
 * export class DocumentsController {
 *   @Get()
 *   @ApiPaginatedResponse(DocumentDto)
 *   async findAll(@Query() query: SearchDocumentsDto): Promise<PaginatedResponse<DocumentDto>> {
 *     return this.documentsService.findAll(query);
 *   }
 * }
 * 
 * @module common/decorators
 * @since 1.0.0
 */

import { applyDecorators, Type } from '@nestjs/common';
import {
  ApiExtraModels,
  ApiOkResponse,
  ApiProperty,
  getSchemaPath,
} from '@nestjs/swagger';

/**
 * Default pagination page size
 * Per Section 0.7.4 API Design Guidelines
 */
const DEFAULT_PAGE_SIZE = 25;

/**
 * Maximum pagination page size
 * Per Section 0.7.4 API Design Guidelines
 */
const MAX_PAGE_SIZE = 100;

/**
 * Pagination metadata class for API responses
 * 
 * Contains all pagination-related information returned with paginated API responses.
 * Supports both offset-based pagination (page, page_size, total_pages) and
 * cursor-based pagination (next_cursor) per Section 0.7.4 pagination standards.
 * 
 * @class PaginationMeta
 * @example
 * {
 *   "total": 1000,
 *   "page": 1,
 *   "page_size": 25,
 *   "total_pages": 40,
 *   "has_next": true,
 *   "next_cursor": "abc123"
 * }
 */
export class PaginationMeta {
  /**
   * Total number of items across all pages
   * @type {number}
   */
  @ApiProperty({
    description: 'Total number of items across all pages',
    example: 1000,
    type: Number,
    required: true,
  })
  total!: number;

  /**
   * Current page number (1-indexed)
   * Used for offset-based pagination
   * @type {number}
   */
  @ApiProperty({
    description: 'Current page number (1-indexed)',
    example: 1,
    type: Number,
    required: true,
    minimum: 1,
  })
  page!: number;

  /**
   * Number of items per page
   * Default: 25, Maximum: 100 per Section 0.7.4
   * @type {number}
   */
  @ApiProperty({
    description: `Number of items per page (default: ${DEFAULT_PAGE_SIZE}, max: ${MAX_PAGE_SIZE})`,
    example: DEFAULT_PAGE_SIZE,
    type: Number,
    required: true,
    minimum: 1,
    maximum: MAX_PAGE_SIZE,
  })
  page_size!: number;

  /**
   * Total number of pages available
   * Calculated as Math.ceil(total / page_size)
   * @type {number}
   */
  @ApiProperty({
    description: 'Total number of pages available',
    example: 40,
    type: Number,
    required: true,
    minimum: 0,
  })
  total_pages!: number;

  /**
   * Indicates whether there is a next page available
   * @type {boolean}
   */
  @ApiProperty({
    description: 'Indicates whether there is a next page available',
    example: true,
    type: Boolean,
    required: true,
  })
  has_next!: boolean;

  /**
   * Cursor for cursor-based pagination
   * Optional field used for large datasets requiring cursor-based pagination
   * @type {string}
   */
  @ApiProperty({
    description:
      'Cursor for cursor-based pagination (optional, used for large datasets)',
    example: 'abc123xyz',
    type: String,
    required: false,
    nullable: true,
  })
  next_cursor?: string;
}

/**
 * Custom decorator for documenting paginated API responses in OpenAPI/Swagger
 * 
 * This decorator generates standardized Swagger documentation for endpoints that
 * return paginated results. It automatically includes the pagination metadata schema
 * and wraps the data model in a consistent response structure.
 * 
 * Response Structure:
 * ```json
 * {
 *   "success": true,
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
 * 
 * @template TModel - The DTO class type for items in the data array
 * @param {Type<TModel>} model - The DTO class to be used for the data array items
 * @returns {MethodDecorator} Combined decorator for Swagger documentation
 * 
 * @example
 * // Basic usage:
 * @Get()
 * @ApiPaginatedResponse(DocumentDto)
 * async findAll() {
 *   // Returns paginated documents
 * }
 * 
 * @example
 * // With query parameters:
 * @Get()
 * @ApiPaginatedResponse(UserDto)
 * @ApiQuery({ name: 'page', required: false, type: Number })
 * @ApiQuery({ name: 'page_size', required: false, type: Number })
 * async getUsers(@Query('page') page: number = 1) {
 *   // Returns paginated users
 * }
 * 
 * @see PaginationMeta for pagination metadata structure
 * @see Section 0.7.4 for pagination standards
 */
export function ApiPaginatedResponse<TModel extends Type<unknown>>(
  model: TModel,
): MethodDecorator {
  return applyDecorators(
    // Register the model and PaginationMeta for schema generation
    ApiExtraModels(model, PaginationMeta),
    // Define the response structure with OpenAPI schema
    ApiOkResponse({
      description: 'Paginated response with data and pagination metadata',
      schema: {
        type: 'object',
        required: ['success', 'data', 'pagination'],
        properties: {
          success: {
            type: 'boolean',
            description: 'Indicates if the request was successful',
            example: true,
          },
          data: {
            type: 'array',
            description: 'Array of paginated items',
            items: {
              $ref: getSchemaPath(model),
            },
          },
          pagination: {
            $ref: getSchemaPath(PaginationMeta),
            description: 'Pagination metadata',
          },
        },
      },
    }),
  );
}
