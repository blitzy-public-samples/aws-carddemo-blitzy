/**
 * Email Configuration Interfaces
 * 
 * This file defines comprehensive TypeScript interfaces for email service configuration.
 * Supports multiple email providers (SendGrid, AWS SES, SMTP) with provider-specific settings,
 * delivery preferences, retry policies, template management, tracking, validation, and logging.
 * 
 * Security Best Practices:
 * - NEVER log or expose API keys, credentials, or access tokens
 * - Store sensitive configuration in environment variables or secret management systems
 * - Use encryption for credentials at rest
 * - Rotate API keys and credentials regularly
 * - Implement least-privilege access for email service accounts
 * 
 * Usage:
 * - Import these interfaces in EmailService implementations
 * - Use with ConfigModule for environment-based configuration
 * - Validate configuration before initializing email service
 * - Test connection using IEmailConfigValidator before production use
 * 
 * @module email-config.interface
 */

/**
 * Email Service Provider Types
 * Enum defining supported email delivery providers
 */
export enum EmailProvider {
  /** SendGrid cloud email service (recommended for high volume) */
  SENDGRID = 'sendgrid',
  /** AWS Simple Email Service (best for AWS infrastructure) */
  SES = 'ses',
  /** Generic SMTP server (maximum flexibility) */
  SMTP = 'smtp',
}

/**
 * Email Priority Levels
 * Controls delivery priority and queue processing order
 */
export enum EmailPriority {
  /** Low priority - batch notifications, digests (process when resources available) */
  LOW = 'low',
  /** Normal priority - standard transactional emails (default) */
  NORMAL = 'normal',
  /** High priority - critical alerts, password resets (process immediately) */
  HIGH = 'high',
}

/**
 * SendGrid Provider Configuration
 * 
 * Configuration for SendGrid cloud email service.
 * Requires SendGrid API key (obtain from https://app.sendgrid.com/settings/api_keys)
 * 
 * Environment Variables:
 * - SENDGRID_API_KEY: Your SendGrid API key (required)
 * - SENDGRID_IP_POOL_NAME: Dedicated IP pool name (optional)
 * 
 * Example:
 * ```typescript
 * const config: SendGridConfig = {
 *   apiKey: process.env.SENDGRID_API_KEY!,
 *   sandbox: process.env.NODE_ENV !== 'production',
 *   categories: ['ocr-app', 'transactional'],
 *   suppressionGroups: [12345], // Unsubscribe group IDs
 * };
 * ```
 */
export interface SendGridConfig {
  /** SendGrid API key (v3 API) - NEVER commit to version control */
  apiKey: string;
  
  /** API base URL (default: https://api.sendgrid.com) */
  baseUrl?: string;
  
  /** Request timeout in milliseconds (default: 30000) */
  timeout?: number;
  
  /** Sandbox mode - emails are validated but not sent (for testing) */
  sandbox?: boolean;
  
  /** Dedicated IP pool name for improved deliverability (enterprise feature) */
  ipPoolName?: string;
  
  /** Suppression/unsubscribe group IDs for CAN-SPAM compliance */
  suppressionGroups?: number[];
  
  /** Categories for email analytics and filtering (max 10) */
  categories?: string[];
  
  /** Custom arguments for event webhook data (key-value pairs) */
  customArgs?: Record<string, string>;
}

/**
 * AWS SES Provider Configuration
 * 
 * Configuration for Amazon Simple Email Service.
 * Requires AWS IAM credentials with ses:SendEmail and ses:SendRawEmail permissions.
 * 
 * Environment Variables:
 * - AWS_REGION: AWS region (e.g., us-east-1)
 * - AWS_ACCESS_KEY_ID: AWS access key
 * - AWS_SECRET_ACCESS_KEY: AWS secret key
 * - AWS_SES_CONFIGURATION_SET: Configuration set name (optional)
 * 
 * Example:
 * ```typescript
 * const config: SESConfig = {
 *   region: process.env.AWS_REGION!,
 *   accessKeyId: process.env.AWS_ACCESS_KEY_ID!,
 *   secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY!,
 *   configurationSetName: 'production-emails',
 *   tags: [
 *     { Name: 'Application', Value: 'OCR-Processing' },
 *     { Name: 'Environment', Value: 'production' }
 *   ]
 * };
 * ```
 */
export interface SESConfig {
  /** AWS region (e.g., us-east-1, eu-west-1) */
  region: string;
  
