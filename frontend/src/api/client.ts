/**
 * :module: ``frontend/src/api/client.ts``
 * :purpose: Provide the single shared, pre-configured axios instance used by
 *   every CardDemo domain API module (``auth``, ``accounts``, ``cards``,
 *   ``transactions``, ``billpay``, ``reports``, ``users``, ``menu``). It
 *   centralizes the api-gateway base URL, the externalized-session credential
 *   (the Spring Session cookie), CSRF double-submit, correlation-id propagation
 *   for distributed tracing, HTTP-error normalization, and the single place where
 *   an expired (``401``) or refused (``403``) session clears the client's local
 *   authority. It re-expresses the legacy CICS COMMAREA/session and ``RESP``
 *   return-code handling model (``app/cpy/COCOM01Y.cpy``,
 *   ``app/cbl/COACTUPC.cbl``) as axios request and response interceptors.
 * :output: The default export ``apiClient`` (a configured ``AxiosInstance``), the
 *   named exports ``ApiError`` (the normalized error type carrying a safe
 *   ``correlationId`` support reference) and ``isApiError`` (its type-guard), and
 *   ``registerSessionExpiryHandler`` / ``clearLocalCredentials`` used by the
 *   session store to drop local authority exactly once per expiry.
 * :note: This module never reads the Vite build-time environment directly; the
 *   base URL is obtained only through ``getApiBaseUrl`` from ``./config``, so the
 *   module is evaluable under Jest (jsdom) without any Vite environment injection.
 */

import axios, {
  AxiosError,
  AxiosInstance,
  InternalAxiosRequestConfig,
  AxiosResponse,
} from 'axios';
import { getApiBaseUrl } from './config';
import type { ApiErrorResponse } from '../types';

/**
 * :purpose: Per-request opt-out of the shared session-expiry handling, declared on the
 *   axios request config. The sign-on POST, the ``GET /session`` probe and the
 *   ``POST /logout`` revocation set it so a ``401``/``403`` on those calls is reported to
 *   their caller instead of triggering another local sign-out and redirect.
 */
declare module 'axios' {
  interface AxiosRequestConfig {
    skipAuthRedirect?: boolean;
  }
}

/**
 * :purpose: Verbatim optimistic-lock conflict message surfaced on an HTTP
 *   ``409``; matches the legacy ``COACTUPC`` condition
 *   ``DATA-WAS-CHANGED-BEFORE-UPDATE`` screen text character-for-character.
 */
const OPTIMISTIC_LOCK_CONFLICT_MESSAGE =
  'Record changed by some one else. Please review';

/**
 * :purpose: Name of the request header carrying the per-request correlation id
 *   consumed by the backend MDC (``logback-spring.xml``) and the tracing bridge.
 */
const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/**
 * :purpose: ``sessionStorage`` key holding the optional bearer token used by
 *   JWT-based deployments in place of the cookie session.
 */
const JWT_STORAGE_KEY = 'carddemo.jwt';

/**
 * :purpose: Client route of the sign-on screen; the redirect target when a
 *   request is rejected with HTTP ``401`` or ``403``.
 */
const SIGNON_ROUTE = '/signon';

/**
 * :purpose: Name of the script-readable double-submit cookie the api-gateway issues,
 *   whose value must be echoed on every state-changing request.
 */
const CSRF_COOKIE_NAME = 'XSRF-TOKEN';

/**
 * :purpose: Name of the request header carrying the echoed CSRF token.
 */
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

/**
 * :purpose: HTTP methods that carry no CSRF requirement because they change no state.
 */
const CSRF_SAFE_METHODS = new Set(['get', 'head', 'options', 'trace']);

/**
 * :purpose: Generic, non-empty fallback message for a failure that carries
 *   neither a backend body message nor an axios error message.
 */
const GENERIC_ERROR_MESSAGE = 'Unexpected error';

