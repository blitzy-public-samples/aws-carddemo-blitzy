/**
 * Icons Barrel Export Module
 * 
 * This module provides a centralized export point for all icon assets and components
 * used throughout the CardDemo application. It re-exports icon components from three
 * source modules with proper naming conflict resolution.
 * 
 * Purpose:
 * - Provide centralized import path: import { CardIcon, SuccessIcon } from '@/assets/icons'
 * - Resolve naming conflicts between Material-UI and custom SVG icons
 * - Support tree-shaking with named exports
 * - Maintain consistent icon usage patterns across the application
 * 
 * Module Organization:
 * 
 * 1. STATUS ICONS (StatusIcons.tsx):
 *    - SuccessIcon: Green checkmark for successful operations
 *    - ErrorIcon: Red error indicator for failed operations
 *    - WarningIcon: Amber warning for cautionary conditions
 *    - InfoIcon: Blue information indicator for helpful messages
 * 
 * 2. MENU/NAVIGATION ICONS (MenuIcons.tsx):
 *    - HomeIcon: Main menu home navigation
 *    - DashboardIcon: Dashboard and overview screens
 *    - SettingsIcon: Admin and settings functions
 *    - LogoutIcon: User logout and session termination
 *    - MenuIcon: Mobile navigation drawer toggle (hamburger menu)
 *    - MenuOpenIcon: Navigation drawer close button
 * 
 * 3. CUSTOM SVG ICONS (CustomIcons.tsx):
 *    - CardIcon: Credit card management features (default export)
 *    - TransactionIcon: Transaction operations (default export)
 *    - AccountIcon: Account management features (default export)
 *    - UserIcon: User administration features (default export)
 *    - ReportIcon: Report generation features (default export)
 * 
 * 4. MATERIAL-UI ICON VARIANTS (MenuIcons.tsx with Mui prefix):
 *    - MuiCardIcon: Material-UI CreditCard icon
 *    - MuiTransactionIcon: Material-UI Receipt icon
 *    - MuiAccountIcon: Material-UI AccountBalance icon
 *    - MuiUserIcon: Material-UI People icon
 *    - MuiReportIcon: Material-UI Description icon
 * 
 * Naming Conflict Resolution:
 * 
 * Five icon names (CardIcon, TransactionIcon, AccountIcon, UserIcon, ReportIcon) exist
 * in both MenuIcons.tsx (Material-UI wrappers) and CustomIcons.tsx (custom SVG icons).
 * 
 * Resolution Strategy:
 * - Custom SVG icons from CustomIcons.tsx are the DEFAULT exports (without prefix)
 * - Material-UI icon variants from MenuIcons.tsx are exported with "Mui" prefix
 * - This prioritizes application-specific custom icons while maintaining access to Material-UI versions
 * 
 * Migration from BMS 3270 Screens:
 * 
 * These icons replace character-based UI elements from mainframe BMS maps:
 * - Status icons replace ERRMSG field indicators (COSGN00.bms, COMEN01.bms, COACTUP.bms)
 * - Menu icons replace BMS menu OPTN### list items (COMEN01.bms, COADM01.bms)
 * - Custom icons replace terminal character representations (COCRDLI.bms, COTRN00.bms, etc.)
 * 
 * Example usage:
 * 
 * Import custom SVG icons (default - application-specific):
 *   import { CardIcon, TransactionIcon, AccountIcon } from '@/assets/icons';
 * 
 * Import Material-UI icon variants (with Mui prefix):
 *   import { MuiCardIcon, MuiTransactionIcon, MuiAccountIcon } from '@/assets/icons';
 * 
 * Import status icons:
 *   import { SuccessIcon, ErrorIcon, WarningIcon, InfoIcon } from '@/assets/icons';
 * 
 * Import navigation icons:
 *   import { HomeIcon, DashboardIcon, MenuIcon, LogoutIcon } from '@/assets/icons';
 * 
 * Type Exports:
 * 
 * This module also re-exports TypeScript type definitions for icon component props:
 * - StatusIconProps: Props for status icon components (SuccessIcon, ErrorIcon, etc.)
 * - IconComponentProps: Props for menu/navigation icon components
 * - IconProps: Props for custom SVG icon components
 * - IconSizeType: Type definition for status icon sizes ('small' | 'medium' | 'large')
 * 
 * @module icons
 */

// ============================================================================
// STATUS ICONS - StatusIcons.tsx
// ============================================================================

/**
 * Status indicator icon components from StatusIcons.tsx
 * 
 * These icons provide visual feedback for form validation, system messages,
 * and operation results throughout the application.
 */
export {
  SuccessIcon,
  ErrorIcon,
  WarningIcon,
  InfoIcon,
  // Type exports
  type StatusIconProps,
  type IconSizeType,
} from './StatusIcons';

