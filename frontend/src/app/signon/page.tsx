'use client';

/*
 * signon/page.tsx -- Signon page for the `/signon` route.
 *
 * TRACEABILITY (Minimal Change Clause, AAP Section 0.8.1): this page is the
 * modern Next.js / Material UI replacement for the legacy 3270 signon screen
 * BMS map COSGN00 (CICS transaction CC00, COBOL program COSGN00C). It collects
 * a User ID and Password, applies the same required-field edits the legacy
 * program applied (COSGN00C PROCESS-ENTER-KEY, L117-140), then delegates to the
 * server for authentication.
 *
 * All AUTHENTICATION logic is SERVER-SIDE: credential verification, the legacy
 * UPPER-CASE of both fields (COSGN00C L132-136), and role resolution are owned
 * by the backend auth_service. This page only collects and validates input and
 * displays the server's responses; on success it routes by role exactly as the
 * legacy program did (COSGN00C L230-240: admin -> COADM01C, else -> COMEN01C).
 */

import { useState } from 'react';
import type { FormEvent } from 'react';
import { useRouter } from 'next/navigation';

import Box from '@mui/material/Box';
import Container from '@mui/material/Container';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Stack from '@mui/material/Stack';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';

import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { Login } from '@/lib/auth';
import { FocusFieldByName } from '@/lib/keyboard';
import type { LoginRequest, CurrentUser } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs Test Rule: ALL_UPPERCASE with underscores).        */
/* ------------------------------------------------------------------------- */

/** Maximum User ID length -- from COSGN00.CPY `USERIDI PIC X(8)`. */
const USER_ID_MAX_LENGTH = 8;

/** Maximum Password length -- from COSGN00.CPY `PASSWDI PIC X(8)`. */
const PASSWORD_MAX_LENGTH = 8;

/** Empty-User-ID edit message -- verbatim from COSGN00C.cbl L120. */
const EMPTY_USER_ID_MESSAGE = 'Please enter User ID ...';

/** Empty-Password edit message -- verbatim from COSGN00C.cbl L125. */
const EMPTY_PASSWORD_MESSAGE = 'Please enter Password ...';

/**
 * Per-field helper texts shown on the offending input when a required-field
 * edit fails (FINDING-10, WCAG 3.3.1/4.1.2). They are INTENTIONALLY distinct
 * from the verbatim legacy toast messages above: the toast preserves the exact
 * COSGN00C wording, while these short, field-scoped labels are what a screen
 * reader announces via `aria-describedby` when the field is marked
 * `aria-invalid` -- so the error is tied to the specific control, not only to a
 * transient Snackbar.
 */
const REQUIRED_USER_ID_HELPER = 'User ID is required';
const REQUIRED_PASSWORD_HELPER = 'Password is required';

/** Regular-user landing route -- legacy XCTL to COMEN01C (COSGN00C L237). */
const MENU_ROUTE = '/menu';

/** Admin landing route -- legacy XCTL to COADM01C (COSGN00C L232). */
const ADMIN_ROUTE = '/admin';

/** Admin role discriminator -- matches `CurrentUser.user_type === 'A'`. */
const ADMIN_USER_TYPE = 'A';

/* ------------------------------------------------------------------------- */
/* Page component.                                                           */
/* ------------------------------------------------------------------------- */

/**
 * Renders the centered CardDemo signon form and drives the login flow.
 *
 * Behavior (ported 1:1 from COSGN00C): required-field validation runs User ID
 * first then Password with the verbatim legacy messages; a valid submit calls
 * the server-side {@link Login} helper and, on success, routes by role
 * (`user_type === 'A'` -> `/admin`, otherwise -> `/menu`). A rejected login
 * (for example a 401 with the server's "Wrong Password" / "User not found"
 * message) is surfaced in the {@link ErrorAlert} without leaving `/signon`.
 *
 * Network / CORS failures (QA finding M-11): when the login request never reaches
 * the server -- a dropped network, a timeout, or a CORS / host-alias rejection the
 * browser blocks before any response body -- the {@link Login} helper rejects with
 * an ApiError whose message is the actionable "Unable to reach the server..."
 * text (normalized in `apiClient`), which is surfaced here in the same
 * {@link ErrorAlert} so the user is never left without feedback.
 *
 * @returns The signon page element.
 */