  /** AWS IAM access key ID - NEVER commit to version control */
  accessKeyId: string;
  
  /** AWS IAM secret access key - NEVER commit to version control */
  secretAccessKey: string;
  
  /** SES configuration set name for tracking opens, clicks, bounces */
  configurationSetName?: string;
  
  /** Source ARN for cross-account sending authorization */
  sourceArn?: string;
  
  /** Return path ARN for bounce handling */
  returnPathArn?: string;
  
  /** Message tags for cost allocation and filtering (max 50 tags) */
  tags?: Array<{ Name: string; Value: string }>;
  
  /** Maximum send rate per second (default based on account limit) */
  maxRate?: number;
}

/**
 * SMTP Authentication Credentials
 * 
 * Authentication configuration for SMTP connections.
 * Supports standard LOGIN authentication and OAuth2.
 */
export interface SMTPAuth {
  /** SMTP username (typically email address or account name) */
  user: string;
  
  /** SMTP password or OAuth2 access token - NEVER commit to version control */
  pass: string;
  
  /** Authentication method (default: 'login') */
  type?: 'login' | 'oauth2' | 'custom';
}

/**
 * SMTP TLS/SSL Options
 * 
 * Security configuration for SMTP connections.
 * Recommended: Use TLS 1.2 or higher for production.
 */
export interface SMTPTLSOptions {
  /** Reject connections to servers with invalid certificates (recommended: true) */
  rejectUnauthorized?: boolean;
  
  /** Server name for SNI (Server Name Indication) */
  servername?: string;
  
  /** Allowed cipher suites (e.g., 'TLS_AES_128_GCM_SHA256') */
  ciphers?: string;
  
  /** Minimum TLS version (e.g., 'TLSv1.2', 'TLSv1.3') */
  minVersion?: string;
}

/**
 * SMTP Provider Configuration
 * 
 * Configuration for generic SMTP email servers.
 * Compatible with Gmail, Outlook, custom mail servers, and third-party SMTP services.
 * 
 * Environment Variables:
 * - SMTP_HOST: SMTP server hostname
 * - SMTP_PORT: SMTP server port (587 for TLS, 465 for SSL, 25 for unencrypted)
 * - SMTP_USER: SMTP username
 * - SMTP_PASS: SMTP password
 * 
 * Common SMTP Configurations:
 * 
 * Gmail:
 * ```typescript
 * {
 *   host: 'smtp.gmail.com',
 *   port: 587,
 *   secure: false, // use STARTTLS
 *   auth: { user: 'you@gmail.com', pass: 'app-password' }
 * }
 * ```
 * 
 * Office 365:
 * ```typescript
 * {
 *   host: 'smtp.office365.com',
 *   port: 587,
 *   secure: false,
 *   auth: { user: 'you@company.com', pass: 'password' }
 * }
 * ```
 */
export interface SMTPConfig {
  /** SMTP server hostname (e.g., smtp.gmail.com, smtp.example.com) */
  host: string;
  
  /** SMTP server port (587 for STARTTLS, 465 for SSL, 25 for unencrypted) */
  port: number;
  
  /** Use SSL/TLS (true for port 465, false for port 587 with STARTTLS) */
  secure: boolean;
  
  /** SMTP authentication credentials */
  auth: SMTPAuth;
  
  /** Use connection pooling for better performance (recommended for high volume) */
  pool?: boolean;
  
  /** Maximum number of simultaneous connections (default: 5) */
  maxConnections?: number;
  
  /** Maximum messages per connection before reconnecting (default: 100) */
  maxMessages?: number;
  
  /** Time window for rate limiting in milliseconds (default: 1000) */
  rateDelta?: number;
  
  /** Maximum messages allowed in rateDelta time window (default: unlimited) */
  rateLimit?: number;
  
  /** TLS/SSL security options */
  tls?: SMTPTLSOptions;
}

/**
 * Email Delivery Options
 * 
 * Controls how emails are delivered, queued, and scheduled.
 * Used for testing, batch processing, and scheduled delivery.
 */
export interface DeliveryOptions {
  /** Sandbox mode - validate emails but don't send (for testing) */
  sandbox: boolean;
  
  /** Dry run mode - validate and log but don't actually send */
  dryRun: boolean;
  
  /** Email priority level (affects queue processing order) */
  priority: EmailPriority;
  
  /** Batch size for bulk sending (emails per batch) */
  batchSize?: number;
  
  /** Delay between batches in milliseconds (prevents rate limiting) */
  batchDelay?: number;
  
