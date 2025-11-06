import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
  Index,
} from 'typeorm';
import { Template } from './template.entity';

/**
 * Field type enumeration for template field data types.
 * 
 * Defines the supported data types for extracted document fields,
 * enabling type-specific validation and parsing in the OCR pipeline.
 */
export enum FieldType {
  /** General text fields (names, descriptions, IDs) */
  TEXT = 'text',
  
  /** Numeric values (integers, decimals, quantities) */
  NUMBER = 'number',
  
  /** Date fields with format validation */
  DATE = 'date',
  
  /** Monetary amounts with currency symbol handling */
  CURRENCY = 'currency',
  
  /** Email address validation */
  EMAIL = 'email',
  
  /** Phone number validation */
  PHONE = 'phone',
  
  /** Checkbox/yes-no fields */
  BOOLEAN = 'boolean',
  
  /** Multi-line address fields */
  ADDRESS = 'address',
  
  /** Custom data types with specialized validation */
  CUSTOM = 'custom',
}

/**
 * Bounding box interface for zone coordinates.
 * 
 * Defines the rectangular area on a document page where a field should be extracted.
 * Coordinates are relative to page dimensions with (0,0) at top-left corner.
 */
export interface BoundingBox {
  /** X coordinate of zone top-left corner (in points or pixels) */
  x: number;
  
  /** Y coordinate of zone top-left corner (in points or pixels) */
  y: number;
  
  /** Zone width in document units */
  width: number;
  
  /** Zone height in document units */
  height: number;
}

/**
 * TemplateField entity for storing field definitions and zone coordinates in custom extraction templates.
 * 
 * Each TemplateField represents a specific data field to be extracted from a document zone,
 * including its position on the page (bounding box coordinates), data type, validation rules,
 * and extraction parameters.
 * 
 * Features:
 * - Zone positioning: x, y, width, height coordinates relative to page dimensions
 * - Multi-page support: page_number for documents with multiple pages
 * - Field metadata: name, label, type, required flag, display order
 * - Flexible validation: JSONB validation_rules for regex patterns, min/max, formats
 * - Confidence control: confidence_threshold for auto-approval decisions
 * - Extraction hints: Optional hints to guide OCR processing
 * - Foreign key relationship: Belongs to Template entity with CASCADE delete
 * 
 * Data Types Supported:
 * - TEXT: General text fields
 * - NUMBER: Numeric values (integers, decimals)
 * - DATE: Date fields with format validation
 * - CURRENCY: Monetary amounts with currency symbol handling
 * - EMAIL: Email address validation
 * - PHONE: Phone number validation
 * - BOOLEAN: Checkbox/yes-no fields
 * - ADDRESS: Multi-line address fields
 * - CUSTOM: Custom data types with specialized validation
 * 
 * Zone Coordinates:
 * - Coordinates are relative to page dimensions (0,0 = top-left corner)
 * - Units are typically in points (1/72 inch) or pixels depending on document resolution
 * - Bounding box defines rectangular extraction zone on the page
 * - Multiple fields can have overlapping zones for different data types
 * 
 * Validation Rules Structure (JSONB examples):
 * ```json
 * // Pattern validation for invoice numbers
 * {
 *   "pattern": "^INV-[0-9]{6}$",
 *   "error_message": "Invoice number must be in format INV-######"
 * }
 * 
 * // Range validation for amounts
 * {
 *   "min": 0,
 *   "max": 999999.99,
 *   "error_message": "Amount must be between 0 and 999,999.99"
 * }
 * 
 * // Date format validation
 * {
 *   "format": "YYYY-MM-DD",
 *   "min_date": "2020-01-01",
 *   "max_date": "2030-12-31"
 * }
 * ```
 * 
 * Usage in OCR Pipeline:
 * 1. Template selected based on document type classification
 * 2. OCR service extracts text from each field's bounding box zone
 * 3. Extracted value validated against validation_rules
 * 4. If confidence >= confidence_threshold: auto-approve
 * 5. If confidence < threshold: flag for manual review
 * 6. User corrections stored for ML training
 * 
 * Integration Points:
 * - Created via TemplatesService.createTemplate() with zones array
 * - Used by OCR Processing Service for template-based extraction
 * - Updated via TemplatesService.updateTemplate() for template modifications
 * - Deleted automatically when parent Template is deleted (CASCADE)
 * 
 * Database Schema:
 * - Defined in migration 006-create-templates-table.ts
 * - Foreign key constraint to templates(id) with ON DELETE CASCADE
 * - Indexes on template_id, (template_id, field_name), field_order
 * - CHECK constraint: confidence_threshold BETWEEN 0 AND 100
 * - CHECK constraint: x, y, width, height >= 0
 * 
 * Security:
 * - Multi-tenant isolation inherited from parent Template entity
 * - No sensitive data stored in this entity
 * - Validation rules validated before storage to prevent injection
 * 
 * @see Template - Parent template entity
 * @see TemplatesService - Service for template and field management
 * @see Section 0.5.7 - Phase 7 Template Builder implementation requirements
 * @see Section 0.7.1 - Security and multi-tenant isolation requirements
 */
