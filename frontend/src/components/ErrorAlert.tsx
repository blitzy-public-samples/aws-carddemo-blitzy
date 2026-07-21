'use client';

/**
 * ErrorAlert — a reusable error / validation / info feedback component built on
 * the Material UI `Snackbar` + `Alert` pair (AAP §0.3.2: Error → Snackbar + Alert
 * `severity="error"`).
 *
 * Traceability: this component is the modern replacement for the single 3270
 * message line rendered at the bottom of every legacy BMS map — the
 * `ERRMSGI` / `INFOMSGI` symbolic-map fields (for example `ERRMSGI PIC X(78)` in
 * `app/cpy-bms/COSGN00.CPY`) and the shared message text held in the
 * `CSMSG01Y` / `CSMSG02Y` copybooks. It also surfaces the transaction-posting
 * reason codes emitted by the batch posting validator `CBTRN02C`
 * (`app/cbl/CBTRN02C.cbl`, codes 100–103 and 109 — AAP §0.7.3), so a rejected
 * posting shows the operator the same reason description the mainframe wrote to
 * the `DALYREJS` reject file.
 *
 * Design-system rules (AAP §0.3.4): only MUI components are emitted (never a raw
 * `<div>` / `<span>` / `<p>`), and every spacing / size value resolves to a
 * theme-scale `sx` token (`width: 1` = 100%, `mt: 0.5` = half a spacing unit) —
 * never a hardcoded pixel string.
 *
 * Security (Ochs Test Rule): the component renders only the user-facing
 * `message` / `code` / `detail`; it never logs the raw error object and never
 * receives or displays secrets (there is no `cvv` in these payloads).
 *
 * @packageDocumentation
 */

import { Snackbar, Alert, AlertTitle, Typography, Box } from '@mui/material';
import type { AlertColor } from '@mui/material';
import type { SyntheticEvent } from 'react';

import type { ErrorResponse } from '@/types';
import { IsApiError } from '@/lib/apiClient';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Default Snackbar auto-hide delay, in milliseconds. */
const DEFAULT_AUTO_HIDE_DURATION = 6000;

/**
 * Snackbar close reason that must be IGNORED. A click elsewhere on the page
 * ("clickaway") must NOT dismiss an error the user still needs to read; only the
 * explicit close button or the auto-hide timeout may dismiss it.
 */
const CLICKAWAY_REASON = 'clickaway';

/** Fallback shown when an error of an unrecognized shape reaches the component. */
const UNEXPECTED_ERROR_MESSAGE = 'An unexpected error occurred.';

/**
 * Canonical transaction-posting reason descriptions, copied VERBATIM from the
 * batch posting validator `CBTRN02C` (`app/cbl/CBTRN02C.cbl`). Used as a
 * fallback description when the backend sends only a numeric `code`:
 *   - 100 — invalid card cross-reference (READ XREF INVALID KEY, L385).
 *   - 101 — account not found on the account READ (INVALID KEY, L397).
 *   - 102 — over-limit transaction (L410).
 *   - 103 — transaction received after account expiration (L417).
 *   - 109 — account not found on the REWRITE during posting (L556).
 * (AAP §0.7.3.)
 */
const POSTING_CODE_MESSAGES: Readonly<Record<string, string>> = {
    '100': 'INVALID CARD NUMBER FOUND',
    '101': 'ACCOUNT RECORD NOT FOUND',
    '102': 'OVERLIMIT TRANSACTION',
    '103': 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION',
    '109': 'ACCOUNT RECORD NOT FOUND',
};

/* ------------------------------------------------------------------------- */
/* Props (single typed object — Ochs ≤4-parameter rule).                     */
/* ------------------------------------------------------------------------- */

/**
 * Props for {@link ErrorAlert}. Grouping every input into one object satisfies
 * the Ochs "max 4 parameters" rule (the component takes a single props argument).
 */
export interface ErrorAlertProps {
    /** Whether the alert is visible; controls the underlying MUI `Snackbar`. */
    open: boolean;
    /** Invoked when dismissed (the Alert close button or the auto-hide timeout). */
    onClose: () => void;
    /**
     * The error to display. Accepts a normalized {@link ErrorResponse}, a raw
     * string, an unknown thrown value (for example a caught `ApiError`), or
     * `null`; {@link NormalizeError} resolves it to displayable fields.
     */
    error: ErrorResponse | string | unknown | null;
    /** Alert color / severity; defaults to `'error'`. */
    severity?: AlertColor;
    /** Auto-hide delay in ms; defaults to `DEFAULT_AUTO_HIDE_DURATION` (6000). */
    autoHideDuration?: number;
    /** Optional heading rendered as an `AlertTitle` above the message. */
    title?: string;
}

