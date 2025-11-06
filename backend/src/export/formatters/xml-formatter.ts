import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { Buffer } from 'buffer';

/**
 * Export options interface for XML formatting configuration.
 * Extends base export options with XML-specific settings.
 */
export interface XmlExportOptions {
  /** Array of field names to include in export (filters fields if provided) */
  selectedFields?: string[];
  
  /** Whether to include metadata section with processing info and corrections */
  includeMetadata?: boolean;
  
  /** Number of spaces for XML indentation (default: 2) */
  indentSpaces?: number;
  
  /** Whether to include XML schema reference in root element */
  includeSchema?: boolean;
  
  /** Schema location URL for XSD validation */
  schemaLocation?: string;
  
  /** Whether to wrap text content in CDATA sections for special characters */
  useCDATA?: boolean;
}

/**
 * Represents a bounding box coordinate structure for field extraction
 */
interface BoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
  page: number;
}

/**
 * Represents a document field structure
 */
interface DocumentField {
  field_name: string;
  field_label?: string;
  field_type: string;
  original_value: string;
  corrected_value?: string;
  normalized_value?: string;
  confidence_score: number;
  validation_status: string;
  page_number?: number;
  bounding_box?: BoundingBox;
  extraction_method?: string;
}

/**
 * Represents a document structure
 */
interface Document {
  id: string;
  file_name: string;
  document_type: string;
  status: string;
  confidence_score: number;
  created_at: string | Date;
  processing_completed_at?: string | Date;
  extracted_fields?: DocumentField[];
  fields?: DocumentField[];
  processing_date?: string | Date;
  uploaded_by?: string;
  approved_by?: string;
  approved_at?: string | Date;
  corrections?: any[];
}

/**
 * Injectable NestJS service implementing XML export formatter for the OCR Processing Application.
 * Transforms document data from PostgreSQL and MongoDB into well-formed XML format with proper
 * schema, encoding (UTF-8), and indentation per Phase 9 Section 0.5.9 Group 9A requirements.
 * 
 * @class XmlFormatterService
 * @implements format() method for single document export
 * @implements formatBatch() method for bulk document export
 */
@Injectable()
export class XmlFormatterService {
  private readonly logger = new Logger(XmlFormatterService.name);

  /**
   * Formats a single document into XML format.
   * 
   * @param documentData - The document object containing id, file_name, and extracted_fields
   * @param options - Optional XML export configuration
   * @returns Buffer containing UTF-8 encoded XML string
   * @throws BadRequestException if document data is invalid or malformed
   * 
   * @example
   * const buffer = xmlFormatter.format(document, { includeMetadata: true, indentSpaces: 2 });
   */
  format(documentData: any, options?: XmlExportOptions): Buffer {
    try {
      // Validate document data structure
      this.validateDocumentData(documentData);

      const indentSpaces = options?.indentSpaces ?? 2;
      const selectedFields = options?.selectedFields;

      this.logger.log({
        message: 'Formatting document to XML',
        document_id: documentData.id,
        field_count: (documentData.extracted_fields || documentData.fields || []).length,
        include_schema: options?.includeSchema ?? false,
        include_metadata: options?.includeMetadata ?? false,
      });

      // Build XML string
      let xml = '<?xml version="1.0" encoding="UTF-8"?>\n';
      
      // Add root element with optional schema reference
      if (options?.includeSchema && options?.schemaLocation) {
        xml += '<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" ';
        xml += `xsi:schemaLocation="${this.escapeXML(options.schemaLocation)}">\n`;
      } else {
        xml += '<document>\n';
      }

      // Add document element content
      xml += this.buildDocumentElement(documentData, 1, selectedFields, indentSpaces, options);

      // Close root element
      xml += '</document>';

      // Validate XML is well-formed
      this.validateXML(xml);

      // Convert to Buffer
      return Buffer.from(xml, 'utf-8');
    } catch (error) {
      if (error instanceof BadRequestException) {
        throw error;
      }
      this.logger.error({
        message: 'Error formatting document to XML',
        error: error.message,
        document_id: documentData?.id,
      });
      throw new BadRequestException(`Failed to format document to XML: ${error.message}`);
    }
  }

