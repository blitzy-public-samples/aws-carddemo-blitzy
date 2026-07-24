'use client';

/**
 * theme.ts — Global Material UI (MUI v9) theme for the CardDemo frontend.
 *
 * Purpose: the SINGLE source of truth for all design tokens (palette,
 * typography, shape) consumed by `<ThemeProvider theme={theme}>` in
 * `src/app/layout.tsx` and, transitively, by every one of the 17 pages and the
 * shared MUI components. It replaces the legacy 3270 attribute-byte color model
 * with the MUI / Material Design 3 semantic palette.
 *
 * MUI v9 default Material Design 3 theme; single token source of truth; no Figma
 * (AAP §0.9.2). The token values below intentionally MATCH the MUI default light
 * theme (verbatim from AAP §0.3.2) so the application renders as stock Material
 * Design 3. They are written out explicitly to document the token contract and
 * to lock it against future MUI default drift; spacing (8px base), breakpoints,
 * elevation (shadows) and the typography variant scale are deliberately left at
 * their MUI defaults and therefore not overridden here.
 *
 * TOKEN-DEFINITION EXEMPTION (AAP §0.3.4): the hex / rgba color literals AND the
 * named `layout` sizing tokens (`drawerWidth`, `fullWidth`, `fullViewportHeight`)
 * in this file are the token DEFINITIONS and are explicitly permitted here. The
 * "zero hardcoded CSS values" rule applies to the component/page files that
 * CONSUME the theme (via `sx` / `theme`) — never to this theme-definition file.
 */

import { createTheme } from '@mui/material/styles';
import type { ThemeOptions } from '@mui/material/styles';

/**
 * Layout-token contract (QA finding M-25). The design-system rule (AAP §0.3.4)
 * requires every CSS value in the CONSUMING component/page files to resolve to a
 * theme token — magic layout literals such as the `240` drawer width, `100%`
 * fill-parent width and `100vh` full-viewport height are NOT among the six
 * exempt keywords and must not be hardcoded at the call site. They are therefore
 * hoisted here, into the theme, as NAMED tokens under a custom `layout`
 * namespace, and consumed via `sx={(theme) => ({ ... theme.layout.X })}`.
 *
 * The augmentation makes `layout` a required member of the resolved `Theme` (so
 * consumers get non-optional, typo-safe access) while keeping it optional on
 * `ThemeOptions` (so `createTheme` does not force every caller to re-supply it;
 * the single application theme below sets it once, and the test theme inherits
 * it via `createTheme(theme, …)`).
 */
declare module '@mui/material/styles' {
    interface Theme {
        layout: {
            /** Side-navigation drawer width in pixels (AppShell). */
            drawerWidth: number;
            /** Fill-parent width token (`100%`) for flex/region children. */
            fullWidth: string;
            /** Full-viewport height token (`100vh`) for full-page shells. */
            fullViewportHeight: string;
        };
    }
    interface ThemeOptions {
        layout?: {
            drawerWidth?: number;
            fullWidth?: string;
            fullViewportHeight?: string;
        };
    }
}

/**
 * Explicit token contract for the CardDemo theme. Typed as `ThemeOptions` so the
 * TypeScript compiler rejects any unknown key or malformed token value.
 */
const themeOptions: ThemeOptions = {
    palette: {
        mode: 'light',
        primary: {
            main: '#1976d2',
            light: '#42a5f5',
            dark: '#1565c0',
            contrastText: '#ffffff',
        },
        secondary: {
            main: '#9c27b0',
            light: '#ba68c8',
            dark: '#7b1fa2',
            contrastText: '#ffffff',
        },
        // Semantic roles: drive ErrorAlert severity, status Chips and form
        // validation feedback across the app (AAP §0.3.2).
        error: {
            main: '#d32f2f',
        },
        warning: {
            main: '#ed6c02',
        },
        info: {
            main: '#0288d1',
        },
        success: {
            main: '#2e7d32',
        },
        background: {
            default: '#ffffff',
            paper: '#ffffff',
        },
        // Text roles set explicitly to the identical MUI defaults for contract
        // documentation; omitting them would inherit the same values.
        text: {
            primary: 'rgba(0, 0, 0, 0.87)',
            secondary: 'rgba(0, 0, 0, 0.6)',
            disabled: 'rgba(0, 0, 0, 0.38)',
        },
    },
    typography: {
        // Only the font family is set; the MUI default variant scale (h1–h6,
        // subtitle1/2, body1/2, button, caption, overline) IS the MD3 scale and
        // is intentionally left untouched (AAP §0.3.2).
        fontFamily: 'Roboto, Helvetica, Arial, sans-serif',
    },
    shape: {
        borderRadius: 4,
    },
    // Named layout tokens (QA finding M-25). These are the token DEFINITIONS for
    // the drawer width and the fill-parent / full-viewport sizing keywords; they
    // are permitted HERE (theme-definition file) under the same exemption as the
    // color literals above, and consumed symbolically as `theme.layout.*` by the
    // AppShell, DataTable and signon page rather than as inline magic values.
    layout: {
        drawerWidth: 240,
        fullWidth: '100%',
        fullViewportHeight: '100vh',
    },
};

/**
 * The single, global, ready-to-use MUI theme. Imported as a default import by
 * `src/app/layout.tsx`: `import theme from './theme';`.
 */
const theme = createTheme(themeOptions);

export default theme;
