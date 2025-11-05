import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Migration: CreateRolesPermissions003
 * 
 * Creates the Role-Based Access Control (RBAC) system with four interconnected tables:
 * 1. roles - Defines user roles (both system-wide and tenant-specific)
 * 2. permissions - Defines granular permissions with resource and action
 * 3. role_permissions - Junction table mapping roles to permissions (many-to-many)
 * 4. user_roles - Junction table assigning roles to users with audit trail
 * 
 * Implements multi-tenant role isolation, system role support, and comprehensive
 * foreign key constraints per Section 0.5.3 Phase 2 Authentication System requirements.
 * 
 * Includes seeding of default permissions for document management, template management,
 * user management, integration management, API key management, and analytics access.
 */
export class CreateRolesPermissions003 implements MigrationInterface {
  /**
   * Executes the migration to create RBAC tables and seed default permissions
   * 
   * @param queryRunner - TypeORM QueryRunner for executing SQL statements within transaction
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // ============================================================
    // Table 1: roles
    // Stores role definitions with support for both system-wide and tenant-specific roles
    // ============================================================
    await queryRunner.query(`
      CREATE TABLE IF NOT EXISTS roles (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        account_id UUID NULL,
        name VARCHAR(100) NOT NULL,
        description TEXT,
        is_system_role BOOLEAN NOT NULL DEFAULT false,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        CONSTRAINT fk_roles_account_id FOREIGN KEY (account_id) 
          REFERENCES accounts(id) ON DELETE CASCADE,
        CONSTRAINT uq_roles_account_name UNIQUE (account_id, name)
      )
    `);

    // Create index on account_id for efficient tenant-specific role queries
    await queryRunner.query(`
      CREATE INDEX IF NOT EXISTS idx_roles_account_id ON roles(account_id)
    `);

    // ============================================================
    // Table 2: permissions
    // Stores granular permissions with resource and action specifications
    // ============================================================
    await queryRunner.query(`
      CREATE TABLE IF NOT EXISTS permissions (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        name VARCHAR(100) NOT NULL UNIQUE,
        description TEXT,
        resource VARCHAR(50) NOT NULL,
        action VARCHAR(50) NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
      )
    `);

    // Create composite index on resource and action for efficient permission lookups
    await queryRunner.query(`
      CREATE INDEX IF NOT EXISTS idx_permissions_resource_action 
        ON permissions(resource, action)
    `);

    // ============================================================
    // Table 3: role_permissions
    // Junction table for many-to-many relationship between roles and permissions
    // ============================================================
    await queryRunner.query(`
      CREATE TABLE IF NOT EXISTS role_permissions (
        role_id UUID NOT NULL,
        permission_id UUID NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        PRIMARY KEY (role_id, permission_id),
        CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) 
          REFERENCES roles(id) ON DELETE CASCADE,
        CONSTRAINT fk_role_permissions_permission_id FOREIGN KEY (permission_id) 
          REFERENCES permissions(id) ON DELETE CASCADE
      )
    `);

    // ============================================================
    // Table 4: user_roles
    // Junction table for assigning roles to users with audit trail
    // ============================================================
    await queryRunner.query(`
      CREATE TABLE IF NOT EXISTS user_roles (
        user_id UUID NOT NULL,
        role_id UUID NOT NULL,
        assigned_by UUID NULL,
        assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        PRIMARY KEY (user_id, role_id),
        CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) 
          REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) 
          REFERENCES roles(id) ON DELETE CASCADE,
        CONSTRAINT fk_user_roles_assigned_by FOREIGN KEY (assigned_by) 
          REFERENCES users(id) ON DELETE SET NULL
      )
    `);

    // ============================================================
    // Seed Default Permissions
    // Creates system-wide permissions for all core application features
    // ============================================================
    
    // Document management permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('document.read', 'View documents and their extracted data', 'document', 'read'),
        ('document.write', 'Upload and create new documents', 'document', 'write'),
        ('document.delete', 'Delete documents permanently', 'document', 'delete'),
        ('document.approve', 'Approve extracted data and mark documents as validated', 'document', 'approve')
    `);

    // Template management permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('template.read', 'View extraction templates', 'template', 'read'),
        ('template.write', 'Create and modify extraction templates', 'template', 'write'),
        ('template.delete', 'Delete extraction templates', 'template', 'delete')
    `);

    // User management permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('user.read', 'View user accounts and profiles', 'user', 'read'),
        ('user.write', 'Create and modify user accounts', 'user', 'write'),
        ('user.delete', 'Delete user accounts', 'user', 'delete')
    `);

    // Integration management permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('integration.manage', 'Configure and manage third-party integrations', 'integration', 'manage')
    `);

    // API key management permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('api_key.manage', 'Create and manage API keys for programmatic access', 'api_key', 'manage')
    `);

    // Analytics access permissions
    await queryRunner.query(`
      INSERT INTO permissions (name, description, resource, action) VALUES
        ('analytics.view', 'View analytics dashboard and reports', 'analytics', 'view')
    `);
  }

  /**
   * Reverses the migration by dropping all RBAC tables in correct dependency order
   * 
   * @param queryRunner - TypeORM QueryRunner for executing SQL statements within transaction
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop tables in reverse dependency order to avoid foreign key constraint violations
    
    // Drop user_roles junction table first (depends on users and roles)
    await queryRunner.query(`DROP TABLE IF EXISTS user_roles CASCADE`);

    // Drop role_permissions junction table (depends on roles and permissions)
    await queryRunner.query(`DROP TABLE IF EXISTS role_permissions CASCADE`);

    // Drop permissions table (no dependencies on other tables)
    await queryRunner.query(`DROP TABLE IF EXISTS permissions CASCADE`);

    // Drop roles table last (depends on accounts table which should persist)
    await queryRunner.query(`DROP TABLE IF EXISTS roles CASCADE`);
  }
}
