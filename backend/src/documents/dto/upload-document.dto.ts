import { ApiProperty } from '@nestjs/swagger';
import { IsUUID, IsEnum, IsOptional, IsArray, IsString, IsNotEmpty } from 'class-validator';

/**
 * Document type enumeration for classification and processing rules.
 * 
 * Used for document type classification to apply appropriate OCR and NLP processing:
 * - INVOICE: Commercial invoices with line items, totals, vendor information
 * - RECEIPT: Purchase receipts with merchant, date, total amount
 * - CONTRACT: Legal contracts with terms, signatures, dates
 * - FORM: Structured forms with fields and checkboxes
 * - OTHER: Miscellaneous documents that don't fit other categories
 * 
 * @see DocumentClassifierService for automatic type detection
 */
export enum DocumentType {
  INVOICE = 'invoice',
  RECEIPT = 'receipt',
  CONTRACT = 'contract',
  FORM = 'form',
  OTHER = 'other'
}

/**
 * Data Transfer Object for document upload with validation.
 * 
 * Used by POST /documents endpoint for uploading documents for OCR processing.
 * Supports multipart/form-data with file upload via FileInterceptor.
 * File validation (type, size, virus scan) performed by StorageService.
 * 
 * Required fields:
 * - account_id: UUID for multi-tenant data isolation per Section 0.7.1
 * - file: Document file (handled by FileInterceptor, validated by StorageService)
 * - document_type: Document classification (invoice, receipt, contract, form, other)
 * 
 * Optional fields:
 * - template_id: UUID for template-based extraction (if custom template exists)
 * - tags: Array of strings for categorization and filtering
 * 
 * File type validation: PDF, JPG, PNG, TIFF (enforced by StorageService)
 * Max file size: Configured in StorageService (default 50MB)
 * Security: All files virus scanned before processing per Section 0.7.1
 * 
 * @example
 * ```typescript
 * const uploadDto: UploadDocumentDto = {
 *   account_id: '550e8400-e29b-41d4-a716-446655440000',
 *   document_type: DocumentType.INVOICE,
 *   template_id: '650e8400-e29b-41d4-a716-446655440001',
 *   tags: ['finance', 'Q4-2025', 'vendor-acme']
 * };
 * ```
 * 
 * @see DocumentsController.uploadDocument()
 * @see DocumentsService.uploadDocument()
 * @see StorageService.uploadFile()
 * @see VirusScannerService.scanFile()
 */
export class UploadDocumentDto {
  /**
   * Account ID for multi-tenant isolation.
   * 
   * All documents are scoped to an account for data isolation.
   * Must be a valid UUID v4 format.
   * Used in all database queries with WHERE account_id = ? to prevent cross-tenant access.
   * 
   * Required for all document operations per Section 0.7.1 Security Requirements.
   * 
   * @type {string}
   * @format uuid
   * @example '550e8400-e29b-41d4-a716-446655440000'
   */
  @ApiProperty({
    description: 'Account ID for multi-tenant isolation (UUID v4 format)',
    example: '550e8400-e29b-41d4-a716-446655440000',
    format: 'uuid',
    required: true
  })
  @IsUUID(4, { message: 'Account ID must be a valid UUID v4' })
  @IsNotEmpty({ message: 'Account ID is required' })
  account_id!: string;

  /**
   * Document file for OCR processing.
   * 
   * Uploaded via multipart/form-data and handled by @UseInterceptors(FileInterceptor('file'))
   * in the controller. File is not validated in this DTO - validation is performed by:
   * 
   * 1. StorageService.uploadFile():
   *    - File type validation (PDF, JPG, PNG, TIFF via MIME type check)
   *    - File size validation (max 50MB default, configurable)
   *    - File extension validation
   * 
   * 2. VirusScannerService.scanFile():
   *    - ClamAV virus scanning per Section 0.7.1 Security Requirements
   *    - Quarantine infected files
   * 
   * Supported formats:
   * - PDF: application/pdf
   * - JPG/JPEG: image/jpeg
   * - PNG: image/png
   * - TIFF: image/tiff
   * 
   * @type {Express.Multer.File}
   * @format binary
   */
  @ApiProperty({
    description: 'Document file (PDF, JPG, PNG, TIFF). Max size 50MB. Virus scanned before processing.',
    type: 'string',
    format: 'binary',
    required: true
  })
  // Note: File validation handled by FileInterceptor and StorageService, not here

