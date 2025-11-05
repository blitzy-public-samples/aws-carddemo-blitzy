/**
 * Toast Notification Component
 *
 * Provides a consistent notification system throughout the application using react-hot-toast.
 * Supports multiple variants (success, error, warning, info) with auto-dismiss functionality,
 * action buttons, stack management, and customizable positioning.
 *
 * @module components/common/Toast
 */

import React, { type FC } from 'react';
import toast, { type ToastOptions as ReactHotToastOptions, Toaster } from 'react-hot-toast';
import { AlertTriangle, CheckCircle, Info, XCircle } from 'lucide-react';
import clsx from 'clsx';

/**
 * Toast variant types for different notification purposes
 */
export type ToastVariant = 'success' | 'error' | 'warning' | 'info';

/**
 * Configuration options for toast notifications
 *
 * @interface ToastOptions
 * @property {string} message - The message content to display in the toast
 * @property {number} [duration] - Duration in milliseconds before auto-dismiss (default: 4000)
 * @property {boolean} [action] - Whether to show an action button
 * @property {string} [actionLabel] - Label text for the action button
 * @property {() => void} [onAction] - Callback function when action button is clicked
 * @property {'top-left' | 'top-center' | 'top-right' | 'bottom-left' | 'bottom-center' | 'bottom-right'} [position] - Toast position on screen
 */
export interface ToastOptions {
  message: string;
  duration?: number;
  action?: boolean;
  actionLabel?: string;
  onAction?: () => void;
  position?:
    | 'top-left'
    | 'top-center'
    | 'top-right'
    | 'bottom-left'
    | 'bottom-center'
    | 'bottom-right';
}

/**
 * Internal interface extending react-hot-toast options with custom properties
 */
interface CustomToastOptions extends Partial<ReactHotToastOptions> {
  action?: boolean;
  actionLabel?: string;
  onAction?: () => void;
}

/**
 * Configuration object for toast variant styling and accessibility
 */
interface ToastVariantConfig {
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
  bgColor: string;
  textColor: string;
  borderColor: string;
  ariaRole: 'status' | 'alert';
  ariaLive: 'polite' | 'assertive';
}

/**
 * Variant configuration mapping for consistent styling and accessibility
 */
const variantConfig: Record<ToastVariant, ToastVariantConfig> = {
  success: {
    icon: CheckCircle,
    iconColor: 'text-green-500',
    bgColor: 'bg-green-50',
    textColor: 'text-green-900',
    borderColor: 'border-green-200',
    ariaRole: 'status',
    ariaLive: 'polite',
  },
  error: {
    icon: XCircle,
    iconColor: 'text-red-500',
    bgColor: 'bg-red-50',
    textColor: 'text-red-900',
    borderColor: 'border-red-200',
    ariaRole: 'alert',
    ariaLive: 'assertive',
  },
  warning: {
    icon: AlertTriangle,
    iconColor: 'text-yellow-500',
    bgColor: 'bg-yellow-50',
    textColor: 'text-yellow-900',
    borderColor: 'border-yellow-200',
    ariaRole: 'alert',
    ariaLive: 'assertive',
  },
  info: {
    icon: Info,
    iconColor: 'text-blue-500',
    bgColor: 'bg-blue-50',
    textColor: 'text-blue-900',
    borderColor: 'border-blue-200',
    ariaRole: 'status',
    ariaLive: 'polite',
  },
};

/**
 * Custom toast content renderer with variant styling and action buttons
 *
 * @param {ToastVariant} variant - The toast variant type
 * @param {string} message - The message to display
 * @param {string} [toastId] - Unique identifier for the toast instance
 * @param {CustomToastOptions} [options] - Additional options including action button config
 * @returns {JSX.Element} The rendered toast content
 */
const renderToast = (
  variant: ToastVariant,
  message: string,
  toastId?: string,
  options?: CustomToastOptions
): JSX.Element => {
  const config = variantConfig[variant];
  const IconComponent = config.icon;

  return (
    <div
      className={clsx(
        'flex items-start gap-3 p-4 rounded-lg border shadow-lg max-w-md',
        config.bgColor,
        config.borderColor
      )}
      role={config.ariaRole}
      aria-live={config.ariaLive}
      aria-atomic="true"
    >
      <IconComponent
        className={clsx('w-5 h-5 flex-shrink-0 mt-0.5', config.iconColor)}
        aria-hidden="true"
      />

      <div className="flex-1 min-w-0">
        <p className={clsx('text-sm font-medium', config.textColor)}>{message}</p>
      </div>

      {options?.action && options?.actionLabel && options?.onAction && (
        <button
          onClick={() => {
            options.onAction?.();
            if (toastId) {
              toast.dismiss(toastId);
            }
          }}
          className={clsx(
            'flex-shrink-0 text-sm font-medium px-3 py-1 rounded-md',
            'hover:opacity-80 focus:outline-none focus:ring-2 focus:ring-offset-2',
            variant === 'success' &&
              'text-green-700 bg-green-100 hover:bg-green-200 focus:ring-green-500',
            variant === 'error' && 'text-red-700 bg-red-100 hover:bg-red-200 focus:ring-red-500',
            variant === 'warning' &&
              'text-yellow-700 bg-yellow-100 hover:bg-yellow-200 focus:ring-yellow-500',
            variant === 'info' && 'text-blue-700 bg-blue-100 hover:bg-blue-200 focus:ring-blue-500'
          )}
          aria-label={options.actionLabel}
        >
          {options.actionLabel}
        </button>
      )}

      <button
        onClick={() => toastId && toast.dismiss(toastId)}
        className={clsx(
          'flex-shrink-0 p-1 rounded-md hover:opacity-70',
          'focus:outline-none focus:ring-2 focus:ring-offset-2',
          variant === 'success' && 'focus:ring-green-500',
          variant === 'error' && 'focus:ring-red-500',
          variant === 'warning' && 'focus:ring-yellow-500',
          variant === 'info' && 'focus:ring-blue-500',
          config.textColor
        )}
        aria-label="Dismiss notification"
      >
        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20" aria-hidden="true">
          <path
            fillRule="evenodd"
            d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
            clipRule="evenodd"
          />
        </svg>
      </button>
    </div>
  );
};

