import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { Transform, Readable } from 'stream';
import { Buffer } from 'buffer';

/**
 * Base export options interface for all formatters
 * Provides common configuration options shared across export formats
 */
export interface ExportOptions {
  /**
   * Array of field names to include in export. If not provided, all fields are included.
   * @example ['invoice_number', 'total_amount', 'date']
   */
  selectedFields?: string[];

  /**
   * Whether to include metadata columns (processing_date, uploaded_by, approved_by, etc.)
   * @default false
   */
  includeMetadata?: boolean;
}

/**
 * CSV-specific export options extending base ExportOptions
 * Provides configuration for CSV formatting and output
 */
export interface CsvExportOptions extends ExportOptions {
  /**
   * Delimiter character to use for separating CSV values
   * @default ','
   */
  delimiter?: ',' | ';' | '\t';

  /**
   * Line ending character(s) to use between CSV rows
   * @default '\n'
   */
  lineEnding?: '\n' | '\r\n';

  /**
   * Whether to use streaming for large exports (memory-efficient processing)
   * Automatically enabled for exports > 1000 rows or > 1MB data
   * @default false
   */
  streaming?: boolean;

  /**
   * Whether to include header row with column names
   * @default true
   */
  includeHeaders?: boolean;
}

/**
 * Injectable NestJS service for formatting document data as CSV exports
 * Implements CSV export with RFC 4180 compliance, configurable delimiters,
 * and streaming support for memory-efficient large dataset processing
 * 
 * @class CsvFormatterService
 * @description Transforms document data from PostgreSQL and MongoDB into CSV format
 * with proper escaping, field flattening, and optional streaming for large exports
 */
@Injectable()
export class CsvFormatterService {
  private readonly logger = new Logger(CsvFormatterService.name);
  private readonly STREAMING_THRESHOLD_ROWS = 1000;
  private readonly STREAMING_THRESHOLD_BYTES = 1024 * 1024; // 1MB

  /**
   * Formats document data as CSV, returning Buffer for small exports or Readable stream for large exports
   * 
   * @param documentData - Document object with extracted fields from OCR processing
   * @param options - CSV export configuration options (delimiter, streaming, field selection, etc.)
   * @returns Buffer containing CSV data for small exports, or Readable stream for large exports
   * @throws {BadRequestException} If document data is invalid or malformed
   * 
   * @example
   * ```typescript
   * const csvBuffer = await csvFormatter.format(document, {
   *   delimiter: ',',
   *   selectedFields: ['invoice_number', 'total_amount'],
   *   includeMetadata: true
   * });
   * ```
   */
  format(documentData: any, options?: CsvExportOptions): Buffer | Readable {
    try {
      // Validate input data
      this.validateDocumentData(documentData);

      // Set default options
      const delimiter = options?.delimiter || ',';
      const lineEnding = options?.lineEnding || '\n';
      const includeHeaders = options?.includeHeaders !== false; // default true
      const includeMetadata = options?.includeMetadata || false;
      const selectedFields = options?.selectedFields;

      // Flatten document structure into rows
      const rows = this.flattenDocument(documentData, selectedFields);

      // Check if streaming is needed
      const shouldStream = this.shouldUseStreaming(rows, options);

      this.logger.log({
        message: 'Formatting document as CSV',
        document_id: documentData.id,
        row_count: rows.length,
        streaming_mode: shouldStream,
        delimiter,
        include_metadata: includeMetadata
      });

      if (shouldStream) {
        // Return streaming response for large exports
        return this.createStreamingResponse(rows, delimiter, lineEnding, includeHeaders, includeMetadata);
      } else {
        // Return Buffer for small exports
        return this.createBufferResponse(rows, delimiter, lineEnding, includeHeaders, includeMetadata);
      }
    } catch (error) {
      this.logger.error({
        message: 'CSV formatting failed',
        document_id: documentData?.id,
        error: error.message,
        stack: error.stack
      });

      if (error instanceof BadRequestException) {
        throw error;
      }

      throw new BadRequestException(`CSV formatting failed: ${error.message}`);
    }
  }

