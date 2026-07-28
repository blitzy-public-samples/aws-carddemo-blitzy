'use client';

/*
 * TransactionsAddPage — Add Transaction screen.
 * Legacy origin: BMS map COTRN02 (mapset COTRN02) | CICS Tx CT02 | COBOL program COTRN02C.
 * Modern redesign (Material Design 3 / MUI): data-entry form with confirm-before-add;
 * client-side validation is UX-fidelity only — posting validation (codes 100-103/109)
 * is authoritative on the server and surfaced via ErrorAlert.
 */

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Grid from '@mui/material/Grid';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';

import { FormField } from '@/components/FormField';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ErrorAlert } from '@/components/ErrorAlert';
import { TransactionsApi } from '@/lib/apiClient';
import { FocusFirstInvalidField } from '@/lib/keyboard';

import type { TransactionCreate } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* Text is taken verbatim from COTRN02.bms / COTRN02C.cbl for fidelity.      */
/* ------------------------------------------------------------------------- */

/** Screen heading (BMS COTRN02 title line "Add Transaction"). */
const PAGE_TITLE = 'Add Transaction';

/**
 * Amount format hint shown beneath the amount field. Updated for the MD3
 * redesign (QA finding C4): shows a natural signed-decimal example instead of
 * the legacy fixed-width `-99999999.99` 3270 template, matching the relaxed
 * {@link AMOUNT_PATTERN} that accepts ergonomic input such as `10.00`.
 */
const AMOUNT_FORMAT_HINT = '(e.g. -12345.67)';

/** ISO date format hint shown beneath each date field (BMS row 15). */
const DATE_FORMAT_HINT = '(YYYY-MM-DD)';

/** Confirmation dialog heading (mirrors the screen title). */
const CONFIRM_TITLE = 'Add Transaction';

/** Confirmation dialog prompt (BMS row 21 confirm text). */
const CONFIRM_MESSAGE = 'You are about to add this transaction. Please confirm.';

/** Success banner prefix (COTRN02C "Transaction added successfully."). */
const SUCCESS_MESSAGE = 'Transaction added successfully.';

/* Validation messages — preserved EXACTLY from COTRN02C.cbl (AAP §0.8.1). */
const ACCOUNT_ID_NUMERIC_ERROR = 'Account ID must be Numeric...';
const CARD_NUMBER_NUMERIC_ERROR = 'Card Number must be Numeric...';
const ACCT_OR_CARD_REQUIRED_ERROR = 'Account or Card Number must be entered...';
const TYPE_CD_EMPTY_ERROR = 'Type CD can NOT be empty...';
const TYPE_CD_NUMERIC_ERROR = 'Type CD must be Numeric...';
const CATEGORY_CD_EMPTY_ERROR = 'Category CD can NOT be empty...';
const CATEGORY_CD_NUMERIC_ERROR = 'Category CD must be Numeric...';
const SOURCE_EMPTY_ERROR = 'Source can NOT be empty...';
const DESCRIPTION_EMPTY_ERROR = 'Description can NOT be empty...';
const AMOUNT_EMPTY_ERROR = 'Amount can NOT be empty...';
/*
 * Amount-format message updated for the MD3 redesign (QA finding C4): describes
 * the relaxed AMOUNT_PATTERN (signed, up to 9 digits, up to 2 decimals) so the
 * guidance matches what is actually accepted; the legacy fixed-width wording
 * "-99999999.99" would misdescribe the corrected, ergonomic input rule.
 */
const AMOUNT_FORMAT_ERROR =
    'Amount must be a number with up to 9 digits and up to 2 decimals (e.g. -12345.67).';
const ORIG_DATE_EMPTY_ERROR = 'Orig Date can NOT be empty...';
const ORIG_DATE_FORMAT_ERROR = 'Orig Date should be in format YYYY-MM-DD';
const PROC_DATE_EMPTY_ERROR = 'Proc Date can NOT be empty...';
const PROC_DATE_FORMAT_ERROR = 'Proc Date should be in format YYYY-MM-DD';
const MERCHANT_ID_EMPTY_ERROR = 'Merchant ID can NOT be empty...';
const MERCHANT_ID_NUMERIC_ERROR = 'Merchant ID must be Numeric...';
const MERCHANT_NAME_EMPTY_ERROR = 'Merchant Name can NOT be empty...';
const MERCHANT_CITY_EMPTY_ERROR = 'Merchant City can NOT be empty...';
const MERCHANT_ZIP_EMPTY_ERROR = 'Merchant Zip can NOT be empty...';

