/**
 * MongoDB Schema Definition for Corrections Collection
 * 
 * Purpose:
 * This schema defines the structure for the corrections collection, which stores
 * field-level corrections made by users during document review. These corrections
 * serve as training data for machine learning pipelines to improve OCR and NLP
 * accuracy over time.
 * 
 * Use Cases:
 * - Track user corrections to OCR-extracted field values
 * - Capture confidence scores and correction metadata
 * - Build ML training datasets from user feedback
 * - Analyze correction patterns to identify problematic fields
 * - Measure OCR accuracy improvements over time
 * - Support multi-tenant data isolation
 * 
 * References:
 * - Section 0.2.5: MongoDB Schema Definitions
 * - Section 0.5.4: Phase 4 NLP and Data Extraction
 * - Section 0.5.6: Phase 5 Document Review and Correction
 * - Section 0.7.1: Security Requirements (Multi-tenant isolation)
 * 
 * Collection Name: corrections
 * Database: MongoDB
 * Indexes: See CORRECTION_INDEXES constant
 * 
 * @module correction.schema
 */

import type { ObjectId } from 'mongodb';

/**
 * Field Type Union
 * 
 * Defines the possible types of fields that can be corrected.
 * Used for categorizing corrections and filtering training data.
 */
export type FieldType = 
  | 'text'
  | 'number'
  | 'date'
  | 'currency'
  | 'email'
  | 'phone'
  | 'url'
  | 'boolean'
  | 'percentage'
  | 'address'
  | 'tax_id'
  | 'account_number'
  | 'invoice_number'
  | 'po_number'
  | 'other';

/**
 * Validation Status Union
 * 
 * Defines the validation status of a correction after it's been made.
 * Allows for quality control of training data.
 */
export type ValidationStatus = 'pending' | 'approved' | 'rejected';

/**
 * Correction Interface
 * 
 * Represents a single field-level correction made by a user during document review.
 * This data is used to improve OCR/NLP models through machine learning training.
 * 
 * @interface Correction
 * 
 * @example
 * {
 *   _id: ObjectId("507f1f77bcf86cd799439011"),
 *   account_id: "acc_123456",
 *   document_id: "doc_550e8400-e29b-41d4-a716-446655440000",
 *   field_name: "invoice_total",
 *   original_value: "$1,234.56",
 *   corrected_value: "$1,534.56",
 *   original_confidence: 72,
 *   correction_reason: "OCR misread the 5 as a 2",
 *   corrected_by: "user_789",
 *   corrected_by_email: "reviewer@company.com",
 *   created_at: new Date("2025-10-31T14:30:00Z"),
 *   metadata: { page_number: 1, bounding_box: { x: 450, y: 200, width: 80, height: 20 } },
 *   field_type: "currency",
 *   is_training_data: true,
 *   validation_status: "approved"
 * }
 */
export interface Correction {
  /**
   * MongoDB Document ID
   * Unique identifier for this correction record
   */
  _id: ObjectId;

  /**
   * Account ID
   * Multi-tenant isolation - identifies which account this correction belongs to
   * REQUIRED, INDEXED
   * Per Section 0.7.1 Security Requirement #3: Multi-tenant isolation
   */
  account_id: string;

  /**
   * Document ID
   * Reference to the document that was corrected
   * Links to the documents collection
   * REQUIRED, INDEXED
   */
  document_id: string;

  /**
   * Field Name
   * Name/identifier of the field that was corrected
   * Examples: "invoice_number", "total_amount", "vendor_name", "invoice_date"
   * REQUIRED, INDEXED
   */
  field_name: string;

  /**
   * Original Value
   * The value extracted by OCR/NLP before user correction
   * Can be null if no value was extracted
   */
  original_value: string | number | null;

  /**
   * Corrected Value
   * The value provided by the user after correction
   * REQUIRED
   */
  corrected_value: string | number;

  /**
   * Original Confidence
   * Confidence score (0-100) of the original OCR/NLP extraction
   * Lower scores typically indicate fields that need correction
   * Range: 0-100
   */
  original_confidence: number;

  /**
   * Correction Reason
   * Optional user-provided explanation for why the correction was made
   * Helps understand common OCR failure patterns
   * OPTIONAL
   */
  correction_reason?: string;

  /**
   * Corrected By
   * User ID of the person who made the correction
   * REQUIRED, INDEXED
   */
  corrected_by: string;

  /**
   * Corrected By Email
   * Email address of the user who made the correction
   * Used for reporting and accountability
   */
  corrected_by_email: string;

  /**
   * Created At
   * Timestamp when the correction was made
   * REQUIRED, INDEXED, IMMUTABLE
   * Default: Date.now
   */
  created_at: Date;

  /**
   * Metadata
   * Additional context about the correction
   * Can include: page_number, bounding_box coordinates, context text, etc.
   * OPTIONAL, FLEXIBLE SCHEMA
   */
  metadata?: Record<string, unknown>;

