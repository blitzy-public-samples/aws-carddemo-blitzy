'use client';

/**
 * Card Detail (view) page — route /cards/view
 * Modernizes: BMS map COCRDSL (mapset CCRDSLA) · CICS Tx CCDL · COBOL COCRDSLC
 * Security: card_num is displayed MASKED (last-4); the card security code is
 *           never fetched, referenced, or rendered.
 *
 * Read-only credit-card detail panel. Reads the `acctId` query-string param
 * (the UNMASKED owning-account id), fetches the account's card through
 * `CardsApi.GetCardByAccount`, and renders the five business fields from the
 * legacy COCRDSL map (Account Number, Card Number, Name on card, Card Active
 * Y/N, Expiry) with Edit (Enter equivalent) and Back (PF3-exit) actions.
 *
 * QA C1: the card LIST masks `card_num` (AAP 0.7.8), so a masked PAN can never
 * be a valid card key -- navigating card detail by it produced a 422 dead-end
 * with no manual fallback. This page therefore keys card detail on the account
 * id (not the PAN), and additionally provides an account-id key-capture picker
 * (mirroring /accounts/view) so a card can always be reached manually. The
 * backend resolves the account to its card server-side via the CARD-ACCT-ID
 * alternate index; the full PAN never appears in a URL.
 *
 * All identity/role propagation that the legacy COMMAREA carried is now handled
 * by the shared api client (session cookie / bearer token).
 */

