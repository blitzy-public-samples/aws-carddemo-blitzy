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
 * Blitzy brand theme on MUI v9; single token source of truth; no Figma. The
 * primary, secondary, background, text and divider tokens are the Blitzy Brand
 * Inputs, used verbatim: primary #5B39F3 and secondary #333333 (both with white
 * contrast text), canvas #F2F0FE, paper surfaces #FFFFFF, text #000000 and
 * #666666, and dividers/borders #D9D9D9. Every brand text pairing meets WCAG AA
 * (the lowest, #666666 on the #F2F0FE canvas, is 5.10:1). The semantic roles
 * (error / warning / info / success), grey, spacing (8px base), breakpoints,
 * elevation (shadows) and the typography variant scale stay at their MUI
 * defaults. Page and component files consume these tokens through `sx` /
 * `theme` and never repeat a brand hex value.
 *
 * TOKEN-DEFINITION EXEMPTION (AAP §0.3.4): the hex / rgba color literals, the
 * named `layout` sizing tokens (`drawerWidth`, `fullWidth`, `fullViewportHeight`)
 * AND the 1px border width in the `components` overrides below are the token
 * DEFINITIONS in this file and are explicitly permitted here. The
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
        // Brand roles. Only `main` and `contrastText` are set: `createTheme`
        // derives `light` (main lightened 20%) and `dark` (main darkened 30%)
        // from the brand main, so hover and emphasis shades stay on-brand and
        // the darker shade only ever raises the contrast of the white text.
        primary: {
            main: '#5B39F3',
            contrastText: '#FFFFFF',
        },
        secondary: {
            main: '#333333',
            contrastText: '#FFFFFF',
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
        // Brand canvas behind every page, and the white surface of the
        // AppBar, drawer, cards, dialogs and menus.
        background: {
            default: '#F2F0FE',
            paper: '#FFFFFF',
        },
        // Brand text roles. `disabled` keeps the MUI default; disabled controls
        // are exempt from the WCAG contrast minimum.
        text: {
            primary: '#000000',
            secondary: '#666666',
            disabled: 'rgba(0, 0, 0, 0.38)',
        },
        // Brand divider/border color for Divider, the drawer edge, outlined
        // Paper, table cells and the AppBar bottom edge. It is decorative
        // separation only (1.41:1 on paper), so form-control outlines are not
        // tied to it and keep the darker MUI default.
        divider: '#D9D9D9',
    },
    typography: {
        // Only the font family is set; the MUI default variant scale (h1–h6,
        // subtitle1/2, body1/2, button, caption, overline) IS the MD3 scale and
        // is intentionally left untouched (AAP §0.3.2).
        //
        // `var(--font-inter)` resolves to the self-hosted Inter family (the
        // brand font for headings and body) that `src/app/layout.tsx` activates
        // via `next/font/google` (the class is applied to <html>, defining the
        // custom property document-wide). Listing it FIRST makes MUI use the
        // same-origin, CSP-safe, offline-capable font; the system sans-serif
        // stack after it is the fallback, so the family degrades gracefully if
        // the variable is unset.
        fontFamily: 'var(--font-inter), system-ui, -apple-system, "Segoe UI", Helvetica, Arial, sans-serif',
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
    components: {
        // The header is a white paper surface with black text and a divider
        // edge rather than a primary-colored bar, so the purple brand logo stays
        // visible on it. These root overrides are applied after the AppBar's
        // built-in color variant, replacing its primary background; header
        // controls with `color="inherit"` pick up the black text color.
        MuiAppBar: {
            styleOverrides: {
                root: ({ theme }) => ({
                    backgroundColor: theme.palette.background.paper,
                    color: theme.palette.text.primary,
                    borderBottom: `1px solid ${theme.palette.divider}`,
                }),
            },
        },
        // MUI derives table-cell borders by lightening the divider 88%, which
        // turns the opaque brand divider into a near-invisible #FAFAFA. Pin the
        // border color to the divider token so table rows keep the brand border.
        MuiTableCell: {
            styleOverrides: {
                root: ({ theme }) => ({
                    borderBottomColor: theme.palette.divider,
                }),
            },
        },
        // MUI v9 Typography only maps palette keys and `textSecondary`-style
        // names in its `color` prop and silently drops the dotted
        // `color="text.secondary"` the pages use. Resolve it here so secondary
        // copy renders the brand secondary text color instead of black.
        MuiTypography: {
            variants: [
                {
                    props: { color: 'text.secondary' },
                    style: ({ theme }) => ({
                        color: theme.palette.text.secondary,
                    }),
                },
            ],
        },
    },
};

/**
 * The single, global, ready-to-use MUI theme. Imported as a default import by
 * `src/app/layout.tsx`: `import theme from './theme';`.
 */
const theme = createTheme(themeOptions);

export default theme;