  /**
   * Field Type
   * Categorizes the type of field that was corrected
   * Used for filtering training data by field type
   * OPTIONAL, INDEXED (via compound index)
   */
  field_type?: FieldType;

  /**
   * Is Training Data
   * Flag indicating whether this correction should be used for ML training
   * Default: true
   * Can be set to false to exclude low-quality or questionable corrections
   */
  is_training_data: boolean;

  /**
   * Validation Status
   * Quality control status for the correction
   * - pending: Correction made but not yet validated
   * - approved: Correction verified and ready for training
   * - rejected: Correction deemed incorrect or low-quality
   * OPTIONAL
   */
  validation_status?: ValidationStatus;
}

/**
 * Create Correction DTO
 * 
 * Type for creating a new correction document.
 * Omits auto-generated fields: _id, created_at
 * 
 * @example
 * const newCorrection: CreateCorrectionDto = {
 *   account_id: "acc_123",
 *   document_id: "doc_456",
 *   field_name: "invoice_total",
 *   original_value: "1234.56",
 *   corrected_value: "1534.56",
 *   original_confidence: 72,
 *   corrected_by: "user_789",
 *   corrected_by_email: "user@example.com",
 *   field_type: "currency",
 *   is_training_data: true
 * };
 */
export type CreateCorrectionDto = Omit<Correction, '_id' | 'created_at'>;

/**
 * Update Correction DTO
 * 
 * Type for updating an existing correction document.
 * All fields are optional except immutable ones (account_id, created_at)
 */
export type UpdateCorrectionDto = Partial<Omit<Correction, '_id' | 'account_id' | 'created_at'>>;

/**
 * Collection Name Constant
 * 
 * MongoDB collection name for corrections
 */
export const CORRECTIONS_COLLECTION = 'corrections';

/**
 * Collection Indexes Configuration
 * 
 * Defines all indexes for the corrections collection to optimize query performance.
 * These indexes support the following query patterns:
 * - Tenant isolation queries (account_id)
 * - Document corrections lookup (document_id)
 * - Field-specific correction queries (field_name)
 * - User correction history (corrected_by)
 * - ML training data queries (field_name + is_training_data)
 * - Timeline queries (created_at)
 * 
 * Per assigned folder requirements: "Indexed for query performance on document_id and field_name"
 */
export const CORRECTION_INDEXES = [
  // Single field indexes
  { key: { account_id: 1 }, name: 'idx_account_id' },
  { key: { document_id: 1 }, name: 'idx_document_id' },
  { key: { field_name: 1 }, name: 'idx_field_name' },
  { key: { created_at: -1 }, name: 'idx_created_at_desc' },
  { key: { corrected_by: 1 }, name: 'idx_corrected_by' },

  // Compound indexes for common query patterns
  { 
    key: { document_id: 1, field_name: 1 }, 
    name: 'idx_document_field',
    // Note: Can be made unique if one correction per field per document is enforced
  },
  { 
    key: { account_id: 1, created_at: -1 }, 
    name: 'idx_account_timeline' 
  },
  { 
    key: { field_name: 1, is_training_data: 1 }, 
    name: 'idx_field_training_data' 
  },
  { 
    key: { corrected_by: 1, created_at: -1 }, 
    name: 'idx_user_history' 
  },
] as const;

/**
 * Type Guard: Validate Field Type
 * 
 * Checks if a string is a valid FieldType.
 * Useful for runtime validation of user input.
 * 
 * @param type - The string to validate
 * @returns True if the string is a valid FieldType
 * 
 * @example
 * if (isValidFieldType(userInput)) {
 *   // TypeScript now knows userInput is FieldType
 *   const fieldType: FieldType = userInput;
 * }
 */
export function isValidFieldType(type: string): type is FieldType {
  const validTypes: FieldType[] = [
    'text',
    'number',
    'date',
    'currency',
    'email',
    'phone',
    'url',
    'boolean',
    'percentage',
    'address',
    'tax_id',
    'account_number',
    'invoice_number',
    'po_number',
    'other',
  ];
  return validTypes.includes(type as FieldType);
}

/**
 * Type Guard: Validate Validation Status
 * 
 * Checks if a string is a valid ValidationStatus.
 * Useful for runtime validation of user input.
 * 
 * @param status - The string to validate
 * @returns True if the string is a valid ValidationStatus
 * 
 * @example
 * if (isValidValidationStatus(input)) {
 *   const status: ValidationStatus = input;
 * }
 */
export function isValidValidationStatus(status: string): status is ValidationStatus {
  const validStatuses: ValidationStatus[] = ['pending', 'approved', 'rejected'];
  return validStatuses.includes(status as ValidationStatus);
}

/**
 * Default Values for Correction Documents
 * 
 * Provides default values for optional fields when creating new corrections
 */
export const CORRECTION_DEFAULTS = {
  is_training_data: true,
  validation_status: 'pending' as ValidationStatus,
} as const;

