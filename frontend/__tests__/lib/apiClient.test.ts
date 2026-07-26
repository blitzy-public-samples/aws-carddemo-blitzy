/**
 * apiClient.test.ts — Jest + jsdom unit suite for the frontend API-client
 * singleton `@/lib/apiClient` (frontend/src/lib/apiClient.ts).
 *
 * WHAT IS VERIFIED
 *   - the axios singleton configuration (baseURL `/api/v1` suffix, withCredentials,
 *     default JSON Content-Type);
 *   - that NO request interceptor exists (QA finding M-10: the browser SPA sends
 *     no bearer Authorization header; auth rides on the HTTP-only session cookie);
 *   - the response interceptor's typed error normalization into `ApiError`
 *     (app-standard `{message,code,detail}`, FastAPI `{detail:string}`, FastAPI 422
 *     `{detail:[...]}`, and network errors);
 *   - the posting-validation codes 100/101/102/103/109 surfacing as HTTP 400 with
 *     the numeric `code` preserved (AAP Section 0.7.3, legacy program CBTRN02C);
 *   - the 401 auto-logout + redirect-to-/signon behavior and its two suppression
 *     cases (the login request itself, and being already on /signon);
 *   - the auth-storage guards/helpers (`IsApiError`, `ClearStoredAuth`);
 *   - the exact HTTP verb + URL + payload + return contract for all 8 resource API
 *     objects (AuthApi, MenuApi, AccountsApi, CardsApi, TransactionsApi, ReportsApi,
 *     BillPayApi, UsersApi).
 *
 * TRACEABILITY: the API client is the modern replacement for legacy CICS
 * transaction dispatch behind the 17 BMS screens — the signon (COSGN00 / CC00),
 * card-list (COCRDLI / CCLI), transaction-list (COTRN00 / CT00) and user-admin
 * (COUSR00 / CU00) maps among them. There is NO real network here.
 *
 * TWO MOCKING GOTCHAS (both handled below — do not "simplify" them away):
 *   1. `axios` is mocked with a self-contained FACTORY (the mock instance lives
 *      INSIDE the factory). Jest forbids a `jest.mock` factory from referencing an
 *      out-of-scope variable unless its name is `mock`-prefixed; keeping the
 *      instance inside sidesteps that rule and the temporal-dead-zone error.
 *   2. `apiClient.ts` calls `axios.create(...)` and registers BOTH interceptors
 *      exactly ONCE at import time. The jest config sets `clearMocks: true`, which
 *      wipes every mock's `.mock.calls` BEFORE each test. Therefore the create
 *      config and the interceptor callbacks are captured into module-scope
 *      constants at file-evaluation time (below), BEFORE any `beforeEach` runs.
 *      Re-reading `...use.mock.calls[0]` inside a test would read as empty.
 */

// --- Phase A: mock axios with a self-contained factory (hoisted above imports) ---
jest.mock('axios', () => {
    const mockInstance = {
        get: jest.fn(),
        post: jest.fn(),
        put: jest.fn(),
        delete: jest.fn(),
        interceptors: {
            request: { use: jest.fn() },
            response: { use: jest.fn() },
        },
    };
    return {
        __esModule: true,
        default: {
            create: jest.fn(() => mockInstance),
            isAxiosError: (error: unknown) =>
                Boolean(error && (error as { isAxiosError?: boolean }).isAxiosError),
        },
    };
});

import axios from 'axios';
import {
    apiClient,
    ApiError,
    IsApiError,
    ClearStoredAuth,
    SESSION_USER_STORAGE_KEY,
    AuthApi,
    MenuApi,
    AccountsApi,
    CardsApi,
    TransactionsApi,
    ReportsApi,
    BillPayApi,
    UsersApi,
} from '@/lib/apiClient';
import { DEFAULT_PAGE_SIZE, ReportType } from '@/types';
import type {
    LoginRequest,
    AccountUpdate,
    CardUpdate,
    TransactionCreate,
    ReportRequest,
    BillPayRequest,
    UserCreate,
    UserUpdate,
    PaginationParams,
} from '@/types';
import {
    MakePaginatedResponse,
    MakeCurrentUser,
    MakeCardSummary,
    MakeUserSummary,
} from '../testUtils';

/* ------------------------------------------------------------------------- */
/* Test-local helpers (PascalCase per the Ochs Rule).                        */
/* ------------------------------------------------------------------------- */

/**
 * Narrows an unknown jest-mocked value to `jest.Mock` for call/return assertions.
 * A single `unknown`-typed parameter keeps the cast minimal and always compiles.
 */
function AsMock(candidate: unknown): jest.Mock {
    return candidate as jest.Mock;
}

