# Assets Folder

## Overview

This folder contains all static assets used throughout the CardDemo React application, including images, icons, and other media files. Assets are organized into logical subfolders and follow consistent naming conventions to ensure maintainability and ease of use.

### Folder Structure

```
assets/
├── images/          # Application images (logos, backgrounds, illustrations)
├── icons/           # Icon files (SVG, PNG icons for UI elements)
├── constants.ts     # Asset configuration constants and utilities
├── types.d.ts       # TypeScript type declarations for asset imports
└── README.md        # This documentation file
```

## Folder Organization

### `images/`
Contains raster and vector images used throughout the application:
- Application logos (main logo, small logo variants)
- Card images and placeholders
- Background images
- User avatars and placeholders
- Illustrations and marketing assets
- Any other photographic or graphic content

**Recommended formats:**
- **SVG**: Preferred for logos, illustrations, and scalable graphics
- **PNG**: For images requiring transparency
- **WebP**: For optimized photographic content (with fallbacks)
- **JPG**: For photographic content without transparency

### `icons/`
Contains icon files used in UI components:
- Navigation icons
- Action icons (edit, delete, save, cancel)
- Status indicators (success, warning, error, info)
- Feature-specific icons (card, transaction, account, user)
- System icons (close, menu, search, filter)

**Recommended formats:**
- **SVG**: Strongly preferred for icons (scalable, small file size, styleable with CSS)
- **PNG**: Only when SVG is not available (provide @1x, @2x, @3x variants for high-DPI displays)

## Naming Conventions

**All assets MUST follow kebab-case naming convention:**

✅ **Correct:**
```
logo-main.svg
card-placeholder.png
icon-edit.svg
background-gradient.webp
user-avatar-default.png
transaction-success-icon.svg
```

❌ **Incorrect:**
```
LogoMain.svg           # PascalCase - not allowed
card_placeholder.png   # snake_case - not allowed
iconEdit.svg           # camelCase - not allowed
Background-Gradient.webp # Mixed case - not allowed
```

### Naming Pattern Guidelines

- Use descriptive, lowercase names separated by hyphens
- Start with the general category, then add specific descriptors
- For resolution variants, use `@2x`, `@3x` suffix before file extension:
  ```
  icon-user@1x.png
  icon-user@2x.png
  icon-user@3x.png
  ```
- For state variants, include state in the name:
  ```
  button-primary-hover.svg
  checkbox-checked.svg
  toggle-disabled.svg
  ```

## Usage Guidelines

### Importing Assets in React Components

Assets are referenced using ES6 import statements and processed by the Vite bundler:

```tsx
// Import images
import logoMain from '@/assets/images/logo-main.svg';
import cardPlaceholder from '@/assets/images/card-placeholder.png';

// Import icons
import editIcon from '@/assets/icons/icon-edit.svg';
import deleteIcon from '@/assets/icons/icon-delete.svg';

// Use in JSX
function Header() {
  return (
    <div>
      <img src={logoMain} alt="CardDemo Logo" />
      <img src={editIcon} alt="Edit" className="icon" />
    </div>
  );
}
```

### Using Asset Constants

For commonly used assets, import from `constants.ts`:

```tsx
import { ASSET_PATHS, ICON_SIZES, IMAGE_DIMENSIONS } from '@/assets/constants';

function UserProfile() {
  return (
    <div>
      <img 
        src={ASSET_PATHS.PLACEHOLDER_USER} 
        alt="User placeholder"
        width={IMAGE_DIMENSIONS.THUMBNAIL.width}
        height={IMAGE_DIMENSIONS.THUMBNAIL.height}
      />
    </div>
  );
}
```

### TypeScript Support

Asset imports are fully typed through `types.d.ts`. TypeScript will recognize asset imports and provide proper type checking:

```tsx
// TypeScript knows these imports return strings
import logo from './images/logo.png';  // logo: string
import icon from './icons/check.svg';   // icon: string
```

## Vite Asset Handling Features

The Vite bundler provides sophisticated asset processing automatically:

