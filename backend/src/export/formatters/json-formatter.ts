import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { Buffer } from 'buffer';

/**
 * Configuration options for JSON export formatting
 */
export interface ExportOptions {
  /**
   * Array of field names to include in export. If not provided, all fields are included.
   */
  selectedFields?: string[];

  /**
   * Whether to include additional metadata in export (processing dates, user info, confidence scores, corrections)
   */
  includeMetadata?: boolean;

  /**
   * Number of spaces for JSON indentation. Default is 2.
   */
  indentation?: number;
}

/**
 * Document field structure representing extracted OCR data
 */
interface DocumentField {
  field_name: string;
  field_label?: string;
  field_type?: string;
  original_value?: string;
  corrected_value?: string;
  normalized_value?: string;
  confidence_score?: number;
  validation_status?: string;
  bounding_box?: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  page_number?: number;
}

/**
 * Document data structure from PostgreSQL and MongoDB
 */
interface DocumentData {
  id: string;
  file_name: string;
  document_type?: string;
  status?: string;
  confidence_score?: number;
  created_at?: Date | string;
  processing_completed_at?: Date | string;
  uploaded_by?: string;
  approved_by?: string;
  approved_at?: Date | string;
  extracted_fields?: DocumentField[];
  fields?: DocumentField[]; // Alternative field name
  corrections?: any[];
}

/**
 * Formatted document structure for JSON export
 */
interface FormattedDocument {
  id: string;
  file_name: string;
  document_type?: string;
  status?: string;
  confidence_score?: number;
  created_at?: string;
  processing_completed_at?: string;
  extracted_fields: DocumentField[];
  metadata?: any;
}

/**
 * Injectable NestJS service implementing JSON export formatter for the OCR Processing Application.
 * 
 * Transforms document data from PostgreSQL and MongoDB into properly structured JSON format
 * with configurable indentation and field selection. Implements comprehensive error handling
 * and follows NestJS service patterns per Section 0.7.2 guidelines.
 * 
 * @example
 * ```typescript
 * const jsonFormatter = new JsonFormatterService();
 * const buffer = jsonFormatter.format(documentData, {
 *   selectedFields: ['invoice_number', 'total_amount'],
 *   includeMetadata: true,
 *   indentation: 2
 * });
 * ```
 */
@Injectable()
export class JsonFormatterService {
  private readonly logger = new Logger(JsonFormatterService.name);

  /**
   * Format a single document into JSON format
   * 
   * @param documentData - Document data from PostgreSQL/MongoDB containing metadata and extracted fields
   * @param options - Export options for field selection, metadata inclusion, and indentation
   * @returns Buffer containing formatted JSON string in UTF-8 encoding
   * @throws {BadRequestException} When document data is invalid or JSON generation fails
   * 
   * @example
   * ```typescript
   * const buffer = jsonFormatter.format(document, {
   *   selectedFields: ['field1', 'field2'],
   *   includeMetadata: true
   * });
   * ```
   */
  format(documentData: DocumentData, options?: ExportOptions): Buffer {
    try {
      // Validate input data
      this.validateDocumentData(documentData);

      const indentation = options?.indentation ?? 2;

      // Extract fields array (support both 'extracted_fields' and 'fields' property names)
      let fields = documentData.extracted_fields || documentData.fields || [];

      // Apply field selection if specified
      if (options?.selectedFields && options.selectedFields.length > 0) {
        fields = this.applyFieldSelection(fields, options.selectedFields);
      }

      // Build formatted document structure
      const formattedDocument: FormattedDocument = {
        id: documentData.id,
        file_name: documentData.file_name,
        document_type: documentData.document_type,
        status: documentData.status,
        confidence_score: documentData.confidence_score,
        created_at: this.formatDate(documentData.created_at),
        processing_completed_at: this.formatDate(documentData.processing_completed_at),
        extracted_fields: fields,
      };

      // Include metadata if requested
      if (options?.includeMetadata) {
        formattedDocument.metadata = this.includeMetadata(documentData);
      }

      // Convert to JSON string with proper indentation
      const jsonString = JSON.stringify(formattedDocument, null, indentation);

      // Convert to Buffer
      const buffer = Buffer.from(jsonString, 'utf-8');

      // Log successful formatting
      this.logger.log(
        `Formatted document to JSON: document_id=${documentData.id}, ` +
        `field_count=${fields.length}, ` +
        `selected_fields=${options?.selectedFields?.length || 'all'}, ` +
        `include_metadata=${options?.includeMetadata || false}`,
      );

      return buffer;
    } catch (error) {
      if (error instanceof BadRequestException) {
        throw error;
      }

      // Handle JSON stringification errors (circular references, non-serializable data)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;
      
      this.logger.error(
        `Failed to format document to JSON: ${errorMessage}`,
        errorStack,
      );

      throw new BadRequestException(
        `Failed to generate JSON export: ${errorMessage}`,
      );
    }
  }

