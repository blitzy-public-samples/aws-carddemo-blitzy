/**
 * Custom SVG Icon Components
 * 
 * This module provides React wrapper components for custom SVG icons used throughout
 * the CardDemo application. These icons replace character-based representations from
 * BMS 3270 terminal screens with modern, scalable vector graphics.
 * 
 * Converted from mainframe BMS screen definitions to modern React components.
 * 
 * Features:
 * - Scalable SVG icons for application-specific functions
 * - Consistent sizing following Material-UI conventions
 * - Dynamic color support via currentColor CSS
 * - Full accessibility support with ARIA attributes
 * - Type-safe props with TypeScript
 * 
 * Icon Size Mapping:
 * - SMALL: 16px - Inline icons, compact buttons, table cells
 * - MEDIUM: 24px - Standard buttons, form fields, navigation
 * - LARGE: 32px - Headers, prominent actions, feature highlights
 * - XLARGE: 48px - Hero sections, empty states, large dialogs
 * 
 * BMS Screen Replacements:
 * - CardIcon: Replaces card representations from COCRDLI, COCRDSL, COCRDUP screens
 * - TransactionIcon: Replaces transaction indicators from COTRN00, COTRN01, COTRN02 screens
 * - AccountIcon: Replaces account symbols from COACTUP, COACTVW screens
 * - UserIcon: Replaces user indicators from COUSR00-03 screens
 * - ReportIcon: Replaces report symbols from CORPT00 screen
 * 
 * @module CustomIcons
 */

import React, { CSSProperties, FC } from 'react';
import { IconSizeName } from '../constants';

// Import custom SVG files
import CardIconSvg from './card-icon.svg?react';
import TransactionIconSvg from './transaction-icon.svg?react';
import AccountIconSvg from './account-icon.svg?react';
import UserIconSvg from './user-icon.svg?react';
import ReportIconSvg from './report-icon.svg?react';

/**
 * Common props interface for all custom icon components
 * 
 * @interface IconProps
 */
export interface IconProps {
  /**
   * Size of the icon using Material-UI naming conventions
   * - SMALL: 16px (inline icons, compact UI elements)
   * - MEDIUM: 24px (default size for most use cases)
   * - LARGE: 32px (prominent features and headers)
   * - XLARGE: 48px (hero sections and large displays)
   * 
   * @default 'MEDIUM'
   */
  size?: IconSizeName;

  /**
   * Color of the icon (CSS color value)
   * Uses currentColor by default to inherit from parent text color
   * 
   * @default 'currentColor'
   * @example '#1976d2', 'rgb(25, 118, 210)', 'blue'
   */
  color?: string;

  /**
   * Additional CSS classes to apply to the icon wrapper
   * 
   * @example 'my-custom-icon-class'
   */
  className?: string;

  /**
   * Accessible label for screen readers
   * Required for icons used as standalone interactive elements
   * Can be omitted for decorative icons or when parent element provides context
   * 
   * @example 'Credit card icon'
   */
  ariaLabel?: string;

  /**
   * Inline styles to apply to the icon wrapper
   * 
   * @example { marginRight: '8px' }
   */
  style?: CSSProperties;

  /**
   * Whether the icon is purely decorative (hidden from screen readers)
   * Set to true for icons that are supplementary to adjacent text
   * 
   * @default false
   */
  decorative?: boolean;
}

/**
 * Mapping of icon size names to pixel values
 * Follows Material-UI icon sizing conventions
 */
const ICON_SIZE_MAP: Record<IconSizeName, number> = {
  SMALL: 16,
  MEDIUM: 24,
  LARGE: 32,
  XLARGE: 48,
};

/**
 * Helper function to get pixel size from size name
 * 
 * @param size - Icon size name
 * @returns Pixel size as number
 */
const getIconSize = (size: IconSizeName = 'MEDIUM'): number => {
  return ICON_SIZE_MAP[size];
};

