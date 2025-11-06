/**
 * Base Entity Interface
 * 
 * This file defines common interfaces for database entities used throughout the OCR Processing Application.
 * These interfaces ensure consistency across all TypeORM entities and support critical requirements:
 * 
 * - Multi-tenant data isolation with accountId
 * - Audit trail with creation/update timestamps in UTC
 * - Soft delete functionality for data retention
 * - Optimistic locking with version field
 * - User tracking with createdBy/updatedBy fields
 * 
 * @module backend/src/common/interfaces/base-entity.interface
 * @see Section 0.7.1 - ALL timestamps MUST use UTC
 * @see Agent Action Plan - Multi-tenant architecture requirement
 * @see Section 0.5.4 - Account table for multi-tenant support
 */

/**
 * BaseEntity Interface
 * 
 * Standard interface for all database entities in the OCR Processing Application.
 * Provides common fields for multi-tenant isolation, audit tracking, soft deletion,
 * and optimistic locking.
 * 
 * Usage Example:
 * ```typescript
 * import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, UpdateDateColumn, DeleteDateColumn, VersionColumn } from 'typeorm';
 * import { BaseEntity } from '@/common/interfaces/base-entity.interface';
 * 
 * @Entity('documents')
 * export class Document implements BaseEntity {
 *   @PrimaryGeneratedColumn('uuid')
 *   id: string;
 * 
 *   @Column('uuid')
 *   accountId: string;
 * 
 *   @CreateDateColumn({ type: 'timestamptz' })
 *   createdAt: Date;
 * 
 *   @UpdateDateColumn({ type: 'timestamptz' })
 *   updatedAt: Date;
 * 
 *   @DeleteDateColumn({ type: 'timestamptz', nullable: true })
 *   deletedAt: Date | null;
 * 
 *   @VersionColumn()
 *   version: number;
 * 
 *   @Column('uuid', { nullable: true })
 *   createdBy: string | null;
 * 
 *   @Column('uuid', { nullable: true })
 *   updatedBy: string | null;
 * 
 *   // Additional entity-specific fields...
 * }
 * ```
 * 
 * @interface BaseEntity
 */
export interface BaseEntity {
  /**
   * Unique identifier for the entity.
   * 
   * Uses UUID (Universally Unique Identifier) for better distributed system support
   * and to avoid sequential ID enumeration attacks.
   * 
   * @type {string}
   * @example "550e8400-e29b-41d4-a716-446655440000"
   */
  id: string;

  /**
   * Multi-tenant account identifier for data isolation.
   * 
   * CRITICAL: This field enables multi-tenant architecture where each account's data
   * is strictly isolated. ALL database queries MUST filter by accountId to prevent
   * cross-account data access.
   * 
   * References: accounts.id
   * 
   * @type {string}
   * @example "123e4567-e89b-12d3-a456-426614174000"
   * @see Agent Action Plan Section 0.7.1 - ALL queries MUST be tenant-isolated
   */
  accountId: string;

  /**
   * Timestamp when the entity was created.
   * 
   * Automatically set on entity creation. MUST be stored in UTC timezone
   * as per Section 0.7.1 requirements.
   * 
   * TypeORM Implementation: Use @CreateDateColumn({ type: 'timestamptz' })
   * 
   * @type {Date}
   * @readonly
   * @example new Date('2025-10-31T12:00:00.000Z')
   */
  createdAt: Date;

  /**
   * Timestamp when the entity was last updated.
   * 
   * Automatically updated on any entity modification. MUST be stored in UTC timezone
   * as per Section 0.7.1 requirements.
   * 
   * TypeORM Implementation: Use @UpdateDateColumn({ type: 'timestamptz' })
   * 
   * @type {Date}
   * @example new Date('2025-11-01T15:30:00.000Z')
   */
  updatedAt: Date;

