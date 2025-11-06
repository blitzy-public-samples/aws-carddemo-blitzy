/**
 * Barrel export for notification entities.
 * 
 * Provides centralized access to all notification-related entities and enums:
 * - Notification: Main notification history and delivery tracking
 * - NotificationPreference: User notification preferences
 * - NotificationType: Types of notifications (processing_complete, failed, etc.)
 * - NotificationChannel: Delivery channels (email, push, webhook)
 * - NotificationStatus: Delivery status (pending, sent, delivered, failed)
 * - NotificationFrequency: Notification frequency (instant, daily, weekly, never)
 * 
 * @example
 * // Instead of multiple imports:
 * import { Notification } from './entities/notification.entity';
 * import { NotificationPreference } from './entities/notification-preference.entity';
 * 
 * // Use centralized import:
 * import { Notification, NotificationPreference, NotificationStatus } from './entities';
 */

// Export Notification entity and related enums
export {
  Notification,
  NotificationType,
  NotificationChannel,
  NotificationStatus,
} from './notification.entity';

// Export NotificationPreference entity and related enums
export {
  NotificationPreference,
  NotificationFrequency,
} from './notification-preference.entity';
