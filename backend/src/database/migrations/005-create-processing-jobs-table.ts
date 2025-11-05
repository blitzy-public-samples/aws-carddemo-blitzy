import { MigrationInterface, QueryRunner, Table, TableForeignKey, TableIndex } from 'typeorm';

/**
 * Migration: Create processing_jobs table
 * 
 * Purpose: Creates the processing_jobs table for tracking OCR processing job status,
 * progress, error handling, and execution metadata. Supports both single document and
 * batch processing workflows with comprehensive job lifecycle tracking.
 * 
 * Related to: Section 0.5.5 Phase 4 Queue and Worker System
 * Performance Target: Support 100+ concurrent document processing operations
 * 
 * Schema Features:
 * - UUID primary key for distributed system compatibility
 * - Multi-tenant isolation via account_id foreign key
 * - Job type distinction (single vs batch processing)
 * - Status lifecycle tracking (queued → processing → completed/failed)
 * - Progress tracking with page-level granularity
 * - OCR engine selection tracking (Tesseract, Google Vision, AWS Textract)
 * - Comprehensive error logging for failed jobs
 * - Timestamp tracking for job lifecycle events
 * - Optimized indexes for common query patterns
 * 
 * Foreign Key Relationships:
 * - account_id → accounts(id): CASCADE delete for tenant cleanup
 * - document_id → documents(id): CASCADE delete for document removal (nullable for batch jobs)
 * - user_id → users(id): SET NULL on user deletion to preserve job history
 * 
 * @implements {MigrationInterface}
 */
