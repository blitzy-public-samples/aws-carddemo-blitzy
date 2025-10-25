# CardDemo Assets - Images Directory

## Purpose

This directory contains image assets for the CardDemo React application, replacing ASCII art and text-based graphics from the original IBM 3270 BMS maps.

## Required Files

### Logo Files (Binary Assets - Require Designer)

The following logo files need to be created by a graphic designer:

#### Standard Theme (Light logos on dark backgrounds)
- `logo.png` - 200x60px, standard resolution
- `logo@2x.png` - 400x120px, Retina display
- `logo@3x.png` - 600x180px, high-DPI mobile

#### Dark Theme (Dark logos on light backgrounds)
- `logo-dark.png` - 200x60px, standard resolution
- `logo-dark@2x.png` - 400x120px, Retina display
- `logo-dark@3x.png` - 600x180px, high-DPI mobile

**📋 See `LOGO_SPECIFICATIONS.md` for complete design requirements**

### Icon Files (Planned)

Additional icon assets for the application:

- `favicon.ico` - Browser favicon (16x16, 32x32, 48x48 multi-size)
- `favicon-16x16.png` - Small favicon
- `favicon-32x32.png` - Medium favicon
- `icon-192x192.png` - Android icon
- `icon-512x512.png` - Large icon for PWA
- `apple-touch-icon.png` - iOS home screen icon (180x180px)

### Placeholder Files (Development)

During development, placeholder images can be used:

- Simple text-based logos using online logo generators
- Solid color rectangles with text overlays
- SVG placeholders for testing layouts

## File Format Guidelines

### For Logos
- **Format**: PNG-24 with alpha transparency
- **Color Mode**: RGB
- **Compression**: Web-optimized
- **Max File Size**: 
  - 1x: < 20KB
  - 2x: < 40KB
  - 3x: < 60KB

### For Icons
- **Format**: PNG or ICO (favicon)
- **Color Mode**: RGB
- **Transparency**: Use alpha channel where appropriate

### General Guidelines
- All images must be web-optimized
- Use appropriate resolution for target display
- Include alt text support in component usage
- Test on various devices and browsers

## Source Context

These images replace ASCII art from COBOL BMS maps:

- **COSGN00.bms** (lines 104-144): ASCII dollar bill with owl → Modern logo
- **COMEN01.bms**: Text-based headers → Professional header images
- **Various maps**: 3270 character graphics → Web-optimized imagery

## Usage in React Components

### Logo Component Example

```tsx
import React from 'react';
import logoDark from '@/assets/images/logo-dark.png';
import logoDark2x from '@/assets/images/logo-dark@2x.png';
import logoDark3x from '@/assets/images/logo-dark@3x.png';
import logo from '@/assets/images/logo.png';
import logo2x from '@/assets/images/logo@2x.png';
import logo3x from '@/assets/images/logo@3x.png';

interface LogoProps {
  variant?: 'light' | 'dark';
  width?: number;
  height?: number;
}

export const Logo: React.FC<LogoProps> = ({ 
  variant = 'light', 
  width = 200, 
  height = 60 
}) => {
  const isDark = variant === 'dark';
  
  return (
    <img 
      src={isDark ? logoDark : logo}
      srcSet={isDark 
        ? `${logoDark2x} 2x, ${logoDark3x} 3x`
        : `${logo2x} 2x, ${logo3x} 3x`
      }
      alt="CardDemo - Credit Card Management System"
      width={width}
      height={height}
      loading="lazy"
    />
  );
};
```

### CSS Background Example

```css
.header-logo {
  width: 200px;
  height: 60px;
  background-image: url('/assets/images/logo.png');
  background-size: contain;
  background-repeat: no-repeat;
  background-position: center;
}

/* High-DPI displays */
@media (min-resolution: 192dpi),
       (-webkit-min-device-pixel-ratio: 2) {
  .header-logo {
    background-image: url('/assets/images/logo@2x.png');
  }
}

@media (min-resolution: 288dpi),
       (-webkit-min-device-pixel-ratio: 3) {
  .header-logo {
    background-image: url('/assets/images/logo@3x.png');
  }
}

/* Dark theme variant */
.header-logo.dark-theme {
  background-image: url('/assets/images/logo-dark.png');
}

@media (min-resolution: 192dpi) and (prefers-color-scheme: light) {
  .header-logo.dark-theme {
    background-image: url('/assets/images/logo-dark@2x.png');
  }
}
```

## Image Optimization

All images in this directory should be optimized before deployment:

### Optimization Tools
- **TinyPNG** (https://tinypng.com/) - PNG compression
- **ImageOptim** (https://imageoptim.com/) - Mac optimization tool
- **Squoosh** (https://squoosh.app/) - Web-based image optimizer
- **sharp** (npm package) - Node.js image processing

### Optimization Script Example

```bash
# Install sharp for image optimization
npm install --save-dev sharp

# Create optimization script (scripts/optimize-images.js)
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const imagesDir = path.join(__dirname, '../src/assets/images');

fs.readdirSync(imagesDir)
  .filter(file => /\.(png|jpg|jpeg)$/i.test(file))
  .forEach(file => {
    const inputPath = path.join(imagesDir, file);
    const outputPath = path.join(imagesDir, 'optimized', file);
    
    sharp(inputPath)
      .png({ quality: 90, compressionLevel: 9 })
      .toFile(outputPath)
      .then(info => console.log(`Optimized ${file}: ${info.size} bytes`))
      .catch(err => console.error(`Error optimizing ${file}:`, err));
  });
```

## Accessibility Considerations

When using images from this directory:

1. **Always provide alt text** describing the image content
2. **Use semantic HTML** (`<img>` for content, CSS backgrounds for decoration)
3. **Ensure contrast ratios** meet WCAG 2.1 AA standards (4.5:1)
4. **Provide text alternatives** for important graphical information
5. **Test with screen readers** to ensure proper announcement

### Alt Text Guidelines

```tsx
// Good: Descriptive alt text
<img src={logo} alt="CardDemo Credit Card Management System" />

// Bad: Generic alt text
<img src={logo} alt="Logo" />

// Acceptable: Decorative images (empty alt)
<img src={decorative} alt="" role="presentation" />
```

## Version Control

- **Include in Git**: Optimized final assets (PNG, ICO)
- **Exclude from Git**: Source files (PSD, AI, Sketch) - store separately
- **Git LFS**: Consider using Git Large File Storage for larger assets

### .gitignore Entries (Already configured)

```gitignore
# Exclude unoptimized originals
*.psd
*.ai
*.sketch

# Exclude temporary files
*-backup.*
*-original.*
*.tmp
```

## Build Process Integration

Images are processed during the Vite build:

```typescript
// vite.config.ts
import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        assetFileNames: (assetInfo) => {
          let extType = assetInfo.name.split('.').at(-1);
          if (/png|jpe?g|svg|gif|tiff|bmp|ico/i.test(extType)) {
            return `assets/images/[name]-[hash][extname]`;
          }
          return `assets/[name]-[hash][extname]`;
        }
      }
    }
  }
});
```

## Migration Notes

This directory structure replaces:

- **BMS Map ASCII Art**: Text-based graphics from 3270 terminals
- **Character-Based Logos**: Terminal font-based branding
- **Limited Color Palette**: 8-color 3270 display → Full RGB web colors
- **Fixed Layout**: 80x24 character grid → Responsive web images

## File Naming Conventions

- Use lowercase with hyphens: `logo-dark.png` ✓
- Avoid underscores or spaces: `Logo_Dark.png` ✗
- Include resolution suffixes: `@2x`, `@3x`
- Use descriptive names: `icon-success.png` ✓ (not `icon-1.png` ✗)

## Testing Checklist

Before deploying images:

- [ ] All required logo variants created (light, dark, 1x, 2x, 3x)
- [ ] Images optimized for web (compressed, appropriate format)
- [ ] File sizes within guidelines (< 20KB for 1x logos)
- [ ] Images tested on various screen sizes
- [ ] Images tested on high-DPI displays (Retina, etc.)
- [ ] Images tested in both light and dark themes
- [ ] Alt text provided for all images
- [ ] Contrast ratios verified for accessibility
- [ ] Images render correctly in all target browsers
- [ ] Loading performance acceptable (lazy loading implemented)

## Support and Resources

- **Logo Design Specifications**: See `LOGO_SPECIFICATIONS.md`
- **Design System**: Follow Material-UI (MUI) design guidelines
- **Brand Guidelines**: Maintain consistency with CardDemo branding
- **Technical Support**: Contact frontend development team

## Status

**Current Status**: 🚧 **Logo assets pending creation by designer**

The logo specification document has been created, but the actual PNG files require graphic design software and cannot be generated by automated tools.

**Next Steps**:
1. Assign logo design task to graphic designer
2. Designer creates assets per `LOGO_SPECIFICATIONS.md`
3. Review and approve designs
4. Optimize images for web deployment
5. Integrate into React components
6. Test across devices and themes

---

**Last Updated**: CardDemo Modernization Project  
**Maintainer**: Frontend Development Team  
**Version**: 1.0