/**
 * Empty add-transaction form seed (all 13 TransactionCreate fields blank).
 * Reused by the initial state, the Clear (F4) action, and the post-success
 * reset so a fresh form is presented in every case.
 */
const EMPTY_TRANSACTION_FORM: TransactionCreate = {
    acct_id: '', card_num: '', tran_type_cd: '', tran_cat_cd: '',
    tran_source: '', tran_desc: '', tran_amt: '', merchant_id: '',
    merchant_name: '', merchant_city: '', merchant_zip: '',
    orig_ts: '', proc_ts: '',
};

/* Validation patterns (client-side UX fidelity only; server re-validates). */

/** Unsigned integer digits (COBOL IS NUMERIC edit). */
const NUMERIC_PATTERN = /^\d+$/;

/**
 * Signed money amount matching the TRAN-AMT data contract (PIC S9(09)V99):
 * an optional sign, 1-9 integer digits, and an optional 1-2 digit fraction —
 * e.g. `10.00`, `10`, `-500.25`, `+12.3` (QA finding C4).
 *
 * The legacy COTRN02C screen edit (COTRN02C.cbl L340-347) demanded the fixed
 * 12-column 3270 layout `sign + 8 digits + '.' + 2 digits` (so only
 * `-00000010.00` passed and a natural `10.00` was rejected). That fixed-width
 * requirement is a terminal-era artifact of the 3270 field, not a data rule:
 * the legacy itself parses the value with `FUNCTION NUMVAL-C` (lenient), and
 * the authoritative server edit (`decimal_utils.ToDecimal`, exact Decimal, no
 * sign required) accepts ergonomic input like `12.34`. Per the Material Design 3
 * redesign (AAP §0.3, Goal 3) this client check is UX fidelity only and must not
 * be STRICTER than the server; the PIC S9(09)V99 data contract (<=9 integer +
 * 2 fractional digits) is preserved exactly.
 */
const AMOUNT_PATTERN = /^[+-]?\d{1,9}(\.\d{1,2})?$/;

/** ISO calendar date format YYYY-MM-DD. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/* ------------------------------------------------------------------------- */
/* Field descriptors that drive the data-entry grid and the required/numeric */
/* validation loop (keeps the render and the validator small and DRY).       */
/* ------------------------------------------------------------------------- */

/** Describes one editable grid field (label + copybook length + hint). */
interface FieldDescriptor {
    name: keyof TransactionCreate;
    label: string;
    maxLength: number;
    hint?: string;
}

/**
 * The 11 grid fields below the account/card key row. Labels come from
 * COTRN02.bms; each maxLength is the symbolic-map buffer width from
 * COTRN02.CPY (merchant name/city use the tighter BMS widths 30/25).
 */
const GRID_FIELDS: readonly FieldDescriptor[] = [
    { name: 'tran_type_cd', label: 'Type CD:', maxLength: 2 },
    { name: 'tran_cat_cd', label: 'Category CD:', maxLength: 4 },
    { name: 'tran_source', label: 'Source:', maxLength: 10 },
    { name: 'tran_desc', label: 'Description:', maxLength: 60 },
    { name: 'tran_amt', label: 'Amount:', maxLength: 12, hint: AMOUNT_FORMAT_HINT },
    { name: 'orig_ts', label: 'Orig Date:', maxLength: 10, hint: DATE_FORMAT_HINT },
    { name: 'proc_ts', label: 'Proc Date:', maxLength: 10, hint: DATE_FORMAT_HINT },
    { name: 'merchant_id', label: 'Merchant ID:', maxLength: 9 },
    { name: 'merchant_name', label: 'Merchant Name:', maxLength: 30 },
    { name: 'merchant_city', label: 'Merchant City:', maxLength: 25 },
    { name: 'merchant_zip', label: 'Merchant Zip:', maxLength: 10 },
];

/**
 * Field names in on-screen order — the account/card key row followed by the
 * grid fields — used to move focus to the FIRST field in error after a failed
 * submit (QA Issue 5). Derived from {@link GRID_FIELDS} so it stays in sync
 * with the rendered order automatically.
 */
const FIELD_FOCUS_ORDER: readonly string[] = [
    'acct_id',
    'card_num',
    ...GRID_FIELDS.map((field) => field.name),
];

