# OCR Processing Application - Image Assets

## Overview
This directory contains all static image assets for the OCR Processing Application frontend. Next.js automatically serves files from this directory at the `/images/` URL path.

## Required Image Assets

### 1. Application Logos
**Purpose**: Brand identity across different contexts (header, footer, email, print)

Required files:
- `logo.svg` - Primary logo (vector format, scalable)
- `logo-light.svg` - Logo for dark backgrounds
- `logo-dark.svg` - Logo for light backgrounds  
- `logo-192.png` - Small raster version (192x192px)
- `logo-512.png` - Large raster version (512x512px)

**Specifications**:
- SVG: Optimized, minified, viewBox defined
- PNG: Transparent background, optimized with tools like ImageOptim or TinyPNG
- Color palette should match application theme defined in tailwind.config.js

### 2. PWA Icons (CRITICAL - Referenced by manifest.json)
**Purpose**: Progressive Web App installation icons

Required files:
- `icon-192x192.png` - Standard PWA icon
- `icon-512x512.png` - High-resolution PWA icon

**Specifications**:
- PNG format with transparency
- Square dimensions (192x192, 512x512)
- Represent OCR/document processing theme
- File size: <50KB each (optimized)
- Referenced in `/public/manifest.json`

### 3. Favicon Assets
**Purpose**: Browser tab/bookmark icons

Files handled in parent directory:
- `/public/favicon.ico` - Multi-resolution ICO (16x16, 32x32)

### 4. Document Placeholder Images
**Purpose**: Visual feedback for various document states

Required files:
- `document-placeholder.svg` - Generic document icon
- `document-loading.svg` - Animated loading state
- `document-empty.svg` - Empty state illustration
- `document-error.svg` - Error state illustration

**Specifications**:
- SVG format for scalability
- Monochromatic or limited color palette
- Size: <10KB each
- Support dark mode variants if needed

### 5. Error State Illustrations
**Purpose**: User-friendly error pages per Section 0.2.2 requirements

Required files:
- `404-not-found.svg` - Page not found illustration
- `500-error.svg` - Server error illustration  
- `403-forbidden.svg` - Access denied illustration
- `error-generic.svg` - Generic error illustration

**Specifications**:
- SVG format, optimized and minified
- Friendly, professional illustration style
- Size: <25KB each
- Consistent color scheme with brand palette

### 6. Success/Confirmation Feedback
**Purpose**: Positive user feedback for completed actions

Required files:
- `success-checkmark.svg` - Success confirmation icon
- `upload-success.svg` - Document upload success
- `processing-complete.svg` - OCR processing completion
- `approval-confirmed.svg` - Document approval confirmation

**Specifications**:
- SVG format
- Green/success color from theme
- Size: <5KB each
- Animation-ready (CSS animation support)

### 7. UI Icons and Iconography
**Purpose**: Interface icons for buttons, navigation, actions

Note: Primary icon library is Lucide React (per package.json dependency). This directory is for:
- Custom icons not available in Lucide
- Brand-specific iconography
- Specialized OCR/document icons

Required files:
- `scan-icon.svg` - Document scanning icon
- `ocr-icon.svg` - OCR processing icon
- `template-icon.svg` - Template builder icon
- `batch-icon.svg` - Batch processing icon

**Specifications**:
- SVG format, 24x24px viewBox standard
- Strokewidth consistent with Lucide (2px)
- Size: <3KB each

### 8. Empty State Illustrations
**Purpose**: Engaging visuals for empty data states

Required files:
- `empty-documents.svg` - No documents uploaded yet
- `empty-templates.svg` - No templates created yet
- `empty-search.svg` - No search results found
- `empty-analytics.svg` - No analytics data available

**Specifications**:
- SVG format
- Illustrative style, professional and friendly
- Size: <30KB each
- Match application color palette

## Image Optimization Requirements

Per **Section 0.7.1 Performance Targets**: Web interface initial load MUST complete in <2 seconds