  /**
   * Formats multiple documents into XML format with a documents root element.
   * 
   * @param documents - Array of document objects
   * @param options - Optional XML export configuration
   * @returns Buffer containing UTF-8 encoded XML string with all documents
   * @throws BadRequestException if documents array is invalid or any document is malformed
   * 
   * @example
   * const buffer = xmlFormatter.formatBatch(documents, { selectedFields: ['invoice_number'] });
   */
  formatBatch(documents: any[], options?: XmlExportOptions): Buffer {
    try {
      // Validate documents array
      if (!Array.isArray(documents)) {
        throw new BadRequestException('Documents must be an array');
      }

      if (documents.length === 0) {
        throw new BadRequestException('Documents array cannot be empty');
      }

      const indentSpaces = options?.indentSpaces ?? 2;
      const selectedFields = options?.selectedFields;

      this.logger.log({
        message: 'Formatting batch of documents to XML',
        document_count: documents.length,
        include_schema: options?.includeSchema ?? false,
        include_metadata: options?.includeMetadata ?? false,
      });

      // Build XML string
      let xml = '<?xml version="1.0" encoding="UTF-8"?>\n';
      
      // Add root element with count attribute
      if (options?.includeSchema && options?.schemaLocation) {
        xml += `<documents count="${documents.length}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" `;
        xml += `xsi:schemaLocation="${this.escapeXML(options.schemaLocation)}">\n`;
      } else {
        xml += `<documents count="${documents.length}">\n`;
      }

      // Add each document
      for (const document of documents) {
        this.validateDocumentData(document);
        xml += this.indent(1, indentSpaces) + '<document>\n';
        xml += this.buildDocumentElement(document, 2, selectedFields, indentSpaces, options);
        xml += this.indent(1, indentSpaces) + '</document>\n';
      }

      // Close root element
      xml += '</documents>';

      // Validate XML is well-formed
      this.validateXML(xml);

      // Convert to Buffer
      return Buffer.from(xml, 'utf-8');
    } catch (error) {
      if (error instanceof BadRequestException) {
        throw error;
      }
      this.logger.error({
        message: 'Error formatting batch to XML',
        error: error.message,
        document_count: documents?.length,
      });
      throw new BadRequestException(`Failed to format batch to XML: ${error.message}`);
    }
  }

  /**
   * Builds the XML content for a document element.
   * Creates nested structure with metadata and fields.
   * 
   * @param document - Document object to format
   * @param indentLevel - Current indentation level
   * @param selectedFields - Optional array of field names to include
   * @param spaces - Number of spaces per indent level
   * @param options - Export options for additional settings
   * @returns XML string fragment for document content
   * @private
   */
  private buildDocumentElement(
    document: Document,
    indentLevel: number,
    selectedFields: string[] | undefined,
    spaces: number,
    options?: XmlExportOptions,
  ): string {
    let xml = '';

    // Add document metadata as attributes
    const indent1 = this.indent(indentLevel, spaces);
    const indent2 = this.indent(indentLevel + 1, spaces);

    // Document metadata elements
    xml += indent1 + `<id>${this.escapeXML(document.id)}</id>\n`;
    xml += indent1 + `<file_name>${this.escapeXML(document.file_name)}</file_name>\n`;
    xml += indent1 + `<document_type>${this.escapeXML(document.document_type)}</document_type>\n`;
    xml += indent1 + `<status>${this.escapeXML(document.status)}</status>\n`;
    xml += indent1 + `<confidence_score>${document.confidence_score}</confidence_score>\n`;
    xml += indent1 + `<created_at>${this.escapeXML(document.created_at)}</created_at>\n`;
    
    if (document.processing_completed_at) {
      xml += indent1 + `<processing_completed_at>${this.escapeXML(document.processing_completed_at)}</processing_completed_at>\n`;
    }

    // Add fields section
    const fields = document.extracted_fields || document.fields || [];
    const filteredFields = selectedFields
      ? fields.filter(f => selectedFields.includes(f.field_name))
      : fields;

    if (filteredFields.length > 0) {
      xml += indent1 + '<fields>\n';
      
      for (const field of filteredFields) {
        xml += this.buildFieldElement(field, indentLevel + 1, spaces, options);
      }
      
      xml += indent1 + '</fields>\n';
    }

    // Add metadata section if requested
    if (options?.includeMetadata) {
      xml += this.buildMetadata(document, indentLevel, spaces);
    }

    return xml;
  }

