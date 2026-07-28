/**
 * Card DTOs.
 * Mirrors `backend/app/schemas/card.py`.
 * References: app/cpy/CVACT02Y.cpy (CARD-RECORD); screens COCRDLI / COCRDSL / COCRDUP.
 * SECURITY: `card_num` arrives MASKED (last-4). There is intentionally NO CVV
 * field on any type - CARD-CVV-CD is never persisted to a response nor returned.
 */

import type { PaginationParams } from './common';

/**
 * Card-list browse parameters (COCRDLI). Extends the pagination window with the
 * two optional search filters the card-list screen exposes. Mirrors the backend
 * `CardListParams` (backend/app/services/card_service.py). Both filters are
 * optional; a blank/omitted value applies no filter (QA C3).
 */
export interface CardListParams extends PaginationParams {
    /** Optional owning-account filter (COCRDLI "Account ID" search box). */
    acct_id?: string;
    /** Optional exact card-number filter (COCRDLI "Card Number" search box). */
    card_num?: string;
}

/** Card read model. card_num max length 16 (masked); acct_id 11. */
export interface CardRead {
    card_num: string;         // X(16) - MASKED (last-4)
    acct_id: string;          // 9(11)
    embossed_name: string;    // X(50)
    expiration_date: string;  // ISO date
    active_status: string;    // X(1)
}

/** Lightweight card row for the card-list screen (COCRDLI). */
export interface CardSummary {
    card_num: string;         // MASKED
    acct_id: string;
    embossed_name: string;
    active_status: string;
}

/**
 * Client-echoed before-image of the editable card fields (COCRDUP).
 * Mirrors the backend `CardBeforeImage` schema (backend/app/schemas/card.py).
 *
 * This is the optimistic-lock token that reproduces the legacy COCRDUPC
 * READ-for-UPDATE -> REWRITE lost-update guard (AAP §0.7.4 / 9300-CHECK-CHANGE-IN-REC).
 * The ORM `Card` model carries no `version`/`updated_at` column (Minimal Change
 * Clause), so the client instead echoes the values it last read for the editable
 * fields. The service re-reads the `SELECT ... FOR UPDATE` locked row and compares
 * each supplied field; any divergence is a concurrent modification and is rejected
 * with HTTP 409 (QA finding C06).
 *
 * `embossed_name` and `active_status` are REQUIRED (they always exist on a read
 * card and anchor the lost-update check); `expiration_date` is optional (compared
 * only when supplied) so an unsent nullable date never manufactures a false
 * conflict. Dates are ISO strings.
 */
export interface CardBeforeImage {
    embossed_name: string;      // X(50) - REQUIRED
    active_status: string;      // X(1) - REQUIRED
    expiration_date?: string;   // ISO date - optional (compared only when sent)
}

/**
 * Editable fields for the card-update screen (COCRDUP).
 * Mirrors the backend `CardUpdate` schema exactly (extra="forbid").
 *
 * `before_image` is REQUIRED (QA finding C06): the backend `CardUpdate` schema
 * declares it as a mandatory nested echo used for the optimistic-lock compare,
 * so omitting it makes every save fail with HTTP 422. It is populated from the
 * pre-edit card snapshot and is a control field, not an edited value.
 */
export interface CardUpdate {
    before_image: CardBeforeImage;
    embossed_name: string;
    expiration_date: string;
    active_status: string;
}
