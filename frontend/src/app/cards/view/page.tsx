'use client';

/**
 * Card Detail (view) page — route /cards/view
 * Modernizes: BMS map COCRDSL (mapset CCRDSLA) · CICS Tx CCDL · COBOL COCRDSLC
 * Security: card_num is displayed MASKED (last-4); the card security code is
 *           never fetched, referenced, or rendered.
 *
 * Read-only credit-card detail panel. Reads the `cardNum` query-string param
 * (the card number the operator entered), fetches the card through
 * `CardsApi.GetCard`, and renders the five business fields from the legacy
 * COCRDSL map (Account Number, Card Number, Name on card, Card Active Y/N,
 * Expiry) with Edit (Enter equivalent) and Back (PF3-exit) actions.
 *
 * Navigation is faithful to the legacy COCRDSL screen, whose `CARDSID` is an
 * UNPROT "Card Number" input the operator TYPES: the page provides a card-number
 * key-capture picker and keys detail on that entered number via the frozen
 * GET /cards/{cardNum} contract (AAP 0.5.5). The earlier by-account variant was
 * removed because it resolved an account to a single card with `.limit(1)`,
 * silently returning the wrong card on the NONUNIQUE account->card relationship
 * (QA C07), and was outside the frozen route list (QA C08). The rendered
 * `card_num` is always MASKED (last-4) in both the list and this detail, so no
 * unmasked PAN is ever exposed by the UI (AAP 0.7.8); the number the operator
 * types is used only as the path key and never displayed unmasked.
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

/** Query-string key carrying the entered card number (`?cardNum=`). */
const CARD_NUM_QUERY_PARAM = 'cardNum';

/** Route path for this page; used to navigate the card-number picker. */
const CARDS_VIEW_ROUTE = '/cards/view';

/** Back target — the card-list screen (PF3-exit equivalent). */
const CARDS_LIST_ROUTE = '/cards';

/** Edit target base — the card-update screen; `?cardNum=` is appended. */
const CARD_UPDATE_ROUTE = '/cards/update';

/** Label for the card-number picker input (BMS CARDSID field). */
const CARD_NUM_PICKER_LABEL = 'Card Number';

/** Maximum card-number length; CARD-NUM PIC X(16) -> VARCHAR(16). */
const CARD_NUM_MAX_LENGTH = 16;

/** Label for the picker's load button (maps the legacy ENTER lookup). */
const LOAD_BUTTON_LABEL = 'LOAD';

/** Prompt shown when no card number is supplied (mirrors the COBOL guard). */
const MISSING_CARD_MESSAGE =
    'No card number provided. Select a card from the list or enter a card ' +
    'number to view its detail.';

/** Message shown when a completed fetch yields no card for the number. */
const NOT_FOUND_MESSAGE = 'No card found for this card number.';

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
 * Renders the read-only card-detail panel for the `cardNum` query param, plus a
 * card-number key-capture picker mirroring the legacy COCRDSL `CARDSID` input so
 * a card can always be reached manually (QA C07/C08).
 *
 * @returns The card-detail content element.
 */