  /**
   * Builds XML element for a single field with all its properties.
   * 
   * @param field - Field object to format
   * @param indentLevel - Current indentation level
   * @param spaces - Number of spaces per indent level
   * @param options - Export options for CDATA handling
   * @returns XML string fragment for field element
   * @private
   */
  private buildFieldElement(
    field: DocumentField,
    indentLevel: number,
    spaces: number,
    options?: XmlExportOptions,
  ): string {
    let xml = '';
    const indent1 = this.indent(indentLevel, spaces);
    const indent2 = this.indent(indentLevel + 1, spaces);

    xml += indent1 + '<field>\n';

    // Field properties
    xml += indent2 + `<field_name>${this.escapeXML(field.field_name)}</field_name>\n`;
    
    if (field.field_label) {
      xml += indent2 + `<field_label>${this.escapeXML(field.field_label)}</field_label>\n`;
    }
    
    xml += indent2 + `<field_type>${this.escapeXML(field.field_type)}</field_type>\n`;
    
    // Handle value wrapping with CDATA if option is enabled
    if (options?.useCDATA) {
      xml += indent2 + `<original_value><![CDATA[${field.original_value || ''}]]></original_value>\n`;
      if (field.corrected_value !== undefined) {
        xml += indent2 + `<corrected_value><![CDATA[${field.corrected_value || ''}]]></corrected_value>\n`;
      }
      if (field.normalized_value !== undefined) {
        xml += indent2 + `<normalized_value><![CDATA[${field.normalized_value || ''}]]></normalized_value>\n`;
      }
    } else {
      xml += indent2 + `<original_value>${this.escapeXML(field.original_value)}</original_value>\n`;
      if (field.corrected_value !== undefined) {
        xml += indent2 + `<corrected_value>${this.escapeXML(field.corrected_value)}</corrected_value>\n`;
      }
      if (field.normalized_value !== undefined) {
        xml += indent2 + `<normalized_value>${this.escapeXML(field.normalized_value)}</normalized_value>\n`;
      }
    }
    
    xml += indent2 + `<confidence_score>${field.confidence_score}</confidence_score>\n`;
    xml += indent2 + `<validation_status>${this.escapeXML(field.validation_status)}</validation_status>\n`;
    
    if (field.page_number !== undefined) {
      xml += indent2 + `<page_number>${field.page_number}</page_number>\n`;
    }
    
    if (field.bounding_box) {
      xml += indent2 + '<bounding_box>\n';
      const indent3 = this.indent(indentLevel + 2, spaces);
      xml += indent3 + `<x>${field.bounding_box.x}</x>\n`;
      xml += indent3 + `<y>${field.bounding_box.y}</y>\n`;
      xml += indent3 + `<width>${field.bounding_box.width}</width>\n`;
      xml += indent3 + `<height>${field.bounding_box.height}</height>\n`;
      xml += indent3 + `<page>${field.bounding_box.page}</page>\n`;
      xml += indent2 + '</bounding_box>\n';
    }
    
    if (field.extraction_method) {
      xml += indent2 + `<extraction_method>${this.escapeXML(field.extraction_method)}</extraction_method>\n`;
    }

    xml += indent1 + '</field>\n';

    return xml;
  }

