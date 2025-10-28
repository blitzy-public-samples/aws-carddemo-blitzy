# CardDemo Image Assets

This directory contains image assets for the modernized CardDemo application frontend.

## Files

### hero-background.jpg
**Purpose**: Hero section background image for landing pages and main screens

**Specifications**:
- Format: JPEG
- Dimensions: 2560x1440 pixels (Retina/HiDPI optimized)
- File size: ~100KB (optimized for web performance)
- Compression: 85% quality with optimization
- Color scheme: Navy to light blue gradient (inspired by BMS COLOR=BLUE)

**Design Features**:
- Professional diagonal gradient from deep navy (#1e3a5f) to lighter blue (#4a7ba7)
- Subtle radial overlay for depth and focus
- Light geometric pattern suggesting financial/data networks
- Optimized for text overlay (white and dark text)
- WCAG AA compliant for contrast ratios

**Usage**:
```tsx
// In React components
import heroBackground from '@/assets/images/hero-background.jpg';

// As CSS background
background-image: url('/src/assets/images/hero-background.jpg');
background-size: cover;
background-position: center center;
```

**Used In**:
- `SignonPage.tsx` - Full-screen background for login form
- `MainMenuPage.tsx` - Header section background

**Migration Context**:
Replaces solid colored backgrounds from IBM 3270 BMS maps:
- `COSGN00.bms` - Original signon screen with BLUE color scheme
- `COMEN01.bms` - Original main menu with similar color palette

### Supporting Files

#### generate-hero-background.py
Python script used to generate the hero-background.jpg image programmatically.
Can be used to regenerate the image with different parameters if needed.

**Requirements**: `pip install Pillow`

**Usage**: `python3 generate-hero-background.py`

#### hero-background.jpg.SPEC.md
Comprehensive specification document detailing all requirements and design decisions
for the hero background image. Useful reference for future design updates.

## Adding New Images

When adding new images to this directory:

1. **Optimize for web**: Target file sizes under 200KB for backgrounds, under 50KB for icons
2. **Use appropriate formats**:
   - JPEG for photographs and complex gradients
   - PNG for images requiring transparency
   - SVG for icons and simple graphics
3. **Provide multiple resolutions** for responsive design when appropriate
4. **Document** in this README with specifications and usage examples
5. **Follow naming conventions**: kebab-case, descriptive names

## Image Optimization Tools

Recommended tools for optimizing images:
- **ImageOptim** (Mac): https://imageoptim.com/
- **TinyPNG**: https://tinypng.com/
- **Squoosh**: https://squoosh.app/
- **Sharp** (Node.js): `npm install sharp`

## Design Guidelines

All images should:
- Support the CardDemo brand identity (professional, financial, trustworthy)
- Follow the blue-based color palette from original BMS maps
- Be optimized for web performance
- Support accessibility requirements (WCAG 2.1 Level AA)
- Work across responsive breakpoints (mobile, tablet, desktop)

---

**Note**: This directory is part of the CardDemo mainframe-to-cloud migration project, 
modernizing visual assets from IBM 3270 BMS maps to contemporary web design standards.