/** A required-field rule; `numericError` also enforces a numeric edit. */
interface RequiredRule {
    field: keyof TransactionCreate;
    emptyError: string;
    numericError?: string;
}

/**
 * Required (and optionally numeric) field rules, ordered per COTRN02C
 * VALIDATE-INPUT-DATA-FIELDS. Numeric fields (type/category/merchant id)
 * carry a `numericError`; the rest are simple non-empty checks.
 */
const REQUIRED_FIELD_RULES: readonly RequiredRule[] = [
    { field: 'tran_type_cd', emptyError: TYPE_CD_EMPTY_ERROR, numericError: TYPE_CD_NUMERIC_ERROR },
    { field: 'tran_cat_cd', emptyError: CATEGORY_CD_EMPTY_ERROR, numericError: CATEGORY_CD_NUMERIC_ERROR },
    { field: 'tran_source', emptyError: SOURCE_EMPTY_ERROR },
    { field: 'tran_desc', emptyError: DESCRIPTION_EMPTY_ERROR },
    { field: 'merchant_id', emptyError: MERCHANT_ID_EMPTY_ERROR, numericError: MERCHANT_ID_NUMERIC_ERROR },
    { field: 'merchant_name', emptyError: MERCHANT_NAME_EMPTY_ERROR },
    { field: 'merchant_city', emptyError: MERCHANT_CITY_EMPTY_ERROR },
    { field: 'merchant_zip', emptyError: MERCHANT_ZIP_EMPTY_ERROR },
];

/**
 * Validates the account/card key pair (COTRN02C VALIDATE-INPUT-KEY-FIELDS):
 * a supplied account id or card number must be numeric, and at least one of
 * the two must be entered.
 *
 * @param form - The current form values.
 * @param errors - The shared error map populated in place.
 */
function ValidateKeyFields(form: TransactionCreate, errors: Record<string, string>): void {
    const accountId = (form.acct_id ?? '').trim();
    const cardNumber = (form.card_num ?? '').trim();
    if (accountId !== '' && !NUMERIC_PATTERN.test(accountId)) {
        errors.acct_id = ACCOUNT_ID_NUMERIC_ERROR;
    }
    if (cardNumber !== '' && !NUMERIC_PATTERN.test(cardNumber)) {
        errors.card_num = CARD_NUMBER_NUMERIC_ERROR;
    }
    if (accountId === '' && cardNumber === '') {
        errors.acct_id = ACCT_OR_CARD_REQUIRED_ERROR;
    }
}

/**
 * Validates every non-empty (and, where applicable, numeric) required field
 * from {@link REQUIRED_FIELD_RULES}.
 *
 * @param form - The current form values.
 * @param errors - The shared error map populated in place.
 */
function ValidateRequiredFields(form: TransactionCreate, errors: Record<string, string>): void {
    for (const rule of REQUIRED_FIELD_RULES) {
        const fieldValue = (form[rule.field] ?? '').trim();
        if (fieldValue === '') {
            errors[rule.field] = rule.emptyError;
        } else if (rule.numericError && !NUMERIC_PATTERN.test(fieldValue)) {
            errors[rule.field] = rule.numericError;
        }
    }
}

/**
 * Validates the signed money amount and the two ISO dates (COTRN02C amount /
 * orig-date / proc-date edits). Empty and format errors preserve the legacy
 * wording; the server performs the authoritative calendar/posting checks.
 *
 * @param form - The current form values.
 * @param errors - The shared error map populated in place.
 */
function ValidateAmountAndDates(form: TransactionCreate, errors: Record<string, string>): void {
    const amountValue = form.tran_amt.trim();
    if (amountValue === '') {
        errors.tran_amt = AMOUNT_EMPTY_ERROR;
    } else if (!AMOUNT_PATTERN.test(amountValue)) {
        errors.tran_amt = AMOUNT_FORMAT_ERROR;
    }
    const origValue = form.orig_ts.trim();
    if (origValue === '') {
        errors.orig_ts = ORIG_DATE_EMPTY_ERROR;
    } else if (!DATE_PATTERN.test(origValue)) {
        errors.orig_ts = ORIG_DATE_FORMAT_ERROR;
    }
    const procValue = form.proc_ts.trim();
    if (procValue === '') {
        errors.proc_ts = PROC_DATE_EMPTY_ERROR;
    } else if (!DATE_PATTERN.test(procValue)) {
        errors.proc_ts = PROC_DATE_FORMAT_ERROR;
    }
}

