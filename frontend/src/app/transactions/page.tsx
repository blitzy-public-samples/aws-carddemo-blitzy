'use client';

/*
 * TransactionsPage — Transactions List screen.
 * Legacy origin: BMS map COTRN00 (mapset COTRN00) | CICS Tx CT00 | COBOL program COTRN00C.
 * Modern redesign (Material Design 3 / MUI): paginated DataTable with server-driven
 * pagination (page size = 7), row click opens the transaction detail view.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useRouter } from 'next/navigation';

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Container from '@mui/material/Container';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';

import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { TransactionsApi } from '@/lib/apiClient';
import { FormatMoney } from '@/lib/format';
import { DEFAULT_PAGE_SIZE } from '@/types';
import type { TransactionSummary, PaginatedResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Page heading, taken verbatim from the legacy COTRN00 map title. */
const PAGE_TITLE = 'List Transactions';

/** Body text shown by the grid when a page contains zero transactions. */
const EMPTY_TRANSACTIONS_MESSAGE = 'No transactions found.';

/** Inline validation message when Search Tran ID is not numeric (COTRN00C). */
const TRAN_ID_NUMERIC_ERROR = 'Tran ID must be Numeric.';

/** Trailing card-number characters left visible when masking (AAP §0.7.8). */
const VISIBLE_CARD_DIGITS = 4;

/** Maximum accepted length of a transaction id (CVTRA05Y TRAN-ID PIC X(16)). */
const TRAN_ID_MAX_LENGTH = 16;

/** Route of the transaction detail view; carries the selected id as `tranId`. */
const TRANSACTION_VIEW_ROUTE = '/transactions/view';

/** Regular expression matching an all-numeric transaction id. */
const NUMERIC_PATTERN = /^\d+$/;

/* ------------------------------------------------------------------------- */
/* Pure helpers (module scope: stable references, no component state).        */
/* ------------------------------------------------------------------------- */

/**
 * Masks a card number so only the last {@link VISIBLE_CARD_DIGITS} characters
 * remain visible, replacing every preceding character with '*'. The function is
 * idempotent (re-masking an already-masked value yields the same string) and is
 * safe for empty or short inputs (returned unchanged at/below the visible
 * length). The backend already masks `card_num`; this masks defensively too.
 *
 * @param cardNumber - The (possibly already-masked) card number string.
 * @returns The masked card number showing only the trailing visible digits.
 */
function MaskCardNumber(cardNumber: string): string {
    if (!cardNumber) {
        return '';
    }
    if (cardNumber.length <= VISIBLE_CARD_DIGITS) {
        return cardNumber;
    }
    const maskedLength = cardNumber.length - VISIBLE_CARD_DIGITS;
    const visibleDigits = cardNumber.slice(maskedLength);
    return `${'*'.repeat(maskedLength)}${visibleDigits}`;
}

/**
 * Returns the date portion (leading 10 chars, YYYY-MM-DD) of an ISO timestamp,
 * guarding against empty/short values. Mirrors the legacy list Date column,
 * which showed a date derived from TRAN-ORIG-TS.
 *
 * @param originTimestamp - The transaction origination timestamp string.
 * @returns The leading date portion, or an empty string when unavailable.
 */
function FormatTransactionDate(originTimestamp: string | null): string {
    if (!originTimestamp) {
        return '';
    }
    return originTimestamp.slice(0, 10);
}

/**
 * Column definitions for the transactions browse grid, built from the
 * TransactionSummary DTO fields (there is intentionally NO `tran_desc` on the
 * summary). The `card_num` column is masked for security; the `tran_amt` column
 * is right-aligned and rendered as the exact Decimal STRING verbatim (AAP
 * §0.7.1 — never parsed to a JavaScript number).
 */
const transactionColumns: ColumnDef<TransactionSummary>[] = [
    { key: 'tran_id', header: 'Transaction ID', align: 'left' },
    {
        key: 'orig_ts',
        header: 'Date',
        align: 'left',
        render: (row) => FormatTransactionDate(row.orig_ts),
    },
    {
        key: 'card_num',
        header: 'Card Number',
        align: 'left',
        render: (row) => MaskCardNumber(row.card_num),
    },
    { key: 'tran_type_cd', header: 'Type', align: 'left' },
    { key: 'tran_cat_cd', header: 'Category', align: 'left' },
    { key: 'tran_source', header: 'Source', align: 'left' },
    {
        key: 'tran_amt',
        header: 'Amount',
        align: 'right',
        // Route through the shared money formatter so the transaction list shows
        // the same "$" currency presentation as accounts-view / billpay (QA
        // Issue 1). FormatMoney only prepends the symbol; it never coerces the
        // exact Decimal string to a number (AAP §0.7.1).
        render: (row) => FormatMoney(row.tran_amt),
    },
];

/* ------------------------------------------------------------------------- */
/* Page component.                                                            */
/* ------------------------------------------------------------------------- */

/**
 * TransactionsPage — the `/transactions` list screen.
 *
 * Fetches one server-driven page of transactions (page size = 7) through
 * {@link TransactionsApi.ListTransactions} and renders them in the shared
 * {@link DataTable}. Selecting a row opens the transaction detail view; the
 * optional "Search Tran ID" field validates a numeric id (COTRN00C fidelity)
 * and jumps straight to that transaction's detail view.
 *
 * @returns The rendered transactions list page.
 */
