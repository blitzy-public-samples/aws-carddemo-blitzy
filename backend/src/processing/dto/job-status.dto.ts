import { ApiProperty } from '@nestjs/swagger';
import { Expose, Exclude, Type } from 'class-transformer';

/**
 * Enum representing the current status of an OCR processing job.
 * 
 * Status Flow:
 * QUEUED -> PROCESSING -> COMPLETED (success path)
 *                      -> FAILED (error path, may retry)
 *                      -> CANCELLED (manual cancellation)
 */
export enum JobStatus {
  /** Job is waiting in queue for processing */
  QUEUED = 'queued',
  
  /** Job is currently being processed by a worker */
  PROCESSING = 'processing',
  
  /** Job completed successfully with OCR results */
  COMPLETED = 'completed',
  
  /** Job failed due to error (may be retried based on retryCount) */
  FAILED = 'failed',
  
  /** Job was manually cancelled by user or system */
  CANCELLED = 'cancelled',
}

/**
 * Enum representing the type of OCR processing job.
 */
export enum JobType {
  /** Single document processing job */
  SINGLE = 'single',
  
  /** Batch processing job for multiple documents */
  BATCH = 'batch',
}

/**
 * Data Transfer Object for OCR processing job status responses.
 * 
 * Used by GET /processing/jobs/:id and GET /processing/jobs endpoints to return job information.
 * Includes all public fields from ProcessingJob entity with proper serialization.
 * Excludes sensitive errorStack field (available only to admins via separate endpoint).
 * 
 * Security Considerations:
 * - errorStack is excluded for security (contains full stack traces)
 * - accountId is included for audit purposes but filtered by tenant isolation
 * - errorMessage provides user-friendly error description without sensitive details
 * 
 * Serialization:
 * - Uses class-transformer @Expose() decorator for explicit field exposure
 * - Uses @Type(() => Date) for proper date serialization to ISO 8601
 * - Uses @Exclude() for sensitive fields
 * 
 * Computed Properties:
 * - isCompleted: Boolean indicating if job is in terminal state
 * - canRetry: Boolean indicating if failed job can be retried
 * - processingDurationMs: Processing time in milliseconds (null if not completed)
 * 
 * @see ProcessingController.getJobStatus()
 * @see ProcessingController.listJobs()
 * @see ProcessingService.getJobStatus()
 * @see ProcessingJob entity
 */
export class JobStatusDto {
  @ApiProperty({
    description: 'Unique job identifier (UUID v4)',
    example: '550e8400-e29b-41d4-a716-446655440000',
    format: 'uuid',
  })
  @Expose()
  id: string;

  @ApiProperty({
    description: 'Account ID for multi-tenant isolation',
    example: '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
    format: 'uuid',
  })
  @Expose()
  accountId: string;

  @ApiProperty({
    description: 'Document ID being processed',
    example: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    format: 'uuid',
  })
  @Expose()
  documentId: string;

  @ApiProperty({
    description: 'User ID who created the job',
    example: '8d7f5568-8536-51fe-055c-f29ae2b01bf8',
    format: 'uuid',
    nullable: true,
  })
  @Expose()
  userId: string | null;

  @ApiProperty({
    description: 'Current job status',
    enum: JobStatus,
    example: JobStatus.COMPLETED,
  })
  @Expose()
  status: JobStatus;

  @ApiProperty({
    description: 'Job type (single or batch)',
    enum: JobType,
    example: JobType.SINGLE,
  })
  @Expose()
  jobType: JobType;

  @ApiProperty({
    description: 'Batch ID if this job is part of a batch',
    example: '9e8d7c6b-9dad-11d1-80b4-00c04fd430c8',
    format: 'uuid',
    nullable: true,
  })
  @Expose()
  batchId: string | null;

  @ApiProperty({
    description: 'Job priority (1=highest, 10=lowest)',
    example: 5,
    minimum: 1,
    maximum: 10,
  })
  @Expose()
  priority: number;

  @ApiProperty({
    description: 'Number of retry attempts made',
    example: 0,
    minimum: 0,
  })
  @Expose()
  retryCount: number;

  @ApiProperty({
    description: 'Maximum retry attempts allowed',
    example: 3,
    minimum: 0,
  })
  @Expose()
  maxRetries: number;

  @ApiProperty({
    description: 'Timestamp when processing started',
    example: '2025-10-31T14:30:00.000Z',
    format: 'date-time',
    nullable: true,
  })
  @Expose()
  @Type(() => Date)
  processingStartedAt: Date | null;

