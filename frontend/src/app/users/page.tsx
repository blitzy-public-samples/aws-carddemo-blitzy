'use client';

/**
 * Users list page — modern replacement for BMS map COUSR00 (Tx CU00, program COUSR00C).
 * Source of truth: app/bms/COUSR00.bms + app/cpy-bms/COUSR00.CPY.
 * Admin-only (user_type === 'A'); server enforces require_admin (403), client guard is UX-only.
 *
 * Minimal Change Clause: preserves the legacy list/browse behavior (same fields shown, same
 * paginated browse semantics). The only intentional modernization is the UI paradigm (3270 grid
 * → MUI DataTable + Pagination) and the page size (legacy 10 rows → modern 7 rows, F-004).
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { KeyboardEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Stack, Container, Typography, Button } from '@mui/material';

import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';
import { UsersApi, IsApiError } from '@/lib/apiClient';
import { IsAdmin } from '@/lib/auth';
import type { UserSummary, UserListParams, PaginatedResponse } from '@/types';
import { DEFAULT_PAGE_SIZE } from '@/types';

/**
 * Rows rendered per browse page. Modern F-004 limit (7), replacing the legacy
 * COUSR00 fixed grid of 10 display rows. Defined in terms of DEFAULT_PAGE_SIZE
 * so the backend page cap and this page share a single source of truth.
 */
const ROWS_PER_PAGE = DEFAULT_PAGE_SIZE;

/** HTTP status the server's require_admin boundary returns for non-admin callers. */
const HTTP_FORBIDDEN = 403;

/** Admin role flag mirrored from CDEMO-USER-TYPE (COCOM01Y). */
const ADMIN_USER_TYPE = 'A';

/**
 * A well-formed empty page envelope. DataTable's `data` prop is required and
 * non-nullable, so this valid envelope is supplied before the first load
 * completes (and whenever the fetch has not yet populated `usersPage`).
 */
const EMPTY_USERS_PAGE: PaginatedResponse<UserSummary> = {
    items: [],
    page: 1,
    page_size: ROWS_PER_PAGE,
    total_items: 0,
    total_pages: 0,
    has_next: false,
    has_previous: false,
};

/**
 * Users list page (route `/users`). Renders a paginated, admin-only table of
 * application users with per-row Update / Delete actions plus an Add User action.
 *
 * @returns The rendered users list page, or `null` while the admin gate is being
 *     evaluated or when a non-admin is being redirected away.
 */
