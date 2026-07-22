'use client';

/**
 * Account Update page (App Router route: `/accounts/update`).
 *
 * Traceability (Minimal Change Clause, AAP §0.8.1):
 *   - BMS map:          COACTUP  (app/bms/COACTUP.bms)
 *   - CICS transaction: CAUP
 *   - COBOL program:    COACTUPC (app/cbl/COACTUPC.cbl)
 *
 * Purpose: the modern Material-UI redesign of the legacy 3270 "Update Account"
 * screen. An operator keys an account id, loads the account plus its owning
 * customer, edits the ACCOUNT fields, and saves. The customer panel is shown
 * read-only for context (there is no customer-update endpoint on this screen).
 *
 * The legacy READ-UPDATE -> REWRITE optimistic-locking check
 * (COACTUPC `9700-CHECK-CHANGE-IN-REC` / `DATA-WAS-CHANGED-BEFORE-UPDATE`,
 * "Record changed by some one else. Please review") is preserved as an HTTP 409
 * conflict flow: a concurrent modification is surfaced with a dedicated
 * reload-and-retry message, distinct from ordinary validation / not-found errors.
 */

import { useState, useEffect, useCallback, Suspense } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';

import {
    Box,
    Stack,
    Container,
    Button,
    Typography,
    Card,
    CardContent,
    CardHeader,
    CircularProgress,
} from '@mui/material';

import { AccountsApi, IsApiError } from '@/lib/apiClient';
import type {
    AccountBeforeImage,
    AccountDetail,
    AccountUpdate,
    CustomerRead,
} from '@/types';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* Field lengths verified from app/cpy-bms/COACTUP.CPY (`<name>I PIC X(n)`). */
/* ------------------------------------------------------------------------- */

/** ACCTSID `PIC X(11)` — account key length. */
const ACCT_ID_LENGTH = 11;
/** ACSTTUS `PIC X(1)` — active status flag length. */
const STATUS_LENGTH = 1;
/** ACRDLIM / ACSHLIM / ACURBAL / ACRCYCR / ACRCYDB `PIC X(15)` — money width. */
const MONEY_LENGTH = 15;
/** AADDGRP `PIC X(10)` — account group id length. */
const GROUP_ID_LENGTH = 10;
/** ACSFNAM / ACSMNAM / ACSLNAM `PIC X(25)` — customer name width. */
const NAME_LENGTH = 25;
/** ACSADL1 / ACSADL2 `PIC X(50)` — address line width. */
const ADDRESS_LENGTH = 50;
/** ACSSTTE `PIC X(2)` — state code length. */
const STATE_LENGTH = 2;
/** ACSZIPC `PIC X(5)` — zip code length. */
const ZIP_LENGTH = 5;
/** ACSCTRY `PIC X(3)` — country code length. */
const COUNTRY_LENGTH = 3;
/** Masked SSN display width (e.g. `***-**-1234`). */
const SSN_DISPLAY_LENGTH = 11;
/** Combined phone display width (`(999)999-9999`). */
const PHONE_LENGTH = 15;
/** ACSGOVT `PIC X(20)` — government-issued id length. */
const GOVT_ID_LENGTH = 20;
/** ACSEFTC `PIC X(10)` — EFT account id length. */
const EFT_ID_LENGTH = 10;
/** ACSPFLG `PIC X(1)` — primary card-holder indicator length. */
const CARDHOLDER_IND_LENGTH = 1;
/** ACSTFCO `PIC X(3)` — FICO score display width. */
const FICO_LENGTH = 3;
/** Customer id `PIC 9(09)` — display width. */
const CUST_ID_LENGTH = 9;

/** Placeholder / helper text for signed 2-decimal money inputs. */
const MONEY_FORMAT_HINT = '-99999999.99';
/** HTTP 409 optimistic-lock message (modern rendering of COACTUPC L521-522). */
const CONFLICT_MESSAGE = 'Record changed by another user; reload and retry.';
/** Success banner text (modern rendering of COACTUPC L475). */
const SAVE_SUCCESS_MESSAGE = 'Changes committed successfully.';
/** Guard message when the account key is blank (COACTUPC mandatory-id edit). */
const MISSING_ACCT_ID_MESSAGE = 'Account ID must be supplied.';
/** Sibling read-only view — the back / cancel destination. */
const ACCOUNT_VIEW_ROUTE = '/accounts/view';
/** Named HTTP conflict status (no magic number). */
const HTTP_CONFLICT = 409;

