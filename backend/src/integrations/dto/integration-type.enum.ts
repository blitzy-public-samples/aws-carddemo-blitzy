/**
 * Integration Type Enum and Utilities
 * 
 * Defines supported third-party integration types for the OCR Processing Application.
 * Each integration type represents a connection to an external business system
 * or cloud storage service for document export, data synchronization, or file storage.
 * 
 * @module integrations/dto/integration-type.enum
 * @see Section 0.4.6 Third-Party Business System Integrations
 * @see Section 0.5.11 Phase 10: Integrations and API
 * @see Section 0.3.8 Pre-built Integration SDKs
 */

/**
 * Supported third-party integration types.
 * 
 * Uses string enum values for better serialization to JSON and improved
 * debugging experience. Each value represents a specific third-party service
 * that can be integrated with the OCR Processing Application.
 * 
 * @enum {string}
 */
export enum IntegrationType {
  /**
   * QuickBooks Online - Accounting software integration
   * 
   * - Authentication: OAuth 2.0 with QuickBooks Connect
   * - Primary use: Export invoice data, vendor information, expense records
   * - SDK: node-quickbooks v2.0.32
   * - API Version: v3 (QuickBooks Online API)
   * - Scopes: com.intuit.quickbooks.accounting
   * - Webhook support: Yes (change notifications)
   * 
   * @see Section 0.4.6 Third-Party Business System Integrations - QuickBooks Integration
   */
  QUICKBOOKS = 'quickbooks',

  /**
   * Salesforce - Customer Relationship Management (CRM) integration
   * 
   * - Authentication: OAuth 2.0 with Connected App
   * - Primary use: Create leads, update opportunities, attach documents
   * - SDK: jsforce v2.0.0-beta.34
   * - API Version: v59.0 (REST API)
   * - Supports: Bulk API for large data transfers
   * - Webhook support: Yes (outbound messages)
   * 
   * @see Section 0.4.6 Third-Party Business System Integrations - Salesforce Integration
   */
  SALESFORCE = 'salesforce',

  /**
   * NetSuite - Enterprise Resource Planning (ERP) integration
   * 
   * - Authentication: Token-Based Authentication (TBA)
   * - Primary use: Customer records, invoice processing, document attachments
   * - SDK: netsuite-rest v1.1.0
   * - API: RESTlet or SuiteTalk REST API
   * - Credentials: Account ID, consumer key/secret, token ID/secret
   * - Webhook support: No (polling-based integration)
   * 
   * @see Section 0.4.6 Third-Party Business System Integrations - NetSuite Integration
   */
  NETSUITE = 'netsuite',

  /**
   * Microsoft SharePoint - Document management and collaboration integration
   * 
   * - Authentication: OAuth 2.0 with Azure AD app registration
   * - Primary use: Upload documents to SharePoint libraries, metadata tagging
   * - SDK: @pnp/sp v4.9.0
   * - API: Microsoft Graph API or SharePoint REST API
   * - Configuration: Tenant ID, client ID/secret, site URL
   * - Webhook support: No (designed for document storage)
   * 
   * @see Section 0.4.6 Third-Party Business System Integrations - SharePoint Integration
   */
  SHAREPOINT = 'sharepoint',

  /**
   * Dropbox - Cloud storage and file sharing integration
   * 
   * - Authentication: OAuth 2.0 with Dropbox App Console
   * - Primary use: File upload, folder creation, sharing permissions
   * - SDK: dropbox v10.34.0
   * - API: Dropbox API v2
   * - Webhook support: Yes (file change notifications)
   * - Features: Automatic file synchronization
   * 
   * @see Section 0.4.6 Third-Party Business System Integrations - Dropbox Integration
   */
  DROPBOX = 'dropbox',

  /**
   * Google Drive - Cloud storage and document management integration
   * 
   * - Authentication: OAuth 2.0 with Google Cloud Console project
   * - Primary use: Upload documents to Drive, folder management, sharing
   * - SDK: googleapis v144.0.0 (Google Drive API v3)
   * - API Version: v3 (Drive API)
   * - Scopes: drive.file or drive (full access)
   * - Webhook support: Yes (push notifications via webhooks)
   * 
   * @see Section 0.3.8 Pre-built Integration SDKs - Google Drive
   */
  GOOGLE_DRIVE = 'google_drive',
}

