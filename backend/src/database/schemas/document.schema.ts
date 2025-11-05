/**
 * MongoDB Schema Definition for Documents Collection
 * 
 * Comprehensive schema for storing complete extracted OCR data with nested field structures,
 * confidence scores, validation states, and document metadata.
 * 
 * This is the primary schema for full document-oriented storage containing:
 * - Original file information and metadata
 * - OCR processing results and engine details
 * - Extracted fields with data types and confidence levels
 * - Document classification and entity detection
 * - Validation errors and approval workflows
 * - Processing timestamps and audit trail
 * - Custom user annotations and notes
 * 
 * Supports:
 * - Flexible nested arrays for multi-field extraction
 * - Multi-tenant isolation via account_id
 * - Comprehensive indexing for query performance
 * - GDPR/CCPA compliance with soft delete and PII tracking
 * 
 * Used by:
 * - Document management services
 * - OCR processing pipeline
 * - Search and filtering operations
 * - Export and integration services
 * 
 * References:
 * - Section 0.2.5: Database Schema and Migration Files
 * - Section 0.4.3: MongoDB (Document Storage) integration
 * - Section 0.5.4: Phase 4 - OCR Processing Pipeline
 * - Section 0.5.6: Phase 5 - Document Review and Correction
 * - Section 0.7.1: Data Handling and Privacy requirements
 * 
 * @module DocumentSchema
 */

import { ObjectId } from 'mongodb';

/**
 * Collection name constant for documents
 * Used across all services for consistent collection reference
 */
export const DOCUMENTS_COLLECTION = 'documents';

/**
 * Document processing status enumeration
 * Tracks the lifecycle of a document from upload to approval
 */
export type DocumentStatus = 
  | 'uploaded'    // Initial upload, awaiting queue
  | 'queued'      // In processing queue
  | 'processing'  // Currently being processed by OCR worker
  | 'processed'   // OCR complete, awaiting review
  | 'failed'      // Processing failed
  | 'approved'    // Reviewed and approved
  | 'rejected';   // Reviewed and rejected

/**
 * Document type classification
 * Common document types detected by the classification engine
 */
export type DocumentType = 
  | 'invoice'
  | 'receipt'
  | 'contract'
  | 'form'
  | 'letter'
  | 'statement'
  | 'purchase_order'
  | 'tax_document'
  | 'id_document'
  | 'other';

/**
 * Extracted field data types
 * Defines the type of data extracted from a field for proper validation and formatting
 */
export type FieldType = 
  | 'text'
  | 'number'
  | 'date'
  | 'currency'
  | 'email'
  | 'phone'
  | 'url'
  | 'boolean';

/**
 * Field validation status
 * Tracks the validation state of an extracted field
 */
export type FieldValidationStatus = 
  | 'valid'      // Passed all validation rules
  | 'invalid'    // Failed validation
  | 'pending'    // Not yet validated
  | 'corrected'; // Manually corrected by user

/**
 * OCR engine type
 * Identifies which OCR engine processed the document
 */
export type OcrEngine = 
  | 'tesseract'      // Open-source Tesseract OCR
  | 'google_vision'  // Google Cloud Vision API
  | 'aws_textract';  // AWS Textract

/**
 * Validation rule type enumeration
 * Defines the type of validation rule applied to a field
 */
export type ValidationRuleType = 
  | 'required'  // Field must have a value
  | 'format'    // Field must match format pattern
  | 'range'     // Field must be within range
  | 'custom';   // Custom validation logic

/**
 * Bounding box coordinates for field location on document
 * Defines the rectangular region where a field was found
 * 
 * @property x - X coordinate of top-left corner (pixels)
 * @property y - Y coordinate of top-left corner (pixels)
 * @property width - Width of the bounding box (pixels)
 * @property height - Height of the bounding box (pixels)
 */
export interface IBoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * User annotation on a document page
 * Allows users to add notes and highlights to specific locations
 * 
 * @property page - Page number (1-indexed)
 * @property x - X coordinate on the page
 * @property y - Y coordinate on the page
 * @property text - Annotation text content
 */
export interface IAnnotation {
  page: number;
  x: number;
  y: number;
  text: string;
}

/**
 * Validation error for a specific field
 * Describes a validation failure with field identifier and error message
 * 
 * @property field - Field name that failed validation
 * @property error - Human-readable error message
 */
