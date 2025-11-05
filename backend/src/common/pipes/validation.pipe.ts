import {
  PipeTransform,
  Injectable,
  ArgumentMetadata,
  BadRequestException,
  Type,
} from '@nestjs/common';
import { validate, ValidationError } from 'class-validator';
import { plainToInstance, ClassConstructor } from 'class-transformer';

/**
 * Custom NestJS validation pipe implementing comprehensive DTO validation.
 * 
 * This pipe provides:
 * - Automatic transformation of request payloads to DTO instances using class-transformer
 * - Comprehensive validation using class-validator decorators
 * - Whitelist mode to strip unknown properties and prevent mass assignment vulnerabilities
 * - Detailed validation error messages with field-level details
 * - Proper BadRequestException throwing for invalid inputs
 * - Recursive validation of nested objects
 * 
 * Configuration options:
 * - transform: true - Automatically transform payloads to DTO instances
 * - whitelist: true - Strip properties that don't have any decorators
 * - forbidNonWhitelisted: true - Throw error if non-whitelisted properties exist
 * - skipMissingProperties: false - Don't skip validation for missing properties
 * 
 * Security features per Section 0.7.1:
 * - Validates all user inputs before processing
 * - Sanitizes inputs by stripping unknown properties
 * - Prevents mass assignment vulnerabilities
 * 
 * Usage example:
 * ```typescript
 * // In main.ts - Global registration
 * app.useGlobalPipes(new ValidationPipe());
 * 
 * // In controller - Automatic validation
 * @Post()
 * create(@Body() createUserDto: CreateUserDto) {
 *   // createUserDto is already validated and transformed
 *   return this.usersService.create(createUserDto);
 * }
 * ```
 * 
 * Error response format (per Section 0.7.4 API Design Guidelines):
 * ```json
 * {
 *   "success": false,
 *   "error": {
 *     "code": "VALIDATION_ERROR",
 *     "message": "Validation failed",
 *     "details": [
 *       {
 *         "field": "email",
 *         "message": "email must be a valid email address"
 *       }
 *     ]
 *   }
 * }
 * ```
 * 
 * @implements {PipeTransform<unknown, unknown>}
 */
@Injectable()
export class ValidationPipe implements PipeTransform<unknown, unknown> {
  /**
   * Transforms and validates the incoming value against the metatype DTO class.
   * 
   * Process flow:
   * 1. Check if transformation is needed (skip for primitive types and missing metatype)
   * 2. Transform plain object to DTO class instance using class-transformer
   * 3. Validate the DTO instance using class-validator decorators
   * 4. If validation errors exist, format them and throw BadRequestException
   * 5. If validation passes, return the transformed and validated DTO instance
   * 
   * @param {unknown} value - The incoming request payload to validate and transform
   * @param {ArgumentMetadata} metadata - Metadata about the argument being validated
   * @param {Type<unknown>} metadata.metatype - The TypeScript class/type of the parameter
   * @param {string} metadata.type - The type of parameter (body, query, param, custom)
   * @param {string} metadata.data - Additional data (parameter name)
   * @returns {Promise<unknown>} The validated and transformed DTO instance
   * @throws {BadRequestException} When validation fails, with detailed error messages
   */
  async transform(value: unknown, { metatype }: ArgumentMetadata): Promise<unknown> {
    // Skip validation for primitive types and missing metatype
    if (!metatype || !this.shouldValidate(metatype)) {
      return value;
    }

    // Validate that value is not null or undefined for object transformation
    // This prevents TypeError when trying to transform null/undefined to DTO
    if (value === null || value === undefined) {
      throw new BadRequestException({
        success: false,
        error: {
          code: 'VALIDATION_ERROR',
          message: 'Validation failed',
          details: [
            {
              field: 'body',
              message: 'Request body cannot be null or undefined',
            },
          ],
        },
      });
    }

    // Transform plain object to DTO class instance
    // Options per Section 0.7.2 NestJS Backend guidelines:
    // - enableImplicitConversion: false - Explicit type conversion only
    // - excludeExtraneousValues: false - Allow whitelisting to handle this
    const dtoInstance = plainToInstance(
      metatype as ClassConstructor<unknown>,
      value,
      {
        enableImplicitConversion: false,
        excludeExtraneousValues: false,
      },
    );

    // Validate the DTO instance
    // Options per Section 0.7.1 Security Requirements and Section 0.7.2:
    // - whitelist: true - Strip properties without decorators
    // - forbidNonWhitelisted: true - Reject requests with unknown properties
    // - skipMissingProperties: false - Validate all properties including missing ones
    // - forbidUnknownValues: true - Throw error for unknown values
    // - validationError: { target: false } - Don't expose target object in errors
    const validationErrors = await validate(dtoInstance as object, {
      whitelist: true,
      forbidNonWhitelisted: true,
      skipMissingProperties: false,
      forbidUnknownValues: true,
      validationError: {
        target: false, // Security: Don't expose the target object
        value: false,  // Security: Don't expose the original value
      },
    });

    // If validation errors exist, format and throw exception
    if (validationErrors.length > 0) {
      const formattedErrors = this.formatValidationErrors(validationErrors);
      throw new BadRequestException({
        success: false,
        error: {
          code: 'VALIDATION_ERROR',
          message: 'Validation failed',
          details: formattedErrors,
        },
      });
    }

    // Return the validated and transformed DTO instance
    return dtoInstance;
  }

