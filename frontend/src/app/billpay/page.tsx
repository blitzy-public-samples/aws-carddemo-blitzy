'use client';

/**
 * Bill Payment page (route: /billpay).
 *
 * Modern replacement for the legacy 3270 screen COBIL00
 * (CICS Tx CB00, COBOL program COBIL00C).
 * Origin: app/bms/COBIL00.bms + app/cpy-bms/COBIL00.CPY.
 *
 * Business rule F-006 (available_credit = credit_limit - curr_bal) is
 * enforced SERVER-SIDE. This page displays balance / available credit as
 * returned by the API and submits the payment; it performs no client-side
 * monetary computation. Monetary values are Decimal strings (never number).
 *
 * The two-step Look Up -> Pay Balance (Confirm) flow is the Material Design 3
 * redesign of the single 3270 screen's ENTER-then-confirm interaction. On
 * confirmation the request carries only acct_id + confirm, so the server pays
 * the FULL current balance (legacy COBIL00C sets TRAN-AMT = ACCT-CURR-BAL,
 * balance -> 0).
 */

import { useState, useRef } from 'react';
import {
    Box,
    Stack,
    Container,
    Typography,
    Button,
    Card,
    CardHeader,
    CardContent,
} from '@mui/material';

import { FormField } from '@/components/FormField';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ErrorAlert } from '@/components/ErrorAlert';
import { BillPayApi } from '@/lib/apiClient';
import { FormatMoney } from '@/lib/format';
import type { BillPayRequest, BillPayResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Maximum account-id length; from the BMS symbolic map ACTIDINI PIC X(11). */
const ACCOUNT_ID_MAX_LENGTH = 11;

/** Legacy CONFIRM='Y' value that authorizes the payment (COBIL00C). */
const CONFIRM_YES = 'Y';

/** Empty-account edit message, verbatim from COBIL00C (L161). */
const EMPTY_ACCT_MESSAGE = 'Acct ID can NOT be empty...';

/**
 * Field-level helper text shown beneath the Account ID input when the empty-id
 * edit fires. Kept intentionally distinct from the verbatim toast text
 * ({@link EMPTY_ACCT_MESSAGE}) so the field marking and the toast are two
 * separate channels; MUI wires it to `aria-invalid` + `aria-describedby`
 * (WCAG 3.3.1 / 4.1.2).
 */
const REQUIRED_ACCOUNT_HELPER = 'Account ID is required';

/** Page heading text. */
const PAGE_TITLE = 'Bill Payment';

/** Label for the account lookup button (maps the legacy ENTER lookup). */
const LOOKUP_BUTTON_LABEL = 'Look Up';

/** Label for the pay-balance action button. */
const PAY_BUTTON_LABEL = 'Pay Balance';

/** Title of the payment confirmation dialog. */
const CONFIRM_DIALOG_TITLE = 'Confirm Bill Payment';

/** Fallback success message when the API response omits `message`. */
const PAYMENT_SUCCESS_MESSAGE = 'Bill payment processed successfully.';

/**
 * Reports whether an account has a positive balance left to pay. The server
 * pays the FULL balance and only acts when ``curr_bal > 0`` (legacy COBIL00C:
 * a zero/credit balance is a no-op), so the Pay Balance control is meaningful
 * only for a positive balance. After a successful payment the response carries
 * the zeroed balance, so this returns ``false`` and the button disables itself
 * (QA I24: PAY must not stay enabled/stale after a payment). This is a display
 * gate, not a monetary computation, so a numeric parse is sufficient; the
 * authoritative amount and guard remain server-side.
 *
 * @param billPayInfo - The current bill-pay preflight/response snapshot.
 * @returns ``true`` when the current balance is strictly greater than zero.
 */
function HasPositiveBalance(billPayInfo: BillPayResponse): boolean {
    const currentBalance = Number.parseFloat(billPayInfo.curr_bal);
    return Number.isFinite(currentBalance) && currentBalance > 0;
}

/* ------------------------------------------------------------------------- */
/* Page component.                                                           */
/* ------------------------------------------------------------------------- */

/**
 * Renders the Bill Payment page content. This is a THIN CLIENT: it displays
 * the balance / credit limit / available credit exactly as returned by the API
 * and submits the payment. It performs NO client-side monetary computation
 * (F-006 is server-authoritative). The page is content-only — the shared
 * AppShell / providers are supplied by the root layout.
 *
 * @returns The bill-payment page content, rendered inside the shared AppShell.
 */
function BillPayPage() {
    const [accountId, setAccountId] = useState<string>('');
    const [accountIdError, setAccountIdError] = useState<string>('');
    const [billPayInfo, setBillPayInfo] = useState<BillPayResponse | null>(null);
    const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
    const [errorOpen, setErrorOpen] = useState<boolean>(false);
    const [errorValue, setErrorValue] = useState<unknown>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);

    // M6: synchronous in-flight guard for the payment POST. React state
    // (`isLoading`) updates asynchronously, so several same-tick clicks on the
    // confirm button can all pass the `disabled`/`loading` check before the
    // first re-render and each fire a POST. This ref flips synchronously on the
    // first invocation and blocks every re-entrant call until the request
    // settles, guaranteeing exactly one payment request per confirmation.
    const isPaymentInFlightRef = useRef<boolean>(false);

    /**
     * Updates the account id and clears any stale lookup result so a new id
     * never shows a previous account's balance. The signature matches
     * FormField.onChange(name, value).
     *
     * @param name - The originating field name (single-field page).
     * @param value - The new account-id input value.
     */
    function HandleAccountIdChange(name: string, value: string): void {
        setAccountId(value);
        setBillPayInfo(null);
        setSuccessMessage(null);
        // Editing the field clears its validation marking (aria-invalid +
        // helper) so it disappears as soon as the user starts correcting it.
        setAccountIdError('');
    }

    /**
     * Looks up the account balance. The only client-side validation is the
     * non-empty account-id check (mirrors the COBIL00C empty-id edit); all
     * other conditions (account-not-found, nothing-to-pay) are returned by the
     * server and surfaced through ErrorAlert.
     */
    async function HandleLookup(): Promise<void> {
        if (accountId.trim() === '') {
            // Mark the field (aria-invalid + aria-describedby via helperText) in
            // addition to raising the verbatim COBIL00C toast.
            setAccountIdError(REQUIRED_ACCOUNT_HELPER);
            setErrorValue(EMPTY_ACCT_MESSAGE);
            setErrorOpen(true);
            return;
        }
        setAccountIdError('');
        setIsLoading(true);
        setSuccessMessage(null);
        try {
            const info = await BillPayApi.GetBillPayInfo(accountId);
            setBillPayInfo(info);
        } catch (error) {
            setBillPayInfo(null);
            setErrorValue(error);
            setErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }

    /**
     * Opens the confirmation step (legacy "Do you want to pay your balance
     * now? (Y/N)"). Guards that an account has been looked up first.
     */
    function HandleSubmit(): void {
        if (accountId.trim() === '' || billPayInfo === null) {
            // Only the empty-id case marks the field; a missing lookup with a
            // non-empty id is not a field-value error.
            if (accountId.trim() === '') {
                setAccountIdError(REQUIRED_ACCOUNT_HELPER);
            }
            setErrorValue(EMPTY_ACCT_MESSAGE);
            setErrorOpen(true);
            return;
        }
        setConfirmOpen(true);
    }

    /**
     * Performs the payment on confirmation. The request carries only acct_id +
     * confirm; the server always pays the FULL balance (legacy TRAN-AMT =
     * ACCT-CURR-BAL).
     */
    async function HandleConfirm(): Promise<void> {
        // M6: block re-entrant submissions synchronously, before React can
        // re-render the loading/disabled state. The first click flips the ref
        // and proceeds; any same-tick or in-flight follow-up click returns here.
        if (isPaymentInFlightRef.current) {
            return;
        }
        isPaymentInFlightRef.current = true;
        setIsLoading(true);
        // M5: clear any prior success message on each new submit so a subsequent
        // failure (for example the zero-balance 422 on an immediate re-pay) never
        // renders its error next to a stale green success message.
        setSuccessMessage(null);
        const billPayRequest: BillPayRequest = {
            acct_id: accountId,
            confirm: CONFIRM_YES,
        };
        try {
            const result = await BillPayApi.PayBill(billPayRequest);
            setBillPayInfo(result);
            setSuccessMessage(result.message ?? PAYMENT_SUCCESS_MESSAGE);
            setConfirmOpen(false);
        } catch (error) {
            setConfirmOpen(false);
            setErrorValue(error);
            setErrorOpen(true);
        } finally {
            setIsLoading(false);
            isPaymentInFlightRef.current = false;
        }
    }

    /** Cancels the confirmation dialog (the legacy "N" path). */
    function HandleCancelConfirm(): void {
        setConfirmOpen(false);
    }

    /** Dismisses the error alert. */
    function HandleCloseError(): void {
        setErrorOpen(false);
    }

    return (
        <Container maxWidth="sm" sx={{ py: 4 }}>
            <Stack spacing={3}>
                <Typography variant="h4" component="h1">
                    {PAGE_TITLE}
                </Typography>

                <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                    <FormField
                        name="accountId"
                        label="Account ID"
                        value={accountId}
                        onChange={HandleAccountIdChange}
                        maxLength={ACCOUNT_ID_MAX_LENGTH}
                        required
                        autoFocus
                        error={Boolean(accountIdError)}
                        helperText={accountIdError || undefined}
                    />
                    <Button
                        variant="outlined"
                        onClick={HandleLookup}
                        disabled={isLoading}
                        // QA Area of Concern 2 (INFO): keep the short "Look Up"
                        // label on a single line at the 375px breakpoint. The
                        // narrow flex row previously allowed the label to wrap
                        // to two lines. `nowrap` is a CSS keyword (not a
                        // hardcoded dimension), so it stays Ochs-compliant.
                        sx={{ whiteSpace: 'nowrap' }}
                    >
                        {LOOKUP_BUTTON_LABEL}
                    </Button>
                </Stack>

                {billPayInfo ? (
                    <Card>
                        <CardHeader title="Account Balance" />
                        <CardContent>
                            <Stack spacing={1}>
                                <Typography variant="body1">
                                    Current Balance: {FormatMoney(billPayInfo.curr_bal)}
                                </Typography>
                                <Typography variant="body1">
                                    Credit Limit: {FormatMoney(billPayInfo.credit_limit)}
                                </Typography>
                                <Typography variant="body1">
                                    Available Credit: {FormatMoney(billPayInfo.available_credit)}
                                </Typography>
                            </Stack>
                        </CardContent>
                    </Card>
                ) : null}

                {successMessage ? (
                    <Typography variant="body1" sx={{ color: 'success.main' }}>
                        {successMessage}
                    </Typography>
                ) : null}

                <Box>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={HandleSubmit}
                        // QA I24: disable Pay Balance whenever there is nothing
                        // to pay -- no looked-up account, a request in flight,
                        // or a non-positive balance (including the zeroed
                        // balance returned right after a successful payment).
                        // This stops the control from staying enabled/stale
                        // after payment and inviting a pointless re-submit; a
                        // fresh Look Up of a positive-balance account re-enables
                        // it. The server remains the authoritative guard.
                        disabled={
                            !billPayInfo ||
                            isLoading ||
                            !HasPositiveBalance(billPayInfo)
                        }
                    >
                        {PAY_BUTTON_LABEL}
                    </Button>
                </Box>
            </Stack>

            <ConfirmDialog
                open={confirmOpen}
                title={CONFIRM_DIALOG_TITLE}
                message={
                    billPayInfo
                        ? `Do you want to pay your balance of ` +
                          `${FormatMoney(billPayInfo.curr_bal)} now?`
                        : 'Do you want to pay your balance now?'
                }
                confirmLabel={PAY_BUTTON_LABEL}
                confirmColor="primary"
                loading={isLoading}
                onConfirm={HandleConfirm}
                onCancel={HandleCancelConfirm}
            />

            <ErrorAlert
                open={errorOpen}
                error={errorValue}
                onClose={HandleCloseError}
            />
        </Container>
    );
}

export default BillPayPage;
