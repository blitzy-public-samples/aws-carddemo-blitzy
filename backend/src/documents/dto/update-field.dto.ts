import {
  IsString,
  IsNotEmpty,
  MinLength,
  MaxLength,
  IsOptional,
} from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Data Transfer Object for correcting extracted field values.
 * 
 * Used by PATCH /documents/:id/fields endpoint for manual field corrections
 * after OCR processing and user review. Corrections are tracked in MongoDB
 * corrections collection for machine learning model training per Phase 5.
 * 
 * Required fields:
 * - field_name: Identifier of the field to correct (e.g., invoice_number, total_amount)
 * - corrected_value: New value to replace OCR-extracted value
 * 
 * Optional fields:
 * - correction_reason: Explanation for correction (helps ML training understand errors)
 * 
 * Workflow:
 * 1. User reviews OCR-extracted document in UI
 * 2. Identifies incorrect field value
 * 3. Submits correction via this DTO
 * 4. System updates DocumentField entity in PostgreSQL
 * 5. Creates Correction record in MongoDB with original_value, corrected_value, confidence
 * 6. Updates document version in document_versions collection
 * 7. Correction data used to improve future OCR/NLP accuracy
 * 
 * Validation:
 * - field_name: 1-100 characters, non-empty string
 * - corrected_value: Required string, max 5000 characters (supports long text fields)
 * - correction_reason: Optional string, max 1000 characters
 * 
 * Security:
 * - Multi-tenant isolation enforced at service layer (document belongs to user's account)
 * - Audit trail: corrected_by (user_id), corrected_at (timestamp) tracked automatically
 * 
 * @see DocumentsController.updateField()
 * @see DocumentsService.updateField()
 * @see Correction schema in MongoDB
 */
export class UpdateFieldDto {
  /**
   * Name or identifier of the field being corrected.
   * 
   * Examples: 'invoice_number', 'total_amount', 'vendor_name', 'invoice_date'
   * 
   * Corresponds to field names extracted during OCR/NLP processing.
   * Must match an existing field in the document's extracted data.
   */
  @ApiProperty({
    description: 'Name/identifier of the field being corrected (e.g., invoice_number, total_amount, vendor_name)',
    example: 'invoice_number',
    minLength: 1,
    maxLength: 100,
  })
  @IsString({ message: 'Field name must be a string' })
  @IsNotEmpty({ message: 'Field name is required' })
  @MinLength(1, { message: 'Field name must be at least 1 character' })
  @MaxLength(100, { message: 'Field name must not exceed 100 characters' })
  field_name!: string;

  /**
   * Corrected value for the field.
   * 
   * This value will replace the OCR-extracted value in the DocumentField entity.
   * Supports strings up to 5000 characters to accommodate long text fields
   * (e.g., addresses, descriptions, notes).
   * 
   * The original OCR value is preserved in the Correction record for ML training.
   */
  @ApiProperty({
    description: 'Corrected value for the field (will replace OCR-extracted value)',
    example: 'INV-2025-00123',
    maxLength: 5000,
  })
  @IsString({ message: 'Corrected value must be a string' })
  @IsNotEmpty({ message: 'Corrected value is required' })
  @MaxLength(5000, { message: 'Corrected value must not exceed 5000 characters' })
  corrected_value!: string;

  /**
   * Optional explanation for why the correction was made.
   * 
   * Helps ML training understand common OCR errors:
   * - Character confusion (8 vs 3, O vs 0, l vs 1)
   * - Poor image quality
   * - Handwriting recognition errors
   * - Layout/formatting issues
   * 
   * Examples:
   * - "OCR misread digit 8 as 3 due to poor image quality"
   * - "Handwritten signature incorrectly extracted as text"
   * - "Table cell value merged with adjacent cell"
   */
  @ApiProperty({
    description: 'Optional explanation for why the correction was made (audit trail and ML training)',
    example: 'OCR misread digit 8 as 3 due to poor image quality',
    maxLength: 1000,
    required: false,
  })
  @IsOptional()
  @IsString({ message: 'Correction reason must be a string' })
  @MaxLength(1000, { message: 'Correction reason must not exceed 1000 characters' })
  correction_reason?: string;
}