/**
 * Helper function to show a toast notification
 *
 * @param {ToastVariant} variant - The toast variant type
 * @param {string | ToastOptions} messageOrOptions - Message string or options object
 * @param {Partial<ToastOptions>} [additionalOptions] - Additional options when first param is string
 * @returns {string} The toast ID for programmatic dismissal
 */
const showToast = (
  variant: ToastVariant,
  messageOrOptions: string | ToastOptions,
  additionalOptions?: Partial<ToastOptions>
): string => {
  let options: ToastOptions;

  if (typeof messageOrOptions === 'string') {
    options = {
      message: messageOrOptions,
      ...additionalOptions,
    };
  } else {
    options = messageOrOptions;
  }

  const { message, duration = 4000, action, actionLabel, onAction, position } = options;

  const customOptions: CustomToastOptions = {
    duration,
    position,
    action,
    actionLabel,
    onAction,
  };

  const toastId = toast.custom(
    (t) => renderToast(variant, message, t.id, customOptions),
    customOptions
  );

  return toastId;
};

/**
 * Display a success toast notification
 *
 * @param {string | ToastOptions} messageOrOptions - Message string or full options object
 * @param {Partial<ToastOptions>} [options] - Additional options when first param is string
 * @returns {string} The toast ID for programmatic dismissal
 *
 * @example
 * // Simple success message
 * showSuccess('Document uploaded successfully');
 *
 * @example
 * // Success with action button
 * showSuccess({
 *   message: 'Document processed successfully',
 *   action: true,
 *   actionLabel: 'View',
 *   onAction: () => navigateToDocument(id)
 * });
 */
export const showSuccess = (
  messageOrOptions: string | ToastOptions,
  options?: Partial<ToastOptions>
): string => {
  return showToast('success', messageOrOptions, options);
};

/**
 * Display an error toast notification
 *
 * @param {string | ToastOptions} messageOrOptions - Message string or full options object
 * @param {Partial<ToastOptions>} [options] - Additional options when first param is string
 * @returns {string} The toast ID for programmatic dismissal
 *
 * @example
 * // Simple error message
 * showError('Failed to upload document');
 *
 * @example
 * // Error with retry action
 * showError({
 *   message: 'Upload failed. Please try again.',
 *   action: true,
 *   actionLabel: 'Retry',
 *   onAction: () => retryUpload(),
 *   duration: 6000
 * });
 */
export const showError = (
  messageOrOptions: string | ToastOptions,
  options?: Partial<ToastOptions>
): string => {
  return showToast('error', messageOrOptions, options);
};

/**
 * Display a warning toast notification
 *
 * @param {string | ToastOptions} messageOrOptions - Message string or full options object
 * @param {Partial<ToastOptions>} [options] - Additional options when first param is string
 * @returns {string} The toast ID for programmatic dismissal
 *
 * @example
 * // Simple warning message
 * showWarning('Low confidence detected in extraction');
 *
 * @example
 * // Warning with action
 * showWarning({
 *   message: 'Some fields need review',
 *   action: true,
 *   actionLabel: 'Review',
 *   onAction: () => openReviewPanel()
 * });
 */
export const showWarning = (
  messageOrOptions: string | ToastOptions,
  options?: Partial<ToastOptions>
): string => {
  return showToast('warning', messageOrOptions, options);
};

/**
 * Display an informational toast notification
 *
 * @param {string | ToastOptions} messageOrOptions - Message string or full options object
 * @param {Partial<ToastOptions>} [options] - Additional options when first param is string
 * @returns {string} The toast ID for programmatic dismissal
 *
 * @example
 * // Simple info message
 * showInfo('Processing will begin shortly');
 *
 * @example
 * // Info with custom duration
 * showInfo({
 *   message: 'New features available',
 *   duration: 6000,
 *   action: true,
 *   actionLabel: 'Learn More',
 *   onAction: () => openFeatureGuide()
 * });
 */
export const showInfo = (
  messageOrOptions: string | ToastOptions,
  options?: Partial<ToastOptions>
): string => {
  return showToast('info', messageOrOptions, options);
};

/**
 * ToastContainer component that renders the toast notification system
 *
 * This component should be included once in the application root (typically in _app.tsx)
 * to enable toast notifications throughout the application.
 *
 * @component
 * @returns {JSX.Element} The toast container with configured Toaster
 *
 * @example
 * // In _app.tsx
 * import { ToastContainer } from '@/components/common/Toast';
 *
 * function MyApp({ Component, pageProps }) {
 *   return (
 *     <>
 *       <Component {...pageProps} />
 *       <ToastContainer />
 *     </>
 *   );
 * }
 */
export const ToastContainer: FC = () => {
  return (
    <Toaster
      position="top-right"
      reverseOrder={false}
      gutter={8}
      containerClassName=""
      containerStyle={{}}
      toastOptions={{
        // Default options for all toasts
        duration: 4000,
        style: {
          background: 'transparent',
          boxShadow: 'none',
          padding: 0,
        },
        // Maximum number of toasts visible at once (stack management)
        // Older toasts will be auto-dismissed when limit is reached
      }}
      // Maximum 5 toasts visible simultaneously
      // Additional toasts will queue and display as current ones dismiss
    />
  );
};

// Export toast dismiss function for programmatic control
export { toast };
