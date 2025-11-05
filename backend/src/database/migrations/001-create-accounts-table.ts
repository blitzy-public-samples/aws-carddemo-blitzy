import { MigrationInterface, QueryRunner, Table, TableIndex } from 'typeorm';

/**
 * Migration: CreateAccountsTable001
 * 
 * Purpose: Creates the foundational accounts table for multi-tenant architecture.
 * This is the MOST FOUNDATIONAL table in the system - all other entities (users,
 * documents, templates, processing_jobs, etc.) reference accounts via account_id
 * foreign key constraints to ensure workspace isolation.
 * 
 * Features:
 * - Multi-tenant organization management with UUID-based tenant isolation
 * - Flexible subscription plan support (free, basic, professional, enterprise)
 * - Usage limits and quotas per plan (users, documents, storage, API rate limits)
 * - Feature flags stored as JSONB for flexible capability enablement
 * - Billing lifecycle management (active, suspended, cancelled)
 * - URL-based tenant isolation via unique subdomain
 * 
 * Compliance:
 * - Section 0.5.2 Phase 1 Database Setup requirements
 * - Section 0.1.1 Implicit requirement for workspace isolation
 * - Section 0.7.2 Database guidelines (UTC timestamps, reversible migrations)
 * - Section 0.1.2 Integration constraints (1000 req/hour default rate limit)
 */
