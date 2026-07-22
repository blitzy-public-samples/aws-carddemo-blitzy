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

/** Editable fields for the card-update screen (COCRDUP). */
export interface CardUpdate {
    embossed_name: string;
    expiration_date: string;
    active_status: string;
}
