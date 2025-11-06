/**
 * Storage Module Type Definitions
 * 
 * Comprehensive TypeScript type definitions for the storage module supporting
 * AWS S3 integration with document management, virus scanning, and lifecycle policies.
 * 
 * Architecture Requirements:
 * - Multi-bucket organization (documents, processed, exports)
 * - Presigned URL support with configurable expiry (default 15 minutes)
 * - AES-256 encryption at rest per Section 0.7.1 security requirements
 * - Lifecycle policies: Glacier transition (90 days), deletion (7 years)
 * - Virus scanning integration with ClamAV per Phase 3 requirements
 * - Multi-part upload support for large files
 * 
 * @module storage.types
 */

/**
 * Storage bucket types for organizing documents throughout their lifecycle.
 * 
 * Per Section 0.5.4 Phase 3 requirements:
 * - DOCUMENTS: Original uploaded documents awaiting or in processing
 * - PROCESSED: Successfully processed documents with extracted data
 * - EXPORTS: Generated export files (JSON, CSV, XML, Excel)
 */
export enum StorageBucket {
  /** Original uploaded documents bucket */
  DOCUMENTS = 'documents',
  
  /** Processed documents with OCR/NLP completed */
  PROCESSED = 'processed',
  
  /** Generated export files bucket */
  EXPORTS = 'exports',
}

/**
 * Virus scan status enumeration.
 * 
 * Per Section 0.7.1: ALL uploaded files MUST be virus scanned before processing.
 */
export enum ScanStatus {
  /** File passed virus scan - safe to process */
  CLEAN = 'CLEAN',
  
  /** File contains malware or virus - must be quarantined */
  INFECTED = 'INFECTED',
  
  /** Virus scan failed to complete - retry or manual review required */
  SCAN_FAILED = 'SCAN_FAILED',
}

/**
 * Storage error codes for comprehensive error handling.
 * 
 * Per Section 0.7.1: ALL error handling MUST be explicit (no empty catch blocks).
 */
export enum StorageErrorCode {
  /** File upload operation failed */
  UPLOAD_FAILED = 'UPLOAD_FAILED',
  
  /** File download operation failed */
  DOWNLOAD_FAILED = 'DOWNLOAD_FAILED',
  
  /** File deletion operation failed */
  DELETE_FAILED = 'DELETE_FAILED',
  
  /** Requested file does not exist in storage */
  FILE_NOT_FOUND = 'FILE_NOT_FOUND',
  
  /** Virus detected during scan - file quarantined */
  VIRUS_DETECTED = 'VIRUS_DETECTED',
  
  /** Virus scanning service failed */
  SCAN_FAILED = 'SCAN_FAILED',
  
  /** Invalid or non-existent bucket specified */
  INVALID_BUCKET = 'INVALID_BUCKET',
  
  /** Insufficient permissions for storage operation */
  PERMISSION_DENIED = 'PERMISSION_DENIED',
  
  /** Storage quota exceeded for account */
  QUOTA_EXCEEDED = 'QUOTA_EXCEEDED',
}

/**
 * File upload options for configuring storage behavior.
 * 
 * Supports encryption, metadata, multi-part uploads for large files,
 * and custom content type handling per Section 0.4.4 AWS S3 integration.
 */
export interface UploadOptions {
  /**
   * Target storage bucket for the file.
   * Defaults to StorageBucket.DOCUMENTS for new uploads.
   */
  bucket: StorageBucket;
  
  /**
   * MIME type of the file (e.g., 'application/pdf', 'image/jpeg').
   * Used for proper content serving and validation.
   * Must be validated against allowed file types per security requirements.
   */
  contentType: string;
  
  /**
   * Custom metadata key-value pairs to store with the file.
   * Useful for storing account_id, user_id, document_type, etc.
   * Maximum 2KB of metadata per AWS S3 limits.
   */
  metadata?: Record<string, string>;
  
  /**
   * Encryption configuration for data at rest.
   * Per Section 0.7.1: ALL data MUST be encrypted at rest (AES-256).
   * Defaults to 'AES256' (SSE-S3) if not specified.
   */
  encryption?: 'AES256' | 'aws:kms';
  
