# Icons Directory

## Overview

This directory contains the icon assets and icon components for the CardDemo React application. The icon system consists of two main categories:

1. **Custom SVG Icons**: Application-specific vector graphics stored as `.svg` files
2. **Material-UI Icon Wrappers**: Reusable React components that wrap Material-UI icons for consistent usage patterns across the application

All icons use the SVG (Scalable Vector Graphics) format to ensure scalability, performance, and crisp rendering at any resolution. This modern icon system replaces the character-based UI elements from the legacy BMS (Basic Mapping Support) 3270 terminal screens with scalable vector graphics.

## Directory Structure

```
frontend/src/assets/icons/
├── README.md                  # This file - comprehensive icon documentation
├── card-icon.svg              # Custom credit card icon
├── transaction-icon.svg       # Custom transaction/payment icon
├── account-icon.svg           # Custom account/profile icon
├── user-icon.svg              # Custom user/person icon
├── report-icon.svg            # Custom report/document icon
├── StatusIcons.tsx            # Status indicator icon components
├── MenuIcons.tsx              # Navigation menu icon components
└── CustomIcons.tsx            # Application-specific icon components
```

## Custom SVG Icons

### Available Custom Icons

The following custom SVG icons have been created specifically for the CardDemo application:

#### 1. **card-icon.svg**
- **Purpose**: Represents credit cards, card operations, and card-related functionality
- **Used in**: Card list pages, card update forms, card selection screens
- **Original BMS Context**: Replaces text-based card indicators from COCRDLI.bms (Card Listing Screen)
- **Dimensions**: 24x24px default viewBox
- **Color**: Typically rendered in primary theme color

#### 2. **transaction-icon.svg**
- **Purpose**: Represents financial transactions, payments, and transaction history
- **Used in**: Transaction list pages, transaction entry forms, billing screens
- **Original BMS Context**: Replaces character-based transaction markers from COTRN00.bms (Transaction List Screen)
- **Dimensions**: 24x24px default viewBox
- **Color**: Typically rendered in secondary theme color or green for success states

#### 3. **account-icon.svg**
- **Purpose**: Represents customer accounts and account management operations
- **Used in**: Account view pages, account update forms, account administration
- **Original BMS Context**: Replaces text labels from account screens in the BMS interface
- **Dimensions**: 24x24px default viewBox
- **Color**: Typically rendered in neutral or primary theme color

#### 4. **user-icon.svg**
- **Purpose**: Represents user profiles, authentication, and user management
- **Used in**: User administration pages, login screens, user profile sections
- **Original BMS Context**: Replaces text-based user indicators from COSGN00.bms (Login Screen) and COUSR00.bms (User List Screen)
- **Dimensions**: 24x24px default viewBox
- **Color**: Typically rendered in primary theme color

#### 5. **report-icon.svg**
- **Purpose**: Represents reports, documents, and report generation
- **Used in**: Report menu pages, report generation forms, document exports
- **Original BMS Context**: Replaces text-based report indicators from CORPT00.bms (Report Menu Screen)
- **Dimensions**: 24x24px default viewBox
- **Color**: Typically rendered in neutral or primary theme color

## Icon Component Wrappers

### StatusIcons.tsx

Contains React components that wrap Material-UI icons for displaying various status states throughout the application.

**Available Status Icons:**
- **SuccessIcon**: Green checkmark indicating successful operations
- **ErrorIcon**: Red error symbol for failed operations or validation errors
- **WarningIcon**: Yellow warning symbol for caution states
- **InfoIcon**: Blue information symbol for informational messages
- **PendingIcon**: Circular progress indicator for pending/loading states

**Example Usage:**
```typescript
import { SuccessIcon, ErrorIcon } from '@/assets/icons/StatusIcons';

function TransactionResult({ success }: { success: boolean }) {
  return (
    <div>
      {success ? (
        <SuccessIcon size="medium" />
      ) : (
        <ErrorIcon size="medium" />
      )}
    </div>
  );
}
```

### MenuIcons.tsx

Contains React components that wrap Material-UI icons for navigation menu items and application sections.

