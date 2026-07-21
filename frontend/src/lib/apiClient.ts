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
    InternalAxiosRequestConfig,
} from 'axios';

import { DEFAULT_PAGE_SIZE } from '@/types';
import type {
    LoginRequest,
    LoginResponse,
    MenuResponse,
    AccountDetail,
    AccountUpdate,
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
 * localStorage key under which `auth.ts` persists the non-sensitive CurrentUser
 * context. Exported so `auth.ts` shares exactly one key with the interceptor's
 * clear logic below.
 */
export const SESSION_USER_STORAGE_KEY = 'carddemo_user';

/**
 * localStorage key for the optional JWT bearer token (JWT-alternative mode).
 * Unused in the session-cookie baseline; exported for `auth.ts`.
 */
export const ACCESS_TOKEN_STORAGE_KEY = 'carddemo_access_token';

/** Named HTTP status codes (no magic numbers). */
const HTTP_UNAUTHORIZED = 401;
const HTTP_FORBIDDEN = 403;
const HTTP_CONFLICT = 409;

/** Fallback message shown when the backend supplies no usable error text. */
const DEFAULT_ERROR_MESSAGE = 'An unexpected error occurred. Please try again.';

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

/** Joins the `msg` fields of FastAPI 422 validation items into one message string. */
function ExtractValidationMessages(detail: unknown[]): string {
    return detail
        .map((item) => {
            if (IsRecord(item) && typeof item.msg === 'string') {
                return item.msg;
            }
            return '';
        })
        .filter((msg) => msg.length > 0)
        .join('; ');
}

/**
 * Extracts a display message from the error body, handling BOTH the app-standard
 * `{ message, code?, detail? }` shape and FastAPI's default
 * `{ detail: string | ValidationItem[] }` shape. Falls back to the axios/network
 * message and finally to {@link DEFAULT_ERROR_MESSAGE}. Inspects specific shapes --
 * never a blanket catch that hides detail (Ochs error-handling rule).
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
    const detail =
        IsRecord(data) && typeof data.detail === 'string' ? data.detail : undefined;
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
 * Reads the optional JWT bearer token from localStorage (JWT-alternative mode).
 * Guarded for SSR/prerender where `window`/`localStorage` do not exist; returns
 * `null` there and in the session baseline (where no token is stored).
 */
function ReadStoredAccessToken(): string | null {
    if (typeof window === 'undefined') {
        return null;
    }
    return window.localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
}

/**
 * Clears all client-held auth artifacts (the persisted CurrentUser context and any
 * JWT token). The HTTP-only `carddemo_session` cookie is NOT touched here -- it is
 * not readable or removable from JS and is cleared server-side on logout/expiry.
 * Guarded for SSR.
 */
export function ClearStoredAuth(): void {
    if (typeof window === 'undefined') {
        return;
    }
    window.localStorage.removeItem(SESSION_USER_STORAGE_KEY);
    window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
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

/**
 * Request interceptor (JWT-alternative hook). The session-cookie baseline needs no
 * Authorization header (the cookie is auto-sent via `withCredentials`), but when a
 * bearer token has been stored (JWT mode) it is attached here.
 */
apiClient.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
        const accessToken = ReadStoredAccessToken();
        if (accessToken) {
            config.headers.Authorization = `Bearer ${accessToken}`;
        }
        return config;
    },
);

/**
 * Response interceptor. Passes successful responses through unchanged; on error,
 * normalizes to a typed {@link ApiError} so EVERY rejection has a consistent shape.
 *
 * Status handling:
 * - HTTP_UNAUTHORIZED (401): clear client auth and redirect to /signon, EXCEPT for
 *   the login request itself or when already on /signon (see
 *   {@link ShouldRedirectOnUnauthorized}).
 * - HTTP_FORBIDDEN (403, admin-gated), HTTP_CONFLICT (409, optimistic-lock conflict
 *   from `PUT /accounts/{acctId}`), and 400 posting errors (codes 100-103, 109) need
 *   no side effect here -- they are propagated as the typed ApiError (with `.status`
 *   and, for posting errors, `.code`) so callers / ErrorAlert can display them.
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
    /** Lists cards, <= DEFAULT_PAGE_SIZE (7) rows/page (F-004). GET /cards. */
    async ListCards(
        params?: Partial<PaginationParams>,
    ): Promise<PaginatedResponse<CardSummary>> {
        const response = await apiClient.get<PaginatedResponse<CardSummary>>(
            '/cards',
            { params: BuildListQuery(params) },
        );
        return response.data;
    },

    /** Fetches one card. GET /cards/{cardNum}. */
    async GetCard(cardNum: string): Promise<CardRead> {
        const response = await apiClient.get<CardRead>(
            `/cards/${encodeURIComponent(cardNum)}`,
        );
        return response.data;
    },

    /** Updates a card. PUT /cards/{cardNum}. */
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