  /** Maximum concurrent email sends (prevents overwhelming provider) */
  maxConcurrent?: number;
  
  /** Bull queue name for async processing (e.g., 'email-queue') */
  queueName?: string;
  
  /** Schedule email for future delivery (specific timestamp) */
  scheduledAt?: Date;
}

/**
 * Email Retry Policy
 * 
 * Defines retry behavior for failed email deliveries.
 * Implements exponential backoff to handle transient failures.
 * 
 * Example:
 * ```typescript
 * const policy: EmailRetryPolicy = {
 *   maxAttempts: 3,
 *   initialDelay: 5000, // 5 seconds
 *   maxDelay: 125000, // 2 minutes 5 seconds
 *   backoffMultiplier: 5, // 5s, 25s, 125s
 *   retryableErrors: ['ETIMEDOUT', 'ECONNRESET', 'RATE_LIMIT'],
 *   nonRetryableErrors: ['INVALID_EMAIL', 'AUTHENTICATION_FAILED'],
 * };
 * ```
 */
export interface EmailRetryPolicy {
  /** Maximum number of retry attempts (default: 3, per Section 0.4.7) */
  maxAttempts: number;
  
  /** Initial delay before first retry in milliseconds (default: 5000) */
  initialDelay: number;
  
  /** Maximum delay between retries in milliseconds (default: 125000) */
  maxDelay: number;
  
  /** Exponential backoff multiplier (default: 5, per Section 0.4.7) */
  backoffMultiplier: number;
  
  /** Error codes/types that should trigger retry (e.g., network errors) */
  retryableErrors: string[];
  
  /** Error codes/types that should NOT trigger retry (e.g., invalid email) */
  nonRetryableErrors: string[];
  
  /** Optional callback invoked on each retry attempt */
  onRetry?: (attempt: number, error: Error) => void;
}

/**
 * Email Template Configuration
 * 
 * Configuration for email template engine and template management.
 * Supports Handlebars, EJS, Pug, and Mustache templating engines.
 * 
 * Example Directory Structure:
 * ```
 * templates/
 *   ├── layouts/
 *   │   └── default.hbs
 *   ├── partials/
 *   │   ├── header.hbs
 *   │   └── footer.hbs
 *   └── emails/
 *       ├── welcome.hbs
 *       ├── password-reset.hbs
 *       └── processing-complete.hbs
 * ```
 */
export interface TemplateConfig {
  /** Template engine (default: 'handlebars') */
  engine: 'handlebars' | 'ejs' | 'pug' | 'mustache';
  
  /** Directory path containing email templates (absolute or relative to project root) */
  templatesDir: string;
  
  /** Directory path containing layout templates (wraps email content) */
  layoutsDir?: string;
  
  /** Directory path containing partial templates (reusable components) */
  partialsDir?: string;
  
  /** Enable template caching for better performance (recommended: true in production) */
  cacheEnabled: boolean;
  
  /** Cache time-to-live in seconds (0 = cache forever until restart) */
  cacheTTL: number;
  
  /** Default layout to use if not specified in email options */
  defaultLayout?: string;
  
  /** Custom helper functions for template engine (e.g., date formatting, number formatting) */
  helpers?: Record<string, Function>;
  
  /** Engine-specific compilation options */
  compileOptions?: Record<string, any>;
}

/**
 * Subscription/Unsubscribe Tracking Configuration
 * 
 * Controls unsubscribe link generation and tracking for CAN-SPAM compliance.
 */
export interface SubscriptionTracking {
  /** Enable unsubscribe link injection */
  enabled: boolean;
  
  /** Plain text unsubscribe link text (e.g., 'Unsubscribe from these emails') */
  text?: string;
  
  /** HTML unsubscribe link markup (e.g., '<a href="{unsubscribe_url}">Unsubscribe</a>') */
  html?: string;
  
  /** Template substitution tag (e.g., '{{unsubscribe_url}}') */
  substitutionTag?: string;
}

/**
 * Google Analytics Tracking Configuration
 * 
 * UTM parameter configuration for email campaign tracking.
 * Integrates with Google Analytics for email performance analysis.
 * 
 * Example:
 * ```typescript
 * {
 *   enabled: true,
 *   utmSource: 'ocr-app',
 *   utmMedium: 'email',
 *   utmCampaign: 'processing-complete-notifications',
 *   utmContent: 'template-v2'
 * }
 * ```
 */
export interface GoogleAnalyticsConfig {
  /** Enable Google Analytics UTM parameters */
  enabled: boolean;
  
