/**
 * MongoDB Schema Definition for Document Versions Collection
 * 
 * Purpose:
 * - Tracks complete version history of document changes for audit trail and rollback capabilities
 * - Stores full document snapshots on each modification including extracted field updates,
 *   status changes, user corrections, and approval actions
 * - Provides comprehensive audit logging per Section 0.7.1 Security requirement #5
 * - Enables version comparison and rollback support per Phase 5 Document Review requirements
 * 
 * Collection Name: document_versions
 * Database: MongoDB
 * 
 * Performance Considerations:
 * - Indexed on document_id and version_number for efficient version retrieval
 * - Compound indexes optimize queries for tenant isolation and timeline views
 * - Snapshot storage can grow large; consider archival strategy after 90 days
 * - Write concern: majority for replica sets ensures durability
 * 
 * References:
 * - Section 0.2.5: MongoDB Schema Definitions
 * - Section 0.4.3: MongoDB Integration Points
 * - Section 0.5.6: Phase 5 - Document Review and Correction
 * - Section 0.7.1: Audit Logging Requirements
 * 
 * @module database/schemas/document-version
 */

import { ObjectId } from 'mongodb';

/**
 * Valid change types for document versions
 * Categorizes the type of modification that created a new version
 */
export type ChangeType = 
  | 'created'          // Initial document creation
  | 'field_updated'    // One or more fields modified
  | 'status_changed'   // Document status transition
  | 'approved'         // Document approved by reviewer
  | 'rejected'         // Document rejected by reviewer
  | 'corrected';       // User correction applied (for ML training)

/**
 * Status change tracking
 * Records transitions between document statuses
 */
export interface StatusChange {
  /** Previous status value */
  from: string;
  
  /** New status value */
  to: string;
}

/**
 * Individual field modification details
 * Captures what changed in a specific field between versions
 */
export interface FieldModification {
  /** Name of the field that was modified */
  field: string;
  
  /** Previous field value (can be any type) */
  old_value: unknown;
  
  /** New field value (can be any type) */
  new_value: unknown;
}

/**
 * Summary of changes between versions
 * Provides quick overview of what changed without comparing full snapshots
 */
export interface ChangesSummary {
  /** Array of field names that were added in this version */
  fields_added?: string[];
  
  /** Array of field names that were removed in this version */
  fields_removed?: string[];
  
  /** Array of detailed field modifications showing old and new values */
  fields_modified?: FieldModification[];
  
  /** Status transition details if status changed */
  status_change?: StatusChange;
}

/**
 * Complete document snapshot structure
 * Stores the full state of a document at a specific point in time
 * This mirrors the main document schema but is frozen as historical record
 */
export interface DocumentSnapshot {
  /** Document ID reference (same as parent document_id) */
  document_id: string;
  
  /** Original filename of uploaded document */
  file_name: string;
  
  /** File size in bytes */
  file_size: number;
  
  /** MIME type (application/pdf, image/jpeg, image/png, etc.) */
  file_type: string;
  
  /** Document processing status (uploaded, processing, processed, approved, rejected, failed) */
  status: string;
  
  /** Classified document type (invoice, receipt, contract, form, etc.) */
  document_type: string;
  
  /** 
   * All extracted fields from OCR/NLP processing
   * Structure varies by document type
   * Example: { invoice_number: "INV-001", total: 1500.00, date: "2025-10-31" }
   */
  extracted_data: Record<string, unknown>;
  
  /** 
   * Confidence scores for each extracted field (0-100)
   * Example: { invoice_number: 98.5, total: 95.2, date: 99.1 }
   */
  confidence_scores: Record<string, number>;
  
  /** 
   * OCR/NLP processing metadata
   * Example: { ocr_engine: "tesseract", processing_time_ms: 2500, pages: 3 }
   */
  processing_metadata: object;
  
  /** User-assigned tags for organization and search */
  tags: string[];
  
  /** 
   * Custom fields defined by user or template
   * Flexible structure for business-specific data
   */
  custom_fields: Record<string, unknown>;
}

/**
 * Main Document Version Interface
 * Represents a single version entry in the document_versions collection
 * 
 * Each version is immutable and captures:
 * - Who made the change (user ID and email)
 * - When it was made (timestamp)
 * - Why it was made (change reason)
 * - What changed (changes summary)
 * - Complete document state at that moment (snapshot)
 * 
 * Usage Example:
 * ```typescript
 * const version: DocumentVersion = {
 *   _id: new ObjectId(),
 *   document_id: "doc_123",
 *   account_id: "acc_456",
 *   version_number: 2,
 *   change_type: "field_updated",
 *   changed_by: "user_789",
 *   changed_by_email: "john@example.com",
 *   created_at: new Date(),
 *   document_snapshot: { ... },
 *   changes_summary: {
 *     fields_modified: [{
 *       field: "total",
 *       old_value: 1500.00,
 *       new_value: 1575.00
 *     }]
 *   },
 *   change_reason: "Corrected invoice total after verification"
 * };
 * ```
 */
