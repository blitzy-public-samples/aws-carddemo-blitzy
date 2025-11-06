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
import { User } from '../../users/entities/user.entity';
import { Account } from '../../accounts/entities/account.entity';

/**
 * Notification type enum for different notification categories
 */
export enum NotificationType {
  PROCESSING_COMPLETE = 'processing_complete',
  PROCESSING_FAILED = 'processing_failed',
  BATCH_COMPLETE = 'batch_complete',
  WEEKLY_SUMMARY = 'weekly_summary',
  PASSWORD_RESET = 'password_reset',
  USER_INVITATION = 'user_invitation',
  ACCOUNT_ACTIVITY = 'account_activity',
}

/**
 * Notification delivery channel enum
 */
export enum NotificationChannel {
  EMAIL = 'email',
  PUSH = 'push',
  WEBHOOK = 'webhook',
}

/**
 * Notification delivery status enum
 */
export enum NotificationStatus {
  PENDING = 'pending',
  SENT = 'sent',
  DELIVERED = 'delivered',
  FAILED = 'failed',
  RETRYING = 'retrying',
}

/**
 * Notification entity for notification history and delivery tracking.
 * 
 * Stores comprehensive records of all notifications sent through the system,
 * including delivery status, retry attempts, and error tracking for audit trail.
 * 
 * Features:
 * - Multi-tenant isolation via account_id foreign key
 * - Support for multiple notification types (processing, batch, summaries, auth)
 * - Multi-channel delivery (email, push, webhook) per Section 0.4.7 and 0.4.8
 * - Complete delivery lifecycle tracking (pending → sent → delivered/failed)
 * - Automatic retry with exponential backoff (3 attempts, 5s, 25s, 125s)
 * - Detailed error tracking for failed deliveries
 * - Metadata storage for notification context (document_id, job_id, etc.)
 * - Template identifier for email service integration
 * - Webhook signature and delivery confirmation
 * - Indexes for efficient status queries and retry processing
 * 
 * Usage:
 * - Created by NotificationService when sending notifications
 * - Status updated by webhook delivery workers
 * - Queried for user notification history in UI
 * - Used for delivery analytics and audit compliance
 * - Retry worker processes pending/retrying notifications
 * 
 * Retry Logic (per Section 0.4.7):
 * - 3 retry attempts with exponential backoff
 * - Retry intervals: 5s, 25s, 125s
 * - Status transitions: pending → sent → (delivered | retrying → failed)
 * 
 * Security:
 * - Multi-tenant isolation enforced by account_id
 * - Webhook URLs validated before delivery
 * - Error messages sanitized to avoid sensitive data leakage
 * - Cascade delete when user is removed
 * 
 * Implements notification history per folder requirements, webhook delivery
 * per Section 0.4.7, and email notifications per Section 0.4.8.
 */
@Entity('notifications')
@Index(['account_id', 'status'])
@Index(['user_id', 'created_at'])
@Index(['status', 'next_retry_at'])
@Index(['type', 'created_at'])
export class Notification {
  /**
   * Unique identifier for the notification
   */
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  /**
   * Foreign key to user who receives the notification
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
   * Type of notification
   */
  @Column({
    type: 'enum',
    enum: NotificationType,
    name: 'type',
  })
  type!: NotificationType;

  /**
   * Notification title for display
   */
  @Column({ type: 'varchar', length: 255, name: 'title' })
  title!: string;

  /**
   * Notification message content
   */
  @Column({ type: 'text', name: 'message' })
  message!: string;

  /**
   * Delivery channel (email, push, webhook)
   */
  @Column({
    type: 'enum',
    enum: NotificationChannel,
    name: 'channel',
  })
  channel!: NotificationChannel;

  /**
   * Email address for email notifications
   */
  @Column({ type: 'varchar', length: 500, nullable: true, name: 'recipient_email' })
  recipientEmail!: string | null;

  /**
   * Webhook URL for webhook notifications
   */
  @Column({ type: 'varchar', length: 500, nullable: true, name: 'webhook_url' })
  webhookUrl!: string | null;

  /**
   * Current delivery status
   */
  @Column({
    type: 'enum',
    enum: NotificationStatus,
    default: NotificationStatus.PENDING,
    name: 'status',
  })
  status!: NotificationStatus;

  /**
   * When notification was sent
   */
  @Column({ type: 'timestamp', nullable: true, name: 'sent_at' })
  sentAt!: Date | null;

  /**
   * When delivery was confirmed
   */
  @Column({ type: 'timestamp', nullable: true, name: 'delivered_at' })
  deliveredAt!: Date | null;

  /**
   * When delivery failed
   */
  @Column({ type: 'timestamp', nullable: true, name: 'failed_at' })
  failedAt!: Date | null;

  /**
   * Number of retry attempts
   */
  @Column({ type: 'int', default: 0, name: 'retry_count' })
  retryCount!: number;

  /**
   * Maximum retry attempts (default 3 per Section 0.4.7)
   */
  @Column({ type: 'int', default: 3, name: 'max_retries' })
  maxRetries!: number;

  /**
   * Scheduled time for next retry
   */
  @Column({ type: 'timestamp', nullable: true, name: 'next_retry_at' })
  nextRetryAt!: Date | null;

  /**
   * Error message if delivery failed
   */
  @Column({ type: 'text', nullable: true, name: 'error_message' })
  errorMessage!: string | null;

  /**
   * Detailed error information as JSON
   */
  @Column({ type: 'simple-json', nullable: true, name: 'error_details' })
  errorDetails!: Record<string, any> | null;

  /**
   * Additional notification context (document_id, job_id, etc.)
   */
  @Column({ type: 'simple-json', nullable: true, name: 'metadata' })
  metadata!: Record<string, any> | null;

  /**
   * Email template identifier for SendGrid/SES
   */
  @Column({ type: 'varchar', length: 100, nullable: true, name: 'template_id' })
  templateId!: string | null;

  /**
   * When notification was created
   */
  @CreateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'created_at',
  })
  createdAt!: Date;

  /**
   * Last status update time
   */
  @UpdateDateColumn({
    type: 'timestamp',
    default: () => 'CURRENT_TIMESTAMP',
    name: 'updated_at',
  })
  updatedAt!: Date;
}