/* ------------------------------------------------------------------------- */
/* Normalization helper (small, specific — Ochs error-handling rule).        */
/* ------------------------------------------------------------------------- */

/** The display fields resolved from an arbitrary error input. */
interface NormalizedError {
    message: string;
    code?: string;
    detail?: string;
}

/** Narrows an unknown value to a string-keyed record for safe field probing. */
function IsRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

/**
 * Resolves an arbitrary error input into displayable `{ message, code?, detail? }`
 * fields using ordered, SPECIFIC checks (never a blanket cast — Ochs
 * error-handling rule). When only a numeric `code` is supplied, the message is
 * backfilled from {@link POSTING_CODE_MESSAGES}.
 *
 * @param error - The value to normalize (ErrorResponse, string, ApiError, null).
 * @returns The resolved display fields.
 */
function NormalizeError(error: ErrorResponse | string | unknown | null): NormalizedError {
    let resolved: NormalizedError = { message: UNEXPECTED_ERROR_MESSAGE };

    if (error === null || error === undefined) {
        resolved = { message: '' };
    } else if (typeof error === 'string') {
        resolved = { message: error };
    } else if (IsApiError(error)) {
        resolved = { message: error.message, code: error.code, detail: error.detail };
    } else if (
        IsRecord(error) &&
        (typeof error.message === 'string' || typeof error.code === 'string')
    ) {
        resolved = {
            message: typeof error.message === 'string' ? error.message : '',
            code: typeof error.code === 'string' ? error.code : undefined,
            detail: typeof error.detail === 'string' ? error.detail : undefined,
        };
    }

    if (resolved.code && !resolved.message) {
        const backfill = POSTING_CODE_MESSAGES[resolved.code];
        if (backfill) {
            resolved = { ...resolved, message: backfill };
        }
    }

    return resolved;
}

/* ------------------------------------------------------------------------- */
/* Component.                                                                */
/* ------------------------------------------------------------------------- */

/**
 * Renders a top-anchored MUI `Snackbar` carrying a filled `Alert` with the
 * resolved error message (optionally prefixed with its posting code, and with an
 * optional title and secondary detail line).
 *
 * @param props - The {@link ErrorAlertProps} controlling the alert.
 * @returns The rendered Snackbar / Alert element.
 *
 * @example
 * ```tsx
 * // Posting rejection surfaced with its reason code (renders "[102] OVERLIMIT
 * // TRANSACTION"); passing only `{ code: '102' }` backfills the same text.
 * <ErrorAlert
 *     open={isAlertOpen}
 *     onClose={HandleAlertClose}
 *     error={{ code: '102', message: 'OVERLIMIT TRANSACTION' }}
 * />
 * ```
 */
export function ErrorAlert(props: ErrorAlertProps) {
    const { severity = 'error', autoHideDuration = DEFAULT_AUTO_HIDE_DURATION } = props;

    const normalized = NormalizeError(props.error);

    /**
     * Bridges the `Snackbar` close signature to `props.onClose`, IGNORING the
     * click-away reason so an incidental click cannot dismiss the message.
     *
     * @param event - The originating synthetic or DOM event (unused).
     * @param reason - Why close was requested ('timeout' / 'clickaway' / ...).
     */
    const HandleClose = (event: SyntheticEvent | Event, reason?: string): void => {
        if (reason === CLICKAWAY_REASON) {
            return;
        }
        props.onClose();
    };

    return (
        <Snackbar
            open={props.open}
            autoHideDuration={autoHideDuration}
            onClose={HandleClose}
            anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
        >
            <Alert
                severity={severity}
                variant="filled"
                onClose={props.onClose}
                sx={{ width: 1 }}
            >
                {props.title ? <AlertTitle>{props.title}</AlertTitle> : null}
                <Box>
                    <Typography variant="body2" component="span">
                        {normalized.code
                            ? `[${normalized.code}] ${normalized.message}`
                            : normalized.message}
                    </Typography>
                    {normalized.detail ? (
                        <Typography variant="caption" component="div" sx={{ mt: 0.5 }}>
                            {normalized.detail}
                        </Typography>
                    ) : null}
                </Box>
            </Alert>
        </Snackbar>
    );
}
