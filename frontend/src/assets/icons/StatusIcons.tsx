/**
 * StatusIcons Component Module
 * 
 * This module provides wrapped Material-UI icon components for consistent status indicators
 * throughout the CardDemo application. These icons replace BMS ERRMSG field indicators
 * with modern SVG-based status icons providing visual feedback for form validation,
 * system messages, and operation results.
 * 
 * Converted from mainframe BMS map ERRMSG field definitions (COSGN00.bms, COMEN01.bms, COACTUP.bms)
 * which displayed colored text messages. Modern React implementation uses standardized
 * Material-UI icons with consistent sizing, colors, and accessibility attributes.
 * 
 * All icons use SVG format for scalability and optimal performance across all display resolutions.
 * 
 * @module StatusIcons
 */

import React from 'react';
import CheckCircle from '@mui/icons-material/CheckCircle';
import Error from '@mui/icons-material/Error';
import Warning from '@mui/icons-material/Warning';
import Info from '@mui/icons-material/Info';

/**
 * Type definition for icon size values
 * Matches Material-UI icon sizing conventions
 */
export type IconSizeType = 'small' | 'medium' | 'large';

/**
 * Interface for status icon component props
 * 
 * Provides consistent prop structure for all status icon components
 * with support for sizing, custom styling, and accessibility attributes.
 */
export interface StatusIconProps {
  /**
   * Icon size following Material-UI conventions
   * - small: 16px (compact displays, inline text, table cells)
   * - medium: 24px (standard buttons, form fields, default size)
   * - large: 32px (prominent actions, headers, featured content)
   * 
   * @default 'medium'
   */
  size?: IconSizeType;

  /**
   * Optional custom color override
   * By default, each icon uses its semantic color (success=green, error=red, etc.)
   * Use this prop to override the default color when needed
   */
  color?: string;

  /**
   * Optional CSS class name for additional custom styling
   * Applied to the root SVG element
   */
  className?: string;

  /**
   * Accessible label for screen readers
   * Provides context about the icon's meaning for assistive technologies
   * 
   * @default Icon type (e.g., "Success", "Error", "Warning", "Info")
   */
  'aria-label'?: string;
}

/**
 * Map icon size names to pixel dimensions
 * Ensures exact sizing matching CardDemo design specifications
 */
const SIZE_MAP: Record<IconSizeType, number> = {
  small: 16,
  medium: 24,
  large: 32,
};

/**
 * SuccessIcon Component
 * 
 * Displays a green checkmark circle icon to indicate successful operations,
 * valid form inputs, or positive system states.
 * 
 * Replaces BMS ERRMSG field success messages with visual status indicator.
 * Color: #4caf50 (Material Design Green 500)
 * 
 * @example
 * ```tsx
 * // Standard success message
 * <SuccessIcon size="medium" aria-label="Account created successfully" />
 * 
 * // Inline success indicator
 * <SuccessIcon size="small" />
 * 
 * // Prominent success state
 * <SuccessIcon size="large" aria-label="Transaction completed" />
 * ```
 * 
 * @param props - StatusIconProps
 * @returns React component rendering a success status icon
 */
export const SuccessIcon: React.FC<StatusIconProps> = ({
  size = 'medium',
  color = '#4caf50',
  className,
  'aria-label': ariaLabel = 'Success',
}) => {
  const pixelSize = SIZE_MAP[size];

  return (
    <CheckCircle
      sx={{
        width: pixelSize,
        height: pixelSize,
        color: color,
      }}
      className={className}
      role="img"
      aria-label={ariaLabel}
      aria-hidden={false}
    />
  );
};

/**
 * ErrorIcon Component
 * 
 * Displays a red error circle icon to indicate failed operations,
 * invalid form inputs, or error system states.
 * 
 * Replaces BMS ERRMSG field error messages (displayed in RED with BRT attribute)
 * with visual status indicator providing immediate error recognition.
 * Color: #f44336 (Material Design Red 500)
 * 
 * @example
 * ```tsx
 * // Form validation error
 * <ErrorIcon size="small" aria-label="Invalid account number" />
 * 
 * // System error message
 * <ErrorIcon size="medium" aria-label="Transaction failed" />
 * 
 * // Critical error state
 * <ErrorIcon size="large" aria-label="System error occurred" />
 * ```
 * 
 * @param props - StatusIconProps
 * @returns React component rendering an error status icon
 */
export const ErrorIcon: React.FC<StatusIconProps> = ({
  size = 'medium',
  color = '#f44336',
  className,
  'aria-label': ariaLabel = 'Error',
}) => {
  const pixelSize = SIZE_MAP[size];

  return (
    <Error
      sx={{
        width: pixelSize,
        height: pixelSize,
        color: color,
      }}
      className={className}
      role="img"
      aria-label={ariaLabel}
      aria-hidden={false}
    />
  );
};

/**
 * WarningIcon Component
 * 
 * Displays an amber warning triangle icon to indicate cautionary conditions,
 * validation warnings, or states requiring user attention.
 * 
 * Replaces BMS ERRMSG field warning messages with visual status indicator.
 * Color: #ff9800 (Material Design Amber 500)
 * 
 * @example
 * ```tsx
 * // Form field warning
 * <WarningIcon size="small" aria-label="Card expiring soon" />
 * 
 * // System warning message
 * <WarningIcon size="medium" aria-label="Account balance low" />
 * 
 * // Important notice
 * <WarningIcon size="large" aria-label="Action required" />
 * ```
 * 
 * @param props - StatusIconProps
 * @returns React component rendering a warning status icon
 */
export const WarningIcon: React.FC<StatusIconProps> = ({
  size = 'medium',
  color = '#ff9800',
  className,
  'aria-label': ariaLabel = 'Warning',
}) => {
  const pixelSize = SIZE_MAP[size];

  return (
    <Warning
      sx={{
        width: pixelSize,
        height: pixelSize,
        color: color,
      }}
      className={className}
      role="img"
      aria-label={ariaLabel}
      aria-hidden={false}
    />
  );
};

/**
 * InfoIcon Component
 * 
 * Displays a blue information circle icon to indicate informational messages,
 * helpful hints, or neutral system states.
 * 
 * Replaces BMS ERRMSG field informational messages with visual status indicator.
 * Color: #2196f3 (Material Design Blue 500)
 * 
 * @example
 * ```tsx
 * // Inline help text
 * <InfoIcon size="small" aria-label="Password must be 8 characters" />
 * 
 * // Informational message
 * <InfoIcon size="medium" aria-label="Processing may take a few moments" />
 * 
 * // Important information
 * <InfoIcon size="large" aria-label="System maintenance scheduled" />
 * ```
 * 
 * @param props - StatusIconProps
 * @returns React component rendering an info status icon
 */
export const InfoIcon: React.FC<StatusIconProps> = ({
  size = 'medium',
  color = '#2196f3',
  className,
  'aria-label': ariaLabel = 'Info',
}) => {
  const pixelSize = SIZE_MAP[size];

  return (
    <Info
      sx={{
        width: pixelSize,
        height: pixelSize,
        color: color,
      }}
      className={className}
      role="img"
      aria-label={ariaLabel}
      aria-hidden={false}
    />
  );
};
