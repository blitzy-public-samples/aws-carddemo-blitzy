/**
 * Asset Constants Configuration
 * 
 * This file defines standard asset configurations including icon sizes, image dimensions,
 * supported formats, and asset path mappings used across the CardDemo application.
 * 
 * Naming Convention: All asset filenames follow kebab-case naming convention
 * Example: user-placeholder.png, credit-card-icon.svg
 * 
 * Resolution Support: Assets support 1x, 2x, and 3x resolutions for high-DPI displays
 * Example: logo.png (1x), logo@2x.png (2x), logo@3x.png (3x)
 * 
 * Converted from: Mainframe BMS screen assets and COBOL display field definitions
 */

/**
 * Interface defining dimensions for assets (width and height in pixels)
 */
export interface AssetDimension {
  width: number;
  height: number;
}

/**
 * Type definition for icon size values
 */
export type IconSize = 16 | 24 | 32 | 48;

/**
 * Type definition for icon size names
 */
export type IconSizeName = 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';

/**
 * Standard icon sizes matching Material-UI conventions
 * Used for consistent icon rendering across the application
 * 
 * SMALL: 16px - Used for inline icons, table row icons
 * MEDIUM: 24px - Default icon size, used in buttons and toolbars
 * LARGE: 32px - Used in cards and prominent UI elements
 * XLARGE: 48px - Used for hero sections and large feature displays
 */
export const ICON_SIZES = {
  SMALL: 16 as const,
  MEDIUM: 24 as const,
  LARGE: 32 as const,
  XLARGE: 48 as const,
} as const;

/**
 * Standard image dimensions for common use cases
 * Defines width and height in pixels for consistent sizing
 */
export const IMAGE_DIMENSIONS = {
  /**
   * Main application logo dimensions
   */
  LOGO: {
    width: 200,
    height: 60,
  } as AssetDimension,

  /**
   * Thumbnail images for lists and grids
   */
  THUMBNAIL: {
    width: 120,
    height: 120,
  } as AssetDimension,

  /**
   * Credit card image representation
   */
  CARD_IMAGE: {
    width: 320,
    height: 200,
  } as AssetDimension,

  /**
   * Banner images for hero sections
   */
  BANNER: {
    width: 1200,
    height: 400,
  } as AssetDimension,

  /**
   * Small icon dimensions (16x16)
   */
  ICON_SMALL: {
    width: ICON_SIZES.SMALL,
    height: ICON_SIZES.SMALL,
  } as AssetDimension,

  /**
   * Medium icon dimensions (24x24)
   */
  ICON_MEDIUM: {
    width: ICON_SIZES.MEDIUM,
    height: ICON_SIZES.MEDIUM,
  } as AssetDimension,

  /**
   * Large icon dimensions (32x32)
   */
  ICON_LARGE: {
    width: ICON_SIZES.LARGE,
    height: ICON_SIZES.LARGE,
  } as AssetDimension,
} as const;

/**
 * Asset path constants for commonly used images
 * Paths are relative to the public/assets directory
 */
export const ASSET_PATHS = {
  /**
   * Main application logo (full size)
   */
  LOGO: '/assets/images/carddemo-logo.png' as const,

  /**
   * Small version of application logo for compact displays
   */
  LOGO_SMALL: '/assets/images/carddemo-logo-small.png' as const,

  /**
   * Placeholder image for user avatars
   */
  PLACEHOLDER_USER: '/assets/images/placeholder-user.png' as const,

  /**
   * Placeholder image for credit card representations
   */
  PLACEHOLDER_CARD: '/assets/images/placeholder-card.png' as const,

  /**
   * Generic placeholder image for missing assets
   */
  PLACEHOLDER_IMAGE: '/assets/images/placeholder-image.png' as const,
} as const;

/**
 * Supported image formats for the application
 * Used for validation and asset processing
 */
export const SUPPORTED_IMAGE_FORMATS = [
  'png',
  'jpg',
  'jpeg',
  'svg',
  'webp',
  'gif',
] as const;

/**
 * Resolution multipliers for high-DPI displays
 * Used to generate asset URLs for different pixel densities
 * 
 * 1x: Standard resolution (96 DPI)
 * 2x: Retina/High-DPI displays (192 DPI)
 * 3x: Ultra-high-DPI displays (288 DPI)
 */
export const RESOLUTION_MULTIPLIERS = [1, 2, 3] as const;

/**
 * Asset naming convention documentation
 * Guidelines for asset file naming across the application
 */