**Available Menu Icons:**
- **HomeIcon**: Home/dashboard navigation
- **AccountIcon**: Account management section
- **CardIcon**: Credit card management section
- **TransactionIcon**: Transaction history and operations
- **ReportIcon**: Reports and analytics section
- **UserIcon**: User administration and profile
- **SettingsIcon**: Application settings
- **LogoutIcon**: User logout action

**Example Usage:**
```typescript
import { CardIcon, TransactionIcon } from '@/assets/icons/MenuIcons';

function NavigationMenu() {
  return (
    <nav>
      <MenuItem icon={<CardIcon />} label="Cards" />
      <MenuItem icon={<TransactionIcon />} label="Transactions" />
    </nav>
  );
}
```

### CustomIcons.tsx

Contains application-specific icon components that combine custom SVG icons with Material-UI's SvgIcon wrapper for consistent styling and sizing.

**Available Custom Icon Components:**
- **CustomCardIcon**: Wraps card-icon.svg
- **CustomTransactionIcon**: Wraps transaction-icon.svg
- **CustomAccountIcon**: Wraps account-icon.svg
- **CustomUserIcon**: Wraps user-icon.svg
- **CustomReportIcon**: Wraps report-icon.svg

**Example Usage:**
```typescript
import { CustomCardIcon } from '@/assets/icons/CustomIcons';

function CardListHeader() {
  return (
    <header>
      <CustomCardIcon size="large" color="primary" />
      <h1>Credit Cards</h1>
    </header>
  );
}
```

## Usage Guidelines

### Importing Icons

Icons can be imported using standard ES6 import syntax:

```typescript
// Import custom SVG files directly
import cardIcon from '@/assets/icons/card-icon.svg';

// Import icon components
import { SuccessIcon, ErrorIcon } from '@/assets/icons/StatusIcons';
import { CardIcon, TransactionIcon } from '@/assets/icons/MenuIcons';
import { CustomCardIcon } from '@/assets/icons/CustomIcons';
```

### Using Custom SVG Files

Custom SVG files can be used directly in img tags or as CSS backgrounds:

```typescript
// As an img element
<img src={cardIcon} alt="Credit Card" width="24" height="24" />

// As a background in CSS-in-JS
const cardStyle = {
  backgroundImage: `url(${cardIcon})`,
  backgroundSize: 'contain',
  width: '24px',
  height: '24px',
};
```

### Using Icon Components

Icon components accept standard props for size, color, and styling:

```typescript
// Basic usage with default size (medium - 24px)
<CardIcon />

// With size prop
<CardIcon size="small" />   // 16px
<CardIcon size="medium" />  // 24px (default)
<CardIcon size="large" />   // 32px

// With color prop
<CardIcon color="primary" />
<CardIcon color="secondary" />
<CardIcon color="error" />
<CardIcon color="warning" />
<CardIcon color="info" />
<CardIcon color="success" />

// With custom styling
<CardIcon sx={{ fontSize: 40, color: '#1976d2' }} />
```

## Naming Conventions

### SVG File Naming
- Use **kebab-case** for all SVG filenames
- Format: `{function}-icon.svg`
- Examples: `card-icon.svg`, `transaction-icon.svg`, `user-icon.svg`
- Be descriptive and avoid abbreviations

### Component Naming
- Use **PascalCase** for React component names
- Format: `{Function}Icon` for wrapper components
- Format: `Custom{Function}Icon` for components wrapping custom SVGs
- Examples: `CardIcon`, `TransactionIcon`, `CustomCardIcon`, `SuccessIcon`
- Component names should clearly indicate their purpose

### File Organization
- Group related icon components in separate files:
  - `StatusIcons.tsx` - Status and feedback icons
  - `MenuIcons.tsx` - Navigation and menu icons
  - `CustomIcons.tsx` - Custom SVG wrapper components

## Icon Sizing Guidelines

The CardDemo application follows Material-UI icon sizing conventions:

| Size Name | Pixel Size | Use Case |
|-----------|------------|----------|
| **small** | 16px | Inline icons, compact UI elements, table cells |
| **medium** | 24px | Default size, buttons, form labels, list items |
| **large** | 32px | Page headers, prominent actions, empty states |
| **custom** | Variable | Specify custom pixel size using `sx` prop or `fontSize` |

