import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import * as ExcelJS from 'exceljs';

/**
 * Options for Excel export formatting
 * Extends base export options with Excel-specific configuration
 */
export interface ExcelExportOptions {
  /** Custom worksheet name (default: 'Documents') */
  worksheetName?: string;
  
  /** Create separate worksheets for each document or document type */
  separateSheets?: boolean;
  
  /** Include Excel formulas for statistics and calculations */
  includeFormulas?: boolean;
  
  /** Custom header background color in ARGB format (default: 'FF4472C4') */
  headerBackgroundColor?: string;
  
  /** Enable streaming mode for large exports (>10,000 rows) */
  streaming?: boolean;
  
  /** Array of field names to include in export (null = all fields) */
  selectedFields?: string[];
  
  /** Include metadata worksheet with processing summary */
  includeMetadata?: boolean;
}

/**
 * Excel Export Formatter Service
 * 
 * Injectable NestJS service implementing Excel export formatter using exceljs library
 * for the OCR Processing Application. Transforms document data from PostgreSQL and MongoDB
 * into Excel (.xlsx) format with professional formatting.
 * 
 * Features:
 * - Styled headers with colors, borders, and bold text
 * - Auto-sized columns based on content length
 * - Data type detection and formatting (dates, numbers, currency)
 * - Field selection filtering
 * - Metadata worksheet support
 * - Streaming support for large exports
 * - Multiple worksheet support for batch exports
 * 
 * @see Section 0.5.9 Phase 9 Group 9A - Excel export implementation
 * @see Section 0.3.2 - Backend dependencies (exceljs 4.4.0)
 * @see Section 0.7.2 - NestJS service patterns
 */
@Injectable()
export class ExcelFormatterService {
  private readonly logger = new Logger(ExcelFormatterService.name);
  
  // Configuration constants
  private readonly MIN_COLUMN_WIDTH = 10;
  private readonly MAX_COLUMN_WIDTH = 50;
  private readonly DEFAULT_HEADER_COLOR = 'FF4472C4';
  private readonly DEFAULT_HEADER_TEXT_COLOR = 'FFFFFFFF';
  // Reserved for future streaming implementation (Phase 2 enhancement)
  // private readonly STREAMING_THRESHOLD = 10000;

  /**
   * Format document data into Excel Buffer
   * 
   * Transforms document data into professionally formatted Excel file with styled headers,
   * auto-sized columns, and proper data type formatting.
   * 
   * @param documentData - Document object with extracted fields
   * @param options - Excel export options
   * @returns Promise<Buffer> - Excel file as Buffer
   * @throws BadRequestException if document data is invalid or Excel generation fails
   */
  async format(documentData: any, options?: ExcelExportOptions): Promise<Buffer> {
    try {
      this.logger.log(`Starting Excel format for document: ${documentData?.id || 'unknown'}`);
      
      // Validate input data
      this.validateDocumentData(documentData);
      
      // Create workbook
      const workbook = new ExcelJS.Workbook();
      
      // Set workbook properties
      workbook.creator = 'OCR Processing Application';
      workbook.created = new Date();
      workbook.modified = new Date();
      
      // Determine worksheet name
      const worksheetName = options?.worksheetName || 'Documents';
      
      // Add main data worksheet
      const worksheet = workbook.addWorksheet(worksheetName);
      
      // Flatten document structure into rows
      const rows = this.flattenDocument(documentData, options?.selectedFields);
      
      if (rows.length === 0) {
        throw new BadRequestException('No fields to export in document');
      }
      
      // Define columns based on first row keys
      const columns = this.defineColumns(rows[0]);
      worksheet.columns = columns;
      
      // Apply header styling
      this.applyHeaderStyling(worksheet, options?.headerBackgroundColor);
      
      // Add data rows
      rows.forEach(row => {
        worksheet.addRow(row);
      });
      
      // Apply data formatting
      this.applyDataFormatting(worksheet, 2); // Start from row 2 (after header)
      
      // Auto-size columns
      this.autoSizeColumns(worksheet);
      
      // Add autoFilter
      const lastColumn = this.getLastColumnLetter(columns.length);
      worksheet.autoFilter = {
        from: 'A1',
        to: `${lastColumn}1`
      };
      
      // Freeze header row
      worksheet.views = [
        { state: 'frozen', xSplit: 0, ySplit: 1 }
      ];
      
      // Add metadata worksheet if requested
      if (options?.includeMetadata) {
        this.createMetadataWorksheet(workbook, documentData);
      }
      
      // Generate buffer
      const buffer = await workbook.xlsx.writeBuffer();
      
      this.logger.log(`Excel format completed: ${rows.length} rows, ${columns.length} columns`);
      
      return buffer as Buffer;
      
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;
      
      this.logger.error(`Excel format failed: ${errorMessage}`, errorStack);
      
      if (error instanceof BadRequestException) {
        throw error;
      }
      
      throw new BadRequestException(`Failed to generate Excel export: ${errorMessage}`);
    }
  }

