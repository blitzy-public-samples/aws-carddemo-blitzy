/**
 * Shared / cross-cutting API wire types.
 * Mirrors backend Pydantic schemas in `backend/app/schemas/common.py`.
 * References: app/cpy/CSMSG01Y.cpy, app/cpy/CSMSG02Y.cpy (message/error text);
 * default page size mirrors the legacy COCRDLIC card-browse limit (F-004, <= 7 rows/page).
 */

/** Default pagination page size - legacy card-browse limit (F-004, COCRDLIC). */
export const DEFAULT_PAGE_SIZE = 7;

/** Query parameters for paginated list endpoints. */
export interface PaginationParams {
    page: number;
    page_size: number;
}

/** Generic envelope returned by every paginated list endpoint. */
export interface PaginatedResponse<T> {
    items: T[];
    page: number;
    page_size: number;
    total_items: number;
    total_pages: number;
    has_next: boolean;
    has_previous: boolean;
}

/** Simple success / informational message payload. */
export interface MessageResponse {
    message: string;
}

/** Standard error payload surfaced to the UI (maps CSMSG01Y / CSMSG02Y codes). */
export interface ErrorResponse {
    message: string;
    code?: string;
    detail?: string;
}