/**
 * CardIcon Component
 * 
 * Custom SVG icon representing credit card features and functionality.
 * 
 * Replaces: Character-based card representations from BMS 3270 terminal screens
 * Source BMS Screens:
 * - COCRDLI.bms: Card listing screen with account and card number displays
 * - COCRDSL.bms: Card selection screen
 * - COCRDUP.bms: Card update and maintenance screen
 * 
 * Visual Elements:
 * - Credit card shape with rounded corners
 * - EMV chip representation
 * - Magnetic stripe bar
 * - Card number indicator lines
 * 
 * Usage Examples:
 * ```tsx
 * // Default medium size
 * <CardIcon />
 * 
 * // Large size with custom color
 * <CardIcon size="LARGE" color="#1976d2" />
 * 
 * // With accessibility label
 * <CardIcon ariaLabel="View card details" />
 * 
 * // Decorative icon next to text
 * <CardIcon decorative /> Cards
 * ```
 * 
 * @param props - Icon props including size, color, className, ariaLabel, style
 * @returns React component rendering the credit card icon
 */
export const CardIcon: FC<IconProps> = ({
  size = 'MEDIUM',
  color = 'currentColor',
  className,
  ariaLabel,
  style,
  decorative = false,
}) => {
  const pixelSize = getIconSize(size);
  
  const iconStyle: CSSProperties = {
    width: pixelSize,
    height: pixelSize,
    display: 'inline-block',
    color,
    ...style,
  };

  const svgProps = {
    width: pixelSize,
    height: pixelSize,
    role: decorative ? 'presentation' : 'img',
    'aria-label': decorative ? undefined : (ariaLabel || 'Credit card icon'),
    'aria-hidden': decorative ? true : undefined,
  };

  return (
    <span className={className} style={iconStyle}>
      <CardIconSvg {...svgProps} />
    </span>
  );
};

/**
 * TransactionIcon Component
 * 
 * Custom SVG icon representing financial transaction features and operations.
 * 
 * Replaces: Character-based transaction representations from BMS 3270 terminal screens
 * Source BMS Screens:
 * - COTRN00.bms: Transaction list display with date ranges and filtering
 * - COTRN01.bms: Transaction detail view screen
 * - COTRN02.bms: Transaction entry and posting screen
 * 
 * Visual Elements:
 * - Receipt/document shape
 * - Transaction flow arrows
 * - Financial activity indicators
 * 
 * Usage Examples:
 * ```tsx
 * // Default medium size
 * <TransactionIcon />
 * 
 * // Small size for table cells
 * <TransactionIcon size="SMALL" />
 * 
 * // With accessibility label for button
 * <TransactionIcon ariaLabel="View transactions" />
 * 
 * // Extra large for hero section
 * <TransactionIcon size="XLARGE" color="#4caf50" />
 * ```
 * 
 * @param props - Icon props including size, color, className, ariaLabel, style
 * @returns React component rendering the transaction icon
 */
export const TransactionIcon: FC<IconProps> = ({
  size = 'MEDIUM',
  color = 'currentColor',
  className,
  ariaLabel,
  style,
  decorative = false,
}) => {
  const pixelSize = getIconSize(size);
  
  const iconStyle: CSSProperties = {
    width: pixelSize,
    height: pixelSize,
    display: 'inline-block',
    color,
    ...style,
  };

  const svgProps = {
    width: pixelSize,
    height: pixelSize,
    role: decorative ? 'presentation' : 'img',
    'aria-label': decorative ? undefined : (ariaLabel || 'Transaction icon'),
    'aria-hidden': decorative ? true : undefined,
  };

  return (
    <span className={className} style={iconStyle}>
      <TransactionIconSvg {...svgProps} />
    </span>
  );
};

/**
 * AccountIcon Component
 * 
 * Custom SVG icon representing customer account management features.
 * 
 * Replaces: Character-based account representations from BMS 3270 terminal screens
 * Source BMS Screens:
 * - COACTUP.bms: Account update and maintenance screen with financial field validation
 * - COACTVW.bms: Account view and inquiry screen
 * 
 * Visual Elements:
 * - Folder/document icon
 * - Account holder representation
 * - Account identification symbols
 * 
 * Usage Examples:
 * ```tsx
 * // Default medium size
 * <AccountIcon />
 * 
 * // Large size in header
 * <AccountIcon size="LARGE" />
 * 
 * // With custom color and accessibility label
 * <AccountIcon color="#ff9800" ariaLabel="Manage accounts" />
 * 
 * // Decorative icon in menu item
 * <AccountIcon decorative /> Account Management
 * ```
 * 
 * @param props - Icon props including size, color, className, ariaLabel, style
 * @returns React component rendering the account icon
 */