export interface DocumentVersion {
  /** MongoDB document ID (primary key) */
  _id: ObjectId;
  
  /** 
   * Reference to the original document in documents collection
   * INDEXED for efficient version history retrieval
   */
  document_id: string;
  
  /** 
   * Account ID for multi-tenant isolation
   * INDEXED to ensure tenant boundary enforcement
   */
  account_id: string;
  
  /** 
   * Sequential version number starting from 1
   * INDEXED with document_id for version ordering
   * Unique constraint: (document_id, version_number)
   */
  version_number: number;
  
  /** 
   * Type of change that created this version
   * Used for filtering and analytics
   * INDEXED for change type queries
   */
  change_type: ChangeType;
  
  /** 
   * User ID who made the change
   * INDEXED for user activity audit
   */
  changed_by: string;
  
  /** 
   * Email of user who made the change
   * Stored for audit trail even if user account is deleted
   */
  changed_by_email: string;
  
  /** 
   * Timestamp when version was created
   * INDEXED (descending) for recent-first queries
   * Immutable once set
   */
  created_at: Date;
  
  /** 
   * Complete snapshot of document state at this version
   * Contains all extracted data, confidence scores, metadata
   * Large object - consider archival for old versions
   */
  document_snapshot: DocumentSnapshot;
  
  /** 
   * Optional summary of what changed from previous version
   * Helps avoid full snapshot comparison for quick diffs
   * Generated during version creation
   */
  changes_summary?: ChangesSummary;
  
  /** 
   * Optional user-provided reason for the change
   * Useful for approval/rejection actions
   */
  change_reason?: string;
  
  /** 
   * IP address of user making change
   * Captured for security audit trail
   */
  ip_address?: string;
  
  /** 
   * Browser/client user agent string
   * Helps identify how change was made (web, mobile, API)
   */
  user_agent?: string;
  
  /** 
   * Additional flexible metadata
   * Can store integration context, workflow info, etc.
   */
  metadata?: Record<string, unknown>;
}

/**
 * DTO for creating new document versions
 * Omits MongoDB-generated _id field
 */
export type CreateDocumentVersionDto = Omit<DocumentVersion, '_id'>;

/**
 * Collection name constant
 * Use this constant throughout the application for consistency
 * 
 * @constant
 */
export const DOCUMENT_VERSIONS_COLLECTION = 'document_versions';

/**
 * MongoDB Collection Indexes
 * 
 * These indexes must be created during database setup for optimal performance.
 * 
 * Single Field Indexes:
 * 1. { document_id: 1 } - Get all versions for a document
 * 2. { account_id: 1 } - Tenant isolation queries
 * 3. { created_at: -1 } - Recent versions first (descending)
 * 4. { changed_by: 1 } - Versions by user for activity tracking
 * 
 * Compound Indexes:
 * 1. { document_id: 1, version_number: -1 } (UNIQUE) - Latest version lookup
 *    Example: db.document_versions.find({document_id: "doc_123"}).sort({version_number: -1}).limit(1)
 * 
 * 2. { account_id: 1, created_at: -1 } - Tenant version timeline
 *    Example: db.document_versions.find({account_id: "acc_456"}).sort({created_at: -1})
 * 
 * 3. { document_id: 1, change_type: 1 } - Filter by change type
 *    Example: db.document_versions.find({document_id: "doc_123", change_type: "corrected"})
 * 
 * 4. { changed_by: 1, created_at: -1 } - User change history
 *    Example: db.document_versions.find({changed_by: "user_789"}).sort({created_at: -1})
 * 
 * Index Creation Commands (MongoDB Shell):
 * ```javascript
 * db.document_versions.createIndex({ document_id: 1 });
 * db.document_versions.createIndex({ account_id: 1 });
 * db.document_versions.createIndex({ created_at: -1 });
 * db.document_versions.createIndex({ changed_by: 1 });
 * db.document_versions.createIndex({ document_id: 1, version_number: -1 }, { unique: true });
 * db.document_versions.createIndex({ account_id: 1, created_at: -1 });
 * db.document_versions.createIndex({ document_id: 1, change_type: 1 });
 * db.document_versions.createIndex({ changed_by: 1, created_at: -1 });
 * ```
 */

