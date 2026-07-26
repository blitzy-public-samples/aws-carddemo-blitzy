'use client';

/*
 * apiClient.ts -- The single, shared axios instance ("the API client singleton")
 * plus the typed REST endpoint wrappers used by every frontend page/component to
 * talk to the FastAPI backend.
 *
 * TRACEABILITY: this module is the modern replacement for legacy CICS transaction
 * dispatch. On the mainframe each of the 17 BMS screens (app/bms/*.bms) invoked a
 * CICS transaction that ran a COBOL program; here every screen instead calls a
 * typed REST method defined below, which reaches the backend over HTTP/JSON.
 *
 * The identity/role that legacy programs propagated in the CICS CARDDEMO-COMMAREA
 * (copybook COCOM01Y) is NOT carried in the request body here. In the session
 * baseline the backend sets an HTTP-only cookie (`carddemo_session`) that the
 * browser returns automatically on every request (see `withCredentials: true`);
 * the JWT-alternative mode instead attaches a bearer token via the request
 * interceptor. Either way there is no COMMAREA to pass program-to-program.
 *
 * RULE: no component or page may call `fetch`/`axios` inline -- all HTTP flows
 * through this module so authentication, error normalization, and the API prefix
 * live in exactly one place. (AAP Section 0.4.1, 0.5.3, 0.5.5.)
 */

import axios from 'axios';
import type {
    AxiosInstance,
    AxiosError,
    AxiosResponse,
} from 'axios';

import { DEFAULT_PAGE_SIZE } from '@/types';
import type {
    LoginRequest,
    LoginResponse,
    MessageResponse,
    MenuResponse,
    AccountDetail,
    AccountUpdate,
    CardListParams,
    CardRead,
    CardSummary,
    CardUpdate,
    TransactionRead,
    TransactionSummary,
    TransactionCreate,
    ReportRequest,
    ReportResponse,
    BillPayRequest,
    BillPayResponse,
    UserRead,
    UserSummary,
    UserCreate,
    UserUpdate,
    PaginatedResponse,
    PaginationParams,
    ErrorResponse,
} from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * Backend REST prefix. Every router is mounted under this path
 * (backend `settings.API_V1_PREFIX`). It is a path segment, not a secret or an
 * origin, so it is allowed in code; it is appended to the origin exactly once
 * (in `apiBaseUrl` below) and endpoint paths must NOT repeat it.
 */
const API_V1_PATH = '/api/v1';

/** Frontend route the user is redirected to when a session is missing/expired. */
const SIGNON_ROUTE = '/signon';

/**
 * Login endpoint path. Used to suppress the 401 auto-redirect for the login call
 * itself so a bad-credentials error surfaces on the signon page instead of
 * triggering a redirect loop.
 */
const AUTH_LOGIN_PATH = '/auth/login';

/**
 * Logout endpoint path. `POST`ing here instructs the backend to clear the
 * HTTP-only session cookie (the only way JS can trigger removal of a cookie it
 * cannot itself read/delete). Kept as a named constant (no magic string).
 */
const AUTH_LOGOUT_PATH = '/auth/logout';

/**
 * localStorage key under which `auth.ts` persists the non-sensitive CurrentUser
 * context. Exported so `auth.ts` shares exactly one key with the clear logic
 * below.
 *
 * NOTE (QA finding M-10): the browser SPA NEVER persists an authentication
 * credential in localStorage. Authentication rides exclusively on the backend's
 * HTTP-only `carddemo_session` cookie (unreadable by JavaScript, so it cannot be
 * exfiltrated by XSS). Only this NON-SENSITIVE identity context (user id, names,
 * role) is mirrored to localStorage for rendering decisions; it is never a
 * credential. The former optional localStorage JWT bearer path was removed.
 */
export const SESSION_USER_STORAGE_KEY = 'carddemo_user';

/** Named HTTP status code (no magic numbers). The interceptor only acts on
 *  401; 403/409/400 are propagated unchanged, so no constants are needed for
 *  them. */
const HTTP_UNAUTHORIZED = 401;

