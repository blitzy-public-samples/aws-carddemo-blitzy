'use client';

/**
 * Add User page — modern replacement for BMS map COUSR01 (Tx CU01, program COUSR01C).
 * Source of truth: app/bms/COUSR01.bms + app/cpy-bms/COUSR01.CPY.
 * REST: POST /admin/users via UsersApi.AddUser (201).
 * Admin-only (user_type === 'A'); the server require_admin (403) is the real
 * security boundary — the client gate below is UX only.
 *
 * Field fidelity (COUSR01.CPY symbolic map): First Name FNAMEI X(20),
 * Last Name LNAMEI X(20), User ID USERIDI X(8), Password PASSWDI X(8) (masked,
 * ATTRB=(DRK,...)), User Type USRTYPEI X(1) with the inline hint '(A=Admin, U=User)'.
 * PF-key footer 'ENTER=Add User  F3=Back  F4=Clear  F12=Exit' maps to the three
 * MUI buttons below (Enter submits the form, Back → /users, Clear resets).
 */

import { useState, useEffect } from 'react';
import type { FormEvent } from 'react';
import { useRouter } from 'next/navigation';

import Container from '@mui/material/Container';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';

import { FormField } from '@/components/FormField';
import type { FieldOption } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { UsersApi, IsApiError } from '@/lib/apiClient';
import { IsAdmin } from '@/lib/auth';
import { FocusFirstInvalidField } from '@/lib/keyboard';
import type { UserCreate } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Route back to the user list (F3 / F12 = Back, and post-success landing). */
const USERS_LIST_ROUTE = '/users';

/** Route used when the client admin gate or a server 403 denies access. */
const MENU_ROUTE = '/menu';

/** HTTP 403 — server require_admin denial (the real security boundary). */
const HTTP_FORBIDDEN = 403;

/**
 * User Type dropdown options — derived from the legacy inline hint
 * '(A=Admin, U=User)' on COUSR01.bms (USRTYPE field).
 */
const USER_TYPE_OPTIONS: FieldOption[] = [
    { value: 'A', label: 'Admin' },
    { value: 'U', label: 'User' },
];

/**
 * Exact width of SEC-USR-ID (COUSR01 USERIDI PIC X(8)). The user id is a
 * fixed-width key, so it must be EXACTLY this many characters — mirrored from
 * the backend LoginRequest / UserCreate edits so the client rejects the same
 * short ids the server does (QA Issue 2: a <8-char id used to be accepted at
 * create but rejected at sign-on, yielding an unusable "dead" credential).
 */
const USER_ID_LENGTH = 8;

/**
 * Allowed user-id characters: letters and digits only, NO embedded space —
 * identical to the backend IDENTIFIER_PATTERN so accept/reject parity holds on
 * both tiers. A user id becomes a database key, a URL path segment, and a token
 * subject, so an interior space is an ambiguous, non-canonical character (QA
 * Issue 18: "ID pattern allows spaces ... ambiguous identifiers").
 */
const USER_ID_ALPHANUMERIC_PATTERN = /^[A-Za-z0-9]+$/;

/**
 * Exact width of SEC-USR-PWD (COUSR01 PASSWDI PIC X(8)). Like the user id, the
 * password is a fixed-width field, so the client requires EXACTLY this many
 * characters — mirrored from the backend UserCreate edit so the client rejects
 * the same weak short passwords the server does (QA Issue 18: a one-character
 * password used to be accepted because there was no meaningful minimum length).
 */
const PASSWORD_LENGTH = 8;

/* Per-field validation messages. The User ID length/format text matches the
 * backend messages verbatim so the client and server report the same failure. */
const FIRST_NAME_REQUIRED_ERROR = 'First Name is required.';
const LAST_NAME_REQUIRED_ERROR = 'Last Name is required.';
const USER_ID_REQUIRED_ERROR = 'User ID is required.';
const USER_ID_LENGTH_ERROR = 'User ID must be exactly 8 characters.';
const USER_ID_ALPHANUMERIC_ERROR = 'User ID can have numbers or alphabets only.';
const PASSWORD_REQUIRED_ERROR = 'Password is required.';
const PASSWORD_LENGTH_ERROR = 'Password must be exactly 8 characters.';
const USER_TYPE_INVALID_ERROR = 'User Type must be Admin or User.';

/**
 * Field names in on-screen order (First/Last row, then User ID/Password row,
 * then User Type). Used to move focus to the FIRST field in error after a
 * failed submit (QA Issue 5).
 */
const FIELD_FOCUS_ORDER: readonly string[] = [
    'firstName',
    'lastName',
    'userId',
    'password',
    'userType',
];