// The former JWT-bearer localStorage key (QA finding M-10). The client no longer
// exports or uses it; these tests assert it is never written and that the 401
// handler / ClearStoredAuth no longer touch it. Kept only as a literal to prove
// its absence.
const LEGACY_ACCESS_TOKEN_KEY = 'carddemo_access_token';

// --- Phase B: capture import-time registrations at MODULE SCOPE (clearMocks gotcha) ---

/** The config object passed to `axios.create(...)` during module import. */
const capturedCreateConfig = AsMock(axios.create).mock.calls[0][0];

// QA finding M-10: apiClient.ts registers NO request interceptor, so
// `apiClient.interceptors.request.use` is never called at import. This test file
// therefore does NOT capture a request-interceptor callback (doing so would read
// an undefined `mock.calls[0]`); a dedicated test below asserts that absence.

/** The response interceptor's onFulfilled callback (pass-through). */
const responseOnFulfilled = AsMock(apiClient.interceptors.response.use).mock.calls[0][0];

/** The response interceptor's onRejected callback (error normalization + 401 side effects). */
const responseOnRejected = AsMock(apiClient.interceptors.response.use).mock.calls[0][1];

/**
 * Stable references to the mocked HTTP verbs. `apiClient` IS the factory's mock
 * instance, so these are the very `jest.fn()`s each API method calls. `clearMocks`
 * resets their call records before each test, giving per-test isolation.
 */
const mockGet = AsMock(apiClient.get);
const mockPost = AsMock(apiClient.post);
const mockPut = AsMock(apiClient.put);
const mockDelete = AsMock(apiClient.delete);

/**
 * Human-readable descriptions for the five batch-posting validation codes,
 * preserved verbatim from legacy `CBTRN02C` (AAP Section 0.7.3):
 *   100 INVALID CARD NUMBER, 101 ACCOUNT NOT FOUND, 102 OVERLIMIT,
 *   103 AFTER ACCT EXPIRATION, 109 ACCOUNT UPDATE FAILED.
 */
const POSTING_CODE_DESCRIPTIONS: Record<string, string> = {
    '100': 'INVALID CARD NUMBER FOUND',
    '101': 'ACCOUNT RECORD NOT FOUND',
    '102': 'OVERLIMIT TRANSACTION',
    '103': 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION',
    '109': 'ACCOUNT RECORD NOT FOUND FOR UPDATE',
};

/** Shape accepted by {@link MakeAxiosError}. */
interface AxiosErrorInit {
    status?: number;
    data?: unknown;
    url?: string;
    message?: string;
}

/**
 * Builds a fake AxiosError shaped exactly like the value axios rejects with, so the
 * response interceptor's onRejected callback can normalize it. When `status` is
 * omitted, `response` is left undefined to model a network error (never reached the
 * server). The `isAxiosError: true` flag mirrors the real axios discriminator.
 */
function MakeAxiosError(init: AxiosErrorInit): unknown {
    return {
        isAxiosError: true,
        message: init.message ?? 'Request failed',
        config: { url: init.url ?? '/accounts/1' },
        response:
            init.status === undefined
                ? undefined
                : { status: init.status, data: init.data },
    };
}

/**
 * Replaces `window.location` with a settable stub so the interceptor's
 * `window.location.href = '/signon'` assignment is observable and
 * `window.location.pathname` is controllable. Returns the stub so a test can read
 * `stub.href` afterward. `configurable: true` lets it be redefined every test.
 */
function StubLocation(pathname: string): { href: string; pathname: string } {
    const locationStub = { href: '', pathname };
    Object.defineProperty(window, 'location', {
        value: locationStub,
        writable: true,
        configurable: true,
    });
    return locationStub;
}

/**
 * Awaits a promise EXPECTED to reject with an {@link ApiError} and returns that
 * error for further assertions. Catches the SPECIFIC normalized error type (Ochs
 * "catch specific types"): a non-ApiError rejection is re-thrown so the real
 * failure surfaces, and a resolution fails the test with a clear message.
 */
async function ExpectApiErrorRejection(rejection: unknown): Promise<ApiError> {
    try {
        await rejection;
    } catch (caught) {
        if (IsApiError(caught)) {
            return caught;
        }
        throw caught;
    }
    throw new Error('Expected the promise to reject with an ApiError, but it resolved.');
}

/* ------------------------------------------------------------------------- */
/* Per-test reset: jsdom storage + location (clearMocks handles jest state).  */
/* ------------------------------------------------------------------------- */

beforeEach(() => {
    localStorage.clear();
    StubLocation('/menu');
});

/* ------------------------------------------------------------------------- */
/* Phase D — client configuration.                                           */
/* ------------------------------------------------------------------------- */

