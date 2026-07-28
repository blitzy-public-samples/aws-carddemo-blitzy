'use client';

/**
 * Card Update (form) — route /cards/update
 * Modernizes BMS COCRDUP (CCRDUPA) · CICS Tx CCUP · COBOL COCRDUPC
 * Security: card_num is MASKED; the card security code is NEVER present or
 * editable (there is no such field on any card type reaching this screen).
 *
 * The legacy 3270 screen loaded one credit card by its card key and let an
 * operator edit exactly three fields (name-on-card, active status, expiry).
 * This client component reproduces that behavior over the modern REST API.
 *
 * Navigation is faithful to the legacy COCRDUP screen, whose `CARDSID` is an
 * UNPROT "Card Number" input the operator TYPES: the screen keys the card on the
 * card number read from the `cardNum` query string, loads it via
 * `CardsApi.GetCard` and submits the minimal `CardUpdate` via
 * `CardsApi.UpdateCard` over the frozen GET/PUT /cards/{cardNum} contract (AAP
 * 0.5.5). A card-number key-capture picker lets a card always be reached
 * manually. The earlier by-account variant was removed because it resolved an
 * account to a single card with `.limit(1)`, silently editing the wrong card on
 * the NONUNIQUE account->card relationship (QA C07), and was outside the frozen
 * route list (QA C08).
 *
 * Optimistic locking (QA C06): the submitted `CardUpdate` carries a required
 * client-echoed `before_image` — the editable-field values as they were loaded —
 * which the backend compares field-for-field against the freshly locked row,
 * rejecting a stale write with HTTP 409 (reproducing the COCRDUPC
 * READ-for-UPDATE -> REWRITE lost-update guard, AAP 0.7.4). The displayed
 * `card_num` is always MASKED (AAP 0.7.8); the number the operator types is used
 * only as the path key and is never rendered unmasked.
 *
 * Identity/role are carried by the session cookie the apiClient sends
 * automatically — there is no CICS COMMAREA to propagate.
 *
 * @packageDocumentation
 */

import { useState, useEffect, useCallback, Suspense } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import {
    Box,
    Stack,
    Container,
    Card,
    CardHeader,
    CardContent,
    CardActions,
    Button,
    Typography,
    CircularProgress,
} from '@mui/material';