/**
 * Collection Configuration Notes
 * 
 * Collection Name: document_versions
 * Database: MongoDB (per Section 0.4.3)
 * 
 * Schema Validation: None enforced at database level (flexible schema)
 * - Application enforces structure via TypeScript interfaces
 * 
 * Write Concern: majority
 * - Ensures version is written to majority of replica set members
 * - Critical for audit trail integrity
 * 
 * Timestamps:
 * - created_at is explicitly managed (not using MongoDB timestamps)
 * - Versions are immutable (no updated_at field)
 * 
 * Capped Collection: No
 * - Need complete history for compliance
 * - Implement application-level archival for old versions
 * 
 * Data Retention:
 * - Versions retained per data retention policy (default 7 years, min 1 year)
 * - Consider moving versions older than 90 days to archival storage
 * - Implement cleanup mechanism when parent document is permanently deleted
 * - Per Section 0.7.1 requirement #3: All data retention follows configurable policies
 * 
 * Storage Considerations:
 * - Full document snapshots can be large (especially for multi-page PDFs)
 * - Monitor collection size and plan for sharding if needed
 * - Average version size estimate: 50KB - 500KB depending on extracted data
 * - For high-volume accounts (1000+ docs/day), expect 5GB-50GB monthly growth
 * 
 * Query Patterns:
 * - Most common: Get latest version for a document
 * - Second most: Get all versions for a document (paginated)
 * - Important: Filter by change_type (e.g., show only "corrected" versions for ML training)
 * - Audit queries: All changes by a user in a date range
 * 
 * Rollback Support:
 * - To rollback: Copy document_snapshot from target version to main documents collection
 * - Create new version entry with change_type: "rollback" (not in enum but can extend)
 * - Maintain version lineage even after rollback
 * 
 * Integration with Main Documents Collection:
 * - document_id links to documents.document_id (not _id)
 * - Version created on every document mutation (field update, status change)
 * - First version (version_number: 1) created with change_type: "created"
 * 
 * Security and Access Control:
 * - Always filter by account_id to enforce multi-tenant isolation
 * - Version history accessible only to users with document read permission
 * - Audit log access may require elevated permissions (admin role)
 * 
 * Performance Optimization:
 * - Use projection to exclude large document_snapshot when only metadata needed
 * - Example: db.document_versions.find({document_id: "doc_123"}, {document_snapshot: 0})
 * - Implement pagination for version history lists (default: 25 per page, max: 100)
 * - Cache latest version number in Redis to avoid database query on every write
 */

/**
 * Type Validation Functions
 * These are helper type guards that can be used at runtime
 */

/**
 * Type guard to check if a value is a valid ChangeType
 * @param value - Value to check
 * @returns True if value is a valid ChangeType
 */
export function isValidChangeType(value: string): value is ChangeType {
  const validTypes: ChangeType[] = [
    'created',
    'field_updated',
    'status_changed',
    'approved',
    'rejected',
    'corrected',
  ];
  return validTypes.includes(value as ChangeType);
}

/**
 * Validates that a document version object has all required fields
 * @param obj - Object to validate
 * @returns True if object is a valid DocumentVersion structure
 */
export function isValidDocumentVersion(obj: unknown): obj is DocumentVersion {
  if (!obj || typeof obj !== 'object') {
    return false;
  }
  
  const record = obj as Record<string, unknown>;
  
  return (
    record._id instanceof ObjectId &&
    typeof record.document_id === 'string' &&
    typeof record.account_id === 'string' &&
    typeof record.version_number === 'number' &&
    record.version_number >= 1 &&
    typeof record.change_type === 'string' &&
    isValidChangeType(record.change_type) &&
    typeof record.changed_by === 'string' &&
    typeof record.changed_by_email === 'string' &&
    record.created_at instanceof Date &&
    record.document_snapshot !== null &&
    record.document_snapshot !== undefined &&
    typeof record.document_snapshot === 'object'
  );
}

/**
 * Validates that a document snapshot has required fields
 * @param obj - Object to validate
 * @returns True if object is a valid DocumentSnapshot structure
 */
