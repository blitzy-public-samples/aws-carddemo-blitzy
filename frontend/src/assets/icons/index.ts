/**
 * Icon Assets Barrel Export Module
 * 
 * This module provides a centralized import path for all icon components used throughout
 * the CardDemo React application. It consolidates status icons, menu navigation icons,
 * and custom SVG icon components into a single export point.
 * 
 * Purpose:
 * - Simplify icon imports across the application
 * - Provide consistent icon usage patterns
 * - Resolve naming conflicts between Material-UI and custom SVG icons
 * - Support tree-shaking for optimal bundle size
 * 
 * Icon Categories:
 * 
 * 1. Status Icons (from StatusIcons.tsx):
 *    - SuccessIcon: Green checkmark for successful operations
 *    - ErrorIcon: Red error circle for failed operations
 *    - WarningIcon: Amber warning triangle for cautions
 *    - InfoIcon: Blue info circle for informational messages
 * 
 * 2. Menu Navigation Icons (from MenuIcons.tsx):
 *    - HomeIcon: Main menu navigation
 *    - DashboardIcon: Dashboard/overview screens
 *    - SettingsIcon: Admin and settings screens
 *    - LogoutIcon: User logout and exit functions
 *    - MenuIcon: Mobile navigation drawer toggle (hamburger menu)
 *    - MenuOpenIcon: Navigation drawer close button
 * 
 * 3. Custom SVG Icons (from CustomIcons.tsx):
 *    These are application-specific icons that replace BMS 3270 character-based UI elements:
 *    - CardIcon: Credit card features (COCRDLI, COCRDSL, COCRDUP screens)
 *    - TransactionIcon: Financial transactions (COTRN00, COTRN01, COTRN02 screens)
 *    - AccountIcon: Customer accounts (COACTUP, COACTVW screens)
 *    - UserIcon: User management (COUSR00-03 screens)
 *    - ReportIcon: Report generation (CORPT00 screen)
 * 
 * 4. Material-UI Based Icons (from MenuIcons.tsx, prefixed with "Mui"):
 *    These supplement the custom SVG icons with Material-UI variants:
 *    - MuiCardIcon: Material-UI CreditCard icon
 *    - MuiTransactionIcon: Material-UI Receipt icon
 *    - MuiAccountIcon: Material-UI AccountBalance icon
 *    - MuiUserIcon: Material-UI People icon
 *    - MuiReportIcon: Material-UI Description icon
 * 
 * Naming Conflict Resolution:
 * 
 * Both MenuIcons.tsx and CustomIcons.tsx export icons with the same names
 * (CardIcon, TransactionIcon, AccountIcon, UserIcon, ReportIcon). To resolve this:
 * 
 * - Default exports (without prefix): Custom SVG icons from CustomIcons.tsx
 *   These are the primary icons for the application, providing custom-designed graphics
 *   that specifically replace BMS 3270 character-based representations.
 * 
 * - Prefixed exports (Mui*): Material-UI icons from MenuIcons.tsx
 *   These provide alternative Material-UI-based icons for situations where
 *   standard Material Design icons are preferred or needed for consistency.
 * 
 * Usage Examples:
 * 
 * ```tsx
 * // Import status icons
 * import { SuccessIcon, ErrorIcon } from '@/assets/icons';
 * 
 * // Import custom SVG icons (default for business features)
 * import { CardIcon, TransactionIcon } from '@/assets/icons';
 * 
 * // Import Material-UI variants when needed
 * import { MuiCardIcon, MuiAccountIcon } from '@/assets/icons';
 * 
 * // Import navigation icons
 * import { HomeIcon, DashboardIcon, MenuIcon } from '@/assets/icons';
 * 
 * // Use in components
 * <SuccessIcon size="medium" aria-label="Operation successful" />
 * <CardIcon size="LARGE" color="#1976d2" />
 * <MuiCardIcon size="MEDIUM" color="primary" />
 * ```
 * 
 * Migration Notes:
 * 
 * This module is part of the COBOL-to-React migration from mainframe BMS 3270 terminal
 * screens to modern web interfaces. All icons replace character-based UI elements with
 * scalable SVG graphics optimized for modern browsers and responsive designs.
 * 
 * Converted from: BMS map character representations (COSGN00.bms through COUSR03.bms)
 * Target: React SPA with Material-UI component library and custom SVG assets
 * 
 * @module assets/icons
 */