  /**
   * Timestamp when the entity was soft deleted.
   * 
   * Soft delete allows logical deletion without permanently removing data from the database.
   * When deletedAt is set, the entity is considered deleted but remains in the database
   * for audit purposes, recovery, and compliance with data retention policies.
   * 
   * - null: Entity is active (not deleted)
   * - Date: Entity is soft deleted at this timestamp
   * 
   * TypeORM Implementation: Use @DeleteDateColumn({ type: 'timestamptz', nullable: true })
   * 
   * @type {Date | null}
   * @optional
   * @default null
   * @example null (active) or new Date('2025-12-01T10:00:00.000Z') (deleted)
   */
  deletedAt?: Date | null;

  /**
   * Version number for optimistic locking.
   * 
   * Incremented automatically on each update to prevent race conditions in concurrent updates.
   * When updating an entity, TypeORM checks that the version in the database matches
   * the version in the entity being updated. If they don't match, an OptimisticLockVersionMismatchError
   * is thrown, indicating that another process has modified the entity.
   * 
   * TypeORM Implementation: Use @VersionColumn()
   * 
   * Usage:
   * - Prevents lost updates in concurrent scenarios
   * - Ensures data integrity during simultaneous modifications
   * - Automatically managed by TypeORM (no manual intervention needed)
   * 
   * @type {number}
   * @default 1
   * @example 1 (initial version), 2 (after first update), 3 (after second update)
   */
  version: number;

  /**
   * User ID who created the entity.
   * 
   * References: users.id
   * 
   * Provides audit trail for tracking which user created this entity.
   * Useful for compliance, debugging, and user activity reporting.
   * 
   * - null: System-generated entity or user no longer exists
   * - string: UUID of the user who created this entity
   * 
   * @type {string | null}
   * @optional
   * @default null
   * @example "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
   */
  createdBy?: string | null;

  /**
   * User ID who last updated the entity.
   * 
   * References: users.id
   * 
   * Provides audit trail for tracking which user last modified this entity.
   * Updated automatically on every entity modification.
   * 
   * - null: Never updated or user no longer exists
   * - string: UUID of the user who last updated this entity
   * 
   * @type {string | null}
   * @optional
   * @default null
   * @example "b2c3d4e5-f6a7-8901-bcde-f12345678901"
   */
  updatedBy?: string | null;
}

/**
 * BaseEntityWithoutAccount Interface
 * 
 * Extended interface for global entities that are not tied to specific accounts.
 * These entities are shared across the entire application or represent system-level data.
 * 
 * Use Cases:
 * - System configuration settings (not tenant-specific)
 * - Global templates available to all accounts
 * - System-wide logs or metrics
 * - Feature flags or application settings
 * 
 * Example Usage:
 * ```typescript
 * @Entity('system_settings')
 * export class SystemSetting implements BaseEntityWithoutAccount {
 *   @PrimaryGeneratedColumn('uuid')
 *   id: string;
 * 
 *   @CreateDateColumn({ type: 'timestamptz' })
 *   createdAt: Date;
 * 
 *   @UpdateDateColumn({ type: 'timestamptz' })
 *   updatedAt: Date;
 * 
 *   @DeleteDateColumn({ type: 'timestamptz', nullable: true })
 *   deletedAt: Date | null;
 * 
 *   @VersionColumn()
 *   version: number;
 * 
 *   @Column('uuid', { nullable: true })
 *   createdBy: string | null;
 * 
 *   @Column('uuid', { nullable: true })
 *   updatedBy: string | null;
 * 
 *   @Column()
 *   key: string;
 * 
 *   @Column('jsonb')
 *   value: any;
 * }
 * ```
 * 
 * @interface BaseEntityWithoutAccount
 * @extends {Omit<BaseEntity, 'accountId'>}
 */
export interface BaseEntityWithoutAccount extends Omit<BaseEntity, 'accountId'> {
  /**
   * Unique identifier for the entity.
   * Inherited from BaseEntity.
   * 
   * @type {string}
   */
  id: string;

  /**
   * Timestamp when the entity was created.
   * Inherited from BaseEntity.
   * 
   * @type {Date}
   */
  createdAt: Date;

  /**
   * Timestamp when the entity was last updated.
   * Inherited from BaseEntity.
   * 
   * @type {Date}
   */
  updatedAt: Date;