describe('apiClient configuration', () => {
    it('was created via axios.create with a captured config object', () => {
        // clearMocks wiped `axios.create.mock.calls`, so we assert on the config
        // captured at module scope rather than the (now-empty) call record.
        expect(capturedCreateConfig).toBeDefined();
        expect(typeof capturedCreateConfig).toBe('object');
    });

    it('enables credentials so the browser sends the session cookie', () => {
        expect(capturedCreateConfig.withCredentials).toBe(true);
    });

    it('composes a baseURL ending in /api/v1 (origin comes from the environment)', () => {
        // NEXT_PUBLIC_API_URL is typically undefined under jest, so the origin may be
        // the literal "undefined"; asserting the suffix keeps the test env-agnostic.
        expect(typeof capturedCreateConfig.baseURL).toBe('string');
        expect(capturedCreateConfig.baseURL).toMatch(/\/api\/v1$/);
    });

    it('defaults the Content-Type header to application/json', () => {
        expect(capturedCreateConfig.headers['Content-Type']).toBe('application/json');
    });

    it('registered ONLY the response interceptor at import (no request interceptor)', () => {
        // QA finding M-10: there is no request interceptor (no bearer header),
        // so request.use was never called; only the response interceptor exists.
        expect(AsMock(apiClient.interceptors.request.use)).not.toHaveBeenCalled();
        expect(typeof responseOnFulfilled).toBe('function');
        expect(typeof responseOnRejected).toBe('function');
    });
});

/* ------------------------------------------------------------------------- */
/* Phase E — request interceptor (removed: QA finding M-10).                 */
/* ------------------------------------------------------------------------- */

describe('request interceptor (removed per M-10)', () => {
    it('does not register any request interceptor (no bearer Authorization header)', () => {
        // The browser SPA authenticates only via the HTTP-only session cookie
        // (sent automatically by withCredentials), so no Authorization header is
        // ever attached and no request interceptor is registered.
        expect(AsMock(apiClient.interceptors.request.use)).not.toHaveBeenCalled();
    });
});

/* ------------------------------------------------------------------------- */
/* Phase F — response interceptor: success passthrough + error normalization. */
/* ------------------------------------------------------------------------- */

describe('response interceptor / error normalization', () => {
    it('passes a successful response through unchanged', () => {
        const successResponse = { data: { ok: true }, status: 200 };
        expect(responseOnFulfilled(successResponse)).toBe(successResponse);
    });

    it('rejects with an ApiError instance for any failed request', async () => {
        const rejection = responseOnRejected(
            MakeAxiosError({ status: 500, data: { message: 'Server error' }, url: '/x' }),
        );
        await expect(rejection).rejects.toBeInstanceOf(ApiError);
    });

    it('carries status, message, code and detail from the app-standard body shape', async () => {
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({
                    status: 400,
                    data: { message: 'Bad thing', code: 'X1', detail: 'more info' },
                    url: '/x',
                }),
            ),
        );
        expect(apiError.status).toBe(400);
        expect(apiError.message).toBe('Bad thing');
        expect(apiError.code).toBe('X1');
        expect(apiError.detail).toBe('more info');
    });

    it('uses the FastAPI { detail: string } shape as the message and does not duplicate it as detail', async () => {
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({ status: 404, data: { detail: 'Not found' }, url: '/x' }),
            ),
        );
        expect(apiError.status).toBe(404);
        expect(apiError.message).toBe('Not found');
        // QA #4: the FastAPI `{ detail: "<msg>" }` string is surfaced as `message`;
        // it must NOT also be carried as `detail`, or ErrorAlert would render the
        // same text twice. `detail` adds information only when it differs.
        expect(apiError.detail).toBeUndefined();
    });

    it('joins the FastAPI 422 { detail: [...] } msg fields into the message', async () => {
        const validationBody = {
            detail: [
                { loc: ['body', 'user_id'], msg: 'field required', type: 'value_error.missing' },
                { loc: ['body', 'password'], msg: 'value is not valid', type: 'value_error' },
            ],
        };
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({ status: 422, data: validationBody, url: '/x' }),
            ),
        );
        expect(apiError.status).toBe(422);
        expect(apiError.message).toBe('field required; value is not valid');
        // The 422 detail is an ARRAY (not a string), so ApiError.detail stays undefined.
        expect(apiError.detail).toBeUndefined();
    });

    it('strips the Pydantic "Value error, " prefix from 422 msgs (dest INFO(b))', async () => {
        // Pydantic v2 prepends "Value error, " to every custom-validator ValueError
        // msg; these are the exact strings the signon edits emit. The display
        // message must show only the domain sentence, not the framework artifact.
        const validationBody = {
            detail: [
                {
                    loc: ['body', 'user_id'],
                    msg: 'Value error, User ID must be supplied.',
                    type: 'value_error',
                },
                {
                    loc: ['body', 'password'],
                    msg: 'Value error, Password must be supplied.',
                    type: 'value_error',
                },
            ],
        };
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({ status: 422, data: validationBody, url: '/auth/login' }),
            ),
        );
        expect(apiError.status).toBe(422);
        expect(apiError.message).toBe(
            'User ID must be supplied.; Password must be supplied.',
        );
        // A built-in constraint msg carries no prefix and must pass through verbatim.
        const constraintBody = {
            detail: [
                {
                    loc: ['body', 'password'],
                    msg: 'String should have at most 8 characters',
                    type: 'string_too_long',
                },
            ],
        };
        const constraintError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({ status: 422, data: constraintBody, url: '/auth/login' }),
            ),
        );
        expect(constraintError.message).toBe('String should have at most 8 characters');
    });

    it('reports status 0 and an ACTIONABLE network message for a network error (M-11)', async () => {
        // A CORS / host-alias rejection or dropped connection reaches axios as a
        // terse "Network Error" with NO response. The client must replace that with
        // an actionable message so the signon screen (and every page) tells the user
        // what to do, rather than echoing the bare axios string.
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ message: 'Network Error' })),
        );
        expect(apiError.status).toBe(0);
        expect(apiError.message).toBe(
            'Unable to reach the server. Please check your network connection and try again.',
        );
        // The bare, non-actionable axios string is NOT surfaced to the user.
        expect(apiError.message).not.toBe('Network Error');
    });

    it('uses the actionable network message even when axios supplies no message (M-11)', async () => {
        // Some environments reject with an empty/undefined message on a blocked
        // request; the actionable text must still appear (never an empty alert).
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ message: '' })),
        );
        expect(apiError.status).toBe(0);
        expect(apiError.message).toBe(
            'Unable to reach the server. Please check your network connection and try again.',
        );
    });

    it('produces a value that satisfies the IsApiError type guard', async () => {
        try {
            await responseOnRejected(
                MakeAxiosError({ status: 500, data: { message: 'boom' }, url: '/x' }),
            );
            throw new Error('Expected the interceptor to reject.');
        } catch (caught) {
            // Assert on the SPECIFIC normalized error type (Ochs rule).
            expect(IsApiError(caught)).toBe(true);
        }
    });
});

