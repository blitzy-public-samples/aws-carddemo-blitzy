/*
 * not-found.tsx -- App Router root not-found UI (unmatched URLs and notFound()).
 *
 * Renders the framework's standard 404 status and message with Material UI
 * Typography inside a Container, so the page takes the theme's canvas,
 * typography and spacing within the application shell like every other route.
 * It has no legacy BMS map counterpart.
 *
 * This is a Server Component (no `use client` directive) so it can export the
 * route `metadata` that sets the document title.
 */

import type { Metadata } from 'next';
import { Container, Typography } from '@mui/material';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs Test Rule: ALL_UPPERCASE with underscores).        */
/* ------------------------------------------------------------------------- */

/** HTTP status shown as the page heading. */
const NOT_FOUND_STATUS = '404';

/** Explanation shown beneath the heading. */
const NOT_FOUND_MESSAGE = 'This page could not be found.';

/** Document title for the not-found page. */
const NOT_FOUND_METADATA: Metadata = {
    title: `${NOT_FOUND_STATUS}: ${NOT_FOUND_MESSAGE}`,
};

// Next.js reads route metadata only from an export named `metadata`.
export { NOT_FOUND_METADATA as metadata };

/**
 * Not-found page component, rendered for any URL the app does not handle.
 *
 * @returns The rendered not-found content region.
 */
export default function NotFound() {
    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Typography variant="h4" component="h1" gutterBottom>
                {NOT_FOUND_STATUS}
            </Typography>
            <Typography variant="body1">{NOT_FOUND_MESSAGE}</Typography>
        </Container>
    );
}
