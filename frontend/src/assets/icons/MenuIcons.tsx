/**
 * Menu Icons Component Module
 * 
 * This module exports wrapped Material-UI icons for navigation menu items with consistent
 * styling, sizing, and usage patterns across the CardDemo application.
 * 
 * Converted from BMS menu maps (COMEN01.bms, COADM01.bms) which defined mainframe menu
 * options (OPTN001-OPTN012) to modern React icon components for web-based navigation.
 * 
 * All icon components:
 * - Wrap Material-UI icons for consistent styling
 * - Support standardized size prop (SMALL, MEDIUM, LARGE, XLARGE)
 * - Accept Material-UI SvgIcon props for flexibility
 * - Include accessibility support via aria-label
 * - Use SVG format for scalability and performance
 * 
 * Usage Example:
 * ```tsx
 * import { HomeIcon, AccountIcon } from '@/assets/icons/MenuIcons';
 * 
 * <HomeIcon size="MEDIUM" color="primary" aria-label="Home" />
 * <AccountIcon size="LARGE" className="custom-class" />
 * ```
 */

import React from 'react';
import {
  Home,
  Dashboard,
  AccountBalance,
  CreditCard,
  Receipt,
  Description,
  People,
  Settings,
  ExitToApp,
  Menu,
  ChevronLeft,
} from '@mui/icons-material';
import type { SvgIconProps } from '@mui/material/SvgIcon';
import { IconSizeName, ICON_SIZES } from '../constants';

/**
 * Icon component props interface
 * 
 * Extends Material-UI SvgIconProps with additional custom properties for
 * consistent icon rendering across the application.
 */
export interface IconComponentProps extends Omit<SvgIconProps, 'fontSize'> {
  /**
   * Icon size using standardized size names
   * Maps to pixel values defined in constants.ts:
   * - SMALL: 16px (inline icons, compact buttons, table cell icons)
   * - MEDIUM: 24px (standard button icons, navigation icons) - DEFAULT
   * - LARGE: 32px (header icons, prominent actions)
   * - XLARGE: 48px (hero sections, dialog headers)
   */
  size?: IconSizeName;
  
  /**
   * Material-UI color prop for theme-based coloring
   * Options: 'inherit' | 'primary' | 'secondary' | 'action' | 'disabled' | 'error'
   */
  color?: SvgIconProps['color'];
  
  /**
   * Additional CSS class names for custom styling
   */
  className?: string;
  
  /**
   * Accessible label for screen readers
   * Recommended for all interactive icons
   */
  'aria-label'?: string;
}

/**
 * Utility function to get icon style props from size name
 * 
 * Converts IconSizeName to Material-UI compatible style props
 * 
 * @param size - The icon size name (SMALL, MEDIUM, LARGE, XLARGE)
 * @returns Style object with width and height in pixels
 */
const getIconSizeStyle = (size: IconSizeName = 'MEDIUM'): { width: number; height: number } => {
  const pixelSize = ICON_SIZES[size];
  return {
    width: pixelSize,
    height: pixelSize,
  };
};

/**
 * HomeIcon Component
 * 
 * Wraps Material-UI Home icon for main menu navigation and home screen links.
 * Replaces BMS main menu OPTN001 navigation option.
 * 
 * Used for:
 * - Main menu home option
 * - Navigation breadcrumb home link
 * - Dashboard home button
 * 
 * @param props - Icon component props
 * @returns React component rendering Home icon
 * 
 * @example
 * <HomeIcon size="MEDIUM" color="primary" aria-label="Navigate to home" />
 */
export const HomeIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Home
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Home'}
      {...otherProps}
    />
  );
};

/**
 * DashboardIcon Component
 * 
 * Wraps Material-UI Dashboard icon for dashboard and overview screens.
 * Replaces BMS menu dashboard/overview navigation option.
 * 
 * Used for:
 * - Dashboard navigation link
 * - Account overview sections
 * - Summary screen navigation
 * 
 * @param props - Icon component props
 * @returns React component rendering Dashboard icon
 * 
 * @example
 * <DashboardIcon size="MEDIUM" color="primary" aria-label="View dashboard" />
 */
export const DashboardIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Dashboard
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Dashboard'}
      {...otherProps}
    />
  );
};

/**
 * AccountIcon Component
 * 
 * Wraps Material-UI AccountBalance icon for account management screens.
 * Supplements custom account-icon.svg and replaces BMS COACTVW/COACTUPC menu options.
 * 
 * Used for:
 * - Account management navigation (COACTUPC - account update)
 * - Account view screens (COACTVWC - account view)
 * - Account list displays
 * - Banking/financial sections
 * 
 * @param props - Icon component props
 * @returns React component rendering AccountBalance icon
 * 
 * @example
 * <AccountIcon size="LARGE" color="primary" aria-label="Manage accounts" />
 */
export const AccountIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <AccountBalance
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Account'}
      {...otherProps}
    />
  );
};

/**
 * CardIcon Component
 * 
 * Wraps Material-UI CreditCard icon for card management screens.
 * Supplements custom card-icon.svg and replaces BMS COCRDLI/COCRDUP menu options.
 * 
 * Used for:
 * - Card list displays (COCRDLIC - card list)
 * - Card selection screens (COCRDSLC - card selection)
 * - Card update forms (COCRDUPC - card update)
 * - Credit card management sections
 * 
 * @param props - Icon component props
 * @returns React component rendering CreditCard icon
 * 
 * @example
 * <CardIcon size="MEDIUM" color="primary" aria-label="Manage credit cards" />
 */