  /**
   * Determines if the metatype should be validated.
   * 
   * Skips validation for:
   * - Primitive JavaScript types (String, Boolean, Number, Array, Object)
   * - Types that cannot be instantiated
   * 
   * This optimization prevents unnecessary validation attempts on built-in types
   * that don't have class-validator decorators.
   * 
   * @param {Type<unknown>} metatype - The TypeScript class/type to check
   * @returns {boolean} True if the metatype should be validated, false otherwise
   * @private
   */
  private shouldValidate(metatype: Type<unknown>): boolean {
    const primitiveTypes: Array<Type<unknown>> = [
      String,
      Boolean,
      Number,
      Array,
      Object,
    ];
    return !primitiveTypes.includes(metatype);
  }

  /**
   * Formats validation errors into structured field-level error details.
   * 
   * Recursively processes validation errors including nested object validation.
   * Extracts field names and constraint messages to create a clear, developer-friendly
   * error response that follows the API error format from Section 0.7.4.
   * 
   * For nested validation errors, the field path is constructed using dot notation
   * (e.g., "address.city" for nested objects, "users[0].name" for arrays).
   * 
   * Example output:
   * ```typescript
   * [
   *   {
   *     field: "email",
   *     message: "email must be a valid email address"
   *   },
   *   {
   *     field: "age",
   *     message: "age must be a positive number"
   *   },
   *   {
   *     field: "address.zipCode",
   *     message: "zipCode must be a valid postal code"
   *   }
   * ]
   * ```
   * 
   * @param {ValidationError[]} errors - Array of validation errors from class-validator
   * @param {string} parentPath - Parent field path for nested validation (used in recursion)
   * @returns {Array<{ field: string; message: string }>} Formatted error details array
   * @private
   */
  private formatValidationErrors(
    errors: ValidationError[],
    parentPath = '',
  ): Array<{ field: string; message: string }> {
    const formattedErrors: Array<{ field: string; message: string }> = [];

    for (const error of errors) {
      // Construct the full field path (including parent path for nested objects)
      const fieldPath = parentPath
        ? `${parentPath}.${error.property}`
        : error.property;

      // If the error has constraint violations, extract the messages
      if (error.constraints) {
        // Get all constraint violation messages
        const constraintMessages = Object.values(error.constraints);

        // Add each constraint violation as a separate error detail
        // This provides granular feedback when multiple validations fail on the same field
        for (const message of constraintMessages) {
          formattedErrors.push({
            field: fieldPath,
            message: message,
          });
        }
      }

      // Recursively process nested validation errors
      // This handles complex DTOs with nested objects and arrays
      if (error.children && error.children.length > 0) {
        const nestedErrors = this.formatValidationErrors(
          error.children,
          fieldPath,
        );
        formattedErrors.push(...nestedErrors);
      }
    }

    return formattedErrors;
  }
}
