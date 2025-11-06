/**
 * Notification Interfaces and Type Definitions
 * 
 * This module defines comprehensive notification data structures for the OCR Processing Application.
 * Supports multi-channel notifications (email, WebSocket, webhook, SMS, push) with type safety
 * and consistency across all notification services.
 * 
 * Key Features:
 * - Multiple notification types for different events (document processing, batch jobs, account activity)
 * - Multi-channel delivery support (email, WebSocket, webhook, SMS, push)
 * - Priority-based notification handling (low, normal, high, critical)
 * - Multi-tenant isolation via accountId
 * - Delivery tracking and read status management
 * - Extensible payload structures for different notification types
 * 
 * @module notifications/interfaces/notification.interface
 */

/**
 * Notification Type Enum
 * 
 * Defines all possible notification types in the system.
 * Each type corresponds to a specific event that triggers a notification.
 * 
 * Usage:
 * - DOCUMENT_* types: Document lifecycle events
 * - BATCH_* types: Batch processing events
 * - JOB_PROGRESS: Real-time job progress updates via WebSocket
 * - PASSWORD_RESET: Password reset flow notifications
 * - USER_INVITATION: New user invitation emails
 * - ACCOUNT_ACTIVITY: Security and account activity alerts
 * - WEEKLY_DIGEST: Periodic summary reports
 * - TEMPLATE_CREATED: Template management events
 * 
 * @enum {string}
 */
export enum NotificationType {
  /** Document has been successfully uploaded and queued for processing */
  DOCUMENT_UPLOADED = 'document.uploaded',
  
  /** Document processing has started */
  DOCUMENT_PROCESSING = 'document.processing',
  
  /** Document processing completed successfully */
  DOCUMENT_PROCESSED = 'document.processed',
  
  /** User approved extracted document data */
  DOCUMENT_APPROVED = 'document.approved',
  
  /** Document processing failed due to error */
  DOCUMENT_FAILED = 'document.failed',
  
  /** Batch processing job completed successfully */
  BATCH_COMPLETED = 'batch.completed',
  
  /** Batch processing job failed */
  BATCH_FAILED = 'batch.failed',
  
  /** Real-time progress update for processing job (WebSocket) */
  JOB_PROGRESS = 'job.progress',
  
  /** Password reset request notification */
  PASSWORD_RESET = 'password.reset',
  
  /** User invitation to join account */
  USER_INVITATION = 'user.invitation',
  
  /** Account activity alert (login, settings change, etc.) */
  ACCOUNT_ACTIVITY = 'account.activity',
  
  /** Weekly digest report with usage statistics */
  WEEKLY_DIGEST = 'weekly.digest',
  
  /** New extraction template created */
  TEMPLATE_CREATED = 'template.created',
}

/**
 * Notification Channel Enum
 * 
 * Defines all available notification delivery channels.
 * Multiple channels can be used simultaneously for a single notification.
 * 
 * Implementation Status:
 * - EMAIL: Implemented via SendGrid/AWS SES
 * - WEBSOCKET: Implemented via Socket.io for real-time updates
 * - WEBHOOK: Implemented via webhook manager with retry logic
 * - SMS: Future enhancement (marked for Phase 2)
 * - PUSH: Future enhancement (mobile app push notifications)
 * 
 * @enum {string}
 */
export enum NotificationChannel {
  /** Email delivery via SendGrid or AWS SES */
  EMAIL = 'email',
  
  /** Real-time delivery via WebSocket (Socket.io) */
  WEBSOCKET = 'websocket',
  
  /** HTTP POST to subscriber webhook URLs */
  WEBHOOK = 'webhook',
  
  /** SMS delivery (future enhancement) */
  SMS = 'sms',
  
  /** Push notification to mobile apps (future enhancement) */
  PUSH = 'push',
}

/**
 * Notification Priority Enum
 * 
 * Defines priority levels for notification delivery and processing.
 * Higher priority notifications are processed first and may bypass rate limits.
 * 
 * Priority Guidelines:
 * - LOW: Non-urgent informational notifications (weekly digests)
 * - NORMAL: Standard notifications (document processed)
 * - HIGH: Important notifications requiring attention (processing failed)
 * - CRITICAL: Security alerts and system failures (unauthorized access)
 * 
 * @enum {string}
 */
export enum NotificationPriority {
  /** Low priority - non-urgent informational messages */
  LOW = 'low',
  
