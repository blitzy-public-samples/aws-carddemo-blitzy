/**
 * :module: ``frontend/src/api/client.ts``
 * :purpose: Provide the single shared, pre-configured axios instance used by
 *   every CardDemo domain API module (``auth``, ``accounts``, ``cards``,
 *   ``transactions``, ``billpay``, ``reports``, ``users``, ``menu``). It
 *   centralizes the api-gateway base URL, the externalized-session credential
 *   (the Spring Session cookie), correlation-id propagation for distributed
 *   tracing, and HTTP-error normalization, re-expressing the legacy CICS
 *   COMMAREA/session and ``RESP`` return-code handling model
 *   (``app/cpy/COCOM01Y.cpy``, ``app/cbl/COACTUPC.cbl``) as axios request and
 *   response interceptors.
 * :output: The default export ``apiClient`` (a configured ``AxiosInstance``),
 *   plus the named exports ``ApiError`` (the normalized error type) and
 *   ``isApiError`` (its type-guard).
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
 *   request is rejected with HTTP ``401``.
 */
const SIGNON_ROUTE = '/signon';

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
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body?: ApiErrorResponse;
  readonly isOptimisticLockConflict: boolean;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorResponse,
    isOptimisticLockConflict = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
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
    return config;
  },
  (error: unknown): Promise<never> => Promise.reject(error),
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
 * :purpose: Response interceptor. Passes successful responses through unchanged
 *   (wire formats are preserved: money stays a string, dates stay
 *   ``YYYY-MM-DD``) and normalizes every failure into an ``ApiError``.
 * :behavior: ``409`` becomes an optimistic-lock conflict carrying the verbatim
 *   legacy message; ``401`` triggers a guarded redirect to the sign-on route
 *   before rejecting; every other status, and network failures (``status`` 0),
 *   reject with a normalized ``ApiError``.
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

    if (status === 409) {
      return Promise.reject(new ApiError(409, message, body, true));
    }

    if (status === 401) {
      if (
        typeof window !== 'undefined' &&
        window.location.pathname !== SIGNON_ROUTE
      ) {
        window.location.assign(SIGNON_ROUTE);
      }
      return Promise.reject(new ApiError(401, message, body));
    }

    return Promise.reject(new ApiError(status ?? 0, message, body));
  },
);

export default apiClient;
