# Custom Web Fonts Directory

## Purpose

This directory serves as a capability folder for self-hosted typography files in the OCR Processing Application. It is designed to hold custom web fonts in modern formats (WOFF2, WOFF, TTF) when brand-specific typography requirements necessitate them.

**Important**: By default, this application uses system fonts for optimal performance. Only add custom fonts here when explicitly required by brand guidelines or design specifications.

## Performance Considerations

### System Fonts Are Preferred

Per Section 0.7.9 cost optimization guidelines, **system fonts are strongly preferred** for the following reasons:

- **Zero Network Cost**: No font file downloads required
- **Instant Rendering**: No FOUT (Flash of Unstyled Text) or FOIT (Flash of Invisible Text)
- **Optimal Performance**: System fonts are already optimized for the user's device
- **Better Core Web Vitals**: No impact on LCP (Largest Contentful Paint)
- **Reduced Bandwidth**: Smaller page size and faster initial load

The application's default font stack uses system fonts:

```css
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu',
  'Cantarell', 'Fira Sans', 'Droid Sans', 'Helvetica Neue', sans-serif;
```

### When to Use Custom Fonts

Only add custom fonts to this directory when:

- Brand guidelines mandate a specific typeface
- Legal or contractual requirements specify typography
- Specialized readability requirements (e.g., accessibility fonts)
- Document output requires specific font matching

## How Fonts Are Loaded

Custom fonts placed in this directory are:

1. **Served by Next.js**: Files in `/public/fonts/` are accessible at the root `/fonts/` URL path
2. **Loaded via CSS**: Use `@font-face` declarations in your CSS files
3. **Applied via Classes**: Reference fonts in TailwindCSS configuration or CSS

Next.js automatically serves all files in the `public` directory from the root path:

- File path: `frontend/public/fonts/CustomFont.woff2`
- Browser URL: `https://yourdomain.com/fonts/CustomFont.woff2`

## File Format Recommendations

Use modern font formats for optimal performance:

### Recommended Format Priority

1. **WOFF2** (Web Open Font Format 2) - **Preferred**

   - Best compression (~30% smaller than WOFF)
   - Supported by all modern browsers (95%+ coverage)
   - Use this as your primary format

2. **WOFF** (Web Open Font Format) - **Fallback**

   - Good compression
   - Broad browser support (older browsers)
   - Include for browsers that don't support WOFF2

3. **TTF** (TrueType Font) - **Legacy Only**
   - Minimal compression
   - Only include if you need to support very old browsers
   - Generally not recommended for web use

### Do NOT Include

- ❌ EOT (Embedded OpenType) - IE-only, obsolete
- ❌ SVG fonts - Deprecated and removed from modern browsers
- ❌ Uncompressed font files - Wastes bandwidth

## Adding Custom Fonts: Step-by-Step Guide

### Step 1: Obtain Font Files

Ensure you have the legal right to use and self-host the font. Check the font license:

- ✅ Open source fonts (Google Fonts, Adobe Fonts Open Source)
- ✅ Purchased commercial licenses with self-hosting rights
- ✅ Custom fonts from your organization
- ❌ Free fonts without web embedding rights
- ❌ Fonts from subscription services without self-hosting permission

### Step 2: Optimize Font Files

Before adding fonts, optimize them:

1. **Subset the font** to include only necessary characters:

   ```bash
   # Example using pyftsubset (from fonttools)
   pyftsubset CustomFont.ttf \
     --output-file=CustomFont-subset.woff2 \
     --flavor=woff2 \
     --layout-features=* \
     --unicodes=U+0020-007F,U+00A0-00FF
   ```

2. **Convert to WOFF2** format if not already available:

   ```bash
   # Example using fonttools
   fonttools ttLib.woff2 compress CustomFont.ttf
   ```

3. **Limit weights and styles** to only what you need:
   - Regular (400) and Bold (700) are usually sufficient
   - Only include italic variants if used in the design

### Step 3: Place Files in This Directory

Copy optimized font files to `frontend/public/fonts/`:

