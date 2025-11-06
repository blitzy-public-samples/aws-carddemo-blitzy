/**
 * Audit Log Decorator
 * 
 * Provides a custom NestJS method decorator for declarative audit logging.
 * This decorator marks controller methods for automatic audit logging by attaching
 * metadata that is later processed by an interceptor.
 * 
 * @module AuditLogDecorator
 * @see Section 0.5.13 Group 12B - Audit Logging
 * @see Section 0.7.2 - NestJS Backend Guidelines
 */

import { SetMetadata } from '@nestjs/common';

/**
 * Configuration options for the @AuditLog() decorator.
 * 
 * These options define what information should be captured when the decorated
 * method is invoked and how the audit log should be created.
 * 
 * @interface AuditLogOptions
 * @property {string} action - The action being performed (e.g., 'user.create', 'document.approve', 'template.delete')
 * @property {string} resourceType - The type of resource being acted upon (e.g., 'user', 'document', 'template')
 * @property {boolean} [includeBody=false] - Whether to capture the request body in the audit log
 * @property {boolean} [includeResponse=false] - Whether to capture the response data in the audit log
 * @property {boolean} [captureFailure=true] - Whether to log the action when it fails (throws an exception)
 * 
 * @example
 * // Basic audit logging
 * const options: AuditLogOptions = {
 *   action: 'user.delete',
 *   resourceType: 'user'
 * };
 * 
 * @example
 * // Audit logging with request body capture
 * const options: AuditLogOptions = {
 *   action: 'document.create',
 *   resourceType: 'document',
 *   includeBody: true
 * };
 */
export interface AuditLogOptions {
  /**
   * The action being performed.
   * 
   * Use dot notation to namespace actions by resource type.
   * Examples: 'user.create', 'user.update', 'user.delete',
   * 'document.approve', 'document.reject', 'template.publish'
   */
  action: string;

  /**
   * The type of resource being acted upon.
   * 
   * This helps categorize audit logs by the entity type.
   * Examples: 'user', 'document', 'template', 'integration', 'api_key'
   */
  resourceType: string;

  /**
   * Whether to include the request body in the audit log.
   * 
   * Default: false
   * 
   * Set to true for create/update operations where you want to audit
   * what data was submitted. Be careful with sensitive data (passwords,
   * tokens, etc.) - these should be filtered by the interceptor.
   */
  includeBody?: boolean;

  /**
   * Whether to include the response data in the audit log.
   * 
   * Default: false
   * 
   * Set to true when you want to audit what data was returned to the user.
   * Useful for export operations or when you need to track what information
   * was disclosed.
   */
  includeResponse?: boolean;

  /**
   * Whether to log the action when it fails.
   * 
   * Default: true
   * 
   * When true, failed operations will be logged with error details.
   * This is important for security auditing (tracking failed access attempts)
   * and debugging. Set to false only for non-critical operations.
   */
  captureFailure?: boolean;
}

/**
 * Metadata key used to store audit log configuration on decorated methods.
 * 
 * This constant is used by both the decorator and the interceptor to ensure
 * consistent metadata key usage across the audit logging system.
 * 
 * @constant
 * @type {string}
 */
export const AUDIT_LOG_METADATA_KEY = 'audit:log';