import { Suspense, useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import {
    Box,
    Stack,
    Card,
    CardHeader,
    CardContent,
    CardActions,
    Typography,
    Chip,
    Button,
    CircularProgress,
} from '@mui/material';

import { CardsApi, IsApiError } from '@/lib/apiClient';
import { ErrorAlert } from '@/components/ErrorAlert';
import { FormField } from '@/components/FormField';
import type { CardRead, ErrorResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * Maps the single-character `active_status` (legacy `CARD-ACTIVE-STATUS PIC
 * X(01)`, BMS label 'Card Active Y/N') to a human-readable label. Aligns with
 * the sibling /cards list page.
 */
const ACTIVE_STATUS_LABEL: Record<string, string> = {
    Y: 'Active',
    N: 'Inactive',
};

/** Single-character status value that marks a card as active. */
const ACTIVE_STATUS_VALUE = 'Y';

/** Query-string key carrying the UNMASKED owning-account id (`?acctId=`). */
const ACCT_ID_QUERY_PARAM = 'acctId';

/** Route path for this page; used to navigate the account-id picker. */
const CARDS_VIEW_ROUTE = '/cards/view';

/** Back target — the card-list screen (PF3-exit equivalent). */
const CARDS_LIST_ROUTE = '/cards';

/** Edit target base — the card-update screen; `?acctId=` is appended. */
const CARD_UPDATE_ROUTE = '/cards/update';

/** Label for the account-id picker input (BMS ACCTSID field). */
const ACCT_ID_PICKER_LABEL = 'Account Number';

/** Maximum account-id length; ACCT-ID PIC 9(11) -> VARCHAR(11). */
const ACCT_ID_MAX_LENGTH = 11;

/** Label for the picker's load button (maps the legacy ENTER lookup). */
const LOAD_BUTTON_LABEL = 'LOAD';

/** Prompt shown when no account id is supplied (mirrors the COBOL guard). */
const MISSING_ACCT_MESSAGE =
    'No account number provided. Select a card from the list or enter an ' +
    'account number to view its card.';

/** Message shown when a completed fetch yields no card for the account. */
const NOT_FOUND_MESSAGE = 'No card found for this account.';

/** Fallback error text used when a non-API error is caught. */
const GENERIC_LOAD_ERROR = 'Failed to load the card details.';

/** Number of trailing card digits kept visible when masking (defensive). */
const VISIBLE_CARD_DIGITS = 4;

/** Glyph used to mask every non-visible card digit. */
const MASK_CHARACTER = '•';

/* ------------------------------------------------------------------------- */
/* Small display helpers (Ochs: <=20 lines, <=4 params, multi-line blocks).  */
/* ------------------------------------------------------------------------- */

/**
 * Masks a card number so a full PAN is never rendered even if one is passed.
 * `card_num` already arrives masked from the backend; this is purely defensive
 * and idempotent-safe.
 *
 * @param cardNumber - The (already-masked) card identifier to display.
 * @returns The last {@link VISIBLE_CARD_DIGITS} digits, prefixed with mask glyphs.
 */
function MaskCardNumber(cardNumber: string): string {
    if (!cardNumber) {
        return '';
    }
    const trimmedValue = cardNumber.trim();
    if (trimmedValue.length <= VISIBLE_CARD_DIGITS) {
        return trimmedValue;
    }
    const lastFour = trimmedValue.slice(-VISIBLE_CARD_DIGITS);
    const maskedLength = trimmedValue.length - VISIBLE_CARD_DIGITS;
    return `${MASK_CHARACTER.repeat(maskedLength)}${lastFour}`;
}

/**
 * Formats the backend ISO `expiration_date` (e.g. `YYYY-MM-DD`) into the
 * `MM/YYYY` display used by the legacy `EXPMON`/`EXPYEAR` fields.
 *
 * @param expirationDate - The ISO expiration date returned by the backend.
 * @returns The expiration formatted as `MM/YYYY`, or the original string.
 */
function FormatExpiration(expirationDate: string): string {
    if (!expirationDate) {
        return '';
    }
    const parts = expirationDate.split('-');
    if (parts.length >= 2) {
        const year = parts[0];
        const month = parts[1];
        return `${month}/${year}`;
    }
    return expirationDate;
}

/* ------------------------------------------------------------------------- */
/* Inner content component — owns `useSearchParams`, so it MUST live inside a */
/* <Suspense> boundary (provided by the default export below). Next.js 16     */
/* errors the build/runtime if `useSearchParams` has no Suspense ancestor.    */
/* ------------------------------------------------------------------------- */

/**
 * Renders the read-only card-detail panel for the `acctId` query param, plus an
 * account-id key-capture picker so a card can always be reached manually (QA C1).
 *
 * @returns The card-detail content element.
 */
function CardsViewContent() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const acctId = searchParams.get(ACCT_ID_QUERY_PARAM) ?? '';

    const [cardData, setCardData] = useState<CardRead | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<
        ErrorResponse | string | unknown | null
    >(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    // Controlled value of the account-id picker input. Seeded from (and kept in
    // sync with) the `?acctId=` query param so a bookmarked/loaded account shows
    // its id in the picker, while still allowing the user to type a new one.
    const [pickerValue, setPickerValue] = useState<string>(acctId);

    useEffect(() => {
        setPickerValue(acctId);
    }, [acctId]);

    /**
     * Fetches the card owned by the current `acctId`. Guards against a missing
     * param, handles failures SPECIFICALLY via {@link IsApiError} (never a
     * blanket catch — Ochs error-handling rule), surfaces any failure through
     * {@link ErrorAlert}, and always clears the loading flag. A fresh load
     * clears any stale card so a not-found account shows the not-found body
     * rather than the previous card. Memoized on `acctId` for the effect below.
     */
    const LoadCard = useCallback(async (): Promise<void> => {
        if (!acctId) {
            return;
        }
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const response = await CardsApi.GetCardByAccount(acctId);
            setCardData(response);
        } catch (fetchError: unknown) {
            setCardData(null);
            if (IsApiError(fetchError)) {
                setErrorState(fetchError);
            } else {
                setErrorState(GENERIC_LOAD_ERROR);
            }
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [acctId]);

    useEffect(() => {
        void LoadCard();
    }, [LoadCard]);

    /**
     * Updates the picker input value. Signature matches {@link FormField}'s
     * `onChange(name, value)` contract.
     *
     * @param _name - The originating field name (unused; single-field picker).
     * @param value - The new account-id input value.
     */
    const HandlePickerChange = (_name: string, value: string): void => {
        setPickerValue(value);
    };

    /**
     * Navigates to `/cards/view?acctId=<entered id>`. The query-param change
     * re-drives {@link LoadCard} through the `useSearchParams` effect, so the URL
     * stays shareable/bookmarkable. An empty entry is ignored (mirrors the legacy
     * empty-id guard) rather than clearing the current view.
     */
    const HandleLoadClick = (): void => {
        const trimmedId = pickerValue.trim();
        if (trimmedId === '') {
            return;
        }
        const target =
            `${CARDS_VIEW_ROUTE}?${ACCT_ID_QUERY_PARAM}=` +
            `${encodeURIComponent(trimmedId)}`;
        router.push(target);
    };

    /** Navigates to the card-update screen for the current account (Enter/Edit). */
    const HandleEdit = (): void => {
        if (acctId) {
            const target =
                `${CARD_UPDATE_ROUTE}?${ACCT_ID_QUERY_PARAM}=` +
                `${encodeURIComponent(acctId)}`;
            router.push(target);
        }
    };

    /** Returns to the card-list screen (PF3-exit → /cards). */
    const HandleBack = (): void => {
        router.push(CARDS_LIST_ROUTE);
    };

    /** Dismisses the error alert. */
    const HandleErrorClose = (): void => {
        setIsErrorOpen(false);
    };

    // Body has four mutually exclusive states: missing id, loading, loaded, and
    // not-found (the API error path usually pre-empts the last one).
    let bodyContent: ReactNode;
    if (!acctId) {
        bodyContent = (
            <Typography variant="body1" color="text.secondary">
                {MISSING_ACCT_MESSAGE}
            </Typography>
        );
    } else if (isLoading && !cardData) {
        bodyContent = (
            <Box sx={{ p: 2 }}>
                <CircularProgress aria-label="Loading card details" />
            </Box>
        );
    } else if (cardData) {
        bodyContent = (
            <Card>
                <CardHeader title="Card Detail" />
                <CardContent>
                    <Stack spacing={2}>
                        <Box>
                            <Typography variant="body2" color="text.secondary">
                                Account ID
                            </Typography>
                            <Typography variant="body1">
                                {cardData.acct_id}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography variant="body2" color="text.secondary">
                                Card Number
                            </Typography>
                            <Typography variant="body1">
                                {MaskCardNumber(cardData.card_num)}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography variant="body2" color="text.secondary">
                                Name on Card
                            </Typography>
                            <Typography variant="body1">
                                {cardData.embossed_name}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography variant="body2" color="text.secondary">
                                Status
                            </Typography>
                            <Chip
                                label={
                                    ACTIVE_STATUS_LABEL[cardData.active_status] ??
                                    cardData.active_status
                                }
                                color={
                                    cardData.active_status === ACTIVE_STATUS_VALUE
                                        ? 'success'
                                        : 'default'
                                }
                            />
                        </Box>
                        <Box>
                            <Typography variant="body2" color="text.secondary">
                                Expiration
                            </Typography>
                            <Typography variant="body1">
                                {FormatExpiration(cardData.expiration_date)}
                            </Typography>
                        </Box>
                    </Stack>
                </CardContent>
                <CardActions>
                    <Stack direction="row" spacing={2}>
                        <Button
                            variant="contained"
                            color="primary"
                            onClick={HandleEdit}
                        >
                            Edit
                        </Button>
                        <Button variant="outlined" onClick={HandleBack}>
                            Back
                        </Button>
                    </Stack>
                </CardActions>
            </Card>
        );
    } else {
        bodyContent = (
            <Typography variant="body1">{NOT_FOUND_MESSAGE}</Typography>
        );
    }

    return (
        <Box sx={{ p: 2 }}>
            <Typography variant="h5" sx={{ mb: 2 }}>
                View Credit Card Detail
            </Typography>

            <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                sx={{ mb: 3, alignItems: { sm: 'flex-start' } }}
            >
                <FormField
                    name={ACCT_ID_QUERY_PARAM}
                    label={ACCT_ID_PICKER_LABEL}
                    value={pickerValue}
                    onChange={HandlePickerChange}
                    maxLength={ACCT_ID_MAX_LENGTH}
                    required
                    autoFocus
                />
                <Button
                    variant="contained"
                    onClick={HandleLoadClick}
                    disabled={isLoading}
                >
                    {LOAD_BUTTON_LABEL}
                </Button>
            </Stack>

            {bodyContent}

            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Box>
    );
}

/* ------------------------------------------------------------------------- */
/* Default export — thin wrapper providing the mandatory <Suspense> boundary  */
/* around the content that calls `useSearchParams`. Next.js resolves this     */
/* component at the static route /cards/view.                                 */
/* ------------------------------------------------------------------------- */

/**
 * Card Detail page. Wraps {@link CardsViewContent} in a Suspense boundary so
 * the `useSearchParams` client-side-rendering bailout is satisfied.
 *
 * @returns The Suspense-wrapped card-detail page.
 */
export default function CardsViewPage() {
    return (
        <Suspense
            fallback={
                <Box sx={{ p: 2 }}>
                    <CircularProgress />
                </Box>
            }
        >
            <CardsViewContent />
        </Suspense>
    );
}
