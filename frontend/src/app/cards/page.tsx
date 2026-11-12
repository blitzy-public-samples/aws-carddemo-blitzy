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
 * limit and the two filters. It is a browse-only grid: because `card_num` is
 * returned MASKED (AAP 0.7.8), a list row carries no full PAN and therefore
 * cannot key a card-detail navigation. Direct access to a card's detail/update
 * is by the card-number picker on those screens — faithful to the legacy
 * COCRDSL/COCRDUP `CARDSID` input the operator types — over the frozen
 * GET/PUT /cards/{cardNum} contract (AAP 0.5.5). The earlier row-click that
 * navigated by owning-account id was removed with its by-account backend routes,
 * which resolved an account to one card with `.limit(1)` and silently selected
 * the wrong card on the NONUNIQUE account->card relationship (QA C07/C08).
 * PF7/PF8 paging is handled by the shared DataTable component (PageUp/PageDown),
 * so this page supplies the columns, the current page envelope, and the
 * page-change callback.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useRouter } from 'next/navigation';

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Alert from '@mui/material/Alert';

import { DEFAULT_PAGE_SIZE } from '@/types';
import type {
    CardListParams,
    CardSummary,
    PaginatedResponse,
    ErrorResponse,
} from '@/types';
import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { ErrorAlert, NormalizeError } from '@/components/ErrorAlert';
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

/**
 * Detail-drill target for a card row (QA I25). A card row navigates to the
 * account-view screen keyed by the row's OPAQUE `acct_id` -- never by the card
 * number. `card_num` is a sensitive PAN (rendered masked), so it must not
 * appear in a URL; `acct_id` is a non-sensitive business key and the account
 * view surfaces the owning account's detail. The `?acctId=` query param matches
 * the account-view page's reader.
 */
const CARD_DETAIL_ROUTE = '/accounts/view';
const CARD_DETAIL_QUERY_PARAM = 'acctId';

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

/**
 * Persistent message shown in place of the loading indicator when a card-list
 * load fails and there is no data to display (QA Issue 11). Unlike the transient
 * ErrorAlert toast (which auto-hides after a few seconds and would leave the
 * spinner behind), this inline panel stays until the user retries, so the page
 * can never sit on an endless spinner after a failed/timed-out request.
 */
const LIST_LOAD_FAILED_MESSAGE =
    'Unable to load cards. Please check your connection and try again.';