  /** Normal priority - standard notifications */
  NORMAL = 'normal',
  
  /** High priority - important notifications requiring user attention */
  HIGH = 'high',
  
  /** Critical priority - security alerts and system failures */
  CRITICAL = 'critical',
}

/**
 * Base Notification Interface
 * 
 * Defines the common structure for all notification types.
 * All specific notification interfaces extend this base interface.
 * 
 * Multi-Tenant Isolation:
 * - accountId: Ensures notifications are isolated per account
 * - userId: Identifies the recipient user within the account
 * 
 * Delivery Tracking:
 * - delivered: Boolean flag indicating if notification was sent
 * - deliveredAt: Timestamp when notification was delivered
 * - read: Boolean flag indicating if user has read the notification
 * - readAt: Timestamp when notification was read
 * 
 * Expiration:
 * - expiresAt: Optional expiration time (e.g., password reset tokens)
 * 
 * @interface INotification
 */
export interface INotification {
  /** Unique notification identifier (UUID) */
  id: string;
  
  /** Type of notification (determines payload structure) */
  type: NotificationType;
  
  /** Delivery channels for this notification (can be multiple) */
  channels: NotificationChannel[];
  
  /** Priority level for delivery processing */
  priority: NotificationPriority;
  
  /** Account ID for multi-tenant isolation */
  accountId: string;
  
  /** Recipient user ID */
  userId: string;
  
  /** Notification subject line or title */
  subject: string;
  
  /** Notification body message (plain text or HTML) */
  message: string;
  
  /** Structured notification payload (type-specific data) */
  data: Record<string, any>;
  
  /** Timestamp when notification was created (UTC) */
  timestamp: Date;
  
  /** Delivery status flag */
  delivered: boolean;
  
  /** Timestamp when notification was delivered (UTC) */
  deliveredAt?: Date;
  
  /** Read status flag (for in-app notifications) */
  read: boolean;
  
  /** Timestamp when notification was read (UTC) */
  readAt?: Date;
  
  /** Optional expiration time (UTC) for time-sensitive notifications */
  expiresAt?: Date;
  
  /** Additional metadata for notification processing */
  metadata?: Record<string, any>;
}

/**
 * Document Processed Notification Interface
 * 
 * Sent when a document completes OCR/NLP processing.
 * Includes processing results, confidence scores, and extracted field counts.
 * 
 * Use Cases:
 * - Notify user when document is ready for review
 * - Trigger webhook to downstream systems
 * - Send email summary of processing results
 * 
 * Status Values:
 * - completed: All fields extracted successfully with high confidence
 * - partial: Some fields extracted, manual review recommended
 * - failed: Processing failed, no usable data extracted
 * 
 * @interface IDocumentProcessedNotification
 * @extends {INotification}
 */
export interface IDocumentProcessedNotification extends INotification {
  /** Document unique identifier */
  documentId: string;
  
  /** Original document filename */
  documentName: string;
  
  /** Number of pages in document */
  pageCount: number;
  
  /** Processing time in milliseconds */
  processingTime: number;
  
  /** Overall confidence score (0-100) */
  confidenceScore: number;
  
  /** Total number of fields extracted */
  extractedFields: number;
  
  /** Number of fields with confidence score <85% */
  lowConfidenceFields: number;
  
  /** Classified document type (invoice, receipt, contract, etc.) */
  documentType: string;
  
  /** Processing status */
  status: 'completed' | 'partial' | 'failed';
}

/**
 * Job Progress Notification Interface
 * 
 * Real-time progress updates for document processing jobs.
 * Primarily used for WebSocket notifications to update UI in real-time.
 * 
 * Use Cases:
 * - Display progress bar during processing
 * - Show current document being processed
 * - Estimate time remaining
 * - Update batch processing status
 * 
 * @interface IJobProgressNotification
 * @extends {INotification}
 */
export interface IJobProgressNotification extends INotification {
  /** Processing job unique identifier */
  jobId: string;
  
  /** Job type (single document or batch) */
  jobType: 'single' | 'batch';
  
  /** Total number of documents in job */
  totalDocuments: number;
  
  /** Number of documents processed so far */
  processedDocuments: number;
  
  /** Number of documents that failed processing */
  failedDocuments: number;
  
  /** Name of document currently being processed */
  currentDocument: string;
  