export function isValidDocumentSnapshot(obj: unknown): obj is DocumentSnapshot {
  if (!obj || typeof obj !== 'object') {
    return false;
  }
  
  const record = obj as Record<string, unknown>;
  
  return (
    typeof record.document_id === 'string' &&
    typeof record.file_name === 'string' &&
    typeof record.file_size === 'number' &&
    typeof record.file_type === 'string' &&
    typeof record.status === 'string' &&
    typeof record.document_type === 'string' &&
    record.extracted_data !== null &&
    record.extracted_data !== undefined &&
    typeof record.extracted_data === 'object' &&
    record.confidence_scores !== null &&
    record.confidence_scores !== undefined &&
    typeof record.confidence_scores === 'object' &&
    record.processing_metadata !== null &&
    record.processing_metadata !== undefined &&
    typeof record.processing_metadata === 'object' &&
    Array.isArray(record.tags) &&
    record.custom_fields !== null &&
    record.custom_fields !== undefined &&
    typeof record.custom_fields === 'object'
  );
}

/**
 * Example Usage
 * 
 * Creating a version on document creation:
 * ```typescript
 * import type { ObjectId } from 'mongodb';
 * import { DocumentVersion, CreateDocumentVersionDto, DOCUMENT_VERSIONS_COLLECTION } from './document-version.schema';
 * 
 * const newVersion: CreateDocumentVersionDto = {
 *   document_id: 'doc_abc123',
 *   account_id: 'acc_xyz789',
 *   version_number: 1,
 *   change_type: 'created',
 *   changed_by: 'user_456',
 *   changed_by_email: 'user@example.com',
 *   created_at: new Date(),
 *   document_snapshot: {
 *     document_id: 'doc_abc123',
 *     file_name: 'invoice_2025.pdf',
 *     file_size: 524288,
 *     file_type: 'application/pdf',
 *     status: 'processed',
 *     document_type: 'invoice',
 *     extracted_data: {
 *       invoice_number: 'INV-2025-001',
 *       invoice_date: '2025-10-31',
 *       total_amount: 1500.00,
 *       vendor_name: 'Acme Corp'
 *     },
 *     confidence_scores: {
 *       invoice_number: 98.5,
 *       invoice_date: 99.2,
 *       total_amount: 95.7,
 *       vendor_name: 97.3
 *     },
 *     processing_metadata: {
 *       ocr_engine: 'tesseract',
 *       processing_time_ms: 2500,
 *       page_count: 1
 *     },
 *     tags: ['finance', 'vendor-acme'],
 *     custom_fields: {}
 *   }
 * };
 * 
 * await db.collection(DOCUMENT_VERSIONS_COLLECTION).insertOne(newVersion);
 * ```
 * 
 * Creating a version after field correction:
 * ```typescript
 * const correctionVersion: CreateDocumentVersionDto = {
 *   document_id: 'doc_abc123',
 *   account_id: 'acc_xyz789',
 *   version_number: 2,
 *   change_type: 'corrected',
 *   changed_by: 'user_456',
 *   changed_by_email: 'user@example.com',
 *   created_at: new Date(),
 *   document_snapshot: {
 *     // ... full updated document state
 *     extracted_data: {
 *       invoice_number: 'INV-2025-001',
 *       invoice_date: '2025-10-31',
 *       total_amount: 1575.00, // Corrected value
 *       vendor_name: 'Acme Corp'
 *     }
 *   },
 *   changes_summary: {
 *     fields_modified: [{
 *       field: 'total_amount',
 *       old_value: 1500.00,
 *       new_value: 1575.00
 *     }]
 *   },
 *   change_reason: 'Corrected total amount after manual verification with original PDF',
 *   ip_address: '192.168.1.100',
 *   user_agent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)...'
 * };
 * 
 * await db.collection(DOCUMENT_VERSIONS_COLLECTION).insertOne(correctionVersion);
 * ```
 * 
 * Querying version history:
 * ```typescript
 * // Get all versions for a document
 * const versions = await db.collection(DOCUMENT_VERSIONS_COLLECTION)
 *   .find({ document_id: 'doc_abc123', account_id: 'acc_xyz789' })
 *   .sort({ version_number: -1 })
 *   .toArray();
 * 
 * // Get latest version
 * const latestVersion = await db.collection(DOCUMENT_VERSIONS_COLLECTION)
 *   .findOne(
 *     { document_id: 'doc_abc123', account_id: 'acc_xyz789' },
 *     { sort: { version_number: -1 } }
 *   );
 * 
 * // Get only corrections (for ML training)
 * const corrections = await db.collection(DOCUMENT_VERSIONS_COLLECTION)
 *   .find({
 *     document_id: 'doc_abc123',
 *     account_id: 'acc_xyz789',
 *     change_type: 'corrected'
 *   })
 *   .toArray();
 * ```
 */
