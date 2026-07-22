'use client';

/**
 * AccountsViewPage — read-only Account + Customer detail view (route
 * `/accounts/view`), the modern Next.js + Material UI replacement for the legacy
 * 3270 account-view screen.
 *
 * Traceability (Minimal Change Clause, AAP §0.8.1):
 *   - BMS map:       `COACTVW` (mapset `COACTVW`, map `CACTVWA`, title
 *                    "BMS MAP FOR ACCOUNT VIEWING") — `app/bms/COACTVW.bms`.
 *   - CICS tx:       `CAVW`.
 *   - COBOL program: `COACTVWC` — `app/cbl/COACTVWC.cbl` ("Accept and process
 *                    Account View request" / "Displaying details of given
 *                    Account").
 *   - Symbolic map:  field names/lengths taken from `app/cpy-bms/COACTVW.CPY`.
 *
 * This page is READ-ONLY: it fetches one account (with its owning customer) by
 * id and renders it with MUI `Card`/`CardHeader`/`CardContent` + `Typography`,
 * with account status shown as a `Chip`. There are intentionally no editable
 * inputs. It is the DEFAULT accounts route (the AppShell navigation points at
 * `/accounts/view`; there is deliberately no bare `/accounts` page).
 *
 * Security (AAP §0.7.8): the SSN is masked to its last four characters, monetary
 * amounts and identifiers are rendered as VERBATIM strings (never coerced to a
 * number — floating-point rounding is a compliance failure, §0.7.1), and `cvv`
 * is never present nor displayed.
 *
 * @packageDocumentation
 */