  /**
   * Timestamp when the entity was soft deleted.
   * Inherited from BaseEntity.
   * 
   * @type {Date | null}
   * @optional
   */
  deletedAt?: Date | null;

  /**
   * Version number for optimistic locking.
   * Inherited from BaseEntity.
   * 
   * @type {number}
   */
  version: number;

  /**
   * User ID who created the entity.
   * Inherited from BaseEntity.
   * 
   * @type {string | null}
   * @optional
   */
  createdBy?: string | null;

  /**
   * User ID who last updated the entity.
   * Inherited from BaseEntity.
   * 
   * @type {string | null}
   * @optional
   */
  updatedBy?: string | null;
}

/**
 * TimestampEntity Interface
 * 
 * Minimal interface for entities that only need basic timestamp tracking.
 * Use this for simple entities that don't require soft delete, versioning,
 * or multi-tenant isolation.
 * 
 * Use Cases:
 * - Log entries that are never updated or deleted
 * - Read-only reference data
 * - Simple lookup tables
 * - Event tracking tables
 * 
 * Example Usage:
 * ```typescript
 * @Entity('api_request_logs')
 * export class ApiRequestLog implements TimestampEntity {
 *   @PrimaryGeneratedColumn('uuid')
 *   id: string;
 * 
 *   @CreateDateColumn({ type: 'timestamptz' })
 *   createdAt: Date;
 * 
 *   @UpdateDateColumn({ type: 'timestamptz' })
 *   updatedAt: Date;
 * 
 *   @Column()
 *   endpoint: string;
 * 
 *   @Column()
 *   method: string;
 * 
 *   @Column()
 *   statusCode: number;
 * 
 *   @Column()
 *   responseTime: number;
 * }
 * ```
 * 
 * @interface TimestampEntity
 */
export interface TimestampEntity {
  /**
   * Unique identifier for the entity.
   * 
   * @type {string}
   * @example "550e8400-e29b-41d4-a716-446655440000"
   */
  id: string;

  /**
   * Timestamp when the entity was created.
   * 
   * MUST be stored in UTC timezone as per Section 0.7.1 requirements.
   * 
   * @type {Date}
   * @readonly
   * @example new Date('2025-10-31T12:00:00.000Z')
   */
  createdAt: Date;

  /**
   * Timestamp when the entity was last updated.
   * 
   * MUST be stored in UTC timezone as per Section 0.7.1 requirements.
   * 
   * @type {Date}
   * @example new Date('2025-11-01T15:30:00.000Z')
   */
  updatedAt: Date;
}

/**
 * SoftDeletable Interface
 * 
 * Interface for entities that support soft delete functionality.
 * Soft deletes mark records as deleted without physically removing them from the database,
 * supporting data retention policies, audit requirements, and the ability to restore
 * accidentally deleted data.
 * 
 * Benefits of Soft Delete:
 * - Compliance with data retention regulations (GDPR, SOX, HIPAA)
 * - Ability to restore accidentally deleted data
 * - Maintain referential integrity (foreign key relationships)
 * - Audit trail preservation
 * - Historical reporting and analytics
 * 
 * TypeORM Configuration:
 * - Use @DeleteDateColumn() decorator for deletedAt field
 * - TypeORM automatically filters out soft-deleted records in queries
 * - Use .restore() method to undelete records
 * - Use withDeleted() in QueryBuilder to include soft-deleted records
 * 
 * Example Usage:
 * ```typescript
 * @Entity('documents')
 * export class Document implements SoftDeletable {
 *   @DeleteDateColumn({ type: 'timestamptz', nullable: true })
 *   deletedAt: Date | null;
 * 
 *   get isDeleted(): boolean {
 *     return this.deletedAt !== null && this.deletedAt !== undefined;
 *   }
 * 
 *   // Other entity fields...
 * }
 * 
 * // Usage in service:
 * // Soft delete (sets deletedAt to current timestamp)
 * await documentRepository.softRemove(document);
 * 
 * // Restore soft-deleted entity (sets deletedAt back to null)
 * await documentRepository.recover(document);
 * 
 * // Query including soft-deleted records
 * const allDocuments = await documentRepository
 *   .createQueryBuilder('document')
 *   .withDeleted()
 *   .getMany();
 * 
 * // Check if entity is deleted
 * if (document.isDeleted) {
 *   console.log('This document was deleted at:', document.deletedAt);
 * }
 * ```
 * 
 * @interface SoftDeletable
 */
