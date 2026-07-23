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

// Subpath is version-matched to Next.js 16 (repo pins next@16.2.11); the installed
// @mui/material-nextjs@9.1.1 exposes v13–v16 subpaths, all re-exporting the same
// AppRouterCacheProvider ("use v1X-appRouter with Next.js v1X" per MUI docs).
import { AppRouterCacheProvider } from '@mui/material-nextjs/v16-appRouter';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

import theme from './theme';
import { AppShell } from '@/components/AppShell';

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
 * The application's root layout. Establishes the HTML document, loads the Roboto
 * font family that the theme references, and mounts the mandatory MUI + AppShell
 * provider tree around every page.
 *
 * The Roboto stylesheet is registered via a Google Fonts `<link>` so the theme's
 * literal `Roboto` family (`typography.fontFamily` in `./theme`) resolves to the
 * real typeface, with graceful fallback to Helvetica/Arial when the font is
 * unavailable. This keeps the font choice zero-coupling — no extra dependency and
 * no edit to the theme's font-family contract.
 *
 * @param props - The {@link RootLayoutProps} carrying the active page content.
 * @returns The full HTML document with the global provider tree and app chrome.
 */
export default function RootLayout({ children }: RootLayoutProps) {
    return (
        <html lang="en">
            <head>
                <link
                    rel="stylesheet"
                    href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&display=swap"
                />
            </head>
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
