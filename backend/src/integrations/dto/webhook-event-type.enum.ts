/**
 * Webhook event types for the OCR Processing Application notification system.
 * 
 * Events follow the pattern: {category}.{action} (e.g., document.processed, batch.completed)
 * 
 * **Event Delivery:**
 * - HTTP POST to subscriber URL
 * - Headers: X-Webhook-Signature (HMAC-SHA256), X-Event-Type, X-Request-ID
 * - Timeout: 30 seconds
 * - Retry: 3 attempts with exponential backoff (5s, 25s, 125s)
 * 
 * **Payload Format:**
 * ```json
 * {
 *   "event_id": "uuid",
 *   "event_type": "document.processed",
 *   "timestamp": "2025-10-31T12:00:00Z",
 *   "account_id": "uuid",
 *   "data": { ... event-specific data ... }
 * }
 * ```
 * 
 * @see Section 0.4.7 Webhook System Integration
 * @see Section 0.5.11 Phase 10 Group 10B: Webhook System
 */

/**
 * Webhook event types for outbound notifications
 * 
 * String enum using dotted notation for event categorization
 */
export enum WebhookEventType {
  // Document lifecycle events
  DOCUMENT_UPLOADED = 'document.uploaded',
  DOCUMENT_PROCESSING = 'document.processing',
  DOCUMENT_PROCESSED = 'document.processed',
  DOCUMENT_APPROVED = 'document.approved',
  DOCUMENT_REJECTED = 'document.rejected',
  DOCUMENT_DELETED = 'document.deleted',
  
  // Batch processing events
  BATCH_STARTED = 'batch.started',
  BATCH_COMPLETED = 'batch.completed',
  BATCH_FAILED = 'batch.failed',
  
  // Template management events
  TEMPLATE_CREATED = 'template.created',
  TEMPLATE_UPDATED = 'template.updated',
  TEMPLATE_DELETED = 'template.deleted',
  
  // Integration events
  INTEGRATION_CONNECTED = 'integration.connected',
  INTEGRATION_FAILED = 'integration.failed',
  INTEGRATION_SYNCED = 'integration.synced',
  INTEGRATION_DISCONNECTED = 'integration.disconnected',
  
  // User events (optional for future)
  USER_CREATED = 'user.created',
  USER_DELETED = 'user.deleted'
}

/**
 * Event category classification for webhook events
 */
export enum WebhookEventCategory {
  DOCUMENT = 'document',
  BATCH = 'batch',
  TEMPLATE = 'template',
  INTEGRATION = 'integration',
  USER = 'user'
}

/**
 * Event priority for webhook delivery
 * Affects retry behavior and delivery guarantees
 */
export enum WebhookEventPriority {
  LOW = 'low',
  NORMAL = 'normal',
  HIGH = 'high',
  CRITICAL = 'critical'
}

/**
 * Event metadata including display name and description
 */
export interface WebhookEventMetadata {
  displayName: string;
  description: string;
  category: WebhookEventCategory;
  payloadSchema: string; // Reference to payload structure
}

/**
 * Get category for webhook event type
 * 
 * @param eventType - The webhook event type to categorize
 * @returns The category of the event
 * @throws Error if event type doesn't match any known category
 */
export function getEventCategory(eventType: WebhookEventType): WebhookEventCategory {
  if (eventType.startsWith('document.')) return WebhookEventCategory.DOCUMENT;
  if (eventType.startsWith('batch.')) return WebhookEventCategory.BATCH;
  if (eventType.startsWith('template.')) return WebhookEventCategory.TEMPLATE;
  if (eventType.startsWith('integration.')) return WebhookEventCategory.INTEGRATION;
  if (eventType.startsWith('user.')) return WebhookEventCategory.USER;
  throw new Error(`Unknown event category for: ${eventType}`);
}

/**
 * Check if a value is a valid WebhookEventType
 * 
 * @param value - String value to validate
 * @returns True if value is a valid WebhookEventType
 */
export function isValidWebhookEventType(value: string): value is WebhookEventType {
  return Object.values(WebhookEventType).includes(value as WebhookEventType);
}

/**
 * Parse webhook event type from string, throw if invalid
 * 
 * @param value - String value to parse
 * @returns Validated WebhookEventType
 * @throws Error if value is not a valid WebhookEventType
 */
export function parseWebhookEventType(value: string): WebhookEventType {
  if (!isValidWebhookEventType(value)) {
    throw new Error(`Invalid webhook event type: ${value}`);
  }
  return value;
}

/**
 * Get metadata for webhook event type
 * 
 * @param eventType - The webhook event type
 * @returns Metadata including display name, description, category, and payload schema
 */