import { Suspense, useCallback, useEffect, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import {
    Box,
    Button,
    Card,
    CardContent,
    CardHeader,
    Chip,
    CircularProgress,
    Container,
    Grid,
    Stack,
    Typography,
} from '@mui/material';

import { AccountsApi, IsApiError } from '@/lib/apiClient';
import { ErrorAlert } from '@/components/ErrorAlert';
import { FormField } from '@/components/FormField';
import { FormatMoney } from '@/lib/format';
import type { AccountDetail, AccountRead, CustomerRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Query-string key carrying the 11-digit account id (`?acctId=00000000123`). */
const ACCT_ID_QUERY_PARAM = 'acctId';

/** Route path for this page; used to navigate the account-id picker. */
const ACCOUNTS_VIEW_PATH = '/accounts/view';

/** Page heading (legacy 3270 title "View Account", COACTVW). */
const PAGE_TITLE = 'View Account';

/** Label for the account-id picker input (BMS ACCTSID field). */
const ACCT_ID_PICKER_LABEL = 'Account Number';

/** Maximum account-id length; ACCT-ID PIC 9(11) -> VARCHAR(11). */
const ACCT_ID_MAX_LENGTH = 11;

/** Label for the picker's load button (maps the legacy ENTER lookup). */
const LOAD_BUTTON_LABEL = 'LOAD';

/** Number of trailing SSN characters left visible after masking. */
const SSN_VISIBLE_DIGITS = 4;

/** Masked prefix rendered ahead of an SSN's visible last digits. */
const SSN_MASK_PREFIX = '\u2022\u2022\u2022-\u2022\u2022-';

/** Prompt shown when no account id is supplied (mirrors the COBOL guard). */
const MISSING_ACCT_MESSAGE =
    'No account number provided. Select an account to view its details.';

/** Message shown when a completed fetch yields no account. */
const NOT_FOUND_MESSAGE = 'Account not found.';

/** Fallback error text used when a non-API error is caught. */
const GENERIC_LOAD_ERROR = 'Failed to load account details.';

/** Section header for the account panel (legacy title "View Account"). */
const CARD_TITLE_ACCOUNT = 'Account Details';

/** Section header for the customer panel (legacy title "Customer Details"). */
const CARD_TITLE_CUSTOMER = 'Customer Details';

/** Human-readable label for an active status flag. */
const STATUS_LABEL_ACTIVE = 'Active';

/** Human-readable label for an inactive status flag. */
const STATUS_LABEL_INACTIVE = 'Inactive';

/** Record value that denotes the "active" status flag (X(1) = 'Y'). */
const ACTIVE_STATUS_FLAG = 'Y';

/* ------------------------------------------------------------------------- */
/* Pure presentational helpers (module-level; no hooks, no component state).  */
/* ------------------------------------------------------------------------- */

/**
 * Masks a Social Security Number, revealing only its last
 * {@link SSN_VISIBLE_DIGITS} characters. The backend already masks the SSN, so
 * this is a defensive second layer — a full SSN is never rendered (AAP §0.7.8).
 *
 * @param ssn - The (already partially masked) SSN string from the API.
 * @returns The masked SSN (for example `•••-••-6789`), or `''` when absent.
 */
function MaskSsn(ssn: string): string {
    if (!ssn) {
        return '';
    }
    const lastDigits = ssn.slice(-SSN_VISIBLE_DIGITS);
    return `${SSN_MASK_PREFIX}${lastDigits}`;
}

/**
 * Wraps a label and its rendered content in a responsive grid cell (full width
 * on extra-small screens, half width from the small breakpoint up).
 *
 * @param label - The field caption; also used as the React list key.
 * @param content - The rendered value node (text, chip, ...).
 * @returns A keyed `Grid` cell element.
 */
function RenderFieldCell(label: string, content: ReactNode): ReactElement {
    return (
        <Grid key={label} size={{ xs: 12, sm: 6 }}>
            <Typography variant="caption" color="text.secondary" component="div">
                {label}
            </Typography>
            {content}
        </Grid>
    );
}

/**
 * Renders a read-only label/value pair as a grid cell. The value is displayed
 * with a `body1` `Typography` (never an input — this screen is read-only).
 *
 * @param label - The field caption.
 * @param value - The already-formatted display string.
 * @returns A keyed `Grid` cell element.
 */
function RenderField(label: string, value: string): ReactElement {
    return RenderFieldCell(
        label,
        <Typography variant="body1" component="div">
            {value}
        </Typography>,
    );
}

/**
 * Renders the account status flag as a semantic MUI `Chip` (AAP §0.3.2). The
 * color resolves to a theme palette role, never a hardcoded value.
 *
 * @param status - The raw status flag (`'Y'` = active).
 * @returns A `Chip` labelled Active/Inactive with a semantic color.
 */
function RenderStatusChip(status: string): ReactElement {
    const isActive = status === ACTIVE_STATUS_FLAG;
    const chipLabel = isActive ? STATUS_LABEL_ACTIVE : STATUS_LABEL_INACTIVE;
    const chipColor: 'success' | 'default' = isActive ? 'success' : 'default';
    return <Chip label={chipLabel} color={chipColor} size="small" />;
}

/**
 * Builds the ordered list of account field cells (labels verbatim from
 * `COACTVW.bms`). All values are strings rendered verbatim; the status flag is
 * rendered as a `Chip`.
 *
 * @param account - The account read model.
 * @returns The account field cells in screen order.
 */
function BuildAccountCells(account: AccountRead): ReactElement[] {
    return [
        RenderField('Account Number', account.acct_id),
        RenderFieldCell('Status', RenderStatusChip(account.active_status)),
        RenderField('Opened', account.open_date),
        RenderField('Credit Limit', FormatMoney(account.credit_limit)),
        RenderField('Expiry', account.expiration_date),
        RenderField('Cash Credit Limit', FormatMoney(account.cash_credit_limit)),
        RenderField('Reissue', account.reissue_date),
        RenderField('Current Balance', FormatMoney(account.curr_bal)),
        RenderField('Current Cycle Credit', FormatMoney(account.curr_cyc_credit)),
        RenderField('Account Group', account.group_id),
        RenderField('Current Cycle Debit', FormatMoney(account.curr_cyc_debit)),
    ];
}

/**
 * Builds the ordered list of customer field cells (labels verbatim from
 * `COACTVW.bms`). SSN is masked; the FICO score (the only numeric field) is
 * stringified; optional name/address/phone lines render only when non-empty.
 * The legacy "City" screen field has no DTO counterpart and is intentionally
 * omitted (`CustomerRead` has no `addr_city`).
 *
 * @param customer - The customer read model.
 * @returns The customer field cells in screen order.
 */
function BuildCustomerCells(customer: CustomerRead): ReactElement[] {
    const cells: ReactElement[] = [
        RenderField('Customer Id', customer.cust_id),
        RenderField('SSN', MaskSsn(customer.ssn)),
        RenderField('Date of Birth', customer.date_of_birth),
        RenderField('FICO Score', String(customer.fico_credit_score)),
        RenderField('First Name', customer.first_name),
    ];
    if (customer.middle_name) {
        cells.push(RenderField('Middle Name', customer.middle_name));
    }
    cells.push(RenderField('Last Name', customer.last_name));
    cells.push(RenderField('Address', customer.addr_line_1));
    if (customer.addr_line_2) {
        cells.push(RenderField('Address 2', customer.addr_line_2));
    }
    if (customer.addr_line_3) {
        cells.push(RenderField('Address 3', customer.addr_line_3));
    }
    cells.push(RenderField('State', customer.addr_state_cd));
    cells.push(RenderField('Zip', customer.addr_zip));
    cells.push(RenderField('Country', customer.addr_country_cd));
    cells.push(RenderField('Phone 1', customer.phone_num_1));
    if (customer.phone_num_2) {
        cells.push(RenderField('Phone 2', customer.phone_num_2));
    }
    cells.push(RenderField('Government Issued Id', customer.govt_issued_id));
    cells.push(RenderField('EFT Account Id', customer.eft_account_id));
    cells.push(RenderField('Primary Card Holder', customer.pri_card_holder_ind));
    return cells;
}

/**
 * Renders the read-only account detail card.
 *
 * @param account - The account read model.
 * @returns The account `Card` element.
 */
function RenderAccountCard(account: AccountRead): ReactElement {
    return (
        <Card>
            <CardHeader title={CARD_TITLE_ACCOUNT} />
            <CardContent>
                <Grid container spacing={2}>
                    {BuildAccountCells(account)}
                </Grid>
            </CardContent>
        </Card>
    );
}

/**
 * Renders the read-only customer detail card.
 *
 * @param customer - The customer read model.
 * @returns The customer `Card` element.
 */
function RenderCustomerCard(customer: CustomerRead): ReactElement {
    return (
        <Card>
            <CardHeader title={CARD_TITLE_CUSTOMER} />
            <CardContent>
                <Grid container spacing={2}>
                    {BuildCustomerCells(customer)}
                </Grid>
            </CardContent>
        </Card>
    );
}

/**
 * Centered loading indicator used both as the `Suspense` fallback and while the
 * account request is in flight.
 *
 * @returns A centered `CircularProgress` within a padded `Box`.
 */
function LoadingFallback(): ReactElement {
    return (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress aria-label="Loading account details" />
        </Box>
    );
}

/* ------------------------------------------------------------------------- */
/* Route components.                                                          */
/* ------------------------------------------------------------------------- */

/**
 * Inner content component. Reads the account id from the query string, fetches
 * the account (with its owning customer) via {@link AccountsApi.GetAccount}, and
 * renders the read-only detail cards. Isolated from {@link AccountsViewPage} so
 * the `useSearchParams` call lives inside the required Next.js `Suspense`
 * boundary.
 *
 * @returns The account-view page content.
 */
function AccountsViewContent(): ReactElement {
    const router = useRouter();
    const searchParams = useSearchParams();
    const acctId = searchParams.get(ACCT_ID_QUERY_PARAM) ?? '';

    const [account, setAccount] = useState<AccountDetail | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<unknown>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    // Controlled value of the account-id picker input. Seeded from (and kept in
    // sync with) the `?acctId=` query param so a bookmarked/loaded account shows
    // its id in the picker, while still allowing the user to type a new one.
    const [pickerValue, setPickerValue] = useState<string>(acctId);

    useEffect(() => {
        setPickerValue(acctId);
    }, [acctId]);

    /**
     * Loads the account by id. Guards against an empty id, sets loading state,
     * and handles failures SPECIFICALLY via {@link IsApiError} (never a blanket
     * catch that hides detail — Ochs error-handling rule). The caught error is
     * surfaced through {@link ErrorAlert}, not logged.
     */
    const HandleLoad = useCallback(async (): Promise<void> => {
        if (!acctId) {
            return;
        }
        setIsLoading(true);
        setErrorState(null);
        setIsErrorOpen(false);
        try {
            const detail = await AccountsApi.GetAccount(acctId);
            setAccount(detail);
        } catch (caughtError) {
            if (IsApiError(caughtError)) {
                setErrorState(caughtError);
            } else {
                setErrorState(GENERIC_LOAD_ERROR);
            }
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [acctId]);

    useEffect(() => {
        void HandleLoad();
    }, [HandleLoad]);

    /**
     * Dismisses the error alert. Declared as a named handler so the JSX stays
     * declarative and the intent is self-documenting.
     */
    const HandleErrorClose = (): void => {
        setIsErrorOpen(false);
    };

    /**
     * Updates the picker input value. Signature matches
     * {@link FormField}'s `onChange(name, value)` contract.
     *
     * @param _name - The originating field name (unused; single-field picker).
     * @param value - The new account-id input value.
     */
    const HandlePickerChange = (_name: string, value: string): void => {
        setPickerValue(value);
    };

    /**
     * Navigates to `/accounts/view?acctId=<entered id>`. The query-param change
     * re-drives {@link HandleLoad} through the existing `useSearchParams` effect,
     * so the URL stays shareable/bookmarkable. An empty entry is ignored (mirrors
     * the legacy empty-id guard) rather than clearing the current view.
     */
    const HandleLoadClick = (): void => {
        const trimmedId = pickerValue.trim();
        if (trimmedId === '') {
            return;
        }
        const target =
            `${ACCOUNTS_VIEW_PATH}?${ACCT_ID_QUERY_PARAM}=` +
            `${encodeURIComponent(trimmedId)}`;
        router.push(target);
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
    } else if (isLoading) {
        bodyContent = <LoadingFallback />;
    } else if (account) {
        bodyContent = (
            <Stack spacing={3}>
                {RenderAccountCard(account)}
                {account.customer ? RenderCustomerCard(account.customer) : null}
            </Stack>
        );
    } else {
        bodyContent = (
            <Typography variant="body1">{NOT_FOUND_MESSAGE}</Typography>
        );
    }

    return (
        <Container maxWidth="lg" sx={{ py: 3 }}>
            <Stack spacing={3}>
                <Typography variant="h5" component="h1">
                    {PAGE_TITLE}
                </Typography>
                <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ alignItems: { sm: 'flex-start' } }}
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
            </Stack>
            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Container>
    );
}

/**
 * Default route component for `/accounts/view`. Wraps
 * {@link AccountsViewContent} in a `Suspense` boundary because Next.js 16
 * requires any client component that calls `useSearchParams` to be
 * suspense-bounded; a statically rendered call otherwise fails the build.
 *
 * @returns The suspense-bounded account-view page.
 */
export default function AccountsViewPage(): ReactElement {
    return (
        <Suspense fallback={<LoadingFallback />}>
            <AccountsViewContent />
        </Suspense>
    );
}

