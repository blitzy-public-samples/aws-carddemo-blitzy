'use client';

/**
 * Update-User page — modern replacement for BMS map COUSR02 (Tx CU02, program COUSR02C).
 * Source of truth: app/bms/COUSR02.bms + app/cpy-bms/COUSR02.CPY.
 * Admin-only (user_type === 'A'); server enforces require_admin (403), client guard is UX-only.
 *
 * Two-step "fetch-then-edit": the admin enters a User ID and fetches the record, the
 * editable fields populate, then the admin saves the changes. Per the mandatory password
 * uplift (AAP §0.7.7) the password is NEVER returned or rendered and is OPTIONAL on
 * update (blank = keep the existing password unchanged; it is hashed server-side).
 */

import { useState, useEffect, useCallback, Suspense } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Container from '@mui/material/Container';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import type { AlertColor } from '@mui/material';

import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { UsersApi, IsApiError } from '@/lib/apiClient';
import { IsAdmin } from '@/lib/auth';
import { ShouldSuppressActivationShortcut } from '@/lib/keyboard';
import type { UserRead, UserUpdate } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** Route regular (non-admin) users are redirected to when the gate fails. */
const MENU_ROUTE = '/menu';

/** User-list route reached by Save & Exit (F3) and Cancel (F12). */
const USERS_ROUTE = '/users';

/** Lookup-key length — USRIDIN PIC X(8) (COUSR02.CPY). */
const USER_ID_MAX_LENGTH = 8;

/** First/Last name length — FNAME/LNAME PIC X(20) (COUSR02.CPY). */
const NAME_MAX_LENGTH = 20;

/** Password length — PASSWD PIC X(8) (COUSR02.CPY). */
const PASSWORD_MAX_LENGTH = 8;

/** Named HTTP status codes handled at the data layer (no magic numbers). */
const HTTP_FORBIDDEN = 403;
const HTTP_NOT_FOUND = 404;

/** Query-string key carrying a pre-selected user id from the list screen. */
const USER_ID_QUERY_PARAM = 'userId';

/** User-type dropdown options — USRTYPE '(A=Admin, U=User)' (COUSR02.bms). */
const USER_TYPE_OPTIONS = [
    { value: 'A', label: 'Admin' },
    { value: 'U', label: 'User' },
];

/** Friendly, self-documenting operator messages (mirror legacy edit text). */
const EMPTY_USER_ID_MESSAGE = 'User ID can NOT be empty. Enter a User ID to fetch.';
const REQUIRED_FIELDS_MESSAGE = 'First Name, Last Name and User Type are required.';
const USER_NOT_FOUND_MESSAGE = 'User not found. Check the User ID and try again.';
const FETCH_HINT_MESSAGE = 'User loaded. Edit the fields and press Save to apply updates.';
const PASSWORD_HELPER_TEXT = 'Leave blank to keep the current password.';
const USER_ID_HELPER_TEXT = 'Enter a User ID, then Fetch.';

/**
 * Lightweight fallback rendered while the Suspense boundary resolves. Next.js 16
 * requires every `useSearchParams()` consumer to sit beneath a Suspense boundary,
 * so the search-param-reading content is isolated behind this fallback.
 *
 * @returns A centered progress indicator.
 */
function UsersUpdateFallback() {
    return (
        <Container maxWidth="sm">
            <Stack sx={{ alignItems: 'center', py: 6 }}>
                <CircularProgress aria-label="Loading" />
            </Stack>
        </Container>
    );
}

/**
 * Route entry point for `/users/update`. It renders only the Suspense boundary;
 * all state, effects, and the search-param read live in {@link UsersUpdateContent}
 * so `next build` does not fail on the client-side-rendering bailout.
 *
 * @returns The Suspense-wrapped update-user page.
 */
export default function UsersUpdatePage() {
    return (
        <Suspense fallback={<UsersUpdateFallback />}>
            <UsersUpdateContent />
        </Suspense>
    );
}

/**
 * Update-User page content (Next.js App Router client page). Holds every hook —
 * including the sole `useSearchParams()` read — and the page JSX.
 *
 * @returns The rendered page, or `null` while the admin gate is being evaluated
 *     or when the current user is not an administrator (being redirected).
 */
