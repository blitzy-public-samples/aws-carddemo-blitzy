import { MigrationInterface, QueryRunner, Table, TableIndex, TableForeignKey } from 'typeorm';

/**
 * Migration: CreateTemplatesTable006
 * 
 * Creates templates and template_fields tables for storing custom extraction template definitions.
 * Templates enable users to define document zones and field mappings for automated data extraction.
 * 
 * Tables:
 * - templates: Stores template metadata including document type, version, and ownership
 * - template_fields: Stores field definitions with zone coordinates and validation rules
 * 
 * Features:
 * - Multi-tenant isolation via account_id
 * - Template versioning support
 * - JSONB storage for flexible zone coordinates and validation rules
 * - Comprehensive indexing for query performance
 * - Referential integrity with proper CASCADE constraints
 * 
 * @implements {MigrationInterface}
 */
export class CreateTemplatesTable006 implements MigrationInterface {
  /**
   * Executes the migration to create templates and template_fields tables
   * 
   * Creates:
   * 1. templates table with document type classification and versioning
   * 2. template_fields table with zone coordinates and validation rules
   * 3. Foreign key constraints for data integrity
   * 4. Indexes for optimized query performance
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing SQL commands
   * @returns {Promise<void>}
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create templates table
    await queryRunner.createTable(
      new Table({
        name: 'templates',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'uuid_generate_v4()',
            comment: 'Unique identifier for the template',
          },
          {
            name: 'account_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to accounts table for multi-tenant isolation',
          },
          {
            name: 'name',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Human-readable name for the template',
          },
          {
            name: 'description',
            type: 'text',
            isNullable: true,
            comment: 'Detailed description of the template purpose and usage',
          },
          {
            name: 'document_type',
            type: 'enum',
            enum: ['invoice', 'receipt', 'contract', 'form'],
            isNullable: false,
            comment: 'Type of document this template is designed to process',
          },
          {
            name: 'version',
            type: 'integer',
            isNullable: false,
            default: 1,
            comment: 'Version number for template revision tracking',
          },
          {
            name: 'is_active',
            type: 'boolean',
            isNullable: false,
            default: true,
            comment: 'Flag indicating whether template is currently active',
          },
          {
            name: 'created_by',
            type: 'uuid',
            isNullable: true,
            comment: 'Foreign key to users table indicating template creator',
          },
          {
            name: 'created_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'UTC timestamp of template creation',
          },
          {
            name: 'updated_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'UTC timestamp of last template modification',
          },
        ],
      }),
      true,
    );

    // Create foreign key constraint: templates.account_id -> accounts.id
    await queryRunner.createForeignKey(
      'templates',
      new TableForeignKey({
        name: 'fk_templates_account_id',
        columnNames: ['account_id'],
        referencedTableName: 'accounts',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE',
      }),
    );

    // Create foreign key constraint: templates.created_by -> users.id
    await queryRunner.createForeignKey(
      'templates',
      new TableForeignKey({
        name: 'fk_templates_created_by',
        columnNames: ['created_by'],
        referencedTableName: 'users',
        referencedColumnNames: ['id'],
        onDelete: 'SET NULL',
        onUpdate: 'CASCADE',
      }),
    );

    // Create index on account_id for tenant-scoped queries
    await queryRunner.createIndex(
      'templates',
      new TableIndex({
        name: 'idx_templates_account_id',
        columnNames: ['account_id'],
      }),
    );

    // Create composite index on account_id, document_type, is_active for filtering
    await queryRunner.createIndex(
      'templates',
      new TableIndex({
        name: 'idx_templates_account_document_active',
        columnNames: ['account_id', 'document_type', 'is_active'],
      }),
    );

    // Create index on document_type for document classification queries
    await queryRunner.createIndex(
      'templates',
      new TableIndex({
        name: 'idx_templates_document_type',
        columnNames: ['document_type'],
      }),
    );

    // Create template_fields table
    await queryRunner.createTable(
      new Table({
        name: 'template_fields',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'uuid_generate_v4()',
            comment: 'Unique identifier for the template field',
          },
          {
            name: 'template_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to templates table',
          },
          {
            name: 'field_name',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Name of the field to extract (e.g., invoice_number, total_amount)',
          },
          {
            name: 'field_type',
            type: 'enum',
            enum: ['text', 'number', 'date', 'currency', 'email'],
            isNullable: false,
            comment: 'Data type of the field for validation and formatting',
          },
          {
            name: 'zone_coordinates',
            type: 'jsonb',
            isNullable: false,
            comment: 'Bounding box coordinates for field location {x, y, width, height}',
          },
          {
            name: 'validation_rules',
            type: 'jsonb',
            isNullable: true,
            comment: 'Custom validation rules for field value verification (e.g., regex, min/max)',
          },
          {
            name: 'is_required',
            type: 'boolean',
            isNullable: false,
            default: false,
            comment: 'Flag indicating whether field extraction is mandatory',
          },
          {
            name: 'created_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'UTC timestamp of field definition creation',
          },
          {
            name: 'updated_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'UTC timestamp of last field definition modification',
          },
        ],
      }),
      true,
    );

    // Create foreign key constraint: template_fields.template_id -> templates.id
    await queryRunner.createForeignKey(
      'template_fields',
      new TableForeignKey({
        name: 'fk_template_fields_template_id',
        columnNames: ['template_id'],
        referencedTableName: 'templates',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE',
      }),
    );

    // Create index on template_id for efficient field lookup
    await queryRunner.createIndex(
      'template_fields',
      new TableIndex({
        name: 'idx_template_fields_template_id',
        columnNames: ['template_id'],
      }),
    );

    // Create composite index on template_id and field_name for specific field queries
    await queryRunner.createIndex(
      'template_fields',
      new TableIndex({
        name: 'idx_template_fields_template_field_name',
        columnNames: ['template_id', 'field_name'],
      }),
    );
  }

  /**
   * Reverts the migration by dropping templates and template_fields tables
   * 
   * Drops in reverse order to respect foreign key dependencies:
   * 1. Drop template_fields table (has FK to templates)
   * 2. Drop templates table (has FKs to accounts and users)
   * 
   * Note: Indexes and foreign keys are automatically dropped with tables
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing SQL commands
   * @returns {Promise<void>}
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop template_fields table (includes all indexes and foreign keys)
    await queryRunner.dropTable('template_fields', true);

    // Drop templates table (includes all indexes and foreign keys)
    await queryRunner.dropTable('templates', true);
  }
}