/**
 * Decorator factory for declarative audit logging.
 * 
 * This decorator marks controller methods for automatic audit logging. When applied
 * to a method, it attaches metadata containing the audit configuration. An interceptor
 * reads this metadata at runtime and creates audit log entries automatically.
 * 
 * The actual logging is performed by an AuditInterceptor that:
 * - Reads the metadata attached by this decorator
 * - Extracts user information from the request (via JWT/session)
 * - Captures IP address, user agent, and request details
 * - Invokes the decorated method
 * - Logs success or failure to the audit service
 * - Persists audit records to the database
 * 
 * This approach follows the separation of concerns principle by keeping audit
 * configuration separate from the logging implementation.
 * 
 * @param {AuditLogOptions} options - Configuration for the audit log
 * @returns {MethodDecorator} A method decorator that attaches audit metadata
 * 
 * @example
 * // Basic usage - audit user deletion
 * @AuditLog({ action: 'user.delete', resourceType: 'user' })
 * @Delete(':id')
 * async deleteUser(@Param('id') id: string): Promise<void> {
 *   await this.usersService.remove(id);
 * }
 * 
 * @example
 * // Audit document creation with request body
 * @AuditLog({ 
 *   action: 'document.create', 
 *   resourceType: 'document',
 *   includeBody: true 
 * })
 * @Post()
 * async createDocument(@Body() dto: CreateDocumentDto): Promise<Document> {
 *   return this.documentsService.create(dto);
 * }
 * 
 * @example
 * // Audit document approval with response data
 * @AuditLog({ 
 *   action: 'document.approve', 
 *   resourceType: 'document',
 *   includeResponse: true 
 * })
 * @Patch(':id/approve')
 * async approveDocument(@Param('id') id: string): Promise<Document> {
 *   return this.documentsService.approve(id);
 * }
 * 
 * @example
 * // Audit API key generation (capture response for security tracking)
 * @AuditLog({
 *   action: 'api_key.create',
 *   resourceType: 'api_key',
 *   includeBody: true,
 *   includeResponse: true
 * })
 * @Post('api-keys')
 * async createApiKey(@Body() dto: CreateApiKeyDto): Promise<ApiKey> {
 *   return this.apiKeysService.create(dto);
 * }
 * 
 * @example
 * // Audit user login attempts (including failures)
 * @AuditLog({
 *   action: 'auth.login',
 *   resourceType: 'user',
 *   includeBody: false, // Don't log passwords
 *   captureFailure: true // Log failed login attempts
 * })
 * @Post('login')
 * async login(@Body() dto: LoginDto): Promise<AuthResponse> {
 *   return this.authService.login(dto);
 * }
 * 
 * @example
 * // Audit template publication
 * @AuditLog({
 *   action: 'template.publish',
 *   resourceType: 'template',
 *   includeResponse: true
 * })
 * @Patch(':id/publish')
 * async publishTemplate(@Param('id') id: string): Promise<Template> {
 *   return this.templatesService.publish(id);
 * }
 * 
 * @example
 * // Audit integration configuration updates
 * @AuditLog({
 *   action: 'integration.configure',
 *   resourceType: 'integration',
 *   includeBody: true // Log configuration changes
 * })
 * @Patch(':id/config')
 * async updateIntegrationConfig(
 *   @Param('id') id: string,
 *   @Body() dto: UpdateIntegrationDto
 * ): Promise<Integration> {
 *   return this.integrationsService.updateConfig(id, dto);
 * }
 * 
 * @see AuditInterceptor - The interceptor that processes this metadata
 * @see AuditService - The service that persists audit logs
 * @see Section 0.5.13 Group 12B - Audit Logging requirements
 * @see Section 0.7.1 - Code Quality Standards
 * @see Section 0.7.2 - NestJS Backend Guidelines
 */
export const AuditLog = (options: AuditLogOptions): MethodDecorator => {
  // Validate required fields
  if (!options.action || typeof options.action !== 'string' || options.action.trim() === '') {
    throw new Error('AuditLog decorator requires a non-empty "action" string');
  }

  if (!options.resourceType || typeof options.resourceType !== 'string' || options.resourceType.trim() === '') {
    throw new Error('AuditLog decorator requires a non-empty "resourceType" string');
  }

  // Set default values for optional fields
  const auditOptions: Required<AuditLogOptions> = {
    action: options.action.trim(),
    resourceType: options.resourceType.trim(),
    includeBody: options.includeBody ?? false,
    includeResponse: options.includeResponse ?? false,
    captureFailure: options.captureFailure ?? true,
  };

  // Return the decorator using SetMetadata
  // This attaches the audit configuration to the method's metadata
  // The metadata will be retrieved by the AuditInterceptor at runtime
  return SetMetadata(AUDIT_LOG_METADATA_KEY, auditOptions);
};
