/**
 * CreateApiKeyDto defines the validation schema for API key creation requests.
 * 
 * Validation Features:
 * - String sanitization: Alphanumeric with limited special characters
 * - Length constraints: Prevent buffer overflow and DoS attacks
 * - Scope enumeration: Only allow predefined permission scopes
 * - Date validation: Ensure future expiration dates
 * - Rate limit bounds: Prevent abuse and resource exhaustion
 * 
 * Security Notes:
 * - Input sanitization prevents SQL injection and XSS attacks per Section 0.7.1
 * - Scopes must match server-side permission model (validated again in service)
 * - Plain API key returned only once on creation - client must store securely
 * - Rate limits enforced per key to prevent API abuse
 * 
 * Usage Example:
 * ```typescript
 * const createDto: CreateApiKeyDto = {
 *   key_name: 'Production Integration',
 *   description: 'Main production server',
 *   scopes: ['documents:read', 'documents:write'],
 *   expires_at: new Date('2026-12-31'),
 *   rate_limit_per_hour: 5000
 * };
 * ```
 */

import {
  IsString,
  IsNotEmpty,
  IsOptional,
  IsArray,
  IsEnum,
  IsDate,
  IsInt,
  MinLength,
  MaxLength,
  Min,
  Max,
  ArrayNotEmpty,
  ArrayUnique,
  Matches,
} from 'class-validator';
import { Type, Transform } from 'class-transformer';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

/**
 * Data Transfer Object for creating new API keys with scoped permissions.
 * Implements comprehensive validation per Section 0.7.1 security requirements.
 */
export class CreateApiKeyDto {
  /**
   * Human-readable name for the API key.
   * Must be unique within the account scope.
   * Used for identification and management purposes.
   */
  @IsString()
  @IsNotEmpty()
  @MinLength(1, { message: 'Key name must be at least 1 character' })
  @MaxLength(100, { message: 'Key name cannot exceed 100 characters' })
  @Matches(/^[a-zA-Z0-9\s\-_]+$/, {
    message:
      'Key name can only contain letters, numbers, spaces, hyphens, and underscores',
  })
  @ApiProperty({
    description: 'Human-readable name for the API key',
    example: 'Production Integration Key',
    minLength: 1,
    maxLength: 100,
  })
  key_name: string;

  /**
   * Optional description explaining the purpose and usage of this API key.
   * Helps with key management and auditing.
   */
  @IsString()
  @IsOptional()
  @MaxLength(500, { message: 'Description cannot exceed 500 characters' })
  @ApiPropertyOptional({
    description: 'Optional description explaining the key purpose',
    example: 'Used by production server for document processing automation',
    maxLength: 500,
  })
  description?: string;

  /**
   * Array of permission scopes defining what operations this API key can perform.
   * Implements granular access control per Section 0.5.11 Group 10A.
   * If not provided, key has no scopes (restricted access).
   * 
   * Available scopes:
   * - documents:read - Read document metadata and content
   * - documents:write - Create and update documents
   * - documents:delete - Delete documents
   * - templates:read - Read extraction templates
   * - templates:write - Create and update templates
   * - templates:delete - Delete templates
   * - processing:read - Read processing job status
   * - processing:write - Create processing jobs
   * - export:read - Export document data
   * - analytics:read - Access analytics and reports
   * - users:read - Read user information
   * - users:write - Manage users
   */
  @IsArray()
  @IsOptional()
  @ArrayNotEmpty({ message: 'Scopes array cannot be empty if provided' })
  @ArrayUnique({ message: 'Scopes must be unique' })
  @IsEnum(
    [
      'documents:read',
      'documents:write',
      'documents:delete',
      'templates:read',
      'templates:write',
      'templates:delete',
      'processing:read',
      'processing:write',
      'export:read',
      'analytics:read',
      'users:read',
      'users:write',
    ],
    {
      each: true,
      message: 'Invalid scope value',
    },
  )
  @ApiPropertyOptional({
    description:
      'Array of permission scopes for granular access control. If not provided, no scopes are granted (restricted access)',
    example: ['documents:read', 'documents:write', 'processing:read'],
    enum: [
      'documents:read',
      'documents:write',
      'documents:delete',
      'templates:read',
      'templates:write',
      'templates:delete',
      'processing:read',
      'processing:write',
      'export:read',
      'analytics:read',
      'users:read',
      'users:write',
    ],
    isArray: true,
  })
  scopes?: string[];

  /**
   * Expiration date for the API key (ISO 8601 format).
   * After this date, the key will be automatically invalidated.
   * If not provided, key never expires (lifetime key).
   * 
   * Note: Expiration dates should be set to future dates.
   * Service layer will validate that expires_at is after current time.
   */
  @IsDate()
  @IsOptional()
  @Type(() => Date)
  @Transform(({ value }) => (value ? new Date(value) : undefined))
  @ApiPropertyOptional({
    description:
      'Expiration date for the API key (ISO 8601 format). If not provided, key never expires',
    example: '2026-12-31T23:59:59Z',
    type: 'string',
    format: 'date-time',
  })
  expires_at?: Date;

  /**
   * Maximum number of API requests allowed per hour for this key.
   * Defaults to 1000 requests per hour per Section 0.4.5.
   * Minimum: 1, Maximum: 10,000
   * 
   * Rate limits are enforced per key to prevent abuse and ensure fair usage.
   * Exceeded limits result in HTTP 429 (Too Many Requests) responses.
   */
  @IsInt()
  @IsOptional()
  @Min(1, { message: 'Rate limit must be at least 1 request per hour' })
  @Max(10000, {
    message: 'Rate limit cannot exceed 10,000 requests per hour',
  })
  @ApiPropertyOptional({
    description:
      'Maximum number of API requests allowed per hour. Defaults to 1000 per Section 0.4.5',
    example: 1000,
    minimum: 1,
    maximum: 10000,
    default: 1000,
  })
  rate_limit_per_hour?: number;
}
