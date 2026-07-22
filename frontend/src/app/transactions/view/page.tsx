'use client';
// TransactionsViewPage — legacy origin BMS map COTRN01 | CICS Tx CT01 | COBOL program COTRN01C.
// Modern Next.js App Router replacement for the legacy "View a Transaction from TRANSACT file"
// screen. Redesigned per Material Design 3 via MUI — this is NOT a 3270 terminal reproduction.
// Business rules (empty-Tran-ID check, auto-fetch on entry, date-only display, PF3/PF4/PF5
// navigation, PAN masking) are preserved 1:1 with COTRN01C for behavioral fidelity.

import { Suspense, useCallback, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

import Container from '@mui/material/Container';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Divider from '@mui/material/Divider';
import Card from '@mui/material/Card';
import CardHeader from '@mui/material/CardHeader';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';

import { TransactionsApi } from '@/lib/apiClient';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';

import type { TransactionRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Screen title — verbatim from COTRN01.bms ('View Transaction'). */
const PAGE_TITLE = 'View Transaction';

/** Empty-Tran-ID validation message — verbatim from COTRN01C.cbl [L147-150]. */
const TRAN_ID_EMPTY_ERROR = 'Tran ID can NOT be empty...';

/** Number of trailing card-number digits left visible when masking the PAN. */
const VISIBLE_CARD_DIGITS = 4;

/** Length of the date-only prefix (YYYY-MM-DD) sliced from a 26-char timestamp. */
const DATE_ONLY_LENGTH = 10;

/** Maximum accepted Tran ID length — COTRN01.CPY TRNIDINI PIC X(16). */
const TRAN_ID_MAX_LENGTH = 16;

/** Modern navigation target for PF3 (Back) and PF5 (Browse) — the list screen. */
const TRANSACTIONS_LIST_ROUTE = '/transactions';

/** URL search-param key carrying the selected Tran ID (FIXED cross-page contract). */
const TRAN_ID_QUERY_KEY = 'tranId';

/* ------------------------------------------------------------------------- */
/* Pure helpers (module scope — stable, side-effect free).                   */
/* ------------------------------------------------------------------------- */

/**
 * Masks a card number so only the last {@link VISIBLE_CARD_DIGITS} characters
 * remain visible (AAP §0.7.8 — the full PAN is never displayed). Short or empty
 * inputs are returned unchanged.
 *
 * @param cardNumber - The (already server-masked) card number string to mask.
 * @returns The masked representation, e.g. '************3456'.
 */
function MaskCardNumber(cardNumber: string): string {
    if (cardNumber.length <= VISIBLE_CARD_DIGITS) {
        return cardNumber;
    }
    const visible = cardNumber.slice(-VISIBLE_CARD_DIGITS);
    const maskedLength = cardNumber.length - VISIBLE_CARD_DIGITS;
    return `${'*'.repeat(maskedLength)}${visible}`;
}

/**
 * Reproduces the legacy detail mapping where the 26-char TRAN-ORIG-TS /
 * TRAN-PROC-TS values are moved into 10-char screen fields, truncating to the
 * `YYYY-MM-DD` date portion (COTRN01C detail mapping; COTRN01.CPY X(10) fields).
 *
 * @param timestamp - The full ISO timestamp string.
 * @returns The leading `YYYY-MM-DD` date, or the input if shorter than 10 chars.
 */
function FormatDateOnly(timestamp: string): string {
    if (timestamp.length < DATE_ONLY_LENGTH) {
        return timestamp;
    }
    return timestamp.slice(0, DATE_ONLY_LENGTH);
}

/* ------------------------------------------------------------------------- */
/* Presentational sub-components (MUI-only; zero hardcoded CSS).             */
/* ------------------------------------------------------------------------- */

/**
 * Props for {@link DetailRow}. Grouping every input into one object keeps the
 * parameter count at 1 (Ochs "max 4 parameters" rule).
 */
interface DetailRowProps {
    /** Field label, rendered verbatim from COTRN01.bms. */
    label: string;
    /** Field value, always a string (ids/amounts are never coerced to numbers). */
    value: string;
    /** Text alignment for the value column; defaults to 'left'. */
    align?: 'left' | 'right';
}

/**
 * Renders one labelled, read-only detail row as a label/value Typography pair.
 *
 * @param props - The {@link DetailRowProps} describing the row.
 * @returns The MUI element for the row.
 */
function DetailRow(props: DetailRowProps) {
    const valueAlign = props.align ?? 'left';
    return (
        <Box
            sx={{
                display: 'flex',
                flexDirection: { xs: 'column', sm: 'row' },
                justifyContent: 'space-between',
                gap: 1,
            }}
        >
            <Typography variant="subtitle2" color="text.secondary">
                {props.label}
            </Typography>
            <Typography
                variant="body1"
                align={valueAlign}
                sx={{ wordBreak: 'break-word' }}
            >
                {props.value}
            </Typography>
        </Box>
    );
}

/**
 * Centered loading indicator used both for the in-flight fetch state and as the
 * Suspense fallback while the search-param-reading content mounts.
 *
 * @returns A centered MUI CircularProgress.
 */
function LoadingFallback() {
    return (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress />
        </Box>
    );
}

/* ------------------------------------------------------------------------- */
/* Content component (all hooks, logic and rendering).                       */
/* ------------------------------------------------------------------------- */

/**
 * The transaction-detail screen body. It reads the `tranId` search param, auto-
 * fetches the record on entry (fidelity to COTRN01C CDEMO-CT01-TRN-SELECTED),
 * supports a manual Tran ID lookup, and renders the 13 read-only fields.
 *
 * @returns The page content element.
 */
function TransactionsViewContent() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const tranIdParam = searchParams.get(TRAN_ID_QUERY_KEY) ?? '';

    const [tranIdInput, setTranIdInput] = useState('');
    const [transactionDetail, setTransactionDetail] =
        useState<TransactionRead | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [tranIdError, setTranIdError] = useState('');
    const [errorState, setErrorState] = useState<unknown>(null);
    const [isErrorOpen, setIsErrorOpen] = useState(false);

    /**
     * Fetches one transaction by id. Enforces the legacy empty-Tran-ID edit
     * (COTRN01C [L147-150]) BEFORE calling the API; on failure the caught error
     * is surfaced via ErrorAlert (the backend conveys the 404 "Transaction ID
     * NOT found..." and lookup errors as a typed ApiError).
     *
     * @param tranId - The transaction id to look up.
     */
    const LoadTransaction = useCallback(async (tranId: string): Promise<void> => {
        if (tranId.length === 0) {
            setTranIdError(TRAN_ID_EMPTY_ERROR);
            return;
        }
        setTranIdError('');
        setIsLoading(true);
        try {
            const result = await TransactionsApi.GetTransaction(tranId);
            setTransactionDetail(result);
        } catch (error) {
            setTransactionDetail(null);
            setErrorState(error);
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, []);

    // Auto-fetch on entry when arriving with `?tranId=...` (COTRN01C [L103-108]).
    useEffect(() => {
        if (tranIdParam.length > 0) {
            setTranIdInput(tranIdParam);
            void LoadTransaction(tranIdParam);
        }
    }, [tranIdParam, LoadTransaction]);

    /** Manual "Fetch" button handler (ENTER key equivalent). */
    const HandleFetch = (): void => {
        void LoadTransaction(tranIdInput);
    };

    /** FormField onChange relay: `(name, value) => void`. */
    const HandleTranIdChange = (name: string, value: string): void => {
        setTranIdInput(value);
        setTranIdError('');
    };

    /** PF3 = Back: return to the transactions list. */
    const HandleBack = (): void => {
        router.push(TRANSACTIONS_LIST_ROUTE);
    };

    /** PF5 = Browse: navigate to the COTRN00C transaction list. */
    const HandleBrowse = (): void => {
        router.push(TRANSACTIONS_LIST_ROUTE);
    };

    /** PF4 = Clear: reproduce COTRN01C CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS. */
    const HandleClear = (): void => {
        setTranIdInput('');
        setTransactionDetail(null);
        setTranIdError('');
        setErrorState(null);
        setIsErrorOpen(false);
    };

    /** Dismisses the error alert. */
    const HandleErrorClose = (): void => {
        setIsErrorOpen(false);
    };

    return (
        <Container maxWidth="md">
            <Stack spacing={3} sx={{ py: 4 }}>
                <Typography variant="h4">{PAGE_TITLE}</Typography>

                <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ alignItems: 'flex-start' }}
                >
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                        <FormField
                            name={TRAN_ID_QUERY_KEY}
                            label="Tran ID"
                            value={tranIdInput}
                            onChange={HandleTranIdChange}
                            maxLength={TRAN_ID_MAX_LENGTH}
                            error={tranIdError.length > 0}
                            helperText={tranIdError}
                            autoFocus
                        />
                    </Box>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={HandleFetch}
                        disabled={isLoading}
                    >
                        Fetch
                    </Button>
                </Stack>

                {isLoading ? <LoadingFallback /> : null}

                {transactionDetail !== null ? (
                    <Card>
                        <CardHeader title="Transaction Detail" />
                        <CardContent>
                            <Stack spacing={1.5} divider={<Divider flexItem />}>
                                <DetailRow
                                    label="Transaction ID:"
                                    value={transactionDetail.tran_id}
                                />
                                <DetailRow
                                    label="Card Number:"
                                    value={MaskCardNumber(transactionDetail.card_num)}
                                />
                                <DetailRow
                                    label="Type CD:"
                                    value={transactionDetail.tran_type_cd}
                                />
                                <DetailRow
                                    label="Category CD:"
                                    value={transactionDetail.tran_cat_cd}
                                />
                                <DetailRow
                                    label="Source:"
                                    value={transactionDetail.tran_source}
                                />
                                <DetailRow
                                    label="Description:"
                                    value={transactionDetail.tran_desc}
                                />
                                <DetailRow
                                    label="Amount:"
                                    value={transactionDetail.tran_amt}
                                    align="right"
                                />
                                <DetailRow
                                    label="Orig Date:"
                                    value={FormatDateOnly(transactionDetail.orig_ts)}
                                />
                                <DetailRow
                                    label="Proc Date:"
                                    value={FormatDateOnly(transactionDetail.proc_ts)}
                                />
                                <DetailRow
                                    label="Merchant ID:"
                                    value={transactionDetail.merchant_id}
                                />
                                <DetailRow
                                    label="Merchant Name:"
                                    value={transactionDetail.merchant_name}
                                />
                                <DetailRow
                                    label="Merchant City:"
                                    value={transactionDetail.merchant_city}
                                />
                                <DetailRow
                                    label="Merchant Zip:"
                                    value={transactionDetail.merchant_zip}
                                />
                            </Stack>
                        </CardContent>
                    </Card>
                ) : null}

                <Stack direction="row" spacing={2}>
                    <Button variant="outlined" onClick={HandleBack}>
                        Back
                    </Button>
                    <Button variant="outlined" onClick={HandleClear}>
                        Clear
                    </Button>
                    <Button variant="text" onClick={HandleBrowse}>
                        Browse Transactions
                    </Button>
                </Stack>
            </Stack>

            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Container>
    );
}

/* ------------------------------------------------------------------------- */
/* Page default export (Suspense boundary for useSearchParams).              */
/* ------------------------------------------------------------------------- */

/**
 * Next.js page entry for `/transactions/view`. `useSearchParams()` MUST run
 * inside a Suspense boundary or `next build` fails, so the search-param-reading
 * content is isolated in {@link TransactionsViewContent} behind `<Suspense>`.
 *
 * @returns The Suspense-wrapped transaction-detail page.
 */
export default function TransactionsViewPage() {
    return (
        <Suspense fallback={<LoadingFallback />}>
            <TransactionsViewContent />
        </Suspense>
    );
}