/* ------------------------------------------------------------------------- */
/* Phase G — posting/validation codes 100-103, 109 arrive as HTTP 400.        */
/* Legacy origin CBTRN02C 1500-VALIDATE-TRAN (AAP Section 0.7.3).             */
/* ------------------------------------------------------------------------- */

describe('posting validation codes', () => {
    it.each(['100', '101', '102', '103', '109'])(
        'normalizes posting code %s to an HTTP 400 ApiError with the code preserved',
        async (code) => {
            const description = POSTING_CODE_DESCRIPTIONS[code];
            const apiError = await ExpectApiErrorRejection(
                responseOnRejected(
                    MakeAxiosError({
                        status: 400,
                        data: { message: description, code },
                        url: '/transactions',
                    }),
                ),
            );
            expect(apiError.status).toBe(400);
            expect(apiError.code).toBe(code);
            expect(apiError.message).toBe(description);
        },
    );

    it('does not trigger the 401 side effects for a 400 posting error', async () => {
        const locationStub = StubLocation('/menu');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({
                    status: 400,
                    data: { message: POSTING_CODE_DESCRIPTIONS['102'], code: '102' },
                    url: '/transactions',
                }),
            ),
        );
        expect(apiError.status).toBe(400);
        // Session storage untouched and no redirect: the 401-only path did not run.
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).not.toBeNull();
        expect(locationStub.href).toBe('');
    });
});

/* ------------------------------------------------------------------------- */
/* Phase H — 401 handling and its two suppression cases.                      */
/* ------------------------------------------------------------------------- */

describe('401 unauthorized handling', () => {
    it('clears stored auth and redirects to /signon for a normal 401', async () => {
        const locationStub = StubLocation('/menu');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ status: 401, url: '/accounts/1' })),
        );
        expect(apiError.status).toBe(401);
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).toBeNull();
        expect(locationStub.href).toBe('/signon');
    });

    it('suppresses the redirect for the login request itself (bad credentials)', async () => {
        // Pathname is deliberately NOT /signon so only the url rule can suppress.
        const locationStub = StubLocation('/menu');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ status: 401, url: '/auth/login' })),
        );
        expect(apiError.status).toBe(401);
        expect(locationStub.href).toBe('');
        // Redirect suppressed => ClearStoredAuth did not run.
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).not.toBeNull();
    });

    it('suppresses the redirect when already on /signon', async () => {
        const locationStub = StubLocation('/signon');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ status: 401, url: '/accounts/1' })),
        );
        expect(apiError.status).toBe(401);
        expect(locationStub.href).toBe('');
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).not.toBeNull();
    });

    it('does not redirect or clear auth for a 403 (that path is 401-only)', async () => {
        const locationStub = StubLocation('/menu');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(MakeAxiosError({ status: 403, url: '/admin/users' })),
        );
        expect(apiError.status).toBe(403);
        expect(locationStub.href).toBe('');
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).not.toBeNull();
    });
});

