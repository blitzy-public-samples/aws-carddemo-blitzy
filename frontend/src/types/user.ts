/**
 * User (security) DTOs.
 * Mirrors `backend/app/schemas/user.py`.
 * References: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA); admin screens COUSR00-COUSR03.
 * SECURITY: the plaintext SEC-USR-PWD is never exposed on reads; `password`
 * appears only on write payloads (create / update) and is hashed server-side.
 */

import type { PaginationParams } from './common';

/** User read model - no password ever returned. user_id max length 8. */
export interface UserRead {
    user_id: string;
    first_name: string;
    last_name: string;
    user_type: 'A' | 'U';
}

/** Create payload - password (max 8) is write-only. */
export interface UserCreate {
    user_id: string;
    first_name: string;
    last_name: string;
    password: string;
    user_type: 'A' | 'U';
}

/** Update payload - password optional; only re-hashed when provided. */
export interface UserUpdate {
    first_name: string;
    last_name: string;
    user_type: 'A' | 'U';
    password?: string;
}

/** Lightweight user row for admin list screens. */
export interface UserSummary {
    user_id: string;
    first_name: string;
    last_name: string;
    user_type: 'A' | 'U';
}

/**
 * User-list browse parameters (COUSR00C). Extends the pagination window with
 * the optional "Search User ID" prefix filter. Mirrors the backend
 * `GET /admin/users` `user_id` query parameter (backend/app/api/v1/users.py):
 * a non-empty value narrows the browse SERVER-SIDE across the whole user table
 * to ids beginning with it (case-insensitive), so a match on any page is found
 * -- fixing the former client-only current-page filter (QA I23). A blank or
 * omitted value applies no filter.
 */
export interface UserListParams extends PaginationParams {
    /** Optional case-insensitive User ID prefix (COUSR00C search box). */
    user_id?: string;
}