export class CreateProcessingJobsTable005 implements MigrationInterface {
  /**
   * Execute forward migration: Create processing_jobs table with all constraints and indexes
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing migration
   * @returns {Promise<void>}
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create processing_jobs table with comprehensive job tracking columns
    await queryRunner.createTable(
      new Table({
        name: 'processing_jobs',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'uuid_generate_v4()',
            comment: 'Unique identifier for the processing job',
          },
          {
            name: 'account_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to accounts table for multi-tenant isolation',
          },
          {
            name: 'document_id',
            type: 'uuid',
            isNullable: true,
            comment: 'Foreign key to documents table; nullable for batch jobs that process multiple documents',
          },
          {
            name: 'user_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to users table; identifies who initiated the job',
          },
          {
            name: 'job_type',
            type: 'enum',
            enum: ['single', 'batch'],
            isNullable: false,
            comment: 'Type of processing job: single document or batch processing',
          },
          {
            name: 'status',
            type: 'enum',
            enum: ['queued', 'processing', 'completed', 'failed'],
            isNullable: false,
            default: "'queued'",
            comment: 'Current status of the job in its lifecycle',
          },
          {
            name: 'progress_percentage',
            type: 'integer',
            isNullable: false,
            default: 0,
            comment: 'Job completion percentage (0-100) for real-time progress tracking',
          },
          {
            name: 'total_pages',
            type: 'integer',
            isNullable: false,
            default: 0,
            comment: 'Total number of pages to be processed across all documents in this job',
          },
          {
            name: 'processed_pages',
            type: 'integer',
            isNullable: false,
            default: 0,
            comment: 'Number of pages successfully processed so far',
          },
          {
            name: 'ocr_engine_used',
            type: 'enum',
            enum: ['tesseract', 'google_vision', 'aws_textract'],
            isNullable: true,
            comment: 'OCR engine selected for processing; null until processing starts',
          },
          {
            name: 'error_message',
            type: 'text',
            isNullable: true,
            comment: 'Detailed error message if job failed; null for successful jobs',
          },
          {
            name: 'started_at',
            type: 'timestamp',
            isNullable: true,
            comment: 'UTC timestamp when job processing started; null if not yet started',
          },
          {
            name: 'completed_at',
            type: 'timestamp',
            isNullable: true,
            comment: 'UTC timestamp when job finished (success or failure); null if still in progress',
          },
          {
            name: 'created_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'UTC timestamp when job was created/queued',
          },
          {
            name: 'updated_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'UTC timestamp of last job update',
          },
        ],
      }),
      true, // createForeignKeys - will be created separately below
    );

    // Create foreign key constraint: account_id → accounts(id)
    // ON DELETE CASCADE: When account is deleted, cascade delete all associated jobs
    await queryRunner.createForeignKey(
      'processing_jobs',
      new TableForeignKey({
        name: 'fk_processing_jobs_account_id',
        columnNames: ['account_id'],
        referencedTableName: 'accounts',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE',
      }),
    );

    // Create foreign key constraint: document_id → documents(id)
    // ON DELETE CASCADE: When document is deleted, cascade delete associated jobs
    // Note: This is nullable to support batch jobs without a single document reference
    await queryRunner.createForeignKey(
      'processing_jobs',
      new TableForeignKey({
        name: 'fk_processing_jobs_document_id',
        columnNames: ['document_id'],
        referencedTableName: 'documents',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE',
      }),
    );

    // Create foreign key constraint: user_id → users(id)
    // ON DELETE SET NULL: When user is deleted, preserve job history but null out user reference
    await queryRunner.createForeignKey(
      'processing_jobs',
      new TableForeignKey({
        name: 'fk_processing_jobs_user_id',
        columnNames: ['user_id'],
        referencedTableName: 'users',
        referencedColumnNames: ['id'],
        onDelete: 'SET NULL',
        onUpdate: 'CASCADE',
      }),
    );

    // Create composite index for active job queries by account
    // Use case: Dashboard showing active/recent jobs for an account filtered by status
    await queryRunner.createIndex(
      'processing_jobs',
      new TableIndex({
        name: 'idx_processing_jobs_account_status_created',
        columnNames: ['account_id', 'status', 'created_at'],
      }),
    );

    // Create composite index for document-specific job queries
    // Use case: Finding all jobs associated with a specific document
    await queryRunner.createIndex(
      'processing_jobs',
      new TableIndex({
        name: 'idx_processing_jobs_document_status',
        columnNames: ['document_id', 'status'],
      }),
    );

    // Create composite index for user job history queries
    // Use case: User's job history page showing chronological list of their jobs
    await queryRunner.createIndex(
      'processing_jobs',
      new TableIndex({
        name: 'idx_processing_jobs_user_created',
        columnNames: ['user_id', 'created_at'],
      }),
    );

    // Create composite index for worker queue processing
    // Use case: Workers pulling next queued job, ordered by creation time (FIFO)
    await queryRunner.createIndex(
      'processing_jobs',
      new TableIndex({
        name: 'idx_processing_jobs_status_created',
        columnNames: ['status', 'created_at'],
      }),
    );
  }

  /**
   * Execute rollback migration: Drop processing_jobs table and all associated constraints
   * 
   * Rollback Strategy:
   * 1. Drop indexes first (must be done before foreign keys)
   * 2. Drop foreign key constraints (must be done before table)
   * 3. Drop table last
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing migration
   * @returns {Promise<void>}
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop indexes first
    await queryRunner.dropIndex('processing_jobs', 'idx_processing_jobs_status_created');
    await queryRunner.dropIndex('processing_jobs', 'idx_processing_jobs_user_created');
    await queryRunner.dropIndex('processing_jobs', 'idx_processing_jobs_document_status');
    await queryRunner.dropIndex('processing_jobs', 'idx_processing_jobs_account_status_created');

    // Drop foreign key constraints
    await queryRunner.dropForeignKey('processing_jobs', 'fk_processing_jobs_user_id');
    await queryRunner.dropForeignKey('processing_jobs', 'fk_processing_jobs_document_id');
    await queryRunner.dropForeignKey('processing_jobs', 'fk_processing_jobs_account_id');

    // Drop the table
    await queryRunner.dropTable('processing_jobs', true);
  }
}