/* ------------------------------------------------------------------------- */
/* Phase I — guards & helpers.                                               */
/* ------------------------------------------------------------------------- */

describe('IsApiError', () => {
    it('returns true only for an ApiError instance', () => {
        expect(IsApiError(new ApiError({ status: 400, message: 'x' }))).toBe(true);
    });

    it('returns false for a plain Error', () => {
        expect(IsApiError(new Error('y'))).toBe(false);
    });

    it('returns false for an ApiError-shaped plain object', () => {
        expect(IsApiError({ status: 400, message: 'z' })).toBe(false);
    });

    it('returns false for null and undefined', () => {
        expect(IsApiError(null)).toBe(false);
        expect(IsApiError(undefined)).toBe(false);
    });
});

describe('ClearStoredAuth', () => {
    it('removes the user session key and leaves any legacy token untouched (M-10)', () => {
        // M-10: the SPA no longer stores/manages a bearer token, so ClearStoredAuth
        // clears only the cached session user. Any pre-existing legacy token key is
        // never written and never removed by our code.
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        localStorage.setItem(LEGACY_ACCESS_TOKEN_KEY, 'stored-token');
        ClearStoredAuth();
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).toBeNull();
        expect(localStorage.getItem(LEGACY_ACCESS_TOKEN_KEY)).toBe('stored-token');
    });

    it('does not throw when storage is already empty', () => {
        localStorage.clear();
        expect(() => ClearStoredAuth()).not.toThrow();
    });
});


/* ------------------------------------------------------------------------- */
/* Phase J — endpoint / method / payload contract for all 8 API objects.      */
/*                                                                            */
/* Every data-returning method reads `response.data`, so each happy path      */
/* primes the matching verb with `mockResolvedValueOnce({ data })`. Fixtures  */
/* keep money and ids as STRINGS, mask card_num, carry NO cvv, and use no     */
/* real credentials (Ochs security rules). DeleteUser is the sole method that */
/* does not read `.data` (HTTP 204, resolves void).                          */
/* ------------------------------------------------------------------------- */

describe('AuthApi', () => {
    it('Login -> POST /auth/login with the credentials, returning the response data', async () => {
        // Throwaway signon fixture: never the README seed pair, never a real secret.
        const loginCredentials: LoginRequest = {
            user_id: 'TESTUSER',
            password: 'x-throwaway-pw',
        };
        const loginResponse = {
            user_id: 'TESTUSER',
            first_name: 'Test',
            last_name: 'User',
            user_type: 'U',
        };
        mockPost.mockResolvedValueOnce({ data: loginResponse, status: 200 });
        const result = await AuthApi.Login(loginCredentials);
        expect(mockPost).toHaveBeenCalledWith('/auth/login', loginCredentials);
        expect(result).toEqual(loginResponse);
    });
});

describe('MenuApi', () => {
    it('GetMenu -> GET /menu', async () => {
        const menuResponse = { menu_options: [], menu_title: 'Main Menu' };
        mockGet.mockResolvedValueOnce({ data: menuResponse });
        const result = await MenuApi.GetMenu();
        expect(mockGet).toHaveBeenCalledWith('/menu');
        expect(result).toEqual(menuResponse);
    });

    it('GetAdminMenu -> GET /admin/menu', async () => {
        const adminMenuResponse = { menu_options: [], menu_title: 'Admin Menu' };
        mockGet.mockResolvedValueOnce({ data: adminMenuResponse });
        const result = await MenuApi.GetAdminMenu();
        expect(mockGet).toHaveBeenCalledWith('/admin/menu');
        expect(result).toEqual(adminMenuResponse);
    });
});