  /**
   * Format multiple documents into a batch JSON export
   * 
   * @param documents - Array of document data objects from PostgreSQL/MongoDB
   * @param options - Export options for field selection, metadata inclusion, and indentation
   * @returns Buffer containing formatted JSON array with batch metadata
   * @throws {BadRequestException} When documents array is invalid or JSON generation fails
   * 
   * @example
   * ```typescript
   * const buffer = jsonFormatter.formatBatch(documents, {
   *   includeMetadata: true,
   *   indentation: 2
   * });
   * ```
   */
  formatBatch(documents: DocumentData[], options?: ExportOptions): Buffer {
    try {
      // Validate input
      if (!Array.isArray(documents)) {
        throw new BadRequestException('Documents must be an array');
      }

      if (documents.length === 0) {
        this.logger.warn('formatBatch called with empty documents array');
      }

      const indentation = options?.indentation ?? 2;

      // Format each document
      const formattedDocuments: FormattedDocument[] = documents.map(
        (doc, index) => {
          try {
            this.validateDocumentData(doc);

            // Extract fields array
            let fields = doc.extracted_fields || doc.fields || [];

            // Apply field selection if specified
            if (options?.selectedFields && options.selectedFields.length > 0) {
              fields = this.applyFieldSelection(fields, options.selectedFields);
            }

            // Build formatted document
            const formattedDoc: FormattedDocument = {
              id: doc.id,
              file_name: doc.file_name,
              document_type: doc.document_type,
              status: doc.status,
              confidence_score: doc.confidence_score,
              created_at: this.formatDate(doc.created_at),
              processing_completed_at: this.formatDate(doc.processing_completed_at),
              extracted_fields: fields,
            };

            // Include metadata if requested
            if (options?.includeMetadata) {
              formattedDoc.metadata = this.includeMetadata(doc);
            }

            return formattedDoc;
          } catch (error) {
            const errorMessage = error instanceof Error ? error.message : 'Unknown error';
            this.logger.error(
              `Failed to format document at index ${index}: ${errorMessage}`,
            );
            throw new BadRequestException(
              `Failed to format document at index ${index}: ${errorMessage}`,
            );
          }
        },
      );

      // Build batch export structure with metadata
      const batchExport = {
        documents: formattedDocuments,
        metadata: {
          total_count: formattedDocuments.length,
          export_date: new Date().toISOString(),
          format: 'json',
          options: {
            field_selection: options?.selectedFields ? 'filtered' : 'all',
            include_metadata: options?.includeMetadata || false,
          },
        },
      };

      // Convert to JSON string
      const jsonString = JSON.stringify(batchExport, null, indentation);

      // Convert to Buffer
      const buffer = Buffer.from(jsonString, 'utf-8');

      // Log successful batch formatting
      this.logger.log(
        `Formatted batch to JSON: document_count=${documents.length}, ` +
        `selected_fields=${options?.selectedFields?.length || 'all'}, ` +
        `include_metadata=${options?.includeMetadata || false}`,
      );

      return buffer;
    } catch (error) {
      if (error instanceof BadRequestException) {
        throw error;
      }

      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;

      this.logger.error(
        `Failed to format batch to JSON: ${errorMessage}`,
        errorStack,
      );

      throw new BadRequestException(
        `Failed to generate batch JSON export: ${errorMessage}`,
      );
    }
  }