/**
 * :purpose: Normalized, typed error raised for every failed CardDemo REST call
 *   so pages and hooks branch on the outcome without re-inspecting axios
 *   internals.
 * :param status: HTTP status code, or ``0`` for a network/transport failure
 *   (timeout, offline, DNS) where no response was received.
 * :param message: already-resolved, human-readable error message.
 * :param body: standardized backend error body when the response carried one;
 *   ``undefined`` for network failures or non-JSON responses.
 * :param isOptimisticLockConflict: ``true`` only for the ``409`` conflict that
 *   corresponds to the backend ``@Version`` optimistic-lock failure.
 * :param correlationId: the safe support reference for this failure — the
 *   ``X-Correlation-Id`` the backend echoed and stamped on every log record for the
 *   request. It is not sensitive and is the value an operator quotes; ``undefined``
 *   when neither the response body nor the response headers carried one.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body?: ApiErrorResponse;
  readonly isOptimisticLockConflict: boolean;
  readonly correlationId?: string;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorResponse,
    isOptimisticLockConflict = false,
    correlationId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
    this.correlationId = correlationId;
  }
}

/**
 * :purpose: Type-guard narrowing an unknown thrown value to ``ApiError``.
 * :param err: the caught value.
 * :returns: ``true`` when ``err`` is an ``ApiError`` instance.
 */
export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError;
}

/**
 * :purpose: The shared axios instance for every domain API module, configured
 *   with the api-gateway base URL, ``withCredentials`` so the externalized
 *   Spring Session cookie is sent on every request, and JSON content
 *   negotiation.
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: getApiBaseUrl(),
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

/**
 * :purpose: Callbacks the session store registers so it learns, from one place, that the
 *   server no longer accepts the caller's session and must drop its local authority.
 */
const sessionExpiryHandlers = new Set<() => void>();

/**
 * :purpose: Register a callback invoked once per rejected request when the server reports
 *   that the session is no longer usable (``401``) or refuses it (``403``).
 * :param handler: the callback to invoke.
 * :returns: an unregister function that removes the callback.
 */
export function registerSessionExpiryHandler(handler: () => void): () => void {
  sessionExpiryHandlers.add(handler);
  return () => {
    sessionExpiryHandlers.delete(handler);
  };
}

/**
 * :purpose: Remove every locally held credential: the optional bearer token and the
 *   persisted session entry. Called by the session store on sign-out and by the
 *   centralized expiry handling below, so no code path can leave a stale token behind
 *   for the next request interceptor to attach.
 */
export function clearLocalCredentials(): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }
  try {
    sessionStorage.removeItem(JWT_STORAGE_KEY);
  } catch {
    // sessionStorage may be unavailable (private mode); clearing is best-effort and must
    // never prevent the redirect that follows it.
  }
}

/**
 * :purpose: Read a browser cookie value by name.
 * :param name: the cookie name.
 * :returns: the decoded cookie value, or ``undefined`` when absent or unavailable.
 */
function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined' || typeof document.cookie !== 'string') {
    return undefined;
  }
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const entry = part.trim();
    if (entry.startsWith(prefix)) {
      return decodeURIComponent(entry.slice(prefix.length));
    }
  }
  return undefined;
}

/**
 * :purpose: Notify every registered handler that the session is no longer usable and
 *   remove the locally held credentials, then redirect to the sign-on screen. Runs at most
 *   once per rejected request and is skipped for a request that opted out.
 * :param config: the originating request config, consulted for the opt-out flag.
 */
function handleSessionRejected(config: InternalAxiosRequestConfig | undefined): void {
  if (config?.skipAuthRedirect === true) {
    return;
  }
  clearLocalCredentials();
  sessionExpiryHandlers.forEach((handler) => handler());
  if (
    typeof window !== 'undefined' &&
    window.location.pathname !== SIGNON_ROUTE
  ) {
    window.location.assign(SIGNON_ROUTE);
  }
}

/**
 * :purpose: Produce a correlation id for the ``X-Correlation-Id`` header.
 * :returns: An RFC-4122 v4 UUID when the Web Crypto API is available, otherwise
 *   a timestamp-based fallback id that remains unique per request.
 */