/** Fallback message shown when the backend supplies no usable error text. */
const DEFAULT_ERROR_MESSAGE = 'An unexpected error occurred. Please try again.';

/**
 * Actionable message shown when a request never receives a response from the
 * server (QA finding M-11). This covers a dropped/offline network, a DNS
 * failure, a request timeout, and -- critically -- a CORS / host-alias rejection
 * that the browser blocks before axios can read any response body. In all of
 * these cases axios reports only a terse, non-actionable "Network Error" (with no
 * `response`), which previously left the signon screen showing text the user
 * could not act on. This message tells the user what to do; because it is
 * produced at the client's error-normalization core, EVERY page (signon and all
 * others) surfaces it consistently via `ErrorAlert`.
 */
const NETWORK_ERROR_MESSAGE =
    'Unable to reach the server. Please check your network connection and try again.';

/**
 * Pydantic v2 prepends this literal to the `msg` of every 422 item that a custom
 * field/model validator raises as a `ValueError` (for example the signon edits
 * surface "Value error, User ID must be supplied."). It is a framework artifact,
 * not part of the domain sentence the user should read, so the display formatter
 * strips a single leading occurrence before showing the message (QA dest INFO(b)).
 * Built-in constraint messages (e.g. "String should have at most 8 characters")
 * carry no such prefix and are left unchanged.
 */
const PYDANTIC_VALUE_ERROR_PREFIX = 'Value error, ';

/**
 * Base URL for every request, composed ONLY from the environment (Ochs rule #3 --
 * no hardcoded origin). `NEXT_PUBLIC_API_URL` (the backend ORIGIN, e.g.
 * http://localhost:8000) lives in frontend/.env.local.example and is inlined into
 * the client bundle at build time. The `/api/v1` prefix is appended here exactly
 * once; there is intentionally NO hardcoded origin fallback.
 */
const apiBaseUrl = `${process.env.NEXT_PUBLIC_API_URL}${API_V1_PATH}`;

/* ------------------------------------------------------------------------- */
/* The axios singleton (exported).                                           */
/* ------------------------------------------------------------------------- */

/**
 * The shared axios instance that every resource wrapper -- and, indirectly, every
 * page/component -- uses. `withCredentials: true` is REQUIRED so the browser sends
 * the HTTP-only `carddemo_session` cookie on every request; the backend CORS
 * policy allows the http://localhost:3000 origin with credentials enabled.
 */
export const apiClient: AxiosInstance = axios.create({
    baseURL: apiBaseUrl,
    withCredentials: true,
    headers: {
        'Content-Type': 'application/json',
    },
});

/* ------------------------------------------------------------------------- */
/* Typed error model (exported).                                             */
/* ------------------------------------------------------------------------- */

/** Constructor input for {@link ApiError} (single object param -- Ochs <=4 params). */
interface ApiErrorInit {
    status: number;
    message: string;
    code?: string;
    detail?: string;
}

/**
 * Normalized error rejected by every failed request. Carries the HTTP `status` so
 * callers can branch (e.g. 403 admin-gating, 409 optimistic-lock conflict) and, for
 * transaction-posting failures, the numeric posting `code` (100-103, 109).
 */
export class ApiError extends Error {
    readonly status: number;
    readonly code?: string;
    readonly detail?: string;

    constructor(init: ApiErrorInit) {
        super(init.message);
        this.name = 'ApiError';
        this.status = init.status;
        this.code = init.code;
        this.detail = init.detail;
    }
}

/** Type guard: `true` when `error` is an {@link ApiError}. */
export function IsApiError(error: unknown): error is ApiError {
    return error instanceof ApiError;
}

/** Narrows an unknown value to a string-keyed record for safe property probing. */
function IsRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

/**
 * Strips the Pydantic v2 {@link PYDANTIC_VALUE_ERROR_PREFIX} from a single 422
 * `msg` so the toast shows only the domain sentence ("User ID must be supplied."
 * rather than "Value error, User ID must be supplied."). Only a leading
 * occurrence is removed; any message without the prefix is returned unchanged.
 */