@Entity('template_fields')
@Index(['templateId'])
@Index(['templateId', 'fieldName'])
@Index(['fieldOrder'])
export class TemplateField {
  /**
   * Unique identifier for the template field.
   * Uses UUID for better distribution and security per Section 0.7.1.
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Foreign key to parent template.
   * Establishes many-to-one relationship with Template entity.
   * Indexed for query performance optimization.
   */
  @Column({ type: 'uuid', name: 'template_id' })
  templateId!: string;

  /**
   * Parent template relationship.
   * Configured with CASCADE delete to automatically remove fields when template is deleted.
   * 
   * @relation ManyToOne
   * @cascade onDelete: CASCADE
   */
  @ManyToOne(() => Template, (template) => template.fields, {
    onDelete: 'CASCADE',
    nullable: false,
  })
  @JoinColumn({ name: 'template_id' })
  template!: Template;

  /**
   * X coordinate of zone top-left corner.
   * Defines horizontal position of extraction zone relative to page origin.
   * Must be >= 0 (enforced by database CHECK constraint).
   * Unit: points (1/72 inch) or pixels depending on document resolution.
   */
  @Column({ type: 'decimal', precision: 10, scale: 2 })
  x!: number;

  /**
   * Y coordinate of zone top-left corner.
   * Defines vertical position of extraction zone relative to page origin.
   * Must be >= 0 (enforced by database CHECK constraint).
   * Unit: points (1/72 inch) or pixels depending on document resolution.
   */
  @Column({ type: 'decimal', precision: 10, scale: 2 })
  y!: number;

  /**
   * Zone width in document units.
   * Defines horizontal span of extraction zone.
   * Must be > 0 (enforced by database CHECK constraint).
   */
  @Column({ type: 'decimal', precision: 10, scale: 2 })
  width!: number;

  /**
   * Zone height in document units.
   * Defines vertical span of extraction zone.
   * Must be > 0 (enforced by database CHECK constraint).
   */
  @Column({ type: 'decimal', precision: 10, scale: 2 })
  height!: number;

  /**
   * Page number for multi-page documents.
   * 1-indexed (first page = 1).
   * Defaults to 1 for single-page documents.
   * Must be > 0 (enforced by database CHECK constraint).
   */
  @Column({ type: 'int', name: 'page_number', default: 1 })
  pageNumber!: number;

  /**
   * Internal field name (unique within template).
   * Used as programmatic identifier for the field.
   * Examples: 'invoice_number', 'total_amount', 'vendor_name', 'due_date'
   * Should follow snake_case convention.
   * Indexed as part of composite index (template_id, field_name) for fast lookups.
   */
  @Column({ type: 'varchar', length: 100, name: 'field_name' })
  fieldName!: string;

  /**
   * Human-readable field label.
   * Displayed in UI for user-facing field identification.
   * Examples: 'Invoice Number', 'Total Amount', 'Vendor Name', 'Due Date'
   * Optional - if null, fieldName is used for display.
   */
  @Column({ type: 'varchar', length: 100, name: 'field_label', nullable: true })
  fieldLabel!: string | null;

  /**
   * Field data type for validation and parsing.
   * Determines how extracted text is validated and converted.
   * Defaults to TEXT for general text fields.
   * 
   * @see FieldType enum for supported types
   */
  @Column({
    type: 'enum',
    enum: FieldType,
    name: 'field_type',
    default: FieldType.TEXT,
  })
  fieldType!: FieldType;

  /**
   * Whether field must be extracted and validated.
   * Required fields trigger validation errors if missing or invalid.
   * Non-required fields can be empty or low-confidence without blocking approval.
   * Defaults to false.
   */
  @Column({ type: 'boolean', name: 'is_required', default: false })
  isRequired!: boolean;

  /**
   * Display order in UI (0-indexed).
   * Determines sequence of fields in document review interface.
   * Lower values appear first (field_order=0 is first field).
   * Indexed for efficient ordered retrieval.
   * Defaults to 0.
   */
  @Column({ type: 'int', name: 'field_order', default: 0 })
  fieldOrder!: number;

