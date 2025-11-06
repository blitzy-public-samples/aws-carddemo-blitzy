/**
 * Email Provider Interface
 * 
 * Defines the contract for email service provider implementations (SendGrid, AWS SES).
 * This abstraction allows the EmailService to switch between providers without changing
 * calling code, supporting the notification requirements in Section 0.4.8.
 * 
 * @module notifications/interfaces
 */

/**
 * Email Attachment Interface
 * 
 * Represents a file attachment to be included in an email.
 * Supports both Buffer and string content types for flexibility.
 * 
 * @interface EmailAttachment
 * @example
 * ```typescript
 * const attachment: EmailAttachment = {
 *   filename: 'invoice.pdf',
 *   content: pdfBuffer,
 *   contentType: 'application/pdf',
 *   encoding: 'base64'
 * };
 * ```
 */
export interface EmailAttachment {
  /**
   * Name of the file as it should appear to the recipient
   * @example 'invoice_12345.pdf'
   */
  filename: string;

  /**
   * File content as Buffer or base64-encoded string
   */
  content: Buffer | string;

  /**
   * MIME type of the attachment
   * @example 'application/pdf', 'image/png', 'text/csv'
   */
  contentType: string;

  /**
   * Content encoding format
   * @default 'base64'
   */
  encoding?: string;
}

/**
 * Send Email Options Interface
 * 
 * Comprehensive options for sending emails through any provider.
 * Includes support for basic email, CC/BCC, attachments, templates,
 * and provider-specific features like tags and priority.
 * 
 * @interface SendEmailOptions
 * @example
 * ```typescript
 * const options: SendEmailOptions = {
 *   to: 'user@example.com',
 *   from: 'noreply@ocrapp.com',
 *   subject: 'Document Processing Complete',
 *   html: '<h1>Your document is ready</h1>',
 *   text: 'Your document is ready',
 *   priority: 'high',
 *   tags: ['processing', 'notification']
 * };
 * ```
 */
export interface SendEmailOptions {
  /**
   * Recipient email address(es)
   * Single email or array of emails for multiple recipients
   * @example 'user@example.com' or ['user1@example.com', 'user2@example.com']
   */
  to: string | string[];

  /**
   * Sender email address
   * Must be verified in the email provider (SendGrid/SES)
   * @example 'noreply@ocrapp.com'
   */
  from: string;

  /**
   * Email subject line
   * @example 'Your document has been processed'
   */
  subject: string;

  /**
   * HTML email body content
   * Full HTML template with styling
   * @example '<html><body><h1>Processing Complete</h1></body></html>'
   */
  html: string;

  /**
   * Plain text fallback content
   * Used when recipient's client doesn't support HTML
   * Optional but recommended for accessibility
   */
  text?: string;

  /**
   * File attachments to include with the email
   * Optional array of EmailAttachment objects
   */
  attachments?: EmailAttachment[];

  /**
   * Carbon copy recipient(s)
   * Visible to all recipients
   */
  cc?: string | string[];

  /**
   * Blind carbon copy recipient(s)
   * Hidden from other recipients
   */
  bcc?: string | string[];

  /**
   * Reply-to email address
   * Where replies should be directed
   * @example 'support@ocrapp.com'
   */
  replyTo?: string;

  /**
   * Custom email headers
   * Provider-specific or standard email headers
   * @example { 'X-Priority': '1', 'X-Custom-Header': 'value' }
   */
  headers?: Record<string, string>;

  /**
   * Template ID for provider-managed email templates
   * SendGrid: Dynamic Template ID
   * AWS SES: Template name
   * @example 'd-abc123def456' (SendGrid) or 'WelcomeEmail' (SES)
   */
  templateId?: string;

  /**
   * Template variables/substitution data
   * Key-value pairs to populate template placeholders
   * @example { userName: 'John Doe', documentCount: 5 }
   */
  templateData?: Record<string, any>;

  /**
   * Email priority level
   * Affects delivery order and visual indicators in some clients
   * @default 'normal'
   */
  priority?: 'high' | 'normal' | 'low';

  /**
   * Tags for tracking and analytics
   * Used for categorizing and filtering emails in provider dashboards
   * @example ['processing-notification', 'high-priority']
   */
  tags?: string[];
}