/** Y/N options for `active_status` (COACTUPC `1220-EDIT-YESNO` requires Y or N). */
const STATUS_OPTIONS = [
    { value: 'Y', label: 'Active (Y)' },
    { value: 'N', label: 'Inactive (N)' },
];

/**
 * Snapshots the optimistic-lock before-image from a fetched `AccountDetail`.
 *
 * Captures the editable-field values exactly as the operator last read them
 * (pre-edit), which the backend re-reads under `SELECT ... FOR UPDATE` and
 * compares field-for-field to detect a concurrent modification (COACTUPC
 * `9700-CHECK-CHANGE-IN-REC`, AAP §0.7.4). Building it from `detail` (never from
 * the live `formValues`) is what makes the check meaningful: it is the image the
 * client fetched, not the image the client is about to write. Monetary fields
 * stay Decimal strings and dates stay ISO strings, so an echoed `"194.00"`
 * compares equal to the stored `NUMERIC(12,2)` value.
 *
 * @param detail - The account record from `GET /accounts/{acctId}`.
 * @returns The before-image echo for the optimistic-lock compare.
 */
function BuildBeforeImage(detail: AccountDetail): AccountBeforeImage {
    return {
        active_status: detail.active_status,
        curr_bal: detail.curr_bal,
        credit_limit: detail.credit_limit,
        cash_credit_limit: detail.cash_credit_limit,
        curr_cyc_credit: detail.curr_cyc_credit,
        curr_cyc_debit: detail.curr_cyc_debit,
        expiration_date: detail.expiration_date,
        reissue_date: detail.reissue_date,
        group_id: detail.group_id,
    };
}

/**
 * Builds the editable `AccountUpdate` payload from a fetched `AccountDetail`.
 *
 * Includes the editable keys the backend `AccountUpdate` schema accepts plus the
 * REQUIRED `before_image` optimistic-lock token (QA finding C2: the backend
 * declares `before_image` mandatory, so a payload that omits it fails every save
 * with HTTP 422). `open_date` is immutable account metadata (shown read-only),
 * and the customer `addr_zip` belongs to the read-only customer panel; both are
 * intentionally excluded so PUT /accounts/{acctId} does not reject the request
 * with HTTP 422 (extra_forbidden), which the legacy COACTUP screen never did.
 *
 * The `before_image` is seeded from the same fetched `detail` and is a control
 * field, not an edited value: `HandleChange` only ever writes the top-level
 * editable keys (`active_status`, `credit_limit`, ...), never `before_image`, so
 * the pre-edit snapshot rides through every edit untouched until the next
 * successful load/save re-seeds it from the server's response.
 *
 * @param detail - The account + customer record from `GET /accounts/{acctId}`.
 * @returns The initial editable account payload, including `before_image`.
 */
function BuildInitialUpdate(detail: AccountDetail): AccountUpdate {
    return {
        before_image: BuildBeforeImage(detail),
        active_status: detail.active_status,
        credit_limit: detail.credit_limit,
        cash_credit_limit: detail.cash_credit_limit,
        curr_bal: detail.curr_bal,
        curr_cyc_credit: detail.curr_cyc_credit,
        curr_cyc_debit: detail.curr_cyc_debit,
        expiration_date: detail.expiration_date,
        reissue_date: detail.reissue_date,
        group_id: detail.group_id,
    };
}

/**
 * Account Update inner content — owns all state, data flow, and JSX.
 *
 * Separated from the default export so `useSearchParams` runs inside a
 * `<Suspense>` boundary, as required by the Next.js 16 App Router (a bare
 * `useSearchParams` call triggers a client-side-rendering bailout that would
 * otherwise fail `next build`).
 *
 * Client component: it uses React state, event handlers, and client-side API
 * calls, so it must run in the browser (`'use client'`). Providers
 * (`ThemeProvider` / `CssBaseline` / `AppShell`) are supplied by the root
 * `src/app/layout.tsx`; this page never renders them.
 */