export default function TransactionsPage() {
    const router = useRouter();

    const [pageNumber, setPageNumber] = useState<number>(1);
    const [transactionsData, setTransactionsData] =
        useState<PaginatedResponse<TransactionSummary> | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<unknown>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    const [searchTranId, setSearchTranId] = useState<string>('');
    const [searchError, setSearchError] = useState<string>('');

    // Monotonic request-generation counter (QA M-05). Each LoadTransactions call
    // claims the next generation; only the request whose captured generation
    // still equals `requestGenerationRef.current` on settle may commit state, so
    // a slow earlier page can never overwrite a newer one and no state update
    // lands after unmount.
    const requestGenerationRef = useRef<number>(0);

    /**
     * Loads a single page of transactions from the backend. Failures are
     * captured into `errorState` and surfaced through {@link ErrorAlert} (which
     * maps posting codes 100-103/109) — never swallowed. A stable identity
     * (useCallback with no reactive deps) keeps the loading effect lint-clean. A
     * request-generation guard (QA M-05) discards the result of any superseded or
     * post-unmount request.
     *
     * @param targetPage - The 1-based page number to fetch.
     */
    const LoadTransactions = useCallback(
        async (targetPage: number): Promise<void> => {
            const requestGeneration = requestGenerationRef.current + 1;
            requestGenerationRef.current = requestGeneration;
            setIsLoading(true);
            setErrorState(null);
            try {
                const result = await TransactionsApi.ListTransactions({
                    page: targetPage,
                    page_size: DEFAULT_PAGE_SIZE,
                });
                // Only the latest request may commit its data (QA M-05).
                if (requestGenerationRef.current !== requestGeneration) {
                    return;
                }
                setTransactionsData(result);
            } catch (error) {
                // Discard a superseded request's error too (QA M-05).
                if (requestGenerationRef.current !== requestGeneration) {
                    return;
                }
                setErrorState(error);
                setIsErrorOpen(true);
            } finally {
                // Only the latest request owns the shared loading flag (QA M-05).
                if (requestGenerationRef.current === requestGeneration) {
                    setIsLoading(false);
                }
            }
        },
        [],
    );

    // Refetch whenever the target page changes (initial mount loads page 1). The
    // cleanup bumps the request generation so a request still in flight when the
    // page changes or this component unmounts is invalidated (QA M-05).
    useEffect(() => {
        void LoadTransactions(pageNumber);
        return () => {
            requestGenerationRef.current += 1;
        };
    }, [LoadTransactions, pageNumber]);

    /**
     * Relays a DataTable page change to state; the effect performs the refetch.
     * Preserves the legacy PF7/PF8 page-back/forward semantics (COTRN00C
     * PROCESS-PAGE-BACKWARD / PROCESS-PAGE-FORWARD) via the server
     * `has_previous` / `has_next` flags that {@link DataTable} consumes.
     *
     * @param nextPage - The 1-based page selected in the grid.
     */
    function HandlePageChange(nextPage: number): void {
        setPageNumber(nextPage);
    }

    /**
     * Opens the transaction detail view for the selected row — the modern
     * equivalent of the legacy "type S to select" → XCTL to COTRN01C. The child
     * `view` page reads the `tranId` search param.
     *
     * @param row - The transaction summary row that was clicked.
     */
    function HandleRowClick(row: TransactionSummary): void {
        const query = new URLSearchParams({ tranId: row.tran_id });
        router.push(`${TRANSACTION_VIEW_ROUTE}?${query.toString()}`);
    }

    /**
     * Updates the Search Tran ID value and clears any prior inline error.
     * Signature matches {@link FormField} `onChange(name, value)`.
     *
     * @param name - The originating field name (single-field form; unused).
     * @param value - The new field value.
     */
    function HandleSearchChange(name: string, value: string): void {
        setSearchTranId(value);
        setSearchError('');
    }

    /**
     * Validates the Search Tran ID entry (COTRN00C requires a numeric id) and,
     * on success, navigates directly to that transaction's detail view. An empty
     * entry is a no-op. The list API accepts only `{ page, page_size }`, so no
     * filter parameter is ever sent to `ListTransactions`.
     */
    function HandleSearch(): void {
        const trimmedValue = searchTranId.trim();
        if (trimmedValue.length === 0) {
            return;
        }
        if (!NUMERIC_PATTERN.test(trimmedValue)) {
            setSearchError(TRAN_ID_NUMERIC_ERROR);
            return;
        }
        const query = new URLSearchParams({ tranId: trimmedValue });
        router.push(`${TRANSACTION_VIEW_ROUTE}?${query.toString()}`);
    }

    /** Dismisses the error alert. */
    function HandleErrorClose(): void {
        setIsErrorOpen(false);
    }

    return (
        <Container maxWidth="lg" sx={{ py: 3 }}>
            <Stack spacing={3}>
                <Typography variant="h4" component="h1">
                    {PAGE_TITLE}
                </Typography>

                <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ alignItems: { xs: 'stretch', sm: 'flex-start' } }}
                >
                    <Box
                        sx={(theme) => ({
                            flexGrow: 1,
                            maxWidth: theme.spacing(50),
                        })}
                    >
                        <FormField
                            name="searchTranId"
                            label="Search Tran ID"
                            value={searchTranId}
                            onChange={HandleSearchChange}
                            type="text"
                            maxLength={TRAN_ID_MAX_LENGTH}
                            error={Boolean(searchError)}
                            helperText={searchError}
                        />
                    </Box>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={HandleSearch}
                    >
                        Search
                    </Button>
                </Stack>

                {transactionsData ? (
                    <DataTable<TransactionSummary>
                        columns={transactionColumns}
                        data={transactionsData}
                        onPageChange={HandlePageChange}
                        getRowKey={(row) => row.tran_id}
                        onRowClick={HandleRowClick}
                        emptyMessage={EMPTY_TRANSACTIONS_MESSAGE}
                        loading={isLoading}
                    />
                ) : (
                    <Box sx={{ textAlign: 'center', py: 4 }}>
                        <CircularProgress aria-label="Loading transactions" />
                    </Box>
                )}
            </Stack>

            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Container>
    );
}