  /**
   * Multi-part upload options for files larger than 5MB.
   * Enables parallel uploads and resumable uploads for large documents.
   */
  multiPartOptions?: MultiPartUploadOptions;
  
  /**
   * Cache-Control header value for controlling CDN/browser caching.
   * Example: 'max-age=31536000' for immutable files.
   */
  cacheControl?: string;
  
  /**
   * Content-Disposition header for controlling download behavior.
   * Example: 'attachment; filename="document.pdf"'
   */
  contentDisposition?: string;
  
  /**
   * ACL (Access Control List) for the uploaded file.
   * Per Section 0.4.4: Use presigned URLs for access, keep files private.
   * Defaults to 'private'.
   */
  acl?: 'private' | 'public-read';
}

/**
 * File download options for generating presigned URLs.
 * 
 * Per Section 0.4.4: Presigned URLs for temporary access (15-minute expiry default).
 */
export interface DownloadOptions {
  /**
   * Expiry time for presigned URL in seconds.
   * Per Section 0.4.4 AWS S3 integration: Default 15 minutes (900 seconds).
   * Maximum recommended: 1 hour for security best practices.
   */
  expiresIn?: number;
  
  /**
   * Override Content-Type header in the presigned URL response.
   * Useful for forcing browsers to handle files differently than stored type.
   */
  responseContentType?: string;
  
  /**
   * Override Content-Disposition header in the presigned URL response.
   * Controls whether file is displayed inline or downloaded.
   * Example: 'attachment; filename="report.pdf"' or 'inline'
   */
  responseContentDisposition?: string;
  
  /**
   * Override Cache-Control header in the presigned URL response.
   */
  responseCacheControl?: string;
}

/**
 * File metadata interface capturing comprehensive file information.
 * 
 * Returned from upload operations and list operations.
 * All timestamps in ISO 8601 UTC format per Section 0.7.2 database guidelines.
 */
export interface FileMetadata {
  /**
   * Unique file key (path) within the bucket.
   * Format: '{account_id}/{document_id}/{filename}'
   */
  key: string;
  
  /**
   * Storage bucket containing the file.
   */
  bucket: StorageBucket;
  
  /**
   * File size in bytes.
   * Used for quota tracking and multi-part upload decisions.
   */
  size: number;
  
  /**
   * MIME type of the file.
   * Validated on upload per Section 0.7.1 security requirements.
   */
  contentType: string;
  
  /**
   * Entity tag (ETag) for file integrity verification.
   * MD5 hash for single-part uploads, composite for multi-part.
   */
  etag: string;
  
  /**
   * Last modified timestamp from S3 (ISO 8601 UTC).
   */
  lastModified: Date;
  
  /**
   * Upload completion timestamp (ISO 8601 UTC).
   * Stored in metadata for precise tracking.
   */
  uploadedAt: Date;
  
  /**
   * Indicates if file is encrypted at rest.
   * Per Section 0.7.1: ALL files MUST be encrypted.
   */
  isEncrypted: boolean;
  
  /**
   * Custom metadata stored with the file.
   * Includes account_id, user_id, document_type, etc.
   */
  metadata?: Record<string, string>;
  
  /**
   * Storage class of the file (STANDARD, GLACIER, etc.).
   * Changes based on lifecycle policy application.
   */
  storageClass?: string;
  
  /**
   * Version ID for versioned buckets.
   * Enables version control and rollback capabilities.
   */
  versionId?: string;
}

/**
 * Virus scan result interface.
 * 
 * Per Section 0.7.1: ALL uploaded files MUST be virus scanned before processing (ClamAV).
 */
export interface VirusScanResult {
  /**
   * Scan status indicating if file is safe, infected, or scan failed.
   */
  scanStatus: ScanStatus;
  
  /**
   * Name of detected threat/virus if scanStatus is INFECTED.
   * Null for clean files or failed scans.
   */
  threatName: string | null;
  
  /**
   * Timestamp when virus scan completed (ISO 8601 UTC).
   * Used for tracking scan freshness and audit logging.
   */
  scanTimestamp: Date;
  
