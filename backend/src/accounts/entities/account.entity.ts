import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  Index,
} from 'typeorm';

/**
 * Account plan type enum matching database enum
 */
export enum AccountPlanType {
  FREE = 'free',
  BASIC = 'basic',
  PROFESSIONAL = 'professional',
  ENTERPRISE = 'enterprise',
}

/**
 * Account billing status enum matching database enum
 */
export enum AccountBillingStatus {
  ACTIVE = 'active',
  SUSPENDED = 'suspended',
  CANCELLED = 'cancelled',
}

/**
 * Account entity for multi-tenant organization management.
 * 
 * Represents a tenant organization in the multi-tenant architecture.
 * All other entities (users, documents, notifications, etc.) are associated
 * with an account for workspace isolation.
 * 
 * Features:
 * - UUID-based tenant identification
 * - Subscription plan management with usage limits
 * - Feature flags for flexible capability enablement
 * - Billing lifecycle management
 * - Unique subdomain for URL-based tenant isolation
 * 
 * Security:
 * - All entities must reference account_id for tenant isolation per Section 0.7.1
 * - Cascade deletion removes all associated data
 * 
 * Implements multi-tenant architecture per Section 0.7.1 and migration 001.
 */
@Entity('accounts')
@Index(['subdomain'], { unique: true })
@Index(['billing_status'])
export class Account {
  /**
   * Unique identifier for the account (tenant)
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Legal name or display name of the organization
   */
  @Column({ type: 'varchar', length: 255, name: 'organization_name' })
  organizationName!: string;

  /**
   * Unique subdomain for tenant-specific URLs (e.g., acme.ocr-app.com)
   */
  @Column({ type: 'varchar', length: 63, unique: true })
  subdomain!: string;

  /**
   * Subscription plan tier determining feature access and limits
   */
  @Column({
    type: 'enum',
    enum: AccountPlanType,
    default: AccountPlanType.FREE,
    name: 'plan_type',
  })
  planType!: AccountPlanType;

  /**
   * Maximum number of users allowed for this account
   */
  @Column({ type: 'integer', default: 5, name: 'max_users' })
  maxUsers!: number;

  /**
   * Maximum number of documents that can be processed per month
   */
  @Column({ type: 'integer', default: 100, name: 'max_documents_per_month' })
  maxDocumentsPerMonth!: number;

  /**
   * Maximum storage in GB allowed for this account
   */
  @Column({ type: 'integer', default: 10, name: 'max_storage_gb' })
  maxStorageGb!: number;

  /**
   * API rate limit in requests per hour
   */
  @Column({ type: 'integer', default: 1000, name: 'api_rate_limit' })
  apiRateLimit!: number;

  /**
   * Feature flags stored as JSON for flexible capability enablement
   */
  @Column({ type: 'simple-json', nullable: true, name: 'feature_flags' })
  featureFlags!: Record<string, boolean> | null;

  /**
   * Current billing status of the account
   */
  @Column({
    type: 'enum',
    enum: AccountBillingStatus,
    default: AccountBillingStatus.ACTIVE,
    name: 'billing_status',
  })
  billingStatus!: AccountBillingStatus;

  /**
   * When the account was created
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * When the account was last updated
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;
}
