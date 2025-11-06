/**
 * Email Template Data Transfer Object
 * 
 * Defines the structure for email template management with comprehensive validation.
 * Supports dynamic content through template variables and multiple template categories.
 * 
 * Template variables follow the Mustache/Handlebars syntax: {{variableName}}
 * 
 * Supported template types:
 * - processing-complete: Document processing completion notifications
 * - processing-failed: Document processing failure alerts
 * - password-reset: Password reset emails with secure token links
 * - user-invitation: New user invitation emails with activation links
 * - weekly-digest: Weekly summary reports of account activity
 * - account-activity: Account activity summaries and notifications
 * 
 * Per Section 0.4.8 Email Notification Integration and Section 0.7.2 validation requirements.
 */

import {
  IsString,
  IsBoolean,
  IsOptional,
  MaxLength,
  IsArray,
  ArrayNotEmpty,
  IsEnum,
} from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Email Template Category Enumeration
 * 
 * Categorizes email templates by their intended purpose:
 * - TRANSACTIONAL: Transaction-related emails (receipts, confirmations, processing status)
 * - MARKETING: Marketing and promotional emails (feature announcements, newsletters)
 * - SYSTEM: System-generated notifications (password resets, security alerts)
 */
export enum TemplateCategoryEnum {
  TRANSACTIONAL = 'transactional',
  MARKETING = 'marketing',
  SYSTEM = 'system',
}

/**
 * Email Template Data Transfer Object
 * 
 * Validates and structures email template data for creation and updates.
 * Ensures all required fields are present with appropriate constraints.
 * 
 * All templates support dynamic content through variable substitution.
 * Variables are specified in double curly braces: {{variableName}}
 * 
 * Example usage:
 * ```typescript
 * const template: EmailTemplateDto = {
 *   name: 'processing-complete',
 *   subject: 'Your document {{documentName}} has been processed',
 *   htmlBody: '<p>Hello {{userName}}, your document has been processed successfully.</p>',
 *   textBody: 'Hello {{userName}}, your document has been processed successfully.',
 *   requiredVariables: ['userName', 'documentName'],
 *   category: TemplateCategoryEnum.TRANSACTIONAL,
 *   isActive: true,
 *   description: 'Notification sent when document processing completes successfully'
 * };
 * ```
 */
export class EmailTemplateDto {
  /**
   * Unique identifier for the email template
   * 
   * Used to reference templates programmatically when sending emails.
   * Should follow kebab-case naming convention.
   * 
   * Common template names:
   * - processing-complete
   * - processing-failed
   * - password-reset
   * - user-invitation
   * - weekly-digest
   * - account-activity
   * 
   * @example 'processing-complete'
   * @example 'password-reset'
   * @example 'user-invitation'
   */
  @ApiProperty({
    description: 'Unique template identifier following kebab-case convention',
    example: 'processing-complete',
    maxLength: 100,
    type: String,
    required: true,
  })
  @IsString()
  @MaxLength(100, {
    message: 'Template name must not exceed 100 characters',
  })
  name!: string;

  /**
   * Email subject line template
   * 
   * Supports dynamic content through template variables.
   * Variables are replaced at send time with actual values.
   * 
   * Keep subject lines concise and descriptive (recommended: 40-60 characters).
   * Avoid spam trigger words and excessive punctuation.
   * 
   * @example 'Your document {{documentName}} has been processed'
   * @example 'Reset your password - {{appName}}'
   * @example 'Welcome to {{appName}}, {{userName}}!'
   */
  @ApiProperty({
    description:
      'Email subject line template with support for variable placeholders using {{variableName}} syntax',
    example: 'Your document {{documentName}} has been processed',
    maxLength: 200,
    type: String,
    required: true,
  })
  @IsString()
  @MaxLength(200, {
    message: 'Subject line must not exceed 200 characters',
  })
  subject!: string;

  /**
   * HTML email body template
   * 
   * Full HTML structure for the email including styling and dynamic content.
   * Should be responsive and tested across multiple email clients.
   * 
   * Best practices:
   * - Use inline CSS for styling (email clients strip <style> tags)
   * - Use tables for layout (flexbox not universally supported)
   * - Include alt text for images
   * - Test rendering in Gmail, Outlook, Apple Mail, etc.
   * 
   * Template variables: {{variableName}}
   * Conditional blocks: {{#if condition}}...{{/if}}
   * Loops: {{#each items}}...{{/each}}
   * 
   * @example '<html><body><h1>Hello {{userName}}</h1><p>Your document <strong>{{documentName}}</strong> has been processed successfully.</p><p>Processing time: {{processingTime}}</p><a href="{{documentUrl}}">View Document</a></body></html>'
   */
  @ApiProperty({
    description:
      'HTML email body with inline CSS styling and template variable support. Should be responsive and compatible with major email clients.',
    example:
      '<html><body style="font-family: Arial, sans-serif;"><h1>Hello {{userName}}</h1><p>Your document <strong>{{documentName}}</strong> has been processed successfully.</p><p>Processing time: {{processingTime}}</p><a href="{{documentUrl}}" style="background-color: #4CAF50; color: white; padding: 14px 20px; text-decoration: none; display: inline-block;">View Document</a></body></html>',
    type: String,
    required: true,
  })
  @IsString()
  htmlBody!: string;