  /**
   * Version of the virus scanner/signature database used.
   * Format: 'ClamAV 1.0.0 / DB Version 27123'
   * Useful for troubleshooting and compliance reporting.
   */
  scannerVersion: string;
  
  /**
   * Detailed scan information or error message.
   * Provides context for failed scans or suspicious patterns.
   */
  scanDetails?: string;
}

/**
 * Multi-part upload options for handling large files efficiently.
 * 
 * Per AWS S3 best practices: Use multi-part for files >100MB.
 * Required for files >5GB (AWS limit for single-part uploads).
 */
export interface MultiPartUploadOptions {
  /**
   * Size of each part in bytes.
   * AWS S3 requirements: Min 5MB (except last part), Max 5GB per part.
   * Default: 10MB for optimal performance/reliability balance.
   */
  partSize?: number;
  
  /**
   * Maximum number of concurrent part uploads.
   * Default: 4 to balance throughput and resource usage.
   * Increase for high-bandwidth environments.
   */
  queueSize?: number;
  
  /**
   * Whether to leave uploaded parts on error for manual cleanup.
   * Default: false (auto-cleanup failed uploads to avoid storage costs).
   * Set true for debugging or manual recovery scenarios.
   */
  leavePartsOnError?: boolean;
  
  /**
   * Maximum number of retry attempts for failed part uploads.
   * Default: 3 attempts with exponential backoff.
   */
  maxRetries?: number;
  
  /**
   * Timeout for each part upload in milliseconds.
   * Default: 60000 (1 minute).
   */
  partUploadTimeout?: number;
}

/**
 * Lifecycle policy configuration for automated archival and deletion.
 * 
 * Per Section 0.7.1 Security Requirements and Section 0.4.4 AWS S3 Integration:
 * - Transition to Glacier: 90 days after upload
 * - Deletion: 7 years (2555 days) after upload for compliance
 */
export interface LifecyclePolicy {
  /**
   * Number of days after upload to transition files to Glacier storage class.
   * Per Section 0.4.4: Default 90 days for cost optimization.
   * Set to 0 or null to disable Glacier transition.
   */
  transitionToGlacierDays: number;
  
  /**
   * Number of days after upload to permanently delete files.
   * Per Section 0.4.4: Default 7 years (2555 days) for legal compliance.
   * Set to 0 or null to disable automatic deletion.
   */
  expirationDays: number;
  
  /**
   * Whether this lifecycle policy is currently active.
   * Allows temporary disabling without deleting policy configuration.
   */
  enabled: boolean;
  
  /**
   * Optional prefix filter to apply policy to specific paths only.
   * Example: 'exports/' to apply only to export files.
   */
  prefix?: string;
  
  /**
   * Optional tags filter for selective policy application.
   * Example: { 'document-type': 'invoice' }
   */
  tags?: Record<string, string>;
}

/**
 * Custom error class for storage-related errors.
 * 
 * Extends Error with storage-specific context and error codes.
 * Per Section 0.7.1: ALL error handling MUST be explicit.
 */
export class StorageError extends Error {
  /**
   * Storage-specific error code for programmatic error handling.
   */
  public readonly code: StorageErrorCode;
  
  /**
   * Additional context about the error (file key, bucket, operation, etc.).
   * Useful for logging and debugging without exposing in user-facing messages.
   */
  public readonly context?: Record<string, unknown>;
  
  /**
   * HTTP status code for API responses.
   * Mapped from error code for consistent REST API behavior.
   */
  public readonly statusCode: number;
  
  /**
   * Timestamp when error occurred (ISO 8601 UTC).
   */
  public readonly timestamp: Date;
  
