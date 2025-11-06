/**
 * Audit Log Entry Interface
 * 
 * Provides comprehensive type definitions for audit logging throughout the OCR Processing Application.
 * Supports security audit requirements, SOC 2 compliance, and forensic analysis capabilities.
 * 
 * Per Section 0.7.1 Security Requirements: ALL admin operations MUST be logged with comprehensive
 * tracking of who, what, when, where for security audits and compliance.
 * 
 * @module AuditLogEntry
 * @category Common/Interfaces
 * @see Section 0.7.1 - Security and Compliance Requirements
 * @see Section 0.1.2 - SOC 2 Type II Compliance
 */

/**
 * Enumeration of all auditable actions in the system.
 * 
 * Covers all major user and system operations that require audit trails:
 * - CRUD operations (CREATE, READ, UPDATE, DELETE)
 * - Authentication events (LOGIN, LOGOUT)
 * - Business operations (EXPORT, APPROVE, REJECT)
 * - Security events (ACCESS_DENIED)
 * 
 * @enum {string}
 * @example
 * ```typescript
 * const action: AuditAction = AuditAction.CREATE;
 * ```
 */
export enum AuditAction {
  /** Resource creation action (e.g., new document, new user, new template) */
  CREATE = 'create',
  
  /** Resource read/access action (e.g., view document, download file) */
  READ = 'read',
  
  /** Resource modification action (e.g., edit field, update settings) */
  UPDATE = 'update',
  
  /** Resource deletion action (e.g., delete document, remove user) */
  DELETE = 'delete',
  
  /** User authentication success (login) */
  LOGIN = 'login',
  
  /** User session termination (logout) */
  LOGOUT = 'logout',
  
  /** Data export action (e.g., export documents to CSV, JSON, Excel) */
  EXPORT = 'export',
  
  /** Approval workflow action (e.g., approve document data) */
  APPROVE = 'approve',
  
  /** Rejection workflow action (e.g., reject extracted data) */
  REJECT = 'reject',
  
  /** Access denied security event (unauthorized access attempt) */
  ACCESS_DENIED = 'access_denied',
}

/**
 * Enumeration of all resource types in the system that can be audited.
 * 
 * Covers all major entities in the OCR Processing Application:
 * - Core entities (DOCUMENT, USER, TEMPLATE)
 * - Integration entities (INTEGRATION, API_KEY, WEBHOOK)
 * - Security entities (ROLE, PERMISSION)
 * - Account management (ACCOUNT)
 * 
 * @enum {string}
 * @example
 * ```typescript
 * const resourceType: AuditResourceType = AuditResourceType.DOCUMENT;
 * ```
 */
export enum AuditResourceType {
  /** Document resource (uploaded files, processed documents) */
  DOCUMENT = 'document',
  
  /** User resource (user accounts, profiles) */
  USER = 'user',
  
  /** Template resource (custom extraction templates) */
  TEMPLATE = 'template',
  
  /** Integration resource (third-party integrations like QuickBooks, Salesforce) */
  INTEGRATION = 'integration',
  
  /** API key resource (API authentication credentials) */
  API_KEY = 'api_key',
  
  /** Webhook resource (webhook subscriptions and configurations) */
  WEBHOOK = 'webhook',
  
  /** Role resource (RBAC role definitions) */
  ROLE = 'role',
  
  /** Permission resource (RBAC permission grants) */
  PERMISSION = 'permission',
  
  /** Account resource (multi-tenant account management) */
  ACCOUNT = 'account',
}

/**
 * Interface representing changes made to a resource during UPDATE operations.
 * 
 * Captures before/after state for audit trail and rollback capabilities.
 * Supports forensic analysis by preserving complete change history.
 * 
 * @interface AuditChanges
 * @example
 * ```typescript
 * const changes: AuditChanges = {
 *   before: { status: 'pending', confidence: 0.75 },
 *   after: { status: 'approved', confidence: 0.95 },
 *   fields: ['status', 'confidence']
 * };
 * ```
 */
export interface AuditChanges {
  /**
   * Previous values before the update.
   * Key-value pairs of field names and their previous values.
   * Undefined for CREATE operations.
   * 
   * @type {Record<string, any>}
   * @optional
   */
  before?: Record<string, any>;

  /**
   * New values after the update.
   * Key-value pairs of field names and their updated values.
   * Undefined for DELETE operations.
   * 
   * @type {Record<string, any>}
   * @optional
   */
  after?: Record<string, any>;

  /**
   * List of field names that were modified.
   * Provides quick reference to changed fields without comparing before/after.
   * 
   * @type {string[]}
   * @optional
   * @example ['status', 'confidence', 'updatedAt']
   */
  fields?: string[];
}

