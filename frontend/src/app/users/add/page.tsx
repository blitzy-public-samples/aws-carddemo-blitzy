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
    // AddUserFormState key, so the computed-key spread stays a string map.
    const HandleChange = (name: string, value: string): void => {
        setFormValues((previous) => ({ ...previous, [name]: value }));
    };

    // F4 = Clear: reset every field to its empty starting value.
    const HandleClear = (): void => {
        setFormValues(INITIAL_FORM_STATE);
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
     * Validates the five required fields, mirroring the legacy COUSR01C edits.
     *
     * @param values - The current form state.
     * @returns A user-facing message when invalid, otherwise `null`.
     */
    const ValidateForm = (values: AddUserFormState): string | null => {
        if (!values.firstName.trim()) {
            return 'First Name is required.';
        }
        if (!values.lastName.trim()) {
            return 'Last Name is required.';
        }
        if (!values.userId.trim()) {
            return 'User ID is required.';
        }
        if (!values.password) {
            return 'Password is required.';
        }
        if (values.userType !== 'A' && values.userType !== 'U') {
            return 'User Type must be Admin or User.';
        }
        return null;
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
        const validationError = ValidateForm(formValues);
        if (validationError) {
            setErrorValue(validationError);
            setAlertOpen(true);
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
                        />
                        <FormField
                            name="lastName"
                            label="Last Name"
                            value={formValues.lastName}
                            onChange={HandleChange}
                            maxLength={20}
                            required
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
                        />
                        <FormField
                            name="password"
                            label="Password"
                            type="password"
                            value={formValues.password}
                            onChange={HandleChange}
                            maxLength={8}
                            required
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
