import { MigrationInterface, QueryRunner, Table, TableIndex, TableForeignKey } from 'typeorm';

/**
 * Migration: CreateIntegrationsTable009
 * 
 * Creates the integrations table for storing third-party integration configurations
 * including OAuth tokens, API credentials, and connection settings for:
 * - QuickBooks
 * - Salesforce
 * - NetSuite
 * - SharePoint
 * - Dropbox
 * 
 * Features:
 * - Multi-tenant architecture with account_id foreign key
 * - Encrypted storage for OAuth tokens (application-level encryption required)
 * - JSONB field for flexible API credential storage
 * - Connection status tracking
 * - Sync history tracking
 * - Unique constraint preventing duplicate integrations per account
 * - Comprehensive indexing for query optimization
 * - Automatic updated_at timestamp via trigger
 * 
 * Per Section 0.5.11 Phase 10 - Integrations and API requirements
 * Per Section 0.7.2 - Database migration best practices
 */
export class CreateIntegrationsTable009 implements MigrationInterface {
    /**
     * Execute the migration - creates integrations table with all constraints and indexes
     * 
     * @param queryRunner - TypeORM query runner for executing SQL commands
     */
    public async up(queryRunner: QueryRunner): Promise<void> {
        // Create enum type for integration_type to ensure data integrity
        await queryRunner.query(`
            CREATE TYPE integration_type_enum AS ENUM (
                'quickbooks',
                'salesforce',
                'netsuite',
                'sharepoint',
                'dropbox'
            )
        `);

        // Create integrations table with comprehensive column definitions
        await queryRunner.createTable(
            new Table({
                name: 'integrations',
                columns: [
                    {
                        name: 'id',
                        type: 'uuid',
                        isPrimary: true,
                        default: 'uuid_generate_v4()',
                        comment: 'Unique identifier for the integration record',
                    },
                    {
                        name: 'account_id',
                        type: 'uuid',
                        isNullable: false,
                        comment: 'Foreign key reference to accounts table for multi-tenant isolation',
                    },
                    {
                        name: 'integration_type',
                        type: 'integration_type_enum',
                        isNullable: false,
                        comment: 'Type of third-party integration (quickbooks, salesforce, netsuite, sharepoint, dropbox)',
                    },
                    {
                        name: 'oauth_access_token',
                        type: 'text',
                        isNullable: true,
                        comment: 'Encrypted OAuth 2.0 access token - application-level encryption required before storage',
                    },
                    {
                        name: 'oauth_refresh_token',
                        type: 'text',
                        isNullable: true,
                        comment: 'Encrypted OAuth 2.0 refresh token - application-level encryption required before storage',
                    },
                    {
                        name: 'oauth_expires_at',
                        type: 'timestamp with time zone',
                        isNullable: true,
                        comment: 'OAuth access token expiration timestamp in UTC - used for automatic token refresh',
                    },
                    {
                        name: 'api_credentials',
                        type: 'jsonb',
                        isNullable: true,
                        default: "'{}'",
                        comment: 'Flexible JSON storage for additional API configuration (e.g., instance URLs, account IDs, API keys, consumer keys/secrets)',
                    },
                    {
                        name: 'is_connected',
                        type: 'boolean',
                        default: false,
                        isNullable: false,
                        comment: 'Indicates if integration is currently active and successfully connected',
                    },
                    {
                        name: 'last_sync_at',
                        type: 'timestamp with time zone',
                        isNullable: true,
                        comment: 'Timestamp of last successful data synchronization in UTC',
                    },
                    {
                        name: 'created_at',
                        type: 'timestamp with time zone',
                        default: 'CURRENT_TIMESTAMP',
                        isNullable: false,
                        comment: 'Record creation timestamp in UTC',
                    },
                    {
                        name: 'updated_at',
                        type: 'timestamp with time zone',
                        default: 'CURRENT_TIMESTAMP',
                        isNullable: false,
                        comment: 'Record last update timestamp in UTC - automatically updated via trigger',
                    },
                ],
            }),
            true, // ifNotExists
        );

        // Create unique constraint to prevent duplicate integrations per account
        // Each account can only have one integration of each type
        await queryRunner.query(`
            ALTER TABLE integrations
            ADD CONSTRAINT uq_integrations_account_type 
            UNIQUE (account_id, integration_type)
        `);

        // Create foreign key constraint to accounts table with CASCADE operations
        // When an account is deleted, all associated integrations are automatically removed
        await queryRunner.createForeignKey(
            'integrations',
            new TableForeignKey({
                name: 'fk_integrations_account',
                columnNames: ['account_id'],
                referencedTableName: 'accounts',
                referencedColumnNames: ['id'],
                onDelete: 'CASCADE',
                onUpdate: 'CASCADE',
            }),
        );

        // Create index on account_id for efficient single-account queries
        // Used when fetching all integrations for a specific account
        await queryRunner.createIndex(
            'integrations',
            new TableIndex({
                name: 'idx_integrations_account_id',
                columnNames: ['account_id'],
            }),
        );

        // Create composite index on account_id and integration_type
        // Optimizes queries for specific integration lookups (most common access pattern)
        await queryRunner.createIndex(
            'integrations',
            new TableIndex({
                name: 'idx_integrations_account_type',
                columnNames: ['account_id', 'integration_type'],
            }),
        );

        // Create index on integration_type for filtering by integration type across accounts
        // Used for administrative queries and reporting
        await queryRunner.createIndex(
            'integrations',
            new TableIndex({
                name: 'idx_integrations_type',
                columnNames: ['integration_type'],
            }),
        );

        // Create index on is_connected for filtering active integrations
        // Optimizes queries for finding all connected/disconnected integrations
        await queryRunner.createIndex(
            'integrations',
            new TableIndex({
                name: 'idx_integrations_connected',
                columnNames: ['is_connected'],
            }),
        );

        // Create index on oauth_expires_at for token refresh operations
        // Enables efficient queries to find tokens expiring soon
        await queryRunner.createIndex(
            'integrations',
            new TableIndex({
                name: 'idx_integrations_token_expiry',
                columnNames: ['oauth_expires_at'],
            }),
        );

        // Create trigger function to automatically update updated_at timestamp
        // Ensures updated_at is always current without requiring application logic
        await queryRunner.query(`
            CREATE OR REPLACE FUNCTION update_integrations_updated_at()
            RETURNS TRIGGER AS $$
            BEGIN
                NEW.updated_at = CURRENT_TIMESTAMP;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        `);

        // Attach trigger to integrations table
        await queryRunner.query(`
            CREATE TRIGGER trigger_update_integrations_updated_at
            BEFORE UPDATE ON integrations
            FOR EACH ROW
            EXECUTE FUNCTION update_integrations_updated_at();
        `);
    }

