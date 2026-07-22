/*
 * page.tsx -- Application root route ("/").
 *
 * TRACEABILITY (Minimal Change Clause, AAP Section 0.8.1) + QA finding F4:
 * The 17 modern pages correspond 1:1 to the legacy BMS maps (AAP Section 0.4.4),
 * whose entry point is always the COSGN00 signon screen (CICS transaction CC00,
 * COBOL program COSGN00C) -- a mainframe session began at signon. The modern SPA
 * therefore has no content of its own at "/"; before this route existed, loading
 * the site root returned the framework 404 (QA finding F4). This server component
 * closes that gap by immediately redirecting "/" to the signon screen, so the
 * documented landing behavior matches the legacy entry flow and the compose
 * frontend healthcheck's /signon probe corresponds to the real first screen.
 *
 * This is a Server Component (no `use client` directive): the redirect is issued
 * during server rendering, so the browser is sent straight to "/signon" without
 * first downloading and hydrating an otherwise-empty client page.
 */

import { redirect } from 'next/navigation';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs Test Rule: ALL_UPPERCASE with underscores).        */
/* ------------------------------------------------------------------------- */

// Canonical landing route: the signon screen (legacy BMS map COSGN00 / CC00).
const SIGNON_ROUTE = '/signon';

/**
 * Root route handler for "/".
 *
 * Redirects every request for the site root to the signon screen. `redirect()`
 * never returns (it throws the framework's redirect control-flow signal), so the
 * declared `never` return type documents that no element is ever rendered here.
 *
 * @returns This function never returns; it always triggers a redirect.
 */
export default function RootPage(): never {
    redirect(SIGNON_ROUTE);
}