export default function SignonPage() {
    const router = useRouter();
    const [userId, setUserId] = useState('');
    const [password, setPassword] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [alertOpen, setAlertOpen] = useState(false);
    const [errorContent, setErrorContent] = useState<unknown>(null);
    // Per-field validation helper text (empty string = no error). Drives the
    // FormField `error`/`helperText` props so the invalid input is programmatically
    // marked (aria-invalid + aria-describedby), in addition to the toast.
    const [fieldErrors, setFieldErrors] = useState<{ userId: string; password: string }>({
        userId: '',
        password: '',
    });

    /**
     * Routes a {@link FormField} change to the matching state setter. The
     * handler signature is `(name, value)` to match `FormField.onChange`.
     *
     * @param name - The field name (`'userId'` or `'password'`).
     * @param value - The new raw string value (never coerced to a number).
     */
    const HandleChange = (name: string, value: string) => {
        if (name === 'userId') {
            setUserId(value);
            // Clear the field's validation error as soon as it is edited so the
            // aria-invalid state and helper text do not linger after correction.
            setFieldErrors((previous) => ({ ...previous, userId: '' }));
        } else if (name === 'password') {
            setPassword(value);
            setFieldErrors((previous) => ({ ...previous, password: '' }));
        }
    };

    /**
     * Surfaces a message or caught error in the {@link ErrorAlert}.
     *
     * @param content - A verbatim validation string or a caught login error.
     */
    const ShowError = (content: unknown) => {
        setErrorContent(content);
        setAlertOpen(true);
    };

    /** Dismisses the {@link ErrorAlert}; passed to its `onClose`. */
    const HandleAlertClose = () => {
        setAlertOpen(false);
    };

    /**
     * Validates input and performs the server-side login. Mirrors COSGN00C
     * PROCESS-ENTER-KEY (L117-140) then READ-USER-SEC-FILE (L209-257): the
     * User ID edit is checked before the Password edit, then the credentials
     * are sent to the server, which authenticates and returns the role used
     * here for landing-page routing.
     *
     * @param event - The native form submit event (Enter or the Sign On button).
     */
    const HandleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!userId) {
            // Mark the User ID field invalid (aria-invalid + aria-describedby via
            // the helper text) AND keep the verbatim legacy toast.
            setFieldErrors({ userId: REQUIRED_USER_ID_HELPER, password: '' });
            ShowError(EMPTY_USER_ID_MESSAGE);
            // Move the cursor to the offending field (QA Issue 5); the verbatim
            // legacy COSGN00C message itself is preserved unchanged (D1 fidelity).
            FocusFieldByName('userId');
            return;
        }
        if (!password) {
            setFieldErrors({ userId: '', password: REQUIRED_PASSWORD_HELPER });
            ShowError(EMPTY_PASSWORD_MESSAGE);
            FocusFieldByName('password');
            return;
        }
        // Both fields valid: clear any prior field-level error before submitting.
        setFieldErrors({ userId: '', password: '' });
        const credentials: LoginRequest = { user_id: userId, password };
        setSubmitting(true);
        try {
            const currentUser: CurrentUser = await Login(credentials);
            if (currentUser.user_type === ADMIN_USER_TYPE) {
                router.push(ADMIN_ROUTE);
            } else {
                router.push(MENU_ROUTE);
            }
        } catch (loginError) {
            ShowError(loginError);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Box
            sx={(theme) => ({
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                // Full-viewport height is required for vertical centering of the
                // signon card. It is the named `theme.layout.fullViewportHeight`
                // token (QA M-25), not a hardcoded `100vh` literal.
                minHeight: theme.layout.fullViewportHeight,
                p: 2,
            })}
        >
            <Container maxWidth="xs">
                <Card sx={{ width: 1 }}>
                    <CardContent>
                        <Box component="form" onSubmit={HandleSubmit} noValidate>
                            <Stack spacing={3}>
                                <Typography variant="h5" component="h1" align="center">
                                    CardDemo Sign On
                                </Typography>
                                <FormField
                                    name="userId"
                                    label="User ID"
                                    value={userId}
                                    onChange={HandleChange}
                                    type="text"
                                    maxLength={USER_ID_MAX_LENGTH}
                                    required
                                    autoFocus
                                    autoComplete="username"
                                    disabled={submitting}
                                    error={Boolean(fieldErrors.userId)}
                                    helperText={fieldErrors.userId || undefined}
                                />
                                <FormField
                                    name="password"
                                    label="Password"
                                    value={password}
                                    onChange={HandleChange}
                                    type="password"
                                    maxLength={PASSWORD_MAX_LENGTH}
                                    required
                                    autoComplete="current-password"
                                    disabled={submitting}
                                    error={Boolean(fieldErrors.password)}
                                    helperText={fieldErrors.password || undefined}
                                />
                                <Button
                                    type="submit"
                                    variant="contained"
                                    color="primary"
                                    fullWidth
                                    disabled={submitting}
                                >
                                    Sign On
                                </Button>
                            </Stack>
                        </Box>
                    </CardContent>
                </Card>
            </Container>
            <ErrorAlert
                open={alertOpen}
                onClose={HandleAlertClose}
                error={errorContent}
            />
        </Box>
    );
}