function NormalizeValidationMessage(msg: string): string {
    if (msg.startsWith(PYDANTIC_VALUE_ERROR_PREFIX)) {
        return msg.slice(PYDANTIC_VALUE_ERROR_PREFIX.length);
    }
    return msg;
}

/** Joins the `msg` fields of FastAPI 422 validation items into one message string. */
function ExtractValidationMessages(detail: unknown[]): string {
    return detail
        .map((item) => {
            if (IsRecord(item) && typeof item.msg === 'string') {
                return NormalizeValidationMessage(item.msg);
            }
            return '';
        })
        .filter((msg) => msg.length > 0)
        .join('; ');
}

/**
 * Extracts a display message from the error body, handling BOTH the app-standard
 * `{ message, code?, detail? }` shape and FastAPI's default
 * `{ detail: string | ValidationItem[] }` shape. When there is no usable body it
 * distinguishes a request that never reached the server (no `response` --
 * network/CORS/timeout) and returns the ACTIONABLE {@link NETWORK_ERROR_MESSAGE}
 * (QA finding M-11) instead of axios's terse "Network Error"; otherwise it falls
 * back to the axios message and finally to {@link DEFAULT_ERROR_MESSAGE}. Inspects
 * specific shapes -- never a blanket catch that hides detail (Ochs error-handling
 * rule).
 */
function ExtractErrorMessage(error: AxiosError<ErrorResponse>): string {
    const data: unknown = error.response?.data;
    if (IsRecord(data)) {
        if (typeof data.message === 'string' && data.message.length > 0) {
            return data.message;
        }
        if (typeof data.detail === 'string' && data.detail.length > 0) {
            return data.detail;
        }
        if (Array.isArray(data.detail)) {
            const joined = ExtractValidationMessages(data.detail);
            if (joined.length > 0) {
                return joined;
            }
        }
    }
    // QA finding M-11: a request that never received a response (no `error.response`)
    // is a connectivity/CORS/timeout failure. Surface the actionable network message
    // rather than the bare, non-actionable axios "Network Error" string.
    if (!error.response) {
        return NETWORK_ERROR_MESSAGE;
    }
    return error.message || DEFAULT_ERROR_MESSAGE;
}

/**
 * Converts a raw {@link AxiosError} into a typed {@link ApiError}. `status` is 0
 * when the request never reached the server (network error). `code`/`detail` are
 * read from the app-standard body when present (e.g. posting codes 100-103, 109).
 */
function NormalizeAxiosError(error: AxiosError<ErrorResponse>): ApiError {
    const status = error.response?.status ?? 0;
    const data: unknown = error.response?.data;
    const message = ExtractErrorMessage(error);
    const code =
        IsRecord(data) && typeof data.code === 'string' ? data.code : undefined;
    const rawDetail =
        IsRecord(data) && typeof data.detail === 'string' ? data.detail : undefined;
    // QA #4: FastAPI's default error body is `{ detail: "<msg>" }`, and
    // ExtractErrorMessage already surfaced that string as `message`. Carrying the
    // identical string again as `detail` makes ErrorAlert render the same text
    // twice, so keep `detail` only when it adds information beyond `message`.
    const detail = rawDetail === message ? undefined : rawDetail;
    return new ApiError({ status, message, code, detail });
}

/* ------------------------------------------------------------------------- */
/* Auth-storage helpers.                                                     */
/*                                                                           */
/* This module owns only the storage KEYS and the raw CLEAR operation. The   */
/* CurrentUser read/write/serialize logic belongs to `auth.ts`. Keeping the  */
/* clear here lets the response interceptor call it WITHOUT importing         */
/* `auth.ts` (which imports this module) -- avoiding a circular import.      */
/* ------------------------------------------------------------------------- */

/**
 * Clears the client-held identity mirror (the persisted CurrentUser context).
 * The HTTP-only `carddemo_session` cookie is NOT touched here -- it is not
 * readable or removable from JS and is cleared server-side on logout/expiry.
 * Guarded for SSR.
 *
 * QA finding M-10: no bearer token is ever stored, so there is no token key to
 * clear -- only the non-sensitive identity mirror.
 */