// ============================================================================
// Status Icons (Material-UI wrapped, semantic colors)
// ============================================================================

export {
  SuccessIcon,
  ErrorIcon,
  WarningIcon,
  InfoIcon,
} from './StatusIcons';

// ============================================================================
// Menu Navigation Icons (Material-UI wrapped, no naming conflicts)
// ============================================================================

export {
  HomeIcon,
  DashboardIcon,
  SettingsIcon,
  LogoutIcon,
  MenuIcon,
  MenuOpenIcon,
} from './MenuIcons';

// ============================================================================
// Custom SVG Icons (Default exports for business features)
// ============================================================================
// 
// These are the primary icons for credit card, transaction, account, user,
// and report features. They use custom SVG designs that specifically replace
// BMS 3270 character-based representations from mainframe terminal screens.
// ============================================================================

export {
  CardIcon,
  TransactionIcon,
  AccountIcon,
  UserIcon,
  ReportIcon,
} from './CustomIcons';

// ============================================================================
// Material-UI Icon Variants (Prefixed with "Mui" to avoid conflicts)
// ============================================================================
// 
// These exports provide Material-UI-based alternatives to the custom SVG icons.
// Use these when you need standard Material Design icons or want consistency
// with other Material-UI components in your interface.
// 
// Import with aliases to resolve naming conflicts:
// - MenuIcons exports CardIcon → Re-exported as MuiCardIcon
// - MenuIcons exports TransactionIcon → Re-exported as MuiTransactionIcon
// - MenuIcons exports AccountIcon → Re-exported as MuiAccountIcon
// - MenuIcons exports UserIcon → Re-exported as MuiUserIcon
// - MenuIcons exports ReportIcon → Re-exported as MuiReportIcon
// ============================================================================

export {
  CardIcon as MuiCardIcon,
  TransactionIcon as MuiTransactionIcon,
  AccountIcon as MuiAccountIcon,
  UserIcon as MuiUserIcon,
  ReportIcon as MuiReportIcon,
} from './MenuIcons';

// ============================================================================
// Type Re-exports (for convenience)
// ============================================================================

export type { StatusIconProps } from './StatusIcons';
export type { IconComponentProps } from './MenuIcons';
export type { IconProps } from './CustomIcons';

// ============================================================================
// Default Export (for convenience when importing all icons)
// ============================================================================

import * as StatusIcons from './StatusIcons';
import * as MenuIcons from './MenuIcons';
import * as CustomIcons from './CustomIcons';

export default {
  // Status Icons
  ...StatusIcons,
  
  // Menu Navigation Icons (excluding conflicting names)
  HomeIcon: MenuIcons.HomeIcon,
  DashboardIcon: MenuIcons.DashboardIcon,
  SettingsIcon: MenuIcons.SettingsIcon,
  LogoutIcon: MenuIcons.LogoutIcon,
  MenuIcon: MenuIcons.MenuIcon,
  MenuOpenIcon: MenuIcons.MenuOpenIcon,
  
  // Custom SVG Icons (default business feature icons)
  ...CustomIcons,
  
  // Material-UI Icon Variants (with Mui prefix)
  MuiCardIcon: MenuIcons.CardIcon,
  MuiTransactionIcon: MenuIcons.TransactionIcon,
  MuiAccountIcon: MenuIcons.AccountIcon,
  MuiUserIcon: MenuIcons.UserIcon,
  MuiReportIcon: MenuIcons.ReportIcon,
};
