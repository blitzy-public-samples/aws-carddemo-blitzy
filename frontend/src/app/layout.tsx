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

import { Inter } from 'next/font/google';

// Subpath is version-matched to Next.js 16 (repo pins next@16.2.11); the installed
// @mui/material-nextjs@9.1.1 exposes v13–v16 subpaths, all re-exporting the same
// AppRouterCacheProvider ("use v1X-appRouter with Next.js v1X" per MUI docs).
import { AppRouterCacheProvider } from '@mui/material-nextjs/v16-appRouter';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

import theme from './theme';
import { AppShell } from '@/components/AppShell';

/**
 * Self-hosted Inter typeface (the Blitzy brand font for headings and body),
 * loaded and optimized by `next/font/google`.
 *
 * `next/font` downloads Inter at BUILD time and serves it from the SAME ORIGIN
 * (`/_next/static/media/…`), so the browser never contacts the Google Fonts
 * hosts at runtime. That keeps the font compatible with the Content-Security-
 * Policy in `next.config.js` (`font-src 'self' data:`) and working offline; no
 * external stylesheet `<link>` is needed or allowed.
 *
 * It exposes the CSS custom property `--font-inter`, which the MUI theme
 * (`./theme`) references FIRST in `typography.fontFamily`. Inter is a variable
 * font, so omitting `weight` ships the full 100–900 axis and covers every MUI
 * variant weight (light/regular/medium/bold). `display: 'swap'` avoids a
 * blocking flash of invisible text, and `fallback` mirrors the theme's system
 * sans-serif stack. Entries are emitted verbatim into a double-quoted string in
 * the generated font module, so the multi-word family name carries its own
 * SINGLE quotes (double quotes would break that generated string).
 */
const inter = Inter({
    subsets: ['latin'],
    display: 'swap',
    variable: '--font-inter',
    fallback: ['system-ui', '-apple-system', "'Segoe UI'", 'Helvetica', 'Arial', 'sans-serif'],
});

/**
 * Static page metadata for the App Router. The title is the Blitzy brand name,
 * the same product name shown in the AppShell header (`APP_TITLE`); the
 * description is the Blitzy brand description. The description literal is split
 * only to keep source lines short — the joined value is the exact brand string.
 */
export const metadata: Metadata = {
    title: 'Blitzy',
    description:
        'Blitzy: Build enterprise software in days with our autonomous AI platform. ' +
        'Automate up to 80% of development. Infinite code context. Accelerate your roadmap by 5x.',
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
 * self-hosted Inter font that the theme references, and mounts the mandatory
 * MUI + AppShell provider tree around every page.
 *
 * The `inter.variable` class name is applied to `<html>` so the CSS custom
 * property `--font-inter` is defined document-wide; the MUI theme's
 * `typography.fontFamily` (`./theme`) consumes it first, with graceful fallback
 * to the system sans-serif stack. Because `next/font` self-hosts the typeface
 * from the same origin, no external stylesheet `<link>` is required, and no
 * `<head>` element is declared here — the App Router injects head content from
 * `metadata`.
 *
 * @param props - The {@link RootLayoutProps} carrying the active page content.
 * @returns The full HTML document with the global provider tree and app chrome.
 */
export default function RootLayout({ children }: RootLayoutProps) {
    return (
        <html lang="en" className={inter.variable}>
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