### Automatic Optimizations

1. **Content Hashing**: Assets are automatically versioned with content-based hashes for cache busting
   ```
   logo-main.svg → logo-main.a3f2b8c9.svg
   ```

2. **Base64 Inlining**: Small assets (< 4KB) are automatically inlined as base64 data URLs
   ```tsx
   import smallIcon from './icon-small.svg';
   // smallIcon = "data:image/svg+xml;base64,..."
   ```

3. **Compression**: Assets are automatically compressed in production builds

4. **Format Conversion**: Modern image formats (WebP, AVIF) are generated automatically when configured

### Asset URL Handling

```tsx
// Relative imports - processed by Vite
import logo from '../assets/images/logo.svg';

// Public folder assets - not processed, served as-is
// Place in /public folder for assets that shouldn't be processed
<img src="/favicon.ico" />

// Dynamic imports
const imagePath = `/assets/images/${dynamicName}.png`;
```

### Import Queries

Vite supports special import queries for advanced use cases:

```tsx
// Import as raw string (useful for inline SVG manipulation)
import logoRaw from './logo.svg?raw';

// Import as URL (force URL even for small files)
import logoUrl from './logo.svg?url';

// Import as Worker
import Worker from './worker.js?worker';
```

## Asset Optimization Guidelines

### Image Optimization Best Practices

1. **Compress all images before adding to repository:**
   - Use tools like ImageOptim, TinyPNG, or Squoosh
   - Target: PNG < 200KB, JPG < 300KB, WebP < 250KB
   - Maintain visual quality while reducing file size

2. **Choose appropriate format:**
   - **Logos/Icons**: SVG (scalable, small, styleable)
   - **Photos with transparency**: PNG
   - **Photos without transparency**: WebP (with JPG fallback)
   - **Simple graphics**: SVG or optimized PNG

3. **Provide multiple resolutions for raster images:**
   ```
   icon-user@1x.png  (24×24)
   icon-user@2x.png  (48×48) 
   icon-user@3x.png  (72×72)
   ```

4. **Optimize SVG files:**
   - Remove unnecessary metadata
   - Use SVGO or SVGOMG tools
   - Keep viewBox attribute for proper scaling
   - Remove inline styles when possible (use CSS classes instead)

### Icon Guidelines

1. **Prefer SVG icons:**
   - Infinitely scalable
   - Small file size
   - Can be styled with CSS (color, size, etc.)
   - No pixelation on high-DPI displays

2. **Use consistent icon sizing:**
   - Reference `ICON_SIZES` from `constants.ts`
   - Standard sizes: 16px (small), 24px (medium), 32px (large), 48px (xlarge)

3. **Ensure accessibility:**
   - Provide meaningful `alt` text for icon images
   - Use `aria-label` for icon-only buttons
   - Consider using icon fonts or SVG sprites for repeated icons

### High-DPI Display Support

Support multiple pixel densities for raster images:

```tsx
<img
  src={icon1x}
  srcSet={`${icon1x} 1x, ${icon2x} 2x, ${icon3x} 3x`}
  alt="Icon"
/>
```

Or use the `IMAGE_DIMENSIONS` and `RESOLUTION_MULTIPLIERS` from `constants.ts` to programmatically generate srcSet attributes.

## Adding New Assets

### Step-by-Step Process

1. **Prepare the asset:**
   - Optimize file size (compress images, minify SVGs)
   - Ensure proper format (SVG for icons/logos, WebP/PNG for images)
   - Verify visual quality at target display sizes

2. **Name the file:**
   - Use kebab-case naming convention
   - Be descriptive and specific
   - Example: `card-transaction-icon.svg`, `user-profile-placeholder.png`

3. **Place in appropriate subfolder:**
   - Images → `images/`
   - Icons → `icons/`

4. **Add to constants (if frequently used):**
   ```typescript
   // In constants.ts
   export const ASSET_PATHS = {
     // ... existing paths
     NEW_ASSET: new URL('./images/new-asset.png', import.meta.url).href,
   };
   ```

