'use client';

/*
 * Delete User page — modern replacement for the legacy 3270 "Delete User" screen.
 *
 * Traceability (Ochs Test Rule §0.8.1 — each ported unit references its origin):
 *   Origin    : BMS map COUSR03 / mapset COUSR3A, CICS transaction CU03,
 *               COBOL program COUSR03C.
 *   REFERENCE : app/bms/COUSR03.bms, app/cpy-bms/COUSR03.CPY
 *               (business behavior derived from app/cbl/COUSR03C.cbl).
 *   Purpose   : Delete User — lookup by User ID, display read-only details,
 *               confirm, delete.
 *   Access    : ADMIN-ONLY (user_type === 'A'); one of the COUSR00–COUSR03
 *               admin-gated screens (AAP §0.8.1, §0.4.4).
 *
 * This is a Client Component: it uses React hooks, client-side navigation, and
 * localStorage-backed identity (IsAdmin), none of which run on the server.
 *
 * SECURITY: COUSR03 carries NO password field; UserRead exposes none. A password
 * is never requested, rendered, logged, or placed in the confirmation dialog.
 */

import { useState, useEffect, useCallback, useRef, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import {
    Box,
    Stack,
    Container,
    Typography,
    Button,
    CircularProgress,
} from '@mui/material';

import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ErrorAlert } from '@/components/ErrorAlert';
import { FormField } from '@/components/FormField';
import { UsersApi, IsApiError } from '@/lib/apiClient';
import { IsAdmin } from '@/lib/auth';
import type { UserRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** On-screen User ID buffer width — legacy USRIDIN PIC X(8) (COUSR03.CPY). */
const USER_ID_MAX_LENGTH = 8;

/** Admin user-list route; the PF3/PF12 "Back" target of COUSR03. */
const USERS_LIST_ROUTE = '/users';

/** Regular-menu route a non-admin is redirected to (client-side UX gate). */
const MENU_ROUTE = '/menu';

/** HTTP 403 — the server `require_admin` boundary (authoritative gate). */
const HTTP_FORBIDDEN = 403;

/** HTTP 404 — the looked-up User ID does not exist. */
const HTTP_NOT_FOUND = 404;

/* Message text mirroring COUSR03C (self-documenting; no scattered literals). */

/** Empty-id guard message (COUSR03C rejects a blank User ID before any read). */
const MSG_EMPTY_USER_ID = 'User ID can NOT be empty...';

/** Confirm hint shown once a user is loaded (modern PF5-delete equivalent). */
const MSG_CONFIRM_HINT = 'Press Delete to remove this user, or Cancel to abort.';

/** Not-found message when the account/user READ returns no record. */
const MSG_USER_NOT_FOUND = 'User ID NOT found...';

/** Delete-success prefix — composed as `User <id> has been deleted ...`. */
const MSG_DELETE_SUCCESS_PREFIX = 'User ';

/** Delete-success suffix — composed as `User <id> has been deleted ...`. */
const MSG_DELETE_SUCCESS_SUFFIX = ' has been deleted ...';

/* ------------------------------------------------------------------------- */
/* Suspense fallback.                                                        */
/* ------------------------------------------------------------------------- */

/**
 * Lightweight fallback rendered while the Suspense boundary resolves. Next.js 16
 * requires every `useSearchParams()` consumer to sit beneath a Suspense boundary,
 * so the search-param-reading content is isolated behind this fallback.
 *
 * @returns A centered progress indicator.
 */
function DeletePageFallback() {
    return (
        <Container maxWidth="sm">
            <Stack sx={{ alignItems: 'center', py: 6 }}>
                <CircularProgress aria-label="Loading" />
            </Stack>
        </Container>
    );
}

/* ------------------------------------------------------------------------- */
/* Default export — Suspense wrapper (Next.js 16 useSearchParams rule).      */
/* ------------------------------------------------------------------------- */

/**
 * Route entry point for `/users/delete`. It renders only the Suspense boundary;
 * all state, effects, and the search-param read live in {@link UsersDeleteContent}
 * so `next build` does not fail on the client-side-rendering bailout.
 *
 * @returns The Suspense-wrapped delete-user page.
 */
export default function UsersDeletePage() {
    return (
        <Suspense fallback={<DeletePageFallback />}>
            <UsersDeleteContent />
        </Suspense>
    );
}

/* ------------------------------------------------------------------------- */
/* Inner content — all state, effects, handlers, and page JSX.               */
/* ------------------------------------------------------------------------- */

/**
 * The delete-user screen body. Holds every hook and handler and is the sole
 * caller of `useSearchParams()` (which supplies the deep-link `userId`). Renders
 * page content only — the AppBar/Drawer shell is supplied by the root layout.
 *
 * @returns The delete-user page content, or `null` until the admin gate resolves.
 */
function UsersDeleteContent() {
    const router = useRouter();
    const searchParams = useSearchParams();

    // State (camelCase per Ochs).
    const [userId, setUserId] = useState<string>('');
    const [fetchedUser, setFetchedUser] = useState<UserRead | null>(null);
    const [infoMessage, setInfoMessage] = useState<string>('');
    const [errorState, setErrorState] = useState<unknown>(null);
    const [alertOpen, setAlertOpen] = useState<boolean>(false);
    const [dialogOpen, setDialogOpen] = useState<boolean>(false);
    const [fetchLoading, setFetchLoading] = useState<boolean>(false);
    const [deleteLoading, setDeleteLoading] = useState<boolean>(false);
    const [accessChecked, setAccessChecked] = useState<boolean>(false);

    // Fires the deep-link auto-fetch exactly once (survives re-renders).
    const deepLinkHandledRef = useRef<boolean>(false);

    /**
     * Maps a caught error to the correct UX: an admin 403 redirects to the menu
     * (the real boundary is the server), a 404 shows the friendly not-found text,
     * and anything else is surfaced through the ErrorAlert. Catches are narrowed
     * with `IsApiError` (Ochs: specific error handling, never a blanket cast).
     *
     * @param err - The value thrown by an awaited `UsersApi` call.
     */
    const HandleApiFailure = useCallback(
        (err: unknown): void => {
            if (IsApiError(err) && err.status === HTTP_FORBIDDEN) {
                router.replace(MENU_ROUTE);
                return;
            }
            if (IsApiError(err) && err.status === HTTP_NOT_FOUND) {
                setFetchedUser(null);
                setInfoMessage('');
                setErrorState(MSG_USER_NOT_FOUND);
                setAlertOpen(true);
                return;
            }
            setErrorState(err);
            setAlertOpen(true);
        },
        [router],
    );

    /**
     * Relays the FormField edit as `onChange(name, value)`; clears any stale
     * details and info text so the previous lookup cannot be mistaken for the
     * newly-typed id.
     *
     * @param _name - The field name (single field here; unused).
     * @param value - The new User ID value.
     */
    const HandleChange = useCallback((_name: string, value: string): void => {
        setUserId(value);
        setInfoMessage('');
        setFetchedUser(null);
    }, []);

    /**
     * Looks up a user by id — mirrors COUSR03C `PROCESS-ENTER-KEY`. An empty id is
     * rejected before any request (the legacy blank-id guard); a successful read
     * populates the read-only details and shows the confirm hint.
     *
     * @param explicitId - Optional id (used by the deep-link effect); falls back
     *                      to the current `userId` state.
     */
    const HandleFetch = useCallback(
        async (explicitId?: string): Promise<void> => {
            const lookupId = (explicitId ?? userId).trim();
            if (lookupId.length === 0) {
                setInfoMessage('');
                setFetchedUser(null);
                setErrorState(MSG_EMPTY_USER_ID);
                setAlertOpen(true);
                return;
            }
            setFetchLoading(true);
            try {
                const user = await UsersApi.GetUser(lookupId);
                setFetchedUser(user);
                setInfoMessage(MSG_CONFIRM_HINT);
            } catch (err) {
                HandleApiFailure(err);
            } finally {
                setFetchLoading(false);
            }
        },
        [userId, HandleApiFailure],
    );

    /**
     * Deletes the loaded user — mirrors COUSR03C `DELETE-USER-INFO`. DeleteUser
     * returns 204 with no body, so nothing is read from the response. On success
     * it reports the deletion and returns to the admin user list.
     */
    const HandleDelete = useCallback(async (): Promise<void> => {
        if (!fetchedUser || userId.trim().length === 0) {
            setDialogOpen(false);
            return;
        }
        const deletedId = fetchedUser.user_id;
        setDeleteLoading(true);
        try {
            await UsersApi.DeleteUser(deletedId);
            setDialogOpen(false);
            setInfoMessage(
                `${MSG_DELETE_SUCCESS_PREFIX}${deletedId}${MSG_DELETE_SUCCESS_SUFFIX}`,
            );
            setFetchedUser(null);
            router.push(USERS_LIST_ROUTE);
            router.refresh();
        } catch (err) {
            setDialogOpen(false);
            HandleApiFailure(err);
        } finally {
            setDeleteLoading(false);
        }
    }, [fetchedUser, userId, router, HandleApiFailure]);

    /**
     * Opens the delete-confirmation dialog (PF5 equivalent). A confirmation is
     * only meaningful once a user is loaded; otherwise it surfaces the lookup
     * guard message rather than opening an empty dialog.
     */
    const HandleOpenConfirm = useCallback((): void => {
        if (!fetchedUser) {
            setInfoMessage('');
            setErrorState(MSG_EMPTY_USER_ID);
            setAlertOpen(true);
            return;
        }
        setDialogOpen(true);
    }, [fetchedUser]);

    /** Closes the dialog without deleting (Cancel / Escape / backdrop). */
    const HandleCancel = useCallback((): void => {
        setDialogOpen(false);
    }, []);

    /** Resets the whole screen — mirrors COUSR03C `CLEAR-CURRENT-SCREEN` (PF4). */
    const HandleClear = useCallback((): void => {
        setUserId('');
        setFetchedUser(null);
        setInfoMessage('');
        setErrorState(null);
        setAlertOpen(false);
        setDialogOpen(false);
    }, []);

    /** Returns to the admin user list — PF3/PF12 "Back". */
    const HandleBack = useCallback((): void => {
        router.push(USERS_LIST_ROUTE);
    }, [router]);

    // Admin gate (§0.4.4 role-based rendering + §0.8.1 admin-only).
    useEffect(() => {
        // client gating is UX only; server require_admin (403) is the real boundary
        if (!IsAdmin()) {
            router.replace(MENU_ROUTE);
            return;
        }
        setAccessChecked(true);
    }, [router]);

    // Deep-link auto-fetch (COUSR03C MAIN-PARA CDEMO-CU03-USR-SELECTED). Runs once
    // when the list page navigates in via /users/delete?userId=<encoded id>.
    useEffect(() => {
        if (!accessChecked || deepLinkHandledRef.current) {
            return;
        }
        const initialId = searchParams.get('userId');
        if (initialId && initialId.trim().length > 0) {
            deepLinkHandledRef.current = true;
            setUserId(initialId);
            void HandleFetch(initialId);
        }
    }, [accessChecked, searchParams, HandleFetch]);

    // PF-key mapping (COUSR03.bms footer: ENTER=Fetch F3=Back F4=Clear F5=Delete).
    useEffect(() => {
        if (!accessChecked) {
            return;
        }
        function HandleKeyDown(event: KeyboardEvent): void {
            if (event.key === 'Enter') {
                if (!dialogOpen) {
                    void HandleFetch();
                }
            } else if (event.key === 'F5') {
                event.preventDefault();
                HandleOpenConfirm();
            } else if (event.key === 'F3') {
                event.preventDefault();
                HandleBack();
            } else if (event.key === 'F4') {
                event.preventDefault();
                HandleClear();
            } else if (event.key === 'Escape') {
                if (dialogOpen) {
                    HandleCancel();
                }
            }
        }
        window.addEventListener('keydown', HandleKeyDown);
        return () => {
            window.removeEventListener('keydown', HandleKeyDown);
        };
    }, [
        accessChecked,
        dialogOpen,
        HandleFetch,
        HandleOpenConfirm,
        HandleBack,
        HandleClear,
        HandleCancel,
    ]);

    // Render nothing until the admin check runs (prevents a content flash for a
    // non-admin who is about to be redirected, and avoids a hydration mismatch).
    if (!accessChecked) {
        return null;
    }

    // Skip autoFocus when arriving via a deep link so focus is not stolen.
    const deepLinkId = searchParams.get('userId');
    const userTypeLabel =
        fetchedUser && fetchedUser.user_type === 'A' ? 'Admin' : 'User';

    return (
        <Container maxWidth="sm">
            <Stack spacing={3} sx={{ py: 3 }}>
                <Typography variant="h4" component="h1">
                    Delete User
                </Typography>

                <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                    <FormField
                        name="user_id"
                        label="User ID"
                        value={userId}
                        onChange={HandleChange}
                        maxLength={USER_ID_MAX_LENGTH}
                        autoFocus={!deepLinkId}
                    />
                    <Button
                        variant="contained"
                        color="primary"
                        onClick={() => {
                            void HandleFetch();
                        }}
                        disabled={fetchLoading}
                    >
                        Fetch
                    </Button>
                </Stack>

                {fetchedUser ? (
                    <Stack spacing={2}>
                        <Box>
                            <Typography variant="subtitle2" color="text.secondary">
                                First Name
                            </Typography>
                            <Typography variant="body1">
                                {fetchedUser.first_name}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography variant="subtitle2" color="text.secondary">
                                Last Name
                            </Typography>
                            <Typography variant="body1">
                                {fetchedUser.last_name}
                            </Typography>
                        </Box>
                        <Box>
                            <Typography variant="subtitle2" color="text.secondary">
                                User Type
                            </Typography>
                            <Typography variant="body1">{userTypeLabel}</Typography>
                        </Box>
                    </Stack>
                ) : null}

                {infoMessage ? (
                    <Typography variant="body2" color="text.secondary">
                        {infoMessage}
                    </Typography>
                ) : null}

                <Stack direction="row" spacing={2}>
                    <Button
                        variant="contained"
                        color="error"
                        onClick={HandleOpenConfirm}
                        disabled={!fetchedUser || deleteLoading}
                    >
                        Delete
                    </Button>
                    <Button variant="outlined" onClick={HandleClear}>
                        Clear
                    </Button>
                    <Button variant="text" onClick={HandleBack}>
                        Back
                    </Button>
                </Stack>
            </Stack>

            <ConfirmDialog
                open={dialogOpen}
                title="Delete User"
                confirmLabel="Delete"
                cancelLabel="Cancel"
                confirmColor="error"
                loading={deleteLoading}
                onConfirm={() => {
                    void HandleDelete();
                }}
                onCancel={HandleCancel}
            >
                <Typography variant="body2">
                    You are about to delete user {fetchedUser?.user_id} (
                    {fetchedUser?.first_name} {fetchedUser?.last_name},{' '}
                    {userTypeLabel}). This action cannot be undone.
                </Typography>
            </ConfirmDialog>

            <ErrorAlert
                open={alertOpen}
                onClose={() => setAlertOpen(false)}
                error={errorState}
            />
        </Container>
    );
}
