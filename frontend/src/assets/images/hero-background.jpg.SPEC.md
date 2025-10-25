# Hero Background Image Specification

## File Information
- **Target File**: `frontend/src/assets/images/hero-background.jpg`
- **Purpose**: Hero section background image for CardDemo application landing pages and main screens
- **Migration Context**: Replaces IBM 3270 BMS map solid colored backgrounds with modern professional imagery

## Technical Requirements

### Dimensions and Format
- **Format**: JPEG
- **Minimum Dimensions**: 1920x1080 pixels (Full HD)
- **Recommended Dimensions**: 2560x1440 pixels (for Retina/HiDPI displays)
- **Aspect Ratio**: 16:9
- **Compression Quality**: 80-90% (balance between quality and file size)
- **Target File Size**: Under 200KB (optimized for web performance)
- **Color Space**: sRGB

### Design Requirements

#### Visual Style
Based on analysis of source BMS maps (COSGN00.bms and COMEN01.bms), the image should reflect:

1. **Financial/Banking Context**
   - Professional and trustworthy appearance
   - Suitable for credit card management application
   - Corporate/enterprise aesthetic
   - Modern and clean design

2. **Color Scheme**
   The BMS maps use the following colors which should inform the background palette:
   - **Primary**: Blue tones (BMS COLOR=BLUE) - dominant color in original screens
   - **Accent**: Yellow/Gold tones (BMS COLOR=YELLOW) - used for titles
   - **Neutral**: Gray/neutral tones (BMS COLOR=NEUTRAL) - for general content
   - **Supporting**: Turquoise/teal accents (BMS COLOR=TURQUOISE) - for interactive elements
   
   **Recommended Palette**:
   - Deep navy blue to lighter blue gradient (#1e3a5f → #4a7ba7)
   - Subtle gold/amber highlights (#f4a100 or #d4af37) - optional accent
   - Neutral grays for depth (#e8e8e8, #f5f5f5)

3. **Pattern/Texture Options** (choose ONE):
   - **Option A**: Subtle gradient (recommended)
     - Diagonal or radial gradient from deep blue to lighter blue
     - Very subtle, not overpowering
     - Should not interfere with text overlay
   
   - **Option B**: Abstract geometric pattern
     - Subtle geometric shapes suggesting financial data/networks
     - Low opacity (10-20%) to avoid visual clutter
     - Professional and minimal
   
   - **Option C**: Bokeh/blur effect
     - Soft, out-of-focus light patterns
     - Blue and gold color scheme
     - Creates depth and sophistication

#### Text Overlay Compatibility
The image MUST support text overlay with the following characteristics:
- **White text readability**: Ensure white text is clearly readable (WCAG AA compliant)
- **Dark text readability**: Optionally support dark text in lighter areas
- **Contrast ratio**: Minimum 4.5:1 for normal text, 3:1 for large text
- **Safe zones**: Central 60% of image should have consistent contrast for content placement

### Implementation Notes

#### Responsive Behavior
The image will be used in responsive web layouts:
- Desktop (1920x1080+): Full image visible
- Tablet (768x1024): Center-focused crop
- Mobile (375x667): Center-focused crop with key gradient visible

#### CSS Background Properties
The image will typically be used with:
```css
background-image: url('/src/assets/images/hero-background.jpg');
background-size: cover;
background-position: center center;
background-repeat: no-repeat;
```

#### Usage Contexts
This image will be used in:
1. **SignonPage** (replaces COSGN00.bms background)
   - Full-screen background behind login form
   - Must not distract from login fields
   - Should convey security and professionalism

2. **MainMenuPage header** (replaces COMEN01.bms background)
   - Header section background
   - Must support menu option overlays
   - Should provide visual hierarchy

## Original BMS Context

### COSGN00.bms (Signon Screen)
- Original used solid BLUE background for system labels
- YELLOW for title text
- NEUTRAL for descriptive text
- Featured ASCII art of a dollar bill (indicating financial theme)
- Professional, corporate aesthetic

### COMEN01.bms (Main Menu)
- Similar color scheme: BLUE, YELLOW, NEUTRAL, TURQUOISE
- Simple, functional layout
- Focus on clarity and usability

## Design Guidelines

### DO:
✓ Use professional, corporate-friendly design
✓ Ensure excellent text contrast for overlays
✓ Optimize file size for web performance
✓ Use blue-based color palette reflecting original BMS colors
✓ Create subtle, non-distracting background
✓ Test readability with white and dark text overlays
✓ Ensure accessibility (WCAG AA compliance)

### DON'T:
✗ Use busy patterns that distract from content
✗ Include text or specific imagery in the background
✗ Use bright, saturated colors that reduce text readability
✗ Create file sizes over 200KB
✗ Use portrait orientation
✗ Include copyrighted imagery

## Creation Methods

### Option 1: Professional Design Tool
Use Adobe Photoshop, Figma, or similar:
1. Create 2560x1440 canvas
2. Apply gradient or subtle pattern matching specifications
3. Test with sample white/dark text overlays
4. Export as JPEG with 85% quality
5. Optimize with ImageOptim or similar tool

### Option 2: Programmatic Generation
Use image libraries (Python PIL, Node.js Sharp, etc.):
```python
# Example using Python PIL
from PIL import Image, ImageDraw

width, height = 2560, 1440
image = Image.new('RGB', (width, height))
draw = ImageDraw.Draw(image)

# Create gradient from navy to lighter blue
for y in range(height):
    r = int(30 + (74 - 30) * (y / height))
    g = int(58 + (123 - 58) * (y / height))
    b = int(95 + (167 - 95) * (y / height))
    draw.line([(0, y), (width, y)], fill=(r, g, b))

image.save('hero-background.jpg', 'JPEG', quality=85, optimize=True)
```

### Option 3: AI Image Generation
Use DALL-E, Midjourney, or Stable Diffusion with prompt:
```
"Professional abstract background for financial web application, 
subtle blue gradient, modern corporate design, minimal geometric 
patterns, suitable for text overlay, 16:9 aspect ratio, clean 
and trustworthy aesthetic, banking theme"
```

## Validation Checklist

Before finalizing the image, verify:
- [ ] Dimensions: Minimum 1920x1080 pixels
- [ ] Format: JPEG
- [ ] File size: Under 200KB
- [ ] Compression quality: 80-90%
- [ ] White text contrast: WCAG AA compliant (4.5:1 minimum)
- [ ] Color scheme: Blue-based palette
- [ ] Visual style: Professional and corporate
- [ ] Pattern: Subtle and non-distracting
- [ ] Responsiveness: Works at various screen sizes
- [ ] Performance: Optimized for web loading

## Alternative Temporary Solution

If immediate deployment is required before final image creation, use:
1. **Solid color background**: CSS `background: linear-gradient(135deg, #1e3a5f 0%, #4a7ba7 100%);`
2. **Simple gradient**: Can be implemented in CSS without image file
3. **Placeholder service**: Use https://placehold.co/2560x1440/1e3a5f/ffffff

## References
- Source: `app/bms/COSGN00.bms` - Original signon screen layout and colors
- Source: `app/bms/COMEN01.bms` - Original main menu layout and colors
- WCAG 2.1 Contrast Guidelines: https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html
- Web Performance Best Practices: Image optimization for sub-200KB targets

---

**Note**: This specification document should be replaced with the actual `hero-background.jpg` image file once created. The image must meet all specifications above to ensure consistency with the modernized CardDemo application design system.