export const CardIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <CreditCard
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Card'}
      {...otherProps}
    />
  );
};

/**
 * TransactionIcon Component
 * 
 * Wraps Material-UI Receipt icon for transaction screens.
 * Supplements custom transaction-icon.svg and replaces BMS COTRN00/COTRN01/COTRN02 menu options.
 * 
 * Used for:
 * - Transaction list displays (COTRN00C - transaction list)
 * - Transaction detail views (COTRN01C - transaction detail)
 * - Transaction entry forms (COTRN02C - transaction entry)
 * - Payment history sections
 * 
 * @param props - Icon component props
 * @returns React component rendering Receipt icon
 * 
 * @example
 * <TransactionIcon size="MEDIUM" color="primary" aria-label="View transactions" />
 */
export const TransactionIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Receipt
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Transaction'}
      {...otherProps}
    />
  );
};

/**
 * ReportIcon Component
 * 
 * Wraps Material-UI Description icon for report generation screens.
 * Supplements custom report-icon.svg and replaces BMS CORPT00 menu option.
 * 
 * Used for:
 * - Report generation menu (CORPT00C - report menu)
 * - Billing screens (COBIL00C - billing)
 * - Statement generation
 * - Document download sections
 * 
 * @param props - Icon component props
 * @returns React component rendering Description icon
 * 
 * @example
 * <ReportIcon size="MEDIUM" color="primary" aria-label="Generate reports" />
 */
export const ReportIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Description
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Report'}
      {...otherProps}
    />
  );
};

/**
 * UserIcon Component
 * 
 * Wraps Material-UI People icon for user management screens.
 * Supplements custom user-icon.svg and replaces BMS COUSR00/COUSR01/COUSR02/COUSR03 menu options.
 * 
 * Used for:
 * - User list displays (COUSR00C - user list)
 * - User add forms (COUSR01C - user add)
 * - User update forms (COUSR02C - user update)
 * - User delete confirmations (COUSR03C - user delete)
 * - Admin user management sections
 * 
 * @param props - Icon component props
 * @returns React component rendering People icon
 * 
 * @example
 * <UserIcon size="MEDIUM" color="primary" aria-label="Manage users" />
 */
export const UserIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <People
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'User'}
      {...otherProps}
    />
  );
};

/**
 * SettingsIcon Component
 * 
 * Wraps Material-UI Settings icon for admin and settings screens.
 * Replaces BMS COADM01 admin menu navigation option.
 * 
 * Used for:
 * - Admin menu navigation (COADM01C - admin menu)
 * - System settings screens
 * - Configuration management
 * - Administrative functions
 * 
 * @param props - Icon component props
 * @returns React component rendering Settings icon
 * 
 * @example
 * <SettingsIcon size="MEDIUM" color="primary" aria-label="System settings" />
 */
export const SettingsIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Settings
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Settings'}
      {...otherProps}
    />
  );
};

/**
 * LogoutIcon Component
 * 
 * Wraps Material-UI ExitToApp icon for logout and exit functions.
 * Replaces BMS F3=Exit function key and COSGN00 sign-off functionality.
 * 
 * Used for:
 * - User logout buttons
 * - Session termination
 * - Sign out navigation
 * - Exit application actions
 * 
 * @param props - Icon component props
 * @returns React component rendering ExitToApp icon
 * 
 * @example
 * <LogoutIcon size="MEDIUM" color="error" aria-label="Sign out" />
 */
export const LogoutIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <ExitToApp
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Logout'}
      {...otherProps}
    />
  );
};

/**
 * MenuIcon Component
 * 
 * Wraps Material-UI Menu icon (hamburger menu) for mobile navigation drawer toggle.
 * Provides responsive menu controls for opening navigation drawer on small screens.
 * 
 * Used for:
 * - Mobile navigation drawer toggle button
 * - Hamburger menu icon
 * - Responsive navigation controls
 * - Menu open trigger
 * 
 * @param props - Icon component props
 * @returns React component rendering Menu icon
 * 
 * @example
 * <MenuIcon size="MEDIUM" color="inherit" aria-label="Open menu" />
 */
export const MenuIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <Menu
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Menu'}
      {...otherProps}
    />
  );
};

/**
 * MenuOpenIcon Component
 * 
 * Wraps Material-UI ChevronLeft icon for closing navigation drawer.
 * Provides visual indicator for collapsing or closing the navigation menu.
 * 
 * Used for:
 * - Navigation drawer close button
 * - Menu collapse trigger
 * - Back navigation in drawer
 * - Menu close indicator
 * 
 * @param props - Icon component props
 * @returns React component rendering ChevronLeft icon
 * 
 * @example
 * <MenuOpenIcon size="MEDIUM" color="inherit" aria-label="Close menu" />
 */
export const MenuOpenIcon: React.FC<IconComponentProps> = ({ 
  size = 'MEDIUM', 
  color = 'inherit',
  className,
  'aria-label': ariaLabel,
  ...otherProps 
}) => {
  const sizeStyle = getIconSizeStyle(size);
  
  return (
    <ChevronLeft
      sx={sizeStyle}
      color={color}
      className={className}
      aria-label={ariaLabel || 'Close menu'}
      {...otherProps}
    />
  );
};
