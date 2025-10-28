/**
 * Asset Constants and Configuration
 * 
 * This file defines standard asset configurations including icon sizes, image dimensions,
 * supported formats, and asset path mappings used across the CardDemo application.
 * 
 * Converted from mainframe BMS screen resource definitions to modern web asset management.
 * Maintains consistent asset sizing and naming conventions across the React SPA.
 * 
 * Asset Naming Convention:
 * - All asset files MUST follow kebab-case naming convention
 * - Examples: logo-large.png, user-placeholder.svg, card-icon-small.png
 * - Resolution variants: asset-name@2x.png, asset-name@3x.png
 * 
 * High-DPI Display Support:
 * - Standard (1x) resolution for baseline displays
 * - 2x resolution for Retina and high-DPI displays
 * - 3x resolution for ultra-high-DPI mobile displays
 */

/**
 * Type definition for icon size values in pixels
 */
export type IconSize = number;

/**
 * Type definition for icon size names
 */
export type IconSizeName = 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';

/**
 * Interface for asset dimensions
 */
export interface AssetDimension {
  /**
   * Width of the asset in pixels
   */
  width: number;
  
  /**
   * Height of the asset in pixels
   */
  height: number;
}

/**
 * Standard icon sizes matching Material-UI conventions
 * These sizes ensure visual consistency across all UI components
 * 
 * Usage:
 * - SMALL (16px): Inline icons, compact buttons, table cell icons
 * - MEDIUM (24px): Standard button icons, form field icons, navigation icons
 * - LARGE (32px): Header icons, prominent action buttons, feature highlights
 * - XLARGE (48px): Hero section icons, empty state illustrations, dialog headers
 */
export const ICON_SIZES = {
  /**
   * Small icon size - 16x16 pixels
   * Use for inline text icons, compact buttons, and table cell indicators
   */
  SMALL: 16 as IconSize,
  
  /**
   * Medium icon size - 24x24 pixels (Material-UI default)
   * Use for standard buttons, form fields, navigation items, and toolbar actions
   */
  MEDIUM: 24 as IconSize,
  
  /**
   * Large icon size - 32x32 pixels
   * Use for prominent actions, section headers, and featured content
   */
  LARGE: 32 as IconSize,
  
  /**
   * Extra large icon size - 48x48 pixels
   * Use for hero sections, empty states, large dialogs, and key visual elements
   */
  XLARGE: 48 as IconSize,
} as const;

/**
 * Standard image dimensions for common use cases
 * These dimensions maintain visual consistency and optimize layout rendering
 */
export const IMAGE_DIMENSIONS = {
  /**
   * Main application logo dimensions
   * Used in header, login screen, and branding elements
   */
  LOGO: {
    width: 200,
    height: 60,
  } as AssetDimension,
  
  /**
   * Thumbnail size for list views and preview cards
   * Used in account lists, card lists, and search results
   */
  THUMBNAIL: {
    width: 80,
    height: 80,
  } as AssetDimension,
  
  /**
   * Credit card image dimensions
   * Maintains realistic card aspect ratio (1.586:1 - ISO/IEC 7810 standard)
   */
  CARD_IMAGE: {
    width: 320,
    height: 202,
  } as AssetDimension,
  
  /**
   * Banner image dimensions for promotional content
   * Used in dashboard banners and marketing sections
   */
  BANNER: {
    width: 1200,
    height: 300,
  } as AssetDimension,
  
  /**
   * Small icon image dimensions
   * Used for status indicators and small visual markers
   */
  ICON_SMALL: {
    width: 16,
    height: 16,
  } as AssetDimension,
  
  /**
   * Medium icon image dimensions
   * Used for standard UI icons and action buttons
   */
  ICON_MEDIUM: {
    width: 24,
    height: 24,
  } as AssetDimension,
  
  /**
   * Large icon image dimensions
   * Used for prominent features and hero sections
   */
  ICON_LARGE: {
    width: 48,
    height: 48,
  } as AssetDimension,
} as const;

/**
 * Asset path constants for commonly used images
 * Centralized asset paths prevent hardcoded strings throughout the codebase
 * 
 * Note: Paths are relative to the public/ directory
 */
