/**
 * layout.tsx — Next.js App Router ROOT LAYOUT for the CardDemo frontend.
 *
 * Purpose: the single top-level layout that wraps EVERY route in the application.
 * It performs the required MUI v9 SSR integration (AppRouterCacheProvider) and
 * installs the global provider tree: the Emotion/MUI server-side cache, the global
 * Material Design 3 ThemeProvider, the CssBaseline global reset, and the AppShell
 * application chrome. All 17 pages render as `{children}` inside this layout.
 *
 * MUI v9 SSR integration (AAP §0.3.1, §0.4.3): `AppRouterCacheProvider` from
 * `@mui/material-nextjs` collects the Emotion CSS generated during SSR streaming
 * so styles are flushed before hydration, preventing a flash of unstyled content
 * and hydration style mismatches.
 *
 * Provider order (assigned-folder requirement, mandatory):
 *   AppRouterCacheProvider → ThemeProvider → CssBaseline → AppShell → {children}
 * Exactly one CssBaseline and one ThemeProvider live here; AppShell (per its
 * component contract) renders neither and relies on this provider tree.
 *
 * TRACEABILITY (Minimal Change Clause, AAP §0.8.1): the global header/menu chrome
 * that AppShell renders is the modern replacement for the legacy 3270 screen header
 * and the two CICS menu programs — COMEN01C (regular-user menu) and COADM01C (admin
 * menu). This root layout is the composition point where that chrome is mounted
 * around every page.
 *
 * SERVER COMPONENT: this file has NO `'use client'` directive on purpose. A root
 * layout must remain a Server Component so it can `export const metadata`. The
 * providers it renders (AppRouterCacheProvider, ThemeProvider, CssBaseline,
 * AppShell) are themselves Client Components; a Server Component rendering Client
 * Components is the standard, correct App Router pattern.
 */

import type { Metadata } from 'next';
import type { ReactNode } from 'react';

import { Roboto } from 'next/font/google';

// Subpath is version-matched to Next.js 16 (repo pins next@16.2.11); the installed
// @mui/material-nextjs@9.1.1 exposes v13–v16 subpaths, all re-exporting the same
// AppRouterCacheProvider ("use v1X-appRouter with Next.js v1X" per MUI docs).
import { AppRouterCacheProvider } from '@mui/material-nextjs/v16-appRouter';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

import theme from './theme';
import { AppShell } from '@/components/AppShell';

/**
 * Self-hosted Roboto typeface, loaded and optimized by `next/font/google`.
 *
 * FINDING-01 fix: `next/font` downloads Roboto at build time and serves it from
 * the SAME ORIGIN (`/_next/static/media/…`). The font therefore loads with no
 * runtime dependency on the external Google Fonts CDN — it works offline and
 * under any Content-Security-Policy — replacing the previous cross-origin
 * `<link rel="stylesheet" href="https://fonts.googleapis.com/…">` that failed
 * whenever that host was unreachable or blocked.
 *
 * It exposes the CSS custom property `--font-roboto`, which the MUI theme
 * (`./theme`) references FIRST in `typography.fontFamily`, falling back to the
 * literal `Roboto`, then Helvetica/Arial/sans-serif. Weights 300/400/500/700
 * cover the MUI variant scale (light/regular/medium/bold); `display: 'swap'`
 * avoids a blocking flash of invisible text.
 */
const roboto = Roboto({
    weight: ['300', '400', '500', '700'],
    subsets: ['latin'],
    display: 'swap',
    variable: '--font-roboto',
    fallback: ['Helvetica', 'Arial', 'sans-serif'],
});

/**
 * Static page metadata for the App Router. The title text derives from the legacy
 * application-title copybook `app/cpy/COTTL01Y.cpy` ('CardDemo'), the same name
 * used app-wide by the AppShell header (`APP_TITLE`).
 */
export const metadata: Metadata = {
    title: 'CardDemo',
    description: 'AWS CardDemo — modernized credit card management application',
};

/**
 * Props for {@link RootLayout}. The App Router injects the active route's rendered
 * output as `children`.
 */
interface RootLayoutProps {
    /** The active page (route segment) rendered inside the global provider tree. */
    children: ReactNode;
}

/**
 * The application's root layout. Establishes the HTML document, activates the
 * self-hosted Roboto font that the theme references, and mounts the mandatory
 * MUI + AppShell provider tree around every page.
 *
 * The `roboto.variable` class name is applied to `<html>` so the CSS custom
 * property `--font-roboto` is defined document-wide; the MUI theme's
 * `typography.fontFamily` (`./theme`) consumes it first, with graceful fallback
 * to Helvetica/Arial. Because `next/font` self-hosts the typeface from the same
 * origin, no external stylesheet `<link>` is required (FINDING-01), and no `<head>`
 * element is declared here — the App Router injects head content from `metadata`.
 *
 * @param props - The {@link RootLayoutProps} carrying the active page content.
 * @returns The full HTML document with the global provider tree and app chrome.
 */
export default function RootLayout({ children }: RootLayoutProps) {
    return (
        <html lang="en" className={roboto.variable}>
            <body>
                <AppRouterCacheProvider>
                    <ThemeProvider theme={theme}>
                        <CssBaseline />
                        <AppShell>{children}</AppShell>
                    </ThemeProvider>
                </AppRouterCacheProvider>
            </body>
        </html>
    );
}