// ============================================================================
// MENU/NAVIGATION ICONS - MenuIcons.tsx
// ============================================================================

/**
 * Menu and navigation icon components from MenuIcons.tsx
 * 
 * Note: CardIcon, TransactionIcon, AccountIcon, UserIcon, and ReportIcon from
 * MenuIcons.tsx are exported with "Mui" prefix to avoid naming conflicts with
 * custom SVG icons from CustomIcons.tsx.
 */
export {
  HomeIcon,
  DashboardIcon,
  SettingsIcon,
  LogoutIcon,
  MenuIcon,
  MenuOpenIcon,
  // Type export
  type IconComponentProps,
} from './MenuIcons';

/**
 * Material-UI icon variants with Mui prefix to resolve naming conflicts
 * 
 * These are the Material-UI wrapped versions of icons that also have custom
 * SVG implementations in CustomIcons.tsx. Use these when you specifically need
 * the Material-UI icon style instead of the custom application-specific icons.
 */
export {
  CardIcon as MuiCardIcon,
  TransactionIcon as MuiTransactionIcon,
  AccountIcon as MuiAccountIcon,
  UserIcon as MuiUserIcon,
  ReportIcon as MuiReportIcon,
} from './MenuIcons';

// ============================================================================
// CUSTOM SVG ICONS - CustomIcons.tsx (DEFAULT EXPORTS)
// ============================================================================

/**
 * Custom SVG icon components from CustomIcons.tsx
 * 
 * These are the DEFAULT exports (without prefix) for CardIcon, TransactionIcon,
 * AccountIcon, UserIcon, and ReportIcon. They represent application-specific
 * custom SVG implementations that replace character-based representations from
 * BMS 3270 terminal screens.
 * 
 * Prioritization Rationale:
 * - Custom icons are application-specific and designed for CardDemo branding
 * - Material-UI variants remain available with Mui prefix for flexibility
 * - This approach encourages consistent use of custom icons throughout the app
 */
export {
  CardIcon,
  TransactionIcon,
  AccountIcon,
  UserIcon,
  ReportIcon,
  // Type export
  type IconProps,
} from './CustomIcons';

// ============================================================================
// DOCUMENTATION NOTES
// ============================================================================

/**
 * SVG File Paths (for direct SVG imports if needed)
 * 
 * If you need to import the raw SVG files directly (e.g., for use with img tags
 * or in contexts where the React component wrappers are not suitable), you can
 * import them directly:
 * 
 * Example:
 *   import cardIconUrl from '@/assets/icons/card-icon.svg';
 *   import transactionIconUrl from '@/assets/icons/transaction-icon.svg';
 * 
 * However, it is recommended to use the React component wrappers exported from
 * this module for consistent sizing, coloring, and accessibility support.
 */

/**
 * Icon Sizing Guidelines
 * 
 * All icon components support standardized sizing:
 * 
 * Status Icons (StatusIcons.tsx):
 * - small: 16px (compact displays, inline text, table cells)
 * - medium: 24px (standard buttons, form fields) - DEFAULT
 * - large: 32px (prominent actions, headers)
 * 
 * Navigation Icons (MenuIcons.tsx):
 * - SMALL: 16px (inline icons, compact UI)
 * - MEDIUM: 24px (standard navigation) - DEFAULT
 * - LARGE: 32px (header icons)
 * - XLARGE: 48px (hero sections)
 * 
 * Custom SVG Icons (CustomIcons.tsx):
 * - SMALL: 16px (inline icons, compact UI)
 * - MEDIUM: 24px (standard usage) - DEFAULT
 * - LARGE: 32px (prominent features)
 * - XLARGE: 48px (large displays)
 * 
 * Example:
 *   SuccessIcon size="small"  - 16px (status icons use lowercase)
 *   HomeIcon size="MEDIUM"    - 24px (navigation icons use UPPERCASE)
 *   CardIcon size="LARGE"     - 32px (custom icons use UPPERCASE)
 */

/**
 * Accessibility Guidelines
 * 
 * All icon components support accessibility attributes:
 * 
 * 1. Provide aria-label for standalone icons:
 *    CardIcon ariaLabel="View card details"
 * 
 * 2. Mark decorative icons (next to text):
 *    CardIcon decorative - followed by text label
 * 
 * 3. Status icons automatically include role="img" and aria-label
 * 
 * 4. Use aria-hidden for purely decorative icons:
 *    Icons marked as decorative will automatically set aria-hidden to true
 */

/**
 * Tree-Shaking Support
 * 
 * This module uses named exports to support tree-shaking in build tools.
 * Only the icons you import will be included in your production bundle.
 * 
 * Example:
 *   import { CardIcon, SuccessIcon } from '@/assets/icons';
 *   Only CardIcon and SuccessIcon will be bundled
 */