  /**
   * Optional template ID for template-based field extraction.
   * 
   * If provided, the document will be processed using the specified custom template
   * which defines extraction zones and field mappings. Templates are created via
   * the template builder interface.
   * 
   * Must be a valid UUID v4 format if provided.
   * Template must belong to the same account_id (verified by TemplatesService).
   * 
   * If not provided, automatic document classification and generic field extraction
   * will be used.
   * 
   * @type {string | undefined}
   * @format uuid
   * @example '650e8400-e29b-41d4-a716-446655440001'
   * @see TemplatesService.findOne()
   * @see OcrService.processWithTemplate()
   */
  @ApiProperty({
    description: 'Optional template ID for template-based field extraction (UUID v4 format). Template must belong to the same account.',
    example: '650e8400-e29b-41d4-a716-446655440001',
    format: 'uuid',
    required: false
  })
  @IsOptional()
  @IsUUID(4, { message: 'Template ID must be a valid UUID v4' })
  template_id?: string;

  /**
   * Document type for classification and processing rules.
   * 
   * Specifies the document category to apply appropriate OCR and NLP processing:
   * - invoice: Commercial invoices with line items, totals, vendor information
   * - receipt: Purchase receipts with merchant, date, total amount
   * - contract: Legal contracts with terms, signatures, dates
   * - form: Structured forms with fields and checkboxes
   * - other: Miscellaneous documents that don't fit other categories
   * 
   * This field can be auto-detected if not provided (future enhancement), but
   * currently required for accurate processing.
   * 
   * Used by NLP service to apply document-specific entity extraction rules.
   * 
   * @type {DocumentType}
   * @example DocumentType.INVOICE
   * @see DocumentClassifierService.classify()
   * @see EntityExtractorService.extractFields()
   */
  @ApiProperty({
    description: 'Document type for classification and processing rules',
    enum: DocumentType,
    enumName: 'DocumentType',
    example: DocumentType.INVOICE,
    required: true
  })
  @IsEnum(DocumentType, { 
    message: 'Document type must be one of: invoice, receipt, contract, form, other' 
  })
  @IsNotEmpty({ message: 'Document type is required' })
  document_type!: DocumentType;

  /**
   * Optional tags for document categorization and filtering.
   * 
   * Array of custom string tags for organizing and searching documents.
   * Tags enable:
   * - Filtering in document list view
   * - Advanced search queries
   * - Batch operations on tagged documents
   * - Custom reporting and analytics
   * 
   * Common tag patterns:
   * - Department: 'finance', 'legal', 'hr'
   * - Time period: 'Q1-2025', 'FY2025', 'Jan-2025'
   * - Vendor/Customer: 'vendor-acme', 'customer-xyz'
   * - Project: 'project-alpha', 'contract-renewal'
   * - Status: 'pending-approval', 'archived', 'urgent'
   * 
   * Each tag must be a non-empty string. No length limit per tag, but
   * recommended to keep under 50 characters for display purposes.
   * 
   * @type {string[] | undefined}
   * @example ['finance', 'Q4-2025', 'vendor-acme']
   * @see SearchService.filterByTags()
   * @see DocumentsService.findByTags()
   */
  @ApiProperty({
    description: 'Optional tags for document categorization and filtering. Each tag must be a string.',
    example: ['finance', 'Q4-2025', 'vendor-acme'],
    type: [String],
    required: false,
    isArray: true
  })
  @IsOptional()
  @IsArray({ message: 'Tags must be an array' })
  @IsString({ each: true, message: 'Each tag must be a string' })
  tags?: string[];
}
