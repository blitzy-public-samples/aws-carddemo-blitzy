import {
  IsOptional,
  IsString,
  IsNumber,
  Min,
  IsBoolean,
} from 'class-validator';

/**
 * ValidationRuleDto - Data Transfer Object for field validation rules configuration.
 * 
 * Defines flexible validation rules that can be applied to extracted OCR data to ensure
 * data quality and format compliance. Rules are stored in the validation_rules JSONB
 * column of the TemplateField entity.
 * 
 * Supported Validation Types:
 * - Pattern validation: Regex pattern matching for text fields (invoice numbers, IDs)
 * - Range validation: Min/max bounds for numeric and currency fields
 * - Length validation: String length constraints
 * - Date validation: Date format and range validation
 * - Required validation: Enforce field presence
 * - Custom validation: Named custom validator functions
 * 
 * Usage Examples:
 * ```typescript
 * // Invoice number pattern validation
 * {
 *   pattern: '^INV-[0-9]{6}$',
 *   errorMessage: 'Invoice number must be in format INV-######'
 * }
 * 
 * // Currency amount range validation
 * {
 *   min: 0,
 *   max: 999999.99,
 *   required: true,
 *   errorMessage: 'Amount must be between 0 and 999,999.99'
 * }
 * 
 * // Date format and range validation
 * {
 *   format: 'YYYY-MM-DD',
 *   minDate: '2020-01-01',
 *   maxDate: '2030-12-31',
 *   errorMessage: 'Date must be in YYYY-MM-DD format and between 2020-2030'
 * }
 * 
 * // Email validation with custom validator
 * {
 *   pattern: '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$',
 *   customValidator: 'validateBusinessEmail',
 *   errorMessage: 'Must be a valid business email address'
 * }
 * ```
 * 
 * Integration:
 * - Used in TemplateFieldDto.validationRules property
 * - Stored as JSONB in TemplateField entity validation_rules column
 * - Applied during OCR data extraction in Processing Service
 * - Validated in Field Validator service per Section 0.5.5 Phase 4
 * 
 * @see TemplateFieldDto - Parent DTO using validation rules
 * @see TemplateField.validationRules - Database column storing these rules
 * @see Section 0.5.7 - Phase 7 Template Builder with validation rules
 */
export class ValidationRuleDto {
  /**
   * Regular expression pattern for text validation.
   * Used to validate text fields against specific formats (invoice numbers, IDs, etc.).
   * @example "^INV-[0-9]{6}$" - Invoice number format
   * @example "^[A-Z]{2}-[0-9]{4}$" - Custom ID format
   */
  @IsOptional()
  @IsString({ message: 'Pattern must be a string' })
  pattern?: string;

  /**
   * Minimum numeric value for range validation.
   * Applied to numeric and currency fields to enforce lower bounds.
   * @example 0 - Non-negative values only
   * @example -999.99 - Allow negative values with minimum
   */
  @IsOptional()
  @IsNumber({}, { message: 'Min value must be a number' })
  min?: number;

  /**
   * Maximum numeric value for range validation.
   * Applied to numeric and currency fields to enforce upper bounds.
   * @example 999999.99 - Maximum currency amount
   * @example 100 - Percentage field maximum
   */
  @IsOptional()
  @IsNumber({}, { message: 'Max value must be a number' })
  max?: number;

  /**
   * Minimum string length constraint.
   * Enforces minimum number of characters for text fields.
   * @example 5 - Minimum 5 characters
   * @example 0 - Allow empty strings (if allowEmpty is true)
   */
  @IsOptional()
  @IsNumber({}, { message: 'minLength must be a number' })
  @Min(0, { message: 'minLength must be >= 0' })
  minLength?: number;

  /**
   * Maximum string length constraint.
   * Enforces maximum number of characters for text fields.
   * @example 255 - Database varchar limit
   * @example 50 - Reasonable text field limit
   */
  @IsOptional()
  @IsNumber({}, { message: 'maxLength must be a number' })
  @Min(1, { message: 'maxLength must be >= 1' })
  maxLength?: number;

  /**
   * Date format specification string.
   * Defines expected date format for parsing and validation.
   * @example "YYYY-MM-DD" - ISO 8601 date format
   * @example "MM/DD/YYYY" - US date format
   * @example "DD-MMM-YYYY" - Date with month abbreviation
   */
  @IsOptional()
  @IsString({ message: 'Date format must be a string' })
  format?: string;

  /**
   * Minimum date value constraint.
   * Enforces lower bound for date fields (ISO 8601 format recommended).
   * @example "2020-01-01" - No dates before 2020
   * @example "2025-01-01" - Future dates only
   */
  @IsOptional()
  @IsString({ message: 'minDate must be a string' })
  minDate?: string;

  /**
   * Maximum date value constraint.
   * Enforces upper bound for date fields (ISO 8601 format recommended).
   * @example "2030-12-31" - No dates beyond 2030
   * @example "2025-12-31" - Current year only
   */
  @IsOptional()
  @IsString({ message: 'maxDate must be a string' })
  maxDate?: string;

  /**
   * Required field flag.
   * When true, field must be present and non-empty in extracted data.
   * @example true - Field is mandatory
   * @example false - Field is optional
   */
  @IsOptional()
  @IsBoolean({ message: 'required must be a boolean' })
  required?: boolean;

  /**
   * Allow empty values flag.
   * When true, permits empty strings or null values even if field is present.
   * Typically used with required: false for optional fields.
   * @example true - Allow empty values
   * @example false - Reject empty values if field is present
   */
  @IsOptional()
  @IsBoolean({ message: 'allowEmpty must be a boolean' })
  allowEmpty?: boolean;

  /**
   * Custom error message for validation failures.
   * Provides user-friendly error message when validation rule fails.
   * Displayed in UI during document review and correction.
   * @example "Invoice number must be in format INV-######"
   * @example "Amount must be between 0 and 999,999.99"
   */
  @IsOptional()
  @IsString({ message: 'errorMessage must be a string' })
  errorMessage?: string;

  /**
   * Name of custom validator function.
   * References a custom validation function registered in the Field Validator service.
   * Enables domain-specific validation logic beyond standard patterns.
   * @example "validateBusinessEmail" - Custom email validation
   * @example "validateTaxId" - Tax ID checksum validation
   * @example "validateIBAN" - International bank account number validation
   */
  @IsOptional()
  @IsString({ message: 'customValidator must be a string' })
  customValidator?: string;
}