  /**
   * Format multiple documents into single Excel file
   * 
   * Creates Excel file with batch document data, optionally separating into multiple
   * worksheets by document or document type. Includes summary worksheet with statistics.
   * 
   * @param documents - Array of document objects
   * @param options - Excel export options
   * @returns Promise<Buffer> - Excel file as Buffer
   * @throws BadRequestException if documents are invalid or Excel generation fails
   */
  async formatBatch(documents: any[], options?: ExcelExportOptions): Promise<Buffer> {
    try {
      this.logger.log(`Starting batch Excel format for ${documents?.length || 0} documents`);
      
      // Validate input
      if (!Array.isArray(documents) || documents.length === 0) {
        throw new BadRequestException('Documents must be a non-empty array');
      }
      
      // Create workbook
      const workbook = new ExcelJS.Workbook();
      workbook.creator = 'OCR Processing Application';
      workbook.created = new Date();
      workbook.modified = new Date();
      
      let totalRows = 0;
      
      if (options?.separateSheets) {
        // Create separate worksheet for each document
        documents.forEach((doc, index) => {
          this.validateDocumentData(doc);
          
          const worksheetName = doc.file_name 
            ? doc.file_name.substring(0, 31) // Excel worksheet name limit
            : `Document ${index + 1}`;
          
          const worksheet = workbook.addWorksheet(worksheetName);
          const rows = this.flattenDocument(doc, options?.selectedFields);
          
          if (rows.length > 0) {
            const columns = this.defineColumns(rows[0]);
            worksheet.columns = columns;
            this.applyHeaderStyling(worksheet, options?.headerBackgroundColor);
            
            rows.forEach(row => worksheet.addRow(row));
            
            this.applyDataFormatting(worksheet, 2);
            this.autoSizeColumns(worksheet);
            
            const lastColumn = this.getLastColumnLetter(columns.length);
            worksheet.autoFilter = { from: 'A1', to: `${lastColumn}1` };
            worksheet.views = [{ state: 'frozen', xSplit: 0, ySplit: 1 }];
            
            totalRows += rows.length;
          }
        });
      } else {
        // Single worksheet with all documents
        const worksheet = workbook.addWorksheet('All Documents');
        const allRows: any[] = [];
        
        documents.forEach(doc => {
          this.validateDocumentData(doc);
          const rows = this.flattenDocument(doc, options?.selectedFields);
          allRows.push(...rows);
        });
        
        if (allRows.length > 0) {
          const columns = this.defineColumns(allRows[0]);
          worksheet.columns = columns;
          this.applyHeaderStyling(worksheet, options?.headerBackgroundColor);
          
          allRows.forEach(row => worksheet.addRow(row));
          
          this.applyDataFormatting(worksheet, 2);
          this.autoSizeColumns(worksheet);
          
          const lastColumn = this.getLastColumnLetter(columns.length);
          worksheet.autoFilter = { from: 'A1', to: `${lastColumn}1` };
          worksheet.views = [{ state: 'frozen', xSplit: 0, ySplit: 1 }];
          
          totalRows = allRows.length;
        }
      }
      
      // Add summary worksheet
      this.createBatchSummaryWorksheet(workbook, documents, options);
      
      // Generate buffer
      const buffer = await workbook.xlsx.writeBuffer();
      
      this.logger.log(`Batch Excel format completed: ${documents.length} documents, ${totalRows} total rows`);
      
      return buffer as Buffer;
      
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;
      
      this.logger.error(`Batch Excel format failed: ${errorMessage}`, errorStack);
      
      if (error instanceof BadRequestException) {
        throw error;
      }
      
      throw new BadRequestException(`Failed to generate batch Excel export: ${errorMessage}`);
    }
  }