  /**
   * Plain text email body fallback
   * 
   * Optional plain text version for email clients that don't support HTML.
   * Should convey the same information as htmlBody without formatting.
   * 
   * Recommended for:
   * - Accessibility (screen readers)
   * - Email clients with HTML disabled
   * - Reducing spam score (multipart emails preferred)
   * 
   * If not provided, system may auto-generate from HTML (stripping tags).
   * 
   * @example 'Hello {{userName}},\n\nYour document {{documentName}} has been processed successfully.\n\nProcessing time: {{processingTime}}\n\nView your document: {{documentUrl}}'
   */
  @ApiProperty({
    description:
      'Plain text fallback for email clients that do not support HTML. Recommended for accessibility and spam score optimization.',
    example:
      'Hello {{userName}},\n\nYour document {{documentName}} has been processed successfully.\n\nProcessing time: {{processingTime}}\n\nView your document: {{documentUrl}}',
    type: String,
    required: false,
  })
  @IsOptional()
  @IsString()
  textBody?: string;

  /**
   * Array of required template variables
   * 
   * Lists all variable names that must be provided when sending emails using this template.
   * System will validate that all required variables are present before sending.
   * 
   * Variable names should be:
   * - camelCase
   * - Descriptive and self-documenting
   * - Consistent across templates
   * 
   * Common variables:
   * - userName: Recipient's name
   * - userEmail: Recipient's email address
   * - documentName: Name of processed document
   * - documentUrl: Link to view document
   * - processingTime: Time taken to process
   * - accountName: Account/workspace name
   * - resetUrl: Password reset link
   * - invitationUrl: User invitation link
   * 
   * @example ['userName', 'documentName', 'documentUrl', 'processingTime']
   * @example ['userName', 'resetUrl', 'expirationTime']
   */
  @ApiProperty({
    description:
      'Array of required variable names that must be provided when sending emails. Variables follow camelCase naming convention.',
    example: ['userName', 'documentName', 'documentUrl', 'processingTime'],
    type: [String],
    isArray: true,
    required: true,
  })
  @IsArray({
    message: 'Required variables must be an array',
  })
  @ArrayNotEmpty({
    message: 'At least one required variable must be specified',
  })
  @IsString({ each: true })
  requiredVariables!: string[];

  /**
   * Template category classification
   * 
   * Categorizes the template by its intended purpose for organization and filtering.
   * 
   * Categories:
   * - TRANSACTIONAL: Business transaction emails (order confirmations, processing status, receipts)
   *   Examples: processing-complete, processing-failed, export-ready
   *   Typically have higher open rates and are not subject to unsubscribe requirements
   * 
   * - MARKETING: Promotional and marketing content (newsletters, announcements, offers)
   *   Examples: feature-announcement, newsletter, weekly-digest
   *   Must include unsubscribe link and respect user preferences
   * 
   * - SYSTEM: System-level notifications (security, authentication, account management)
   *   Examples: password-reset, user-invitation, account-activity, security-alert
   *   Critical emails that users typically cannot opt out of
   * 
   * @example TemplateCategoryEnum.TRANSACTIONAL
   * @example TemplateCategoryEnum.MARKETING
   * @example TemplateCategoryEnum.SYSTEM
   */
  @ApiProperty({
    description:
      'Template category for classification and filtering. Determines email handling rules and unsubscribe requirements.',
    example: TemplateCategoryEnum.TRANSACTIONAL,
    enum: TemplateCategoryEnum,
    enumName: 'TemplateCategoryEnum',
    required: true,
  })
  @IsEnum(TemplateCategoryEnum, {
    message: 'Category must be one of: transactional, marketing, system',
  })
  category!: TemplateCategoryEnum;

  /**
   * Template activation status
   * 
   * Enables or disables the template without deleting it.
   * Inactive templates are retained in the system but cannot be used for sending emails.
   * 
   * Use cases for deactivation:
   * - Temporarily suspending marketing campaigns
   * - Testing new template versions before activation
   * - Deprecating old templates while maintaining history
   * - Compliance with regulatory requirements (e.g., GDPR data retention)
   * 
   * System will:
   * - Return error if attempting to send with inactive template
   * - Exclude inactive templates from template selection dropdowns
   * - Preserve template configuration for potential reactivation
   * 
   * @default true
   * @example true
   * @example false
   */
  @ApiProperty({
    description:
      'Boolean flag indicating whether the template is active and available for use. Inactive templates are preserved but cannot send emails.',
    example: true,
    type: Boolean,
    default: true,
    required: true,
  })
  @IsBoolean({
    message: 'isActive must be a boolean value (true or false)',
  })
  isActive!: boolean;

  /**
   * Optional template description
   * 
   * Human-readable description of the template's purpose and usage.
   * Helps administrators understand when and how to use the template.
   * 
   * Should include:
   * - Template purpose and trigger conditions
   * - Target audience (users, admins, system)
   * - Any special considerations or requirements
   * - Links to related documentation if applicable
   * 
   * @example 'Notification sent when document processing completes successfully. Includes document name, processing time, and link to view results. Sent to document owner only.'
   * @example 'Password reset email with secure token link. Link expires after 1 hour. Sent when user requests password reset from login page.'
   */
  @ApiProperty({
    description:
      'Optional description explaining the template purpose, trigger conditions, and usage guidelines for administrators.',
    example:
      'Notification sent when document processing completes successfully. Includes document name, processing time, and link to view results. Sent to document owner only.',
    maxLength: 500,
    type: String,
    required: false,
  })
  @IsOptional()
  @MaxLength(500, {
    message: 'Description must not exceed 500 characters',
  })
  description?: string;
}