export const AccountIcon: FC<IconProps> = ({
  size = 'MEDIUM',
  color = 'currentColor',
  className,
  ariaLabel,
  style,
  decorative = false,
}) => {
  const pixelSize = getIconSize(size);
  
  const iconStyle: CSSProperties = {
    width: pixelSize,
    height: pixelSize,
    display: 'inline-block',
    color,
    ...style,
  };

  const svgProps = {
    width: pixelSize,
    height: pixelSize,
    role: decorative ? 'presentation' : 'img',
    'aria-label': decorative ? undefined : (ariaLabel || 'Account icon'),
    'aria-hidden': decorative ? true : undefined,
  };

  return (
    <span className={className} style={iconStyle}>
      <AccountIconSvg {...svgProps} />
    </span>
  );
};

/**
 * UserIcon Component
 * 
 * Custom SVG icon representing user management and administration features.
 * 
 * Replaces: Character-based user representations from BMS 3270 terminal screens
 * Source BMS Screens:
 * - COUSR00.bms: User list display screen
 * - COUSR01.bms: User add/creation screen
 * - COUSR02.bms: User update/modification screen
 * - COUSR03.bms: User delete/removal screen
 * 
 * Visual Elements:
 * - Person silhouette
 * - Avatar/profile shape
 * - User identity indicators
 * 
 * Usage Examples:
 * ```tsx
 * // Default medium size
 * <UserIcon />
 * 
 * // Small size for user list
 * <UserIcon size="SMALL" />
 * 
 * // With accessibility label for navigation
 * <UserIcon ariaLabel="User management" />
 * 
 * // Large icon in admin panel header
 * <UserIcon size="LARGE" color="#2196f3" />
 * ```
 * 
 * @param props - Icon props including size, color, className, ariaLabel, style
 * @returns React component rendering the user icon
 */
export const UserIcon: FC<IconProps> = ({
  size = 'MEDIUM',
  color = 'currentColor',
  className,
  ariaLabel,
  style,
  decorative = false,
}) => {
  const pixelSize = getIconSize(size);
  
  const iconStyle: CSSProperties = {
    width: pixelSize,
    height: pixelSize,
    display: 'inline-block',
    color,
    ...style,
  };

  const svgProps = {
    width: pixelSize,
    height: pixelSize,
    role: decorative ? 'presentation' : 'img',
    'aria-label': decorative ? undefined : (ariaLabel || 'User icon'),
    'aria-hidden': decorative ? true : undefined,
  };

  return (
    <span className={className} style={iconStyle}>
      <UserIconSvg {...svgProps} />
    </span>
  );
};

/**
 * ReportIcon Component
 * 
 * Custom SVG icon representing report generation and data analysis features.
 * 
 * Replaces: Character-based report representations from BMS 3270 terminal screens
 * Source BMS Screens:
 * - CORPT00.bms: Report generation menu and selection screen
 * 
 * Visual Elements:
 * - Document with bar chart/graph
 * - Data analysis symbols
 * - Report/analytics indicators
 * 
 * Usage Examples:
 * ```tsx
 * // Default medium size
 * <ReportIcon />
 * 
 * // Large size for report menu
 * <ReportIcon size="LARGE" />
 * 
 * // With accessibility label and custom styling
 * <ReportIcon 
 *   ariaLabel="Generate report" 
 *   style={{ marginRight: '8px' }}
 * />
 * 
 * // Decorative icon in button
 * <ReportIcon decorative /> View Reports
 * ```
 * 
 * @param props - Icon props including size, color, className, ariaLabel, style
 * @returns React component rendering the report icon
 */
export const ReportIcon: FC<IconProps> = ({
  size = 'MEDIUM',
  color = 'currentColor',
  className,
  ariaLabel,
  style,
  decorative = false,
}) => {
  const pixelSize = getIconSize(size);
  
  const iconStyle: CSSProperties = {
    width: pixelSize,
    height: pixelSize,
    display: 'inline-block',
    color,
    ...style,
  };

  const svgProps = {
    width: pixelSize,
    height: pixelSize,
    role: decorative ? 'presentation' : 'img',
    'aria-label': decorative ? undefined : (ariaLabel || 'Report icon'),
    'aria-hidden': decorative ? true : undefined,
  };

  return (
    <span className={className} style={iconStyle}>
      <ReportIconSvg {...svgProps} />
    </span>
  );
};

/**
 * Default export for convenient importing
 * Exports all icon components as named exports
 */
export default {
  CardIcon,
  TransactionIcon,
  AccountIcon,
  UserIcon,
  ReportIcon,
};