export function ClearStoredAuth(): void {
    if (typeof window === 'undefined') {
        return;
    }
    window.localStorage.removeItem(SESSION_USER_STORAGE_KEY);
}

/* ------------------------------------------------------------------------- */
/* Interceptors.                                                             */
/* ------------------------------------------------------------------------- */

/**
 * Decides whether a 401 should trigger the clear-auth + redirect-to-signon side
 * effect. Returns `false` (suppress; just reject) when the failing request is the
 * login call itself (a bad-credentials error must surface on the signon page, not
 * loop-redirect) or when the browser is already on the signon route.
 */
function ShouldRedirectOnUnauthorized(error: AxiosError<ErrorResponse>): boolean {
    const requestUrl = error.config?.url ?? '';
    if (requestUrl.includes(AUTH_LOGIN_PATH)) {
        return false;
    }
    if (
        typeof window !== 'undefined' &&
        window.location.pathname === SIGNON_ROUTE
    ) {
        return false;
    }
    return true;
}

/**
 * Performs a full-navigation redirect to the signon route. A plain module cannot
 * use the Next `useRouter` hook, so a hard `window.location` navigation is the
 * correct mechanism here. Guarded for SSR.
 */
function RedirectToSignon(): void {
    if (typeof window !== 'undefined') {
        window.location.href = SIGNON_ROUTE;
    }
}

/*
 * QA finding M-10: there is deliberately NO request interceptor attaching an
 * Authorization bearer header. The browser SPA authenticates ONLY via the
 * backend's HTTP-only `carddemo_session` cookie, which axios sends automatically
 * because the client is created with `withCredentials: true`. Removing the
 * bearer path eliminates any need to hold a token in JavaScript-reachable
 * storage. (Non-browser API clients that use JWT mode manage their own header.)
 */

/**
 * Response interceptor. Passes successful responses through unchanged; on error,
 * normalizes to a typed {@link ApiError} so EVERY rejection has a consistent shape.
 *
 * Status handling:
 * - HTTP_UNAUTHORIZED (401): clear client auth and redirect to /signon, EXCEPT for
 *   the login request itself or when already on /signon (see
 *   {@link ShouldRedirectOnUnauthorized}).
 * - 403 (admin-gated), 409 (optimistic-lock conflict from `PUT /accounts/{acctId}`),
 *   and 400 posting errors (codes 100-103, 109) need no side effect here -- they are
 *   propagated as the typed ApiError (with `.status` and, for posting errors,
 *   `.code`) so callers / ErrorAlert can display them.
 */
apiClient.interceptors.response.use(
    (response: AxiosResponse) => response,
    (error: AxiosError<ErrorResponse>) => {
        const apiError = NormalizeAxiosError(error);
        if (
            apiError.status === HTTP_UNAUTHORIZED &&
            ShouldRedirectOnUnauthorized(error)
        ) {
            ClearStoredAuth();
            RedirectToSignon();
        }
        return Promise.reject(apiError);
    },
);

/* ------------------------------------------------------------------------- */
/* Typed endpoint wrappers (exported resource objects).                      */
/*                                                                           */
/* Every method returns the `@/types` interface (unwraps `response.data`),   */
/* passes each path parameter through `encodeURIComponent` (input            */
/* sanitization), and relies on `baseURL` for the `/api/v1` prefix -- method */
/* paths are relative and never repeat it. Method names are PascalCase.      */
/* ------------------------------------------------------------------------- */

/**
 * Builds the pagination query, defaulting `page` to 1 and `page_size` to
 * DEFAULT_PAGE_SIZE (7 rows/page -- the legacy COCRDLIC browse limit, F-004).
 */
function BuildListQuery(
    params?: Partial<PaginationParams>,
): { page: number; page_size: number } {
    return {
        page: params?.page ?? 1,
        page_size: params?.page_size ?? DEFAULT_PAGE_SIZE,
    };
}

/**
 * Builds the card-list query: the pagination window plus the optional COCRDLI
 * search filters (`acct_id`, `card_num`). A filter is included ONLY when it is a
 * non-empty (trimmed) string, so an untouched search box sends no filter and
 * the browse is unfiltered -- this is the wiring that makes the card-list search
 * controls functional (QA C3). Trimming here mirrors the backend's blank-filter
 * normalization and keeps the request URL clean.
 */
