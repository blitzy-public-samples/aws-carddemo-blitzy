/**
 * Data Transfer Objects for Email Sending Requests
 * 
 * This module provides DTOs for email sending operations with comprehensive validation
 * using class-validator decorators. Implements RFC 5322 email validation and enforces
 * strict input constraints per Section 0.7.2 NestJS Backend guidelines.
 * 
 * Key Features:
 * - RFC 5322 compliant email validation
 * - Attachment size and MIME type validation
 * - Template variable support for dynamic content
 * - CC/BCC recipient support
 * - Comprehensive OpenAPI documentation per Section 0.7.4
 * 
 * @module notifications/dto
 */

import {
  IsEmail,
  IsString,
  IsOptional,
  IsArray,
  MaxLength,
  IsObject,
  ValidateNested,
  IsNotEmpty,
  Matches,
  ArrayMaxSize,
  Length,
} from 'class-validator';
import { Type } from 'class-transformer';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Email Attachment Data Transfer Object
 * 
 * Represents a single email attachment with validation for name, content, and MIME type.
 * Used as nested validation within SendEmailDto attachments array.
 * 
 * Validation Rules:
 * - name: Required, 1-255 characters, valid filename pattern
 * - content: Required, base64-encoded content
 * - contentType: Required, valid MIME type format
 */
export class EmailAttachmentDto {
  /**
   * Attachment filename
   * 
   * Must be a valid filename with extension. Maximum 255 characters.
   * Examples: 'invoice.pdf', 'receipt.jpg', 'document_2024.xlsx'
   * 
   * @example 'invoice_12345.pdf'
   */
  @ApiProperty({
    description: 'Attachment filename with extension',
    example: 'invoice_12345.pdf',
    minLength: 1,
    maxLength: 255,
    required: true,
  })
  @IsString({ message: 'Attachment name must be a string' })
  @IsNotEmpty({ message: 'Attachment name cannot be empty' })
  @Length(1, 255, { message: 'Attachment name must be between 1 and 255 characters' })
  @Matches(/^[a-zA-Z0-9_\-\.]+$/, {
    message: 'Attachment name must contain only alphanumeric characters, underscores, hyphens, and dots',
  })
  name!: string;

  /**
   * Base64-encoded attachment content
   * 
   * The actual file content encoded in base64 format. This is required for
   * transmitting binary data over JSON.
   * 
   * @example 'JVBERi0xLjQKJeLjz9MKMSAwIG9iago8PC9UeXBlL0NhdGFsb2cvUGFnZXMgMiAwIFI+PgplbmRvYmoKMiAwIG9iago8PC9UeXBlL1BhZ2VzL0tpZHNbMyAwIFJdL0NvdW50IDE+PgplbmRvYmo='
   */
  @ApiProperty({
    description: 'Base64-encoded attachment content',
    example: 'JVBERi0xLjQKJeLjz9MKMSAwIG9iago8PC9UeXBlL0NhdGFsb2cvUGFnZXMgMiAwIFI+PgplbmRvYmoKMiAwIG9iago8PC9UeXBlL1BhZ2VzL0tpZHNbMyAwIFJdL0NvdW50IDE+PgplbmRvYmo=',
    required: true,
  })
  @IsString({ message: 'Attachment content must be a string' })
  @IsNotEmpty({ message: 'Attachment content cannot be empty' })
  @Matches(/^[A-Za-z0-9+/]*={0,2}$/, {
    message: 'Attachment content must be valid base64-encoded data',
  })
  content!: string;

  /**
   * MIME type of the attachment
   * 
   * Must be a valid MIME type (e.g., 'application/pdf', 'image/jpeg', 'text/plain').
   * Used by email clients to properly handle the attachment.
   * 
   * @example 'application/pdf'
   */
  @ApiProperty({
    description: 'MIME type of the attachment',
    example: 'application/pdf',
    pattern: '^[a-zA-Z0-9][a-zA-Z0-9!#$&^_+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*$',
    required: true,
  })
  @IsString({ message: 'Content type must be a string' })
  @IsNotEmpty({ message: 'Content type cannot be empty' })
  @Matches(/^[a-zA-Z0-9][a-zA-Z0-9!#$&^_+-]*\/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*$/, {
    message: 'Content type must be a valid MIME type (e.g., application/pdf, image/jpeg)',
  })
  contentType!: string;
}

/**
 * Send Email Data Transfer Object
 * 
 * Primary DTO for email sending requests. Validates all required and optional fields
 * for sending emails through the notification service.
 * 
 * Required Fields:
 * - recipient: Primary recipient email address
 * - subject: Email subject line (max 200 chars)
 * - body: Email body content (HTML or plain text)
 * 
 * Optional Fields:
 * - attachments: Array of file attachments
 * - templateVariables: Dynamic content substitution
 * - replyTo: Custom reply-to address
 * - cc: Carbon copy recipients
 * - bcc: Blind carbon copy recipients
 * 
 * Validation:
 * - All email addresses validated per RFC 5322
 * - Subject limited to 200 characters
 * - Maximum 10 attachments per email
 * - Maximum 50 CC recipients
 * - Maximum 50 BCC recipients
 * 
 * @see Section 0.4.8 Email Notification Integration
 * @see Section 0.7.2 NestJS Backend Guidelines
 * @see Section 0.7.4 API Design Guidelines
 */
