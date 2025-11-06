/**
 * Salesforce API Connector Service
 * 
 * Implements OAuth 2.0 authentication and CRM data synchronization operations for Salesforce.
 * Provides comprehensive integration capabilities including lead management, opportunity tracking,
 * document attachments, bulk operations, and metadata retrieval using jsforce SDK v2.0.0-beta.34.
 * 
 * Key Features:
 * - OAuth 2.0 authentication flow with automatic token refresh
 * - Lead and Opportunity CRUD operations
 * - Document attachment management via ContentVersion
 * - SOQL and SOSL query execution
 * - Bulk API support for large data operations
 * - Metadata introspection for dynamic field mapping
 * - Comprehensive error handling with retry logic
 * 
 * Security: All OAuth tokens are handled securely per Section 0.7.1, never logged,
 * and encrypted before storage by the parent IntegrationsService.
 * 
 * @module SalesforceConnector
 * @see Section 0.4.6 - Salesforce Integration Requirements
 * @see Section 0.5.11 - Phase 10 Group 10C Implementation
 */

import { Injectable, Logger, BadRequestException, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as jsforce from 'jsforce';

/**
 * Salesforce OAuth credentials structure
 */
export interface SFCredentials {
  access_token: string;
  refresh_token: string;
  instance_url: string;
  environment: 'production' | 'sandbox';
}

/**
 * OAuth token response from Salesforce
 */
export interface SFTokens {
  access_token: string;
  refresh_token?: string;
  instance_url: string;
  id: string; // Identity URL
  token_type: string;
  issued_at?: string;
  signature?: string;
}

/**
 * Salesforce Lead data structure
 */
export interface LeadData {
  LastName: string; // Required
  Company: string; // Required
  Email?: string;
  Phone?: string;
  Status?: string;
  LeadSource?: string;
  FirstName?: string;
  Title?: string;
  Street?: string;
  City?: string;
  State?: string;
  PostalCode?: string;
  Country?: string;
  Description?: string;
  [key: string]: any;
}

/**
 * Salesforce Lead object response
 */
export interface SFLead extends LeadData {
  Id: string;
  IsConverted?: boolean;
  ConvertedDate?: string;
  ConvertedAccountId?: string;
  ConvertedContactId?: string;
  ConvertedOpportunityId?: string;
}

/**
 * Lead conversion parameters
 */
export interface LeadConversion {
  convertedStatus: string;
  accountId?: string;
  contactId?: string;
  opportunityName?: string;
  doNotCreateOpportunity?: boolean;
  overwriteLeadSource?: boolean;
  sendNotificationEmail?: boolean;
}

/**
 * Lead conversion result
 */
export interface ConversionResult {
  success: boolean;
  leadId: string;
  accountId?: string;
  contactId?: string;
  opportunityId?: string;
  errors?: any[];
}

/**
 * Salesforce Opportunity data structure
 */
export interface OpportunityData {
  Name: string; // Required
  StageName: string; // Required
  CloseDate: string; // Required (YYYY-MM-DD format)
  Amount?: number;
  AccountId?: string;
  Probability?: number;
  Type?: string;
  LeadSource?: string;
  Description?: string;
  NextStep?: string;
  [key: string]: any;
}

/**
 * Salesforce Opportunity object response
 */
export interface SFOpportunity extends OpportunityData {
  Id: string;
  IsClosed?: boolean;
  IsWon?: boolean;
  ForecastCategory?: string;
}

/**
 * Document attachment structure for ContentVersion
 */
export interface DocumentAttachment {
  Title: string;
  VersionData: string; // base64 encoded file content
  PathOnClient: string; // Original file name with extension
  FirstPublishLocationId?: string; // Record Id to link to (optional)
  ContentLocation?: string; // 'S' for Salesforce
  Origin?: string; // 'H' for uploaded
}

/**
 * Attachment data structure (legacy or ContentVersion)
 */
export interface AttachmentData {
  ParentId?: string; // For legacy Attachment
  Name: string;
  Body: string; // base64 encoded
  ContentType?: string;
  Description?: string;
}

/**
 * Bulk API operation result
 */
export interface BulkResult {
  id: string;
  success: boolean;
  successfulResults: any[];
  failedResults: any[];
  errors?: any[];
}

/**
 * Salesforce object metadata description
 */
export interface ObjectDescribe {
  name: string;
  label: string;
  labelPlural: string;
  fields: FieldDescribe[];
  recordTypeInfos: RecordTypeInfo[];
  createable: boolean;
  updateable: boolean;
  deletable: boolean;
  queryable: boolean;
  custom: boolean;
}

/**
 * Field metadata description
 */
export interface FieldDescribe {
  name: string;
  label: string;
  type: string;
  length?: number;
  precision?: number;
  scale?: number;
  createable: boolean;
  updateable: boolean;
  nillable: boolean;
  picklistValues?: PicklistValue[];
  referenceTo?: string[];
}

/**
 * Picklist value structure
 */
export interface PicklistValue {
  value: string;
  label: string;
  active: boolean;
  defaultValue: boolean;
}

/**
 * Record type information
 */
export interface RecordTypeInfo {
  recordTypeId: string;
  name: string;
  active: boolean;
  defaultRecordTypeMapping: boolean;
}

/**
 * Object information for global describe
 */
export interface ObjectInfo {
  name: string;
  label: string;
  custom: boolean;
  queryable: boolean;
  createable: boolean;
  updateable: boolean;
}

/**
 * SOSL search result structure
 */
export interface SearchResult {
  searchRecords: any[];
}

/**
 * Connection test result
 */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
  organizationId?: string;
  organizationName?: string;
  username?: string;
}