describe('AccountsApi', () => {
    // Money fields are Decimal strings (NUMERIC(12,2)); acct_id preserves leading zeros.
    const accountDetail = {
        acct_id: '00000000011',
        active_status: 'Y',
        curr_bal: '1000.00',
        credit_limit: '5000.00',
        cash_credit_limit: '1000.00',
        open_date: '2020-01-01',
        expiration_date: '2030-01-01',
        reissue_date: '2025-01-01',
        curr_cyc_credit: '0.00',
        curr_cyc_debit: '0.00',
        addr_zip: '12345',
        group_id: 'DEFAULT',
        customer: {
            cust_id: '000000001',
            first_name: 'Test',
            last_name: 'Customer',
            ssn: '***-**-6789',
        },
    };

    it('GetAccount -> GET /accounts/{id}', async () => {
        mockGet.mockResolvedValueOnce({ data: accountDetail });
        const result = await AccountsApi.GetAccount('00000000011');
        expect(mockGet).toHaveBeenCalledWith('/accounts/00000000011');
        expect(result).toEqual(accountDetail);
    });

    it('UpdateAccount -> PUT /accounts/{id} with the update body', async () => {
        // QA finding C2: the backend `AccountUpdate` schema declares `before_image`
        // as REQUIRED (the optimistic-lock echo of the last-read editable fields).
        // A realistic payload therefore carries it alongside the edited fields; the
        // apiClient must forward the body verbatim (before_image included).
        const accountUpdate: AccountUpdate = {
            before_image: {
                active_status: 'Y',
                curr_bal: '1000.00',
                credit_limit: '5000.00',
                cash_credit_limit: '1000.00',
                curr_cyc_credit: '0.00',
                curr_cyc_debit: '0.00',
                expiration_date: '2030-01-01',
                reissue_date: '2025-01-01',
                group_id: 'DEFAULT',
            },
            active_status: 'Y',
            credit_limit: '6000.00',
            cash_credit_limit: '1500.00',
            expiration_date: '2031-01-01',
            reissue_date: '2026-01-01',
            group_id: 'DEFAULT',
        };
        mockPut.mockResolvedValueOnce({ data: accountDetail });
        const result = await AccountsApi.UpdateAccount('00000000011', accountUpdate);
        expect(mockPut).toHaveBeenCalledWith('/accounts/00000000011', accountUpdate);
        expect(result).toEqual(accountDetail);
    });

    it('propagates an optimistic-lock 409 as an ApiError(status=409)', async () => {
        // The method just awaits; the interceptor normalizes the 409 conflict.
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({
                    status: 409,
                    data: { message: 'Account was modified by another user' },
                    url: '/accounts/00000000011',
                }),
            ),
        );
        expect(IsApiError(apiError)).toBe(true);
        expect(apiError.status).toBe(409);
    });
});

describe('CardsApi', () => {
    const maskedCardNum = '************1234';
    const cardRead = {
        card_num: maskedCardNum,
        acct_id: '00000000011',
        embossed_name: 'TEST CARDHOLDER',
        expiration_date: '2030-01-01',
        active_status: 'Y',
    };

    it('ListCards() -> GET /cards with default page 1 and DEFAULT_PAGE_SIZE (7)', async () => {
        // DEFAULT_PAGE_SIZE ties to the legacy COCRDLIC <= 7-rows/page browse (F-004).
        expect(DEFAULT_PAGE_SIZE).toBe(7);
        const cardsPage = MakePaginatedResponse([MakeCardSummary()]);
        mockGet.mockResolvedValueOnce({ data: cardsPage });
        const result = await CardsApi.ListCards();
        expect(mockGet).toHaveBeenCalledWith('/cards', {
            params: { page: 1, page_size: DEFAULT_PAGE_SIZE },
        });
        expect(result).toEqual(cardsPage);
    });

    it('ListCards(params) -> GET /cards forwarding the provided page/page_size', async () => {
        const listParams: PaginationParams = { page: 2, page_size: 7 };
        mockGet.mockResolvedValueOnce({ data: MakePaginatedResponse([]) });
        await CardsApi.ListCards(listParams);
        expect(mockGet).toHaveBeenCalledWith('/cards', {
            params: { page: 2, page_size: 7 },
        });
    });

    it('GetCard -> GET /cards/{cardNum}', async () => {
        mockGet.mockResolvedValueOnce({ data: cardRead });
        const result = await CardsApi.GetCard(maskedCardNum);
        expect(mockGet).toHaveBeenCalledWith(`/cards/${maskedCardNum}`);
        expect(result).toEqual(cardRead);
    });

    it('UpdateCard -> PUT /cards/{cardNum} with the update body (before_image echoed)', async () => {
        // QA finding C06: the backend `CardUpdate` schema declares `before_image`
        // (the client-echoed optimistic-lock token) mandatory, so the client must
        // forward the body verbatim, before_image included.
        const cardUpdate: CardUpdate = {
            before_image: {
                embossed_name: 'TEST CARDHOLDER',
                active_status: 'Y',
                expiration_date: '2030-01-01',
            },
            embossed_name: 'TEST CARDHOLDER',
            expiration_date: '2031-01-01',
            active_status: 'Y',
        };
        mockPut.mockResolvedValueOnce({ data: cardRead });
        const result = await CardsApi.UpdateCard(maskedCardNum, cardUpdate);
        expect(mockPut).toHaveBeenCalledWith(`/cards/${maskedCardNum}`, cardUpdate);
        expect(result).toEqual(cardRead);
    });
});