  /**
   * Builds metadata XML section with processing info and corrections.
   * 
   * @param document - Document object containing metadata
   * @param indentLevel - Current indentation level
   * @param spaces - Number of spaces per indent level
   * @returns XML string fragment for metadata section
   * @private
   */
  private buildMetadata(document: Document, indentLevel: number, spaces: number): string {
    let xml = '';
    const indent1 = this.indent(indentLevel, spaces);
    const indent2 = this.indent(indentLevel + 1, spaces);
    const indent3 = this.indent(indentLevel + 2, spaces);

    xml += indent1 + '<metadata>\n';

    // Processing info
    xml += indent2 + '<processing_info>\n';
    
    if (document.processing_date) {
      xml += indent3 + `<processing_date>${this.escapeXML(document.processing_date)}</processing_date>\n`;
    }
    
    if (document.uploaded_by) {
      xml += indent3 + `<uploaded_by>${this.escapeXML(document.uploaded_by)}</uploaded_by>\n`;
    }
    
    if (document.approved_by) {
      xml += indent3 + `<approved_by>${this.escapeXML(document.approved_by)}</approved_by>\n`;
    }
    
    if (document.approved_at) {
      xml += indent3 + `<approved_at>${this.escapeXML(document.approved_at)}</approved_at>\n`;
    }
    
    xml += indent2 + '</processing_info>\n';

    // Confidence metrics
    xml += indent2 + '<confidence_metrics>\n';
    xml += indent3 + `<overall_score>${document.confidence_score}</overall_score>\n`;
    
    const fields = document.extracted_fields || document.fields || [];
    if (fields.length > 0) {
      xml += indent3 + '<field_scores>\n';
      const indent4 = this.indent(indentLevel + 3, spaces);
      
      for (const field of fields) {
        xml += indent4 + `<field name="${this.escapeXML(field.field_name)}" score="${field.confidence_score}"/>\n`;
      }
      
      xml += indent3 + '</field_scores>\n';
    }
    
    xml += indent2 + '</confidence_metrics>\n';

    // Corrections history if available
    if (document.corrections && document.corrections.length > 0) {
      xml += indent2 + '<corrections>\n';
      
      for (const correction of document.corrections) {
        xml += indent3 + '<correction>\n';
        const indent4 = this.indent(indentLevel + 3, spaces);
        
        if (correction.field_name) {
          xml += indent4 + `<field_name>${this.escapeXML(correction.field_name)}</field_name>\n`;
        }
        if (correction.original_value !== undefined) {
          xml += indent4 + `<original_value>${this.escapeXML(correction.original_value)}</original_value>\n`;
        }
        if (correction.corrected_value !== undefined) {
          xml += indent4 + `<corrected_value>${this.escapeXML(correction.corrected_value)}</corrected_value>\n`;
        }
        if (correction.corrected_by) {
          xml += indent4 + `<corrected_by>${this.escapeXML(correction.corrected_by)}</corrected_by>\n`;
        }
        if (correction.corrected_at) {
          xml += indent4 + `<corrected_at>${this.escapeXML(correction.corrected_at)}</corrected_at>\n`;
        }
        
        xml += indent3 + '</correction>\n';
      }
      
      xml += indent2 + '</corrections>\n';
    }

    xml += indent1 + '</metadata>\n';

    return xml;
  }

  /**
   * Escapes special XML characters to their entity references.
   * Handles: &, <, >, ', "
   * 
   * @param value - Value to escape (any type, will be converted to string)
   * @returns Escaped string safe for XML content
   * @private
   */
  private escapeXML(value: any): string {
    if (value === null || value === undefined) {
      return '';
    }

    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/'/g, '&apos;')
      .replace(/"/g, '&quot;');
  }

  /**
   * Generates indentation string with specified number of spaces.
   * 
   * @param level - Indentation level (0, 1, 2, ...)
   * @param spaces - Number of spaces per level
   * @returns String containing the appropriate number of spaces
   * @private
   */
  private indent(level: number, spaces: number): string {
    return ' '.repeat(level * spaces);
  }

  /**
   * Validates that generated XML string is well-formed.
   * Performs basic check for matching opening and closing tags.
   * 
   * @param xmlString - XML string to validate
   * @returns true if validation passes
   * @throws BadRequestException if XML is not well-formed
   * @private
   */
  private validateXML(xmlString: string): boolean {
    try {
      // Basic validation: check for matching tags
      // Count opening and closing tags
      const openTags = xmlString.match(/<[^/][^>]*>/g) || [];
      const closeTags = xmlString.match(/<\/[^>]+>/g) || [];
      
      // Filter out self-closing tags and XML declaration
      const selfClosingTags = xmlString.match(/<[^>]+\/>/g) || [];
      const xmlDeclaration = xmlString.match(/<\?xml[^>]+\?>/g) || [];
      
      const openCount = openTags.length - selfClosingTags.length - xmlDeclaration.length;
      const closeCount = closeTags.length;

      if (openCount !== closeCount) {
        throw new BadRequestException(
          `XML validation failed: Mismatched tags (${openCount} opening, ${closeCount} closing)`
        );
      }

      return true;
    } catch (error) {
      if (error instanceof BadRequestException) {
        throw error;
      }
      throw new BadRequestException(`XML validation failed: ${error.message}`);
    }
  }

  /**
   * Validates document data structure has required fields.
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
      throw new BadRequestException('Document data must include an id field');
    }

    if (!data.file_name) {
      throw new BadRequestException('Document data must include a file_name field');
    }

    // Check for fields array (either extracted_fields or fields)
    const fields = data.extracted_fields || data.fields;
    if (!Array.isArray(fields)) {
      throw new BadRequestException('Document data must include an extracted_fields or fields array');
    }
  }
}