  /** Campaign source (e.g., 'ocr-app', 'newsletter') */
  utmSource: string;
  
  /** Campaign medium (typically 'email') */
  utmMedium: string;
  
  /** Campaign name (e.g., 'welcome-series', 'monthly-digest') */
  utmCampaign?: string;
  
  /** Campaign term (for paid search, optional for email) */
  utmTerm?: string;
  
  /** Campaign content (for A/B testing variants) */
  utmContent?: string;
}

/**
 * Email Tracking Configuration
 * 
 * Controls email open tracking, click tracking, and analytics integration.
 * Tracking is implemented via invisible pixels (opens) and link rewriting (clicks).
 */
export interface TrackingConfig {
  /** Enable all tracking features (master switch) */
  enabled: boolean;
  
  /** Track email opens using invisible tracking pixel */
  openTracking: boolean;
  
  /** Track link clicks by rewriting URLs */
  clickTracking: boolean;
  
  /** Track unsubscribe actions */
  unsubscribeTracking: boolean;
  
  /** Subscription/unsubscribe link configuration */
  subscriptionTracking?: SubscriptionTracking;
  
  /** Google Analytics UTM parameter injection */
  googleAnalytics?: GoogleAnalyticsConfig;
  
  /** Custom tracking parameters (provider-specific) */
  customTracking?: Record<string, any>;
}

/**
 * Email Validation Configuration
 * 
 * Controls email address validation, domain verification, and content restrictions.
 * Helps prevent sending to invalid addresses and reduces bounce rates.
 */
export interface EmailValidationConfig {
  /** Validate email address format using RFC 5322 regex */
  validateEmails: boolean;
  
  /** Verify that email domain has valid MX records (DNS lookup) */
  validateDomains: boolean;
  
  /** Reject disposable email addresses (e.g., 10minutemail.com, guerrillamail.com) */
  rejectDisposable: boolean;
  
  /** Reject role-based email addresses (e.g., admin@, support@, noreply@) */
  rejectRoleAccounts: boolean;
  
  /** Custom validation functions for additional checks */
  customValidators?: Array<(email: string) => boolean>;
  
  /** Maximum recipients per email (to + cc + bcc combined) */
  maxRecipients: number;
  
  /** Maximum attachment size in bytes (default: 10MB) */
  maxAttachmentSize: number;
  
  /** Allowed attachment MIME types (e.g., ['application/pdf', 'image/jpeg']) */
  allowedAttachmentTypes: string[];
}

/**
 * Email Log Storage Configuration
 * 
 * Defines where and how email logs are stored.
 * Supports database, file system, S3, and CloudWatch storage.
 */
export interface EmailLogStorage {
  /** Storage type for email logs */
  type: 'database' | 'file' | 's3' | 'cloudwatch';
  
  /** Log retention period in days (0 = keep forever) */
  retentionDays: number;
  
  /** Compress logs for storage efficiency (recommended for long-term storage) */
  compressionEnabled?: boolean;
  
  /** Encrypt logs at rest (recommended for compliance) */
  encryptionEnabled?: boolean;
  
  /** Storage-specific configuration (connection strings, bucket names, etc.) */
  config?: Record<string, any>;
}

/**
 * Email Logging Configuration
 * 
 * Controls logging verbosity, what data is logged, and where logs are stored.
 * CRITICAL: Never log sensitive data (passwords, API keys, PII) in production.
 * 
 * Recommended Production Settings:
 * ```typescript
 * {
 *   enabled: true,
 *   logLevel: 'info',
 *   logSentEmails: true,
 *   logFailedEmails: true,
 *   logRetries: true,
 *   includeBody: false, // Disable in production to avoid logging PII
 *   includeHeaders: false,
 *   maskSensitiveData: true,
 * }
 * ```
 */
export interface EmailLoggingConfig {
  /** Enable email operation logging */
  enabled: boolean;
  
  /** Minimum log level to capture (debug < info < warn < error) */
  logLevel: 'debug' | 'info' | 'warn' | 'error';
  
  /** Log successfully sent emails */
  logSentEmails: boolean;
  
  /** Log failed email attempts */
  logFailedEmails: boolean;
  
  /** Log retry attempts */
  logRetries: boolean;
  
  /** Include email body content in logs (DISABLE in production - may contain PII) */
  includeBody: boolean;
  
  /** Include email headers in logs (may contain sensitive routing info) */
  includeHeaders: boolean;
  
