'use client';

/**
 * Card Update (form) — route /cards/update
 * Modernizes BMS COCRDUP (CCRDUPA) · CICS Tx CCUP · COBOL COCRDUPC
 * Security: card_num is MASKED; the card security code is NEVER present or
 * editable (there is no such field on any card type reaching this screen).
 *
 * The legacy 3270 screen loaded one credit card by its account/card key and let
 * an operator edit exactly three fields (name-on-card, active status, expiry).
 * This client component reproduces that behavior over the modern REST API:
 * it reads the (masked) card identifier from the query string, loads the card
 * via `CardsApi.GetCard`, and submits a minimal `CardUpdate` payload via
 * `CardsApi.UpdateCard`. Identity/role are carried by the session cookie the
 * apiClient sends automatically — there is no CICS COMMAREA to propagate.
 *
 * @packageDocumentation
 */

import { useState, useEffect, useCallback, Suspense } from 'react';
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
import type { CardRead, CardUpdate, ErrorResponse } from '@/types';

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

/** Number of trailing card-number characters left visible when masking. */
const MASK_VISIBLE_DIGITS = 4;

/** Glyph used to mask the leading portion of a card number. */
const MASK_CHARACTER = '*';

/** HTTP status used to detect a "card not found" response on load. */
const HTTP_NOT_FOUND = 404;

/** Route of the card-list screen (COCRDLI), used as the null-identifier fallback. */
const CARDS_LIST_ROUTE = '/cards';

/** Heading text, mirroring the legacy COCRDUP title INITIAL literal. */
const PAGE_TITLE = 'Update Credit Card Details';

/** Shown (via the alert) when the page is reached without a `cardNum` query parameter. */
const NO_CARD_NUMBER_MESSAGE =
    'No card number supplied. Return to the card list to select a card.';

/** Complementary inline guidance shown in the body of the null-identifier guard. */
const NO_CARD_NUMBER_HINT =
    'Select a card from the card list to update its details.';

/** Shown when the backend reports the requested card does not exist. */
const CARD_NOT_FOUND_MESSAGE = 'The requested card could not be found.';

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
 * Masks all but the trailing {@link MASK_VISIBLE_DIGITS} characters of a card
 * number. The backend already pre-masks `card_num`, but this defensive helper
 * guarantees a full PAN is never rendered even if one is somehow supplied.
 *
 * @param cardNumber - The (already masked) card number to display.
 * @returns The masked display string (empty string when input is empty).
 */
function MaskCardNumber(cardNumber: string): string {
    if (!cardNumber) {
        return '';
    }
    const visible = cardNumber.slice(-MASK_VISIBLE_DIGITS);
    const maskedLength = Math.max(cardNumber.length - MASK_VISIBLE_DIGITS, 0);
    return `${MASK_CHARACTER.repeat(maskedLength)}${visible}`;
}

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
 * Builds the editable {@link CardUpdate} form shape from a loaded card. Only the
 * three editable fields are carried forward — never `acct_id`, `card_num`, or
 * any sensitive value.
 *
 * @param card - The card read from the backend.
 * @returns The initial editable form state.
 */
function BuildInitialFormData(card: CardRead): CardUpdate {
    return {
        embossed_name: card.embossed_name ?? '',
        expiration_date: NormalizeIsoDate(card.expiration_date),
        active_status: card.active_status ?? '',
    };
}

/**
 * Builds the card-detail route for a given (masked) identifier, encoding the
 * identifier for safe inclusion in the query string.
 *
 * @param cardNumber - The masked card identifier.
 * @returns The `/cards/view?cardNum=...` route string.
 */
function BuildCardViewRoute(cardNumber: string): string {
    return `/cards/view?cardNum=${encodeURIComponent(cardNumber)}`;
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
 * Renders and drives the Card Update form. Reads the masked card identifier
 * from the `cardNum` query parameter, loads the card, and submits edits.
 *
 * @returns The card-update form element.
 */
function CardsUpdateContent() {
    const searchParams = useSearchParams();
    const cardNumber = searchParams.get('cardNum');
    const router = useRouter();

    const [formData, setFormData] = useState<CardUpdate>({
        embossed_name: '',
        expiration_date: '',
        active_status: '',
    });
    const [acctId, setAcctId] = useState<string>('');
    const [cardNumberDisplay, setCardNumberDisplay] = useState<string>('');
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
    const [errorState, setErrorState] = useState<ErrorResponse | string | null>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

    /**
     * Loads the card identified by the query string and seeds the form. Uses a
     * specific `IsApiError` branch to surface a "not found" message on 404.
     */
    const LoadCard = useCallback(async (): Promise<void> => {
        if (!cardNumber) {
            setErrorState(NO_CARD_NUMBER_MESSAGE);
            setIsErrorOpen(true);
            return;
        }
        setIsLoading(true);
        setIsErrorOpen(false);
        setErrorState(null);
        try {
            const card = await CardsApi.GetCard(cardNumber);
            setFormData(BuildInitialFormData(card));
            setAcctId(card.acct_id ?? '');
            setCardNumberDisplay(MaskCardNumber(card.card_num));
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
    }, [cardNumber]);

    useEffect(() => {
        void LoadCard();
    }, [LoadCard]);

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
     * Validates the form and, when valid, submits the minimal `CardUpdate`
     * payload. On success navigates to the card-detail view; on failure routes
     * the specific error to the alert.
     */
    const HandleSubmit = async (): Promise<void> => {
        if (!cardNumber) {
            setErrorState(NO_CARD_NUMBER_MESSAGE);
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
                embossed_name: formData.embossed_name,
                expiration_date: formData.expiration_date,
                active_status: formData.active_status,
            };
            await CardsApi.UpdateCard(cardNumber, cardUpdate);
            router.push(BuildCardViewRoute(cardNumber));
        } catch (err) {
            setErrorState(ResolveError(err, UPDATE_FAILED_MESSAGE));
            setIsErrorOpen(true);
        } finally {
            setIsSubmitting(false);
        }
    };

    /** Returns to the card detail view (or the card list when id is absent). */
    const HandleCancel = (): void => {
        if (cardNumber) {
            router.push(BuildCardViewRoute(cardNumber));
        } else {
            router.push(CARDS_LIST_ROUTE);
        }
    };

    /** Dismisses the error alert. */
    const HandleErrorClose = (): void => {
        setIsErrorOpen(false);
    };

    // No identifier: surface the guard message and offer a way back — never
    // render editable fields without a card to edit.
    if (!cardNumber) {
        return (
            <Container maxWidth="sm" sx={{ py: 4 }}>
                <Card>
                    <CardHeader title={PAGE_TITLE} />
                    <CardContent>
                        <Typography variant="body1" color="text.secondary">
                            {NO_CARD_NUMBER_HINT}
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
                <CardHeader title={PAGE_TITLE} />
                <CardContent>
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
                                Account id and card number are read-only. Edit the
                                name on card, status, and expiration date.
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
                                name="card_num"
                                label="Card Number"
                                value={cardNumberDisplay}
                                onChange={HandleFieldChange}
                                readOnly
                                maxLength={CARD_NUM_LENGTH}
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