function BuildCardListQuery(
    params?: Partial<CardListParams>,
): Record<string, string | number> {
    const query: Record<string, string | number> = BuildListQuery(params);
    const acctId = params?.acct_id?.trim();
    if (acctId) {
        query.acct_id = acctId;
    }
    const cardNum = params?.card_num?.trim();
    if (cardNum) {
        query.card_num = cardNum;
    }
    return query;
}

/**
 * Builds the transaction-report query. `report_type` is passed straight through
 * from the request (axios serializes the enum's string value); `confirm` is
 * included only when present. `format` selects the output representation.
 */
function BuildReportQuery(
    reportRequest: ReportRequest,
    format: string,
): Record<string, string> {
    const query: Record<string, string> = {
        report_type: reportRequest.report_type,
        start_date: reportRequest.start_date,
        end_date: reportRequest.end_date,
        format,
    };
    if (reportRequest.confirm) {
        query.confirm = reportRequest.confirm;
    }
    return query;
}

/**
 * Authentication API. Origin: COSGN00C / COSGN00.bms (CICS tx CC00).
 * The only unauthenticated endpoint.
 */
export const AuthApi = {
    /**
     * Signs a user in. POST /auth/login (HTTP 200). In the session baseline the
     * backend sets the HTTP-only `carddemo_session` cookie and returns null
     * access_token/token_type; in JWT mode those fields carry the bearer token.
     */
    async Login(credentials: LoginRequest): Promise<LoginResponse> {
        const response = await apiClient.post<LoginResponse>(
            AUTH_LOGIN_PATH,
            credentials,
        );
        return response.data;
    },

    /**
     * Signs the current user out. POST /auth/logout (HTTP 200). The session
     * baseline stores identity in an HTTP-only cookie that JS can neither read
     * nor delete, so genuine sign-out requires this server round-trip: the
     * backend responds with a cookie-deletion `Set-Cookie` header, after which
     * the browser sends no credential and protected calls return 401. The
     * endpoint is unauthenticated and idempotent, so it is safe to call even
     * when the session is already gone.
     */
    async Logout(): Promise<MessageResponse> {
        const response = await apiClient.post<MessageResponse>(AUTH_LOGOUT_PATH);
        return response.data;
    },
};

/**
 * Navigation-menu API. Origin: COMEN01C + COADM01C /
 * COMEN01 + COADM01.bms (CICS tx CM00 / CA00).
 */
export const MenuApi = {
    /** Returns the regular-user menu. GET /menu (any authenticated user). */
    async GetMenu(): Promise<MenuResponse> {
        const response = await apiClient.get<MenuResponse>('/menu');
        return response.data;
    },

    /** Returns the admin menu. GET /admin/menu (admin-gated -> 403 for user_type='U'). */
    async GetAdminMenu(): Promise<MenuResponse> {
        const response = await apiClient.get<MenuResponse>('/admin/menu');
        return response.data;
    },
};

/**
 * Account API. Origin: COACTVWC + COACTUPC / COACTVW + COACTUP.bms
 * (CICS tx CAVW / CAUP). `acctId` is a string (VARCHAR(11); preserves leading zeros).
 */
export const AccountsApi = {
    /** Fetches an account plus its owning customer. GET /accounts/{acctId}. */
    async GetAccount(acctId: string): Promise<AccountDetail> {
        const response = await apiClient.get<AccountDetail>(
            `/accounts/${encodeURIComponent(acctId)}`,
        );
        return response.data;
    },

    /**
     * Updates an account. PUT /accounts/{acctId}. A concurrent-modification
     * (optimistic-lock) conflict returns HTTP 409, propagated as an ApiError with
     * `.status === 409` for the update page to surface.
     */
    async UpdateAccount(
        acctId: string,
        accountUpdate: AccountUpdate,
    ): Promise<AccountDetail> {
        const response = await apiClient.put<AccountDetail>(
            `/accounts/${encodeURIComponent(acctId)}`,
            accountUpdate,
        );
        return response.data;
    },
};

