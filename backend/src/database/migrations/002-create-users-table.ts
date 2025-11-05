import { Table, TableForeignKey, TableIndex } from 'typeorm';

import type { MigrationInterface, QueryRunner} from 'typeorm';

/**
 * Migration: CreateUsersTable002
 * 
 * Creates the users table for storing user authentication data, profile information,
 * OAuth provider linkage, MFA settings, and multi-tenant account association.
 * 
 * Features:
 * - Multi-tenant architecture with account_id foreign key
 * - Support for local authentication (email/password) and OAuth 2.0 (Google, Microsoft)
 * - Multi-factor authentication (MFA) with TOTP secret storage
 * - Email verification workflow
 * - User profile data (name, avatar)
 * - Activity tracking (last login, active status)
 * - Optimized indexes for common query patterns
 * 
 * Security:
 * - Password hashes stored using bcrypt (nullable for OAuth-only users)
 * - MFA secrets encrypted at application layer before storage
 * - Email unique per account (multi-tenant isolation)
 * - Cascade deletion when account is removed
 * 
 * References: Section 0.5.3 Phase 2 User Management requirements
 */
export class CreateUsersTable002 implements MigrationInterface {
  /**
   * Run the migration - creates users table with all indexes and constraints
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create users table with comprehensive authentication and profile fields
    await queryRunner.createTable(
      new Table({
        name: 'users',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            default: 'uuid_generate_v4()',
            comment: 'Unique user identifier (UUID v4)'
          },
          {
            name: 'account_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key to accounts table for multi-tenant isolation'
          },
          {
            name: 'email',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'User email address (unique per account, not globally unique)'
          },
          {
            name: 'password_hash',
            type: 'varchar',
            length: '255',
            isNullable: true,
            comment: 'Bcrypt password hash (nullable for OAuth-only users who have no local password)'
          },
          {
            name: 'first_name',
            type: 'varchar',
            length: '100',
            isNullable: false,
            comment: 'User first name'
          },
          {
            name: 'last_name',
            type: 'varchar',
            length: '100',
            isNullable: false,
            comment: 'User last name'
          },
          {
            name: 'avatar_url',
            type: 'varchar',
            length: '500',
            isNullable: true,
            comment: 'URL to user profile avatar image (S3 or OAuth provider URL)'
          },
          {
            name: 'oauth_provider',
            type: 'enum',
            enum: ['local', 'google', 'microsoft'],
            default: "'local'",
            isNullable: false,
            comment: 'Authentication provider type: local (email/password), google (Google OAuth), microsoft (Microsoft OAuth)'
          },
          {
            name: 'oauth_provider_id',
            type: 'varchar',
            length: '255',
            isNullable: true,
            comment: 'External provider user ID for OAuth users (e.g., Google sub claim or Microsoft oid)'
          },
          {
            name: 'is_email_verified',
            type: 'boolean',
            default: false,
            isNullable: false,
            comment: 'Whether user has verified their email address'
          },
          {
            name: 'email_verification_token',
            type: 'varchar',
            length: '255',
            isNullable: true,
            comment: 'Token for email verification workflow (set to null after verification)'
          },
          {
            name: 'mfa_enabled',
            type: 'boolean',
            default: false,
            isNullable: false,
            comment: 'Whether multi-factor authentication is enabled for this user'
          },
          {
            name: 'mfa_secret',
            type: 'varchar',
            length: '255',
            isNullable: true,
            comment: 'TOTP secret for MFA (encrypted at application layer before storage)'
          },
          {
            name: 'last_login_at',
            type: 'timestamp',
            isNullable: true,
            comment: 'Timestamp of most recent successful login (UTC)'
          },
          {
            name: 'is_active',
            type: 'boolean',
            default: true,
            isNullable: false,
            comment: 'Whether user account is active (false = deactivated)'
          },
          {
            name: 'created_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Record creation timestamp (UTC)'
          },
          {
            name: 'updated_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            onUpdate: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Record last update timestamp (UTC)'
          }
        ]
      }),
      true // Enable foreign keys
    );

    // Create foreign key constraint to accounts table with cascade deletion
    await queryRunner.createForeignKey(
      'users',
      new TableForeignKey({
        name: 'fk_users_account_id',
        columnNames: ['account_id'],
        referencedTableName: 'accounts',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE'
      })
    );

    // Create unique constraint for email per account (multi-tenant email uniqueness)
    await queryRunner.createIndex(
      'users',
      new TableIndex({
        name: 'idx_users_account_email_unique',
        columnNames: ['account_id', 'email'],
        isUnique: true
      })
    );

    // Create composite index on account_id and is_active for efficient user listing queries
    await queryRunner.createIndex(
      'users',
      new TableIndex({
        name: 'idx_users_account_active',
        columnNames: ['account_id', 'is_active']
      })
    );

    // Create index on email for fast email lookup (login queries)
    await queryRunner.createIndex(
      'users',
      new TableIndex({
        name: 'idx_users_email',
        columnNames: ['email']
      })
    );

    // Create composite index on oauth_provider and oauth_provider_id for OAuth login queries
    await queryRunner.createIndex(
      'users',
      new TableIndex({
        name: 'idx_users_oauth_provider',
        columnNames: ['oauth_provider', 'oauth_provider_id']
      })
    );

    // Create index on email_verification_token for verification workflow
    await queryRunner.createIndex(
      'users',
      new TableIndex({
        name: 'idx_users_email_verification_token',
        columnNames: ['email_verification_token']
      })
    );
  }

  /**
   * Revert the migration - drops users table and all associated indexes and constraints
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop indexes first (order matters for clean rollback)
    await queryRunner.dropIndex('users', 'idx_users_email_verification_token');
    await queryRunner.dropIndex('users', 'idx_users_oauth_provider');
    await queryRunner.dropIndex('users', 'idx_users_email');
    await queryRunner.dropIndex('users', 'idx_users_account_active');
    await queryRunner.dropIndex('users', 'idx_users_account_email_unique');

    // Drop foreign key constraint
    await queryRunner.dropForeignKey('users', 'fk_users_account_id');

    // Drop the entire users table
    await queryRunner.dropTable('users');
  }
}
