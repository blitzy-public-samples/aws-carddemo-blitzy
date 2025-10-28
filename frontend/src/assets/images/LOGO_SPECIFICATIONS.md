# CardDemo Logo Specifications

## Overview

This document specifies the requirements for creating the CardDemo application logos to replace the ASCII art dollar bill with owl from the original COBOL BMS map (COSGN00.bms lines 104-144).

## Source Context

The original mainframe application displayed this ASCII art on the login screen:

```
+========================================+
|%%%%%%%  NATIONAL RESERVE NOTE  %%%%%%%%|
|%(1)  THE UNITED STATES OF KICSLAND (1)%|
|%$$              ___       ********  $$%|
|%$    {x}       (o o)                 $%|
|%$     ******  (  V  )      O N E     $%|
|%(1)          ---m-m---             (1)%|
|%%~~~~~~~~~~~ ONE DOLLAR ~~~~~~~~~~~~~%%|
+========================================+
```

This ASCII art represents a whimsical dollar bill with an owl, used as branding for the credit card demo application.

## Required Logo Files

### 1. logo-dark.png (THIS FILE)

**Purpose**: Dark theme variant for use on light backgrounds

**Specifications**:
- **Dimensions**: 200x60px (1x standard resolution)
- **Format**: PNG with transparency (PNG-24 recommended)
- **Color Palette**: Dark colors for visibility on light backgrounds
  - Primary: Navy (#1e3a8a) or Dark Blue (#1e40af)
  - Secondary: Dark Gray (#374151) or Charcoal (#1f2937)
  - Accent: Professional gold or teal for highlights
- **Compression**: Web-optimized, target file size < 20KB
- **Alpha Channel**: Use transparency for background

**Design Requirements**:
- Professional financial services branding
- Modern, clean design suitable for credit card management application
- Incorporate "CardDemo" or "National Reserve" text
- Optional: Subtle reference to original owl mascot (modernized)
- Horizontal layout optimized for navigation bars
- Readable at small sizes (down to 100x30px)

**Usage Context**:
- React Header component on light backgrounds
- Navigation bar in light theme
- Login page header when light theme is active
- Email templates with light backgrounds

### 2. logo.png (Standard/Light Theme)

**Purpose**: Standard logo variant for use on dark backgrounds

**Specifications**:
- **Dimensions**: 200x60px (1x standard resolution)
- **Format**: PNG with transparency (PNG-24 recommended)
- **Color Palette**: Light colors for visibility on dark backgrounds
  - Primary: White (#ffffff) or Light Gray (#f3f4f6)
  - Secondary: Light Blue (#60a5fa) or Cyan (#22d3ee)
  - Accent: Professional gold or bright teal for highlights
- **Compression**: Web-optimized, target file size < 20KB
- **Alpha Channel**: Use transparency for background

**Design Requirements**:
- Match design style of logo-dark.png but with inverted colors
- Same dimensions and layout as dark variant
- Maintain brand consistency across themes

**Usage Context**:
- React Header component on dark backgrounds
- Navigation bar in dark theme
- Login page header when dark theme is active
- Dashboard headers

## High-DPI Display Support

For both logo variants, create additional resolutions:

### Standard Resolution (1x)
- Dimensions: 200x60px
- Filename: `logo.png` or `logo-dark.png`
- Target: Standard displays (96 DPI)

### Retina Resolution (2x)
- Dimensions: 400x120px
- Filename: `logo@2x.png` or `logo-dark@2x.png`
- Target: Retina displays (192 DPI)

### High-Res Resolution (3x)
- Dimensions: 600x180px
- Filename: `logo@3x.png` or `logo-dark@3x.png`
- Target: High-DPI mobile displays (288+ DPI)

## Brand Guidelines

### Typography
- **Primary Font**: Modern sans-serif (e.g., Inter, Roboto, Open Sans)
- **Weight**: Semi-bold (600) to Bold (700) for "CardDemo"
- **Style**: Clean, professional, financial services appropriate
- **Letter Spacing**: Slightly increased for readability

### Iconography (Optional Owl Element)
If including a modernized owl reference:
- Simplified, geometric owl silhouette or icon
- Positioned to left of text or integrated into letterform
- Subtle, not dominant - professional over playful
- Monochromatic or limited color palette

### Modernization from ASCII Art
The new logo should:
- Transform the whimsical ASCII dollar bill into professional branding
- Maintain "National Reserve" or use "CardDemo" branding
- Replace ASCII characters with clean vector graphics
- Evolve from mainframe terminal aesthetic to modern web design

## Technical Requirements

### File Format Details
- **Color Mode**: RGB (not CMYK)
- **Bit Depth**: 24-bit color + 8-bit alpha (32-bit total)
- **Compression**: PNG-24 with optimized compression
- **Metadata**: Include creation date and version in PNG metadata
- **No Background**: Use full alpha transparency

### Optimization
- Run through image optimization tools (e.g., TinyPNG, ImageOptim)
- Target file sizes:
  - 1x: < 20KB
  - 2x: < 40KB
  - 3x: < 60KB
- Maintain sharp edges and text readability

### Accessibility
- Ensure sufficient contrast ratios:
  - Dark logo on light background: min 4.5:1 contrast
  - Light logo on dark background: min 4.5:1 contrast
- Text must be readable when displayed at minimum size (100x30px)

## Implementation Notes

### CSS Usage Example
```css
.logo {
  width: 200px;
  height: 60px;
  background-image: url('/assets/images/logo.png');
  background-size: contain;
  background-repeat: no-repeat;
}

@media (min-resolution: 192dpi) {
  .logo {
    background-image: url('/assets/images/logo@2x.png');
  }
}

@media (min-resolution: 288dpi) {
  .logo {
    background-image: url('/assets/images/logo@3x.png');
  }
}
```

### React Component Usage Example
```tsx
import logoDark from '@/assets/images/logo-dark.png';
import logoDark2x from '@/assets/images/logo-dark@2x.png';
import logoDark3x from '@/assets/images/logo-dark@3x.png';

<img 
  src={logoDark}
  srcSet={`${logoDark2x} 2x, ${logoDark3x} 3x`}
  alt="CardDemo - Credit Card Management System"
  width="200"
  height="60"
/>
```

## Deliverables Checklist

- [ ] logo.png (200x60px, standard/light theme)
- [ ] logo@2x.png (400x120px, standard/light theme)
- [ ] logo@3x.png (600x180px, standard/light theme)
- [ ] logo-dark.png (200x60px, dark theme) **← THIS FILE**
- [ ] logo-dark@2x.png (400x120px, dark theme)
- [ ] logo-dark@3x.png (600x180px, dark theme)
- [ ] Source files (AI, SVG, or design tool format for future edits)

## Design Approval Process

1. Create initial concept mockups
2. Review against brand guidelines and technical specs
3. Test at various sizes (200x60, 150x45, 100x30)
4. Test on both light and dark backgrounds
5. Validate web optimization and file sizes
6. Obtain stakeholder approval
7. Generate all required resolutions
8. Deliver final assets

## References

- Original ASCII art source: `app/bms/COSGN00.bms` lines 104-144
- Application name: "CardDemo" or "National Reserve"
- Industry: Credit Card Management / Financial Services
- Target audience: Financial professionals, system administrators
- Migration context: Mainframe COBOL to modern Java/React web application

## Contact

For questions about these specifications or design approval, contact the CardDemo development team.

---

**Version**: 1.0  
**Date**: Migration from COBOL BMS Maps  
**Status**: Awaiting designer creation of PNG assets