  /**
   * Formats multiple documents as a single CSV file with all fields
   * 
   * @param documents - Array of document objects to export
   * @param options - CSV export configuration options
   * @returns Buffer containing CSV data for small exports, or Readable stream for large exports
   * @throws {BadRequestException} If documents array is invalid or any document is malformed
   * 
   * @example
   * ```typescript
   * const csvBuffer = await csvFormatter.formatBatch(documents, {
   *   delimiter: ',',
   *   streaming: true,
   *   includeMetadata: false
   * });
   * ```
   */
  formatBatch(documents: any[], options?: CsvExportOptions): Buffer | Readable {
    try {
      // Validate input
      if (!Array.isArray(documents)) {
        throw new BadRequestException('Documents must be an array');
      }

      if (documents.length === 0) {
        throw new BadRequestException('Documents array cannot be empty');
      }

      // Validate each document
      documents.forEach((doc, index) => {
        try {
          this.validateDocumentData(doc);
        } catch (error) {
          throw new BadRequestException(`Invalid document at index ${index}: ${error.message}`);
        }
      });

      // Set default options
      const delimiter = options?.delimiter || ',';
      const lineEnding = options?.lineEnding || '\n';
      const includeHeaders = options?.includeHeaders !== false;
      const includeMetadata = options?.includeMetadata || false;
      const selectedFields = options?.selectedFields;

      // Flatten all documents into rows
      const allRows: any[] = [];
      for (const document of documents) {
        const documentRows = this.flattenDocument(document, selectedFields);
        allRows.push(...documentRows);
      }

      // Check if streaming is needed
      const shouldStream = this.shouldUseStreaming(allRows, options);

      this.logger.log({
        message: 'Formatting batch documents as CSV',
        document_count: documents.length,
        total_rows: allRows.length,
        streaming_mode: shouldStream,
        delimiter,
        include_metadata: includeMetadata
      });

      if (shouldStream) {
        // Return streaming response for large batches
        return this.createStreamingResponse(allRows, delimiter, lineEnding, includeHeaders, includeMetadata);
      } else {
        // Return Buffer for small batches
        return this.createBufferResponse(allRows, delimiter, lineEnding, includeHeaders, includeMetadata);
      }
    } catch (error) {
      this.logger.error({
        message: 'Batch CSV formatting failed',
        document_count: documents?.length,
        error: error.message,
        stack: error.stack
      });

      if (error instanceof BadRequestException) {
        throw error;
      }

      throw new BadRequestException(`Batch CSV formatting failed: ${error.message}`);
    }
  }

  /**
   * Determines if streaming should be used based on data size and options
   * @private
   */
  private shouldUseStreaming(rows: any[], options?: CsvExportOptions): boolean {
    // Explicit streaming option takes precedence
    if (options?.streaming !== undefined) {
      return options.streaming;
    }

    // Auto-enable streaming for large datasets
    if (rows.length > this.STREAMING_THRESHOLD_ROWS) {
      return true;
    }

    // Estimate data size (rough calculation)
    const estimatedSize = JSON.stringify(rows).length;
    if (estimatedSize > this.STREAMING_THRESHOLD_BYTES) {
      return true;
    }

    return false;
  }

  /**
   * Creates a Buffer response for small exports (non-streaming)
   * @private
   */
  private createBufferResponse(
    rows: any[],
    delimiter: string,
    lineEnding: string,
    includeHeaders: boolean,
    includeMetadata: boolean
  ): Buffer {
    // Generate headers
    const headers = this.generateHeader(rows, includeMetadata);

    // Build CSV content
    const csvLines: string[] = [];

    // Add header row if requested
    if (includeHeaders) {
      const headerRow = headers.map(h => this.escapeCSV(h, delimiter)).join(delimiter);
      csvLines.push(headerRow);
    }

    // Add data rows
    for (const row of rows) {
      const csvRow = this.rowToCSV(row, headers, delimiter);
      csvLines.push(csvRow);
    }

    // Join with line endings
    const csvString = csvLines.join(lineEnding);

    // Convert to Buffer
    return Buffer.from(csvString, 'utf-8');
  }

