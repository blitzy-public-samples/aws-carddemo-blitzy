import { IsUUID, IsOptional, IsInt, Min, Max, IsObject } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Data Transfer Object for creating a single OCR processing job.
 * 
 * Used by POST /processing/jobs endpoint to queue a document for OCR extraction.
 * Validates that the document ID is a valid UUID and belongs to the user's account.
 * Priority determines queue ordering (1=highest, 10=lowest, default=5).
 * Metadata stores flexible job-specific information (file size, page count, etc.).
 * 
 * Job Lifecycle:
 * 1. User uploads document via DocumentsController
 * 2. User creates processing job via ProcessingController.createJob()
 * 3. ProcessingService creates ProcessingJob entity with status='queued'
 * 4. Job message published to RabbitMQ via DocumentQueue
 * 5. DocumentProcessorWorker consumes message and starts OCR processing
 * 6. Job status updated to 'processing', 'completed', or 'failed'
 * 
 * Performance Requirements:
 * - Single-page document: <30 seconds end-to-end (Section 0.7.1)
 * - Multi-page document (10 pages): <2 minutes (Section 0.7.1)
 * 
 * Security Considerations:
 * - Document ID validated as UUID v4 format to prevent injection attacks
 * - Document ownership verified at service layer via accountId filtering
 * - Priority constrained to 1-10 range to prevent queue manipulation
 * - Metadata validated as JSON object to prevent type confusion attacks
 * 
 * Usage Example:
 * ```typescript
 * // Basic job creation with default priority
 * const jobDto = {
 *   documentId: '550e8400-e29b-41d4-a716-446655440000'
 * };
 * 
 * // High-priority job with metadata
 * const urgentJobDto = {
 *   documentId: '550e8400-e29b-41d4-a716-446655440000',
 *   priority: 1,
 *   metadata: {
 *     fileSize: 1024000,
 *     pageCount: 5,
 *     source: 'web_upload',
 *     uploadedBy: 'user@example.com'
 *   }
 * };
 * ```
 * 
 * @see ProcessingController.createJob()
 * @see ProcessingService.createJob()
 * @see ProcessingJob entity
 * @see Section 0.5.5 Phase 4 Group 4B - Queue and Worker System
 * @see Section 0.7.2 - NestJS Backend Guidelines
 */
export class CreateJobDto {
  /**
   * UUID of the document to process.
   * 
   * Must be a valid UUID v4 format and must reference an existing document
   * in the documents table that belongs to the authenticated user's account.
   * The document must have been successfully uploaded and virus-scanned before
   * job creation.
   * 
   * Validation:
   * - Format: UUID v4 (8-4-4-4-12 hexadecimal characters)
   * - Existence: Verified at service layer
   * - Ownership: Verified via accountId at service layer
   * 
   * @example '550e8400-e29b-41d4-a716-446655440000'
   */
  @ApiProperty({
    description: 'UUID of the document to process',
    example: '550e8400-e29b-41d4-a716-446655440000',
    format: 'uuid',
  })
  @IsUUID(4, { message: 'Document ID must be a valid UUID v4' })
  documentId!: string;

  /**
   * Job priority for queue ordering.
   * 
   * Determines the order in which jobs are processed by workers.
   * Higher priority jobs (lower numbers) are processed before lower priority jobs.
   * Default priority is 5 (normal priority).
   * 
   * Priority Levels:
   * - 1: Critical (urgent business documents, time-sensitive invoices)
   * - 2-3: High (important contracts, customer-facing documents)
   * - 4-6: Normal (standard document processing)
   * - 7-8: Low (batch processing, background operations)
   * - 9-10: Deferred (non-urgent archival, bulk imports)
   * 
   * Queue Behavior:
   * - Jobs are ordered by priority (ascending), then by creation time (FIFO)
   * - Same priority jobs processed in creation order
   * - Priority can be updated before processing starts
   * 
   * @example 5
   * @default 5
   */
  @ApiProperty({
    description: 'Job priority for queue ordering (1=highest, 10=lowest). Default is 5 for normal priority.',
    example: 5,
    minimum: 1,
    maximum: 10,
    default: 5,
    required: false,
  })
  @IsOptional()
  @IsInt({ message: 'Priority must be an integer' })
  @Min(1, { message: 'Priority must be at least 1 (highest priority)' })
  @Max(10, { message: 'Priority must not exceed 10 (lowest priority)' })
  priority?: number;

  /**
   * Optional metadata for the processing job.
   * 
   * Flexible JSON object for storing job-specific information that may be useful
   * for tracking, analytics, or processing decisions. This data is stored in the
   * ProcessingJob entity's metadata JSONB column and can be queried or filtered.
   * 
   * Common Metadata Fields:
   * - fileSize: Size of the uploaded file in bytes
   * - pageCount: Number of pages in the document (if known)
   * - source: Source of upload ('web_upload', 'email_import', 'api', 'mobile_app')
   * - uploadedBy: Email or name of the uploader
   * - fileName: Original file name
   * - mimeType: MIME type of the uploaded file
   * - templateId: UUID of template to use for extraction (if applicable)
   * - webhookUrl: Custom webhook URL for job completion notification
   * - tags: Array of tags for categorization
   * - customFields: Application-specific custom data
   * 
   * Size Limits:
   * - Maximum recommended size: 64KB (PostgreSQL JSONB practical limit)
   * - Avoid storing large binary data or full document text
   * 
   * @example { fileSize: 1024000, pageCount: 5, source: 'web_upload' }
   */
  @ApiProperty({
    description: 'Optional metadata for the processing job (e.g., file size, page count, source application)',
    example: { fileSize: 1024000, pageCount: 5, source: 'web_upload' },
    required: false,
    type: Object,
  })
  @IsOptional()
  @IsObject({ message: 'Metadata must be a valid JSON object' })
  metadata?: Record<string, any>;
}