/**
 * Document structure for transformation methods
 */
export interface Document {
  id: string;
  document_name: string;
  document_type: string;
  extracted_fields: Record<string, any>;
  confidence_score: number;
  processed_at: Date;
}

/**
 * Salesforce API Connector
 * 
 * Provides comprehensive Salesforce CRM integration capabilities including OAuth authentication,
 * data synchronization, bulk operations, and metadata management.
 */
@Injectable()
export class SalesforceConnector {
  private readonly logger = new Logger(SalesforceConnector.name);
  private sfConnections: Map<string, jsforce.Connection> = new Map(); // Cache SF connections by accountId

  constructor(private readonly configService: ConfigService) {
    this.logger.log('Salesforce Connector initialized');
  }

  /**
   * Generate Salesforce OAuth 2.0 authorization URL
   * 
   * Creates the authorization URL that users must visit to grant access to their Salesforce account.
   * Supports both Production (login.salesforce.com) and Sandbox (test.salesforce.com) environments.
   * 
   * @param accountId - Internal account identifier for connection caching
   * @param state - CSRF protection token
   * @param environment - Salesforce environment type (production or sandbox)
   * @returns Authorization URL for user consent
   */
  getAuthorizationUrl(accountId: string, state: string, environment: 'production' | 'sandbox' = 'production'): string {
    const clientId = this.configService.get<string>('SALESFORCE_CLIENT_ID');
    const redirectUri = this.configService.get<string>('SALESFORCE_CALLBACK_URL');
    
    if (!clientId || !redirectUri) {
      this.logger.error('Salesforce OAuth configuration missing');
      throw new BadRequestException('Salesforce integration is not properly configured');
    }

    // Determine authorization endpoint based on environment
    const authEndpoint = environment === 'sandbox'
      ? 'https://test.salesforce.com/services/oauth2/authorize'
      : 'https://login.salesforce.com/services/oauth2/authorize';

    // Build authorization URL with required parameters
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      redirect_uri: redirectUri,
      state: state,
      scope: 'api refresh_token full', // api=REST API access, refresh_token=offline access, full=full access
      prompt: 'consent', // Force consent screen to ensure refresh token is issued
    });

    const authUrl = `${authEndpoint}?${params.toString()}`;
    
    this.logger.log(`Generated Salesforce authorization URL for account ${accountId}`);
    return authUrl;
  }

  /**
   * Exchange authorization code for access and refresh tokens
   * 
   * After user grants consent, Salesforce redirects back with an authorization code.
   * This method exchanges that code for access tokens required for API calls.
   * 
   * @param authorizationCode - Authorization code from OAuth callback
   * @param redirectUri - Must match the redirect URI used in authorization URL
   * @param environment - Salesforce environment type
   * @returns OAuth tokens including access_token, refresh_token, and instance_url
   */
  async exchangeCodeForTokens(
    authorizationCode: string,
    redirectUri: string,
    environment: 'production' | 'sandbox' = 'production'
  ): Promise<SFTokens> {
    const clientId = this.configService.get<string>('SALESFORCE_CLIENT_ID');
    const clientSecret = this.configService.get<string>('SALESFORCE_CLIENT_SECRET');

    if (!clientId || !clientSecret) {
      this.logger.error('Salesforce OAuth credentials missing');
      throw new BadRequestException('Salesforce integration credentials are not configured');
    }

    // Determine token endpoint based on environment
    const tokenEndpoint = environment === 'sandbox'
      ? 'https://test.salesforce.com/services/oauth2/token'
      : 'https://login.salesforce.com/services/oauth2/token';

    try {
      // Make POST request to token endpoint
      const response = await fetch(tokenEndpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({
          grant_type: 'authorization_code',
          code: authorizationCode,
          client_id: clientId,
          client_secret: clientSecret,
          redirect_uri: redirectUri,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        this.logger.error('Token exchange failed', errorData);
        throw new BadRequestException(`Failed to exchange authorization code: ${errorData.error_description || errorData.error}`);
      }

      const tokens: SFTokens = await response.json();

      this.logger.log('Successfully exchanged authorization code for tokens');
      // Note: Never log the actual token values per Section 0.7.1 security requirements

      return tokens;
    } catch (error) {
      this.logger.error('Error during token exchange', error);
      if (error instanceof BadRequestException) {
        throw error;
      }
      throw new BadRequestException('Failed to obtain Salesforce access tokens');
    }
  }

  /**
   * Refresh expired access token using refresh token
   * 
   * Access tokens expire after a period (typically 2 hours). Use this method to obtain
   * a new access token without requiring user interaction. Refresh tokens in Salesforce
   * do not rotate, so the same refresh token can be reused.
   * 
   * @param refreshToken - Valid refresh token from initial authorization
   * @param instanceUrl - Salesforce instance URL (e.g., https://na1.salesforce.com)
   * @param environment - Salesforce environment type
   * @returns New access token (refresh token remains the same)
   */
  async refreshAccessToken(
    refreshToken: string,
    instanceUrl: string,
    environment: 'production' | 'sandbox' = 'production'
  ): Promise<SFTokens> {
    const clientId = this.configService.get<string>('SALESFORCE_CLIENT_ID');
    const clientSecret = this.configService.get<string>('SALESFORCE_CLIENT_SECRET');

    if (!clientId || !clientSecret) {
      this.logger.error('Salesforce OAuth credentials missing');
      throw new BadRequestException('Salesforce integration credentials are not configured');
    }

    // Determine token endpoint based on environment
    const tokenEndpoint = environment === 'sandbox'
      ? 'https://test.salesforce.com/services/oauth2/token'
      : 'https://login.salesforce.com/services/oauth2/token';

    try {
      const response = await fetch(tokenEndpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({
          grant_type: 'refresh_token',
          refresh_token: refreshToken,
          client_id: clientId,
          client_secret: clientSecret,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        this.logger.error('Token refresh failed', errorData);
        
        // If refresh token is invalid/revoked, user must re-authorize
        if (errorData.error === 'invalid_grant') {
          throw new UnauthorizedException('Refresh token is invalid or revoked. User must re-authorize.');
        }
        
        throw new BadRequestException(`Failed to refresh access token: ${errorData.error_description || errorData.error}`);
      }

      const tokens: SFTokens = await response.json();
      
      // Salesforce doesn't return a new refresh token, use the existing one
      if (!tokens.refresh_token) {
        tokens.refresh_token = refreshToken;
      }

      // Preserve instance URL if not returned
      if (!tokens.instance_url) {
        tokens.instance_url = instanceUrl;
      }

      this.logger.log('Successfully refreshed access token');

      return tokens;
    } catch (error) {
      this.logger.error('Error during token refresh', error);
      if (error instanceof UnauthorizedException || error instanceof BadRequestException) {
        throw error;
      }
      throw new BadRequestException('Failed to refresh Salesforce access token');
    }
  }

  /**
   * Revoke access or refresh token
   * 
   * Called when user disconnects Salesforce integration or when revoking access.
   * This invalidates the token and prevents further API access.
   * 
   * @param token - Access token or refresh token to revoke
   * @param environment - Salesforce environment type
   */
  async revokeToken(token: string, environment: 'production' | 'sandbox' = 'production'): Promise<void> {
    // Determine revoke endpoint based on environment
    const revokeEndpoint = environment === 'sandbox'
      ? 'https://test.salesforce.com/services/oauth2/revoke'
      : 'https://login.salesforce.com/services/oauth2/revoke';

    try {
      const response = await fetch(revokeEndpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({
          token: token,
        }),
      });

      if (!response.ok) {
        const errorText = await response.text();
        this.logger.warn('Token revocation failed', errorText);
        // Don't throw error - token might already be invalid
      }

      this.logger.log('Successfully revoked Salesforce token');
    } catch (error) {
      this.logger.error('Error during token revocation', error);
      // Don't throw - revocation is best-effort
    }
  }

  /**
   * Create authenticated Salesforce connection
   * 
   * Establishes a jsforce Connection instance with automatic token refresh capability.
   * Connections are cached by account ID for reuse within the same session.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param accountId - Internal account ID for connection caching (optional)
   * @returns Configured jsforce Connection instance
   */
  connect(credentials: SFCredentials, accountId?: string): jsforce.Connection {
    const clientId = this.configService.get<string>('SALESFORCE_CLIENT_ID');
    const clientSecret = this.configService.get<string>('SALESFORCE_CLIENT_SECRET');
    const redirectUri = this.configService.get<string>('SALESFORCE_CALLBACK_URL');

    // Check if we have a cached connection for this account
    if (accountId && this.sfConnections.has(accountId)) {
      const cachedConnection = this.sfConnections.get(accountId);
      if (cachedConnection) {
        // Update access token in case it was refreshed
        cachedConnection.accessToken = credentials.access_token;
        cachedConnection.instanceUrl = credentials.instance_url;
        return cachedConnection;
      }
    }

    // Create new OAuth2 configuration
    const oauth2 = new jsforce.OAuth2({
      clientId: clientId,
      clientSecret: clientSecret,
      redirectUri: redirectUri,
      loginUrl: credentials.environment === 'sandbox'
        ? 'https://test.salesforce.com'
        : 'https://login.salesforce.com',
    });

    // Create connection with OAuth credentials
    const connection = new jsforce.Connection({
      oauth2: oauth2,
      instanceUrl: credentials.instance_url,
      accessToken: credentials.access_token,
      refreshToken: credentials.refresh_token,
      version: '59.0', // API version per Section 0.4.6
    });

    // Set up automatic token refresh callback
    connection.on('refresh', async (_accessToken: string, _res: any) => {
      this.logger.log('Salesforce access token was refreshed automatically');
      // Token refresh is handled automatically by jsforce
      // Parent service should persist the new token if needed
    });

    // Cache connection if account ID provided
    if (accountId) {
      this.sfConnections.set(accountId, connection);
    }

    this.logger.log('Created new Salesforce connection');
    return connection;
  }

  /**
   * Test Salesforce connection validity
   * 
   * Verifies that credentials are valid by making a test API call.
   * Used for credential validation during integration setup.
   * 
   * @param credentials - Salesforce OAuth credentials to test
   * @returns Connection test result with organization information
   */
  async testConnection(credentials: SFCredentials): Promise<ConnectionTestResult> {
    try {
      const connection = this.connect(credentials);

      // Test connection by fetching user identity
      const identity = await connection.identity();

      // Also fetch organization details
      const orgQuery = await connection.query('SELECT Id, Name FROM Organization LIMIT 1');
      const orgName = orgQuery.records.length > 0 ? (orgQuery.records[0] as any).Name : 'Unknown';
      const orgId = orgQuery.records.length > 0 ? (orgQuery.records[0] as any).Id : undefined;

      this.logger.log('Salesforce connection test successful');

      return {
        success: true,
        message: `Successfully connected to Salesforce organization: ${orgName}`,
        organizationId: orgId,
        organizationName: orgName,
        username: identity.username,
      };
    } catch (error) {
      this.logger.error('Salesforce connection test failed', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      return {
        success: false,
        message: `Connection test failed: ${errorMessage}`,
      };
    }
  }

  /**
   * Create Lead in Salesforce
   * 
   * Creates a new Lead record from OCR-extracted data. Lead is a potential customer
   * that hasn't been qualified yet.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param leadData - Lead information with required LastName and Company
   * @returns Created Lead record with Salesforce Id
   */
  async createLead(credentials: SFCredentials, leadData: LeadData): Promise<SFLead> {
    try {
      const connection = this.connect(credentials);

      // Validate required fields
      if (!leadData.LastName || !leadData.Company) {
        throw new BadRequestException('LastName and Company are required fields for Lead creation');
      }

      // Create Lead record
      const result = await connection.sobject('Lead').create(leadData);

      if (!result.success) {
        throw new BadRequestException(`Failed to create Lead: ${JSON.stringify(result.errors)}`);
      }

      this.logger.log(`Created Lead with Id: ${result.id}`);

      // Fetch and return the created Lead
      const createdLead = await connection.sobject('Lead').retrieve(result.id);

      return createdLead as SFLead;
    } catch (error) {
      return this.handleSFError(error, 'createLead');
    }
  }

  /**
   * Update existing Lead record
   * 
   * Updates fields on an existing Lead record. Commonly used to correct OCR extraction
   * errors or add additional information.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param leadId - Salesforce Lead Id
   * @param updateData - Fields to update (partial Lead data)
   */
  async updateLead(credentials: SFCredentials, leadId: string, updateData: Partial<LeadData>): Promise<void> {
    try {
      const connection = this.connect(credentials);

      // Add Id to update data
      const dataWithId = { Id: leadId, ...updateData };

      // Update Lead record
      const result = await connection.sobject('Lead').update(dataWithId);

      if (!result.success) {
        throw new BadRequestException(`Failed to update Lead: ${JSON.stringify(result.errors)}`);
      }

      this.logger.log(`Updated Lead with Id: ${leadId}`);
    } catch (error) {
      return this.handleSFError(error, 'updateLead');
    }
  }

  /**
   * Convert Lead to Account, Contact, and Opportunity
   * 
   * When a Lead is qualified, it can be converted to create an Account (company),
   * Contact (person), and optionally an Opportunity (sales deal).
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param leadId - Salesforce Lead Id to convert
   * @param conversionData - Conversion parameters
   * @returns Conversion result with created record Ids
   */
  async convertLead(credentials: SFCredentials, leadId: string, conversionData: LeadConversion): Promise<ConversionResult> {
    try {
      const connection = this.connect(credentials);

      // Build conversion request
      const convertRequest: any = {
        leadId: leadId,
        convertedStatus: conversionData.convertedStatus,
        doNotCreateOpportunity: conversionData.doNotCreateOpportunity || false,
        sendNotificationEmail: conversionData.sendNotificationEmail || false,
        overwriteLeadSource: conversionData.overwriteLeadSource || false,
      };

      // Add optional fields
      if (conversionData.accountId) convertRequest.accountId = conversionData.accountId;
      if (conversionData.contactId) convertRequest.contactId = conversionData.contactId;
      if (conversionData.opportunityName) convertRequest.opportunityName = conversionData.opportunityName;

      // Perform Lead conversion using REST API
      const result: any = await connection.requestPost('/services/data/v59.0/process/conversions', convertRequest);

      if (!result.success) {
        return {
          success: false,
          leadId: leadId,
          errors: result.errors || [],
        };
      }

      this.logger.log(`Converted Lead ${leadId} successfully`);

      return {
        success: true,
        leadId: leadId,
        accountId: result.accountId,
        contactId: result.contactId,
        opportunityId: result.opportunityId,
      };
    } catch (error) {
      this.logger.error('Error converting Lead', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      throw new BadRequestException(`Failed to convert Lead: ${errorMessage}`);
    }
  }

  /**
   * Create Opportunity in Salesforce
   * 
   * Creates a new Opportunity record representing a potential sales deal.
   * Often created from OCR-processed business proposals or quotes.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param opportunityData - Opportunity information with required fields
   * @returns Created Opportunity record with Salesforce Id
   */
  async createOpportunity(credentials: SFCredentials, opportunityData: OpportunityData): Promise<SFOpportunity> {
    try {
      const connection = this.connect(credentials);

      // Validate required fields
      if (!opportunityData.Name || !opportunityData.StageName || !opportunityData.CloseDate) {
        throw new BadRequestException('Name, StageName, and CloseDate are required fields for Opportunity creation');
      }

      // Validate CloseDate format (YYYY-MM-DD)
      const datePattern = /^\d{4}-\d{2}-\d{2}$/;
      if (!datePattern.test(opportunityData.CloseDate)) {
        throw new BadRequestException('CloseDate must be in YYYY-MM-DD format');
      }

      // Create Opportunity record
      const result = await connection.sobject('Opportunity').create(opportunityData);

      if (!result.success) {
        throw new BadRequestException(`Failed to create Opportunity: ${JSON.stringify(result.errors)}`);
      }

      this.logger.log(`Created Opportunity with Id: ${result.id}`);

      // Fetch and return the created Opportunity
      const createdOpportunity = await connection.sobject('Opportunity').retrieve(result.id);

      return createdOpportunity as SFOpportunity;
    } catch (error) {
      return this.handleSFError(error, 'createOpportunity');
    }
  }

  /**
   * Update existing Opportunity record
   * 
   * Updates fields on an existing Opportunity record. Commonly used to update
   * stage, amount, or close date as the deal progresses.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param opportunityId - Salesforce Opportunity Id
   * @param updateData - Fields to update (partial Opportunity data)
   */
  async updateOpportunity(credentials: SFCredentials, opportunityId: string, updateData: Partial<OpportunityData>): Promise<void> {
    try {
      const connection = this.connect(credentials);

      // Validate CloseDate format if provided
      if (updateData.CloseDate) {
        const datePattern = /^\d{4}-\d{2}-\d{2}$/;
        if (!datePattern.test(updateData.CloseDate)) {
          throw new BadRequestException('CloseDate must be in YYYY-MM-DD format');
        }
      }

      // Add Id to update data
      const dataWithId = { Id: opportunityId, ...updateData };

      // Update Opportunity record
      const result = await connection.sobject('Opportunity').update(dataWithId);

      if (!result.success) {
        throw new BadRequestException(`Failed to update Opportunity: ${JSON.stringify(result.errors)}`);
      }

      this.logger.log(`Updated Opportunity with Id: ${opportunityId}`);
    } catch (error) {
      return this.handleSFError(error, 'updateOpportunity');
    }
  }

  /**
   * Attach document to Opportunity using ContentVersion
   * 
   * Uploads an OCR-processed document and links it to an Opportunity record.
   * Uses Salesforce Files (ContentVersion) for modern file storage.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param opportunityId - Salesforce Opportunity Id
   * @param documentData - Document attachment with base64 content
   * @returns ContentDocument Id of the attached document
   */
  async attachDocumentToOpportunity(
    credentials: SFCredentials,
    opportunityId: string,
    documentData: DocumentAttachment
  ): Promise<string> {
    try {
      const connection = this.connect(credentials);

      // Step 1: Create ContentVersion (the file)
      const contentVersion: any = {
        Title: documentData.Title,
        PathOnClient: documentData.PathOnClient,
        VersionData: documentData.VersionData, // base64 encoded
        ContentLocation: 'S', // S = Stored in Salesforce
        Origin: 'H', // H = Uploaded
      };

      if (documentData.FirstPublishLocationId) {
        contentVersion.FirstPublishLocationId = documentData.FirstPublishLocationId;
      }

      const cvResultRaw = await connection.sobject('ContentVersion').create(contentVersion);
      const cvResult = Array.isArray(cvResultRaw) ? cvResultRaw[0] : cvResultRaw;

      if (!cvResult) {
        throw new BadRequestException('Failed to create ContentVersion: No result returned');
      }

      if (!cvResult.success) {
        throw new BadRequestException(`Failed to create ContentVersion: ${JSON.stringify(cvResult.errors)}`);
      }

      this.logger.log(`Created ContentVersion with Id: ${cvResult.id}`);

      // Step 2: Query for ContentDocumentId
      const cvQuery = await connection.sobject('ContentVersion')
        .select('ContentDocumentId')
        .where({ Id: cvResult.id })
        .execute();

      if (cvQuery.length === 0) {
        throw new BadRequestException('Failed to retrieve ContentDocumentId');
      }

      const contentDocumentId = (cvQuery[0] as any).ContentDocumentId;

      // Step 3: Create ContentDocumentLink to link document to Opportunity
      const cdLink: any = {
        ContentDocumentId: contentDocumentId,
        LinkedEntityId: opportunityId,
        ShareType: 'V', // V = Viewer permission
        Visibility: 'AllUsers', // AllUsers = visible to all org users
      };

      const cdlResultRaw = await connection.sobject('ContentDocumentLink').create(cdLink);
      const cdlResult = Array.isArray(cdlResultRaw) ? cdlResultRaw[0] : cdlResultRaw;

      if (!cdlResult) {
        this.logger.warn('Failed to create ContentDocumentLink: No result returned');
      } else if (!cdlResult.success) {
        this.logger.warn(`Failed to create ContentDocumentLink: ${JSON.stringify(cdlResult.errors)}`);
        // Don't throw - document was created, just not linked
      }

      this.logger.log(`Attached document ${contentDocumentId} to Opportunity ${opportunityId}`);

      return contentDocumentId;
    } catch (error) {
      return this.handleSFError(error, 'attachDocumentToOpportunity');
    }
  }

  /**
   * Upload attachment to Salesforce
   * 
   * Generic method to upload documents as either legacy Attachment or ContentVersion.
   * ContentVersion is preferred for modern Salesforce orgs.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param attachment - Attachment data with base64 content
   * @returns Id of created attachment (ContentVersionId or AttachmentId)
   */
  async uploadAttachment(credentials: SFCredentials, attachment: AttachmentData): Promise<string> {
    try {
      const connection = this.connect(credentials);

      // Use ContentVersion for modern file storage
      const contentVersion: any = {
        Title: attachment.Name,
        PathOnClient: attachment.Name,
        VersionData: attachment.Body, // base64 encoded
        ContentLocation: 'S',
        Origin: 'H',
      };

      if (attachment.ParentId) {
        contentVersion.FirstPublishLocationId = attachment.ParentId;
      }

      if (attachment.Description) {
        contentVersion.Description = attachment.Description;
      }

      const resultRaw = await connection.sobject('ContentVersion').create(contentVersion);
      const result = Array.isArray(resultRaw) ? resultRaw[0] : resultRaw;

      if (!result) {
        throw new BadRequestException('Failed to upload attachment: No result returned');
      }

      if (!result.success) {
        throw new BadRequestException(`Failed to upload attachment: ${JSON.stringify(result.errors)}`);
      }

      this.logger.log(`Uploaded attachment with ContentVersionId: ${result.id}`);

      return result.id;
    } catch (error) {
      return this.handleSFError(error, 'uploadAttachment');
    }
  }

  /**
   * Retrieve attachment content
   * 
   * Downloads the binary content of an attachment or ContentVersion.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param attachmentId - ContentVersion Id or Attachment Id
   * @returns File content as Buffer
   */
  async getAttachment(credentials: SFCredentials, attachmentId: string): Promise<Buffer> {
    try {
      const connection = this.connect(credentials);

      // Try ContentVersion first (modern approach)
      try {
        const cvRecord: any = await connection.sobject('ContentVersion').retrieve(attachmentId);
        
        if (cvRecord && cvRecord.VersionData) {
          // VersionData is base64 encoded in REST API responses
          return Buffer.from(cvRecord.VersionData, 'base64');
        }
      } catch (cvError) {
        // If ContentVersion fails, try legacy Attachment
        this.logger.warn('Failed to retrieve as ContentVersion, trying Attachment');
      }

      // Try legacy Attachment
      const attachment: any = await connection.sobject('Attachment').retrieve(attachmentId);
      
      if (attachment && attachment.Body) {
        return Buffer.from(attachment.Body, 'base64');
      }

      throw new BadRequestException('Attachment not found or has no content');
    } catch (error) {
      return this.handleSFError(error, 'getAttachment');
    }
  }

  /**
   * Execute SOQL query
   * 
   * Executes a Salesforce Object Query Language (SOQL) query to retrieve records.
   * Automatically handles pagination for queries returning more than 2000 records.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param soql - SOQL query string
   * @returns Array of query results
   */
  async query<T = any>(credentials: SFCredentials, soql: string): Promise<T[]> {
    try {
      const connection = this.connect(credentials);

      // Execute query with automatic pagination handling
      const result = await connection.query(soql) as any;

      let records = result.records;

      // Handle pagination if there are more records (more than 2000)
      let nextRecordsUrl = result.nextRecordsUrl;
      while (nextRecordsUrl && !result.done) {
        const moreResult = await connection.queryMore(nextRecordsUrl) as any;
        records = records.concat(moreResult.records);
        nextRecordsUrl = moreResult.nextRecordsUrl;
      }

      this.logger.log(`Query executed successfully, returned ${records.length} records`);

      return records;
    } catch (error) {
      return this.handleSFError(error, 'query');
    }
  }

  /**
   * Execute SOQL query including deleted/archived records
   * 
   * Executes a SOQL query that includes soft-deleted and archived records.
   * Useful for data recovery or complete data exports.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param soql - SOQL query string
   * @returns Array of query results including deleted records
   */
  async queryAll<T = any>(credentials: SFCredentials, soql: string): Promise<T[]> {
    try {
      const connection = this.connect(credentials);

      // Execute queryAll to include deleted records
      // Use type assertion as queryAll might not be in all jsforce versions
      const result = await (connection as any).queryAll(soql) as any;

      let records = result.records;

      // Handle pagination
      let nextRecordsUrl = result.nextRecordsUrl;
      while (nextRecordsUrl && !result.done) {
        const moreResult = await connection.queryMore(nextRecordsUrl) as any;
        records = records.concat(moreResult.records);
        nextRecordsUrl = moreResult.nextRecordsUrl;
      }

      this.logger.log(`QueryAll executed successfully, returned ${records.length} records (including deleted)`);

      return records;
    } catch (error) {
      return this.handleSFError(error, 'queryAll');
    }
  }

  /**
   * Execute SOSL search
   * 
   * Executes a Salesforce Object Search Language (SOSL) query for full-text search
   * across multiple objects and fields.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param sosl - SOSL search string
   * @returns Search results grouped by object type
   */
  async search(credentials: SFCredentials, sosl: string): Promise<SearchResult[]> {
    try {
      const connection = this.connect(credentials);

      // Execute SOSL search
      const result = await connection.search(sosl) as any;

      this.logger.log(`SOSL search executed successfully`);

      // jsforce returns the search records directly as an array
      // Wrap in SearchResult structure for consistency with our interface
      return [{ searchRecords: result.searchRecords || result }];
    } catch (error) {
      return this.handleSFError(error, 'search');
    }
  }

  /**
   * Bulk insert records using Bulk API
   * 
   * Uses Salesforce Bulk API v1 for inserting large numbers of records (>200).
   * Processes records asynchronously and provides detailed success/failure results.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param objectType - Salesforce object API name (e.g., 'Lead', 'Opportunity')
   * @param records - Array of records to insert
   * @returns Bulk operation result with success/failure details
   */
  async bulkInsert<T = any>(credentials: SFCredentials, objectType: string, records: T[]): Promise<BulkResult> {
    try {
      const connection = this.connect(credentials);

      if (records.length === 0) {
        throw new BadRequestException('No records provided for bulk insert');
      }

      this.logger.log(`Starting bulk insert of ${records.length} ${objectType} records`);

      // Create bulk job
      const job = connection.bulk.createJob(objectType, 'insert');

      // Create batch
      const batch = job.createBatch();
      batch.execute(records as any);

      // Poll for completion (5 second interval, 60 second timeout)
      await batch.poll(5000, 60000);

      // Retrieve results
      const results = await batch.retrieve();

      // Separate successful and failed results
      const successfulResults: any[] = [];
      const failedResults: any[] = [];

      results.forEach((result: any, index: number) => {
        if (result.success) {
          successfulResults.push({
            id: result.id,
            record: records[index],
          });
        } else {
          failedResults.push({
            record: records[index],
            errors: result.errors,
          });
        }
      });

      this.logger.log(`Bulk insert completed: ${successfulResults.length} successful, ${failedResults.length} failed`);

      return {
        id: job.id || 'unknown',
        success: failedResults.length === 0,
        successfulResults,
        failedResults,
      };
    } catch (error) {
      return this.handleSFError(error, 'bulkInsert');
    }
  }

  /**
   * Bulk update records using Bulk API
   * 
   * Uses Salesforce Bulk API v1 for updating large numbers of records (>200).
   * Each record must include an 'Id' field.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param objectType - Salesforce object API name
   * @param records - Array of records to update (must include Id field)
   * @returns Bulk operation result with success/failure details
   */
  async bulkUpdate<T = any>(credentials: SFCredentials, objectType: string, records: T[]): Promise<BulkResult> {
    try {
      const connection = this.connect(credentials);

      if (records.length === 0) {
        throw new BadRequestException('No records provided for bulk update');
      }

      // Validate all records have Id field
      const invalidRecords = records.filter((record: any) => !record.Id);
      if (invalidRecords.length > 0) {
        throw new BadRequestException(`${invalidRecords.length} records missing required Id field`);
      }

      this.logger.log(`Starting bulk update of ${records.length} ${objectType} records`);

      // Create bulk job
      const job = connection.bulk.createJob(objectType, 'update');

      // Create batch
      const batch = job.createBatch();
      batch.execute(records as any);

      // Poll for completion
      await batch.poll(5000, 60000);

      // Retrieve results
      const results = await batch.retrieve();

      // Separate successful and failed results
      const successfulResults: any[] = [];
      const failedResults: any[] = [];

      results.forEach((result: any, index: number) => {
        if (result.success) {
          successfulResults.push({
            id: result.id,
            record: records[index],
          });
        } else {
          failedResults.push({
            record: records[index],
            errors: result.errors,
          });
        }
      });

      this.logger.log(`Bulk update completed: ${successfulResults.length} successful, ${failedResults.length} failed`);

      return {
        id: job.id || 'unknown',
        success: failedResults.length === 0,
        successfulResults,
        failedResults,
      };
    } catch (error) {
      return this.handleSFError(error, 'bulkUpdate');
    }
  }

  /**
   * Describe Salesforce object metadata
   * 
   * Retrieves detailed metadata about a Salesforce object including fields,
   * relationships, record types, and permissions. Used for dynamic field mapping.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @param objectName - Salesforce object API name
   * @returns Object metadata description
   */
  async describeObject(credentials: SFCredentials, objectName: string): Promise<ObjectDescribe> {
    try {
      const connection = this.connect(credentials);

      // Get object describe metadata
      const describe = await connection.sobject(objectName).describe();

      this.logger.log(`Retrieved metadata for object: ${objectName}`);

      return describe as unknown as ObjectDescribe;
    } catch (error) {
      return this.handleSFError(error, 'describeObject');
    }
  }

  /**
   * List all available Salesforce objects
   * 
   * Retrieves a list of all objects accessible to the authenticated user.
   * Includes both standard and custom objects.
   * 
   * @param credentials - Salesforce OAuth credentials
   * @returns Array of object information
   */
  async listObjects(credentials: SFCredentials): Promise<ObjectInfo[]> {
    try {
      const connection = this.connect(credentials);

      // Get global describe (all objects)
      const globalDescribe = await connection.describeGlobal();

      // Map to simplified object info structure
      const objects: ObjectInfo[] = globalDescribe.sobjects.map((obj: any) => ({
        name: obj.name,
        label: obj.label,
        custom: obj.custom,
        queryable: obj.queryable,
        createable: obj.createable,
        updateable: obj.updateable,
      }));

      this.logger.log(`Retrieved ${objects.length} Salesforce objects`);

      return objects;
    } catch (error) {
      return this.handleSFError(error, 'listObjects');
    }
  }

  /**
   * Transform OCR document to Salesforce Lead
   * 
   * Converts OCR-extracted document data to Salesforce Lead structure.
   * Maps common fields and handles required field defaults.
   * 
   * @param document - OCR-processed document
   * @returns Salesforce Lead data structure
   */
  transformDocumentToLead(document: Document): LeadData {
    const fields = document.extracted_fields || {};

    // Build Lead data from extracted fields
    const leadData: LeadData = {
      LastName: fields.last_name || fields.name || fields.contact_name || 'Unknown',
      Company: fields.company || fields.organization || fields.company_name || 'Unknown',
      FirstName: fields.first_name || undefined,
      Email: fields.email || fields.email_address || undefined,
      Phone: fields.phone || fields.phone_number || fields.telephone || undefined,
      Title: fields.title || fields.job_title || undefined,
      Status: 'Open - Not Contacted', // Default lead status
      LeadSource: 'OCR Document', // Indicate source
      Description: `Extracted from document: ${document.document_name}\nDocument Type: ${document.document_type}\nConfidence: ${document.confidence_score}%`,
    };

    // Map address fields if available
    if (fields.street || fields.address) {
      leadData.Street = fields.street || fields.address;
    }
    if (fields.city) {
      leadData.City = fields.city;
    }
    if (fields.state || fields.province) {
      leadData.State = fields.state || fields.province;
    }
    if (fields.postal_code || fields.zip_code || fields.zip) {
      leadData.PostalCode = fields.postal_code || fields.zip_code || fields.zip;
    }
    if (fields.country) {
      leadData.Country = fields.country;
    }

    this.logger.log(`Transformed document ${document.id} to Lead data`);

    return leadData;
  }

  /**
   * Transform OCR document to Salesforce Opportunity
   * 
   * Converts OCR-extracted business proposal or quote to Salesforce Opportunity structure.
   * Maps common fields and sets appropriate defaults for sales pipeline.
   * 
   * @param document - OCR-processed document (proposal/quote)
   * @returns Salesforce Opportunity data structure
   */
  transformDocumentToOpportunity(document: Document): OpportunityData {
    const fields = document.extracted_fields || {};

    // Calculate close date (default to 30 days from now if not specified)
    let closeDate = new Date();
    if (fields.due_date || fields.expiry_date || fields.close_date) {
      try {
        closeDate = new Date(fields.due_date || fields.expiry_date || fields.close_date);
      } catch (e) {
        // Use default if date parsing fails
      }
    } else {
      closeDate.setDate(closeDate.getDate() + 30);
    }

    // Format close date as YYYY-MM-DD
    const closeDateStr: string = closeDate.toISOString().split('T')[0]!;

    // Parse amount from string if necessary
    let amount: number | undefined = undefined;
    const rawAmount = fields.amount || fields.total || fields.total_amount;
    if (rawAmount !== undefined) {
      if (typeof rawAmount === 'string') {
        const amountStr = rawAmount.replace(/[^0-9.-]/g, '');
        amount = parseFloat(amountStr) || undefined;
      } else if (typeof rawAmount === 'number') {
        amount = rawAmount;
      }
    }

    // Build Opportunity data from extracted fields
    const opportunityData: OpportunityData = {
      Name: fields.opportunity_name || fields.project_name || document.document_name || 'OCR Opportunity',
      StageName: 'Prospecting', // Default stage for new opportunities
      CloseDate: closeDateStr,
      Amount: amount,
      Type: fields.opportunity_type || 'New Business',
      LeadSource: 'OCR Document',
      Description: `Extracted from document: ${document.document_name}\nDocument Type: ${document.document_type}\nConfidence: ${document.confidence_score}%`,
      NextStep: 'Review extracted data and qualify opportunity',
    };

    // Set probability based on stage
    opportunityData.Probability = 10; // 10% for Prospecting stage

    this.logger.log(`Transformed document ${document.id} to Opportunity data`);

    return opportunityData;
  }

  /**
   * Handle Salesforce API errors
   * 
   * Parses Salesforce error responses and throws appropriate NestJS exceptions.
   * Handles common error codes like invalid session, rate limits, and validation errors.
   * 
   * @param error - Error object from Salesforce API
   * @param operation - Name of the operation that failed (for logging)
   * @throws Appropriate NestJS exception based on error type
   */
  private handleSFError(error: any, operation: string): never {
    this.logger.error(`Salesforce ${operation} operation failed`, error);

    // Handle jsforce errors
    if (error.errorCode) {
      switch (error.errorCode) {
        case 'INVALID_SESSION_ID':
        case 'INVALID_LOGIN':
          throw new UnauthorizedException('Salesforce session is invalid or expired. Please re-authenticate.');
        
        case 'REQUEST_LIMIT_EXCEEDED':
          throw new BadRequestException('Salesforce API request limit exceeded. Please try again later.');
        
        case 'DUPLICATE_VALUE':
          throw new BadRequestException('A record with this value already exists in Salesforce.');
        
        case 'FIELD_CUSTOM_VALIDATION_EXCEPTION':
        case 'REQUIRED_FIELD_MISSING':
        case 'INVALID_FIELD':
          throw new BadRequestException(`Salesforce validation error: ${error.message || 'Invalid field value'}`);
        
        case 'INSUFFICIENT_ACCESS':
        case 'INSUFFICIENT_ACCESS_OR_READONLY':
          throw new UnauthorizedException('Insufficient permissions to perform this operation.');
        
        default:
          throw new BadRequestException(`Salesforce error: ${error.message || error.errorCode}`);
      }
    }

    // Handle HTTP errors
    if (error.response) {
      const status = error.response.status || error.statusCode;
      
      if (status === 401) {
        throw new UnauthorizedException('Salesforce authentication failed. Please check credentials.');
      } else if (status === 429) {
        throw new BadRequestException('Salesforce API rate limit exceeded. Please try again later.');
      } else if (status === 503) {
        throw new BadRequestException('Salesforce service temporarily unavailable. Please try again.');
      }
    }

    // Handle generic errors
    if (error instanceof UnauthorizedException || error instanceof BadRequestException) {
      throw error;
    }

    throw new BadRequestException(`Salesforce operation failed: ${error.message || 'Unknown error'}`);
  }

  /**
   * Retry operation with exponential backoff
   * 
   * Implements retry logic for transient failures like network errors and rate limiting.
   * Uses exponential backoff to avoid overwhelming the Salesforce API.
   * 
   * @param operation - Async function to retry
   * @param maxRetries - Maximum number of retry attempts (default: 3)
   * @param initialDelay - Initial delay in milliseconds (default: 1000)
   * @returns Result of the operation
   */
  async retryWithBackoff<T>(
    operation: () => Promise<T>,
    maxRetries: number = 3,
    initialDelay: number = 1000
  ): Promise<T> {
    let lastError: any;
    
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await operation();
      } catch (error) {
        lastError = error;
        
        // Don't retry on certain errors
        if (
          error instanceof UnauthorizedException ||
          (error instanceof BadRequestException && !error.message.includes('rate limit') && !error.message.includes('temporarily unavailable'))
        ) {
          throw error;
        }
        
        // If this was the last attempt, throw the error
        if (attempt === maxRetries) {
          break;
        }
        
        // Calculate exponential backoff delay
        const delay = initialDelay * Math.pow(2, attempt);
        
        this.logger.warn(`Operation failed, retrying in ${delay}ms (attempt ${attempt + 1}/${maxRetries})`);
        
        // Wait before retrying
        await new Promise(resolve => setTimeout(resolve, delay));
      }
    }
    
    throw lastError;
  }
}