/**
 * Builds the POST body from the form, normalizing the account/card key pair
 * (QA finding C5). The backend declares `acct_id` and `card_num` as
 * `Optional[str]` and enforces the COTRN02 "(or)" rule (at least one). Its
 * digit validator passes only `None` for an absent key — an empty string ""
 * FAILS the edit — so an account-only or card-only add must OMIT the unused
 * key rather than send a blank. Non-empty values are trimmed so stray spaces
 * from the terminal-style fields never reach the server.
 *
 * @param form - The current form values.
 * @returns The request payload with empty key fields omitted.
 */
function BuildTransactionPayload(form: TransactionCreate): TransactionCreate {
    const accountId = (form.acct_id ?? '').trim();
    const cardNumber = (form.card_num ?? '').trim();
    const payload: TransactionCreate = {
        ...form,
        acct_id: accountId === '' ? undefined : accountId,
        card_num: cardNumber === '' ? undefined : cardNumber,
    };
    return payload;
}


/**
 * Mints one idempotency key per confirmed add operation (QA Issue 16). The
 * server treats two requests carrying the same key as a single financial
 * effect, so this key — attached to exactly one confirmed submit — makes a
 * transport-level duplication of that submit collapse to one transaction while
 * two genuinely distinct confirmed adds (distinct keys) both post.
 *
 * Prefers the Web Crypto `randomUUID`; falls back to a timestamp+random token
 * for runtimes/test environments where it is unavailable.
 *
 * @returns A collision-resistant idempotency key string.
 */
function GenerateIdempotencyKey(): string {
    const cryptoObject =
        typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
    if (cryptoObject && typeof cryptoObject.randomUUID === 'function') {
        return cryptoObject.randomUUID();
    }
    return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}


/**
 * Add-Transaction page. Renders the redesigned MD3 data-entry form, runs
 * client-side validation for UX fidelity, gates the write behind a confirm
 * dialog, and POSTs to `/transactions` — surfacing the server's authoritative
 * success or posting error (codes 100-103/109) via ErrorAlert.
 *
 * @returns The rendered add-transaction page content.
 */