  @ApiProperty({
    description: 'Timestamp when processing completed (success or failure)',
    example: '2025-10-31T14:30:25.000Z',
    format: 'date-time',
    nullable: true,
  })
  @Expose()
  @Type(() => Date)
  processingCompletedAt: Date | null;

  @ApiProperty({
    description: 'Human-readable error message if job failed',
    example: 'OCR extraction failed: Image quality too low',
    nullable: true,
  })
  @Expose()
  errorMessage: string | null;

  /**
   * Error stack trace (EXCLUDED from API responses for security).
   * Contains full stack traces which may expose internal system details.
   * Available only to administrators via separate admin-only endpoint.
   * 
   * @security Admin-only field per Section 0.7.1 Security Requirements
   */
  @Exclude()
  errorStack?: string | null;

  @ApiProperty({
    description: 'OCR extraction result with text, fields, and confidence scores',
    example: {
      text: 'Invoice\\n\\nDate: 10/31/2025\\nTotal: $1,234.56',
      fields: [
        { name: 'invoice_date', value: '2025-10-31', confidence: 0.98 },
        { name: 'total_amount', value: '1234.56', confidence: 0.95 },
      ],
      confidence: 0.96,
      ocrEngine: 'google_vision',
    },
    nullable: true,
    type: 'object',
  })
  @Expose()
  result: any;

  @ApiProperty({
    description: 'Job metadata (file size, page count, processing details)',
    example: {
      fileSize: 1024000,
      pageCount: 3,
      ocrEngine: 'google_vision',
      processingTime: 25000,
    },
    nullable: true,
    type: 'object',
  })
  @Expose()
  metadata: Record<string, any>;

  @ApiProperty({
    description: 'Timestamp when job was created',
    example: '2025-10-31T14:29:00.000Z',
    format: 'date-time',
  })
  @Expose()
  @Type(() => Date)
  createdAt: Date;

  @ApiProperty({
    description: 'Timestamp when job was last updated',
    example: '2025-10-31T14:30:25.000Z',
    format: 'date-time',
  })
  @Expose()
  @Type(() => Date)
  updatedAt: Date;

  /**
   * Computed property indicating if the job has reached a terminal state.
   * 
   * A job is considered completed if it's in one of the following states:
   * - COMPLETED: Successfully processed with results
   * - FAILED: Processing failed (may be retried if retryCount < maxRetries)
   * - CANCELLED: Manually cancelled by user or system
   * 
   * Jobs in QUEUED or PROCESSING states are not completed.
   * 
   * @returns true if job is in a terminal state, false otherwise
   */
  @ApiProperty({
    description: 'Whether the job has completed (success, failure, or cancelled)',
    example: true,
  })
  @Expose()
  get isCompleted(): boolean {
    return (
      this.status === JobStatus.COMPLETED ||
      this.status === JobStatus.FAILED ||
      this.status === JobStatus.CANCELLED
    );
  }

  /**
   * Computed property indicating if a failed job can be retried.
   * 
   * A job can be retried if:
   * - Status is FAILED (not COMPLETED or CANCELLED)
   * - Retry count is less than maximum allowed retries
   * 
   * This property is useful for UI to show "Retry" button and for
   * backend retry logic to determine if automatic retry should occur.
   * 
   * @returns true if job failed and has retry attempts remaining, false otherwise
   */
  @ApiProperty({
    description: 'Whether the job can be retried',
    example: false,
  })
  @Expose()
  get canRetry(): boolean {
    return this.status === JobStatus.FAILED && this.retryCount < this.maxRetries;
  }

  /**
   * Computed property calculating total processing duration in milliseconds.
   * 
   * Calculates the time difference between when processing started and completed.
   * Returns null if either timestamp is missing (job not started or not completed yet).
   * 
   * Useful for:
   * - Performance monitoring and optimization
   * - SLA compliance tracking (target: <30s for single page per Section 0.7.1)
   * - Analytics and reporting dashboards
   * - Cost analysis and billing
   * 
   * @returns Processing duration in milliseconds, or null if not completed
   */
  @ApiProperty({
    description: 'Processing duration in milliseconds (null if not completed)',
    example: 25000,
    nullable: true,
  })
  @Expose()
  get processingDurationMs(): number | null {
    if (!this.processingStartedAt || !this.processingCompletedAt) {
      return null;
    }
    return (
      new Date(this.processingCompletedAt).getTime() -
      new Date(this.processingStartedAt).getTime()
    );
  }
}
