/**
 * WebSocket Event Data Transfer Objects
 * 
 * Defines the structure and validation for WebSocket events used in real-time
 * notification delivery via Socket.io. Supports multi-tenant architecture with
 * account and user-level routing.
 * 
 * Event Types:
 * - document:processed - Document OCR/NLP processing completed
 * - job:progress - Batch processing job progress update
 * - job:completed - Batch processing job completed successfully
 * - job:failed - Batch processing job failed with errors
 * 
 * @module notifications/dto
 * @see Section 0.4.2 Internal Service Integration Points
 * @see Section 0.5.5 Phase 4 Group 4D - Processing Status and Notifications
 */

import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import {
  IsEnum,
  IsObject,
  IsOptional,
  IsUUID,
  IsISO8601,
  ValidateNested,
  IsNumber,
  IsString,
  IsArray,
  Min,
  Max,
  IsBoolean,
  IsInt,
} from 'class-validator';

/**
 * WebSocket event type enumeration
 * Defines all supported real-time event types per Section 0.4.2
 */
export enum WebSocketEventType {
  /** Document processing completed - includes extracted data and confidence scores */
  DOCUMENT_PROCESSED = 'document:processed',
  
  /** Batch job progress update - includes percentage and current document */
  JOB_PROGRESS = 'job:progress',
  
  /** Batch job completed successfully - includes final statistics */
  JOB_COMPLETED = 'job:completed',
  
  /** Batch job failed - includes error details and affected documents */
  JOB_FAILED = 'job:failed',
}

/**
 * Document Processed Event Payload
 * 
 * Sent when a document has completed OCR/NLP processing.
 * Includes extracted data, confidence scores, and processing metrics.
 */
export class DocumentProcessedPayloadDto {
  @ApiProperty({
    description: 'UUID of the processed document',
    example: '550e8400-e29b-41d4-a716-446655440000',
    format: 'uuid',
  })
  @IsUUID('4')
  documentId!: string;

  @ApiProperty({
    description: 'Document type classification (invoice, receipt, contract, form)',
    example: 'invoice',
    enum: ['invoice', 'receipt', 'contract', 'form', 'other'],
  })
  @IsString()
  documentType!: string;

  @ApiProperty({
    description: 'Overall confidence score for the extraction (0-100)',
    example: 95.5,
    minimum: 0,
    maximum: 100,
  })
  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(100)
  confidenceScore!: number;

  @ApiProperty({
    description: 'Extracted fields with values and individual confidence scores',
    example: [
      {
        fieldName: 'invoice_number',
        value: 'INV-2025-001',
        confidence: 98.5,
        coordinates: { x: 100, y: 50, width: 150, height: 20 },
      },
    ],
    type: 'array',
    items: {
      type: 'object',
      properties: {
        fieldName: { type: 'string' },
        value: { type: 'string' },
        confidence: { type: 'number' },
        coordinates: {
          type: 'object',
          properties: {
            x: { type: 'number' },
            y: { type: 'number' },
            width: { type: 'number' },
            height: { type: 'number' },
          },
        },
      },
    },
  })
  @IsArray()
  @ValidateNested({ each: true })
  extractedFields!: Array<{
    fieldName: string;
    value: any;
    confidence: number;
    coordinates?: {
      x: number;
      y: number;
      width: number;
      height: number;
    };
  }>;

  @ApiProperty({
    description: 'Total processing time in milliseconds',
    example: 12500,
    minimum: 0,
  })
  @IsNumber()
  @Min(0)
  processingTimeMs!: number;

  @ApiProperty({
    description: 'Number of pages processed',
    example: 3,
    minimum: 1,
  })
  @IsInt()
  @Min(1)
  pageCount!: number;

  @ApiProperty({
    description: 'OCR engine used (tesseract, google-vision, aws-textract)',
    example: 'google-vision',
    enum: ['tesseract', 'google-vision', 'aws-textract', 'hybrid'],
  })
  @IsString()
  ocrEngine!: string;

  @ApiPropertyOptional({
    description: 'Validation errors or warnings for extracted fields',
    example: [
      {
        fieldName: 'invoice_date',
        message: 'Date format validation failed',
        severity: 'warning',
      },
    ],
    type: 'array',
  })
  @IsOptional()
  @IsArray()
  validationIssues?: Array<{
    fieldName: string;
    message: string;
    severity: 'error' | 'warning';
  }>;
}

/**
 * Job Progress Event Payload
 * 
 * Sent periodically during batch processing to update clients on progress.
 * Provides real-time feedback for long-running batch operations.
 */
export class JobProgressPayloadDto {
  @ApiProperty({
    description: 'UUID of the processing job',
    example: '650e8400-e29b-41d4-a716-446655440001',
    format: 'uuid',
  })
  @IsUUID('4')
  jobId!: string;