  /**
   * Flexible validation rules stored as JSONB.
   * Enables complex, type-specific validation without schema changes.
   * 
   * Common validation rule structures:
   * 
   * Pattern validation (regex):
   * {
   *   "pattern": "^INV-[0-9]{6}$",
   *   "error_message": "Invalid invoice number format"
   * }
   * 
   * Range validation (numbers, dates):
   * {
   *   "min": 0,
   *   "max": 999999.99,
   *   "error_message": "Amount out of valid range"
   * }
   * 
   * Date format validation:
   * {
   *   "format": "YYYY-MM-DD",
   *   "min_date": "2020-01-01",
   *   "max_date": "2030-12-31"
   * }
   * 
   * Email validation:
   * {
   *   "allow_international": true,
   *   "require_tld": true
   * }
   * 
   * Phone validation:
   * {
   *   "country_code": "US",
   *   "format": "E.164"
   * }
   * 
   * Custom validator reference:
   * {
   *   "custom_validator": "validatePONumber",
   *   "validator_params": { "prefix": "PO" }
   * }
   * 
   * Multiple rules can be combined:
   * {
   *   "required": true,
   *   "pattern": "^[A-Z]{2}[0-9]{6}$",
   *   "min_length": 8,
   *   "max_length": 8,
   *   "error_message": "Invalid format"
   * }
   * 
   * Null value indicates no validation rules (accept any extracted value).
   */
  @Column({ type: 'jsonb', name: 'validation_rules', nullable: true })
  validationRules!: Record<string, any> | null;

  /**
   * Minimum confidence score for auto-approval (0.00-100.00).
   * If OCR confidence >= this threshold, field is auto-approved.
   * If OCR confidence < this threshold, field is flagged for manual review.
   * Defaults to 85.00 (85% confidence).
   * Must be between 0 and 100 (enforced by database CHECK constraint).
   * 
   * Recommended thresholds by field criticality:
   * - Critical fields (invoice amounts, legal dates): 95.00
   * - Important fields (vendor names, quantities): 85.00
   * - Non-critical fields (descriptions, notes): 70.00
   */
  @Column({
    type: 'decimal',
    precision: 5,
    scale: 2,
    name: 'confidence_threshold',
    default: 85.0,
  })
  confidenceThreshold!: number;

  /**
   * Optional hint for OCR engine to improve extraction accuracy.
   * Provides context or patterns to guide OCR processing.
   * 
   * Examples:
   * - "Look for bold text after 'Invoice #:'"
   * - "Currency amount with $ symbol"
   * - "Date in MM/DD/YYYY format below 'Due Date' label"
   * - "Multi-line address in upper-right corner"
   * - "Total in last row of table"
   * 
   * Null value indicates no specific extraction hint.
   */
  @Column({ type: 'varchar', length: 500, name: 'extraction_hint', nullable: true })
  extractionHint!: string | null;

  /**
   * Timestamp of field creation (UTC).
   * Automatically set on INSERT.
   * Used for audit trail and tracking template evolution.
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * Timestamp of last field update (UTC).
   * Automatically updated on every UPDATE.
   * Used for audit trail and change tracking.
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;

  /**
   * Get bounding box coordinates as structured object.
   * 
   * Convenience method for accessing zone coordinates as a single object
   * instead of individual properties. Useful for passing to OCR processing
   * functions that expect BoundingBox interface.
   * 
   * @returns BoundingBox object with x, y, width, height
   * 
   * @example
   * ```typescript
   * const field = await templateFieldRepository.findOne({ where: { id: fieldId } });
   * const zone = field.getBoundingBox();
   * const extractedText = await ocrService.extractFromZone(documentImage, zone);
   * ```
   */
  getBoundingBox(): BoundingBox {
    return {
      x: this.x,
      y: this.y,
      width: this.width,
      height: this.height,
    };
  }

  /**
   * Validate if coordinates are within valid ranges.
   * 
   * Checks that zone coordinates satisfy basic validity constraints:
   * - x and y must be non-negative (>= 0)
   * - width and height must be positive (> 0)
   * 
   * Note: This does NOT validate that coordinates are within actual page bounds
   * (that requires page dimensions which are stored in parent document/template).
   * Database CHECK constraints enforce these rules at persistence layer, but this
   * method is useful for pre-validation in application logic.
   * 
   * @returns true if all coordinates are valid, false otherwise
   * 
   * @example
   * ```typescript
   * const field = new TemplateField();
   * field.x = 100;
   * field.y = 200;
   * field.width = 300;
   * field.height = 50;
   * 
   * if (field.isValidCoordinates()) {
   *   await templateFieldRepository.save(field);
   * } else {
   *   throw new Error('Invalid zone coordinates');
   * }
   * ```
   */
  isValidCoordinates(): boolean {
    return (
      this.x >= 0 &&
      this.y >= 0 &&
      this.width > 0 &&
      this.height > 0
    );
  }
}