export interface IValidationError {
  field: string;
  error: string;
}

/**
 * Extracted field from OCR processing
 * Represents a single data field extracted from the document with confidence scoring
 * and validation status. Supports manual corrections and tracks field location.
 * 
 * Example:
 * ```typescript
 * const invoiceNumberField: IExtractedField = {
 *   field_name: 'invoice_number',
 *   field_label: 'Invoice Number',
 *   field_value: 'INV-2025-001234',
 *   field_type: 'text',
 *   confidence_score: 98.5,
 *   validation_status: 'valid',
 *   bounding_box: { x: 100, y: 50, width: 200, height: 30 },
 *   page_number: 1,
 *   is_required: true
 * };
 * ```
 */
export interface IExtractedField {
  /** Unique field identifier (e.g., 'invoice_number', 'total_amount') */
  field_name: string;
  
  /** Human-readable field label (e.g., 'Invoice Number', 'Total Amount') */
  field_label: string;
  
  /** Extracted field value, typed according to field_type */
  field_value: string | number | Date | boolean | null;
  
  /** Data type of the field for proper formatting and validation */
  field_type: FieldType;
  
  /** OCR confidence score from 0-100 (percentage) */
  confidence_score: number;
  
  /** Current validation status of the field */
  validation_status: FieldValidationStatus;
  
  /** Array of validation error messages if validation_status is 'invalid' */
  validation_errors?: string[];
  
  /** Location of the field on the document page */
  bounding_box?: IBoundingBox;
  
  /** Page number where this field was found (1-indexed) */
  page_number?: number;
  
  /** Whether this field is required by validation rules */
  is_required: boolean;
  
  /** User ID who manually corrected this field (if applicable) */
  corrected_by?: string;
  
  /** Timestamp of manual correction */
  corrected_at?: Date;
}

/**
 * OCR processing metadata
 * Contains detailed information about the OCR processing operation including
 * engine used, performance metrics, and preprocessing steps applied.
 * 
 * Example:
 * ```typescript
 * const metadata: IProcessingMetadata = {
 *   ocr_engine: 'google_vision',
 *   ocr_version: 'v1.4',
 *   processing_time_ms: 2500,
 *   page_count: 3,
 *   language: 'en',
 *   preprocessing_applied: ['deskew', 'denoise', 'enhance_contrast'],
 *   ocr_started_at: new Date('2025-10-31T10:00:00Z'),
 *   ocr_completed_at: new Date('2025-10-31T10:00:02.5Z'),
 *   worker_id: 'worker-pod-abc123'
 * };
 * ```
 */
export interface IProcessingMetadata {
  /** OCR engine that processed this document */
  ocr_engine: OcrEngine;
  
  /** Version of the OCR engine used */
  ocr_version: string;
  
  /** Total processing time in milliseconds */
  processing_time_ms: number;
  
  /** Number of pages in the document */
  page_count: number;
  
  /** Detected or specified language (ISO 639-1 code, e.g., 'en', 'es', 'fr') */
  language: string;
  
  /** List of preprocessing steps applied (e.g., ['deskew', 'denoise', 'enhance_contrast']) */
  preprocessing_applied: string[];
  
  /** Timestamp when OCR processing started */
  ocr_started_at: Date;
  
  /** Timestamp when OCR processing completed */
  ocr_completed_at: Date;
  
  /** Identifier of the worker that processed this document */
  worker_id?: string;
}

/**
 * Validation rule definition
 * Defines a validation rule applied to a specific field with configuration parameters
 * and error message for failures.
 * 
 * Example:
 * ```typescript
 * const requiredRule: IValidationRule = {
 *   rule_id: 'rule_001',
 *   rule_type: 'required',
 *   field_name: 'invoice_number',
 *   rule_config: {},
 *   error_message: 'Invoice number is required'
 * };
 * 
 * const formatRule: IValidationRule = {
 *   rule_id: 'rule_002',
 *   rule_type: 'format',
 *   field_name: 'email',
 *   rule_config: { pattern: '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$' },
 *   error_message: 'Email must be in valid format'
 * };
 * ```
 */
export interface IValidationRule {
  /** Unique identifier for this validation rule */
  rule_id: string;
  
  /** Type of validation to perform */
  rule_type: ValidationRuleType;
  
  /** Field name this rule applies to */
  field_name: string;
  