  /** Completion percentage (0-100) */
  percentComplete: number;
  
  /** Estimated time remaining in milliseconds */
  estimatedTimeRemaining: number;
  
  /** Timestamp when job started (UTC) */
  startedAt: Date;
}

/**
 * Batch Completed Notification Interface
 * 
 * Sent when a batch processing job completes (success or failure).
 * Includes comprehensive statistics and detailed failure information.
 * 
 * Use Cases:
 * - Notify user of batch completion via email
 * - Provide summary statistics for reporting
 * - List failed documents for manual review
 * - Trigger downstream batch export
 * 
 * @interface IBatchCompletedNotification
 * @extends {INotification}
 */
export interface IBatchCompletedNotification extends INotification {
  /** Batch job unique identifier */
  batchId: string;
  
  /** Total number of documents in batch */
  totalDocuments: number;
  
  /** Number of successfully processed documents */
  successCount: number;
  
  /** Number of failed documents */
  failureCount: number;
  
  /** Average processing time per document in milliseconds */
  averageProcessingTime: number;
  
  /** Total processing time for entire batch in milliseconds */
  totalProcessingTime: number;
  
  /** Timestamp when batch started (UTC) */
  startedAt: Date;
  
  /** Timestamp when batch completed (UTC) */
  completedAt: Date;
  
  /** Array of failed documents with error details */
  failedDocuments: Array<{
    /** Document ID */
    id: string;
    /** Document filename */
    name: string;
    /** Error message */
    error: string;
  }>;
}

/**
 * Account Activity Notification Interface
 * 
 * Security and activity alerts for account events.
 * Used for audit trail and security monitoring.
 * 
 * Activity Types:
 * - login: User logged in
 * - logout: User logged out
 * - password_change: Password was changed
 * - settings_update: Account settings modified
 * - api_key_created: New API key generated
 * - integration_connected: Third-party integration connected
 * 
 * Use Cases:
 * - Security alerts for suspicious activity
 * - Audit logging for compliance
 * - User activity tracking
 * 
 * @interface IAccountActivityNotification
 * @extends {INotification}
 */
export interface IAccountActivityNotification extends INotification {
  /** Type of activity that occurred */
  activityType: 'login' | 'logout' | 'password_change' | 'settings_update' | 'api_key_created' | 'integration_connected';
  
  /** IP address where activity originated */
  ipAddress: string;
  
  /** User agent string from browser/client */
  userAgent: string;
  
  /** Geographic location (optional, from IP lookup) */
  location?: string;
  
  /** Timestamp when activity occurred (UTC) */
  timestamp: Date;
}

/**
 * Password Reset Notification Interface
 * 
 * Sent when user requests a password reset.
 * Contains secure reset token and URL with expiration time.
 * 
 * Security Considerations:
 * - resetToken should be hashed before storage
 * - resetUrl includes token as query parameter
 * - expiresIn defines token validity period (typically 60 minutes)
 * - Include IP address for security audit
 * 
 * Use Cases:
 * - Send password reset email with secure link
 * - Log password reset requests for security monitoring
 * - Track reset token expiration
 * 
 * @interface IPasswordResetNotification
 * @extends {INotification}
 */
export interface IPasswordResetNotification extends INotification {
  /** Secure reset token (should be hashed in database) */
  resetToken: string;
  
  /** Full password reset URL with embedded token */
  resetUrl: string;
  
  /** Token expiration time in minutes */
  expiresIn: number;
  
  /** Timestamp when reset was requested (UTC) */
  requestedAt: Date;
  
  /** IP address where reset was requested (for security audit) */
  ipAddress: string;
}

/**
 * User Invitation Notification Interface
 * 
 * Sent when an administrator invites a new user to join the account.
 * Contains invitation token and account details.
 * 
 * Invitation Flow:
 * 1. Admin creates invitation
 * 2. System generates invitation token
 * 3. Email sent to invitee with invitation URL
 * 4. Invitee clicks URL, creates account
 * 5. Token consumed, user joins account
 * 
 * Use Cases:
 * - Onboard new team members
 * - Provide context about account and inviter
 * - Track invitation status and expiration
 * 
 * @interface IUserInvitationNotification
 * @extends {INotification}
 */
export interface IUserInvitationNotification extends INotification {
  /** Unique invitation token */
  invitationToken: string;
  
