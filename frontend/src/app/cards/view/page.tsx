'use client';

/**
 * Card Detail (view) page — route /cards/view
 * Modernizes: BMS map COCRDSL (mapset CCRDSLA) · CICS Tx CCDL · COBOL COCRDSLC
 * Security: card_num is displayed MASKED (last-4); the card security code is
 *           never fetched, referenced, or rendered.
 *
 * Read-only credit-card detail panel. Reads the `cardNum` query-string param,
 * fetches the card through `CardsApi.GetCard`, and renders the five business
 * fields from the legacy COCRDSL map (Account Number, Card Number, Name on card,
 * Card Active Y/N, Expiry) with Edit (Enter equivalent) and Back (PF3-exit)
 * actions. All identity/role propagation that the legacy COMMAREA carried is now
 * handled by the shared api client (session cookie / bearer token).
 */

import { Suspense, useState, useEffect, useCallback } from 'react';
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

import { CardsApi } from '@/lib/apiClient';
import { ErrorAlert } from '@/components/ErrorAlert';
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

/** Back target — the card-list screen (PF3-exit equivalent). */
const CARDS_LIST_ROUTE = '/cards';

/** Edit target base — the card-update screen; `?cardNum=` is appended. */
const CARD_UPDATE_ROUTE = '/cards/update';

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
 * Renders the read-only card-detail panel for the `cardNum` query param.
 *
 * @returns The card-detail content element.
 */
function CardsViewContent() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const cardNumber = searchParams.get('cardNum');

    const [cardData, setCardData] = useState<CardRead | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<
        ErrorResponse | string | unknown | null
    >(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);

    /**
     * Fetches the card for the current `cardNumber`. Guards against a missing
     * param, surfaces any failure through {@link ErrorAlert}, and always clears
     * the loading flag. Memoized on `cardNumber` for the effect below.
     */
    const LoadCard = useCallback(async (): Promise<void> => {
        if (!cardNumber) {
            setErrorState('No card number was provided.');
            setIsErrorOpen(true);
            return;
        }
        setIsLoading(true);
        setIsErrorOpen(false);
        try {
            const response = await CardsApi.GetCard(cardNumber);
            setCardData(response);
        } catch (fetchError: unknown) {
            setErrorState(fetchError);
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [cardNumber]);

    useEffect(() => {
        LoadCard();
    }, [LoadCard]);

    /** Navigates to the card-update screen for the current card (Enter/Edit). */
    const HandleEdit = (): void => {
        if (cardNumber) {
            const target =
                `${CARD_UPDATE_ROUTE}?cardNum=${encodeURIComponent(cardNumber)}`;
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

    return (
        <Box sx={{ p: 2 }}>
            <Typography variant="h5" sx={{ mb: 2 }}>
                View Credit Card Detail
            </Typography>

            {isLoading && !cardData ? (
                <Box sx={{ p: 2 }}>
                    <CircularProgress />
                </Box>
            ) : null}

            {cardData ? (
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
            ) : null}

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