describe('TransactionsApi', () => {
    const maskedCardNum = '************1234';
    const transactionRead = {
        tran_id: '0000000000000001',
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_source: 'ONLINE',
        tran_desc: 'PURCHASE',
        tran_amt: '123.45',
        merchant_id: '000000001',
        merchant_name: 'TEST MERCHANT',
        merchant_city: 'TEST CITY',
        merchant_zip: '12345',
        card_num: maskedCardNum,
        orig_ts: '2024-01-01T00:00:00Z',
        proc_ts: '2024-01-01T00:00:00Z',
    };
    const transactionSummary = {
        tran_id: '0000000000000001',
        card_num: maskedCardNum,
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_amt: '123.45',
        tran_source: 'ONLINE',
        orig_ts: '2024-01-01T00:00:00Z',
    };

    it('ListTransactions() -> GET /transactions with default page/page_size', async () => {
        const page = MakePaginatedResponse([transactionSummary]);
        mockGet.mockResolvedValueOnce({ data: page });
        const result = await TransactionsApi.ListTransactions();
        expect(mockGet).toHaveBeenCalledWith('/transactions', {
            params: { page: 1, page_size: 7 },
        });
        expect(result).toEqual(page);
    });

    it('GetTransaction -> GET /transactions/{id}', async () => {
        const tranId = '0000000000000001';
        mockGet.mockResolvedValueOnce({ data: transactionRead });
        const result = await TransactionsApi.GetTransaction(tranId);
        expect(mockGet).toHaveBeenCalledWith(`/transactions/${tranId}`);
        expect(result).toEqual(transactionRead);
    });

    it('AddTransaction -> POST /transactions (backend 201) returning the created record', async () => {
        const transactionCreate: TransactionCreate = {
            acct_id: '00000000011',
            card_num: maskedCardNum,
            tran_type_cd: '01',
            tran_cat_cd: '0005',
            tran_source: 'ONLINE',
            tran_desc: 'PURCHASE',
            tran_amt: '123.45',
            merchant_id: '000000001',
            merchant_name: 'TEST MERCHANT',
            merchant_city: 'TEST CITY',
            merchant_zip: '12345',
            orig_ts: '2024-01-01T00:00:00Z',
            proc_ts: '2024-01-01T00:00:00Z',
        };
        // Backend returns 201; the method returns response.data, so status is not asserted.
        mockPost.mockResolvedValueOnce({ data: transactionRead, status: 201 });
        const result = await TransactionsApi.AddTransaction(transactionCreate);
        expect(mockPost).toHaveBeenCalledWith('/transactions', transactionCreate);
        expect(result).toEqual(transactionRead);
    });
});

describe('ReportsApi', () => {
    const reportRequest: ReportRequest = {
        report_type: ReportType.Monthly,
        start_date: '2024-01-01',
        end_date: '2024-01-31',
    };
    const reportResponse = {
        report_type: ReportType.Monthly,
        start_date: '2024-01-01',
        end_date: '2024-01-31',
        rows: [],
        page_total: '0.00',
        account_total: '0.00',
        grand_total: '0.00',
    };

    it('GetTransactionReport -> GET /reports/transactions with format=json params', async () => {
        mockGet.mockResolvedValueOnce({ data: reportResponse });
        const result = await ReportsApi.GetTransactionReport(reportRequest);
        expect(mockGet).toHaveBeenCalledWith(
            '/reports/transactions',
            expect.objectContaining({
                params: expect.objectContaining({
                    report_type: ReportType.Monthly,
                    start_date: '2024-01-01',
                    end_date: '2024-01-31',
                    format: 'json',
                }),
            }),
        );
        expect(result).toEqual(reportResponse);
    });

    it('includes the optional confirm flag in the query when present', async () => {
        const confirmedRequest: ReportRequest = { ...reportRequest, confirm: 'Y' };
        mockGet.mockResolvedValueOnce({ data: reportResponse });
        await ReportsApi.GetTransactionReport(confirmedRequest);
        expect(mockGet).toHaveBeenCalledWith(
            '/reports/transactions',
            expect.objectContaining({
                params: expect.objectContaining({ confirm: 'Y', format: 'json' }),
            }),
        );
    });

    it.each<'csv' | 'pdf'>(['csv', 'pdf'])(
        'DownloadTransactionReport(%s) -> GET blob with responseType blob',
        async (format) => {
            const reportBlob = new Blob(['a,b,c']);
            mockGet.mockResolvedValueOnce({ data: reportBlob });
            const result = await ReportsApi.DownloadTransactionReport(reportRequest, format);
            expect(mockGet).toHaveBeenCalledWith(
                '/reports/transactions',
                expect.objectContaining({
                    params: expect.objectContaining({ format }),
                    responseType: 'blob',
                }),
            );
            expect(result).toBe(reportBlob);
        },
    );
});