/**
 * Complete audit log entry interface for comprehensive audit trails.
 * 
 * Captures all necessary information for security audits, compliance reporting,
 * and forensic analysis per SOC 2 Type II requirements.
 * 
 * Tracks:
 * - WHO performed the action (userId, userEmail)
 * - WHAT action was performed (action, resourceType, resourceId)
 * - WHEN it occurred (timestamp)
 * - WHERE it came from (ipAddress, userAgent)
 * - HOW it went (success, errorMessage)
 * - WHAT changed (changes)
 * 
 * @interface AuditLogEntry
 * @see Section 0.7.1 - ALL admin operations MUST be logged in audit_logs table
 * @example
 * ```typescript
 * const auditEntry: AuditLogEntry = {
 *   id: '550e8400-e29b-41d4-a716-446655440000',
 *   accountId: '123e4567-e89b-12d3-a456-426614174000',
 *   userId: '789e0123-e45b-67c8-d901-234567890abc',
 *   userEmail: 'admin@example.com',
 *   action: AuditAction.UPDATE,
 *   resourceType: AuditResourceType.DOCUMENT,
 *   resourceId: 'doc-12345',
 *   resourceName: 'Invoice_2025_001.pdf',
 *   changes: {
 *     before: { status: 'pending' },
 *     after: { status: 'approved' },
 *     fields: ['status']
 *   },
 *   metadata: { reason: 'Manual review completed' },
 *   ipAddress: '192.168.1.100',
 *   userAgent: 'Mozilla/5.0...',
 *   success: true,
 *   timestamp: new Date('2025-10-31T12:00:00Z'),
 *   duration: 45
 * };
 * ```
 */
export interface AuditLogEntry {
  /**
   * Unique identifier for this audit log entry.
   * UUID v4 format for global uniqueness.
   * 
   * @type {string}
   * @required
   * @example '550e8400-e29b-41d4-a716-446655440000'
   */
  id: string;

  /**
   * Multi-tenant account identifier.
   * Ensures audit logs are properly isolated per account.
   * 
   * @type {string}
   * @required
   * @example '123e4567-e89b-12d3-a456-426614174000'
   */
  accountId: string;

  /**
   * User ID who performed the action.
   * Null for system-initiated actions (e.g., automated cleanup, scheduled jobs).
   * 
   * @type {string | null}
   * @required
   * @example '789e0123-e45b-67c8-d901-234567890abc'
   */
  userId: string | null;

  /**
   * User email for human readability in audit reports.
   * Populated from user record at time of action.
   * Optional as some actions may not have associated user email.
   * 
   * @type {string}
   * @optional
   * @example 'admin@example.com'
   */
  userEmail?: string;

  /**
   * Type of action performed on the resource.
   * 
   * @type {AuditAction}
   * @required
   * @see AuditAction enum for available values
   */
  action: AuditAction;

  /**
   * Type of resource that was affected by the action.
   * 
   * @type {AuditResourceType}
   * @required
   * @see AuditResourceType enum for available values
   */
  resourceType: AuditResourceType;

  /**
   * Unique identifier of the specific resource affected.
   * Null for bulk operations or actions not tied to specific resource
   * (e.g., bulk delete, list operations).
   * 
   * @type {string | null}
   * @required
   * @example 'doc-12345' or 'user-67890'
   */
  resourceId: string | null;

  /**
   * Human-readable name of the resource for audit reports.
   * Makes audit logs more accessible to non-technical reviewers.
   * 
   * @type {string}
   * @optional
   * @example 'Invoice_2025_001.pdf' or 'admin@example.com'
   */
  resourceName?: string;

  /**
   * Detailed change information for UPDATE operations.
   * Captures before/after state for complete audit trail.
   * Undefined for non-UPDATE actions.
   * 
   * @type {AuditChanges}
   * @optional
   * @see AuditChanges interface
   */
  changes?: AuditChanges;

  /**
   * Additional contextual metadata about the action.
   * Flexible key-value storage for action-specific details.
   * 
   * Examples:
   * - Reason for approval/rejection
   * - Export format and filters
   * - Integration sync details
   * - Batch operation summary
   * 
   * @type {Record<string, any>}
   * @optional
   * @example { reason: 'Manual review', documentCount: 5, exportFormat: 'CSV' }
   */
  metadata?: Record<string, any>;

  /**
   * Client IP address from which the action originated.
   * Null for server-initiated actions.
   * Supports forensic analysis and security investigations.
   * 
   * @type {string | null}
   * @required
   * @example '192.168.1.100' or '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
   */
  ipAddress: string | null;

  /**
   * Client user agent string for browser/client identification.
   * Helps identify access patterns and potential security issues.
   * 
   * @type {string}
   * @optional
   * @example 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
   */
  userAgent?: string;

  /**
   * Whether the action completed successfully.
   * True for successful operations, false for failures.
   * Critical for identifying failed access attempts and errors.
   * 
   * @type {boolean}
   * @required
   * @example true
   */
  success: boolean;

  /**
   * Error message if the action failed (success = false).
   * Contains technical error details for troubleshooting.
   * Undefined for successful operations.
   * 
   * @type {string}
   * @optional
   * @example 'Insufficient permissions to access resource'
   */
  errorMessage?: string;

  /**
   * Timestamp when the action occurred (UTC).
   * Must use UTC timezone for consistent audit trails across regions.
   * 
   * @type {Date}
   * @required
   * @example new Date('2025-10-31T12:00:00Z')
   */
  timestamp: Date;

  /**
   * Duration of the action in milliseconds.
   * Useful for performance analysis and detecting anomalies.
   * Undefined if duration tracking not applicable.
   * 
   * @type {number}
   * @optional
   * @example 45 (milliseconds)
   */
  duration?: number;
}