export function getEventMetadata(eventType: WebhookEventType): WebhookEventMetadata {
  const metadata: Record<WebhookEventType, WebhookEventMetadata> = {
    [WebhookEventType.DOCUMENT_UPLOADED]: {
      displayName: 'Document Uploaded',
      description: 'Triggered when a document is successfully uploaded to the system',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentUploadedPayload'
    },
    [WebhookEventType.DOCUMENT_PROCESSING]: {
      displayName: 'Document Processing',
      description: 'Triggered when OCR processing starts for a document',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentProcessingPayload'
    },
    [WebhookEventType.DOCUMENT_PROCESSED]: {
      displayName: 'Document Processed',
      description: 'Triggered when OCR and NLP processing completes successfully',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentProcessedPayload'
    },
    [WebhookEventType.DOCUMENT_APPROVED]: {
      displayName: 'Document Approved',
      description: 'Triggered when a user approves extracted document data',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentApprovedPayload'
    },
    [WebhookEventType.DOCUMENT_REJECTED]: {
      displayName: 'Document Rejected',
      description: 'Triggered when a user rejects a processed document',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentRejectedPayload'
    },
    [WebhookEventType.DOCUMENT_DELETED]: {
      displayName: 'Document Deleted',
      description: 'Triggered when a document is permanently deleted',
      category: WebhookEventCategory.DOCUMENT,
      payloadSchema: 'DocumentDeletedPayload'
    },
    [WebhookEventType.BATCH_STARTED]: {
      displayName: 'Batch Processing Started',
      description: 'Triggered when a batch processing job is initiated',
      category: WebhookEventCategory.BATCH,
      payloadSchema: 'BatchStartedPayload'
    },
    [WebhookEventType.BATCH_COMPLETED]: {
      displayName: 'Batch Processing Completed',
      description: 'Triggered when all documents in a batch are processed',
      category: WebhookEventCategory.BATCH,
      payloadSchema: 'BatchCompletedPayload'
    },
    [WebhookEventType.BATCH_FAILED]: {
      displayName: 'Batch Processing Failed',
      description: 'Triggered when a batch processing job encounters errors',
      category: WebhookEventCategory.BATCH,
      payloadSchema: 'BatchFailedPayload'
    },
    [WebhookEventType.TEMPLATE_CREATED]: {
      displayName: 'Template Created',
      description: 'Triggered when a new extraction template is created',
      category: WebhookEventCategory.TEMPLATE,
      payloadSchema: 'TemplateCreatedPayload'
    },
    [WebhookEventType.TEMPLATE_UPDATED]: {
      displayName: 'Template Updated',
      description: 'Triggered when an extraction template is modified',
      category: WebhookEventCategory.TEMPLATE,
      payloadSchema: 'TemplateUpdatedPayload'
    },
    [WebhookEventType.TEMPLATE_DELETED]: {
      displayName: 'Template Deleted',
      description: 'Triggered when an extraction template is deleted',
      category: WebhookEventCategory.TEMPLATE,
      payloadSchema: 'TemplateDeletedPayload'
    },
    [WebhookEventType.INTEGRATION_CONNECTED]: {
      displayName: 'Integration Connected',
      description: 'Triggered when a third-party integration is successfully connected',
      category: WebhookEventCategory.INTEGRATION,
      payloadSchema: 'IntegrationConnectedPayload'
    },
    [WebhookEventType.INTEGRATION_FAILED]: {
      displayName: 'Integration Failed',
      description: 'Triggered when an integration connection or sync fails',
      category: WebhookEventCategory.INTEGRATION,
      payloadSchema: 'IntegrationFailedPayload'
    },
    [WebhookEventType.INTEGRATION_SYNCED]: {
      displayName: 'Integration Synced',
      description: 'Triggered when data is successfully synchronized with third-party system',
      category: WebhookEventCategory.INTEGRATION,
      payloadSchema: 'IntegrationSyncedPayload'
    },
    [WebhookEventType.INTEGRATION_DISCONNECTED]: {
      displayName: 'Integration Disconnected',
      description: 'Triggered when an integration is disconnected or revoked',
      category: WebhookEventCategory.INTEGRATION,
      payloadSchema: 'IntegrationDisconnectedPayload'
    },
    [WebhookEventType.USER_CREATED]: {
      displayName: 'User Created',
      description: 'Triggered when a new user account is created',
      category: WebhookEventCategory.USER,
      payloadSchema: 'UserCreatedPayload'
    },
    [WebhookEventType.USER_DELETED]: {
      displayName: 'User Deleted',
      description: 'Triggered when a user account is deleted',
      category: WebhookEventCategory.USER,
      payloadSchema: 'UserDeletedPayload'
    }
  };
  
  return metadata[eventType];
}

/**
 * Get priority level for event type
 * 
 * Critical events require immediate delivery with highest retry guarantees
 * 
 * @param eventType - The webhook event type
 * @returns Priority level for the event
 */
export function getEventPriority(eventType: WebhookEventType): WebhookEventPriority {
  // Critical events require immediate delivery
  const criticalEvents = [
    WebhookEventType.DOCUMENT_PROCESSED,
    WebhookEventType.BATCH_COMPLETED
  ];
  
  // High priority events
  const highPriorityEvents = [
    WebhookEventType.DOCUMENT_APPROVED,
    WebhookEventType.INTEGRATION_FAILED,
    WebhookEventType.BATCH_FAILED
  ];
  
  // Low priority events
  const lowPriorityEvents = [
    WebhookEventType.USER_CREATED,
    WebhookEventType.USER_DELETED
  ];
  
  if (criticalEvents.includes(eventType)) return WebhookEventPriority.CRITICAL;
  if (highPriorityEvents.includes(eventType)) return WebhookEventPriority.HIGH;
  if (lowPriorityEvents.includes(eventType)) return WebhookEventPriority.LOW;
  return WebhookEventPriority.NORMAL;
}

/**
 * Get all event types for a category
 * 
 * @param category - The event category to filter by
 * @returns Array of all event types in the category
 */
export function getEventsByCategory(category: WebhookEventCategory): WebhookEventType[] {
  return Object.values(WebhookEventType).filter(
    eventType => getEventCategory(eventType) === category
  );
}

/**
 * Check if event type is document-related
 * 
 * @param eventType - The webhook event type to check
 * @returns True if event is document-related
 */
export function isDocumentEvent(eventType: WebhookEventType): boolean {
  return getEventCategory(eventType) === WebhookEventCategory.DOCUMENT;
}

/**
 * Check if event type is integration-related
 * 
 * @param eventType - The webhook event type to check
 * @returns True if event is integration-related
 */
export function isIntegrationEvent(eventType: WebhookEventType): boolean {
  return getEventCategory(eventType) === WebhookEventCategory.INTEGRATION;
}