export interface SoftDeletable {
  /**
   * Timestamp when the entity was soft deleted.
   * 
   * Soft Delete vs Hard Delete:
   * - Soft Delete: Sets deletedAt to current timestamp, record remains in database
   * - Hard Delete: Permanently removes record from database (not recommended for most cases)
   * 
   * When to use Soft Delete:
   * - User data (for GDPR compliance and data portability)
   * - Business documents (for audit trails and compliance)
   * - Transactional data (for historical reporting)
   * - Any data that might need to be restored
   * 
   * When to use Hard Delete:
   * - Temporary data (sessions, cache entries)
   * - Test data
   * - Data that must be permanently removed for legal reasons (right to be forgotten)
   * 
   * @type {Date | null}
   * @default null
   * @example null (active) or new Date('2025-12-01T10:00:00.000Z') (deleted)
   */
  deletedAt: Date | null;

  /**
   * Computed property indicating whether the entity is soft deleted.
   * 
   * This is a helper property that should be implemented as a getter
   * in entity classes. It provides a convenient boolean check for deletion status.
   * 
   * Implementation Example:
   * ```typescript
   * get isDeleted(): boolean {
   *   return this.deletedAt !== null && this.deletedAt !== undefined;
   * }
   * ```
   * 
   * Usage:
   * ```typescript
   * if (document.isDeleted) {
   *   throw new Error('Cannot modify a deleted document');
   * }
   * ```
   * 
   * @type {boolean}
   * @readonly
   * @returns {boolean} true if deletedAt is not null, false otherwise
   */
  isDeleted: boolean;
}

/**
 * Type guard to check if an entity implements BaseEntity interface.
 * 
 * @param {any} entity - The entity to check
 * @returns {boolean} true if entity implements BaseEntity
 * 
 * @example
 * if (isBaseEntity(someEntity)) {
 *   console.log('Account ID:', someEntity.accountId);
 *   console.log('Created at:', someEntity.createdAt);
 * }
 */
export function isBaseEntity(entity: any): entity is BaseEntity {
  return (
    entity !== null &&
    typeof entity === 'object' &&
    typeof entity.id === 'string' &&
    typeof entity.accountId === 'string' &&
    entity.createdAt instanceof Date &&
    entity.updatedAt instanceof Date &&
    typeof entity.version === 'number'
  );
}

/**
 * Type guard to check if an entity implements SoftDeletable interface.
 * 
 * @param {any} entity - The entity to check
 * @returns {boolean} true if entity implements SoftDeletable
 * 
 * @example
 * if (isSoftDeletable(someEntity) && someEntity.isDeleted) {
 *   console.log('This entity was deleted at:', someEntity.deletedAt);
 * }
 */
export function isSoftDeletable(entity: any): entity is SoftDeletable {
  return (
    entity !== null &&
    typeof entity === 'object' &&
    'deletedAt' in entity &&
    'isDeleted' in entity
  );
}

/**
 * Type guard to check if an entity implements TimestampEntity interface.
 * 
 * @param {any} entity - The entity to check
 * @returns {boolean} true if entity implements TimestampEntity
 * 
 * @example
 * if (isTimestampEntity(someEntity)) {
 *   console.log('Created at:', someEntity.createdAt);
 *   console.log('Updated at:', someEntity.updatedAt);
 * }
 */
export function isTimestampEntity(entity: any): entity is TimestampEntity {
  return (
    entity !== null &&
    typeof entity === 'object' &&
    typeof entity.id === 'string' &&
    entity.createdAt instanceof Date &&
    entity.updatedAt instanceof Date
  );
}
