import type { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Migration: CreateAuditLogsTable007
 * 
 * Creates the audit_logs table for comprehensive audit trail of all system operations.
 * Tracks user actions, admin operations, document changes, API access, and security events.
 * 
 * Features:
 * - UUID primary key for distributed system compatibility
 * - Nullable account_id and user_id to support system-level events
 * - JSONB changes field for before/after state tracking
 * - Multiple indexes for efficient audit query performance
 * - Foreign keys with ON DELETE SET NULL to preserve audit trail integrity
 * - Immutable records (no updated_at field)
 * 
 * Compliance: SOC 2, GDPR, CCPA audit requirements
 * Reference: Section 0.5.13 Phase 12 Audit Logging, Section 0.7.2 Security Requirements
 */
export class CreateAuditLogsTable007 implements MigrationInterface {
  /**
   * Execute the migration - creates audit_logs table with all constraints and indexes
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   * @returns Promise<void>
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create audit_logs table with comprehensive audit trail columns
    await queryRunner.query(`
      CREATE TABLE audit_logs (
        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
        account_id UUID NULL,
        user_id UUID NULL,
        action_type VARCHAR(50) NOT NULL,
        resource_type VARCHAR(100) NOT NULL,
        resource_id UUID NULL,
        changes JSONB NULL,
        ip_address VARCHAR(45) NULL,
        user_agent TEXT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'success',
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_audit_logs_account FOREIGN KEY (account_id) 
          REFERENCES accounts(id) ON DELETE SET NULL,
        CONSTRAINT fk_audit_logs_user FOREIGN KEY (user_id) 
          REFERENCES users(id) ON DELETE SET NULL,
        CONSTRAINT chk_audit_logs_action_type CHECK (
          action_type IN (
            'create', 'read', 'update', 'delete',
            'login', 'logout', 'login_failed',
            'approve', 'reject', 'export', 'import',
            'upload', 'download', 'share',
            'permission_change', 'role_change',
            'api_key_create', 'api_key_revoke',
            'integration_connect', 'integration_disconnect',
            'template_create', 'template_update', 'template_delete',
            'batch_process', 'webhook_trigger',
            'password_reset', 'mfa_enable', 'mfa_disable',
            'account_create', 'account_delete', 'account_suspend',
            'security_event', 'system_event'
          )
        ),
        CONSTRAINT chk_audit_logs_status CHECK (
          status IN ('success', 'failure', 'pending', 'error')
        )
      );
    `);

    // Create comment on table for documentation
    await queryRunner.query(`
      COMMENT ON TABLE audit_logs IS 
        'Comprehensive audit trail for all system operations, user actions, and security events. Immutable records for compliance and forensic analysis.';
    `);

    // Create comments on key columns for clarity
    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.account_id IS 
        'Reference to account (nullable for system-level events). ON DELETE SET NULL preserves audit trail.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.user_id IS 
        'Reference to user who performed action (nullable for system/automated events). ON DELETE SET NULL preserves audit trail.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.action_type IS 
        'Type of action performed: create, read, update, delete, login, logout, approve, reject, export, etc.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.resource_type IS 
        'Type of resource affected: document, user, template, integration, api_key, etc.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.resource_id IS 
        'UUID of the specific resource affected by the action (nullable for bulk operations or system events).';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.changes IS 
        'JSONB field containing before/after state for update operations. Structure: {"before": {...}, "after": {...}, "fields_changed": [...]}';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.ip_address IS 
        'IP address of request origin (IPv4 or IPv6 format, max 45 chars for IPv6).';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.user_agent IS 
        'User agent string from HTTP request for tracking client application/browser.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.status IS 
        'Outcome of the action: success, failure, pending, or error.';
    `);

    await queryRunner.query(`
      COMMENT ON COLUMN audit_logs.created_at IS 
        'Timestamp when audit log entry was created (UTC). No updated_at since audit logs are immutable.';
    `);

    // Create index on account_id and created_at for tenant-specific audit queries
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_account_created 
        ON audit_logs(account_id, created_at DESC) 
        WHERE account_id IS NOT NULL;
    `);

    // Create index on user_id and created_at for user activity tracking
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_user_created 
        ON audit_logs(user_id, created_at DESC) 
        WHERE user_id IS NOT NULL;
    `);

    // Create index on resource_type and resource_id for resource history queries
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_resource 
        ON audit_logs(resource_type, resource_id, created_at DESC) 
        WHERE resource_id IS NOT NULL;
    `);

    // Create index on action_type and created_at for action-specific queries
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_action_created 
        ON audit_logs(action_type, created_at DESC);
    `);

    // Create index on status for filtering failed/error events
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_status 
        ON audit_logs(status, created_at DESC) 
        WHERE status IN ('failure', 'error');
    `);

    // Create composite index for security event monitoring
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_security 
        ON audit_logs(action_type, status, created_at DESC) 
        WHERE action_type IN ('login_failed', 'security_event', 'permission_change', 'role_change');
    `);

    // Create index on created_at for time-based queries and data retention
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_created_at 
        ON audit_logs(created_at DESC);
    `);

    // Create GIN index on changes JSONB field for efficient JSON queries
    await queryRunner.query(`
      CREATE INDEX idx_audit_logs_changes_gin 
        ON audit_logs USING GIN(changes) 
        WHERE changes IS NOT NULL;
    `);
  }

  /**
   * Revert the migration - drops audit_logs table and all associated indexes and constraints
   * 
   * @param queryRunner - TypeORM QueryRunner for executing database operations
   * @returns Promise<void>
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop the audit_logs table with CASCADE
    // This automatically drops all indexes, constraints, and dependent objects
    // Using IF EXISTS makes this operation idempotent
    await queryRunner.query(`DROP TABLE IF EXISTS audit_logs CASCADE;`);
  }
}
