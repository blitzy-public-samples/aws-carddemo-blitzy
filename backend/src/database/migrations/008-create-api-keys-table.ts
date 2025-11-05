import { Table, TableForeignKey, TableIndex } from 'typeorm';

import type { MigrationInterface, QueryRunner} from 'typeorm';

/**
 * Migration: CreateApiKeysTable008
 * 
 * Creates the api_keys table for storing API authentication keys with the following features:
 * - Secure hashed key storage (never stores plain text keys)
 * - Scoped permissions per API key
 * - Rate limiting configuration per key
 * - Expiration date support
 * - Usage tracking (last_used_at)
 * - Multi-tenant support via account_id
 * - User ownership tracking via user_id
 * 
 * Security Requirements:
 * - API keys MUST be stored hashed using a strong hashing algorithm (bcrypt/argon2)
 * - key_hash field is indexed for fast authentication lookups
 * - Foreign keys cascade on delete to maintain referential integrity
 * 
 * Implements Phase 10 API Management requirements from Section 0.5.11
 * Follows security guidelines from Section 0.7.2 (mandating hashed key storage)
 * 
 * @implements {MigrationInterface}
 */
export class CreateApiKeysTable008 implements MigrationInterface {
  /**
   * Run the migration - creates api_keys table with proper constraints and indexes
   * 
   * Table Structure:
   * - id: UUID primary key
   * - account_id: Foreign key to accounts table (multi-tenant isolation)
   * - user_id: Foreign key to users table (ownership tracking)
   * - key_name: Human-readable name for the API key
   * - key_hash: Hashed API key (NEVER plain text)
   * - scopes: Array of permission strings (e.g., ['document.read', 'document.write'])
   * - rate_limit_per_hour: Maximum API requests per hour (default: 1000)
   * - expires_at: Optional expiration timestamp
   * - last_used_at: Timestamp of last API request using this key
   * - is_active: Boolean flag to enable/disable key without deletion
   * - created_at: Record creation timestamp (UTC)
   * - updated_at: Record last update timestamp (UTC)
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing database operations
   * @returns {Promise<void>}
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create api_keys table
    await queryRunner.createTable(
      new Table({
        name: 'api_keys',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'gen_random_uuid()',
            comment: 'Unique identifier for the API key record'
          },
          {
            name: 'account_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to accounts table - ensures multi-tenant isolation'
          },
          {
            name: 'user_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to users table - tracks which user created this API key'
          },
          {
            name: 'key_name',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Human-readable name for the API key (e.g., "Production Integration", "Mobile App")'
          },
          {
            name: 'key_hash',
            type: 'varchar',
            length: '255',
            isNullable: false,
            isUnique: true,
            comment: 'Hashed API key using bcrypt/argon2 - NEVER stores plain text for security'
          },
          {
            name: 'scopes',
            type: 'text',
            isArray: true,
            default: 'ARRAY[]::text[]',
            comment: 'Array of permission scopes (e.g., ["document.read", "document.write", "template.manage"])'
          },
          {
            name: 'rate_limit_per_hour',
            type: 'integer',
            default: 1000,
            isNullable: false,
            comment: 'Maximum number of API requests allowed per hour (default: 1000)'
          },
          {
            name: 'expires_at',
            type: 'timestamp with time zone',
            isNullable: true,
            comment: 'Optional expiration timestamp - key becomes invalid after this date'
          },
          {
            name: 'last_used_at',
            type: 'timestamp with time zone',
            isNullable: true,
            comment: 'Timestamp of the last API request using this key - updated on each use'
          },
          {
            name: 'is_active',
            type: 'boolean',
            default: true,
            isNullable: false,
            comment: 'Flag to enable/disable the API key without deletion'
          },
          {
            name: 'created_at',
            type: 'timestamp with time zone',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Record creation timestamp in UTC'
          },
          {
            name: 'updated_at',
            type: 'timestamp with time zone',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Record last update timestamp in UTC'
          }
        ]
      }),
      true // indicates this is a table creation
    );

    // Create foreign key constraint to accounts table
    // ON DELETE CASCADE ensures that when an account is deleted, all associated API keys are removed
    await queryRunner.createForeignKey(
      'api_keys',
      new TableForeignKey({
        name: 'fk_api_keys_account_id',
        columnNames: ['account_id'],
        referencedTableName: 'accounts',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE'
      })
    );

    // Create foreign key constraint to users table
    // ON DELETE CASCADE ensures that when a user is deleted, all their API keys are removed
    await queryRunner.createForeignKey(
      'api_keys',
      new TableForeignKey({
        name: 'fk_api_keys_user_id',
        columnNames: ['user_id'],
        referencedTableName: 'users',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE'
      })
    );

    // Create unique index on key_hash for fast authentication lookups
    // This is critical for API performance - every API request validates the key against this hash
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_key_hash_unique',
        columnNames: ['key_hash'],
        isUnique: true
      })
    );

    // Create composite index on (account_id, user_id, is_active)
    // Optimizes queries that filter by account, user, and active status
    // Common query pattern: "Get all active API keys for account X created by user Y"
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_account_user_active',
        columnNames: ['account_id', 'user_id', 'is_active']
      })
    );

    // Create index on account_id for tenant-specific queries
    // Optimizes queries like: "Get all API keys for this account"
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_account_id',
        columnNames: ['account_id']
      })
    );

    // Create index on user_id for user-specific queries
    // Optimizes queries like: "Get all API keys created by this user"
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_user_id',
        columnNames: ['user_id']
      })
    );

    // Create index on is_active for filtering active/inactive keys
    // Optimizes queries that filter by active status
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_is_active',
        columnNames: ['is_active']
      })
    );

    // Create index on expires_at for cleanup jobs
    // Optimizes queries that find expired keys for deactivation/cleanup
    await queryRunner.createIndex(
      'api_keys',
      new TableIndex({
        name: 'idx_api_keys_expires_at',
        columnNames: ['expires_at']
      })
    );
  }

  /**
   * Revert the migration - drops api_keys table and all associated constraints
   * 
   * Rollback order (reverse of creation):
   * 1. Drop indexes
   * 2. Drop foreign key constraints
   * 3. Drop table
   * 
   * This ensures clean rollback without constraint violations
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing database operations
   * @returns {Promise<void>}
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop indexes first (reverse order of creation)
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_expires_at');
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_is_active');
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_user_id');
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_account_id');
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_account_user_active');
    await queryRunner.dropIndex('api_keys', 'idx_api_keys_key_hash_unique');

    // Drop foreign key constraints
    await queryRunner.dropForeignKey('api_keys', 'fk_api_keys_user_id');
    await queryRunner.dropForeignKey('api_keys', 'fk_api_keys_account_id');

    // Finally, drop the table
    await queryRunner.dropTable('api_keys', true);
  }
}