  /**
   * Creates a Readable stream response for large exports (streaming)
   * @private
   */
  private createStreamingResponse(
    rows: any[],
    delimiter: string,
    lineEnding: string,
    includeHeaders: boolean,
    includeMetadata: boolean
  ): Readable {
    const headers = this.generateHeader(rows, includeMetadata);
    let currentIndex = 0;
    let headerEmitted = false;

    const transform = new Transform({
      objectMode: false,
      transform(chunk: any, encoding: string, callback: Function) {
        try {
          // This transform stream doesn't process input chunks
          // It generates output from rows array
          callback();
        } catch (error) {
          callback(error);
        }
      }
    });

    // Custom readable stream that pushes CSV rows
    const readable = new Readable({
      read() {
        try {
          // Emit header row first
          if (!headerEmitted && includeHeaders) {
            const headerRow = headers.map(h => this.escapeCSV(h, delimiter)).join(delimiter);
            const pushed = this.push(headerRow + lineEnding);
            headerEmitted = true;

            // If push returns false, respect backpressure
            if (!pushed) {
              return;
            }
          }

          // Emit data rows
          while (currentIndex < rows.length) {
            const row = rows[currentIndex];
            const csvRow = this.rowToCSV(row, headers, delimiter);
            const pushed = this.push(csvRow + lineEnding);
            currentIndex++;

            // If push returns false, respect backpressure and stop
            if (!pushed) {
              return;
            }
          }

          // All rows processed, end stream
          if (currentIndex >= rows.length) {
            this.push(null);
          }
        } catch (error) {
          this.destroy(error);
        }
      }.bind(this) // Bind context for access to escapeCSV and rowToCSV
    });

    // Bind the private methods to readable stream for use in read()
    (readable as any).escapeCSV = this.escapeCSV.bind(this);
    (readable as any).rowToCSV = this.rowToCSV.bind(this);

    return readable;
  }

  /**
   * Flattens a document with nested fields into an array of flat row objects
   * Each row represents one extracted field with document-level and field-level properties
   * 
   * @private
   * @param document - Document object with nested extracted_fields structure
   * @param selectedFields - Optional array of field names to filter
   * @returns Array of flat row objects suitable for CSV conversion
   */
  private flattenDocument(document: any, selectedFields?: string[]): any[] {
    const rows: any[] = [];

    // Extract fields array (check both common field names)
    const fields = document.extracted_fields || document.fields || [];

    // Filter fields if selection provided
    const filteredFields = selectedFields
      ? fields.filter((field: any) => selectedFields.includes(field.field_name || field.name))
      : fields;

    // Create one row per field
    for (const field of filteredFields) {
      const row: any = {
        // Document-level properties
        document_id: document.id,
        file_name: document.file_name,
        document_type: document.document_type || document.type || '',
        document_status: document.status || '',
        document_confidence: document.confidence_score || document.confidence || '',
        document_created_at: document.created_at || '',

        // Field-level properties
        field_name: field.field_name || field.name || '',
        field_label: field.field_label || field.label || '',
        field_type: field.field_type || field.type || '',
        original_value: field.original_value || field.value || '',
        corrected_value: field.corrected_value || '',
        normalized_value: field.normalized_value || '',
        field_confidence_score: field.confidence_score || field.confidence || '',
        validation_status: field.validation_status || field.status || '',
        page_number: field.page_number || field.page || '',
        bounding_box_json: field.bounding_box ? JSON.stringify(field.bounding_box) : '',
        extraction_method: field.extraction_method || field.method || ''
      };

      // Add metadata if available (checking document level)
      if (document.processing_date || document.created_at) {
        row.processing_date = document.processing_date || document.created_at || '';
      }
      if (document.uploaded_by || document.user_id) {
        row.uploaded_by = document.uploaded_by || document.user_id || '';
      }
      if (document.approved_by) {
        row.approved_by = document.approved_by || '';
      }
      if (document.approved_at) {
        row.approved_at = document.approved_at || '';
      }
      if (document.correction_count !== undefined) {
        row.correction_count = document.correction_count || 0;
      }

      rows.push(row);
    }

    // Handle edge case: document with no fields
    if (rows.length === 0 && fields.length === 0) {
      // Create a single row with document info only
      rows.push({
        document_id: document.id,
        file_name: document.file_name,
        document_type: document.document_type || document.type || '',
        document_status: document.status || '',
        document_confidence: document.confidence_score || document.confidence || '',
        document_created_at: document.created_at || '',
        field_name: '',
        field_label: '',
        field_type: '',
        original_value: '',
        corrected_value: '',
        normalized_value: '',
        field_confidence_score: '',
        validation_status: '',
        page_number: '',
        bounding_box_json: '',
        extraction_method: ''
      });
    }

    return rows;
  }