  /** Full invitation URL with embedded token */
  invitationUrl: string;
  
  /** Name of user who sent the invitation */
  inviterName: string;
  
  /** Name of account user is being invited to */
  accountName: string;
  
  /** Role that will be assigned to user upon acceptance */
  role: string;
  
  /** Invitation expiration time in hours (typically 72 hours) */
  expiresIn: number;
}

/**
 * Weekly Digest Notification Interface
 * 
 * Periodic summary report sent to users weekly.
 * Includes usage statistics, trends, and key metrics.
 * 
 * Report Contents:
 * - Document processing volume
 * - Average confidence scores
 * - Top document types processed
 * - Accuracy trends (compared to previous week)
 * - Storage usage
 * - API usage statistics
 * 
 * Use Cases:
 * - Weekly summary email to users
 * - Usage reporting and analytics
 * - Identify trends and patterns
 * - Storage and cost tracking
 * 
 * @interface IWeeklyDigestNotification
 * @extends {INotification}
 */
export interface IWeeklyDigestNotification extends INotification {
  /** Start date of reporting week (UTC) */
  weekStartDate: Date;
  
  /** End date of reporting week (UTC) */
  weekEndDate: Date;
  
  /** Total number of documents processed during week */
  totalDocumentsProcessed: number;
  
  /** Average confidence score across all documents (0-100) */
  averageConfidenceScore: number;
  
  /** Top document types with counts */
  topDocumentTypes: Array<{
    /** Document type name */
    type: string;
    /** Number of documents of this type */
    count: number;
  }>;
  
  /** Accuracy trend compared to previous week (percentage change) */
  accuracyTrend: number;
  
  /** Total storage used in bytes */
  storageUsed: number;
  
  /** Total API requests made during week */
  apiUsage: number;
}

/**
 * Notification Delivery Result Interface
 * 
 * Records the delivery status of a notification through a specific channel.
 * Used for tracking delivery success/failure and debugging delivery issues.
 * 
 * Delivery Tracking:
 * - Each channel delivery is tracked separately
 * - Success flag indicates delivery status
 * - Error message captured for failed deliveries
 * - Provider message ID stored for reference (e.g., SendGrid message ID)
 * 
 * Use Cases:
 * - Audit notification delivery
 * - Retry failed deliveries
 * - Debug delivery issues
 * - Track provider-specific delivery IDs
 * 
 * @interface NotificationDeliveryResult
 */
export interface NotificationDeliveryResult {
  /** Notification ID that was delivered */
  notificationId: string;
  
  /** Delivery channel used */
  channel: NotificationChannel;
  
  /** Delivery success flag */
  success: boolean;
  
  /** Timestamp when delivery was attempted (UTC) */
  deliveredAt: Date;
  
  /** Error message if delivery failed */
  error?: string;
  
  /** Provider-specific message ID (e.g., SendGrid message ID, webhook delivery ID) */
  messageId?: string;
  
  /** Additional metadata from delivery provider */
  metadata?: Record<string, any>;
}

/**
 * Notification Payload Type Union
 * 
 * Union type combining all specific notification interfaces.
 * Enables type-safe handling of different notification types.
 * 
 * Usage Example:
 * ```typescript
 * function handleNotification(notification: NotificationPayload) {
 *   switch (notification.type) {
 *     case NotificationType.DOCUMENT_PROCESSED:
 *       const doc = notification as IDocumentProcessedNotification;
 *       console.log(`Document ${doc.documentName} processed with ${doc.confidenceScore}% confidence`);
 *       break;
 *     case NotificationType.BATCH_COMPLETED:
 *       const batch = notification as IBatchCompletedNotification;
 *       console.log(`Batch ${batch.batchId}: ${batch.successCount}/${batch.totalDocuments} succeeded`);
 *       break;
 *     // ... handle other types
 *   }
 * }
 * ```
 * 
 * Type Safety:
 * - Compiler ensures all notification types are handled
 * - Enables discriminated union pattern with type field
 * - Provides autocomplete for type-specific fields
 * 
 * @type {NotificationPayload}
 */
export type NotificationPayload =
  | INotification
  | IDocumentProcessedNotification
  | IJobProgressNotification
  | IBatchCompletedNotification
  | IAccountActivityNotification
  | IPasswordResetNotification
  | IUserInvitationNotification
  | IWeeklyDigestNotification;