/** Label of the inline retry action rendered beside {@link LIST_LOAD_FAILED_MESSAGE}. */
const RETRY_LABEL = 'Retry';

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
    // QA Issue 11: tracks whether the LAST load failed with no data to show, so
    // the render can replace the (otherwise endless) spinner with a persistent
    // retry panel. Reset to false at the start of every load and set true only in
    // the failure path (guarded by the request-generation check below).
    const [loadFailed, setLoadFailed] = useState<boolean>(false);
    const [accountIdFilter, setAccountIdFilter] = useState<string>('');
    const [cardIdFilter, setCardIdFilter] = useState<string>('');

    // Live mirrors of the two filter values (QA C3). `LoadCards` is intentionally
    // memoized on [pageNumber] only, so it must NOT close over the filter STATE
    // (that would either send a stale value or force a refetch on every
    // keystroke). Reading the current filter from a ref lets the stable callback
    // see the latest value without becoming a dependency. The refs are updated
    // synchronously in the same handlers that update the state, so they are
    // always current -- including on the same-tick Search/Clear refetch paths.
    const accountIdFilterRef = useRef<string>('');
    const cardIdFilterRef = useRef<string>('');

    // Monotonic request-generation counter (QA M-05). Every LoadCards
    // invocation -- from the mount/page effect, Search or Clear -- claims the
    // next generation and captures it locally; only the request whose captured
    // generation still equals `requestGenerationRef.current` when it settles may
    // commit state. A superseded (stale) response, or any response that resolves
    // after this page has unmounted, is DISCARDED, so a slow earlier request can
    // never overwrite a newer one's data nor flip the shared loading flag, and no
    // state update ever lands on an unmounted component.
    const requestGenerationRef = useRef<number>(0);

    /**
     * Fetches the current page of cards, capped at ROWS_PER_PAGE (F-004). On
     * failure the caught value is routed to the ErrorAlert, which performs the
     * specific narrowing (ApiError / ErrorResponse / string); this handler never
     * swallows the error nor logs it silently. A request-generation guard
     * (QA M-05) discards the result of any superseded or post-unmount request.
     */
    const LoadCards = useCallback(async (): Promise<void> => {
        const requestGeneration = requestGenerationRef.current + 1;
        requestGenerationRef.current = requestGeneration;
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        setLoadFailed(false);
        try {
            // Read the CURRENT filter values from the refs (see the ref
            // declarations above) so the search boxes actually narrow the browse
            // (QA C3). Blank values are trimmed away by the apiClient query
            // builder, so an untouched search box sends no filter.
            const params: Partial<CardListParams> = {
                page: pageNumber,
                page_size: ROWS_PER_PAGE,
                acct_id: accountIdFilterRef.current,
                card_num: cardIdFilterRef.current,
            };
            const response = await CardsApi.ListCards(params);
            // Ignore a stale/superseded response (QA M-05): only the latest
            // request may commit its data.
            if (requestGenerationRef.current !== requestGeneration) {
                return;
            }
            setPageData(response);
        } catch (caughtError: unknown) {
            // Discard a stale request's error too, so a superseded failure does
            // not surface over a newer success (QA M-05).
            if (requestGenerationRef.current !== requestGeneration) {
                return;
            }
            setErrorState(caughtError);
            setIsErrorOpen(true);
            // QA Issue 11: record the failure so the render shows a persistent
            // retry panel instead of an endless spinner when no data is present.
            setLoadFailed(true);
        } finally {
            // Only the latest request owns the shared loading flag (QA M-05).
            if (requestGenerationRef.current === requestGeneration) {
                setIsLoading(false);
            }
        }
    }, [pageNumber]);

    // Fetch on mount and whenever the page number changes (LoadCards is memoized
    // on [pageNumber], so it is a stable, correct effect dependency). The cleanup
    // bumps the request generation so any request still in flight when the page
    // changes or this component unmounts is invalidated and cannot update state
    // afterwards (QA M-05).
    useEffect(() => {
        void LoadCards();
        return () => {
            requestGenerationRef.current += 1;
        };
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
     * Opens the detail drill-down for a selected card row (QA I25): the card
     * list was previously a dead, unclickable grid. Navigation is keyed by the
     * row's OPAQUE `acct_id` (never the masked PAN), routing to the account-view
     * screen for the owning account. Invoked by both mouse click and keyboard
     * (Enter/Space) via the shared DataTable's accessible row-selection support.
     *
     * @param row - The selected card summary row.
     */
    function HandleSelectCard(row: CardSummary): void {
        const target =
            `${CARD_DETAIL_ROUTE}?${CARD_DETAIL_QUERY_PARAM}=` +
            `${encodeURIComponent(row.acct_id)}`;
        router.push(target);
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
            // Keep the ref in lock-step with the state so the stable LoadCards
            // callback reads the latest value (QA C3).
            accountIdFilterRef.current = value;
        } else if (name === CARD_FILTER_NAME) {
            setCardIdFilter(value);
            cardIdFilterRef.current = value;
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
        // Clear the refs synchronously too: HandleClear may call LoadCards in
        // this SAME tick (page-1 path), before any state-sync effect could run,
        // so the refs -- not the pending state -- are what LoadCards will read
        // (QA C3). Without this the cleared search would still send the old
        // filter value.
        accountIdFilterRef.current = '';
        cardIdFilterRef.current = '';
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
                    onRowClick={HandleSelectCard}
                    getRowLabel={(row) =>
                        `View account ${row.acct_id} for card ` +
                        `${MaskCardNumber(row.card_num)}`
                    }
                    loading={isLoading}
                    emptyMessage={EMPTY_CARDS_MESSAGE}
                />
            ) : loadFailed ? (
                /*
                 * QA Issue 11: the initial load failed and there is no data to
                 * render. Show a PERSISTENT error with an inline Retry action
                 * rather than an endless spinner. This is the SOLE error surface
                 * for the no-data case (the ErrorAlert toast below is gated off
                 * while `pageData` is null) so the failure is not announced
                 * twice. The specific failure message is shown when available,
                 * falling back to a connectivity-oriented hint. Retry re-invokes
                 * LoadCards, which resets `loadFailed` and shows the spinner
                 * again while the new request is in flight.
                 */
                <Alert
                    severity="error"
                    action={
                        <Button
                            color="inherit"
                            size="small"
                            onClick={() => void LoadCards()}
                        >
                            {RETRY_LABEL}
                        </Button>
                    }
                >
                    {NormalizeError(errorState).message || LIST_LOAD_FAILED_MESSAGE}
                </Alert>
            ) : (
                <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
                    <CircularProgress aria-label="Loading cards" />
                </Box>
            )}

            {/*
             * The auto-hiding toast is the error surface ONLY when a table is
             * already on screen (a pagination/refresh failure): the table stays
             * put and the transient failure is announced briefly. When there is
             * no data, the persistent retry panel above is the sole surface, so
             * the toast is gated off to avoid announcing the same failure twice
             * (QA Issue 11).
             */}
            <ErrorAlert
                open={isErrorOpen && pageData !== null}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Box>
    );
}