### Modern Format Support
- **Primary**: WebP format for modern browsers (better compression)
- **Fallback**: PNG for images with transparency, JPEG for photos
- Use `<picture>` element or Next.js Image component for format fallbacks

### Compression Standards
- **PNG**: Use ImageOptim, TinyPNG, or similar (aim for 70-80% size reduction)
- **JPEG**: Quality 80-85, progressive encoding
- **SVG**: Minify with SVGO, remove unnecessary metadata
- **WebP**: Quality 80-85

### Responsive Images
Per **Section 0.7.11 Mobile Responsiveness**, support breakpoints:
- Mobile: <640px
- Tablet: 640-1024px  
- Desktop: >1024px

Provide multiple resolutions using `srcset` or Next.js Image:
```jsx
<Image
  src="/images/logo.png"
  srcSet="/images/logo-192.png 192w, /images/logo-512.png 512w"
  sizes="(max-width: 640px) 192px, 512px"
/>
```

### File Size Targets
- Icons: <5KB
- Logos: <50KB
- Illustrations: <30KB
- Placeholder images: <10KB
- PWA icons: <50KB

## Accessibility Requirements

Per **Section 0.7.10 WCAG 2.1 AA Compliance**:

1. **Alt Text**: All images MUST have descriptive alt text (enforced in React components)
2. **Contrast**: Ensure 4.5:1 contrast ratio for text on images
3. **SVG Accessibility**: Include `<title>` and `<desc>` tags in SVG files
4. **Focus Indicators**: Ensure clickable images have visible focus states

Example accessible SVG:
```xml
<svg role="img" aria-labelledby="logo-title">
  <title id="logo-title">OCR Processing Application Logo</title>
  <desc>Company logo featuring a document with scanning rays</desc>
  <!-- SVG content -->
</svg>
```

## Development Workflow

### Adding New Images

1. **Design Phase**:
   - Create images in design tool (Figma, Sketch, Illustrator)
   - Follow specifications above for dimensions and format
   - Use brand colors from `tailwind.config.js`

2. **Optimization Phase**:
   - Export SVG: Minify with SVGO
   - Export PNG: Optimize with ImageOptim/TinyPNG
   - Create WebP versions: Use tools like cwebp or Squoosh
   - Verify file sizes meet targets

3. **Integration Phase**:
   - Place files in this directory (`frontend/public/images/`)
   - Reference in components using `/images/filename.ext`
   - Use Next.js `<Image>` component for automatic optimization
   - Test across devices and screen sizes

### Using Images in Components

Next.js Image component (recommended):
```jsx
import Image from 'next/image';

<Image
  src="/images/logo.svg"
  alt="OCR Processing Application"
  width={200}
  height={50}
  priority // for above-the-fold images
/>
```

Standard HTML (for SVG with inline styles):
```jsx
<img 
  src="/images/success-checkmark.svg" 
  alt="Success"
  className="w-6 h-6"
/>
```

## Performance Monitoring

Monitor image performance using:
- **Next.js Analytics**: Built-in performance metrics
- **Lighthouse**: Aim for score >90 for Performance
- **WebPageTest**: Check image compression effectiveness
- **Browser DevTools**: Network tab for image load times

Target metrics:
- Largest Contentful Paint (LCP): <2.5s
- First Contentful Paint (FCP): <1.8s  
- Total image payload: <500KB for initial page load

## Version Control

- **DO commit**: Optimized final images to git
- **DO NOT commit**: 
  - Source design files (.fig, .sketch, .ai, .psd)
  - Unoptimized exports
  - Temporary/work-in-progress images

## References

- Next.js Image Optimization: https://nextjs.org/docs/api-reference/next/image
- WebP Support: https://caniuse.com/webp
- SVGO Optimization: https://github.com/svg/svgo
- WCAG Image Guidelines: https://www.w3.org/WAI/WCAG21/Understanding/images-of-text

## Contact

For questions about image requirements or to request new assets, contact the frontend development team.