export const ASSET_PATHS = {
  /**
   * Main application logo (full size)
   * Used in header and main navigation
   */
  LOGO: '/assets/images/logo.png',
  
  /**
   * Small application logo
   * Used in compact headers and mobile navigation
   */
  LOGO_SMALL: '/assets/images/logo-small.png',
  
  /**
   * Placeholder image for user profiles
   * Used when user profile image is not available
   */
  PLACEHOLDER_USER: '/assets/images/placeholder-user.svg',
  
  /**
   * Placeholder image for credit cards
   * Used when card image is not available or during loading
   */
  PLACEHOLDER_CARD: '/assets/images/placeholder-card.svg',
  
  /**
   * Generic placeholder image
   * Used for any missing image content
   */
  PLACEHOLDER_IMAGE: '/assets/images/placeholder-image.svg',
} as const;

/**
 * Supported image file formats
 * Defines acceptable image formats for upload and display validation
 */
export const SUPPORTED_IMAGE_FORMATS = [
  'image/jpeg',
  'image/jpg',
  'image/png',
  'image/svg+xml',
  'image/webp',
] as const;

/**
 * Resolution multipliers for high-DPI displays
 * Used to serve appropriate image resolution based on device pixel ratio
 * 
 * - 1x: Standard resolution (device pixel ratio 1.0)
 * - 2x: Retina displays (device pixel ratio 2.0)
 * - 3x: Ultra-high DPI mobile displays (device pixel ratio 3.0)
 */
export const RESOLUTION_MULTIPLIERS = [1, 2, 3] as const;

/**
 * Asset naming convention documentation
 * Provides guidelines for creating and naming asset files
 */
export const ASSET_NAMING_CONVENTION = {
  /**
   * Base naming convention
   */
  convention: 'kebab-case',
  
  /**
   * Description of the naming standard
   */
  description: 'All asset files must use lowercase letters with hyphens separating words',
  
  /**
   * Examples of correct naming
   */
  examples: [
    'logo-large.png',
    'user-placeholder.svg',
    'card-icon-small.png',
    'banner-image.jpg',
  ],
  
  /**
   * High-DPI resolution suffix pattern
   */
  resolutionSuffix: {
    '1x': '',
    '2x': '@2x',
    '3x': '@3x',
  },
  
  /**
   * Example with resolution variants
   */
  resolutionExamples: [
    'logo.png',      // 1x resolution
    'logo@2x.png',   // 2x resolution
    'logo@3x.png',   // 3x resolution
  ],
} as const;

/**
 * Utility function to get icon size by name
 * 
 * @param sizeName - The name of the icon size (SMALL, MEDIUM, LARGE, XLARGE)
 * @returns The icon size in pixels
 * 
 * @example
 * const iconSize = getIconSize('MEDIUM'); // Returns 24
 */
export const getIconSize = (sizeName: IconSizeName): IconSize => {
  return ICON_SIZES[sizeName];
};

/**
 * Utility function to get asset path with resolution multiplier
 * 
 * Generates the appropriate asset path based on device pixel ratio
 * Automatically selects the highest available resolution that doesn't exceed device capabilities
 * 
 * @param basePath - The base path of the asset (without resolution suffix)
 * @param devicePixelRatio - The device pixel ratio (defaults to window.devicePixelRatio)
 * @returns The asset path with appropriate resolution suffix
 * 
 * @example
 * // On a 2x Retina display
 * const logoPath = getAssetPath('/assets/images/logo.png'); 
 * // Returns '/assets/images/logo@2x.png'
 * 
 * @example
 * // On a standard display
 * const logoPath = getAssetPath('/assets/images/logo.png'); 
 * // Returns '/assets/images/logo.png'
 */
export const getAssetPath = (
  basePath: string,
  devicePixelRatio: number = typeof window !== 'undefined' ? window.devicePixelRatio : 1
): string => {
  // Determine the appropriate resolution multiplier
  // Use the highest multiplier that doesn't exceed the device pixel ratio
  let multiplier = 1;
  
  if (devicePixelRatio >= 3) {
    multiplier = 3;
  } else if (devicePixelRatio >= 2) {
    multiplier = 2;
  }
  
  // If multiplier is 1, return the base path unchanged
  if (multiplier === 1) {
    return basePath;
  }
  
  // Extract file extension and base name
  const lastDotIndex = basePath.lastIndexOf('.');
  
  if (lastDotIndex === -1) {
    // No extension found, append multiplier suffix
    return `${basePath}@${multiplier}x`;
  }
  
  // Insert resolution suffix before file extension
  const baseWithoutExtension = basePath.substring(0, lastDotIndex);
  const extension = basePath.substring(lastDotIndex);
  
  return `${baseWithoutExtension}@${multiplier}x${extension}`;
};
