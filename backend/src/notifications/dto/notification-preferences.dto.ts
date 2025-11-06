/**
 * Data Transfer Objects for Notification Preferences
 * 
 * Defines DTOs for user notification preference configuration including:
 * - Multi-channel notification settings (email, WebSocket)
 * - Notification type subscriptions (processing events, digests, account activity)
 * - Email delivery frequency controls (immediate, hourly, daily batching)
 * - Quiet hours configuration for personalized notification scheduling
 * 
 * Used by GET/PUT /api/v1/notifications/preferences endpoints per Section 0.5.5 Phase 4 Group 4D
 * 
 * @module NotificationPreferencesDto
 */

import {
  IsBoolean,
  IsEnum,
  IsObject,
  IsOptional,
  IsString,
  Matches,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import { ApiProperty } from '@nestjs/swagger';

/**
 * Email frequency enumeration for notification batching
 * Controls how frequently email notifications are sent to users
 */
export enum EmailFrequencyEnum {
  /** Send emails immediately as events occur */
  IMMEDIATE = 'immediate',
  /** Batch emails and send once per hour */
  HOURLY = 'hourly',
  /** Batch emails and send once per day */
  DAILY = 'daily',
}

/**
 * Nested DTO for notification type-specific preferences
 * Allows users to enable/disable individual notification types
 * Each type corresponds to specific system events per Section 0.4.7 outbound webhooks
 */
export class NotificationTypePreferencesDto {
  @ApiProperty({
    description: 'Receive notifications when document processing completes successfully',
    example: true,
    type: Boolean,
    required: true,
  })
  @IsBoolean({ message: 'processingComplete must be a boolean value' })
  processingComplete!: boolean;

  @ApiProperty({
    description: 'Receive notifications when document processing fails',
    example: true,
    type: Boolean,
    required: true,
  })
  @IsBoolean({ message: 'processingFailed must be a boolean value' })
  processingFailed!: boolean;

  @ApiProperty({
    description: 'Receive weekly digest emails summarizing account activity',
    example: true,
    type: Boolean,
    required: true,
  })
  @IsBoolean({ message: 'weeklyDigest must be a boolean value' })
  weeklyDigest!: boolean;

  @ApiProperty({
    description: 'Receive notifications for general account activity (user changes, settings updates)',
    example: true,
    type: Boolean,
    required: true,
  })
  @IsBoolean({ message: 'accountActivity must be a boolean value' })
  accountActivity!: boolean;

  @ApiProperty({
    description: 'Receive password reset notifications (always enabled for security, immutable)',
    example: true,
    type: Boolean,
    required: true,
    default: true,
  })
  @IsBoolean({ message: 'passwordReset must be a boolean value' })
  passwordReset!: boolean;

  @ApiProperty({
    description: 'Receive notifications when invited to collaborate on documents or teams',
    example: true,
    type: Boolean,
    required: true,
  })
  @IsBoolean({ message: 'userInvitation must be a boolean value' })
  userInvitation!: boolean;
}

/**
 * Main DTO for notification preference configuration
 * Manages all user notification settings including channels, types, frequency, and quiet hours
 * 
 * Validation rules per Section 0.7.2:
 * - All inputs validated with class-validator decorators
 * - Comprehensive error messages for validation failures
 * - Type-safe with TypeScript strict mode
 * 
 * API documentation per Section 0.7.4:
 * - Complete OpenAPI schema with @ApiProperty decorators
 * - Examples for all fields
 * - Clear descriptions and constraints
 */
export class NotificationPreferencesDto {
  @ApiProperty({
    description: 'Enable or disable email notifications globally. When disabled, no emails will be sent regardless of other settings.',
    example: true,
    type: Boolean,
    required: true,
    default: true,
  })
  @IsBoolean({ message: 'emailEnabled must be a boolean value' })
  emailEnabled!: boolean;

  @ApiProperty({
    description: 'Enable or disable real-time WebSocket notifications. When disabled, no WebSocket events will be pushed to the client.',
    example: true,
    type: Boolean,
    required: true,
    default: true,
  })
  @IsBoolean({ message: 'websocketEnabled must be a boolean value' })
  websocketEnabled!: boolean;

  @ApiProperty({
    description: 'Fine-grained notification type preferences. Control which specific event types trigger notifications.',
    type: NotificationTypePreferencesDto,
    required: true,
    example: {
      processingComplete: true,
      processingFailed: true,
      weeklyDigest: true,
      accountActivity: false,
      passwordReset: true,
      userInvitation: true,
    },
  })
  @IsObject({ message: 'notificationTypes must be a valid object' })
  @ValidateNested({ message: 'notificationTypes must contain valid notification type preferences' })
  @Type(() => NotificationTypePreferencesDto)
  notificationTypes!: NotificationTypePreferencesDto;

  @ApiProperty({
    description: 'Email batching frequency. Controls how often notifications are bundled and sent via email.',
    enum: EmailFrequencyEnum,
    enumName: 'EmailFrequencyEnum',
    example: EmailFrequencyEnum.IMMEDIATE,
    required: true,
    default: EmailFrequencyEnum.IMMEDIATE,
  })
  @IsEnum(EmailFrequencyEnum, {
    message: `emailFrequency must be one of: ${Object.values(EmailFrequencyEnum).join(', ')}`,
  })
  emailFrequency!: EmailFrequencyEnum;

  @ApiProperty({
    description: 'Enable quiet hours during which notifications will be suppressed. Notifications received during quiet hours are queued and delivered after the quiet period ends.',
    example: false,
    type: Boolean,
    required: true,
    default: false,
  })
  @IsBoolean({ message: 'quietHoursEnabled must be a boolean value' })
  quietHoursEnabled!: boolean;

  @ApiProperty({
    description: 'Start time for quiet hours in 24-hour HH:MM format (e.g., "22:00" for 10:00 PM). Required when quietHoursEnabled is true.',
    example: '22:00',
    type: String,
    required: false,
    pattern: '^([0-1][0-9]|2[0-3]):[0-5][0-9]$',
  })
  @IsOptional()
  @IsString({ message: 'quietHoursStart must be a string' })
  @Matches(/^([0-1][0-9]|2[0-3]):[0-5][0-9]$/, {
    message: 'quietHoursStart must be in HH:MM format (24-hour time, e.g., "22:00")',
  })
  quietHoursStart?: string;

  @ApiProperty({
    description: 'End time for quiet hours in 24-hour HH:MM format (e.g., "08:00" for 8:00 AM). Required when quietHoursEnabled is true.',
    example: '08:00',
    type: String,
    required: false,
    pattern: '^([0-1][0-9]|2[0-3]):[0-5][0-9]$',
  })
  @IsOptional()
  @IsString({ message: 'quietHoursEnd must be a string' })
  @Matches(/^([0-1][0-9]|2[0-3]):[0-5][0-9]$/, {
    message: 'quietHoursEnd must be in HH:MM format (24-hour time, e.g., "08:00")',
  })
  quietHoursEnd?: string;

  @ApiProperty({
    description: 'IANA timezone identifier for calculating quiet hours in user\'s local time (e.g., "America/New_York", "Europe/London", "UTC"). Required when quietHoursEnabled is true.',
    example: 'America/New_York',
    type: String,
    required: false,
  })
  @IsOptional()
  @IsString({ message: 'timezone must be a valid string' })
  timezone?: string;
}