export default function TransactionsAddPage() {
    const router = useRouter();

    const [transactionForm, setTransactionForm] =
        useState<TransactionCreate>(EMPTY_TRANSACTION_FORM);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [isConfirmOpen, setIsConfirmOpen] = useState<boolean>(false);
    const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
    const [isAlertOpen, setIsAlertOpen] = useState<boolean>(false);
    const [alertSeverity, setAlertSeverity] = useState<'success' | 'error'>('error');
    const [alertContent, setAlertContent] = useState<unknown>(null);

    // Synchronous in-flight guard (QA Issue 16). React state updates are
    // asynchronous, so `isSubmitting` has not yet re-rendered and disabled the
    // Confirm button by the time a rapid SECOND click fires HandleConfirm. A
    // ref flips synchronously and closes that double-submit window before any
    // second network call can start — complementing the server-enforced
    // Idempotency-Key so a duplicate add posts at most one financial effect.
    const inFlightRef = useRef<boolean>(false);

    /**
     * Relays a single field edit into the grouped form and clears that field's
     * inline error. Matches the FormField `onChange(name, value)` contract.
     */
    const HandleFieldChange = (name: string, value: string) => {
        setTransactionForm((previousForm) => ({ ...previousForm, [name]: value }));
        setFieldErrors((previousErrors) => ({ ...previousErrors, [name]: '' }));
    };

    /** Runs all client-side validators and returns the field → message map. */
    const ValidateForm = (): Record<string, string> => {
        const errors: Record<string, string> = {};
        ValidateKeyFields(transactionForm, errors);
        ValidateRequiredFields(transactionForm, errors);
        ValidateAmountAndDates(transactionForm, errors);
        return errors;
    };

    /** ENTER=Continue: validate, then open the confirm dialog when clean. */
    const HandleSubmit = () => {
        const errors = ValidateForm();
        setFieldErrors(errors);
        const invalidFieldNames = new Set(Object.keys(errors));
        if (invalidFieldNames.size > 0) {
            // Park the cursor on the first field in error, in on-screen order
            // (QA Issue 5).
            FocusFirstInvalidField(FIELD_FOCUS_ORDER, invalidFieldNames);
            return;
        }
        setIsConfirmOpen(true);
    };

    /** Confirmed add: POST the transaction and surface success or the error. */
    const HandleConfirm = async () => {
        // Reject re-entry synchronously so a rapid double-click cannot fire a
        // second POST before `isSubmitting` re-renders (QA Issue 16).
        if (inFlightRef.current) {
            return;
        }
        inFlightRef.current = true;

        // One key for this single confirmed operation: a transport-level retry
        // of THIS submit reuses it (server collapses to one effect), while a
        // later, separate confirmed add mints a fresh key and posts normally.
        const idempotencyKey = GenerateIdempotencyKey();

        setIsConfirmOpen(false);
        setIsSubmitting(true);
        try {
            const createdTransaction = await TransactionsApi.AddTransaction(
                BuildTransactionPayload(transactionForm),
                idempotencyKey,
            );
            setAlertSeverity('success');
            setAlertContent(
                `${SUCCESS_MESSAGE} Your Tran ID is ${createdTransaction.tran_id}.`,
            );
            setIsAlertOpen(true);
            setTransactionForm(EMPTY_TRANSACTION_FORM);
            setFieldErrors({});
        } catch (submitError) {
            setAlertSeverity('error');
            setAlertContent(submitError);
            setIsAlertOpen(true);
        } finally {
            setIsSubmitting(false);
            // Clear the guard so a genuine, user-initiated retry of a FAILED
            // submit is allowed (it mints a new key on the next confirm).
            inFlightRef.current = false;
        }
    };

    /** CONFIRM=N: dismiss the confirm dialog without writing. */
    const HandleCancel = () => {
        setIsConfirmOpen(false);
    };

    /** F4=Clear: reset the form and clear all inline errors. */
    const HandleClear = () => {
        setTransactionForm(EMPTY_TRANSACTION_FORM);
        setFieldErrors({});
    };

    /** F3=Back: return to the transactions list. */
    const HandleBack = () => {
        router.push('/transactions');
    };

    /** Dismiss the success/error alert. */
    const HandleErrorClose = () => {
        setIsAlertOpen(false);
    };

    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Stack spacing={3}>
                <Typography variant="h4" component="h1">
                    {PAGE_TITLE}
                </Typography>

                <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ alignItems: { xs: 'stretch', sm: 'flex-start' } }}
                >
                    <FormField
                        name="acct_id"
                        label="Enter Acct #:"
                        value={transactionForm.acct_id ?? ''}
                        onChange={HandleFieldChange}
                        maxLength={11}
                        error={Boolean(fieldErrors.acct_id)}
                        helperText={fieldErrors.acct_id || ''}
                    />
                    <Typography component="span" sx={{ alignSelf: 'center' }}>
                        (or)
                    </Typography>
                    <FormField
                        name="card_num"
                        label="Card #:"
                        value={transactionForm.card_num ?? ''}
                        onChange={HandleFieldChange}
                        maxLength={16}
                        error={Boolean(fieldErrors.card_num)}
                        helperText={fieldErrors.card_num || ''}
                    />
                </Stack>

                <Grid container spacing={2}>
                    {GRID_FIELDS.map((field) => (
                        <Grid key={field.name} size={{ xs: 12, sm: 6 }}>
                            <FormField
                                name={field.name}
                                label={field.label}
                                value={transactionForm[field.name] ?? ''}
                                onChange={HandleFieldChange}
                                maxLength={field.maxLength}
                                error={Boolean(fieldErrors[field.name])}
                                helperText={fieldErrors[field.name] || (field.hint ?? '')}
                            />
                        </Grid>
                    ))}
                </Grid>

                <Stack direction="row" spacing={2}>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={HandleSubmit}
                        disabled={isSubmitting}
                    >
                        Add
                    </Button>
                    <Button variant="outlined" onClick={HandleClear}>
                        Clear
                    </Button>
                    <Button variant="outlined" onClick={HandleBack}>
                        Back
                    </Button>
                </Stack>
            </Stack>

            <ConfirmDialog
                open={isConfirmOpen}
                title={CONFIRM_TITLE}
                message={CONFIRM_MESSAGE}
                confirmLabel="Add"
                cancelLabel="Cancel"
                confirmColor="primary"
                loading={isSubmitting}
                onConfirm={HandleConfirm}
                onCancel={HandleCancel}
            />

            <ErrorAlert
                open={isAlertOpen}
                onClose={HandleErrorClose}
                error={alertContent}
                severity={alertSeverity}
            />
        </Container>
    );
}