**Examples:**
```typescript
// Small icon for inline text
<StatusIcon size="small" /> Transaction approved

// Medium icon for button (default)
<Button startIcon={<CardIcon />}>View Cards</Button>

// Large icon for page header
<Box display="flex" alignItems="center">
  <CustomCardIcon size="large" />
  <Typography variant="h4">Card Management</Typography>
</Box>

// Custom size
<ReportIcon sx={{ fontSize: 48 }} />
```

## Migration Notes: BMS 3270 to Modern SVG Icons

### Original BMS Limitations

The legacy CardDemo mainframe application used BMS (Basic Mapping Support) for 3270 terminal screens, which had significant visual limitations:

1. **Text-Only Interface**: All UI elements were character-based (ASCII/EBCDIC characters)
2. **Limited Visual Indicators**: Differentiation relied solely on:
   - Text color attributes (BLUE, YELLOW, RED, GREEN, TURQUOISE, NEUTRAL)
   - Text highlighting (NORM, BRT - normal/bright intensity)
   - Character sequences (e.g., "===", "***", ">>>") to simulate borders and emphasis
3. **No Graphics**: Zero capability for icons, images, or vector graphics
4. **Fixed Layout**: 80 characters wide × 24 lines tall grid with fixed positioning

### Modern SVG Icon Advantages

The React application's SVG icon system provides:

1. **Scalable Graphics**: Icons remain crisp at any size or resolution
2. **Visual Clarity**: Instantly recognizable symbols replace text labels
3. **Consistent Design**: Unified visual language across the application
4. **Accessibility**: Icons include proper aria-labels and can be read by screen readers
5. **Theming Support**: Icons automatically adapt to theme colors and dark/light modes
6. **Interactive States**: Hover effects, click feedback, and animations enhance UX
7. **Performance**: SVG icons are lightweight and render efficiently

### Mapping BMS Elements to Icons

| BMS Screen Element | Original Representation | Modern Icon Equivalent |
|-------------------|------------------------|------------------------|
| User ID field (COSGN00.bms) | Text label "User ID     :" | `<UserIcon />` with form label |
| Card listing indicator | Text "List Credit Cards" | `<CustomCardIcon />` in page header |
| Transaction markers | Character sequences ">>>" | `<TransactionIcon />` or `<StatusIcon />` |
| Error messages (RED color) | Red text with ERRMSG field | `<ErrorIcon />` with error message |
| Menu options (COMEN01.bms) | Numbered text options | `<MenuIcons />` with navigation items |
| Status indicators | Color-coded text (GREEN=active, RED=error) | `<SuccessIcon />`, `<ErrorIcon />`, etc. |

## Accessibility Best Practices

### Always Provide Accessible Labels

All icon components should include proper accessibility attributes:

```typescript
// Decorative icon (adjacent to text label)
<CardIcon aria-hidden="true" />
<span>Credit Cards</span>

// Semantic icon (conveys meaning)
<CardIcon 
  aria-label="View credit cards" 
  role="img" 
  titleAccess="View credit cards"
/>

// Icon button
<IconButton aria-label="Delete transaction">
  <DeleteIcon />
</IconButton>
```

### Color Contrast

Ensure sufficient color contrast for icon visibility:
- Icons must meet WCAG AA standards (4.5:1 contrast ratio for normal text)
- Avoid relying solely on color to convey information
- Pair icons with text labels when possible

### Keyboard Navigation

Icons used as interactive elements must be keyboard-accessible:

```typescript
// Icon button with proper keyboard support
<IconButton 
  onClick={handleAction}
  aria-label="Approve transaction"
  tabIndex={0}
>
  <SuccessIcon />
</IconButton>
```

### Screen Reader Support

Test icons with screen readers to ensure proper announcement:
- Use `aria-label` for semantic meaning
- Use `aria-hidden="true"` for purely decorative icons
- Provide `titleAccess` prop for tooltip text

## Adding New Custom Icons

### Step 1: Create or Obtain SVG File

1. Design the icon in a vector graphics tool (Adobe Illustrator, Figma, Inkscape)
2. Export as SVG with the following settings:
   - ViewBox: `0 0 24 24` (standard 24×24 grid)
   - Remove unnecessary metadata and comments
   - Optimize path data
   - Use single color (typically black `#000000`) - color will be applied via CSS