import { CardsApi, IsApiError } from '@/lib/apiClient';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import type { CardBeforeImage, CardRead, CardUpdate, ErrorResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* Field lengths are the on-screen buffer widths verified from the BMS       */
/* symbolic-map copybook app/cpy-bms/COCRDUP.CPY (`<name>I PIC X(n)`).       */
/* ------------------------------------------------------------------------- */

/** CRDNAME PIC X(50) — maximum accepted length of the name-on-card field. */
const EMBOSSED_NAME_LENGTH = 50;

/** ACCTSID PIC X(11) — display width of the read-only account id. */
const ACCT_ID_LENGTH = 11;

/** CARDSID PIC X(16) — display width of the read-only (masked) card number. */
const CARD_NUM_LENGTH = 16;

/** Canonical value for an ACTIVE card (CRDSTCD domain, "Card Active Y/N"). */
const STATUS_ACTIVE = 'Y';

/** Canonical value for an INACTIVE card (CRDSTCD domain, "Card Active Y/N"). */
const STATUS_INACTIVE = 'N';

/**
 * Dropdown options for the CRDSTCD "Card Active Y/N" flag. Rendered through the
 * `FormField` select variant (design-system preference over a raw text field).
 */
const STATUS_OPTIONS = [
    { value: STATUS_ACTIVE, label: 'Active (Y)' },
    { value: STATUS_INACTIVE, label: 'Inactive (N)' },
];

/** Length of an ISO calendar date (`YYYY-MM-DD`) accepted by the date picker. */
const ISO_DATE_LENGTH = 10;

/** Matches a strict ISO calendar date (`YYYY-MM-DD`). */
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** HTTP status used to detect a "card not found" response on load. */
const HTTP_NOT_FOUND = 404;

/** Query-string key carrying the entered card number (`?cardNum=`). */
const CARD_NUM_QUERY_PARAM = 'cardNum';

/** Route of this update screen; used to navigate the card-number picker. */
const CARDS_UPDATE_ROUTE = '/cards/update';

/** Route of the card-list screen (COCRDLI), used as the null-identifier fallback. */
const CARDS_LIST_ROUTE = '/cards';

/** Heading text, mirroring the legacy COCRDUP title INITIAL literal. */
const PAGE_TITLE = 'Update Credit Card Details';

/** Label for the card-number picker input (BMS CARDSID field). */
const CARD_NUM_PICKER_LABEL = 'Card Number';

/** Label for the picker's load button (maps the legacy ENTER lookup). */
const LOAD_BUTTON_LABEL = 'LOAD';

/** Shown (via the alert) when the page is reached without a `cardNum` param. */
const NO_CARD_NUM_MESSAGE =
    'No card number supplied. Return to the card list to select a card.';

/** Complementary inline guidance shown in the body of the null-identifier guard. */
const NO_CARD_NUM_HINT =
    'Select a card from the card list, or enter a card number above, to ' +
    'update its card details.';

/** Shown when the backend reports no card exists for the number. */
const CARD_NOT_FOUND_MESSAGE = 'No card could be found for this card number.';

/** Fallback shown when the card details fail to load. */
const LOAD_FAILED_MESSAGE = 'Unable to load the card details. Please try again.';

/** Fallback shown when the update request fails. */
const UPDATE_FAILED_MESSAGE = 'Unable to update the card. Please try again.';

/** Shown when client-side validation blocks submission. */
const VALIDATION_FAILED_MESSAGE =
    'Please correct the highlighted fields and try again.';

/* ------------------------------------------------------------------------- */
/* Pure helpers (small, single-purpose — Ochs complexity rule).             */
/* ------------------------------------------------------------------------- */

/**
 * Normalizes an ISO date-ish string to the `YYYY-MM-DD` value the native date
 * picker expects (the loaded value may carry a time component).
 *
 * @param value - The raw ISO date string from `CardRead.expiration_date`.
 * @returns The `YYYY-MM-DD` slice, or empty string when input is empty.
 */
function NormalizeIsoDate(value: string): string {
    if (!value) {
        return '';
    }
    return value.slice(0, ISO_DATE_LENGTH);
}

/**
 * Validates that a string is a syntactically valid ISO calendar date.
 *
 * @param value - The candidate `YYYY-MM-DD` string.
 * @returns `true` when the value matches the ISO pattern and parses to a date.
 */
function IsValidExpirationDate(value: string): boolean {
    if (!ISO_DATE_PATTERN.test(value)) {
        return false;
    }
    const parsed = new Date(value);
    return !Number.isNaN(parsed.getTime());
}

/**
 * Builds the client-echoed {@link CardBeforeImage} optimistic-lock token from a
 * loaded card — the editable-field values exactly as the operator last read
 * them. The backend compares this field-for-field against the freshly locked row
 * and rejects a stale write with HTTP 409 (QA C06). `expiration_date` is always
 * present on a read card, so it is echoed to anchor the lost-update check.
 *
 * @param card - The card read from the backend.
 * @returns The before-image echo for the optimistic-lock compare.
 */
function BuildBeforeImage(card: CardRead): CardBeforeImage {
    return {
        embossed_name: card.embossed_name ?? '',
        active_status: card.active_status ?? '',
        expiration_date: NormalizeIsoDate(card.expiration_date),
    };
}

/**
 * Builds the editable {@link CardUpdate} form shape from a loaded card. Carries
 * the three editable fields plus the REQUIRED `before_image` optimistic-lock
 * token (QA C06) — never `acct_id`, `card_num`, or any sensitive value.
 *
 * The `before_image` is a control field, not an edited value: `HandleFieldChange`
 * only ever writes the top-level editable keys (`embossed_name`,
 * `expiration_date`, `active_status`), never `before_image`, so the pre-edit
 * snapshot rides through every edit untouched until the next load re-seeds it.
 *
 * @param card - The card read from the backend.
 * @returns The initial editable form state, including `before_image`.
 */
function BuildInitialFormData(card: CardRead): CardUpdate {
    return {
        before_image: BuildBeforeImage(card),
        embossed_name: card.embossed_name ?? '',
        expiration_date: NormalizeIsoDate(card.expiration_date),
        active_status: card.active_status ?? '',
    };
}

/**
 * Builds the card-detail route for a given card number, encoding it for safe
 * inclusion in the query string. The detail/update screens are keyed on the
 * card number the operator entered (legacy COCRDSL/COCRDUP `CARDSID`), per the
 * frozen GET/PUT /cards/{cardNum} contract (AAP 0.5.5).
 *
 * @param cardNumber - The entered card number (path key).
 * @returns The `/cards/view?cardNum=...` route string.
 */
function BuildCardViewRoute(cardNumber: string): string {
    return `/cards/view?${CARD_NUM_QUERY_PARAM}=${encodeURIComponent(cardNumber)}`;
}

/**
 * Applies the field-level edits ported from the COCRDUP screen definition:
 * name-on-card is required and bounded to {@link EMBOSSED_NAME_LENGTH}; status
 * must be `Y` or `N`; expiration date must be a valid ISO date.
 *
 * @param formData - The current editable form state.
 * @returns A map of field name → error message (empty when the form is valid).
 */
function ValidateForm(formData: CardUpdate): Record<string, string> {
    const errors: Record<string, string> = {};
    const embossedName = formData.embossed_name.trim();
    if (embossedName.length === 0) {
        errors.embossed_name = 'Name on card is required.';
    } else if (embossedName.length > EMBOSSED_NAME_LENGTH) {
        errors.embossed_name =
            `Name on card must be ${EMBOSSED_NAME_LENGTH} characters or fewer.`;
    }
    if (
        formData.active_status !== STATUS_ACTIVE &&
        formData.active_status !== STATUS_INACTIVE
    ) {
        errors.active_status = 'Status must be Active (Y) or Inactive (N).';
    }
    if (!IsValidExpirationDate(formData.expiration_date)) {
        errors.expiration_date = 'Expiration date must be a valid date.';
    }
    return errors;
}

/**
 * Resolves a caught error into a displayable payload for {@link ErrorAlert}
 * using a SPECIFIC {@link IsApiError} branch (never a blanket cast — Ochs
 * error-handling rule). Backend-supplied `code`/`detail` are preserved so
 * posting/validation codes reach the operator.
 *
 * @param err - The caught (unknown) error.
 * @param fallbackMessage - Message used when the error carries no text.
 * @returns A normalized {@link ErrorResponse} or a plain message string.
 */
function ResolveError(err: unknown, fallbackMessage: string): ErrorResponse | string {
    if (IsApiError(err)) {
        return {
            message: err.message.length > 0 ? err.message : fallbackMessage,
            code: err.code,
            detail: err.detail,
        };
    }
    return fallbackMessage;
}

/* ------------------------------------------------------------------------- */
/* Inner content component — owns all state, data flow, and JSX.             */
/* Separated from the default export so `useSearchParams` runs inside a       */
/* <Suspense> boundary (required by the Next.js 16 App Router).               */
/* ------------------------------------------------------------------------- */

/**
 * Renders and drives the Card Update form. Reads the card number from the
 * `cardNum` query parameter, loads that card, and submits edits addressed by the
 * card number (QA C07/C08) with a client-echoed `before_image` optimistic-lock
 * token (QA C06). A card-number key-capture picker lets a card be reached
 * manually.
 *
 * @returns The card-update form element.
 */
function CardsUpdateContent() {
    const searchParams = useSearchParams();
    const cardNumParam = searchParams.get(CARD_NUM_QUERY_PARAM) ?? '';
    const router = useRouter();

    const [formData, setFormData] = useState<CardUpdate>({
        before_image: {
            embossed_name: '',
            active_status: '',
            expiration_date: '',
        },
        embossed_name: '',
        expiration_date: '',
        active_status: '',
    });
    const [acctId, setAcctId] = useState<string>('');
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<ErrorResponse | string | null>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    // Controlled value of the card-number picker input. Seeded from (and kept in
    // sync with) the `?cardNum=` query param so a loaded card shows its number
    // in the picker, while still allowing the user to type a new one.
    const [pickerValue, setPickerValue] = useState<string>(cardNumParam);

    useEffect(() => {
        setPickerValue(cardNumParam);
    }, [cardNumParam]);

    /**
     * Loads the card for the query-string card number and seeds the form
     * (including the `before_image` optimistic-lock echo). Uses a specific
     * `IsApiError` branch to surface a "not found" message on 404. Memoized on
     * `cardNumParam` for the effect below.
     */
    const LoadCard = useCallback(async (): Promise<void> => {
        if (!cardNumParam) {
            return;
        }
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const card = await CardsApi.GetCard(cardNumParam);
            setFormData(BuildInitialFormData(card));
            setAcctId(card.acct_id ?? '');
        } catch (err) {
            if (IsApiError(err) && err.status === HTTP_NOT_FOUND) {
                setErrorState(CARD_NOT_FOUND_MESSAGE);
            } else {
                setErrorState(ResolveError(err, LOAD_FAILED_MESSAGE));
            }
            setIsErrorOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [cardNumParam]);

    useEffect(() => {
        void LoadCard();
    }, [LoadCard]);

    /**
     * Updates the picker input value. Signature matches {@link FormField}'s
     * `onChange(name, value)` contract.
     *
     * @param _name - The originating field name (unused; single-field picker).
     * @param value - The new card-number input value.
     */
    const HandlePickerChange = (_name: string, value: string): void => {
        setPickerValue(value);
    };

    /**
     * Navigates to `/cards/update?cardNum=<entered number>`. The query-param
     * change re-drives {@link LoadCard} through the `useSearchParams` effect. An
     * empty entry is ignored (mirrors the legacy empty-key guard).
     */
    const HandleLoadClick = (): void => {
        const trimmedNum = pickerValue.trim();
        if (trimmedNum === '') {
            return;
        }
        const target =
            `${CARDS_UPDATE_ROUTE}?${CARD_NUM_QUERY_PARAM}=` +
            `${encodeURIComponent(trimmedNum)}`;
        router.push(target);
    };

    /**
     * Updates one field immutably and clears any prior error for that field.
     * Signature matches `FormField.onChange`, which passes `(name, value)`.
     *
     * @param name - The field name (also the `CardUpdate` key).
     * @param value - The new string value.
     */
    const HandleFieldChange = (name: string, value: string): void => {
        setFormData((prev) => ({ ...prev, [name]: value }));
        setFieldErrors((prev) => {
            if (!prev[name]) {
                return prev;
            }
            const next = { ...prev };
            delete next[name];
            return next;
        });
    };

    /**
     * Validates the form and, when valid, submits the `CardUpdate` payload
     * addressed by the card number, echoing the loaded `before_image` so the
     * backend can reject a stale write with HTTP 409 (QA C06). On success
     * navigates to the card-detail view; on failure routes the specific error
     * to the alert.
     */
    const HandleSubmit = async (): Promise<void> => {
        if (!cardNumParam) {
            setErrorState(NO_CARD_NUM_MESSAGE);
            setIsErrorOpen(true);
            return;
        }
        const validationErrors = ValidateForm(formData);
        if (Object.keys(validationErrors).length > 0) {
            setFieldErrors(validationErrors);
            setErrorState(VALIDATION_FAILED_MESSAGE);
            setIsErrorOpen(true);
            return;
        }
        setFieldErrors({});
        setIsSubmitting(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const cardUpdate: CardUpdate = {
                before_image: formData.before_image,
                embossed_name: formData.embossed_name,
                expiration_date: formData.expiration_date,
                active_status: formData.active_status,
            };
            await CardsApi.UpdateCard(cardNumParam, cardUpdate);
            router.push(BuildCardViewRoute(cardNumParam));
        } catch (err) {
            setErrorState(ResolveError(err, UPDATE_FAILED_MESSAGE));
            setIsErrorOpen(true);
        } finally {
            setIsSubmitting(false);
        }
    };

    /** Returns to the card detail view (or the card list when the number is absent). */
    const HandleCancel = (): void => {
        if (cardNumParam) {
            router.push(BuildCardViewRoute(cardNumParam));
        } else {
            router.push(CARDS_LIST_ROUTE);
        }
    };

    /** Dismisses the error alert. */
    const HandleErrorClose = (): void => {
        setIsErrorOpen(false);
    };

    /**
     * The card-number key-capture picker, rendered on every state so a card can
     * always be reached manually (QA C07/C08 fallback).
     */
    const pickerRow: ReactNode = (
        <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ mb: 3, alignItems: { sm: 'flex-start' } }}
        >
            <FormField
                name={CARD_NUM_QUERY_PARAM}
                label={CARD_NUM_PICKER_LABEL}
                value={pickerValue}
                onChange={HandlePickerChange}
                maxLength={CARD_NUM_LENGTH}
                required
                autoFocus
                autoComplete="off"
            />
            <Button
                variant="contained"
                onClick={HandleLoadClick}
                disabled={isLoading || isSubmitting}
            >
                {LOAD_BUTTON_LABEL}
            </Button>
        </Stack>
    );

    // No identifier: surface the guard message and offer a way back — never
    // render editable fields without a card to edit. The picker is still shown
    // so a card number can be entered manually.
    if (!cardNumParam) {
        return (
            <Container maxWidth="sm" sx={{ py: 4 }}>
                <Card>
                    <CardHeader
                        title={PAGE_TITLE}
                        slotProps={{ title: { component: 'h1' } }}
                    />
                    <CardContent>
                        {pickerRow}
                        <Typography variant="body1" color="text.secondary">
                            {NO_CARD_NUM_HINT}
                        </Typography>
                    </CardContent>
                    <CardActions sx={{ justifyContent: 'flex-end', px: 2, pb: 2 }}>
                        <Button variant="outlined" onClick={HandleCancel}>
                            Back to Cards
                        </Button>
                    </CardActions>
                </Card>
                <ErrorAlert
                    open={isErrorOpen}
                    onClose={HandleErrorClose}
                    error={errorState}
                />
            </Container>
        );
    }

    return (
        <Container maxWidth="sm" sx={{ py: 4 }}>
            <Card>
                <CardHeader
                    title={PAGE_TITLE}
                    slotProps={{ title: { component: 'h1' } }}
                />
                <CardContent>
                    {pickerRow}
                    {isLoading ? (
                        <Box
                            sx={{
                                display: 'flex',
                                justifyContent: 'center',
                                py: 4,
                            }}
                        >
                            <CircularProgress />
                        </Box>
                    ) : (
                        <Stack spacing={3}>
                            <Typography variant="body2" color="text.secondary">
                                The card number (entered above) and the owning
                                account id are read-only. Edit the name on card,
                                status, and expiration date.
                            </Typography>
                            <FormField
                                name="acct_id"
                                label="Account ID"
                                value={acctId}
                                onChange={HandleFieldChange}
                                readOnly
                                maxLength={ACCT_ID_LENGTH}
                            />
                            <FormField
                                name="embossed_name"
                                label="Name on Card"
                                value={formData.embossed_name}
                                onChange={HandleFieldChange}
                                type="text"
                                maxLength={EMBOSSED_NAME_LENGTH}
                                required
                                error={Boolean(fieldErrors.embossed_name)}
                                helperText={fieldErrors.embossed_name}
                            />
                            <FormField
                                name="active_status"
                                label="Status"
                                value={formData.active_status}
                                onChange={HandleFieldChange}
                                type="select"
                                options={STATUS_OPTIONS}
                                required
                                error={Boolean(fieldErrors.active_status)}
                                helperText={fieldErrors.active_status}
                            />
                            <FormField
                                name="expiration_date"
                                label="Expiration Date"
                                value={formData.expiration_date}
                                onChange={HandleFieldChange}
                                type="date"
                                required
                                error={Boolean(fieldErrors.expiration_date)}
                                helperText={fieldErrors.expiration_date}
                            />
                        </Stack>
                    )}
                </CardContent>
                <CardActions sx={{ justifyContent: 'flex-end', px: 2, pb: 2 }}>
                    <Button
                        variant="outlined"
                        onClick={HandleCancel}
                        disabled={isSubmitting}
                    >
                        Cancel
                    </Button>
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={HandleSubmit}
                        disabled={isSubmitting || isLoading}
                    >
                        Save
                    </Button>
                </CardActions>
            </Card>
            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Container>
    );
}

/* ------------------------------------------------------------------------- */
/* Default export — thin Suspense wrapper (Next.js 16 App Router).           */
/* ------------------------------------------------------------------------- */

/**
 * Card Update page. Wraps {@link CardsUpdateContent} in a `<Suspense>` boundary
 * because `useSearchParams` triggers a client-side-rendering bailout that would
 * otherwise fail `next build`.
 *
 * @returns The Suspense-wrapped card-update page.
 */
export default function CardsUpdatePage() {
    return (
        <Suspense
            fallback={
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
                    <CircularProgress />
                </Box>
            }
        >
            <CardsUpdateContent />
        </Suspense>
    );
}