/**
 * Validation Rules for Correction Fields
 * 
 * Defines validation constraints for correction fields.
 * These rules should be enforced at the application layer.
 * 
 * Per Section 0.7.1 Code Quality Standards: All inputs must be validated
 */
export const CORRECTION_VALIDATION = {
  account_id: {
    required: true,
    maxLength: 50,
    pattern: /^[a-zA-Z0-9_-]+$/,
  },
  document_id: {
    required: true,
    maxLength: 100,
  },
  field_name: {
    required: true,
    maxLength: 100,
    pattern: /^[a-zA-Z0-9_-]+$/,
  },
  corrected_value: {
    required: true,
  },
  corrected_by: {
    required: true,
    maxLength: 50,
  },
  corrected_by_email: {
    required: true,
    maxLength: 255,
    pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, // Basic email pattern
  },
  original_confidence: {
    min: 0,
    max: 100,
  },
  correction_reason: {
    maxLength: 1000,
  },
} as const;

/**
 * Helper: Calculate Correction Accuracy
 * 
 * Calculates the average confidence score improvement from corrections.
 * Useful for measuring OCR accuracy improvements over time.
 * 
 * @param corrections - Array of correction documents
 * @returns Average confidence score of original extractions
 * 
 * @example
 * const avgConfidence = calculateAverageOriginalConfidence(corrections);
 * console.log(`Average confidence of corrected fields: ${avgConfidence}%`);
 */
export function calculateAverageOriginalConfidence(corrections: Correction[]): number {
  if (corrections.length === 0) return 0;
  
  const totalConfidence = corrections.reduce(
    (sum, correction) => sum + correction.original_confidence,
    0
  );
  
  return Math.round(totalConfidence / corrections.length);
}

/**
 * Helper: Filter Training Data by Field Type
 * 
 * Filters corrections to only those suitable for ML training of a specific field type.
 * 
 * @param corrections - Array of correction documents
 * @param fieldType - The field type to filter by
 * @returns Filtered array of corrections
 * 
 * @example
 * const currencyCorrections = filterTrainingDataByFieldType(allCorrections, 'currency');
 * // Use currencyCorrections to train currency field extraction model
 */
export function filterTrainingDataByFieldType(
  corrections: Correction[],
  fieldType: FieldType
): Correction[] {
  return corrections.filter(
    (correction) =>
      correction.is_training_data &&
      correction.field_type === fieldType &&
      (!correction.validation_status || correction.validation_status === 'approved')
  );
}

/**
 * Helper: Get Corrections by User
 * 
 * Groups corrections by the user who made them.
 * Useful for user activity reporting and quality analysis.
 * 
 * @param corrections - Array of correction documents
 * @returns Map of user ID to their corrections
 * 
 * @example
 * const userCorrections = getCorrectionsByUser(allCorrections);
 * userCorrections.forEach((corrections, userId) => {
 *   console.log(`User ${userId} made ${corrections.length} corrections`);
 * });
 */
export function getCorrectionsByUser(
  corrections: Correction[]
): Map<string, Correction[]> {
  const userMap = new Map<string, Correction[]>();
  
  corrections.forEach((correction) => {
    const userId = correction.corrected_by;
    const userCorrections = userMap.get(userId);
    if (userCorrections) {
      userCorrections.push(correction);
    } else {
      userMap.set(userId, [correction]);
    }
  });
  
  return userMap;
}

/**
 * Helper: Get Most Corrected Fields
 * 
 * Analyzes corrections to identify fields that are most frequently corrected.
 * This helps identify problematic OCR extraction patterns.
 * 
 * @param corrections - Array of correction documents
 * @param limit - Maximum number of fields to return (default: 10)
 * @returns Array of [field_name, count] tuples, sorted by count descending
 * 
 * @example
 * const problematicFields = getMostCorrectedFields(corrections, 5);
 * console.log('Top 5 fields needing correction:', problematicFields);
 */
export function getMostCorrectedFields(
  corrections: Correction[],
  limit = 10
): Array<[string, number]> {
  const fieldCounts = new Map<string, number>();
  
  corrections.forEach((correction) => {
    const count = fieldCounts.get(correction.field_name) || 0;
    fieldCounts.set(correction.field_name, count + 1);
  });
  
  return Array.from(fieldCounts.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit);
}

/**
 * Type Export: Correction Document with _id
 * 
 * Alias for Correction, emphasizing that it includes MongoDB _id
 */
export type CorrectionDocument = Correction;

/**
 * MongoDB Write Concern Configuration
 * 
 * Recommended write concern for correction documents in production.
 * Uses 'majority' to ensure data durability across replica set.
 * 
 * Per Section 0.7.1: "Write concern: majority for replica sets"
 */
export const CORRECTION_WRITE_CONCERN = {
  w: 'majority',
  wtimeout: 5000,
} as const;

/**
 * MongoDB Read Concern Configuration
 * 
 * Recommended read concern for correction documents in production.
 * Uses 'majority' to ensure consistent reads.
 */
export const CORRECTION_READ_CONCERN = {
  level: 'majority',
} as const;