/**
 * Local form state uses camelCase (Ochs Test Rule); the wire DTO {@link UserCreate}
 * keeps its snake_case keys and is assembled only at submit time.
 */
interface AddUserFormState {
    firstName: string;
    lastName: string;
    userId: string;
    password: string;
    /** '' until chosen; enforced to 'A' | 'U' by ValidateForm before submit. */
    userType: string;
}

/** Empty starting values (also reused by the Clear action). */
const INITIAL_FORM_STATE: AddUserFormState = {
    firstName: '',
    lastName: '',
    userId: '',
    password: '',
    userType: '',
};

/* ------------------------------------------------------------------------- */
/* Page component (Ochs rule: PascalCase component & handler names).         */
/* ------------------------------------------------------------------------- */

/**
 * Renders the admin-only Add-User form and submits a {@link UserCreate} to
 * POST /admin/users. Returns `null` while the client admin gate is being
 * evaluated and for non-admins (who are redirected to the menu).
 *
 * @returns The Add-User page element, or `null` when access is denied/pending.
 */
export default function UsersAddPage() {
    const router = useRouter();

    // Admin-gate state. accessChecked defers rendering until the client-only
    // localStorage check has run (avoids an SSR/hydration flash of the form).
    const [accessChecked, setAccessChecked] = useState(false);
    const [isAdminUser, setIsAdminUser] = useState(false);

    // Form + submission state.
    const [formValues, setFormValues] = useState<AddUserFormState>(INITIAL_FORM_STATE);
    const [submitting, setSubmitting] = useState(false);
    // Per-field inline validation errors (field name -> message), matching the
    // transactions/add pattern (QA Issue 6). Server-side failures still surface
    // through the ErrorAlert snackbar (errorValue/alertOpen) below.
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [errorValue, setErrorValue] = useState<unknown>(null);
    const [alertOpen, setAlertOpen] = useState(false);

    // Client-side gating is UX only; the server require_admin (403) is the real
    // security boundary. IsAdmin() reads client-only storage, so run it in an
    // effect (never during render) to avoid an SSR/hydration mismatch.
    useEffect(() => {
        const adminFlag = IsAdmin();
        setIsAdminUser(adminFlag);
        setAccessChecked(true);
        if (!adminFlag) {
            router.replace(MENU_ROUTE);
        }
    }, [router]);

    // FormField invokes onChange(name, value); `name` matches an
    // AddUserFormState key, so the computed-key spread stays a string map. The
    // edited field's inline error is cleared so it disappears as the user types.
    const HandleChange = (name: string, value: string): void => {
        setFormValues((previous) => ({ ...previous, [name]: value }));
        setFieldErrors((previous) => ({ ...previous, [name]: '' }));
    };

    // F4 = Clear: reset every field to its empty starting value and drop all
    // inline field errors.
    const HandleClear = (): void => {
        setFormValues(INITIAL_FORM_STATE);
        setFieldErrors({});
    };

    // F3 / F12 = Back: return to the user list (push, so Back is available).
    const HandleCancel = (): void => {
        router.push(USERS_LIST_ROUTE);
    };

    // Dismiss the error/validation alert.
    const HandleCloseError = (): void => {
        setAlertOpen(false);
    };

    /**
     * Validates the five required fields, mirroring the legacy COUSR01C edits,
     * and returns a field-name -> message map (empty when the form is clean).
     * The User ID additionally must be EXACTLY 8 alphanumeric characters,
     * matching the backend so a created id can always be used to sign on
     * (QA Issue 2). Errors are reported per field (QA Issue 6) rather than as a
     * single aggregate banner.
     *
     * @param values - The current form state.
     * @returns A map of field name to error message; empty when every field is valid.
     */
    const ValidateForm = (values: AddUserFormState): Record<string, string> => {
        const errors: Record<string, string> = {};
        if (!values.firstName.trim()) {
            errors.firstName = FIRST_NAME_REQUIRED_ERROR;
        }
        if (!values.lastName.trim()) {
            errors.lastName = LAST_NAME_REQUIRED_ERROR;
        }
        // Trim first so the length/format edits see what the server sees
        // (UserCreate has str_strip_whitespace=True).
        const trimmedUserId = values.userId.trim();
        if (!trimmedUserId) {
            errors.userId = USER_ID_REQUIRED_ERROR;
        } else if (trimmedUserId.length !== USER_ID_LENGTH) {
            errors.userId = USER_ID_LENGTH_ERROR;
        } else if (!USER_ID_ALPHANUMERIC_PATTERN.test(trimmedUserId)) {
            errors.userId = USER_ID_ALPHANUMERIC_ERROR;
        }
        if (!values.password) {
            errors.password = PASSWORD_REQUIRED_ERROR;
        } else if (values.password.length !== PASSWORD_LENGTH) {
            // QA Issue 18: reject a too-short (weak) password before submit, the
            // same exact-8 rule the server enforces (UserCreate password edit).
            errors.password = PASSWORD_LENGTH_ERROR;
        }
        if (values.userType !== 'A' && values.userType !== 'U') {
            errors.userType = USER_TYPE_INVALID_ERROR;
        }
        return errors;
    };

    /**
     * ENTER = Add User. Validates, maps the camelCase state to the snake_case
     * {@link UserCreate} wire DTO, and posts it. On success the form is cleared
     * and the browser returns to the user list.
     *
     * @param event - The native form-submit event.
     */
    const HandleSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
        event.preventDefault();
        const validationErrors = ValidateForm(formValues);
        setFieldErrors(validationErrors);
        const invalidFieldNames = new Set(Object.keys(validationErrors));
        if (invalidFieldNames.size > 0) {
            // Park the cursor on the first field in error (QA Issue 5).
            FocusFirstInvalidField(FIELD_FOCUS_ORDER, invalidFieldNames);
            return;
        }
        // The plaintext password lives ONLY here and in the request body; it is
        // hashed server-side. It is never logged, persisted, or rendered back.
        const userCreate: UserCreate = {
            user_id: formValues.userId,
            first_name: formValues.firstName,
            last_name: formValues.lastName,
            password: formValues.password,
            user_type: formValues.userType as 'A' | 'U',
        };
        setSubmitting(true);
        try {
            await UsersApi.AddUser(userCreate);
            setFormValues(INITIAL_FORM_STATE);
            router.push(USERS_LIST_ROUTE);
        } catch (caughtError) {
            // Narrow with IsApiError — the TS equivalent of catching a specific
            // exception (never a blanket catch that swallows detail).
            if (IsApiError(caughtError) && caughtError.status === HTTP_FORBIDDEN) {
                router.replace(MENU_ROUTE);
                return;
            }
            setErrorValue(caughtError);
            setAlertOpen(true);
        } finally {
            setSubmitting(false);
        }
    };

    if (!accessChecked) {
        return null;
    }
    if (!isAdminUser) {
        return null;
    }

    return (
        <Container sx={{ mt: 3, mb: 3 }}>
            <Typography variant="h5" component="h1" sx={{ mb: 3 }}>
                Add User
            </Typography>

            <Box component="form" onSubmit={HandleSubmit} noValidate>
                <Stack spacing={3}>
                    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                        <FormField
                            name="firstName"
                            label="First Name"
                            value={formValues.firstName}
                            onChange={HandleChange}
                            maxLength={20}
                            required
                            autoFocus
                            error={Boolean(fieldErrors.firstName)}
                            helperText={fieldErrors.firstName || ''}
                        />
                        <FormField
                            name="lastName"
                            label="Last Name"
                            value={formValues.lastName}
                            onChange={HandleChange}
                            maxLength={20}
                            required
                            error={Boolean(fieldErrors.lastName)}
                            helperText={fieldErrors.lastName || ''}
                        />
                    </Stack>

                    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                        <FormField
                            name="userId"
                            label="User ID"
                            value={formValues.userId}
                            onChange={HandleChange}
                            maxLength={8}
                            required
                            error={Boolean(fieldErrors.userId)}
                            helperText={fieldErrors.userId || ''}
                        />
                        <FormField
                            name="password"
                            label="Password"
                            type="password"
                            value={formValues.password}
                            onChange={HandleChange}
                            maxLength={8}
                            required
                            autoComplete="new-password"
                            error={Boolean(fieldErrors.password)}
                            helperText={fieldErrors.password || ''}
                        />
                    </Stack>

                    <FormField
                        name="userType"
                        label="User Type"
                        type="select"
                        value={formValues.userType}
                        onChange={HandleChange}
                        options={USER_TYPE_OPTIONS}
                        required
                        error={Boolean(fieldErrors.userType)}
                        helperText={fieldErrors.userType || ''}
                    />

                    <Stack direction="row" spacing={2}>
                        <Button
                            type="submit"
                            variant="contained"
                            color="primary"
                            disabled={submitting}
                        >
                            Add User
                        </Button>
                        <Button
                            type="button"
                            variant="outlined"
                            onClick={HandleClear}
                            disabled={submitting}
                        >
                            Clear
                        </Button>
                        <Button
                            type="button"
                            variant="text"
                            onClick={HandleCancel}
                            disabled={submitting}
                        >
                            Back
                        </Button>
                    </Stack>
                </Stack>
            </Box>

            <ErrorAlert open={alertOpen} onClose={HandleCloseError} error={errorValue} />
        </Container>
    );
}