  /** Mask sensitive data in logs (email addresses, names, etc.) */
  maskSensitiveData: boolean;
  
  /** Log storage configuration */
  storage?: EmailLogStorage;
}

/**
 * Main Email Service Configuration Interface
 * 
 * Root configuration object for email service initialization.
 * Combines provider-specific settings with delivery, retry, template, tracking,
 * validation, and logging configuration.
 * 
 * Configuration Sources:
 * 1. Environment variables (via ConfigModule)
 * 2. Configuration files (JSON, YAML)
 * 3. Secret management systems (AWS Secrets Manager, HashiCorp Vault)
 * 
 * Example - SendGrid Configuration:
 * ```typescript
 * const config: IEmailConfig = {
 *   provider: EmailProvider.SENDGRID,
 *   providerConfig: {
 *     apiKey: process.env.SENDGRID_API_KEY!,
 *     sandbox: false,
 *     categories: ['ocr-app']
 *   },
 *   defaultFrom: 'noreply@ocr-app.com',
 *   defaultFromName: 'OCR Processing App',
 *   defaultReplyTo: 'support@ocr-app.com',
 *   deliveryOptions: {
 *     sandbox: false,
 *     dryRun: false,
 *     priority: EmailPriority.NORMAL,
 *     batchSize: 100,
 *     batchDelay: 1000,
 *     maxConcurrent: 10,
 *     queueName: 'email-queue'
 *   },
 *   retryPolicy: {
 *     maxAttempts: 3,
 *     initialDelay: 5000,
 *     maxDelay: 125000,
 *     backoffMultiplier: 5,
 *     retryableErrors: ['ETIMEDOUT', 'RATE_LIMIT'],
 *     nonRetryableErrors: ['INVALID_EMAIL']
 *   },
 *   templateConfig: {
 *     engine: 'handlebars',
 *     templatesDir: './templates/emails',
 *     layoutsDir: './templates/layouts',
 *     partialsDir: './templates/partials',
 *     cacheEnabled: true,
 *     cacheTTL: 3600,
 *     defaultLayout: 'default'
 *   },
 *   trackingConfig: {
 *     enabled: true,
 *     openTracking: true,
 *     clickTracking: true,
 *     unsubscribeTracking: true,
 *     googleAnalytics: {
 *       enabled: true,
 *       utmSource: 'ocr-app',
 *       utmMedium: 'email'
 *     }
 *   },
 *   validation: {
 *     validateEmails: true,
 *     validateDomains: false,
 *     rejectDisposable: true,
 *     rejectRoleAccounts: false,
 *     maxRecipients: 50,
 *     maxAttachmentSize: 10485760, // 10MB
 *     allowedAttachmentTypes: ['application/pdf', 'image/jpeg', 'image/png']
 *   },
 *   logging: {
 *     enabled: true,
 *     logLevel: 'info',
 *     logSentEmails: true,
 *     logFailedEmails: true,
 *     logRetries: true,
 *     includeBody: false,
 *     includeHeaders: false,
 *     maskSensitiveData: true
 *   }
 * };
 * ```
 * 
 * Example - AWS SES Configuration:
 * ```typescript
 * const config: IEmailConfig = {
 *   provider: EmailProvider.SES,
 *   providerConfig: {
 *     region: 'us-east-1',
 *     accessKeyId: process.env.AWS_ACCESS_KEY_ID!,
 *     secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY!,
 *     configurationSetName: 'production-emails'
 *   },
 *   // ... rest of configuration similar to above
 * };
 * ```
 * 
 * Example - SMTP Configuration:
 * ```typescript
 * const config: IEmailConfig = {
 *   provider: EmailProvider.SMTP,
 *   providerConfig: {
 *     host: 'smtp.gmail.com',
 *     port: 587,
 *     secure: false,
 *     auth: {
 *       user: process.env.SMTP_USER!,
 *       pass: process.env.SMTP_PASS!
 *     }
 *   },
 *   // ... rest of configuration similar to above
 * };
 * ```
 */
export interface IEmailConfig {
  /** Email service provider (SendGrid, SES, or SMTP) */
  provider: EmailProvider;
  
  /** Provider-specific configuration (union type based on provider) */
  providerConfig: SendGridConfig | SESConfig | SMTPConfig;
  
  /** Default "from" email address (must be verified with provider) */
  defaultFrom: string;
  
  /** Default "from" name displayed to recipients */
  defaultFromName?: string;
  