/**
 * Send Email Result Interface
 * 
 * Standardized result structure returned after email send attempt.
 * Provides success status, provider message ID, and error details if applicable.
 * 
 * @interface SendEmailResult
 * @example
 * ```typescript
 * // Success result
 * const result: SendEmailResult = {
 *   success: true,
 *   messageId: '<20251031120000.1.abc@email.com>',
 *   timestamp: new Date(),
 *   provider: 'sendgrid'
 * };
 * 
 * // Failure result
 * const result: SendEmailResult = {
 *   success: false,
 *   messageId: '',
 *   timestamp: new Date(),
 *   provider: 'ses',
 *   error: 'Invalid recipient email address'
 * };
 * ```
 */
export interface SendEmailResult {
  /**
   * Whether the email was successfully sent/queued
   * true = email accepted by provider
   * false = sending failed
   */
  success: boolean;

  /**
   * Provider-specific message identifier
   * Used for tracking delivery status and debugging
   * Empty string if send failed
   * @example '<20251031120000.1.abc@email.com>' (SES) or 'sg-message-id' (SendGrid)
   */
  messageId: string;

  /**
   * Timestamp when the send operation completed
   */
  timestamp: Date;

  /**
   * Name of the email provider used
   * @example 'sendgrid', 'ses', 'smtp'
   */
  provider: string;

  /**
   * Error message if sending failed
   * Undefined/not present on successful sends
   */
  error?: string;

  /**
   * Provider-specific metadata
   * Additional information from the provider API response
   * @example { requestId: 'xyz', region: 'us-east-1' } for SES
   */
  metadata?: Record<string, any>;
}

/**
 * Email Provider Configuration Interface
 * 
 * Configuration options for initializing email provider instances.
 * Supports multiple providers (SendGrid, AWS SES, SMTP) with provider-specific
 * credentials and retry/timeout settings per Section 0.7.2.
 * 
 * @interface EmailProviderConfig
 * @example
 * ```typescript
 * // SendGrid configuration
 * const sendgridConfig: EmailProviderConfig = {
 *   provider: 'sendgrid',
 *   apiKey: process.env.SENDGRID_API_KEY,
 *   defaultFrom: 'noreply@ocrapp.com',
 *   retryAttempts: 3,
 *   retryDelay: 1000,
 *   timeout: 30000
 * };
 * 
 * // AWS SES configuration
 * const sesConfig: EmailProviderConfig = {
 *   provider: 'ses',
 *   awsRegion: 'us-east-1',
 *   awsAccessKeyId: process.env.AWS_ACCESS_KEY_ID,
 *   awsSecretAccessKey: process.env.AWS_SECRET_ACCESS_KEY,
 *   defaultFrom: 'noreply@ocrapp.com',
 *   retryAttempts: 3,
 *   retryDelay: 2000,
 *   timeout: 30000
 * };
 * ```
 */
export interface EmailProviderConfig {
  /**
   * Email provider type
   * Determines which provider implementation to use
   */
  provider: 'sendgrid' | 'ses' | 'smtp';

  /**
   * SendGrid API key
   * Required when provider = 'sendgrid'
   * @see https://docs.sendgrid.com/ui/account-and-settings/api-keys
   */
  apiKey?: string;

  /**
   * AWS region for SES service
   * Required when provider = 'ses'
   * @example 'us-east-1', 'us-west-2', 'eu-west-1'
   */
  awsRegion?: string;

  /**
   * AWS IAM access key ID
   * Required when provider = 'ses'
   */
  awsAccessKeyId?: string;

  /**
   * AWS IAM secret access key
   * Required when provider = 'ses'
   */
  awsSecretAccessKey?: string;

  /**
   * SMTP server hostname
   * Required when provider = 'smtp'
   * @example 'smtp.gmail.com', 'smtp.office365.com'
   */
  smtpHost?: string;

  /**
   * SMTP server port
   * Required when provider = 'smtp'
   * @example 587 (TLS), 465 (SSL), 25 (unencrypted)
   */
  smtpPort?: number;

  /**
   * Use TLS/SSL for SMTP connection
   * Required when provider = 'smtp'
   * @default true for ports 465/587
   */
  smtpSecure?: boolean;