export class SendEmailDto {
  /**
   * Primary recipient email address
   * 
   * The main recipient of the email. Must be a valid email address per RFC 5322.
   * 
   * @example 'user@example.com'
   */
  @ApiProperty({
    description: 'Primary recipient email address (RFC 5322 compliant)',
    example: 'user@example.com',
    format: 'email',
    required: true,
  })
  @IsEmail({}, { message: 'Recipient must be a valid email address per RFC 5322' })
  @IsNotEmpty({ message: 'Recipient email address is required' })
  recipient!: string;

  /**
   * Email subject line
   * 
   * Subject line for the email. Limited to 200 characters for compatibility
   * with email clients and best practices.
   * 
   * @example 'Document Processing Complete'
   */
  @ApiProperty({
    description: 'Email subject line',
    example: 'Document Processing Complete',
    maxLength: 200,
    required: true,
  })
  @IsString({ message: 'Subject must be a string' })
  @IsNotEmpty({ message: 'Subject cannot be empty' })
  @MaxLength(200, { message: 'Subject must not exceed 200 characters' })
  subject!: string;

  /**
   * Email body content
   * 
   * The main content of the email. Can be plain text or HTML format.
   * When using HTML, ensure proper sanitization to prevent XSS attacks.
   * Template variables (if provided) will be substituted before sending.
   * 
   * @example '<h1>Processing Complete</h1><p>Your document has been successfully processed.</p>'
   */
  @ApiProperty({
    description: 'Email body content (plain text or HTML format)',
    example: '<h1>Processing Complete</h1><p>Your document has been successfully processed.</p>',
    required: true,
  })
  @IsString({ message: 'Body must be a string' })
  @IsNotEmpty({ message: 'Body cannot be empty' })
  body!: string;

  /**
   * Email attachments
   * 
   * Optional array of file attachments to include in the email.
   * Each attachment must include name, content (base64), and contentType.
   * Limited to 10 attachments per email for performance and deliverability.
   * 
   * @example [{ name: 'invoice.pdf', content: 'JVBERi0x...', contentType: 'application/pdf' }]
   */
  @ApiProperty({
    description: 'Optional array of email attachments',
    type: [EmailAttachmentDto],
    required: false,
    isArray: true,
    maxItems: 10,
    example: [
      {
        name: 'invoice.pdf',
        content: 'JVBERi0xLjQKJeLjz9MK...',
        contentType: 'application/pdf',
      },
    ],
  })
  @IsOptional()
  @IsArray({ message: 'Attachments must be an array' })
  @ValidateNested({ each: true, message: 'Each attachment must be a valid EmailAttachmentDto' })
  @Type(() => EmailAttachmentDto)
  @ArrayMaxSize(10, { message: 'Maximum 10 attachments allowed per email' })
  attachments?: EmailAttachmentDto[];

  /**
   * Template variables for dynamic content substitution
   * 
   * Optional key-value pairs for substituting dynamic content in the email body.
   * Variables in the body (e.g., {{userName}}, {{documentName}}) will be replaced
   * with values from this object.
   * 
   * @example { userName: 'John Doe', documentName: 'Invoice_2024.pdf', processingTime: '28 seconds' }
   */
  @ApiProperty({
    description: 'Template variables for dynamic content substitution in email body',
    type: 'object',
    additionalProperties: true,
    example: {
      userName: 'John Doe',
      documentName: 'Invoice_2024.pdf',
      processingTime: '28 seconds',
    },
  })
  @IsOptional()
  @IsObject({ message: 'Template variables must be an object' })
  templateVariables?: Record<string, any>;

  /**
   * Reply-to email address
   * 
   * Optional custom email address for replies. When recipients reply to the email,
   * their response will be sent to this address instead of the sender.
   * Must be a valid email address per RFC 5322.
   * 
   * @example 'support@example.com'
   */
  @ApiProperty({
    description: 'Optional reply-to email address',
    example: 'support@example.com',
    format: 'email',
    required: false,
  })
  @IsOptional()
  @IsEmail({}, { message: 'Reply-to must be a valid email address per RFC 5322' })
  replyTo?: string;

  /**
   * Carbon copy (CC) recipients
   * 
   * Optional array of email addresses to receive a copy of the email.
   * All CC recipients will be visible to all other recipients.
   * Limited to 50 recipients for deliverability and performance.
   * 
   * @example ['manager@example.com', 'team@example.com']
   */
  @ApiProperty({
    description: 'Optional array of carbon copy (CC) recipient email addresses',
    type: [String],
    required: false,
    isArray: true,
    maxItems: 50,
    example: ['manager@example.com', 'team@example.com'],
  })
  @IsOptional()
  @IsArray({ message: 'CC recipients must be an array' })
  @IsEmail({}, { each: true, message: 'Each CC recipient must be a valid email address per RFC 5322' })
  @ArrayMaxSize(50, { message: 'Maximum 50 CC recipients allowed' })
  cc?: string[];

  /**
   * Blind carbon copy (BCC) recipients
   * 
   * Optional array of email addresses to receive a copy of the email.
   * BCC recipients are hidden from all other recipients.
   * Limited to 50 recipients for deliverability and performance.
   * 
   * @example ['archive@example.com', 'audit@example.com']
   */
  @ApiProperty({
    description: 'Optional array of blind carbon copy (BCC) recipient email addresses',
    type: [String],
    required: false,
    isArray: true,
    maxItems: 50,
    example: ['archive@example.com', 'audit@example.com'],
  })
  @IsOptional()
  @IsArray({ message: 'BCC recipients must be an array' })
  @IsEmail({}, { each: true, message: 'Each BCC recipient must be a valid email address per RFC 5322' })
  @ArrayMaxSize(50, { message: 'Maximum 50 BCC recipients allowed' })
  bcc?: string[];
}