export const ASSET_NAMING_CONVENTION = {
  /**
   * Base naming pattern: kebab-case
   * Example: user-avatar.png, credit-card-icon.svg
   */
  pattern: 'kebab-case' as const,

  /**
   * High-DPI suffix pattern
   * Example: logo@2x.png, icon@3x.svg
   */
  highDpiSuffix: '@{multiplier}x' as const,

  /**
   * Description of naming rules
   */
  rules: [
    'Use lowercase letters only',
    'Separate words with hyphens (-)',
    'No spaces or special characters except hyphens',
    'Include resolution suffix for high-DPI assets (@2x, @3x)',
    'Use descriptive names that indicate asset purpose',
  ] as const,

  /**
   * Example asset names following the convention
   */
  examples: [
    'carddemo-logo.png',
    'carddemo-logo@2x.png',
    'user-placeholder.svg',
    'credit-card-icon.png',
    'transaction-list-icon@3x.png',
  ] as const,
} as const;

/**
 * Utility function to get icon size by name
 * 
 * @param sizeName - Name of the icon size (SMALL, MEDIUM, LARGE, XLARGE)
 * @returns Pixel size of the icon
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
 * @param basePath - Base path to the asset (e.g., '/assets/images/logo.png')
 * @param multiplier - Resolution multiplier (1, 2, or 3)
 * @returns Asset path with appropriate resolution suffix
 * 
 * @example
 * const retinaLogo = getAssetPath('/assets/images/logo.png', 2);
 * // Returns: '/assets/images/logo@2x.png'
 * 
 * @example
 * const standardLogo = getAssetPath('/assets/images/logo.png', 1);
 * // Returns: '/assets/images/logo.png' (no suffix for 1x)
 */
export const getAssetPath = (
  basePath: string,
  multiplier: 1 | 2 | 3 = 1
): string => {
  // For 1x resolution, return the base path as-is
  if (multiplier === 1) {
    return basePath;
  }

  // Extract file extension and path components
  const lastDotIndex = basePath.lastIndexOf('.');
  
  // If no extension found, append multiplier before any query params
  if (lastDotIndex === -1) {
    const queryIndex = basePath.indexOf('?');
    if (queryIndex !== -1) {
      return `${basePath.substring(0, queryIndex)}@${multiplier}x${basePath.substring(queryIndex)}`;
    }
    return `${basePath}@${multiplier}x`;
  }

  // Insert multiplier suffix before the file extension
  const pathWithoutExtension = basePath.substring(0, lastDotIndex);
  const extension = basePath.substring(lastDotIndex);
  
  return `${pathWithoutExtension}@${multiplier}x${extension}`;
};

/**
 * Utility function to get responsive asset path based on device pixel ratio
 * Automatically selects the appropriate resolution based on window.devicePixelRatio
 * 
 * @param basePath - Base path to the asset
 * @returns Asset path optimized for current device pixel ratio
 * 
 * @example
 * const logoPath = getResponsiveAssetPath('/assets/images/logo.png');
 * // On Retina display: Returns '/assets/images/logo@2x.png'
 * // On standard display: Returns '/assets/images/logo.png'
 */
export const getResponsiveAssetPath = (basePath: string): string => {
  // Determine device pixel ratio (default to 1 if not available)
  const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
  
  // Select appropriate multiplier based on device pixel ratio
  let multiplier: 1 | 2 | 3 = 1;
  
  if (dpr >= 3) {
    multiplier = 3;
  } else if (dpr >= 2) {
    multiplier = 2;
  }
  
  return getAssetPath(basePath, multiplier);
};

/**
 * Utility function to validate if a file format is supported
 * 
 * @param format - File format/extension to validate (e.g., 'png', 'jpg')
 * @returns True if the format is supported, false otherwise
 * 
 * @example
 * const isValid = isSupportedFormat('png'); // Returns true
 * const isInvalid = isSupportedFormat('bmp'); // Returns false
 */
export const isSupportedFormat = (format: string): boolean => {
  return SUPPORTED_IMAGE_FORMATS.includes(
    format.toLowerCase() as typeof SUPPORTED_IMAGE_FORMATS[number]
  );
};

/**
 * Utility function to extract file format from asset path
 * 
 * @param assetPath - Full path to the asset
 * @returns File format/extension (lowercase) or null if not found
 * 
 * @example
 * const format = getAssetFormat('/assets/images/logo.png'); // Returns 'png'
 * const format2 = getAssetFormat('/assets/logo.PNG'); // Returns 'png'
 */
export const getAssetFormat = (assetPath: string): string | null => {
  const lastDotIndex = assetPath.lastIndexOf('.');
  
  if (lastDotIndex === -1) {
    return null;
  }
  
  // Extract extension and remove any query parameters
  let extension = assetPath.substring(lastDotIndex + 1);
  const queryIndex = extension.indexOf('?');
  
  if (queryIndex !== -1) {
    extension = extension.substring(0, queryIndex);
  }
  
  return extension.toLowerCase();
};
