import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  OneToMany,
  Index,
} from 'typeorm';

import { TemplateField } from './template-field.entity';

/**
 * Template entity for storing custom extraction templates.
 * 
 * Each Template represents a reusable document processing configuration
 * that defines which fields to extract and from where on the document.
 * Templates enable automatic field extraction based on document type
 * classification and user-defined extraction zones.
 * 
 * Features:
 * - Template metadata: name, description, document type
 * - Multi-tenant isolation: account_id for workspace separation
 * - Status management: is_active flag for template lifecycle
 * - Field relationships: one-to-many with TemplateField entities
 * - Usage tracking: timestamps for audit and analytics
 * 
 * Integration Points:
 * - Created via TemplatesService.createTemplate()
 * - Used by OCR Processing Service for document classification
 * - Updated via TemplatesService.updateTemplate()
 * - Deleted via TemplatesService.deleteTemplate() (cascades to fields)
 * 
 * Database Schema:
 * - Defined in migration 006-create-templates-table.ts
 * - Foreign key to accounts(id) for multi-tenant isolation
 * - One-to-many relationship with template_fields
 * - Indexes on account_id and (account_id, template_name)
 * 
 * Security:
 * - Multi-tenant isolation enforced via account_id
 * - All queries must filter by account_id
 * - Template sharing not supported (account-specific only)
 * 
 * @see TemplateField - Child field definition entity
 * @see TemplatesService - Service for template management
 * @see Section 0.5.7 - Phase 7 Template Builder implementation
 * @see Section 0.7.1 - Security and multi-tenant isolation requirements
 */
@Entity('templates')
@Index(['accountId'])
@Index(['accountId', 'templateName'])
@Index(['isActive'])
export class Template {
  /**
   * Unique identifier for the template.
   * Uses UUID for better distribution and security per Section 0.7.1.
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Foreign key to account for multi-tenant isolation.
   * Every template belongs to exactly one account.
   * Indexed for query performance optimization.
   */
  @Column({ type: 'uuid', name: 'account_id' })
  accountId!: string;

  /**
   * Template name (unique within account).
   * Used for template selection in UI and API.
   * Examples: 'Standard Invoice', 'Purchase Order', 'Receipt'
   * Indexed as part of composite index (account_id, template_name).
   */
  @Column({ type: 'varchar', length: 100, name: 'template_name' })
  templateName!: string;

  /**
   * Human-readable template description.
   * Explains the template's purpose and usage.
   * Optional field for additional context.
   */
  @Column({ type: 'text', nullable: true })
  description!: string | null;

  /**
   * Document type this template is designed for.
   * Used by document classifier to auto-select template.
   * Examples: 'invoice', 'receipt', 'purchase_order', 'contract', 'form'
   * Should match document types from NLP classification service.
   */
  @Column({ type: 'varchar', length: 50, name: 'document_type' })
  documentType!: string;

  /**
   * Whether template is active and available for use.
   * Inactive templates are hidden from selection but retained for history.
   * Defaults to true.
   * Indexed for fast filtering of active templates.
   */
  @Column({ type: 'boolean', name: 'is_active', default: true })
  isActive!: boolean;

  /**
   * Template field definitions (one-to-many relationship).
   * Contains all field configurations including zone coordinates.
   * Ordered by field_order for consistent display.
   * Cascade delete: removing template removes all its fields.
   */
  @OneToMany(() => TemplateField, (field) => field.template, {
    cascade: true,
  })
  fields!: TemplateField[];

  /**
   * Timestamp of template creation (UTC).
   * Automatically set on INSERT.
   * Used for audit trail and template history.
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * Timestamp of last template update (UTC).
   * Automatically updated on every UPDATE.
   * Used for audit trail and change tracking.
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;

  /**
   * Get count of fields defined in this template.
   * 
   * Convenience method for quickly checking template completeness
   * without loading all field data.
   * 
   * @returns Number of fields in template
   */
  getFieldCount(): number {
    return this.fields?.length || 0;
  }

  /**
   * Get all required fields in this template.
   * 
   * Filters template fields to return only those marked as required.
   * Useful for validation logic to ensure all required fields are extracted.
   * 
   * @returns Array of required TemplateField entities
   */
  getRequiredFields(): TemplateField[] {
    return this.fields?.filter(field => field.isRequired) || [];
  }

  /**
   * Check if template has any fields defined.
   * 
   * Templates without fields cannot be used for extraction.
   * This check can be used before allowing template activation.
   * 
   * @returns true if template has at least one field, false otherwise
   */
  hasFields(): boolean {
    return (this.fields?.length || 0) > 0;
  }
}