/**
 * Card API. Origin: COCRDLIC + COCRDSLC + COCRDUPC /
 * COCRDLI + COCRDSL + COCRDUP.bms (CICS tx CCLI / CCDL / CCUP).
 */
export const CardsApi = {
    /**
     * Lists cards, <= DEFAULT_PAGE_SIZE (7) rows/page (F-004). GET /cards.
     * Accepts the optional COCRDLI search filters (`acct_id`, `card_num`); a
     * blank/omitted filter is not sent, so the browse is unfiltered (QA C3).
     */
    async ListCards(
        params?: Partial<CardListParams>,
    ): Promise<PaginatedResponse<CardSummary>> {
        const response = await apiClient.get<PaginatedResponse<CardSummary>>(
            '/cards',
            { params: BuildCardListQuery(params) },
        );
        return response.data;
    },

    /**
     * Fetches one card by card number. GET /cards/{cardNum} (CCDL; AAP 0.5.5).
     * This is the sole card-detail endpoint: the earlier by-account helper was
     * removed because it resolved an account to a single card with `.limit(1)`,
     * silently selecting the wrong card on the NONUNIQUE account->card
     * relationship (QA C07), and was outside the frozen route contract (QA C08).
     * Faithful to the legacy COCRDSL screen, the operator ENTERS the card number
     * (its `CARDSID` input), so no unmasked PAN is returned in a list; server
     * responses mask the PAN and app logs are PAN-scrubbed (AAP 0.7.8).
     */
    async GetCard(cardNum: string): Promise<CardRead> {
        const response = await apiClient.get<CardRead>(
            `/cards/${encodeURIComponent(cardNum)}`,
        );
        return response.data;
    },

    /**
     * Updates a card by card number. PUT /cards/{cardNum} (CCUP; AAP 0.5.5).
     * The sole card-update endpoint (see {@link GetCard}). `cardUpdate` carries
     * the required client-echoed `before_image` optimistic-lock token: the
     * editable-field values the operator last read, compared field-for-field
     * against the freshly locked row so a stale write is rejected with HTTP 409
     * (QA C06). Responses mask the PAN (AAP 0.7.8).
     */
    async UpdateCard(cardNum: string, cardUpdate: CardUpdate): Promise<CardRead> {
        const response = await apiClient.put<CardRead>(
            `/cards/${encodeURIComponent(cardNum)}`,
            cardUpdate,
        );
        return response.data;
    },
};

/**
 * Transaction API. Origin: COTRN00C + COTRN01C + COTRN02C /
 * COTRN00 + COTRN01 + COTRN02.bms (CICS tx CT00 / CT01 / CT02).
 */
export const TransactionsApi = {
    /** Lists transactions with page/page_size defaults. GET /transactions. */
    async ListTransactions(
        params?: Partial<PaginationParams>,
    ): Promise<PaginatedResponse<TransactionSummary>> {
        const response = await apiClient.get<
            PaginatedResponse<TransactionSummary>
        >('/transactions', { params: BuildListQuery(params) });
        return response.data;
    },

    /** Fetches one transaction. GET /transactions/{tranId}. */
    async GetTransaction(tranId: string): Promise<TransactionRead> {
        const response = await apiClient.get<TransactionRead>(
            `/transactions/${encodeURIComponent(tranId)}`,
        );
        return response.data;
    },

    /**
     * Adds a transaction. POST /transactions (HTTP 201). Posting-validation
     * failures come back as HTTP 400 whose ApiError `.code` holds the numeric
     * posting code and `.message` the description:
     *   100 INVALID CARD NUMBER, 101 ACCOUNT NOT FOUND, 102 OVERLIMIT,
     *   103 AFTER ACCT EXPIRATION, 109 ACCOUNT UPDATE FAILED.
     * The add-transaction page maps these to ErrorAlert.
     */
    async AddTransaction(
        transactionCreate: TransactionCreate,
    ): Promise<TransactionRead> {
        const response = await apiClient.post<TransactionRead>(
            '/transactions',
            transactionCreate,
        );
        return response.data;
    },
};