  /**
   * Creates a new StorageError instance.
   * 
   * @param message - Human-readable error message
   * @param code - Storage error code for categorization
   * @param context - Additional error context (optional)
   */
  constructor(message: string, code: StorageErrorCode, context?: Record<string, unknown>) {
    super(message);
    
    // Maintains proper prototype chain for instanceof checks
    Object.setPrototypeOf(this, StorageError.prototype);
    
    this.name = 'StorageError';
    this.code = code;
    this.context = context;
    this.timestamp = new Date();
    
    // Map error codes to HTTP status codes
    this.statusCode = this.mapErrorCodeToStatus(code);
    
    // Capture stack trace for debugging
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, StorageError);
    }
  }
  
  /**
   * Maps storage error codes to appropriate HTTP status codes.
   * 
   * @param code - Storage error code
   * @returns HTTP status code
   */
  private mapErrorCodeToStatus(code: StorageErrorCode): number {
    const statusMap: Record<StorageErrorCode, number> = {
      [StorageErrorCode.UPLOAD_FAILED]: 500,
      [StorageErrorCode.DOWNLOAD_FAILED]: 500,
      [StorageErrorCode.DELETE_FAILED]: 500,
      [StorageErrorCode.FILE_NOT_FOUND]: 404,
      [StorageErrorCode.VIRUS_DETECTED]: 400,
      [StorageErrorCode.SCAN_FAILED]: 500,
      [StorageErrorCode.INVALID_BUCKET]: 400,
      [StorageErrorCode.PERMISSION_DENIED]: 403,
      [StorageErrorCode.QUOTA_EXCEEDED]: 429,
    };
    
    return statusMap[code] || 500;
  }
  
  /**
   * Converts error to JSON for logging and API responses.
   * Excludes sensitive context data from user-facing responses.
   * 
   * @returns JSON representation of error
   */
  public toJSON(): Record<string, unknown> {
    return {
      name: this.name,
      message: this.message,
      code: this.code,
      statusCode: this.statusCode,
      timestamp: this.timestamp.toISOString(),
      // Context excluded from JSON for security (may contain sensitive data)
      // Include context only in server logs, not client responses
    };
  }
}

/**
 * Default configuration constants for storage operations.
 * 
 * Centralized defaults per Section 0.7.1 code quality standards
 * (ALL magic numbers MUST be named constants).
 */
export const STORAGE_DEFAULTS = {
  /** Default presigned URL expiry in seconds (15 minutes per Section 0.4.4) */
  PRESIGNED_URL_EXPIRY_SECONDS: 900,
  
  /** Default multi-part upload part size in bytes (10 MB) */
  MULTIPART_PART_SIZE_BYTES: 10 * 1024 * 1024,
  
  /** Default multi-part concurrent upload queue size */
  MULTIPART_QUEUE_SIZE: 4,
  
  /** Default Glacier transition days (90 days per Section 0.4.4) */
  GLACIER_TRANSITION_DAYS: 90,
  
  /** Default expiration days (7 years = 2555 days per Section 0.4.4) */
  EXPIRATION_DAYS: 2555,
  
  /** AWS S3 minimum part size (5 MB) */
  MIN_PART_SIZE_BYTES: 5 * 1024 * 1024,
  
  /** AWS S3 maximum part size (5 GB) */
  MAX_PART_SIZE_BYTES: 5 * 1024 * 1024 * 1024,
  
  /** Default encryption type (SSE-S3 with AES-256) */
  DEFAULT_ENCRYPTION: 'AES256' as const,
  
  /** Default ACL for uploaded files */
  DEFAULT_ACL: 'private' as const,
  
  /** Maximum metadata size in bytes (2 KB per AWS S3 limits) */
  MAX_METADATA_SIZE_BYTES: 2048,
} as const;

/**
 * Type guard to check if a value is a valid StorageBucket.
 * 
 * @param value - Value to check
 * @returns True if value is a valid StorageBucket enum value
 */
export function isStorageBucket(value: unknown): value is StorageBucket {
  return (
    typeof value === 'string' &&
    Object.values(StorageBucket).includes(value as StorageBucket)
  );
}

/**
 * Type guard to check if a value is a valid ScanStatus.
 * 
 * @param value - Value to check
 * @returns True if value is a valid ScanStatus enum value
 */
export function isScanStatus(value: unknown): value is ScanStatus {
  return (
    typeof value === 'string' &&
    Object.values(ScanStatus).includes(value as ScanStatus)
  );
}

/**
 * Type guard to check if an error is a StorageError.
 * 
 * @param error - Error to check
 * @returns True if error is a StorageError instance
 */
export function isStorageError(error: unknown): error is StorageError {
  return error instanceof StorageError;
}
