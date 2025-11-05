import type { QueryRunner } from 'typeorm';

/**
 * Migration: CreateDocumentsTable004
 * 
 * Creates documents and document_fields tables for the OCR Processing Application.
 * 
 * Documents Table:
 * - Stores document metadata, file information, and processing status
 * - Multi-tenant with account_id foreign key
 * - Links to user who uploaded and optionally to user who approved
 * - Supports template-based extraction with optional template_id
 * - Tracks overall confidence scores for OCR extraction quality
 * 
 * Document Fields Table:
 * - Stores individual extracted fields from OCR/NLP processing
 * - Each field has its own confidence score for validation workflows
 * - Tracks user corrections for machine learning improvement
 * - Supports various field types (text, number, date, currency, email)
 * 
 * Compliance:
 * - Phase 3 (Section 0.5.4): Document Upload and Storage
 * - Phase 5 (Section 0.5.6): Document Review and Correction
 * - UTC timestamps for all datetime fields per Section 0.7.2
 * - Reversible migration with complete down() implementation
 */
export class CreateDocumentsTable004 {
  /**
   * Execute the migration - create documents and document_fields tables
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create documents table
    await queryRunner.query(`
      CREATE TABLE documents (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        account_id UUID NOT NULL,
        user_id UUID NOT NULL,
        template_id UUID DEFAULT NULL,
        file_name VARCHAR(255) NOT NULL,
        file_type VARCHAR(50) NOT NULL CHECK (file_type IN ('pdf', 'jpg', 'jpeg', 'png', 'tiff')),
        file_size BIGINT NOT NULL CHECK (file_size > 0),
        storage_path VARCHAR(500) NOT NULL,
        document_type VARCHAR(50) NOT NULL CHECK (document_type IN ('invoice', 'receipt', 'contract', 'form', 'other')),
        page_count INTEGER NOT NULL DEFAULT 1 CHECK (page_count > 0),
        processing_status VARCHAR(50) NOT NULL DEFAULT 'pending' CHECK (processing_status IN ('pending', 'processing', 'completed', 'failed', 'approved')),
        overall_confidence_score DECIMAL(5,2) DEFAULT NULL CHECK (overall_confidence_score >= 0 AND overall_confidence_score <= 100),
        approved_by UUID DEFAULT NULL,
        approved_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        
        -- Foreign key constraints
        CONSTRAINT fk_documents_account FOREIGN KEY (account_id) 
          REFERENCES accounts(id) ON DELETE CASCADE,
        CONSTRAINT fk_documents_user FOREIGN KEY (user_id) 
          REFERENCES users(id) ON DELETE SET NULL,
        -- Note: template_id foreign key constraint will be added in migration 006
        -- after templates table is created (Phase 7 - Section 0.5.8)
        CONSTRAINT fk_documents_approved_by FOREIGN KEY (approved_by) 
          REFERENCES users(id) ON DELETE SET NULL
      );
    `);

    // Create indexes for documents table - optimized for common query patterns
    // Composite index for account-based queries filtered by status and sorted by date
    await queryRunner.query(`
      CREATE INDEX idx_documents_account_status_created 
      ON documents(account_id, processing_status, created_at DESC);
    `);

    // Index for user-uploaded documents sorted by date
    await queryRunner.query(`
      CREATE INDEX idx_documents_user_created 
      ON documents(user_id, created_at DESC);
    `);

    // Index for document type filtering with date sorting
    await queryRunner.query(`
      CREATE INDEX idx_documents_type_created 
      ON documents(document_type, created_at DESC);
    `);

    // Index for template-based document queries
    // Note: templates table and FK constraint added in migration 006
    await queryRunner.query(`
      CREATE INDEX idx_documents_template 
      ON documents(template_id) WHERE template_id IS NOT NULL;
    `);

    // Index for approval workflow queries
    await queryRunner.query(`
      CREATE INDEX idx_documents_approved_by 
      ON documents(approved_by) WHERE approved_by IS NOT NULL;
    `);

    // Create document_fields table
    await queryRunner.query(`
      CREATE TABLE document_fields (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        document_id UUID NOT NULL,
        field_name VARCHAR(255) NOT NULL,
        field_type VARCHAR(50) NOT NULL CHECK (field_type IN ('text', 'number', 'date', 'currency', 'email')),
        field_value TEXT DEFAULT NULL,
        confidence_score DECIMAL(5,2) NOT NULL CHECK (confidence_score >= 0 AND confidence_score <= 100),
        validation_status VARCHAR(50) NOT NULL DEFAULT 'needs_review' CHECK (validation_status IN ('valid', 'invalid', 'needs_review')),
        corrected_by UUID DEFAULT NULL,
        corrected_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        
        -- Foreign key constraints
        CONSTRAINT fk_document_fields_document FOREIGN KEY (document_id) 
          REFERENCES documents(id) ON DELETE CASCADE,
        CONSTRAINT fk_document_fields_corrected_by FOREIGN KEY (corrected_by) 
          REFERENCES users(id) ON DELETE SET NULL
      );
    `);

    // Create indexes for document_fields table
    // Primary index for accessing fields by document and field name
    await queryRunner.query(`
      CREATE INDEX idx_document_fields_document_field 
      ON document_fields(document_id, field_name);
    `);

    // Index for finding low-confidence fields needing review
    await queryRunner.query(`
      CREATE INDEX idx_document_fields_confidence 
      ON document_fields(confidence_score) WHERE confidence_score < 85;
    `);

    // Index for validation workflow queries
    await queryRunner.query(`
      CREATE INDEX idx_document_fields_validation_status 
      ON document_fields(validation_status) WHERE validation_status != 'valid';
    `);

    // Index for ML training data - fields that have been corrected
    await queryRunner.query(`
      CREATE INDEX idx_document_fields_corrections 
      ON document_fields(corrected_by, corrected_at) WHERE corrected_by IS NOT NULL;
    `);

    // Create trigger to automatically update updated_at timestamp for documents
    await queryRunner.query(`
      CREATE OR REPLACE FUNCTION update_documents_updated_at()
      RETURNS TRIGGER AS $$
      BEGIN
        NEW.updated_at = NOW();
        RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;
    `);

    await queryRunner.query(`
      CREATE TRIGGER trigger_documents_updated_at
      BEFORE UPDATE ON documents
      FOR EACH ROW
      EXECUTE FUNCTION update_documents_updated_at();
    `);

    // Create trigger to automatically update updated_at timestamp for document_fields
    await queryRunner.query(`
      CREATE OR REPLACE FUNCTION update_document_fields_updated_at()
      RETURNS TRIGGER AS $$
      BEGIN
        NEW.updated_at = NOW();
        RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;
    `);

    await queryRunner.query(`
      CREATE TRIGGER trigger_document_fields_updated_at
      BEFORE UPDATE ON document_fields
      FOR EACH ROW
      EXECUTE FUNCTION update_document_fields_updated_at();
    `);

    // Add comment to documents table for documentation
    await queryRunner.query(`
      COMMENT ON TABLE documents IS 'Stores document metadata, file information, and processing status for OCR application';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN documents.overall_confidence_score IS 'Aggregate confidence score (0-100) from OCR/NLP processing. NULL until processing completes.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN documents.processing_status IS 'Document processing workflow status: pending (uploaded), processing (OCR in progress), completed (extracted), failed (error), approved (user verified)';
    `);

    // Add comment to document_fields table for documentation
    await queryRunner.query(`
      COMMENT ON TABLE document_fields IS 'Stores individual extracted fields from OCR/NLP processing with confidence scores and validation status';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN document_fields.confidence_score IS 'Field-level confidence score (0-100) from OCR engine. Fields <85 are flagged for manual review.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN document_fields.corrected_by IS 'User who corrected this field. Used for ML training data to improve extraction accuracy.';
    `);
  }

  /**
   * Rollback the migration - drop documents and document_fields tables
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop triggers first (before dropping tables)
    await queryRunner.query(`
      DROP TRIGGER IF EXISTS trigger_document_fields_updated_at ON document_fields;
    `);

    await queryRunner.query(`
      DROP FUNCTION IF EXISTS update_document_fields_updated_at();
    `);

    await queryRunner.query(`
      DROP TRIGGER IF EXISTS trigger_documents_updated_at ON documents;
    `);

    await queryRunner.query(`
      DROP FUNCTION IF EXISTS update_documents_updated_at();
    `);

    // Drop document_fields table (must be dropped before documents due to foreign key)
    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_document_fields_corrections;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_document_fields_validation_status;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_document_fields_confidence;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_document_fields_document_field;
    `);

    await queryRunner.query(`
      DROP TABLE IF EXISTS document_fields CASCADE;
    `);

    // Drop documents table indexes
    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_documents_approved_by;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_documents_template;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_documents_type_created;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_documents_user_created;
    `);

    await queryRunner.query(`
      DROP INDEX IF EXISTS idx_documents_account_status_created;
    `);

    // Drop documents table
    await queryRunner.query(`
      DROP TABLE IF EXISTS documents CASCADE;
    `);
  }
}