  /** Configuration parameters specific to the rule type */
  rule_config: Record<string, any>;
  
  /** Error message to display if validation fails */
  error_message: string;
}

/**
 * Main Document Schema Interface
 * 
 * Comprehensive document structure for MongoDB storage containing all metadata,
 * OCR results, extracted fields, validation state, and user interactions.
 * 
 * This interface represents the complete document structure stored in the MongoDB
 * 'documents' collection. It supports multi-tenant isolation, flexible field extraction,
 * validation workflows, and comprehensive audit trails.
 * 
 * Performance Considerations:
 * - extracted_text is limited to 1MB; larger texts stored in S3 with reference
 * - Use projection to exclude large fields (raw_ocr_output, extracted_text) when not needed
 * - Indexes are defined for common query patterns (see index definitions below)
 * 
 * GDPR/CCPA Compliance:
 * - PII fields: extracted_text, extracted_fields may contain personal data
 * - Soft delete via deleted_at maintains audit trail
 * - Support for data export via integration_data
 * - Retention policies enforced via deleted_at queries
 * 
 * Example Usage:
 * ```typescript
 * const document: IDocument = {
 *   _id: new ObjectId(),
 *   document_id: 'uuid-v4-string',
 *   account_id: 'acct_123',
 *   file_name: 'invoice_2025_001.pdf',
 *   file_size: 245760,
 *   file_type: 'application/pdf',
 *   file_extension: '.pdf',
 *   storage_path: 's3://bucket/path/to/file.pdf',
 *   page_count: 1,
 *   document_type: 'invoice',
 *   classification_confidence: 95.8,
 *   detected_entities: ['Acme Corp', '2025-01-15', '$1,234.56'],
 *   status: 'processed',
 *   retry_count: 0,
 *   extracted_text: 'Full OCR text...',
 *   extracted_fields: [...],
 *   overall_confidence: 92.5,
 *   low_confidence_fields: ['handwritten_signature'],
 *   validation_rules: [...],
 *   validation_errors: [],
 *   is_validated: true,
 *   uploaded_by: 'user_456',
 *   tags: ['Q1-2025', 'vendor-acme'],
 *   created_at: new Date(),
 *   updated_at: new Date(),
 *   processing_metadata: {...},
 *   custom_fields: {},
 *   version: 1
 * };
 * ```
 */
export interface IDocument {
  // === MongoDB Document ID ===
  /** MongoDB internal document identifier */
  _id: ObjectId;
  
  // === Primary Identifiers ===
  /** Unique document identifier (UUID v4) - indexed, unique */
  document_id: string;
  
  /** Account identifier for multi-tenant isolation - indexed */
  account_id: string;
  
  // === File Metadata ===
  /** Original filename as uploaded by user */
  file_name: string;
  
  /** File size in bytes */
  file_size: number;
  
  /** MIME type (e.g., 'application/pdf', 'image/jpeg', 'image/png') */
  file_type: string;
  
  /** File extension (e.g., '.pdf', '.jpg', '.png') */
  file_extension?: string;
  
  /** Storage path (S3 URL or file system path) */
  storage_path: string;
  
  /** Path to generated thumbnail image for preview */
  thumbnail_path?: string;
  
  /** Number of pages in the document */
  page_count: number;
  
  // === Document Classification ===
  /** Document type classification (invoice, receipt, contract, etc.) - indexed */
  document_type?: string;
  
  /** More specific document subtype for fine-grained classification */
  document_subtype?: string;
  
  /** Confidence score (0-100) for document type classification */
  classification_confidence?: number;
  
  /** Array of named entities detected in the document (company names, dates, amounts) */
  detected_entities: string[];
  
  // === Processing Status ===
  /** Current processing status - indexed */
  status: DocumentStatus;
  
  /** Error message if processing failed (status === 'failed') */
  processing_error?: string;
  
  /** Number of processing retry attempts */
  retry_count: number;
  
  /** Timestamp of last retry attempt */
  last_retry_at?: Date;
  
  // === Extracted Data ===
  /** Full OCR-extracted text content (limited to 1MB, larger stored in S3) */
  extracted_text?: string;
  
  /** Array of structured extracted fields with confidence scores and validation */
  extracted_fields: IExtractedField[];
  
  /** Complete raw output from OCR engine (for debugging and reprocessing) */
  raw_ocr_output?: any;
  