export default function UsersPage() {
    const router = useRouter();

    const [accessChecked, setAccessChecked] = useState(false);
    const [isAdminUser, setIsAdminUser] = useState(false);

    const [usersPage, setUsersPage] = useState<PaginatedResponse<UserSummary> | null>(null);
    const [currentPage, setCurrentPage] = useState(1);
    const [loading, setLoading] = useState(false);
    const [searchUserId, setSearchUserId] = useState('');
    const [errorValue, setErrorValue] = useState<unknown>(null);
    const [alertOpen, setAlertOpen] = useState(false);

    // Committed search term the browse is actually filtered by (QA I23). The
    // input box (`searchUserId` state) only updates this on Search/Clear/Enter,
    // never per keystroke, so the whole-table server search is not re-run on
    // every character. The ref mirrors it so the memoized LoadUsers reads the
    // latest committed value even on the same-tick Search/Clear refetch path
    // (the same pattern the card-list search uses).
    const searchTermRef = useRef<string>('');

    // Client-side gating is UX only; the server require_admin (403) is the real security boundary.
    useEffect(() => {
        const adminFlag = IsAdmin();
        setIsAdminUser(adminFlag);
        setAccessChecked(true);
        if (!adminFlag) {
            router.replace('/menu');
        }
    }, [router]);

    // Monotonic request-generation counter (QA M-05). Each LoadUsers call claims
    // the next generation; only the request whose captured generation still
    // equals `requestGenerationRef.current` on settle may commit list data or
    // surface an error, so a slow earlier page cannot overwrite a newer one and
    // no state update lands after unmount. The 403 redirect is exempt: losing
    // admin access is terminal navigation, correct regardless of generation.
    const requestGenerationRef = useRef<number>(0);

    const LoadUsers = useCallback(async () => {
        const requestGeneration = requestGenerationRef.current + 1;
        requestGenerationRef.current = requestGeneration;
        setLoading(true);
        try {
            // Read the COMMITTED search term from the ref (see its declaration)
            // so the server-side prefix search narrows the WHOLE user table, not
            // just the current page (QA I23). The term is included ONLY when
            // non-empty, so an unfiltered browse sends exactly {page, page_size}
            // (the real contract) and a search adds the user_id prefix.
            const listParams: Partial<UserListParams> = {
                page: currentPage,
                page_size: ROWS_PER_PAGE,
            };
            const committedSearch = searchTermRef.current.trim();
            if (committedSearch) {
                listParams.user_id = committedSearch;
            }
            const result = await UsersApi.ListUsers(listParams);
            // Only the latest request may commit its data (QA M-05).
            if (requestGenerationRef.current !== requestGeneration) {
                return;
            }
            setUsersPage(result);
        } catch (caughtError) {
            // Narrow the caught value with IsApiError (the TypeScript equivalent of catching a
            // specific exception) rather than blindly swallowing an unknown error.
            // The 403 redirect fires regardless of generation (terminal navigation).
            if (IsApiError(caughtError) && caughtError.status === HTTP_FORBIDDEN) {
                router.replace('/menu');
                return;
            }
            // Discard a superseded request's error too (QA M-05).
            if (requestGenerationRef.current !== requestGeneration) {
                return;
            }
            setErrorValue(caughtError);
            setAlertOpen(true);
        } finally {
            // Only the latest request owns the shared loading flag (QA M-05).
            if (requestGenerationRef.current === requestGeneration) {
                setLoading(false);
            }
        }
    }, [currentPage, router]);

    useEffect(() => {
        if (isAdminUser) {
            LoadUsers();
        }
        // Invalidate any in-flight request when the page changes or unmounts so
        // its late resolve cannot update state afterwards (QA M-05).
        return () => {
            requestGenerationRef.current += 1;
        };
    }, [isAdminUser, LoadUsers]);

    const HandlePageChange = (page: number) => {
        setCurrentPage(page);
    };

    // Editing the box only updates the input value; it does NOT commit the
    // search or fetch. The whole-table server search runs only when the admin
    // presses Search/Enter (or Clear), so typing does not fire a request per
    // keystroke (QA I23).
    const HandleSearchChange = (name: string, value: string) => {
        setSearchUserId(value);
    };

    /**
     * Commits the current box value as the active search term and returns to
     * the first page. The committed term is mirrored into the ref synchronously
     * so the page-1 refetch path (below) reads it immediately. When already on
     * page 1, setting the same page number would not re-run the fetch effect, so
     * LoadUsers is invoked directly.
     */
    const HandleSearch = () => {
        const committed = searchUserId.trim();
        searchTermRef.current = committed;
        if (currentPage === 1) {
            void LoadUsers();
        } else {
            setCurrentPage(1);
        }
    };

    /**
     * Clears the search box and the committed term, returning to the unfiltered
     * first page. The ref is cleared synchronously (the page-1 path may refetch
     * this same tick before any state-sync effect runs), mirroring the card-list
     * Clear semantics.
     */
    const HandleClear = () => {
        setSearchUserId('');
        searchTermRef.current = '';
        if (currentPage === 1) {
            void LoadUsers();
        } else {
            setCurrentPage(1);
        }
    };

    /**
     * Restores the 3270 Enter-key affordance: pressing Return in the search box
     * commits the search, exactly like clicking Search (QA I23 / I25).
     *
     * @param event - The keyboard event from the search field.
     */
    const HandleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            HandleSearch();
        }
    };

    const HandleAddUser = () => {
        router.push('/users/add');
    };

    const HandleEditUser = (userId: string) => {
        router.push(`/users/update?userId=${encodeURIComponent(userId)}`);
    };

    const HandleDeleteUser = (userId: string) => {
        router.push(`/users/delete?userId=${encodeURIComponent(userId)}`);
    };

    const HandleCloseError = () => {
        setAlertOpen(false);
    };

    // Columns mirror COUSR00: User ID / First Name / Last Name / Type. The legacy
    // `Sel` selection column becomes the per-row action buttons (Update / Delete).
    const columns: ColumnDef<UserSummary>[] = [
        { key: 'user_id', header: 'User ID' },
        { key: 'first_name', header: 'First Name' },
        { key: 'last_name', header: 'Last Name' },
        {
            key: 'user_type',
            header: 'Type',
            render: (row) => (row.user_type === ADMIN_USER_TYPE ? 'Admin' : 'User'),
        },
        {
            key: 'actions',
            header: 'Actions',
            align: 'right',
            render: (row) => (
                <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                    <Button
                        size="small"
                        variant="outlined"
                        onClick={() => HandleEditUser(row.user_id)}
                        aria-label={`Update user ${row.user_id}`}
                    >
                        Update
                    </Button>
                    <Button
                        size="small"
                        variant="outlined"
                        color="error"
                        onClick={() => HandleDeleteUser(row.user_id)}
                        aria-label={`Delete user ${row.user_id}`}
                    >
                        Delete
                    </Button>
                </Stack>
            ),
        },
    ];

    // The search now runs SERVER-SIDE over the whole user table (QA I23), so the
    // page returned by the API is already the correct, filtered browse -- there
    // is no client-side re-filtering. Render whatever page the backend returned.
    const displayedPage = usersPage ?? EMPTY_USERS_PAGE;

    if (!accessChecked) {
        // Gate not yet evaluated: render nothing to avoid an SSR/hydration flash.
        return null;
    }
    if (!isAdminUser) {
        // Non-admin is being redirected to /menu; render nothing meaningful.
        return null;
    }

    return (
        <Container sx={{ mt: 3, mb: 3 }}>
            <Stack
                direction="row"
                sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}
            >
                <Typography variant="h5" component="h1">
                    List Users
                </Typography>
                <Button variant="contained" color="primary" onClick={HandleAddUser}>
                    Add User
                </Button>
            </Stack>

            <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                sx={{ mb: 2, alignItems: { sm: 'flex-start' } }}
            >
                <FormField
                    name="searchUserId"
                    label="Search User ID"
                    value={searchUserId}
                    onChange={HandleSearchChange}
                    onKeyDown={HandleSearchKeyDown}
                    maxLength={8}
                    fullWidth={false}
                />
                <Button variant="contained" onClick={HandleSearch}>
                    Search
                </Button>
                <Button variant="outlined" onClick={HandleClear}>
                    Clear
                </Button>
            </Stack>

            <DataTable<UserSummary>
                columns={columns}
                data={displayedPage}
                onPageChange={HandlePageChange}
                getRowKey={(row) => row.user_id}
                loading={loading}
                emptyMessage="No users found."
            />

            <ErrorAlert open={alertOpen} onClose={HandleCloseError} error={errorValue} />
        </Container>
    );
}
