import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
  Unique,
  Index,
} from 'typeorm';
import { User } from '../../users/entities/user.entity';
import { Account } from '../../accounts/entities/account.entity';

/**
 * Notification frequency enum
 */
export enum NotificationFrequency {
  INSTANT = 'instant',
  DAILY = 'daily',
  WEEKLY = 'weekly',
  NEVER = 'never',
}

/**
 * Notification delivery channel enum (shared with Notification entity)
 */
export enum NotificationChannel {
  EMAIL = 'email',
  PUSH = 'push',
  WEBHOOK = 'webhook',
}

/**
 * NotificationPreference entity for user-specific notification settings.
 * 
 * Stores user preferences for receiving notifications through various channels
 * (email, push, webhook) with configurable frequency and notification types.
 * 
 * Features:
 * - Multi-tenant isolation via account_id foreign key
 * - Email notification preferences with frequency control (instant, daily, weekly, never)
 * - Digest summary support for batched notifications
 * - Per-notification-type opt-in/opt-out (processing complete, failed, batch, weekly)
 * - Webhook integration with custom URL support
 * - Future-ready for push notifications
 * - JSON storage for extensible channel configurations
 * - Unique constraint ensures one preference record per user per account
 * 
 * Usage:
 * - Created with defaults when user signs up
 * - Updated via SettingsController.updateNotificationPreferences()
 * - Queried by NotificationService before sending notifications
 * - Used to determine delivery channels and frequency
 * 
 * Security:
 * - Multi-tenant isolation enforced by unique(user_id, account_id)
 * - Webhook URLs validated before use
 * - Cascade delete when user is removed
 * 
 * Implements notification preferences per Section 0.4.8 and folder requirements.
 */
@Entity('notification_preferences')
@Unique(['user_id', 'account_id'])
@Index(['account_id'])
@Index(['user_id'])
export class NotificationPreference {
  /**
   * Unique identifier for the preference record
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Foreign key to user who owns these preferences
   */
  @Column({ type: 'uuid', name: 'user_id' })
  userId!: string;

  /**
   * User relationship
   */
  @ManyToOne(() => User, { onDelete: 'CASCADE', nullable: false })
  @JoinColumn({ name: 'user_id' })
  user!: User;

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
   * Master switch for email notifications
   */
  @Column({ type: 'boolean', default: true, name: 'email_enabled' })
  emailEnabled!: boolean;

  /**
   * How often to send email notifications
   */
  @Column({
    type: 'enum',
    enum: NotificationFrequency,
    default: NotificationFrequency.INSTANT,
    name: 'email_frequency',
  })
  emailFrequency!: NotificationFrequency;

  /**
   * Enable digest summaries per Section 0.4.8
   */
  @Column({ type: 'boolean', default: false, name: 'email_digest_enabled' })
  emailDigestEnabled!: boolean;

  /**
   * Alert when document processing completes
   */
  @Column({ type: 'boolean', default: true, name: 'notify_processing_complete' })
  notifyProcessingComplete!: boolean;

  /**
   * Alert when processing fails
   */
  @Column({ type: 'boolean', default: true, name: 'notify_processing_failed' })
  notifyProcessingFailed!: boolean;

  /**
   * Alert when batch job completes
   */
  @Column({ type: 'boolean', default: false, name: 'notify_batch_complete' })
  notifyBatchComplete!: boolean;

  /**
   * Enable weekly summary reports
   */
  @Column({ type: 'boolean', default: false, name: 'notify_weekly_summary' })
  notifyWeeklySummary!: boolean;

  /**
   * Enable push notifications (future feature)
   */
  @Column({ type: 'boolean', default: false, name: 'push_enabled' })
  pushEnabled!: boolean;

  /**
   * Enable webhook delivery per Section 0.4.7
   */
  @Column({ type: 'boolean', default: false, name: 'webhook_enabled' })
  webhookEnabled!: boolean;

  /**
   * Custom webhook URL for notifications
   */
  @Column({ type: 'varchar', length: 500, nullable: true, name: 'webhook_url' })
  webhookUrl!: string | null;

  /**
   * Additional channel-specific configurations as JSON
   */
  @Column({ type: 'simple-json', nullable: true, name: 'channel_config' })
  channelConfig!: Record<string, any> | null;

  /**
   * When preferences were created
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * When preferences were last updated
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;
}