describe('BillPayApi', () => {
    const billPayResponse = {
        acct_id: '00000000011',
        curr_bal: '1000.00',
        credit_limit: '5000.00',
        available_credit: '4000.00',
        payment_amount: '1000.00',
    };

    it('GetBillPayInfo -> GET /billpay/{id}', async () => {
        mockGet.mockResolvedValueOnce({ data: billPayResponse });
        const result = await BillPayApi.GetBillPayInfo('00000000011');
        expect(mockGet).toHaveBeenCalledWith('/billpay/00000000011');
        expect(result).toEqual(billPayResponse);
    });

    it('PayBill -> POST /billpay (HTTP 200 action, NOT 201)', async () => {
        const billPayRequest: BillPayRequest = {
            acct_id: '00000000011',
            confirm: 'Y',
        };
        // Bill payment is an action: the backend answers 200, not a 201 creation.
        mockPost.mockResolvedValueOnce({ data: billPayResponse, status: 200 });
        const result = await BillPayApi.PayBill(billPayRequest);
        expect(mockPost).toHaveBeenCalledWith('/billpay', billPayRequest);
        expect(result).toEqual(billPayResponse);
    });
});

describe('UsersApi', () => {
    // Admin-gated resource. Read fixtures never carry a password field.
    const userRead = {
        user_id: 'NEWUSER1',
        first_name: 'New',
        last_name: 'User',
        user_type: 'U',
    };

    it('ListUsers() -> GET /admin/users with default page/page_size', async () => {
        const usersPage = MakePaginatedResponse([MakeUserSummary()]);
        mockGet.mockResolvedValueOnce({ data: usersPage });
        const result = await UsersApi.ListUsers();
        expect(mockGet).toHaveBeenCalledWith('/admin/users', {
            params: { page: 1, page_size: 7 },
        });
        expect(result).toEqual(usersPage);
    });

    it('AddUser -> POST /admin/users (HTTP 201) returning the created user', async () => {
        const userCreate: UserCreate = {
            user_id: 'NEWUSER1',
            first_name: 'New',
            last_name: 'User',
            // Throwaway write-only value required by UserCreate; not a real credential.
            password: 'x-throwaway-pw',
            user_type: 'U',
        };
        mockPost.mockResolvedValueOnce({ data: userRead, status: 201 });
        const result = await UsersApi.AddUser(userCreate);
        expect(mockPost).toHaveBeenCalledWith('/admin/users', userCreate);
        expect(result).toEqual(userRead);
    });

    it('GetUser -> GET /admin/users/{id}', async () => {
        mockGet.mockResolvedValueOnce({ data: userRead });
        const result = await UsersApi.GetUser('NEWUSER1');
        expect(mockGet).toHaveBeenCalledWith('/admin/users/NEWUSER1');
        expect(result).toEqual(userRead);
    });

    it('UpdateUser -> PUT /admin/users/{id} with the update body', async () => {
        const userUpdate: UserUpdate = {
            first_name: 'New',
            last_name: 'Name',
            user_type: 'U',
        };
        mockPut.mockResolvedValueOnce({ data: userRead });
        const result = await UsersApi.UpdateUser('NEWUSER1', userUpdate);
        expect(mockPut).toHaveBeenCalledWith('/admin/users/NEWUSER1', userUpdate);
        expect(result).toEqual(userRead);
    });

    it('DeleteUser -> DELETE /admin/users/{id} (HTTP 204) resolving void, not reading data', async () => {
        // No `data` on the mocked response: the method must not read it.
        mockDelete.mockResolvedValueOnce({ status: 204 });
        await expect(UsersApi.DeleteUser('NEWUSER1')).resolves.toBeUndefined();
        expect(mockDelete).toHaveBeenCalledWith('/admin/users/NEWUSER1');
    });

    it('surfaces a server-enforced admin gate (403) as an ApiError without redirect/clear', async () => {
        const locationStub = StubLocation('/menu');
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(MakeCurrentUser()));
        const apiError = await ExpectApiErrorRejection(
            responseOnRejected(
                MakeAxiosError({
                    status: 403,
                    data: { message: 'Admin privileges required' },
                    url: '/admin/users',
                }),
            ),
        );
        expect(apiError.status).toBe(403);
        expect(locationStub.href).toBe('');
        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).not.toBeNull();
    });
});