5. **Update TypeScript types (if new file extension):**
   ```typescript
   // In types.d.ts (only if adding new format)
   declare module '*.newformat' {
     const src: string;
     export default src;
   }
   ```

6. **Import and use in components:**
   ```tsx
   import newAsset from '@/assets/images/new-asset.png';
   ```

7. **Commit to version control:**
   ```bash
   git add src/assets/images/new-asset.png
   git commit -m "Add new asset: new-asset.png"
   ```

### Asset Checklist

Before adding a new asset, verify:

- [ ] File is optimized and compressed
- [ ] Filename follows kebab-case convention
- [ ] File is placed in correct subfolder (images/ or icons/)
- [ ] File size is reasonable (< 500KB for images, < 100KB for icons)
- [ ] For raster icons, @2x and @3x variants are provided
- [ ] SVG files are cleaned and optimized
- [ ] Asset is tested in actual UI at various screen sizes
- [ ] Asset is committed to Git

## Migration Notes: BMS to Modern Web Assets

### Context: Mainframe to Cloud Transition

This CardDemo React application replaces a legacy IBM mainframe system that used BMS (Basic Mapping Support) for 3270 terminal screens. The original system displayed:

- **ASCII art** for logos and graphics
- **3270 character graphics** (box-drawing characters, line art)
- **Monochrome text-based UI** with no true images or icons
- **Fixed 80×24 character grid layout**

### Transformation to Modern Web Assets

The React application introduces rich visual assets that were impossible in the 3270 terminal environment:

| Legacy BMS 3270 | Modern React Web |
|-----------------|------------------|
| ASCII art logos (text characters) | SVG/PNG logos with full color and transparency |
| Box-drawing characters (┌─┐│└┘) | Modern UI components with CSS styling |
| Fixed EBCDIC character set | Full Unicode support with web fonts |
| No images, no icons | Rich image and icon libraries |
| Monochrome or 8-color palette | Full 16.7M color support (24-bit) |
| Fixed 80×24 character grid | Responsive, fluid layouts |

### Asset Replacement Examples

**BMS Screen Headers (ASCII art):**
```
╔════════════════════════════════════════════════════════════╗
║              CARDDEMO - ACCOUNT MANAGEMENT                 ║
╚════════════════════════════════════════════════════════════╝
```
**Replaced with:**
```tsx
<Header logo={logoMain} title="Account Management" />
```

**BMS Field Markers (character graphics):**
```
[*] Required field
[i] Information
[!] Warning
```
**Replaced with:**
```tsx
<Icon name="required" /> Required field
<Icon name="info" /> Information  
<Icon name="warning" /> Warning
```

**BMS Status Indicators:**
```
Status: [OK]  or  Status: [ERR]
```
**Replaced with:**
```tsx
<StatusBadge status="success" icon={checkIcon} />
<StatusBadge status="error" icon={errorIcon} />
```

### Design Considerations for BMS Migration

When adding assets for screens that replace BMS maps:

1. **Maintain functional equivalence:**
   - Every BMS field indicator has a corresponding icon
   - Status messages have appropriate visual indicators
   - Field attributes (protected, numeric, required) are visually distinct

2. **Preserve information density:**
   - BMS screens were very information-dense (80×24 characters)
   - Use icons and compact layouts to maintain similar density
   - Avoid overly spacious layouts that hide information

3. **Visual hierarchy:**
   - BMS used BRIGHT/DIM attributes and color
   - Use size, weight, color, and icons to establish hierarchy
   - Maintain consistent visual language across all screens

4. **Accessibility:**
   - BMS was keyboard-driven and screen-reader friendly (text-based)
   - Ensure all icons have proper alt text
   - Maintain keyboard navigation support
   - Provide sufficient color contrast

## Performance Considerations

### Asset Loading Performance

1. **Lazy Loading:**
   ```tsx
   // Lazy load images below the fold
   <img src={image} loading="lazy" alt="..." />
   ```