function CardsViewContent() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const cardNum = searchParams.get(CARD_NUM_QUERY_PARAM) ?? '';

    const [cardData, setCardData] = useState<CardRead | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<
        ErrorResponse | string | unknown | null
    >(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    // Controlled value of the card-number picker input. Seeded from (and kept in
    // sync with) the `?cardNum=` query param so a bookmarked/loaded card shows
    // its number in the picker, while still allowing the user to type a new one.
    const [pickerValue, setPickerValue] = useState<string>(cardNum);

    useEffect(() => {
        setPickerValue(cardNum);
    }, [cardNum]);

    /**
     * Fetches the card for the current `cardNum`. Guards against a missing
     * param, handles failures SPECIFICALLY via {@link IsApiError} (never a
     * blanket catch — Ochs error-handling rule), surfaces any failure through
     * {@link ErrorAlert}, and always clears the loading flag. A fresh load
     * clears any stale card so a not-found number shows the not-found body
     * rather than the previous card. Memoized on `cardNum` for the effect below.
     */
    const LoadCard = useCallback(async (): Promise<void> => {
        if (!cardNum) {
            return;
        }
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const response = await CardsApi.GetCard(cardNum);
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
    }, [cardNum]);

    useEffect(() => {
        void LoadCard();
    }, [LoadCard]);

    /**
     * Updates the picker input value. Signature matches {@link FormField}'s
     * `onChange(name, value)` contract.
     *
     * @param _name - The originating field name (unused; single-field picker).
     * @param value - The new card-number input value.
     */
    const HandlePickerChange = (_name: string, value: string): void => {
        setPickerValue(value);
    };

    /**
     * Navigates to `/cards/view?cardNum=<entered number>`. The query-param
     * change re-drives {@link LoadCard} through the `useSearchParams` effect, so
     * the URL stays shareable/bookmarkable. An empty entry is ignored (mirrors
     * the legacy empty-key guard) rather than clearing the current view.
     */
    const HandleLoadClick = (): void => {
        const trimmedNum = pickerValue.trim();
        if (trimmedNum === '') {
            return;
        }
        const target =
            `${CARDS_VIEW_ROUTE}?${CARD_NUM_QUERY_PARAM}=` +
            `${encodeURIComponent(trimmedNum)}`;
        router.push(target);
    };

    /** Navigates to the card-update screen for the current card (Enter/Edit). */
    const HandleEdit = (): void => {
        if (cardNum) {
            const target =
                `${CARD_UPDATE_ROUTE}?${CARD_NUM_QUERY_PARAM}=` +
                `${encodeURIComponent(cardNum)}`;
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

    // Body has four mutually exclusive states: missing number, loading, loaded,
    // and not-found (the API error path usually pre-empts the last one).
    let bodyContent: ReactNode;
    if (!cardNum) {
        bodyContent = (
            <Typography variant="body1" color="text.secondary">
                {MISSING_CARD_MESSAGE}
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
                <CardHeader
                    title="Card Detail"
                    slotProps={{ title: { component: 'h2' } }}
                />
                <CardContent>
                    {/*
                      * Read-only card attributes are rendered as a description
                      * list (`<dl>` / `<dt>` / `<dd>`) so every value is
                      * programmatically associated with its label (QA N-01,
                      * "associated labels"). The `<dl>`/`<dd>` user-agent
                      * margins are reset (`m: 0`) so the description-list markup
                      * is a purely semantic change with no visual difference
                      * from the previous Box + Typography pairs. Typography's
                      * `component` prop keeps every node an MUI Typography
                      * (AAP §0.3.4 rule b), not a raw HTML text element.
                      */}
                    <Stack component="dl" spacing={2} sx={{ m: 0 }}>
                        <Box>
                            <Typography
                                component="dt"
                                variant="body2"
                                color="text.secondary"
                            >
                                Account ID
                            </Typography>
                            <Typography component="dd" variant="body1" sx={{ m: 0 }}>
                                {cardData.acct_id}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography
                                component="dt"
                                variant="body2"
                                color="text.secondary"
                            >
                                Card Number
                            </Typography>
                            <Typography component="dd" variant="body1" sx={{ m: 0 }}>
                                {MaskCardNumber(cardData.card_num)}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography
                                component="dt"
                                variant="body2"
                                color="text.secondary"
                            >
                                Name on Card
                            </Typography>
                            <Typography component="dd" variant="body1" sx={{ m: 0 }}>
                                {cardData.embossed_name}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography
                                component="dt"
                                variant="body2"
                                color="text.secondary"
                            >
                                Status
                            </Typography>
                            <Box component="dd" sx={{ m: 0 }}>
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
                        </Box>
                        <Box>
                            <Typography
                                component="dt"
                                variant="body2"
                                color="text.secondary"
                            >
                                Expiration
                            </Typography>
                            <Typography component="dd" variant="body1" sx={{ m: 0 }}>
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
            <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
                View Credit Card Detail
            </Typography>

            <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                sx={{ mb: 3, alignItems: { sm: 'flex-start' } }}
            >
                <FormField
                    name={CARD_NUM_QUERY_PARAM}
                    label={CARD_NUM_PICKER_LABEL}
                    value={pickerValue}
                    onChange={HandlePickerChange}
                    maxLength={CARD_NUM_MAX_LENGTH}
                    required
                    autoFocus
                    autoComplete="off"
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