  /**
   * Default sender email address
   * Used when 'from' is not specified in SendEmailOptions
   * Must be verified in the provider
   * @example 'noreply@ocrapp.com'
   */
  defaultFrom: string;

  /**
   * Default reply-to email address
   * Used when 'replyTo' is not specified in SendEmailOptions
   * @example 'support@ocrapp.com'
   */
  replyTo?: string;

  /**
   * Number of retry attempts for failed sends
   * Per Section 0.7.3 retry logic requirements
   * @default 3
   */
  retryAttempts: number;

  /**
   * Delay between retry attempts in milliseconds
   * Exponential backoff should be implemented by the provider
   * @default 1000 (1 second)
   */
  retryDelay: number;

  /**
   * Request timeout in milliseconds
   * Maximum time to wait for provider API response
   * Per Section 0.7.4 timeout requirements
   * @default 30000 (30 seconds)
   */
  timeout: number;
}

/**
 * Email Provider Interface
 * 
 * Contract that all email provider implementations must fulfill.
 * Enables dependency injection and provider switching without code changes.
 * Implementations: SendGridProvider, SESProvider, SMTPProvider.
 * 
 * @interface IEmailProvider
 * @example
 * ```typescript
 * class SendGridProvider implements IEmailProvider {
 *   async send(options: SendEmailOptions): Promise<SendEmailResult> {
 *     // SendGrid implementation
 *   }
 *   
 *   async verifyConnection(): Promise<boolean> {
 *     // SendGrid API health check
 *   }
 *   
 *   getProviderName(): string {
 *     return 'sendgrid';
 *   }
 *   
 *   async validateConfiguration(): Promise<void> {
 *     // Validate SendGrid API key
 *   }
 * }
 * ```
 */
export interface IEmailProvider {
  /**
   * Send an email using the provider
   * 
   * Handles email delivery with retry logic and error handling.
   * Returns standardized result regardless of provider.
   * 
   * @param options - Email content and delivery options
   * @returns Promise resolving to send result with success status and message ID
   * @throws Never throws - errors are captured in SendEmailResult.error
   * 
   * @example
   * ```typescript
   * const result = await provider.send({
   *   to: 'user@example.com',
   *   from: 'noreply@ocrapp.com',
   *   subject: 'Welcome',
   *   html: '<h1>Welcome to OCR App</h1>',
   *   text: 'Welcome to OCR App'
   * });
   * 
   * if (result.success) {
   *   console.log(`Email sent with ID: ${result.messageId}`);
   * } else {
   *   console.error(`Email failed: ${result.error}`);
   * }
   * ```
   */
  send(options: SendEmailOptions): Promise<SendEmailResult>;

  /**
   * Verify connection to the email provider
   * 
   * Performs health check to ensure provider credentials are valid
   * and the service is reachable. Used by health check endpoints
   * per Section 0.7.5 monitoring requirements.
   * 
   * @returns Promise resolving to true if connection is valid, false otherwise
   * 
   * @example
   * ```typescript
   * const isHealthy = await provider.verifyConnection();
   * if (!isHealthy) {
   *   logger.error('Email provider connection failed');
   * }
   * ```
   */
  verifyConnection(): Promise<boolean>;

  /**
   * Get the provider name/type
   * 
   * Returns the provider identifier for logging, monitoring, and debugging.
   * Used in telemetry and result metadata.
   * 
   * @returns Provider type string ('sendgrid', 'ses', 'smtp')
   * 
   * @example
   * ```typescript
   * const providerName = provider.getProviderName();
   * logger.info(`Using email provider: ${providerName}`);
   * ```
   */
  getProviderName(): string;

  /**
   * Validate provider configuration
   * 
   * Checks that all required configuration values are present and valid.
   * Should be called during provider initialization to fail fast on
   * configuration errors per Section 0.7.2 NestJS Backend guidelines.
   * 
   * @throws Error if configuration is invalid or incomplete
   * 
   * @example
   * ```typescript
   * try {
   *   await provider.validateConfiguration();
   *   logger.info('Email provider configuration is valid');
   * } catch (error) {
   *   logger.error('Invalid email provider configuration', error);
   *   throw error;
   * }
   * ```
   */
  validateConfiguration(): Promise<void>;
}