  @ApiProperty({
    description: 'Job progress percentage (0-100)',
    example: 45.5,
    minimum: 0,
    maximum: 100,
  })
  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(100)
  percentComplete!: number;

  @ApiProperty({
    description: 'Total number of documents in the batch',
    example: 100,
    minimum: 1,
  })
  @IsInt()
  @Min(1)
  totalDocuments!: number;

  @ApiProperty({
    description: 'Number of documents processed so far',
    example: 45,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  processedDocuments!: number;

  @ApiProperty({
    description: 'Number of documents that failed processing',
    example: 2,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  failedDocuments!: number;

  @ApiPropertyOptional({
    description: 'UUID of the document currently being processed',
    example: '750e8400-e29b-41d4-a716-446655440002',
    format: 'uuid',
  })
  @IsOptional()
  @IsUUID('4')
  currentDocumentId?: string;

  @ApiPropertyOptional({
    description: 'Name of the document currently being processed',
    example: 'invoice_2025_001.pdf',
  })
  @IsOptional()
  @IsString()
  currentDocumentName?: string;

  @ApiPropertyOptional({
    description: 'Estimated time remaining in milliseconds',
    example: 180000,
    minimum: 0,
  })
  @IsOptional()
  @IsNumber()
  @Min(0)
  estimatedTimeRemainingMs?: number;

  @ApiProperty({
    description: 'Average processing time per document in milliseconds',
    example: 15000,
    minimum: 0,
  })
  @IsNumber()
  @Min(0)
  averageProcessingTimeMs!: number;
}

/**
 * Job Completed Event Payload
 * 
 * Sent when a batch processing job completes successfully.
 * Includes comprehensive statistics about the completed job.
 */
export class JobCompletedPayloadDto {
  @ApiProperty({
    description: 'UUID of the completed job',
    example: '850e8400-e29b-41d4-a716-446655440003',
    format: 'uuid',
  })
  @IsUUID('4')
  jobId!: string;

  @ApiProperty({
    description: 'Total number of documents processed',
    example: 100,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  totalDocuments!: number;

  @ApiProperty({
    description: 'Number of documents processed successfully',
    example: 98,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  successCount!: number;

  @ApiProperty({
    description: 'Number of documents that failed processing',
    example: 2,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  failureCount!: number;

  @ApiProperty({
    description: 'Total processing time for the entire job in milliseconds',
    example: 1500000,
    minimum: 0,
  })
  @IsNumber()
  @Min(0)
  totalProcessingTimeMs!: number;

  @ApiProperty({
    description: 'Average confidence score across all successfully processed documents (0-100)',
    example: 94.2,
    minimum: 0,
    maximum: 100,
  })
  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(100)
  averageConfidenceScore!: number;

  @ApiProperty({
    description: 'Job completion timestamp in ISO 8601 format',
    example: '2025-10-31T12:30:00.000Z',
    format: 'date-time',
  })
  @IsISO8601()
  completedAt!: string;

  @ApiPropertyOptional({
    description: 'Breakdown of processing statistics by document type',
    example: {
      invoice: { count: 50, avgConfidence: 95.5 },
      receipt: { count: 30, avgConfidence: 92.1 },
      contract: { count: 18, avgConfidence: 96.8 },
    },
  })
  @IsOptional()
  @IsObject()
  documentTypeBreakdown?: Record<string, {
    count: number;
    avgConfidence: number;
  }>;

  @ApiPropertyOptional({
    description: 'List of document IDs that failed processing',
    example: ['950e8400-e29b-41d4-a716-446655440004'],
    type: [String],
  })
  @IsOptional()
  @IsArray()
  @IsUUID('4', { each: true })
  failedDocumentIds?: string[];
}

/**
 * Job Failed Event Payload
 * 
 * Sent when a batch processing job fails.
 * Includes detailed error information and recovery options.
 */
export class JobFailedPayloadDto {
  @ApiProperty({
    description: 'UUID of the failed job',
    example: 'a50e8400-e29b-41d4-a716-446655440005',
    format: 'uuid',
  })
  @IsUUID('4')
  jobId!: string;

  @ApiProperty({
    description: 'Error code identifying the failure type',
    example: 'OCR_SERVICE_UNAVAILABLE',
    enum: [
      'OCR_SERVICE_UNAVAILABLE',
      'STORAGE_ERROR',
      'VALIDATION_ERROR',
      'TIMEOUT',
      'QUOTA_EXCEEDED',
      'UNKNOWN_ERROR',
    ],
  })
  @IsString()
  errorCode!: string;

  @ApiProperty({
    description: 'Human-readable error message',
    example: 'OCR service is temporarily unavailable. Please try again later.',
  })
  @IsString()
  errorMessage!: string;

  @ApiPropertyOptional({
    description: 'Detailed error stack trace (only in development/debugging)',
    example: 'Error: Connection timeout...',
  })
  @IsOptional()
  @IsString()
  errorDetails?: string;

  @ApiProperty({
    description: 'Number of documents processed before failure',
    example: 45,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  processedDocuments!: number;

  @ApiProperty({
    description: 'Total number of documents in the failed job',
    example: 100,
    minimum: 0,
  })
  @IsInt()
  @Min(0)
  totalDocuments!: number;

  @ApiProperty({
    description: 'List of document IDs that were affected by the failure',
    example: ['b50e8400-e29b-41d4-a716-446655440006'],
    type: [String],
  })
  @IsArray()
  @IsUUID('4', { each: true })
  affectedDocumentIds!: string[];

  @ApiProperty({
    description: 'Timestamp when the job failed in ISO 8601 format',
    example: '2025-10-31T12:35:00.000Z',
    format: 'date-time',
  })
  @IsISO8601()
  failedAt!: string;

  @ApiProperty({
    description: 'Whether the job can be retried',
    example: true,
  })
  @IsBoolean()
  canRetry!: boolean;

  @ApiPropertyOptional({
    description: 'Suggested retry delay in milliseconds',
    example: 60000,
    minimum: 0,
  })
  @IsOptional()
  @IsNumber()
  @Min(0)
  retryDelayMs?: number;

  @ApiPropertyOptional({
    description: 'Recovery options available to the user',
    example: [
      { action: 'retry_all', label: 'Retry All Documents' },
      { action: 'retry_failed', label: 'Retry Only Failed Documents' },
    ],
    type: 'array',
  })
  @IsOptional()
  @IsArray()
  retryOptions?: Array<{
    action: string;
    label: string;
  }>;
}

/**
 * Main WebSocket Event DTO
 * 
 * Wrapper for all WebSocket events sent to clients.
 * Includes event type, payload, routing information, and metadata.
 * 
 * Usage:
 * ```typescript
 * const event = new WebSocketEventDto();
 * event.eventType = WebSocketEventType.DOCUMENT_PROCESSED;
 * event.payload = new DocumentProcessedPayloadDto();
 * event.accountId = 'account-uuid';
 * event.timestamp = new Date().toISOString();
 * 
 * // Send via Socket.io
 * socket.emit('notification', event);
 * ```
 */
export class WebSocketEventDto {
  @ApiProperty({
    description: 'Type of WebSocket event',
    enum: WebSocketEventType,
    example: WebSocketEventType.DOCUMENT_PROCESSED,
    enumName: 'WebSocketEventType',
  })
  @IsEnum(WebSocketEventType)
  eventType!: WebSocketEventType;

  @ApiProperty({
    description: 'Event-specific payload data. Type depends on eventType.',
    oneOf: [
      { $ref: '#/components/schemas/DocumentProcessedPayloadDto' },
      { $ref: '#/components/schemas/JobProgressPayloadDto' },
      { $ref: '#/components/schemas/JobCompletedPayloadDto' },
      { $ref: '#/components/schemas/JobFailedPayloadDto' },
    ],
  })
  @IsObject()
  @ValidateNested()
  @Type(() => Object)
  payload!:
    | DocumentProcessedPayloadDto
    | JobProgressPayloadDto
    | JobCompletedPayloadDto
    | JobFailedPayloadDto;

  @ApiPropertyOptional({
    description: 'UUID of the document associated with this event (if applicable)',
    example: 'c50e8400-e29b-41d4-a716-446655440007',
    format: 'uuid',
  })
  @IsOptional()
  @IsUUID('4')
  documentId?: string;

  @ApiPropertyOptional({
    description: 'UUID of the processing job associated with this event (if applicable)',
    example: 'd50e8400-e29b-41d4-a716-446655440008',
    format: 'uuid',
  })
  @IsOptional()
  @IsUUID('4')
  jobId?: string;

  @ApiProperty({
    description: 'Account ID for multi-tenant routing. Ensures events are delivered to correct account.',
    example: 'e50e8400-e29b-41d4-a716-446655440009',
    format: 'uuid',
  })
  @IsUUID('4')
  accountId!: string;

  @ApiPropertyOptional({
    description: 'User ID for user-specific event routing (optional)',
    example: 'f50e8400-e29b-41d4-a716-446655440010',
    format: 'uuid',
  })
  @IsOptional()
  @IsUUID('4')
  userId?: string;

  @ApiProperty({
    description: 'Event timestamp in ISO 8601 format',
    example: '2025-10-31T12:00:00.000Z',
    format: 'date-time',
  })
  @IsISO8601()
  @Type(() => String)
  timestamp!: string;

  @ApiPropertyOptional({
    description: 'Additional metadata for the event (custom fields, debugging info, etc.)',
    example: {
      environment: 'production',
      version: '1.0.0',
      requestId: 'req-12345',
    },
  })
  @IsOptional()
  @IsObject()
  metadata?: Record<string, any>;
}