2. **Preloading Critical Assets:**
   ```tsx
   // In index.html for critical assets
   <link rel="preload" as="image" href="/logo-main.svg" />
   ```

3. **Responsive Images:**
   ```tsx
   // Use srcset for different viewport sizes
   <img
     src={imageMobile}
     srcSet={`${imageMobile} 480w, ${imageTablet} 768w, ${imageDesktop} 1200w`}
     sizes="(max-width: 480px) 480px, (max-width: 768px) 768px, 1200px"
     alt="..."
   />
   ```

### Bundle Size Impact

- **Small assets (< 4KB)**: Automatically inlined by Vite, added to JS bundle
- **Large assets (> 4KB)**: Loaded as separate files with hashed URLs
- **Monitor bundle size**: Use `vite build --mode analyze` to visualize asset impact

### Caching Strategy

- **Content hashing**: Assets are automatically versioned (e.g., `logo.a3f2b8c9.svg`)
- **Long cache headers**: Production builds use long-lived cache headers
- **Cache invalidation**: Changing asset content changes hash, invalidating cache

## Troubleshooting

### Common Issues

**Issue: Asset not found**
```
Error: Module not found: Can't resolve './assets/logo.png'
```
**Solution:** 
- Verify file path is correct (relative to component)
- Check filename spelling and case (Linux filesystems are case-sensitive)
- Ensure file extension is correct

**Issue: TypeScript error on asset import**
```
Cannot find module './logo.png' or its corresponding type declarations
```
**Solution:**
- Ensure `types.d.ts` includes module declaration for the file extension
- Restart TypeScript server in IDE

**Issue: Asset not optimized in production**
```
Large asset loaded without optimization
```
**Solution:**
- Verify asset is imported (not referenced as string path)
- Check asset is under `src/assets/` (not `public/`)
- Review Vite configuration for asset handling rules

**Issue: Asset loading slowly**
```
Large asset blocking page render
```
**Solution:**
- Compress/optimize the asset file
- Use lazy loading for below-the-fold images
- Consider using WebP format with JPG fallback
- Provide multiple resolution variants

## Additional Resources

- **Vite Asset Handling**: https://vitejs.dev/guide/assets.html
- **Image Optimization Tools**:
  - [Squoosh](https://squoosh.app/) - Online image compression
  - [SVGOMG](https://jakearchibald.github.io/svgomg/) - SVG optimizer
  - [TinyPNG](https://tinypng.com/) - PNG/JPG compression
- **Icon Libraries**:
  - [Material Icons](https://mui.com/material-ui/material-icons/) - Used in Material-UI components
  - [Heroicons](https://heroicons.com/) - SVG icons
  - [Feather Icons](https://feathericons.com/) - Simple, consistent icons
- **Accessibility Guidelines**:
  - [WebAIM Image Guidelines](https://webaim.org/techniques/images/)
  - [W3C WCAG Images Tutorial](https://www.w3.org/WAI/tutorials/images/)

## Version Control Best Practices

### What to Commit

✅ **DO commit:**
- Optimized production assets
- Icon files (SVG, optimized PNG)
- Logo files and brand assets
- Placeholder images
- Documentation and configuration files

❌ **DO NOT commit:**
- Unoptimized source files (large PSDs, AI files)
- Temporary or test assets
- Assets over 5MB (consider Git LFS or external hosting)
- Auto-generated assets (should be in build process)

### Git LFS for Large Assets

For assets over 5MB, consider using Git Large File Storage:

```bash
# Install Git LFS
git lfs install

# Track large images
git lfs track "*.psd"
git lfs track "*.ai"

# Commit .gitattributes
git add .gitattributes
git commit -m "Configure Git LFS"
```

## Contact and Support

For questions about asset management in the CardDemo application:

- **Technical Issues**: Open an issue in the project repository
- **Design Assets**: Contact the design team for source files
- **Asset Optimization**: Review Vite documentation or consult frontend team

---

**Last Updated**: December 2024  
**Maintained By**: CardDemo Frontend Team  
**Related Documentation**: See `/frontend/README.md` for overall frontend architecture
