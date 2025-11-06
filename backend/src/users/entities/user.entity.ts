import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
  Index,
} from 'typeorm';
import { Account } from '../../accounts/entities/account.entity';

/**
 * OAuth provider type enum matching database enum
 */
export enum OAuthProvider {
  LOCAL = 'local',
  GOOGLE = 'google',
  MICROSOFT = 'microsoft',
}

/**
 * User entity for authentication and profile management.
 * 
 * Represents a user within a multi-tenant account with support for:
 * - Local authentication (email/password)
 * - OAuth 2.0 authentication (Google, Microsoft)
 * - Multi-factor authentication (MFA) via TOTP
 * - Email verification workflow
 * - Profile management
 * 
 * Features:
 * - Multi-tenant isolation via account_id foreign key
 * - Support for multiple authentication methods
 * - MFA with TOTP secret storage (encrypted at application layer)
 * - Email verification workflow
 * - Activity tracking (last login)
 * - Account activation/deactivation
 * - Unique email per account (not globally unique)
 * 
 * Security:
 * - Password hashes stored using bcrypt (nullable for OAuth-only users)
 * - MFA secrets encrypted at application layer before storage per Section 0.7.1
 * - Email unique per account for multi-tenant isolation
 * - Cascade deletion when account is removed
 * 
 * Implements authentication system per Section 0.5.3 Phase 2 and migration 002.
 */
@Entity('users')
@Index(['account_id', 'email'], { unique: true })
@Index(['account_id', 'is_active'])
@Index(['email'])
@Index(['oauth_provider', 'oauth_provider_id'])
export class User {
  /**
   * Unique identifier for the user
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Foreign key to account for multi-tenant isolation
   */
  @Column({ type: 'uuid', name: 'account_id' })
  accountId!: string;

  /**
   * Account relationship
   */
  @ManyToOne(() => Account, { onDelete: 'CASCADE', nullable: false })
  @JoinColumn({ name: 'account_id' })
  account!: Account;

  /**
   * User email address (unique per account)
   */
  @Column({ type: 'varchar', length: 255 })
  email!: string;

  /**
   * Bcrypt password hash (nullable for OAuth-only users)
   */
  @Column({ type: 'varchar', length: 255, nullable: true, name: 'password_hash' })
  passwordHash!: string | null;

  /**
   * User first name
   */
  @Column({ type: 'varchar', length: 100, name: 'first_name' })
  firstName!: string;

  /**
   * User last name
   */
  @Column({ type: 'varchar', length: 100, name: 'last_name' })
  lastName!: string;

  /**
   * URL to user profile avatar image
   */
  @Column({ type: 'varchar', length: 500, nullable: true, name: 'avatar_url' })
  avatarUrl!: string | null;

  /**
   * Authentication provider type
   */
  @Column({
    type: 'enum',
    enum: OAuthProvider,
    default: OAuthProvider.LOCAL,
    name: 'oauth_provider',
  })
  oauthProvider!: OAuthProvider;

  /**
   * External provider user ID for OAuth users
   */
  @Column({ type: 'varchar', length: 255, nullable: true, name: 'oauth_provider_id' })
  oauthProviderId!: string | null;

  /**
   * Whether user has verified their email address
   */
  @Column({ type: 'boolean', default: false, name: 'is_email_verified' })
  isEmailVerified!: boolean;

  /**
   * Token for email verification workflow
   */
  @Column({ type: 'varchar', length: 255, nullable: true, name: 'email_verification_token' })
  emailVerificationToken!: string | null;

  /**
   * Whether multi-factor authentication is enabled
   */
  @Column({ type: 'boolean', default: false, name: 'mfa_enabled' })
  mfaEnabled!: boolean;

  /**
   * TOTP secret for MFA (encrypted at application layer)
   */
  @Column({ type: 'varchar', length: 255, nullable: true, name: 'mfa_secret' })
  mfaSecret!: string | null;

  /**
   * Timestamp of most recent successful login
   */
  @Column({ type: 'timestamp', nullable: true, name: 'last_login_at' })
  lastLoginAt!: Date | null;

  /**
   * Whether user account is active
   */
  @Column({ type: 'boolean', default: true, name: 'is_active' })
  isActive!: boolean;

  /**
   * When the user account was created
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * When the user account was last updated
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;
}