/**
 * Integration capabilities interface.
 * 
 * Defines the operational capabilities of each integration type,
 * indicating which features are supported by the external service.
 * 
 * @interface IntegrationCapabilities
 */
export interface IntegrationCapabilities {
  /**
   * Whether the integration supports exporting data FROM the OCR app TO the external system.
   * Example: Exporting invoice data to QuickBooks
   */
  supportsExport: boolean;

  /**
   * Whether the integration supports importing data FROM the external system INTO the OCR app.
   * Example: Importing customer records from Salesforce
   */
  supportsImport: boolean;

  /**
   * Whether the integration supports webhooks for real-time event notifications.
   * Example: Receiving QuickBooks change notifications
   */
  supportsWebhooks: boolean;

  /**
   * Whether the integration supports bulk operations for processing multiple records.
   * Example: Salesforce Bulk API for large data transfers
   */
  supportsBulkOperations: boolean;

  /**
   * Whether the integration supports file/document upload functionality.
   * Example: Uploading processed documents to SharePoint or Dropbox
   */
  supportsFileUpload: boolean;
}

/**
 * Type guard to check if a string value is a valid IntegrationType.
 * 
 * Useful for validating user input or API request parameters before
 * using them as IntegrationType enum values.
 * 
 * @param value - The string value to validate
 * @returns True if the value is a valid IntegrationType, false otherwise
 * 
 * @example
 * ```typescript
 * const userInput = 'quickbooks';
 * if (isValidIntegrationType(userInput)) {
 *   // TypeScript now knows userInput is IntegrationType
 *   const integration = createIntegration(userInput);
 * }
 * ```
 */
export function isValidIntegrationType(value: string): value is IntegrationType {
  return Object.values(IntegrationType).includes(value as IntegrationType);
}

/**
 * Get the human-readable display name for an integration type.
 * 
 * Returns the proper marketing/brand name for each integration,
 * suitable for displaying in user interfaces.
 * 
 * @param type - The integration type enum value
 * @returns The display name for the integration
 * 
 * @example
 * ```typescript
 * const displayName = getIntegrationTypeDisplayName(IntegrationType.QUICKBOOKS);
 * console.log(displayName); // "QuickBooks Online"
 * ```
 */
export function getIntegrationTypeDisplayName(type: IntegrationType): string {
  const displayNames: Record<IntegrationType, string> = {
    [IntegrationType.QUICKBOOKS]: 'QuickBooks Online',
    [IntegrationType.SALESFORCE]: 'Salesforce',
    [IntegrationType.NETSUITE]: 'NetSuite',
    [IntegrationType.SHAREPOINT]: 'Microsoft SharePoint',
    [IntegrationType.DROPBOX]: 'Dropbox',
    [IntegrationType.GOOGLE_DRIVE]: 'Google Drive',
  };
  return displayNames[type];
}

/**
 * Get the authentication method used by an integration type.
 * 
 * Returns the primary authentication mechanism required to
 * establish a connection with the external service.
 * 
 * @param type - The integration type enum value
 * @returns The authentication method: 'oauth2', 'token', or 'api_key'
 * 
 * @example
 * ```typescript
 * const authMethod = getAuthenticationMethod(IntegrationType.NETSUITE);
 * console.log(authMethod); // "token"
 * ```
 */
export function getAuthenticationMethod(
  type: IntegrationType,
): 'oauth2' | 'token' | 'api_key' {
  const authMethods: Record<IntegrationType, 'oauth2' | 'token' | 'api_key'> = {
    [IntegrationType.QUICKBOOKS]: 'oauth2',
    [IntegrationType.SALESFORCE]: 'oauth2',
    [IntegrationType.NETSUITE]: 'token',
    [IntegrationType.SHAREPOINT]: 'oauth2',
    [IntegrationType.DROPBOX]: 'oauth2',
    [IntegrationType.GOOGLE_DRIVE]: 'oauth2',
  };
  return authMethods[type];
}

/**
 * Get the list of required credential fields for an integration type.
 * 
 * Returns the array of field names that must be provided when
 * configuring an integration connection. These fields will be
 * validated and securely stored in the database.
 * 
 * @param type - The integration type enum value
 * @returns Array of required credential field names
 * 
 * @example
 * ```typescript
 * const fields = getRequiredCredentialFields(IntegrationType.QUICKBOOKS);
 * console.log(fields); // ['access_token', 'refresh_token', 'realm_id']
 * ```
 */