export class CreateAccountsTable001 implements MigrationInterface {
  /**
   * Execute forward migration: Create accounts table
   * 
   * Creates the accounts table with:
   * - UUID primary key for tenant identification
   * - Organization details and branding (name, subdomain)
   * - Subscription plan configuration with usage limits
   * - JSONB feature flags for flexible capability management
   * - Billing information and lifecycle status
   * - Audit timestamps in UTC
   * - Unique constraint on subdomain for URL-based tenant isolation
   * - Optimized indexes for common query patterns
   * 
   * @param queryRunner - TypeORM QueryRunner for executing SQL commands
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create enum types for plan_type and billing_status
    await queryRunner.query(`
      CREATE TYPE account_plan_type_enum AS ENUM (
        'free',
        'basic',
        'professional',
        'enterprise'
      );
    `);

    await queryRunner.query(`
      CREATE TYPE account_billing_status_enum AS ENUM (
        'active',
        'suspended',
        'cancelled'
      );
    `);

    // Create accounts table with all required columns
    await queryRunner.createTable(
      new Table({
        name: 'accounts',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'gen_random_uuid()',
            comment: 'Unique identifier for the account (tenant)',
          },
          {
            name: 'organization_name',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Legal name or display name of the organization',
          },
          {
            name: 'subdomain',
            type: 'varchar',
            length: '63',
            isNullable: false,
            isUnique: true,
            comment: 'Unique subdomain for tenant-specific URLs (e.g., acme.ocr-app.com)',
          },
          {
            name: 'plan_type',
            type: 'account_plan_type_enum',
            isNullable: false,
            default: "'free'",
            comment: 'Subscription plan tier determining feature access and limits',
          },
          {
            name: 'max_users',
            type: 'integer',
            isNullable: false,
            default: 5,
            comment: 'Maximum number of users allowed for this account per plan',
          },
          {
            name: 'max_documents_per_month',
            type: 'integer',
            isNullable: false,
            default: 100,
            comment: 'Monthly quota for document processing',
          },
          {
            name: 'storage_quota_gb',
            type: 'integer',
            isNullable: false,
            default: 10,
            comment: 'Total storage quota in gigabytes for S3 document storage',
          },
          {
            name: 'api_rate_limit',
            type: 'integer',
            isNullable: false,
            default: 1000,
            comment: 'API requests allowed per hour per Section 0.1.2 integration constraints',
          },
          {
            name: 'features',
            type: 'jsonb',
            isNullable: false,
            default: "'{}'",
            comment: 'Feature flags as JSON object enabling/disabling capabilities (e.g., {"batch_processing": true, "custom_templates": false})',
          },
          {
            name: 'billing_email',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Primary email address for billing and invoicing communications',
          },
          {
            name: 'billing_status',
            type: 'account_billing_status_enum',
            isNullable: false,
            default: "'active'",
            comment: 'Current billing lifecycle status',
          },
          {
            name: 'subscription_ends_at',
            type: 'timestamp with time zone',
            isNullable: true,
            comment: 'Trial or subscription expiration date (null for indefinite subscriptions)',
          },
          {
            name: 'is_active',
            type: 'boolean',
            isNullable: false,
            default: true,
            comment: 'Account suspension flag - false disables all access',
          },
          {
            name: 'created_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'Account creation timestamp in UTC per Section 0.7.2',
          },
          {
            name: 'updated_at',
            type: 'timestamp with time zone',
            isNullable: false,
            default: 'CURRENT_TIMESTAMP',
            comment: 'Last modification timestamp in UTC per Section 0.7.2',
          },
        ],
      }),
      true, // ifNotExists
    );

    // Create index on subdomain for tenant lookup by URL
    await queryRunner.createIndex(
      'accounts',
      new TableIndex({
        name: 'IDX_accounts_subdomain',
        columnNames: ['subdomain'],
      }),
    );

    // Create composite index on plan_type and is_active for filtering active accounts by plan
    await queryRunner.createIndex(
      'accounts',
      new TableIndex({
        name: 'IDX_accounts_plan_type_is_active',
        columnNames: ['plan_type', 'is_active'],
      }),
    );

    // Create composite index on billing_status and subscription_ends_at for billing queries
    await queryRunner.createIndex(
      'accounts',
      new TableIndex({
        name: 'IDX_accounts_billing_status_subscription_ends_at',
        columnNames: ['billing_status', 'subscription_ends_at'],
      }),
    );

    // Create trigger to automatically update updated_at timestamp on row modification
    await queryRunner.query(`
      CREATE OR REPLACE FUNCTION update_accounts_updated_at()
      RETURNS TRIGGER AS $$
      BEGIN
        NEW.updated_at = CURRENT_TIMESTAMP;
        RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;
    `);

    await queryRunner.query(`
      CREATE TRIGGER accounts_updated_at_trigger
      BEFORE UPDATE ON accounts
      FOR EACH ROW
      EXECUTE FUNCTION update_accounts_updated_at();
    `);
  }

  /**
   * Execute rollback migration: Drop accounts table
   * 
   * Drops the accounts table, all indexes, triggers, and enum types created in up().
   * This ensures clean rollback capability per Section 0.7.2 database guidelines
   * requiring all migrations to be reversible.
   * 
   * WARNING: This will CASCADE delete all data in dependent tables (users, documents,
   * templates, etc.) if foreign key constraints are already in place.
   * 
   * @param queryRunner - TypeORM QueryRunner for executing SQL commands
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop trigger and function
    await queryRunner.query(`
      DROP TRIGGER IF EXISTS accounts_updated_at_trigger ON accounts;
    `);

    await queryRunner.query(`
      DROP FUNCTION IF EXISTS update_accounts_updated_at();
    `);

    // Drop indexes (explicit drops for clarity, though dropTable would cascade)
    await queryRunner.dropIndex('accounts', 'IDX_accounts_billing_status_subscription_ends_at');
    await queryRunner.dropIndex('accounts', 'IDX_accounts_plan_type_is_active');
    await queryRunner.dropIndex('accounts', 'IDX_accounts_subdomain');

    // Drop accounts table (CASCADE will drop dependent foreign keys if they exist)
    await queryRunner.dropTable('accounts', true, true, true);

    // Drop enum types
    await queryRunner.query(`
      DROP TYPE IF EXISTS account_billing_status_enum;
    `);

    await queryRunner.query(`
      DROP TYPE IF EXISTS account_plan_type_enum;
    `);
  }
}