  /** Overall confidence score (0-100) averaged across all extracted fields */
  overall_confidence: number;
  
  /** Array of field names with confidence below threshold requiring review */
  low_confidence_fields: string[];
  
  // === Validation ===
  /** Array of validation rules applied to this document */
  validation_rules: IValidationRule[];
  
  /** Array of current validation errors */
  validation_errors: IValidationError[];
  
  /** Whether all validation rules have passed */
  is_validated: boolean;
  
  /** User ID who validated the document */
  validated_by?: string;
  
  /** Timestamp of validation completion */
  validated_at?: Date;
  
  // === Template Association ===
  /** ID of template used for extraction (if applicable) - indexed */
  template_id?: string;
  
  /** Version of template used */
  template_version?: number;
  
  // === User Interaction ===
  /** User ID who uploaded this document - indexed */
  uploaded_by: string;
  
  /** User ID assigned to review this document */
  assigned_to?: string;
  
  /** User ID who reviewed this document */
  reviewed_by?: string;
  
  /** User ID who approved this document */
  approved_by?: string;
  
  /** User-defined tags for categorization and search */
  tags: string[];
  
  /** User notes or comments about the document */
  notes?: string;
  
  /** Array of user annotations on document pages */
  annotations?: IAnnotation[];
  
  // === Timestamps ===
  /** Document upload timestamp - indexed */
  created_at: Date;
  
  /** Last modification timestamp */
  updated_at: Date;
  
  /** Processing completion timestamp */
  processed_at?: Date;
  
  /** Approval timestamp */
  approved_at?: Date;
  
  /** Soft delete timestamp (null if not deleted) */
  deleted_at?: Date | null;
  
  // === Metadata ===
  /** OCR processing metadata and performance metrics */
  processing_metadata: IProcessingMetadata;
  
  /** User-defined custom fields for flexible data storage */
  custom_fields: Record<string, any>;
  
  /** Third-party integration metadata (QuickBooks, Salesforce, etc.) */
  integration_data?: Record<string, any>;
  
  /** Document version number for change tracking */
  version: number;
}

/**
 * Create Document DTO Type
 * Used for inserting new documents, omitting auto-generated fields
 * 
 * Omits: _id (generated by MongoDB), created_at, updated_at (set by application)
 */
export type CreateDocumentDto = Omit<IDocument, '_id' | 'created_at' | 'updated_at'>;

/**
 * Update Document DTO Type
 * Used for updating existing documents, all fields optional except identifiers
 * 
 * Partial type allows updating only specific fields
 */
export type UpdateDocumentDto = Partial<Omit<IDocument, '_id' | 'document_id' | 'account_id'>>;

/**
 * Type guard to check if a string is a valid DocumentStatus
 * 
 * @param status - String to validate
 * @returns True if status is a valid DocumentStatus
 * 
 * @example
 * ```typescript
 * if (isValidStatus(userInput)) {
 *   document.status = userInput;
 * }
 * ```
 */
export function isValidStatus(status: string): status is DocumentStatus {
  const validStatuses: DocumentStatus[] = [
    'uploaded',
    'queued',
    'processing',
    'processed',
    'failed',
    'approved',
    'rejected'
  ];
  return validStatuses.includes(status as DocumentStatus);
}

/**
 * Type guard to check if a string is a valid FieldType
 * 
 * @param type - String to validate
 * @returns True if type is a valid FieldType
 * 
 * @example
 * ```typescript
 * if (isValidFieldType(userType)) {
 *   field.field_type = userType;
 * }
 * ```
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
    'boolean'
  ];
  return validTypes.includes(type as FieldType);
}

/**
 * Validates a document ID format (UUID v4)
 * 
 * @param id - Document ID to validate
 * @returns True if ID matches UUID v4 format
 * 
 * @example
 * ```typescript
 * if (!validateDocumentId(documentId)) {
 *   throw new Error('Invalid document ID format');
 * }
 * ```
 */
export function validateDocumentId(id: string): boolean {
  // UUID v4 format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
  const uuidV4Regex = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  return uuidV4Regex.test(id);
}

