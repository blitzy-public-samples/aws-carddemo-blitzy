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
 * confirmation the request omits payment_amount, so the server pays the FULL
 * current balance (legacy COBIL00C sets TRAN-AMT = ACCT-CURR-BAL, balance -> 0).
 */

import { useState } from 'react';
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
    const [billPayInfo, setBillPayInfo] = useState<BillPayResponse | null>(null);
    const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
    const [errorOpen, setErrorOpen] = useState<boolean>(false);
    const [errorValue, setErrorValue] = useState<unknown>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(false);

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
    }

    /**
     * Looks up the account balance. The only client-side validation is the
     * non-empty account-id check (mirrors the COBIL00C empty-id edit); all
     * other conditions (account-not-found, nothing-to-pay) are returned by the
     * server and surfaced through ErrorAlert.
     */
    async function HandleLookup(): Promise<void> {
        if (accountId.trim() === '') {
            setErrorValue(EMPTY_ACCT_MESSAGE);
            setErrorOpen(true);
            return;
        }
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
            setErrorValue(EMPTY_ACCT_MESSAGE);
            setErrorOpen(true);
            return;
        }
        setConfirmOpen(true);
    }

    /**
     * Performs the payment on confirmation. Omitting payment_amount instructs
     * the server to pay the FULL balance (legacy TRAN-AMT = ACCT-CURR-BAL).
     */
    async function HandleConfirm(): Promise<void> {
        setIsLoading(true);
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
                    />
                    <Button
                        variant="outlined"
                        onClick={HandleLookup}
                        disabled={isLoading}
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
                        disabled={!billPayInfo || isLoading}
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