/**
 * Report API. Origin: CORPT00C / CORPT00.bms (CICS tx CR00). Legacy TDQ/GDG
 * text+HTML statement output is redesigned to an on-screen table plus CSV/PDF
 * downloads (AAP Section 0.8.4).
 */
export const ReportsApi = {
    /**
     * Fetches the transaction report as JSON (rendered as an on-screen table).
     * GET /reports/transactions?format=json.
     */
    async GetTransactionReport(
        reportRequest: ReportRequest,
    ): Promise<ReportResponse> {
        const response = await apiClient.get<ReportResponse>(
            '/reports/transactions',
            { params: BuildReportQuery(reportRequest, 'json') },
        );
        return response.data;
    },

    /**
     * Downloads the transaction report as a CSV or PDF attachment.
     * GET /reports/transactions?format=csv|pdf with `responseType: 'blob'`. The
     * backend returns text/csv or application/pdf.
     */
    async DownloadTransactionReport(
        reportRequest: ReportRequest,
        format: 'csv' | 'pdf',
    ): Promise<Blob> {
        const response = await apiClient.get<Blob>('/reports/transactions', {
            params: BuildReportQuery(reportRequest, format),
            responseType: 'blob',
        });
        return response.data as Blob;
    },
};

/**
 * Bill-payment API. Origin: COBIL00C / COBIL00.bms (CICS tx CB00).
 * available_credit = credit_limit - current_balance (F-006).
 */
export const BillPayApi = {
    /** Fetches bill-pay info for an account. GET /billpay/{acctId}. */
    async GetBillPayInfo(acctId: string): Promise<BillPayResponse> {
        const response = await apiClient.get<BillPayResponse>(
            `/billpay/${encodeURIComponent(acctId)}`,
        );
        return response.data;
    },

    /**
     * Submits a bill payment. POST /billpay -- HTTP 200 (it is an action, not a
     * resource creation; do NOT expect 201).
     */
    async PayBill(billPayRequest: BillPayRequest): Promise<BillPayResponse> {
        const response = await apiClient.post<BillPayResponse>(
            '/billpay',
            billPayRequest,
        );
        return response.data;
    },
};

/**
 * User-administration API. Origin: COUSR00C-COUSR03C /
 * COUSR00-COUSR03.bms (CICS tx CU00-CU03). ALL endpoints are admin-gated (the
 * server enforces this via a router-level dependency -> 403 for non-admin).
 * `userId` is a string (VARCHAR(8)).
 */
export const UsersApi = {
    /** Lists users with page/page_size defaults. GET /admin/users. */
    async ListUsers(
        params?: Partial<PaginationParams>,
    ): Promise<PaginatedResponse<UserSummary>> {
        const response = await apiClient.get<PaginatedResponse<UserSummary>>(
            '/admin/users',
            { params: BuildListQuery(params) },
        );
        return response.data;
    },

    /** Creates a user. POST /admin/users (HTTP 201). */
    async AddUser(userCreate: UserCreate): Promise<UserRead> {
        const response = await apiClient.post<UserRead>(
            '/admin/users',
            userCreate,
        );
        return response.data;
    },

    /** Fetches one user. GET /admin/users/{userId}. */
    async GetUser(userId: string): Promise<UserRead> {
        const response = await apiClient.get<UserRead>(
            `/admin/users/${encodeURIComponent(userId)}`,
        );
        return response.data;
    },

    /** Updates a user. PUT /admin/users/{userId}. */
    async UpdateUser(userId: string, userUpdate: UserUpdate): Promise<UserRead> {
        const response = await apiClient.put<UserRead>(
            `/admin/users/${encodeURIComponent(userId)}`,
            userUpdate,
        );
        return response.data;
    },

    /**
     * Deletes a user. DELETE /admin/users/{userId} (HTTP 204, no body -- the
     * response carries no data to read).
     */
    async DeleteUser(userId: string): Promise<void> {
        await apiClient.delete<void>(
            `/admin/users/${encodeURIComponent(userId)}`,
        );
    },
};