function UsersUpdateContent() {
    const router = useRouter();
    const searchParams = useSearchParams();

    // Lookup key + editable fields (camelCase state per the Ochs rule).
    const [userId, setUserId] = useState('');
    const [firstName, setFirstName] = useState('');
    const [lastName, setLastName] = useState('');
    const [password, setPassword] = useState('');
    const [userType, setUserType] = useState<'A' | 'U'>('U');

    // Step / request state.
    const [fetched, setFetched] = useState(false);
    const [loading, setLoading] = useState(false);

    // Feedback state (ErrorAlert is reused for success/info via `alertSeverity`).
    const [errorValue, setErrorValue] = useState<unknown>(null);
    const [alertOpen, setAlertOpen] = useState(false);
    const [alertSeverity, setAlertSeverity] = useState<AlertColor>('error');

    // Admin-gate + one-shot deep-link auto-fetch state.
    const [accessChecked, setAccessChecked] = useState(false);
    const [isAdminUser, setIsAdminUser] = useState(false);
    const [autoFetchDone, setAutoFetchDone] = useState(false);

    /**
     * Surfaces a message through the shared ErrorAlert. Setters are stable, so
     * this callback has no dependencies and stays referentially constant.
     */
    const ShowAlert = useCallback((content: unknown, severity: AlertColor): void => {
        setErrorValue(content);
        setAlertSeverity(severity);
        setAlertOpen(true);
    }, []);

    // Admin gate. Runs client-side only (IsAdmin reads localStorage), so it lives
    // in an effect to avoid an SSR/hydration mismatch.
    useEffect(() => {
        // Client-side gating is UX only; the server require_admin (403) is the real security boundary.
        const adminFlag = IsAdmin();
        setIsAdminUser(adminFlag);
        setAccessChecked(true);
        if (!adminFlag) {
            router.replace(MENU_ROUTE);
        }
    }, [router]);

    /**
     * Step 1 — fetch a user by id and populate the editable fields. Shared by the
     * Fetch button and the deep-link auto-fetch effect (a single `idToLoad`
     * argument avoids stale-closure reads of `userId`).
     *
     * @param idToLoad - The User ID to look up.
     */
    const LoadUser = useCallback(async (idToLoad: string): Promise<void> => {
        const trimmedId = idToLoad.trim();
        if (!trimmedId) {
            ShowAlert(EMPTY_USER_ID_MESSAGE, 'error');
            return;
        }
        setLoading(true);
        try {
            const user: UserRead = await UsersApi.GetUser(trimmedId);
            setUserId(user.user_id);
            setFirstName(user.first_name);
            setLastName(user.last_name);
            setUserType(user.user_type);
            // Never populate the password from the response (UserRead has none) — R6/§0.7.7.
            setPassword('');
            setFetched(true);
            ShowAlert(FETCH_HINT_MESSAGE, 'info');
        } catch (caughtError) {
            // Narrow the unknown error with IsApiError (Ochs: catch specific, never swallow).
            setFetched(false);
            setFirstName('');
            setLastName('');
            setPassword('');
            setUserType('U');
            if (IsApiError(caughtError) && caughtError.status === HTTP_FORBIDDEN) {
                router.replace(MENU_ROUTE);
                return;
            }
            if (IsApiError(caughtError) && caughtError.status === HTTP_NOT_FOUND) {
                ShowAlert(USER_NOT_FOUND_MESSAGE, 'error');
                return;
            }
            ShowAlert(caughtError, 'error');
        } finally {
            setLoading(false);
        }
    }, [router, ShowAlert]);

    /** Fetch handler for the button / ENTER key (uses the typed-in User ID). */
    const HandleFetch = useCallback((): void => {
        void LoadUser(userId);
    }, [LoadUser, userId]);

    // Deep-link auto-fetch (mirrors legacy CDEMO-CU02-USR-SELECTED). Runs once,
    // only after the admin gate has passed, when `?userId=` is present.
    useEffect(() => {
        if (!accessChecked || !isAdminUser || autoFetchDone) {
            return;
        }
        setAutoFetchDone(true);
        const preselectedUserId = searchParams.get(USER_ID_QUERY_PARAM);
        if (preselectedUserId) {
            setUserId(preselectedUserId);
            void LoadUser(preselectedUserId);
        }
    }, [accessChecked, isAdminUser, autoFetchDone, searchParams, LoadUser]);

    /**
     * Routes a field edit to the matching state setter.
     *
     * @param name - The field name supplied by FormField.
     * @param value - The new string value.
     */
    function HandleChange(name: string, value: string): void {
        if (name === 'userId') {
            setUserId(value);
            return;
        }
        if (name === 'firstName') {
            setFirstName(value);
            return;
        }
        if (name === 'lastName') {
            setLastName(value);
            return;
        }
        if (name === 'password') {
            setPassword(value);
            return;
        }
        if (name === 'userType') {
            if (value === 'A' || value === 'U') {
                setUserType(value);
            }
            return;
        }
    }

    /**
     * Step 2 — validate and persist the edits. Password is OMITTED from the
     * payload when blank so the server keeps the existing hash (R6/§0.7.7).
     *
     * @param exitAfterSave - When true, navigate to the user list on success (F3).
     */
    async function SaveUser(exitAfterSave: boolean): Promise<void> {
        if (!fetched || !userId.trim()) {
            ShowAlert(EMPTY_USER_ID_MESSAGE, 'error');
            return;
        }
        if (!firstName.trim() || !lastName.trim() || !userType) {
            ShowAlert(REQUIRED_FIELDS_MESSAGE, 'error');
            return;
        }
        const userUpdate: UserUpdate = {
            first_name: firstName,
            last_name: lastName,
            user_type: userType,
        };
        if (password) {
            // Only transmit a password when the admin actually typed one; server hashes it.
            userUpdate.password = password;
        }
        setLoading(true);
        try {
            await UsersApi.UpdateUser(userId, userUpdate);
            // Drop the transient password immediately after a successful save.
            setPassword('');
            if (exitAfterSave) {
                router.push(USERS_ROUTE);
                return;
            }
            ShowAlert(`User ${userId} has been updated.`, 'success');
        } catch (caughtError) {
            // Narrow the unknown error with IsApiError (Ochs: catch specific, never swallow).
            if (IsApiError(caughtError) && caughtError.status === HTTP_FORBIDDEN) {
                router.replace(MENU_ROUTE);
                return;
            }
            ShowAlert(caughtError, 'error');
        } finally {
            setLoading(false);
        }
    }

    /** Save (F5) — persist and stay on the page. */
    function HandleSubmit(): void {
        void SaveUser(false);
    }

    /** Save & Exit (F3) — persist then return to the user list. */
    function HandleSaveExit(): void {
        void SaveUser(true);
    }

    /** Clear / Reset (F4) — reset every field back to the initial fetch state. */
    function HandleClear(): void {
        setUserId('');
        setFirstName('');
        setLastName('');
        setPassword('');
        setUserType('U');
        setFetched(false);
        setErrorValue(null);
        setAlertOpen(false);
    }

    /** Cancel / Back (F12) — leave without saving. */
    function HandleCancel(): void {
        router.push(USERS_ROUTE);
    }

    /** Dismiss the feedback alert. */
    function HandleCloseAlert(): void {
        setAlertOpen(false);
    }

    /**
     * Maps the legacy PF keys (COUSR02.bms footer) onto the page actions:
     * ENTER=Fetch, F3=Save&Exit, F4=Clear, F5=Save, F12=Cancel. Keyboard
     * shortcuts are a progressive enhancement; the buttons are the primary
     * affordance.
     *
     * @param event - The keyboard event bubbling up from a focused control.
     */
    function HandlePreventSubmit(event: FormEvent): void {
        // The page persists edits explicitly via the Save / Save & Exit buttons
        // (and the F5/F3 shortcuts), so an implicit native form submit (e.g.
        // Enter in a field) must never reload the page. Wrapping the editable
        // fields in a real <form> element places the Password field inside a
        // form for password managers and assistive tech (QA Issue 7c); this
        // handler neutralizes the form's default submit.
        event.preventDefault();
    }

    function HandleKeyDown(event: KeyboardEvent<HTMLElement>): void {
        if (event.key === 'Enter') {
            // Let a focused button/link/select own Enter; only the screen-level
            // fetch shortcut runs when Enter fires outside an interactive
            // control, so activation is never duplicated (QA M-28).
            if (ShouldSuppressActivationShortcut(event.key, event.target)) {
                return;
            }
            event.preventDefault();
            HandleFetch();
            return;
        }
        if (event.key === 'F5') {
            event.preventDefault();
            HandleSubmit();
            return;
        }
        if (event.key === 'F3') {
            event.preventDefault();
            HandleSaveExit();
            return;
        }
        if (event.key === 'F4') {
            event.preventDefault();
            HandleClear();
            return;
        }
        if (event.key === 'F12') {
            event.preventDefault();
            HandleCancel();
            return;
        }
    }

    if (!accessChecked) {
        // Gate not yet evaluated — render nothing to avoid a hydration flash.
        return null;
    }
    if (!isAdminUser) {
        // Non-admin is being redirected to /menu — render nothing meaningful.
        return null;
    }

    return (
        <Container maxWidth="sm" sx={{ mt: 3, mb: 3 }}>
            <Box
                component="form"
                noValidate
                onSubmit={HandlePreventSubmit}
                onKeyDown={HandleKeyDown}
            >
                <Typography variant="h5" component="h1" sx={{ mb: 3 }}>
                    Update User
                </Typography>
                <Stack spacing={3}>
                    <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                        <FormField
                            name="userId"
                            label="User ID"
                            value={userId}
                            onChange={HandleChange}
                            maxLength={USER_ID_MAX_LENGTH}
                            required
                            autoFocus
                            readOnly={fetched}
                            disabled={loading}
                            helperText={USER_ID_HELPER_TEXT}
                        />
                        <Button
                            variant="contained"
                            onClick={HandleFetch}
                            disabled={loading}
                        >
                            Fetch
                        </Button>
                    </Stack>

                    {fetched ? (
                        <Stack spacing={2}>
                            <FormField
                                name="firstName"
                                label="First Name"
                                value={firstName}
                                onChange={HandleChange}
                                maxLength={NAME_MAX_LENGTH}
                                required
                                disabled={loading}
                            />
                            <FormField
                                name="lastName"
                                label="Last Name"
                                value={lastName}
                                onChange={HandleChange}
                                maxLength={NAME_MAX_LENGTH}
                                required
                                disabled={loading}
                            />
                            <FormField
                                name="password"
                                label="Password"
                                type="password"
                                value={password}
                                onChange={HandleChange}
                                maxLength={PASSWORD_MAX_LENGTH}
                                disabled={loading}
                                helperText={PASSWORD_HELPER_TEXT}
                                autoComplete="new-password"
                            />
                            <FormField
                                name="userType"
                                label="User Type"
                                type="select"
                                value={userType}
                                onChange={HandleChange}
                                options={USER_TYPE_OPTIONS}
                                required
                                disabled={loading}
                            />
                        </Stack>
                    ) : null}

                    <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        <Button
                            variant="contained"
                            onClick={HandleSubmit}
                            disabled={loading || !fetched}
                        >
                            Save
                        </Button>
                        <Button
                            variant="contained"
                            color="secondary"
                            onClick={HandleSaveExit}
                            disabled={loading || !fetched}
                        >
                            Save &amp; Exit
                        </Button>
                        <Button
                            variant="outlined"
                            onClick={HandleClear}
                            disabled={loading}
                        >
                            Clear
                        </Button>
                        <Button
                            variant="text"
                            onClick={HandleCancel}
                            disabled={loading}
                        >
                            Cancel
                        </Button>
                    </Stack>
                </Stack>
            </Box>
            <ErrorAlert
                open={alertOpen}
                onClose={HandleCloseAlert}
                error={errorValue}
                severity={alertSeverity}
            />
        </Container>
    );
}
