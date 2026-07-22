'use client';

/**
 * Cards List page — route /cards
 * Modernizes: BMS map COCRDLI (mapset CCRDLIA) · CICS Tx CCLI · COBOL COCRDLIC
 * Business rule F-004: EXACTLY 7 cards per browse page.
 * Security: card_num is displayed MASKED (last-4); the card security code is
 * NEVER present on any card type (it is absent from CardSummary by design).
 *
 * The legacy 3270 screen presented a fixed 7-row browse of credit cards with two
 * search keys (Account Number / Credit Card Number) and per-row select markers.
 * The modern redesign (Material Design 3 via Material UI) preserves the browse
 * limit and the two filters, and replaces the terminal CRDSEL selection markers
 * with row-click navigation to the card detail page. PF7/PF8 paging is handled by
 * the shared DataTable component (PageUp/PageDown), so this page only supplies the
 * columns, the current page envelope, and the page-change / row-click callbacks.
 */

import { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';

import { DEFAULT_PAGE_SIZE } from '@/types';
import type {
    CardSummary,
    PaginatedResponse,
    PaginationParams,
    ErrorResponse,
} from '@/types';
import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { CardsApi } from '@/lib/apiClient';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * Rows requested per browse page — the legacy F-004 limit (<= 7). Bound to the
 * shared DEFAULT_PAGE_SIZE (= 7) so the browse limit has a single source of
 * truth; never hardcode the literal 7 in the pagination request.
 */
const ROWS_PER_PAGE = DEFAULT_PAGE_SIZE;

/** FormField `name` for the account-number filter (legacy ACCTSID). */
const ACCOUNT_FILTER_NAME = 'accountIdFilter';

/** FormField `name` for the card-number filter (legacy CARDSID). */
const CARD_FILTER_NAME = 'cardIdFilter';

/** Account-number input max length — legacy ACCTSID PIC X(11). */
const ACCOUNT_FILTER_MAX_LENGTH = 11;

/** Card-number input max length — legacy CARDSID PIC X(16). */
const CARD_FILTER_MAX_LENGTH = 16;

/** Active-status code that marks a card active — legacy CRDSTS 'Y'. */
const ACTIVE_STATUS_CODE = 'Y';

/** Human-readable labels for the raw single-character CRDSTS status code. */
const ACTIVE_STATUS_LABEL: Record<string, string> = {
    Y: 'Active',
    N: 'Inactive',
};

/** Number of trailing card-number digits left visible when masking. */
const VISIBLE_CARD_DIGITS = 4;

/** Character used to mask the leading portion of a card number. */
const CARD_MASK_CHARACTER = '*';

/** Empty-page message shown when a browse page contains no cards. */
const EMPTY_CARDS_MESSAGE = 'No cards found.';

/* ------------------------------------------------------------------------- */
/* Presentation helpers (small, PascalCase — Ochs naming rule).              */
/* ------------------------------------------------------------------------- */

/**
 * Masks a card number so only its last four characters remain visible, replacing
 * every earlier character with {@link CARD_MASK_CHARACTER}. This is a DEFENSIVE
 * measure: the backend already masks `card_num`, but this guarantees the UI never
 * renders a full PAN even if handed one, and it is idempotent on already-masked
 * input. Never unmasks and never surfaces the card security code (absent from
 * all card types by design).
 *
 * @param cardNumber - The (already masked) card number string from the backend.
 * @returns A masked representation exposing only the trailing four characters.
 */
function MaskCardNumber(cardNumber: string): string {
    const safeValue = cardNumber ?? '';
    if (safeValue.length <= VISIBLE_CARD_DIGITS) {
        return safeValue;
    }
    const lastFour = safeValue.slice(-VISIBLE_CARD_DIGITS);
    const maskedLength = safeValue.length - VISIBLE_CARD_DIGITS;
    const maskedPortion = CARD_MASK_CHARACTER.repeat(maskedLength);
    return `${maskedPortion}${lastFour}`;
}

/**
 * Resolves the human-readable label for a raw CRDSTS status code, falling back to
 * the raw value (or an empty string) when the code is unrecognized.
 *
 * @param activeStatus - The raw single-character status code ('Y' / 'N').
 * @returns The display label for the status.
 */
function ResolveStatusLabel(activeStatus: string): string {
    return ACTIVE_STATUS_LABEL[activeStatus] ?? activeStatus ?? '';
}

/**
 * Maps a raw status code to a semantic MUI palette color — `success` for an
 * active card and `default` otherwise. Uses theme palette roles only (zero
 * hardcoded color values, AAP §0.3.4).
 *
 * @param activeStatus - The raw single-character status code ('Y' / 'N').
 * @returns The semantic Chip color to apply.
 */
function ResolveStatusColor(activeStatus: string): 'success' | 'default' {
    if (activeStatus === ACTIVE_STATUS_CODE) {
        return 'success';
    }
    return 'default';
}

/* ------------------------------------------------------------------------- */
/* Column definitions for the shared DataTable<CardSummary>.                 */
/* Maps legacy COCRDLI row fields -> CardSummary. NO CRDSEL/checkbox column  */
/* (selection is modernized to row-click); no card security code column.     */
/* ------------------------------------------------------------------------- */

const columns: ColumnDef<CardSummary>[] = [
    {
        // Legacy ACCTNO PIC X(11) — a string id (leading zeros preserved).
        key: 'acct_id',
        header: 'Account ID',
    },
    {
        // Legacy CRDNUM PIC X(16) — rendered MASKED via Typography (never raw text).
        key: 'card_num',
        header: 'Card Number',
        render: (row) => (
            <Typography variant="body2" component="span">
                {MaskCardNumber(row.card_num)}
            </Typography>
        ),
    },
    {
        // Embossed name (name on card) — provided by CardSummary.
        key: 'embossed_name',
        header: 'Name on Card',
    },
    {
        // Legacy CRDSTS PIC X(1) — rendered as a semantic status Chip.
        key: 'active_status',
        header: 'Status',
        render: (row) => (
            <Chip
                label={ResolveStatusLabel(row.active_status)}
                color={ResolveStatusColor(row.active_status)}
                size="small"
            />
        ),
    },
];

/* ------------------------------------------------------------------------- */
/* Page component (default export — Next.js App Router requirement).         */
/* ------------------------------------------------------------------------- */

/**
 * Renders the paginated, filterable credit-card list (route /cards).
 *
 * @returns The Cards List page element.
 */
export default function CardsPage() {
    const router = useRouter();

    // State (camelCase variables). `pageData` is null until the first page
    // arrives, so the render tree guards against it before handing it to
    // DataTable (whose `data` prop is non-nullable).
    const [pageData, setPageData] =
        useState<PaginatedResponse<CardSummary> | null>(null);
    const [pageNumber, setPageNumber] = useState<number>(1);
    const [isLoading, setIsLoading] = useState<boolean>(true);
    const [errorState, setErrorState] =
        useState<ErrorResponse | string | unknown | null>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    const [accountIdFilter, setAccountIdFilter] = useState<string>('');
    const [cardIdFilter, setCardIdFilter] = useState<string>('');

    /**
     * Fetches the current page of cards, capped at ROWS_PER_PAGE (F-004). On
     * failure the caught value is routed to the ErrorAlert, which performs the
     * specific narrowing (ApiError / ErrorResponse / string); this handler never
     * swallows the error nor logs it silently.
     */
    const LoadCards = useCallback(async (): Promise<void> => {
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const params: Partial<PaginationParams> = {
                page: pageNumber,
                page_size: ROWS_PER_PAGE,
            };
            const response = await CardsApi.ListCards(params);
            setPageData(response);
        } catch (caughtError: unknown) {
            setErrorState(caughtError);
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [pageNumber]);

    // Fetch on mount and whenever the page number changes (LoadCards is memoized
    // on [pageNumber], so it is a stable, correct effect dependency).
    useEffect(() => {
        void LoadCards();
    }, [LoadCards]);

    /**
     * Relays a DataTable page change to state; the fetch effect reacts to it.
     *
     * @param page - The 1-based target page.
     */
    function HandlePageChange(page: number): void {
        setPageNumber(page);
    }

    /**
     * Navigates to the card detail page using the query-string contract shared
     * with the (static) /cards/view and /cards/update routes. `row.card_num` is
     * masked; it is passed as-is because backend GET /cards/{cardNum} owns
     * identifier resolution. Never unmasks; never references a security code.
     *
     * @param row - The clicked card row.
     */
    function HandleRowClick(row: CardSummary): void {
        const cardNumber = row.card_num;
        router.push(`/cards/view?cardNum=${encodeURIComponent(cardNumber)}`);
    }

    /**
     * Updates the matching filter value from a FormField change.
     *
     * @param name - The FormField `name` identifying which filter changed.
     * @param value - The new filter value.
     */
    function HandleFilterChange(name: string, value: string): void {
        if (name === ACCOUNT_FILTER_NAME) {
            setAccountIdFilter(value);
        } else if (name === CARD_FILTER_NAME) {
            setCardIdFilter(value);
        }
    }

    /**
     * Applies the current filters by returning to the first page. When already on
     * page 1 the fetch is triggered directly (setting the same page number would
     * not re-run the effect).
     */
    function HandleSearch(): void {
        if (pageNumber === 1) {
            void LoadCards();
        } else {
            setPageNumber(1);
        }
    }

    /**
     * Clears both filters and returns to the first page, reusing the same
     * page-1 refetch semantics as HandleSearch.
     */
    function HandleClear(): void {
        setAccountIdFilter('');
        setCardIdFilter('');
        if (pageNumber === 1) {
            void LoadCards();
        } else {
            setPageNumber(1);
        }
    }

    /** Dismisses the error alert. */
    function HandleErrorClose(): void {
        setIsErrorOpen(false);
    }

    return (
        <Box sx={{ p: 3 }}>
            <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
                Credit Cards
            </Typography>

            <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                sx={{ mb: 3, alignItems: 'flex-start' }}
            >
                <FormField
                    name={ACCOUNT_FILTER_NAME}
                    label="Account ID"
                    value={accountIdFilter}
                    onChange={HandleFilterChange}
                    maxLength={ACCOUNT_FILTER_MAX_LENGTH}
                />
                <FormField
                    name={CARD_FILTER_NAME}
                    label="Card Number"
                    value={cardIdFilter}
                    onChange={HandleFilterChange}
                    maxLength={CARD_FILTER_MAX_LENGTH}
                />
                <Button variant="contained" onClick={HandleSearch}>
                    Search
                </Button>
                <Button variant="outlined" onClick={HandleClear}>
                    Clear
                </Button>
            </Stack>

            {pageData ? (
                <DataTable<CardSummary>
                    columns={columns}
                    data={pageData}
                    onPageChange={HandlePageChange}
                    getRowKey={(row) => row.card_num}
                    onRowClick={HandleRowClick}
                    loading={isLoading}
                    emptyMessage={EMPTY_CARDS_MESSAGE}
                />
            ) : (
                <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
                    <CircularProgress aria-label="Loading cards" />
                </Box>
            )}

            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Box>
    );
}