    /**
     * Rollback the migration - removes all constraints, indexes, triggers, and the table
     * 
     * @param queryRunner - TypeORM query runner for executing SQL commands
     */
    public async down(queryRunner: QueryRunner): Promise<void> {
        // Drop trigger and function in reverse order
        await queryRunner.query(`
            DROP TRIGGER IF EXISTS trigger_update_integrations_updated_at ON integrations
        `);
        
        await queryRunner.query(`
            DROP FUNCTION IF EXISTS update_integrations_updated_at()
        `);

        // Drop indexes in reverse order of creation
        await queryRunner.dropIndex('integrations', 'idx_integrations_token_expiry');
        await queryRunner.dropIndex('integrations', 'idx_integrations_connected');
        await queryRunner.dropIndex('integrations', 'idx_integrations_type');
        await queryRunner.dropIndex('integrations', 'idx_integrations_account_type');
        await queryRunner.dropIndex('integrations', 'idx_integrations_account_id');

        // Drop foreign key constraint
        await queryRunner.dropForeignKey('integrations', 'fk_integrations_account');

        // Drop unique constraint
        await queryRunner.query(`
            ALTER TABLE integrations
            DROP CONSTRAINT IF EXISTS uq_integrations_account_type
        `);

        // Drop the integrations table
        await queryRunner.dropTable('integrations', true, true, true);

        // Drop the enum type
        await queryRunner.query(`
            DROP TYPE IF EXISTS integration_type_enum
        `);
    }
}