  /**
   * Validate document data structure
   * 
   * @param data - Document data to validate
   * @throws {BadRequestException} When data is invalid
   * @private
   */
  private validateDocumentData(data: any): void {
    if (!data || typeof data !== 'object') {
      throw new BadRequestException('Document data must be an object');
    }

    if (!data.id) {
      throw new BadRequestException('Document data must include an id field');
    }

    if (!data.file_name) {
      throw new BadRequestException('Document data must include a file_name field');
    }

    // Validate that fields exist (either extracted_fields or fields)
    const hasFields = data.extracted_fields || data.fields;
    if (!hasFields) {
      this.logger.warn(
        `Document ${data.id} has no extracted_fields or fields array`,
      );
    }
  }

  /**
   * Apply field selection filtering
   * 
   * @param fields - Array of document fields
   * @param selectedFields - Array of field names to include
   * @returns Filtered array containing only selected fields
   * @private
   */
  private applyFieldSelection(
    fields: DocumentField[],
    selectedFields: string[],
  ): DocumentField[] {
    if (!Array.isArray(fields)) {
      return [];
    }

    if (!Array.isArray(selectedFields) || selectedFields.length === 0) {
      return fields;
    }

    // Create a Set for O(1) lookup performance
    const selectedFieldsSet = new Set(
      selectedFields.map((name) => name.toLowerCase()),
    );

    // Filter fields to only include selected ones
    const filteredFields = fields.filter((field) => {
      if (!field.field_name) {
        return false;
      }
      return selectedFieldsSet.has(field.field_name.toLowerCase());
    });

    this.logger.debug(
      `Applied field selection: original=${fields.length}, filtered=${filteredFields.length}`,
    );

    return filteredFields;
  }

  /**
   * Include metadata for document export
   * 
   * @param document - Document data
   * @returns Metadata object with processing info, user data, and confidence metrics
   * @private
   */
  private includeMetadata(document: DocumentData): any {
    const metadata: any = {
      processing_date: this.formatDate(document.created_at),
      uploaded_by: document.uploaded_by || null,
    };

    // Include approval information if available
    if (document.approved_by || document.approved_at) {
      metadata.approval_info = {
        approved_by: document.approved_by || null,
        approved_at: this.formatDate(document.approved_at),
      };
    }

    // Include confidence metrics
    const fields = document.extracted_fields || document.fields || [];
    const confidenceScores = fields
      .map((field) => field.confidence_score)
      .filter((score): score is number => typeof score === 'number');

    metadata.confidence_metrics = {
      overall_score: document.confidence_score || null,
      field_count: fields.length,
      avg_field_confidence:
        confidenceScores.length > 0
          ? confidenceScores.reduce((sum, score) => sum + score, 0) /
            confidenceScores.length
          : null,
      min_field_confidence:
        confidenceScores.length > 0 ? Math.min(...confidenceScores) : null,
      max_field_confidence:
        confidenceScores.length > 0 ? Math.max(...confidenceScores) : null,
    };

    // Include correction history if available
    if (document.corrections && Array.isArray(document.corrections)) {
      metadata.correction_history = {
        total_corrections: document.corrections.length,
        corrections: document.corrections,
      };
    }

    return metadata;
  }

  /**
   * Format date to ISO 8601 string
   * 
   * @param date - Date object or string
   * @returns ISO 8601 formatted date string or undefined
   * @private
   */
  private formatDate(date: Date | string | undefined): string | undefined {
    if (!date) {
      return undefined;
    }

    try {
      if (typeof date === 'string') {
        return new Date(date).toISOString();
      }

      if (date instanceof Date) {
        return date.toISOString();
      }

      return undefined;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      this.logger.warn(`Failed to format date: ${errorMessage}`);
      return undefined;
    }
  }
}