function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `cid-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * :purpose: Request interceptor. Attaches the optional JWT bearer token (when
 *   present in ``sessionStorage`` and no ``Authorization`` header is already
 *   set) and guarantees an ``X-Correlation-Id`` header so tracing spans the
 *   SPA -> gateway -> services boundary. The cookie session is carried
 *   automatically by ``withCredentials`` and needs no code here.
 * :param config: the outgoing request configuration.
 * :returns: the (possibly header-augmented) request configuration.
 */
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig): InternalAxiosRequestConfig => {
    if (typeof sessionStorage !== 'undefined') {
      const token = sessionStorage.getItem(JWT_STORAGE_KEY);
      if (token && !config.headers.Authorization) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }
    if (!config.headers[CORRELATION_ID_HEADER]) {
      config.headers[CORRELATION_ID_HEADER] = generateCorrelationId();
    }
    const method = (config.method ?? 'get').toLowerCase();
    if (!CSRF_SAFE_METHODS.has(method) && !config.headers[CSRF_HEADER_NAME]) {
      const csrfToken = readCookie(CSRF_COOKIE_NAME);
      if (csrfToken) {
        config.headers[CSRF_HEADER_NAME] = csrfToken;
      }
    }
    return config;
  },
  (error: unknown): Promise<never> =>
    Promise.reject(error instanceof Error ? error : new Error(String(error))),
);

/**
 * :purpose: Resolve a human-readable message for a failed response.
 * :param status: the HTTP status code, or ``undefined`` for a network failure.
 * :param body: the parsed backend error body when present.
 * :param error: the originating axios error; its ``message`` is the last resort
 *   for transport failures.
 * :returns: the backend ``message`` when non-empty; for a ``409`` the verbatim
 *   optimistic-lock literal when the body omits one; otherwise the axios
 *   message, falling back to a generic string.
 */
function resolveMessage(
  status: number | undefined,
  body: ApiErrorResponse | undefined,
  error: AxiosError<ApiErrorResponse>,
): string {
  const bodyMessage = body?.message;
  if (status === 409) {
    return bodyMessage && bodyMessage.length > 0
      ? bodyMessage
      : OPTIMISTIC_LOCK_CONFLICT_MESSAGE;
  }
  if (bodyMessage && bodyMessage.length > 0) {
    return bodyMessage;
  }
  if (error.message && error.message.length > 0) {
    return error.message;
  }
  return GENERIC_ERROR_MESSAGE;
}

/**
 * :purpose: Resolve the safe support reference for a failed response: the correlation id
 *   the backend echoed. The response body's ``correlationId`` is preferred because the
 *   shared error envelope always carries it; the ``X-Correlation-Id`` response header is
 *   the fallback for a failure that produced no envelope.
 * :param error: the originating axios error.
 * :param body: the parsed backend error body when present.
 * :returns: the correlation id, or ``undefined`` when neither source carried one.
 */
function resolveCorrelationId(
  error: AxiosError<ApiErrorResponse>,
  body: ApiErrorResponse | undefined,
): string | undefined {
  const fromBody = body?.correlationId;
  if (fromBody && fromBody.length > 0) {
    return fromBody;
  }
  const headers: unknown = error.response?.headers;
  if (headers === null || typeof headers !== 'object') {
    return undefined;
  }
  const header: unknown = (headers as Record<string, unknown>)[
    CORRELATION_ID_HEADER.toLowerCase()
  ];
  return typeof header === 'string' && header.length > 0 ? header : undefined;
}

/**
 * :purpose: Response interceptor. Passes successful responses through unchanged
 *   (wire formats are preserved: money stays a string, dates stay
 *   ``YYYY-MM-DD``) and normalizes every failure into an ``ApiError``.
 * :behavior: ``409`` becomes an optimistic-lock conflict carrying the verbatim
 *   legacy message; ``401`` and ``403`` both mean the server will not act on this
 *   session, so both clear the locally held credentials, notify the registered
 *   session-expiry handlers and redirect to the sign-on route before rejecting;
 *   every other status, and network failures (``status`` 0), reject with a
 *   normalized ``ApiError``. A request that set ``skipAuthRedirect`` is exempt from
 *   the expiry handling so sign-on, the session probe and logout report their own
 *   outcome.
 * :param response: the fulfilled response, returned unchanged.
 * :param error: the axios error, mapped to and rejected as an ``ApiError``.
 * :returns: the response on success; a rejected ``Promise`` carrying an
 *   ``ApiError`` on failure.
 */
apiClient.interceptors.response.use(
  (response: AxiosResponse): AxiosResponse => response,
  (error: AxiosError<ApiErrorResponse>): Promise<never> => {
    const status = error.response?.status;
    const body = error.response?.data;
    const message = resolveMessage(status, body, error);
    const correlationId = resolveCorrelationId(error, body);

    if (status === 409) {
      return Promise.reject(
        new ApiError(409, message, body, true, correlationId),
      );
    }

    if (status === 401 || status === 403) {
      handleSessionRejected(error.config);
      return Promise.reject(
        new ApiError(status, message, body, false, correlationId),
      );
    }

    return Promise.reject(
      new ApiError(status ?? 0, message, body, false, correlationId),
    );
  },
);

export default apiClient;