  /**
   * Flatten document structure into array of row objects
   * 
   * Converts document with nested fields into flat row objects where each row
   * represents one extracted field with both document-level and field-level properties.
   * 
   * @param document - Document object with extracted fields
   * @param selectedFields - Optional array of field names to include
   * @returns Array of flat row objects
   * @private
   */
  private flattenDocument(document: any, selectedFields?: string[]): any[] {
    const rows: any[] = [];
    
    // Get fields array from document
    const fields = document.extracted_fields || document.fields || [];
    
    if (!Array.isArray(fields)) {
      this.logger.warn(`Document ${document.id} has invalid fields structure`);
      return rows;
    }
    
    // Process each field
    fields.forEach(field => {
      // Apply field selection filter if specified
      if (selectedFields && selectedFields.length > 0) {
        if (!selectedFields.includes(field.field_name)) {
          return; // Skip this field
        }
      }
      
      // Create flat row object
      const row = {
        document_id: document.id || '',
        file_name: document.file_name || '',
        document_type: document.document_type || '',
        status: document.status || '',
        overall_confidence: this.formatNumber(document.confidence_score),
        field_name: field.field_name || '',
        field_label: field.field_label || field.label || '',
        field_type: field.field_type || field.type || '',
        original_value: this.formatCellValue(field.original_value, 'text'),
        corrected_value: this.formatCellValue(field.corrected_value, 'text'),
        normalized_value: this.formatCellValue(field.normalized_value, 'text'),
        field_confidence: this.formatNumber(field.confidence_score),
        validation_status: field.validation_status || '',
        page_number: field.page_number || '',
        bounding_box: field.bounding_box ? JSON.stringify(field.bounding_box) : '',
        extraction_method: field.extraction_method || '',
        created_at: document.created_at ? new Date(document.created_at) : '',
        processing_completed: document.processing_completed_at ? new Date(document.processing_completed_at) : ''
      };
      
      rows.push(row);
    });
    
    return rows;
  }

  /**
   * Define Excel columns from row object keys
   * 
   * @param rowSample - Sample row object to extract keys from
   * @returns Array of column definitions
   * @private
   */
  private defineColumns(rowSample: any): Partial<ExcelJS.Column>[] {
    const keys = Object.keys(rowSample);
    
    return keys.map(key => ({
      key: key,
      header: this.formatHeaderText(key),
      width: 15 // Initial width, will be auto-sized later
    }));
  }

  /**
   * Format header text from snake_case to Title Case
   * 
   * @param key - Column key in snake_case
   * @returns Formatted header text
   * @private
   */
  private formatHeaderText(key: string): string {
    return key
      .split('_')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }

  /**
   * Apply styling to header row
   * 
   * Applies bold font, colored background, white text, borders, and center alignment
   * to the header row for professional appearance.
   * 
   * @param worksheet - Excel worksheet
   * @param customColor - Optional custom header background color (ARGB format)
   * @private
   */
  private applyHeaderStyling(worksheet: ExcelJS.Worksheet, customColor?: string): void {
    const headerRow = worksheet.getRow(1);
    const backgroundColor = customColor || this.DEFAULT_HEADER_COLOR;
    
    // Style each header cell
    headerRow.eachCell((cell) => {
      cell.font = {
        bold: true,
        size: 11,
        color: { argb: this.DEFAULT_HEADER_TEXT_COLOR }
      };
      
      cell.fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: backgroundColor }
      };
      
      cell.border = {
        top: { style: 'thin' },
        left: { style: 'thin' },
        bottom: { style: 'thin' },
        right: { style: 'thin' }
      };
      
      cell.alignment = {
        horizontal: 'center',
        vertical: 'middle'
      };
    });
    
    // Set header row height
    headerRow.height = 20;
  }

  /**
   * Apply formatting to data rows
   * 
   * Detects data types and applies appropriate formatting including date formats,
   * number formats, currency formats, and text alignment.
   * 
   * @param worksheet - Excel worksheet
   * @param startRow - Row number to start formatting (typically 2)
   * @private
   */
  private applyDataFormatting(worksheet: ExcelJS.Worksheet, startRow: number): void {
    const columnCount = worksheet.columnCount;
    
    // Process each column
    for (let colIndex = 1; colIndex <= columnCount; colIndex++) {
      const column = worksheet.getColumn(colIndex);
      const columnKey = column.key;
      
      // Collect column values for type detection
      const values: any[] = [];
      worksheet.eachRow((row, rowNumber) => {
        if (rowNumber >= startRow) {
          values.push(row.getCell(colIndex).value);
        }
      });
      
      // Detect column data type
      const dataType = this.detectColumnDataType(values, columnKey);
      
      // Apply formatting based on data type
      worksheet.eachRow((row, rowNumber) => {
        if (rowNumber >= startRow) {
          const cell = row.getCell(colIndex);
          
          switch (dataType) {
            case 'date':
              if (cell.value instanceof Date) {
                cell.numFmt = 'yyyy-mm-dd hh:mm:ss';
              }
              break;
              
            case 'number':
              if (typeof cell.value === 'number') {
                cell.numFmt = '0.00';
                cell.alignment = { horizontal: 'right' };
              }
              break;
              
            case 'currency':
              if (typeof cell.value === 'number') {
                cell.numFmt = '$#,##0.00';
                cell.alignment = { horizontal: 'right' };
              }
              break;
              
            case 'text':
            default:
              if (typeof cell.value === 'string' && cell.value.length > 50) {
                cell.alignment = { wrapText: true, vertical: 'top' };
              }
              break;
          }
          
          // Add borders to all data cells
          cell.border = {
            top: { style: 'thin' },
            left: { style: 'thin' },
            bottom: { style: 'thin' },
            right: { style: 'thin' }
          };
        }
      });
    }
  }

  /**
   * Auto-size columns based on content length
   * 
   * Calculates optimal column width based on content, respecting minimum and
   * maximum width constraints.
   * 
   * @param worksheet - Excel worksheet
   * @private
   */
  private autoSizeColumns(worksheet: ExcelJS.Worksheet): void {
    worksheet.columns.forEach(column => {
      let maxLength = 0;
      
      // Iterate through all cells in the column
      column.eachCell?.({ includeEmpty: false }, (cell) => {
        const cellValue = cell.value?.toString() || '';
        maxLength = Math.max(maxLength, cellValue.length);
      });
      
      // Set column width with min/max constraints
      const calculatedWidth = maxLength + 2; // Add padding
      column.width = Math.max(
        this.MIN_COLUMN_WIDTH,
        Math.min(calculatedWidth, this.MAX_COLUMN_WIDTH)
      );
    });
  }

  /**
   * Create metadata worksheet with document processing information
   * 
   * @param workbook - Excel workbook
   * @param document - Document object
   * @private
   */
  private createMetadataWorksheet(workbook: ExcelJS.Workbook, document: any): void {
    const worksheet = workbook.addWorksheet('Metadata');
    
    // Define metadata columns
    worksheet.columns = [
      { key: 'property', header: 'Property', width: 30 },
      { key: 'value', header: 'Value', width: 50 }
    ];
    
    // Apply header styling
    this.applyHeaderStyling(worksheet);
    
    // Add metadata rows
    const metadata = [
      { property: 'Export Date', value: new Date().toISOString() },
      { property: 'Document ID', value: document.id || '' },
      { property: 'File Name', value: document.file_name || '' },
      { property: 'Document Type', value: document.document_type || '' },
      { property: 'Status', value: document.status || '' },
      { property: 'Overall Confidence', value: this.formatNumber(document.confidence_score) },
      { property: 'Total Fields', value: (document.extracted_fields || document.fields || []).length },
      { property: 'Processing Date', value: document.processing_completed_at || '' },
      { property: 'Created At', value: document.created_at || '' },
      { property: 'Uploaded By', value: document.uploaded_by || '' },
      { property: 'File Size', value: document.file_size ? `${document.file_size} bytes` : '' },
      { property: 'Page Count', value: document.page_count || '' }
    ];
    
    metadata.forEach(item => {
      worksheet.addRow(item);
    });
    
    // Add borders to all cells
    worksheet.eachRow((row, rowNumber) => {
      if (rowNumber > 1) {
        row.eachCell(cell => {
          cell.border = {
            top: { style: 'thin' },
            left: { style: 'thin' },
            bottom: { style: 'thin' },
            right: { style: 'thin' }
          };
        });
      }
    });
  }

  /**
   * Create summary worksheet for batch export
   * 
   * @param workbook - Excel workbook
   * @param documents - Array of documents
   * @param _options - Export options (reserved for future use)
   * @private
   */
  private createBatchSummaryWorksheet(
    workbook: ExcelJS.Workbook,
    documents: any[],
    _options?: ExcelExportOptions
  ): void {
    const worksheet = workbook.addWorksheet('Summary');
    
    // Calculate statistics
    const totalDocs = documents.length;
    const docsByType: { [key: string]: number } = {};
    const docsByStatus: { [key: string]: number } = {};
    let totalFields = 0;
    let totalConfidence = 0;
    let confidenceCount = 0;
    
    documents.forEach(doc => {
      // Count by type
      const docType = doc.document_type || 'Unknown';
      docsByType[docType] = (docsByType[docType] || 0) + 1;
      
      // Count by status
      const status = doc.status || 'Unknown';
      docsByStatus[status] = (docsByStatus[status] || 0) + 1;
      
      // Count fields
      const fields = doc.extracted_fields || doc.fields || [];
      totalFields += fields.length;
      
      // Sum confidence scores
      if (doc.confidence_score != null) {
        totalConfidence += doc.confidence_score;
        confidenceCount++;
      }
    });
    
    const avgConfidence = confidenceCount > 0 ? totalConfidence / confidenceCount : 0;
    
    // Define columns
    worksheet.columns = [
      { key: 'metric', header: 'Metric', width: 30 },
      { key: 'value', header: 'Value', width: 20 }
    ];
    
    this.applyHeaderStyling(worksheet);
    
    // Add summary rows
    worksheet.addRow({ metric: 'Export Date', value: new Date().toISOString() });
    worksheet.addRow({ metric: 'Total Documents', value: totalDocs });
    worksheet.addRow({ metric: 'Total Fields', value: totalFields });
    worksheet.addRow({ metric: 'Average Confidence', value: avgConfidence.toFixed(2) });
    worksheet.addRow({ metric: '', value: '' }); // Empty row
    
    // Add documents by type
    worksheet.addRow({ metric: 'Documents by Type', value: '' });
    Object.entries(docsByType).forEach(([type, count]) => {
      worksheet.addRow({ metric: `  ${type}`, value: count });
    });
    
    worksheet.addRow({ metric: '', value: '' }); // Empty row
    
    // Add documents by status
    worksheet.addRow({ metric: 'Documents by Status', value: '' });
    Object.entries(docsByStatus).forEach(([status, count]) => {
      worksheet.addRow({ metric: `  ${status}`, value: count });
    });
    
    // Apply formatting
    worksheet.eachRow((row, rowNumber) => {
      if (rowNumber > 1) {
        row.eachCell(cell => {
          cell.border = {
            top: { style: 'thin' },
            left: { style: 'thin' },
            bottom: { style: 'thin' },
            right: { style: 'thin' }
          };
        });
      }
    });
  }

  /**
   * Detect column data type from values
   * 
   * Analyzes column values to determine if they are dates, numbers, currency, or text.
   * 
   * @param columnValues - Array of cell values from the column
   * @param columnKey - Column key name for hint-based detection
   * @returns Detected data type
   * @private
   */
  private detectColumnDataType(columnValues: any[], columnKey?: string): 'date' | 'number' | 'currency' | 'text' {
    // Hint-based detection from column key
    if (columnKey) {
      const lowerKey = columnKey.toLowerCase();
      
      if (lowerKey.includes('date') || lowerKey.includes('created') || lowerKey.includes('completed')) {
        return 'date';
      }
      
      if (lowerKey.includes('confidence') || lowerKey.includes('score')) {
        return 'number';
      }
      
      if (lowerKey.includes('amount') || lowerKey.includes('price') || lowerKey.includes('cost')) {
        return 'currency';
      }
    }
    
    // Value-based detection
    const nonEmptyValues = columnValues.filter(val => val !== null && val !== undefined && val !== '');
    
    if (nonEmptyValues.length === 0) {
      return 'text';
    }
    
    // Check if all values are dates
    const dateCount = nonEmptyValues.filter(val => val instanceof Date).length;
    if (dateCount === nonEmptyValues.length) {
      return 'date';
    }
    
    // Check if all values are numbers
    const numberCount = nonEmptyValues.filter(val => typeof val === 'number').length;
    if (numberCount === nonEmptyValues.length) {
      return 'number';
    }
    
    // Check if values contain currency symbols
    const currencyPattern = /[$€£¥]/;
    const currencyCount = nonEmptyValues.filter(val => 
      typeof val === 'string' && currencyPattern.test(val)
    ).length;
    
    if (currencyCount > 0) {
      return 'currency';
    }
    
    return 'text';
  }

  /**
   * Format cell value based on data type
   * 
   * @param value - Raw value to format
   * @param dataType - Target data type
   * @returns Formatted value
   * @private
   */
  private formatCellValue(value: any, dataType: string): any {
    if (value === null || value === undefined) {
      return '';
    }
    
    switch (dataType) {
      case 'date':
        if (value instanceof Date) {
          return value;
        }
        if (typeof value === 'string') {
          const parsed = new Date(value);
          return isNaN(parsed.getTime()) ? value : parsed;
        }
        return value;
        
      case 'number':
        const num = typeof value === 'number' ? value : parseFloat(value);
        return isNaN(num) ? value : num;
        
      case 'currency':
        // Extract numeric value from currency string
        if (typeof value === 'string') {
          const cleaned = value.replace(/[^0-9.-]/g, '');
          const num = parseFloat(cleaned);
          return isNaN(num) ? value : num;
        }
        return value;
        
      case 'text':
      default:
        return value.toString();
    }
  }

  /**
   * Format number with proper precision
   * 
   * @param value - Number value to format
   * @returns Formatted number or empty string
   * @private
   */
  private formatNumber(value: any): string | number {
    if (value === null || value === undefined) {
      return '';
    }
    
    const num = typeof value === 'number' ? value : parseFloat(value);
    return isNaN(num) ? '' : num;
  }

  /**
   * Get Excel column letter from column number
   * 
   * Converts column number to Excel column letter (1=A, 26=Z, 27=AA, etc.)
   * 
   * @param columnNumber - Column number (1-based)
   * @returns Excel column letter
   * @private
   */
  private getLastColumnLetter(columnNumber: number): string {
    let letter = '';
    let num = columnNumber;
    
    while (num > 0) {
      const remainder = (num - 1) % 26;
      letter = String.fromCharCode(65 + remainder) + letter;
      num = Math.floor((num - 1) / 26);
    }
    
    return letter;
  }

  /**
   * Validate document data structure
   * 
   * Ensures document has required fields for Excel export.
   * 
   * @param data - Document data to validate
   * @throws BadRequestException if validation fails
   * @private
   */
  private validateDocumentData(data: any): void {
    if (!data || typeof data !== 'object') {
      throw new BadRequestException('Document data must be a valid object');
    }
    
    if (!data.id) {
      throw new BadRequestException('Document must have an id field');
    }
    
    if (!data.file_name) {
      throw new BadRequestException('Document must have a file_name field');
    }
    
    const fields = data.extracted_fields || data.fields;
    
    if (!fields || !Array.isArray(fields)) {
      throw new BadRequestException('Document must have extracted_fields or fields array');
    }
  }
}