export function getRequiredCredentialFields(type: IntegrationType): string[] {
  const requiredFields: Record<IntegrationType, string[]> = {
    [IntegrationType.QUICKBOOKS]: ['access_token', 'refresh_token', 'realm_id'],
    [IntegrationType.SALESFORCE]: [
      'access_token',
      'refresh_token',
      'instance_url',
    ],
    [IntegrationType.NETSUITE]: [
      'consumer_key',
      'consumer_secret',
      'token_id',
      'token_secret',
      'account_id',
    ],
    [IntegrationType.SHAREPOINT]: [
      'access_token',
      'refresh_token',
      'tenant_id',
      'site_url',
    ],
    [IntegrationType.DROPBOX]: ['access_token', 'refresh_token'],
    [IntegrationType.GOOGLE_DRIVE]: [
      'access_token',
      'refresh_token',
      'client_id',
      'client_secret',
    ],
  };
  return requiredFields[type];
}

/**
 * Check if an integration type supports OAuth 2.0 authentication.
 * 
 * Convenience function to determine if an integration uses OAuth 2.0,
 * which requires a different authentication flow than token or API key methods.
 * 
 * @param type - The integration type enum value
 * @returns True if the integration supports OAuth 2.0, false otherwise
 * 
 * @example
 * ```typescript
 * if (supportsOAuth(IntegrationType.QUICKBOOKS)) {
 *   // Initiate OAuth 2.0 flow
 *   redirectToAuthorizationUrl();
 * } else {
 *   // Request manual credential input
 *   showCredentialForm();
 * }
 * ```
 */
export function supportsOAuth(type: IntegrationType): boolean {
  return getAuthenticationMethod(type) === 'oauth2';
}

/**
 * Get the operational capabilities for an integration type.
 * 
 * Returns a comprehensive object describing which features are
 * supported by the integration, helping the application determine
 * which operations can be performed.
 * 
 * @param type - The integration type enum value
 * @returns IntegrationCapabilities object with feature flags
 * 
 * @example
 * ```typescript
 * const capabilities = getIntegrationCapabilities(IntegrationType.SALESFORCE);
 * if (capabilities.supportsExport) {
 *   showExportButton();
 * }
 * if (capabilities.supportsBulkOperations) {
 *   enableBatchExport();
 * }
 * ```
 */
export function getIntegrationCapabilities(
  type: IntegrationType,
): IntegrationCapabilities {
  const capabilities: Record<IntegrationType, IntegrationCapabilities> = {
    [IntegrationType.QUICKBOOKS]: {
      supportsExport: true,
      supportsImport: false,
      supportsWebhooks: true,
      supportsBulkOperations: false,
      supportsFileUpload: true,
    },
    [IntegrationType.SALESFORCE]: {
      supportsExport: true,
      supportsImport: true,
      supportsWebhooks: true,
      supportsBulkOperations: true,
      supportsFileUpload: true,
    },
    [IntegrationType.NETSUITE]: {
      supportsExport: true,
      supportsImport: false,
      supportsWebhooks: false,
      supportsBulkOperations: true,
      supportsFileUpload: true,
    },
    [IntegrationType.SHAREPOINT]: {
      supportsExport: false,
      supportsImport: false,
      supportsWebhooks: false,
      supportsBulkOperations: false,
      supportsFileUpload: true,
    },
    [IntegrationType.DROPBOX]: {
      supportsExport: false,
      supportsImport: false,
      supportsWebhooks: true,
      supportsBulkOperations: false,
      supportsFileUpload: true,
    },
    [IntegrationType.GOOGLE_DRIVE]: {
      supportsExport: false,
      supportsImport: false,
      supportsWebhooks: true,
      supportsBulkOperations: false,
      supportsFileUpload: true,
    },
  };
  return capabilities[type];
}

/**
 * Default export for convenient importing
 * 
 * @example
 * ```typescript
 * import IntegrationType from './integration-type.enum';
 * const type = IntegrationType.QUICKBOOKS;
 * ```
 */
export default IntegrationType;