function AccountsUpdateContent() {
    const router = useRouter();
    const searchParams = useSearchParams();
    /**
     * Optional deep-link account id. When present (e.g. `/accounts/update?acctId=…`,
     * the destination of the read-only view's "Update" action), the account is
     * loaded automatically on mount, mirroring `/cards/update`.
     */
    const initialAcctId = searchParams.get('acctId') ?? '';

    const [acctId, setAcctId] = useState(initialAcctId);
    const [accountDetail, setAccountDetail] = useState<AccountDetail | null>(null);
    const [formValues, setFormValues] = useState<AccountUpdate | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [isSaving, setIsSaving] = useState(false);
    const [isLoaded, setIsLoaded] = useState(false);
    const [alertOpen, setAlertOpen] = useState(false);
    const [alertError, setAlertError] = useState<unknown>(null);
    const [infoMessage, setInfoMessage] = useState('');

    /**
     * Surfaces an error through the (error-severity) `ErrorAlert`. The caught
     * value is passed straight through so `ErrorAlert` can normalize its
     * `message` / `code` / `detail`; it is never logged here (Ochs security rule).
     *
     * @param error - The caught value (an `ApiError`, string, or unknown).
     */
    function ShowError(error: unknown): void {
        setAlertError(error);
        setAlertOpen(true);
    }

    /** Dismisses the error alert. */
    function HandleAlertClose(): void {
        setAlertOpen(false);
    }

    /** Dismisses the success banner. */
    function HandleInfoClose(): void {
        setInfoMessage('');
    }

    /**
     * Relays edits from the account-key field. `FormField` calls
     * `onChange(name, value)`; only the value is needed for the singular key.
     *
     * @param _name - The field name (unused; there is a single key field).
     * @param value - The new account-id string.
     */
    function HandleAcctIdChange(_name: string, value: string): void {
        setAcctId(value);
    }

    /**
     * Relays an editable account-field edit into `formValues` immutably. This is
     * exactly the `(name, value)` shape `FormField` dictates, which keeps the
     * handler within the Ochs ≤4-parameter rule.
     *
     * @param name - The `AccountUpdate` key being edited.
     * @param value - The new string value (money / ids stay strings).
     */
    function HandleChange(name: string, value: string): void {
        setFormValues((prev) => {
            if (!prev) {
                return prev;
            }
            return { ...prev, [name]: value };
        });
    }

    /**
     * No-op change handler for the read-only customer panel. The customer fields
     * are display-only (there is no customer-update endpoint), so their edits are
     * never accepted and must never mutate the account payload.
     *
     * @param _name - Unused field name.
     * @param _value - Unused field value.
     */
    function HandleReadOnlyChange(_name: string, _value: string): void {
        // Intentionally empty: read-only fields do not mutate any form state.
    }

    /**
     * Loads the account plus its owning customer for the supplied account id and
     * seeds the editable form. Mirrors the legacy COACTUP account-id fetch.
     *
     * Wrapped in `useCallback` with an empty dependency list (it references only
     * stable state setters and the module-level `BuildInitialUpdate`) so its
     * identity is stable across renders; this lets the auto-load effect below key
     * on it without re-running on every render. Error surfacing is inlined here
     * (rather than via `ShowError`) precisely to keep that dependency list empty.
     *
     * @param rawId - The account id to load (from the key field or `?acctId=`).
     */
    const LoadAccount = useCallback(async (rawId: string): Promise<void> => {
        const trimmedId = rawId.trim();
        if (trimmedId === '') {
            setAlertError(MISSING_ACCT_ID_MESSAGE);
            setAlertOpen(true);
            return;
        }
        setIsLoading(true);
        try {
            const detail = await AccountsApi.GetAccount(trimmedId);
            setAccountDetail(detail);
            setFormValues(BuildInitialUpdate(detail));
            setIsLoaded(true);
            setInfoMessage('');
        } catch (err) {
            // Specific handling: render the ApiError (e.g. 404 -> not found) as-is
            // and keep formValues / isLoaded unchanged so the operator can retry.
            setAlertError(err);
            setAlertOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, []);

    /**
     * Auto-loads the account when the page is reached with an `?acctId=` query
     * parameter (the read-only view's "Update" deep link). Keyed on the stable
     * `LoadAccount` and the query value, so it runs once per distinct account id
     * and never loops. Manual entry (no query param) leaves this a no-op.
     */
    useEffect(() => {
        if (initialAcctId.trim() !== '') {
            void LoadAccount(initialAcctId);
        }
    }, [initialAcctId, LoadAccount]);

    /**
     * Loads the account currently keyed into the account-id field. Thin wrapper
     * over {@link LoadAccount} for the Load button and the Enter shortcut.
     */
    function HandleLoad(): void {
        void LoadAccount(acctId);
    }

    /**
     * Saves the edited account. Preserves the legacy optimistic-lock semantics: a
     * concurrent modification returns HTTP 409 and is surfaced distinctly with a
     * reload-and-retry message (COACTUPC `DATA-WAS-CHANGED-BEFORE-UPDATE`).
     */
    async function HandleSubmit(): Promise<void> {
        if (!formValues || !isLoaded) {
            return;
        }
        setIsSaving(true);
        try {
            const updated = await AccountsApi.UpdateAccount(acctId.trim(), formValues);
            setAccountDetail(updated);
            setFormValues(BuildInitialUpdate(updated));
            setInfoMessage(SAVE_SUCCESS_MESSAGE);
        } catch (err) {
            if (IsApiError(err) && err.status === HTTP_CONFLICT) {
                // Optimistic-lock conflict -> dedicated reload-and-retry message,
                // then re-fetch so the operator reviews the latest values.
                setAlertError(CONFLICT_MESSAGE);
                setAlertOpen(true);
                await LoadAccount(acctId.trim());
                return;
            }
            // Any other error (400/422 validation, 404 not found, network) as-is.
            ShowError(err);
        } finally {
            setIsSaving(false);
        }
    }

    /**
     * Discards edits (legacy F12=Cancel): re-seeds the form from the last fetched
     * record when loaded, otherwise navigates to the sibling read-only view.
     */
    function HandleCancel(): void {
        if (accountDetail) {
            setFormValues(BuildInitialUpdate(accountDetail));
            setInfoMessage('');
            return;
        }
        router.push(ACCOUNT_VIEW_ROUTE);
    }

    /** Navigates back to the read-only account view (legacy F3=Exit). */
    function HandleBack(): void {
        router.push(ACCOUNT_VIEW_ROUTE);
    }

    /**
     * Keyboard shortcuts mirroring the legacy PF keys: Enter processes (Load when
     * not yet loaded, otherwise Save); Escape exits.
     *
     * @param key - The pressed key from the React keyboard event.
     */
    function HandleShortcut(key: string): void {
        if (key === 'Enter') {
            if (!isLoaded) {
                HandleLoad();
            } else {
                void HandleSubmit();
            }
            return;
        }
        if (key === 'Escape') {
            HandleBack();
        }
    }

    const customer: CustomerRead | undefined = accountDetail?.customer;

    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Box
                onKeyDown={(event) => {
                    HandleShortcut(event.key);
                }}
            >
                <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
                    Update Account
                </Typography>

                {/* Account key + Load ---------------------------------------- */}
                <Card sx={{ mb: 2 }}>
                    <CardContent>
                        <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2 }}>
                            <Box sx={{ flexGrow: 1 }}>
                                <FormField
                                    name="acct_id"
                                    label="Account Number"
                                    value={acctId}
                                    onChange={HandleAcctIdChange}
                                    maxLength={ACCT_ID_LENGTH}
                                    required
                                    readOnly={isLoaded}
                                    autoFocus
                                />
                            </Box>
                            <Button
                                variant="contained"
                                color="primary"
                                onClick={HandleLoad}
                                disabled={isLoading || isLoaded}
                            >
                                Load
                            </Button>
                        </Box>
                        {isLoading ? (
                            <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2 }}>
                                <CircularProgress />
                            </Box>
                        ) : null}
                    </CardContent>
                </Card>

                {/* Editable account section ---------------------------------- */}
                {isLoaded && formValues ? (
                    <Card sx={{ mb: 2 }}>
                        <CardHeader title="Account" />
                        <CardContent>
                            <Stack spacing={2}>
                                <FormField
                                    name="active_status"
                                    label="Active Y/N"
                                    type="select"
                                    value={formValues.active_status ?? ''}
                                    onChange={HandleChange}
                                    options={STATUS_OPTIONS}
                                    maxLength={STATUS_LENGTH}
                                    required
                                />
                                {/*
                                  * Opened date is immutable account metadata: shown
                                  * read-only from the fetched record and never part of
                                  * the mutable payload (the backend AccountUpdate schema
                                  * forbids `open_date`; sending it returns HTTP 422).
                                  */}
                                <FormField
                                    name="open_date"
                                    label="Opened"
                                    type="date"
                                    value={accountDetail?.open_date ?? ''}
                                    onChange={HandleReadOnlyChange}
                                    readOnly
                                />
                                <FormField
                                    name="credit_limit"
                                    label="Credit Limit"
                                    value={formValues.credit_limit ?? ''}
                                    onChange={HandleChange}
                                    maxLength={MONEY_LENGTH}
                                    placeholder={MONEY_FORMAT_HINT}
                                    required
                                />
                                <FormField
                                    name="expiration_date"
                                    label="Expiry"
                                    type="date"
                                    value={formValues.expiration_date ?? ''}
                                    onChange={HandleChange}
                                    required
                                />
                                <FormField
                                    name="cash_credit_limit"
                                    label="Cash credit Limit"
                                    value={formValues.cash_credit_limit ?? ''}
                                    onChange={HandleChange}
                                    maxLength={MONEY_LENGTH}
                                    placeholder={MONEY_FORMAT_HINT}
                                    required
                                />
                                <FormField
                                    name="reissue_date"
                                    label="Reissue"
                                    type="date"
                                    value={formValues.reissue_date ?? ''}
                                    onChange={HandleChange}
                                    required
                                />
                                <FormField
                                    name="curr_bal"
                                    label="Current Balance"
                                    value={formValues.curr_bal ?? ''}
                                    onChange={HandleChange}
                                    maxLength={MONEY_LENGTH}
                                    placeholder={MONEY_FORMAT_HINT}
                                />
                                <FormField
                                    name="curr_cyc_credit"
                                    label="Current Cycle Credit"
                                    value={formValues.curr_cyc_credit ?? ''}
                                    onChange={HandleChange}
                                    maxLength={MONEY_LENGTH}
                                    placeholder={MONEY_FORMAT_HINT}
                                />
                                <FormField
                                    name="curr_cyc_debit"
                                    label="Current Cycle Debit"
                                    value={formValues.curr_cyc_debit ?? ''}
                                    onChange={HandleChange}
                                    maxLength={MONEY_LENGTH}
                                    placeholder={MONEY_FORMAT_HINT}
                                />
                                <FormField
                                    name="group_id"
                                    label="Account Group"
                                    value={formValues.group_id ?? ''}
                                    onChange={HandleChange}
                                    maxLength={GROUP_ID_LENGTH}
                                    required
                                />
                            </Stack>
                        </CardContent>
                    </Card>
                ) : null}

                {/* Read-only customer context -------------------------------- */}
                {isLoaded && customer ? (
                    <Card sx={{ mb: 2 }}>
                        <CardHeader title="Customer Details" />
                        <CardContent>
                            <Stack spacing={2}>
                                <FormField
                                    name="cust_id"
                                    label="Customer id"
                                    value={customer.cust_id}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={CUST_ID_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="first_name"
                                    label="First Name"
                                    value={customer.first_name}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={NAME_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="middle_name"
                                    label="Middle Name"
                                    value={customer.middle_name}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={NAME_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="last_name"
                                    label="Last Name"
                                    value={customer.last_name}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={NAME_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_line_1"
                                    label="Address"
                                    value={customer.addr_line_1}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={ADDRESS_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_line_2"
                                    label="Address (line 2)"
                                    value={customer.addr_line_2}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={ADDRESS_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_line_3"
                                    label="Address (line 3)"
                                    value={customer.addr_line_3}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={ADDRESS_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_state_cd"
                                    label="State"
                                    value={customer.addr_state_cd}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={STATE_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_zip"
                                    label="Zip"
                                    value={customer.addr_zip}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={ZIP_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="addr_country_cd"
                                    label="Country"
                                    value={customer.addr_country_cd}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={COUNTRY_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="phone_num_1"
                                    label="Phone 1"
                                    value={customer.phone_num_1}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={PHONE_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="phone_num_2"
                                    label="Phone 2"
                                    value={customer.phone_num_2}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={PHONE_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="ssn"
                                    label="SSN"
                                    value={customer.ssn}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={SSN_DISPLAY_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="govt_issued_id"
                                    label="Government Issued Id Ref"
                                    value={customer.govt_issued_id}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={GOVT_ID_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="date_of_birth"
                                    label="Date of birth"
                                    type="date"
                                    value={customer.date_of_birth}
                                    onChange={HandleReadOnlyChange}
                                    readOnly
                                />
                                <FormField
                                    name="eft_account_id"
                                    label="EFT Account Id"
                                    value={customer.eft_account_id}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={EFT_ID_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="pri_card_holder_ind"
                                    label="Primary Card Holder Y/N"
                                    value={customer.pri_card_holder_ind}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={CARDHOLDER_IND_LENGTH}
                                    readOnly
                                />
                                <FormField
                                    name="fico_credit_score"
                                    label="FICO Score"
                                    value={String(customer.fico_credit_score ?? '')}
                                    onChange={HandleReadOnlyChange}
                                    maxLength={FICO_LENGTH}
                                    readOnly
                                />
                            </Stack>
                        </CardContent>
                    </Card>
                ) : null}

                {/* Action row — legacy PF keys (F5=Save / F12=Cancel / F3=Exit). */}
                <Box sx={{ display: 'flex', gap: 2, mt: 2 }}>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={() => {
                            void HandleSubmit();
                        }}
                        disabled={!isLoaded || isSaving}
                    >
                        {isSaving ? 'Saving…' : 'Save'}
                    </Button>
                    <Button
                        variant="outlined"
                        onClick={HandleCancel}
                        disabled={isSaving}
                    >
                        Cancel
                    </Button>
                    <Button
                        variant="text"
                        onClick={HandleBack}
                        disabled={isSaving}
                    >
                        Back
                    </Button>
                    {isSaving ? <CircularProgress size={24} /> : null}
                </Box>
            </Box>

            {/* Error + success feedback (MUI Snackbar + Alert via ErrorAlert). */}
            <ErrorAlert open={alertOpen} onClose={HandleAlertClose} error={alertError} />
            <ErrorAlert
                open={Boolean(infoMessage)}
                onClose={HandleInfoClose}
                error={infoMessage}
                severity="success"
            />
        </Container>
    );
}

/* ------------------------------------------------------------------------- */
/* Default export — thin Suspense wrapper (Next.js 16 App Router).           */
/* ------------------------------------------------------------------------- */

/**
 * Account Update page — default export for the `/accounts/update` route.
 *
 * Wraps {@link AccountsUpdateContent} in a `<Suspense>` boundary because
 * `useSearchParams` (used to support the `?acctId=` deep link) triggers a
 * client-side-rendering bailout that would otherwise fail `next build`.
 *
 * @returns The Suspense-wrapped account-update page.
 */
export default function AccountsUpdatePage() {
    return (
        <Suspense
            fallback={
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
                    <CircularProgress />
                </Box>
            }
        >
            <AccountsUpdateContent />
        </Suspense>
    );
}