  /** Default "reply-to" email address for responses */
  defaultReplyTo?: string;
  
  /** Email delivery and queue management options */
  deliveryOptions: DeliveryOptions;
  
  /** Retry policy for failed deliveries */
  retryPolicy: EmailRetryPolicy;
  
  /** Template engine configuration */
  templateConfig: TemplateConfig;
  
  /** Email tracking and analytics configuration */
  trackingConfig: TrackingConfig;
  
  /** Email validation rules and limits */
  validation: EmailValidationConfig;
  
  /** Logging configuration */
  logging: EmailLoggingConfig;
}

/**
 * Validation Result
 * 
 * Result object returned by configuration validation functions.
 * Contains validation errors and warnings for configuration issues.
 */
export interface ValidationResult {
  /** Whether configuration is valid and can be used */
  valid: boolean;
  
  /** Critical validation errors that prevent email sending */
  errors: Array<{
    /** Configuration field with error (dot notation, e.g., 'providerConfig.apiKey') */
    field: string;
    /** Human-readable error message */
    message: string;
    /** Error code for programmatic handling */
    code: string;
  }>;
  
  /** Non-critical warnings about suboptimal configuration */
  warnings: Array<{
    /** Configuration field with warning */
    field: string;
    /** Human-readable warning message */
    message: string;
  }>;
}

/**
 * Connection Test Result
 * 
 * Result object returned by connection testing functions.
 * Indicates whether email service is reachable and properly configured.
 */
export interface ConnectionTestResult {
  /** Whether connection test was successful */
  success: boolean;
  
  /** Human-readable test result message */
  message: string;
  
  /** Connection response time in milliseconds */
  responseTime?: number;
  
  /** Provider-specific information (API version, account details, etc.) */
  providerInfo?: Record<string, any>;
  
  /** Error object if connection test failed */
  error?: Error;
}

/**
 * Email Configuration Validator Interface
 * 
 * Service interface for validating and testing email configurations.
 * Implement this interface to create configuration validation utilities.
 * 
 * Usage Example:
 * ```typescript
 * class EmailConfigValidator implements IEmailConfigValidator {
 *   async validate(config: IEmailConfig): Promise<ValidationResult> {
 *     const errors = [];
 *     
 *     if (!config.defaultFrom) {
 *       errors.push({
 *         field: 'defaultFrom',
 *         message: 'Default from email is required',
 *         code: 'MISSING_DEFAULT_FROM'
 *       });
 *     }
 *     
 *     // ... more validation
 *     
 *     return {
 *       valid: errors.length === 0,
 *       errors,
 *       warnings: []
 *     };
 *   }
 *   
 *   async testConnection(config: IEmailConfig): Promise<ConnectionTestResult> {
 *     try {
 *       const startTime = Date.now();
 *       // Test connection to provider
 *       const responseTime = Date.now() - startTime;
 *       
 *       return {
 *         success: true,
 *         message: 'Connection successful',
 *         responseTime
 *       };
 *     } catch (error) {
 *       return {
 *         success: false,
 *         message: 'Connection failed',
 *         error: error as Error
 *       };
 *     }
 *   }
 * }
 * ```
 */
export interface IEmailConfigValidator {
  /**
   * Validate complete email configuration
   * 
   * Checks:
   * - Required fields are present
   * - Email addresses are valid format
   * - Provider-specific configuration is complete
   * - Template directories exist
   * - Retry policy values are reasonable
   * - Validation limits are within provider constraints
   * 
   * @param config Email configuration to validate
   * @returns Validation result with errors and warnings
   */
  validate(config: IEmailConfig): Promise<ValidationResult>;
  
  /**
   * Validate provider-specific configuration
   * 
   * Checks provider-specific requirements:
   * - SendGrid: API key format, category limits
   * - SES: AWS credentials format, region validity
   * - SMTP: Host reachability, port validity
   * 
   * @param config Provider configuration to validate
   * @returns Validation result with provider-specific checks
   */
  validateProvider(
    config: SendGridConfig | SESConfig | SMTPConfig
  ): Promise<ValidationResult>;
  
  /**
   * Test connection to email provider
   * 
   * Attempts to:
   * - Authenticate with provider
   * - Verify credentials are valid
   * - Check sending quota/limits
   * - Measure response time
   * 
   * Note: May send a test email depending on provider API
   * 
   * @param config Email configuration to test
   * @returns Connection test result with success status and metadata
   */
  testConnection(config: IEmailConfig): Promise<ConnectionTestResult>;
}