  /**
   * Escapes special CSV characters per RFC 4180 standard
   * Wraps values in double quotes if they contain delimiter, quotes, newlines, or carriage returns
   * Escapes internal double quotes by doubling them
   * 
   * @private
   * @param value - Value to escape for CSV
   * @param delimiter - Delimiter character being used
   * @returns Escaped CSV value string
   */
  private escapeCSV(value: any, delimiter: string): string {
    // Handle null/undefined
    if (value === null || value === undefined) {
      return '';
    }

    // Convert to string
    const stringValue = String(value);

    // Check if value needs escaping
    const needsEscaping =
      stringValue.includes(delimiter) ||
      stringValue.includes('"') ||
      stringValue.includes('\n') ||
      stringValue.includes('\r');

    if (needsEscaping) {
      // Escape internal double quotes by doubling them
      const escapedValue = stringValue.replace(/"/g, '""');
      // Wrap in double quotes
      return `"${escapedValue}"`;
    }

    return stringValue;
  }

  /**
   * Generates CSV header row from row objects
   * Orders columns logically: document properties, then field properties, then metadata
   * 
   * @private
   * @param rows - Array of row objects to extract column names from
   * @param includeMetadata - Whether to include metadata columns
   * @returns Array of column header names
   */
  private generateHeader(rows: any[], includeMetadata: boolean): string[] {
    if (rows.length === 0) {
      return [];
    }

    // Extract all unique column names from rows
    const columnSet = new Set<string>();
    for (const row of rows) {
      for (const key of Object.keys(row)) {
        columnSet.add(key);
      }
    }

    // Define column order (document fields first, then field properties, then metadata)
    const orderedColumns: string[] = [
      'document_id',
      'file_name',
      'document_type',
      'document_status',
      'document_confidence',
      'document_created_at',
      'field_name',
      'field_label',
      'field_type',
      'original_value',
      'corrected_value',
      'normalized_value',
      'field_confidence_score',
      'validation_status',
      'page_number',
      'bounding_box_json',
      'extraction_method'
    ];

    // Add metadata columns if included
    if (includeMetadata) {
      orderedColumns.push(
        'processing_date',
        'uploaded_by',
        'approved_by',
        'approved_at',
        'correction_count'
      );
    }

    // Filter to only columns that exist in data
    const headers = orderedColumns.filter(col => columnSet.has(col));

    // Add any additional columns not in ordered list
    for (const col of Array.from(columnSet)) {
      if (!orderedColumns.includes(col)) {
        headers.push(col);
      }
    }

    return headers;
  }

  /**
   * Converts a row object to CSV string format
   * Maps each header to the corresponding row value and escapes it
   * 
   * @private
   * @param row - Row object to convert
   * @param headers - Array of column headers defining order
   * @param delimiter - Delimiter character to use
   * @returns CSV-formatted row string
   */
  private rowToCSV(row: any, headers: string[], delimiter: string): string {
    const values = headers.map(header => {
      const value = row[header];
      return this.escapeCSV(value, delimiter);
    });

    return values.join(delimiter);
  }

  /**
   * Validates document data structure has required fields
   * 
   * @private
   * @param data - Document data to validate
   * @throws {BadRequestException} If document data is invalid
   */
  private validateDocumentData(data: any): void {
    if (!data || typeof data !== 'object') {
      throw new BadRequestException('Document data must be a valid object');
    }

    if (!data.id) {
      throw new BadRequestException('Document data must have an id field');
    }

    if (!data.file_name) {
      throw new BadRequestException('Document data must have a file_name field');
    }

    // Check for fields array (can be empty but must exist)
    const hasFields = data.extracted_fields || data.fields;
    if (hasFields !== undefined && !Array.isArray(hasFields)) {
      throw new BadRequestException('extracted_fields or fields must be an array');
    }
  }
}