**Example optimized SVG structure:**
```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
  <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z"/>
</svg>
```

### Step 2: Add SVG File to Directory

1. Name the file using kebab-case: `{function}-icon.svg`
2. Save the file in `frontend/src/assets/icons/`
3. Ensure file uses UTF-8 encoding

### Step 3: Create Wrapper Component (Optional)

If the icon requires custom sizing or theming, create a wrapper component in `CustomIcons.tsx`:

```typescript
import { SvgIcon, SvgIconProps } from '@mui/material';
import { ReactComponent as NewIconSvg } from './new-function-icon.svg';

export function CustomNewFunctionIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} component={NewIconSvg} inheritViewBox />
  );
}
```

### Step 4: Document the New Icon

Add documentation for the new icon in this README:
- Description of purpose and use cases
- Original BMS context (if applicable)
- Usage examples
- Any special considerations

### Step 5: Update Icon Index (if applicable)

If maintaining an icon index file, export the new icon:

```typescript
// icons/index.ts
export { CustomNewFunctionIcon } from './CustomIcons';
export { default as newFunctionIcon } from './new-function-icon.svg';
```

## Best Practices Summary

1. **Use Consistent Sizing**: Stick to small (16px), medium (24px), large (32px) sizes
2. **Leverage Material-UI Icons**: Use Material-UI's extensive icon library before creating custom icons
3. **Optimize SVG Files**: Remove unnecessary metadata, combine paths, minimize file size
4. **Maintain Visual Consistency**: All icons should follow the same visual style and weight
5. **Provide Accessibility**: Always include proper aria-labels and alt text
6. **Test Across Themes**: Verify icons render correctly in light and dark modes
7. **Document Usage**: Keep this README updated with new icons and usage patterns
8. **Version Control**: Track changes to SVG files to maintain design consistency
9. **Performance**: Lazy-load icons in large applications to reduce initial bundle size
10. **Fallbacks**: Provide text labels or alternative indicators for icon failures

## Troubleshooting

### Icon Not Displaying

**Problem**: Icon component renders but doesn't appear visually

**Solutions**:
- Verify the SVG file exists at the specified path
- Check that `fill="currentColor"` is set in the SVG to inherit CSS color
- Ensure the SVG has a proper `viewBox` attribute
- Verify parent container has sufficient size
- Check browser console for import/path errors

### Icon Size Issues

**Problem**: Icon appears too large, too small, or distorted

**Solutions**:
- Use the `size` prop: `small`, `medium`, or `large`
- For custom sizes, use the `sx` prop: `sx={{ fontSize: 40 }}`
- Ensure the SVG `viewBox` matches the actual icon content bounds
- Avoid setting both `width`/`height` and `fontSize` simultaneously

### Icon Color Not Changing

**Problem**: Icon remains black regardless of `color` prop

**Solutions**:
- Ensure SVG uses `fill="currentColor"` instead of hardcoded colors
- Use Material-UI color props: `color="primary"`, `color="error"`, etc.
- For custom colors, use `sx={{ color: '#hexcode' }}`
- Check that parent theme provider is properly configured

### Build Errors with SVG Imports

**Problem**: Webpack/Vite cannot resolve SVG imports

**Solutions**:
- Verify `vite.config.ts` includes `vite-plugin-svgr` for React component imports
- Use correct import syntax: `import { ReactComponent as Icon } from './icon.svg'`
- Ensure SVG files are in a location included by the build tool
- Check TypeScript declarations for SVG modules in `vite-env.d.ts`

## Related Documentation

- [Material-UI Icons Documentation](https://mui.com/material-ui/icons/)
- [SVG Optimization Guide](https://web.dev/svg-optimization/)
- [Accessible Icon Buttons](https://www.w3.org/WAI/ARIA/apg/patterns/button/)
- [React SVG Best Practices](https://create-react-app.dev/docs/adding-images-fonts-and-files/#adding-svgs)

## Maintenance

This README and the icons directory are maintained as part of the CardDemo React frontend application migration from the legacy COBOL/BMS mainframe system. For questions or contributions, please refer to the main project documentation.

**Last Updated**: 2025 (CardDemo Mainframe to Cloud Migration Project)