```
frontend/public/fonts/
├── README.md (this file)
├── CustomFont-Regular.woff2
├── CustomFont-Regular.woff
├── CustomFont-Bold.woff2
└── CustomFont-Bold.woff
```

**Naming Convention**: Use descriptive names that include weight and style:

- `FontName-Regular.woff2`
- `FontName-Bold.woff2`
- `FontName-Italic.woff2`
- `FontName-BoldItalic.woff2`

### Step 4: Declare @font-face Rules

Add `@font-face` declarations in your CSS file (e.g., `frontend/styles/globals.css`):

```css
/* Custom Font - Regular */
@font-face {
  font-family: 'Custom Font';
  font-style: normal;
  font-weight: 400;
  font-display: swap; /* Critical for performance */
  src:
    url('/fonts/CustomFont-Regular.woff2') format('woff2'),
    url('/fonts/CustomFont-Regular.woff') format('woff');
  unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+2000-206F,
    U+2074, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
}

/* Custom Font - Bold */
@font-face {
  font-family: 'Custom Font';
  font-style: normal;
  font-weight: 700;
  font-display: swap;
  src:
    url('/fonts/CustomFont-Bold.woff2') format('woff2'),
    url('/fonts/CustomFont-Bold.woff') format('woff');
  unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+2000-206F,
    U+2074, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
}
```

### Step 5: Configure TailwindCSS

Update `frontend/tailwind.config.js` to use your custom font:

```javascript
module.exports = {
  theme: {
    extend: {
      fontFamily: {
        // Add custom font with system font fallbacks
        sans: [
          'Custom Font',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'Roboto',
          'sans-serif',
        ],
        // Or create a separate utility class
        brand: ['Custom Font', 'sans-serif'],
      },
    },
  },
};
```

### Step 6: Use in Components

Apply the font using TailwindCSS utility classes:

```tsx
// Use as primary font (if configured as 'sans')
<div className="font-sans">This uses the custom font</div>

// Or use specific font family
<div className="font-brand">This uses the brand font</div>
```

Or use in custom CSS:

```css
.custom-heading {
  font-family: 'Custom Font', sans-serif;
}
```

## @font-face Properties Explained

### font-display: swap (Critical for Performance)

**Always use `font-display: swap`** for optimal performance:

```css
@font-face {
  font-family: 'Custom Font';
  font-display: swap; /* This is critical */
  /* ... other properties */
}
```

**What it does**:

- Shows text immediately in fallback font
- Swaps to custom font when loaded
- Prevents invisible text (FOIT)
- Improves Core Web Vitals scores

**Other font-display values** (generally not recommended):

- `block` - Hides text while font loads (bad UX)
- `fallback` - Very short block period (limited browser support)
- `optional` - Makes font fully optional (unpredictable)
- `auto` - Browser decides (inconsistent behavior)

### unicode-range (Optional but Recommended)

Limits which characters use the custom font:

```css
@font-face {
  font-family: 'Custom Font';
  unicode-range: U+0000-00FF; /* Latin characters only */
  /* ... */
}
```

**Benefits**:

- Browser only downloads font if needed characters are present
- Reduces unnecessary font downloads
- Improves performance for multilingual sites

### src Property Format

Always list formats from most modern to least modern:

```css
@font-face {
  src:
    url('/fonts/Font.woff2') format('woff2'),
    /* Modern browsers */ url('/fonts/Font.woff') format('woff'); /* Older browsers */
}
```

Browser will use the first format it supports.

## Best Practices for Font Performance

### 1. Font Subsetting

Remove unused characters to reduce file size:

**Example**: Latin-only subset

```css
unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+2000-206F,
  U+2074, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
```

**Tools for subsetting**:

- [pyftsubset](https://github.com/fonttools/fonttools) (Python)
- [glyphhanger](https://github.com/zachleat/glyphhanger) (Node.js)
- [Font Squirrel Webfont Generator](https://www.fontsquirrel.com/tools/webfont-generator)

### 2. Limit Font Weights and Styles

Only include what you actually use:

- ✅ Regular (400) + Bold (700) = Most common
- ✅ Add Medium (500) or Semibold (600) only if design requires
- ❌ Avoid loading 5+ weights (wasteful)
- ❌ Don't include italic unless used

**File size impact**:

- Each weight/style = ~20-50KB (WOFF2)
- 5 weights × 2 styles = 200-500KB just for fonts

### 3. Preload Critical Fonts (Optional)

For fonts used above-the-fold, consider preloading:

```html
<!-- Add to pages/_document.tsx <Head> section -->
<link
  rel="preload"
  href="/fonts/CustomFont-Regular.woff2"
  as="font"
  type="font/woff2"
  crossorigin="anonymous"
/>
```

**Warning**: Only preload 1-2 most critical font files. Overuse hurts performance.

### 4. Monitor Font Loading Performance

Use browser DevTools to check:

- Font file sizes (<50KB per file is good)
- Load times (<200ms is excellent)
- Total font weight (<150KB for all fonts is good)

### 5. Consider Variable Fonts

For multiple weights, variable fonts can be more efficient:

```css
@font-face {
  font-family: 'Variable Font';
  font-weight: 100 900; /* Supports all weights */
  src: url('/fonts/VariableFont.woff2') format('woff2-variations');
}
```

**Benefits**:

- One file for all weights
- Smooth font-weight transitions
- Smaller total file size than multiple files

**Browser support**: 95%+ (all modern browsers)

## Font Licensing Reminder

Before adding any font to this directory:

1. ✅ Verify you have legal rights to self-host the font
2. ✅ Check license terms for web embedding
3. ✅ Include license file if required (e.g., `OFL.txt` for open fonts)
4. ✅ Document font source and license in this README

**Common license types**:

- **OFL (Open Font License)**: Free for commercial use, can embed
- **Apache/MIT**: Free for commercial use, can embed
- **Commercial licenses**: Check specific terms for self-hosting rights
- **Google Fonts**: Free to download and self-host

## Troubleshooting

### Font not loading?

1. **Check file path**: Ensure font file is in `public/fonts/` directory
2. **Check URL**: Fonts must be accessed as `/fonts/filename.woff2` (not `/public/fonts/`)
3. **Check console**: Look for 404 errors or CORS issues in browser DevTools
4. **Check format**: Ensure browser supports the format (use WOFF2 + WOFF)
5. **Check font-family name**: CSS name must match @font-face declaration exactly

### Font causing performance issues?

1. **Check file size**: Keep each font file <50KB (subset if larger)
2. **Limit weights**: Only include weights/styles you actually use
3. **Use font-display: swap**: Prevents invisible text
4. **Remove unused fonts**: Delete font files that aren't being used
5. **Consider system fonts**: Evaluate if custom font is really necessary

### Font rendering poorly?

1. **Check font-weight values**: Ensure CSS weight matches available font weights
2. **Check font-smoothing**: May need `-webkit-font-smoothing: antialiased`
3. **Check font hinting**: Ensure fonts have good hinting for screen rendering

## Current Fonts in This Directory

<!-- Update this section when adding fonts to the directory -->

**Status**: No custom fonts currently installed.

This directory is empty by default to optimize performance. Add fonts only when required by brand guidelines or design specifications.

## References

- [MDN: @font-face](https://developer.mozilla.org/en-US/docs/Web/CSS/@font-face)
- [MDN: font-display](https://developer.mozilla.org/en-US/docs/Web/CSS/@font-face/font-display)
- [Web Font Optimization Guide](https://web.dev/font-best-practices/)
- [Next.js: Font Optimization](https://nextjs.org/docs/basic-features/font-optimization)
- [TailwindCSS: Font Family](https://tailwindcss.com/docs/font-family)

## Related Files

- `frontend/tailwind.config.js` - TailwindCSS font configuration
- `frontend/styles/globals.css` - Global styles and @font-face declarations
- `frontend/pages/_document.tsx` - Custom document for font preloading

---

**Last Updated**: November 2025  
**Maintained By**: OCR Processing Application Development Team
