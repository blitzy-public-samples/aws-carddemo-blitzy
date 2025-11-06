import {
  IsArray,
  ArrayMinSize,
  ArrayMaxSize,
  IsUUID,
  IsOptional,
  IsInt,
  Min,
  Max,
  IsObject,
} from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Data Transfer Object for creating batch OCR processing jobs.
 * 
 * Used by POST /processing/jobs/batch endpoint to queue multiple documents for concurrent OCR extraction.
 * Validates that all document IDs are valid UUIDs and belong to the user's account.
 * Creates multiple ProcessingJob entities with a shared batch_id for tracking.
 * Priority determines queue ordering for all jobs in the batch (1=highest, 10=lowest, default=5).
 * Metadata stores flexible batch-specific information (batch name, source, etc.).
 * 
 * Batch Processing Flow:
 * 1. User uploads multiple documents via DocumentsController
 * 2. User creates batch processing job via ProcessingController.createBatchJob()
 * 3. ProcessingService generates unique batch_id (UUID)
 * 4. ProcessingService creates ProcessingJob entity for each document with status='queued'
 * 5. All jobs linked via batch_id field for tracking
 * 6. Job messages published to RabbitMQ via DocumentQueue in bulk
 * 7. Multiple DocumentProcessorWorker instances consume messages in parallel
 * 8. Individual job statuses updated independently ('processing', 'completed', 'failed')
 * 9. Batch completion notification sent when all jobs finish
 * 
 * Performance Requirements:
 * - Batch processing (100 documents): <15 minutes (Section 0.7.1)
 * - Support for 500 concurrent users (Section 0.1.2)
 * - Horizontal scaling for processing workers based on queue depth (Section 0.1.2)
 * 
 * Validation Rules:
 * - Minimum 1 document (batch must contain at least one document)
 * - Maximum 100 documents per batch (prevent overload, can be adjusted)
 * - All document IDs must be valid UUID v4 format
 * - All documents must belong to the same account (enforced in service layer)
 * - All documents must exist in the database (enforced in service layer)
 * 
 * Error Handling:
 * - If any document ID is invalid, entire batch creation fails
 * - If any document doesn't exist, entire batch creation fails
 * - Individual job failures don't affect other jobs in the batch
 * - Failed jobs can be retried independently (max 3 attempts per Section 0.4.2)
 * 
 * @see ProcessingController.createBatchJob()
 * @see ProcessingService.createBatchJob()
 * @see ProcessingJob entity (batch_id field)
 * @see Section 0.5.8 Phase 8: Batch Processing
 */
export class CreateBatchJobDto {
  /**
   * Array of document UUIDs to process in batch.
   * 
   * Each UUID must correspond to an existing document in the database that belongs to the
   * authenticated user's account. The documents should already be uploaded and stored in S3.
   * 
   * Minimum: 1 document (at least one document required for batch processing)
   * Maximum: 100 documents (prevents queue overload and ensures timely processing)
   * 
   * The service layer will:
   * - Verify all documents exist in the database
   * - Verify all documents belong to the user's account (tenant isolation)
   * - Check that documents are not already being processed
   * - Create a ProcessingJob entity for each document
   * 
   * @example ['550e8400-e29b-41d4-a716-446655440000', '6ba7b810-9dad-11d1-80b4-00c04fd430c8']
   */
  @ApiProperty({
    description:
      'Array of document UUIDs to process in batch. Minimum 1 document, maximum 100 documents per batch.',
    example: [
      '550e8400-e29b-41d4-a716-446655440000',
      '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
      '6ba7b811-9dad-11d1-80b4-00c04fd430c8',
    ],
    type: [String],
    format: 'uuid',
    minItems: 1,
    maxItems: 100,
  })
  @IsArray({ message: 'Document IDs must be an array' })
  @ArrayMinSize(1, {
    message: 'At least one document ID is required for batch processing',
  })
  @ArrayMaxSize(100, {
    message: 'Maximum 100 documents can be processed in a single batch',
  })
  @IsUUID(4, { each: true, message: 'Each document ID must be a valid UUID v4' })
  documentIds: string[];

  /**
   * Job priority for queue ordering (1=highest, 10=lowest).
   * 
   * Priority determines the order in which jobs are processed from the RabbitMQ queue.
   * Higher priority jobs (lower numbers) are processed first when workers are available.
   * 
   * Priority Levels:
   * - 1-3: High priority (urgent documents, premium accounts)
   * - 4-6: Normal priority (default for most users)
   * - 7-10: Low priority (bulk processing, background tasks)
   * 
   * Default: 5 (normal priority)
   * 
   * All jobs in the batch will have the same priority level. The priority is stored in each
   * ProcessingJob entity and used by the queue management system to order job execution.
   * 
   * Note: Priority does not guarantee immediate processing - it only affects queue ordering.
   * Actual processing time depends on worker availability and current queue depth.
   * 
   * @example 5
   */
  @ApiProperty({
    description:
      'Job priority for queue ordering (1=highest, 10=lowest). Default is 5 for normal priority. Applied to all jobs in the batch.',
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
   * Optional metadata for the batch processing job.
   * 
   * Metadata provides flexible storage for batch-specific information that can be used for:
   * - Tracking and reporting (batch name, source application)
   * - Analytics and optimization (total file size, expected processing time)
   * - Integration context (webhook URLs, callback information)
   * - User notes and custom attributes
   * 
   * Common metadata fields:
   * - batchName: Human-readable name for the batch
   * - source: Origin of the documents (email_import, api_upload, web_ui, etc.)
   * - totalFileSize: Combined size of all documents in bytes
   * - expectedProcessingTime: Estimated processing duration in seconds
   * - tags: Array of custom tags for categorization
   * - webhookUrl: Custom webhook URL for batch completion notification
   * - userId: User identifier for tracking purposes
   * - department: Department or team name
   * 
   * The metadata is stored as JSONB in PostgreSQL (ProcessingJob.metadata column) and can be
   * queried for reporting and analytics. It's included in webhook notifications and API responses.
   * 
   * Maximum size: Recommend keeping under 10KB to avoid database performance issues.
   * 
   * @example { batchName: 'Q4 2025 Invoices', source: 'email_import', totalFileSize: 52428800 }
   */
  @ApiProperty({
    description:
      'Optional metadata for the batch processing job (e.g., batch name, source application, user notes)',
    example: {
      batchName: 'Q4 2025 Invoices',
      source: 'email_import',
      totalFileSize: 52428800,
      expectedProcessingTime: 900,
    },
    required: false,
    type: 'object',
  })
  @IsOptional()
  @IsObject({ message: 'Metadata must be a valid JSON object' })
  metadata?: Record<string, any>;
}