/**
 * Calculates the overall confidence score from an array of extracted fields
 * 
 * Computes the average confidence score across all fields, excluding fields
 * with null or undefined confidence scores.
 * 
 * @param fields - Array of extracted fields with confidence scores
 * @returns Average confidence score from 0-100, or 0 if no valid fields
 * 
 * @example
 * ```typescript
 * const fields: IExtractedField[] = [
 *   { field_name: 'name', confidence_score: 95, ... },
 *   { field_name: 'date', confidence_score: 88, ... },
 *   { field_name: 'amount', confidence_score: 92, ... }
 * ];
 * 
 * const overall = calculateOverallConfidence(fields); // Returns 91.67
 * ```
 */
export function calculateOverallConfidence(fields: IExtractedField[]): number {
  if (!fields || fields.length === 0) {
    return 0;
  }
  
  // Filter out fields without confidence scores
  const fieldsWithConfidence = fields.filter(
    field => typeof field.confidence_score === 'number' && !isNaN(field.confidence_score)
  );
  
  if (fieldsWithConfidence.length === 0) {
    return 0;
  }
  
  // Calculate average confidence
  const totalConfidence = fieldsWithConfidence.reduce(
    (sum, field) => sum + field.confidence_score,
    0
  );
  
  const averageConfidence = totalConfidence / fieldsWithConfidence.length;
  
  // Round to 2 decimal places
  return Math.round(averageConfidence * 100) / 100;
}

/**
 * MongoDB Collection Indexes
 * 
 * The following indexes should be created on the 'documents' collection for optimal query performance:
 * 
 * Single Field Indexes:
 * ```javascript
 * db.documents.createIndex({ "document_id": 1 }, { unique: true });
 * db.documents.createIndex({ "account_id": 1 });
 * db.documents.createIndex({ "status": 1 });
 * db.documents.createIndex({ "document_type": 1 });
 * db.documents.createIndex({ "created_at": -1 });
 * db.documents.createIndex({ "uploaded_by": 1 });
 * db.documents.createIndex({ "template_id": 1 });
 * ```
 * 
 * Compound Indexes (for common query patterns):
 * ```javascript
 * // Tenant documents by status and date (most common query)
 * db.documents.createIndex({ "account_id": 1, "status": 1, "created_at": -1 });
 * 
 * // Tenant documents by type
 * db.documents.createIndex({ "account_id": 1, "document_type": 1 });
 * 
 * // User's document timeline
 * db.documents.createIndex({ "account_id": 1, "uploaded_by": 1, "created_at": -1 });
 * 
 * // Processing queue order
 * db.documents.createIndex({ "status": 1, "created_at": -1 });
 * 
 * // Validated documents filter
 * db.documents.createIndex({ "account_id": 1, "is_validated": 1 });
 * ```
 * 
 * Text Index (for full-text search):
 * ```javascript
 * db.documents.createIndex({
 *   "extracted_text": "text",
 *   "extracted_fields.field_value": "text"
 * }, {
 *   name: "document_text_search",
 *   default_language: "english",
 *   weights: {
 *     "extracted_text": 1,
 *     "extracted_fields.field_value": 2
 *   }
 * });
 * ```
 * 
 * Performance Notes:
 * - Indexes should be created during database initialization
 * - Monitor index usage with db.documents.aggregate([{$indexStats:{}}])
 * - Consider partial indexes for soft-deleted documents: { deleted_at: null }
 * - For multi-region deployments, consider sharding by account_id
 */

/**
 * Collection Configuration Guidelines
 * 
 * Collection: documents
 * Schema Validation: Optional (flexible schema for custom_fields)
 * Capped Collection: No (need full document history)
 * Write Concern: majority (for replica sets, ensures data durability)
 * Read Concern: majority (ensures consistent reads)
 * 
 * Data Retention:
 * - Default retention: 7 years per Section 0.7.1
 * - Soft delete via deleted_at field for audit trail
 * - Implement TTL index on deleted_at for automatic cleanup after retention period:
 *   ```javascript
 *   db.documents.createIndex(
 *     { "deleted_at": 1 },
 *     { expireAfterSeconds: 220752000 } // 7 years in seconds
 *   );
 *   ```
 * 
 * Security:
 * - All queries MUST include account_id for tenant isolation (Section 0.7.1)
 * - Field-level encryption for sensitive data in custom_fields
 * - PII fields: extracted_text, extracted_fields require GDPR compliance
 * - Audit all access via application-level logging
 * 
 * Backup and Recovery:
 * - Point-in-time recovery enabled
 * - Daily snapshots with 30-day retention
 * - Replica set with minimum 3 nodes in production
 */
