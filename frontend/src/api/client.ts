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
 * :note: It navigates nowhere. Clearing the local authority is what returns the
 *   operator to the sign-on screen, through the route guard and therefore
 *   CLIENT-SIDE; a full-document assignment would discard and re-parse the whole
 *   application on every expiry and take the store's message with it.
 * :output: The default export ``apiClient`` (a configured ``AxiosInstance``), the
 *   named exports ``ApiError`` (the normalized error type carrying a safe
 *   ``correlationId`` support reference) and ``isApiError`` (its type-guard),
 *   ``registerSessionExpiryHandler`` used by the session store to drop local
 *   authority exactly once per expiry, ``runWithRequestSignal``, which binds an
 *   ``AbortSignal`` to every request a call issues so an abandoned screen really
 *   cancels its work, ``isCancelledRequest``, its companion predicate, and
 *   ``generateCorrelationId``, the one correlation-id source the request
 *   interceptor and the SPA's uncaught-error telemetry both draw from.
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
import { GENERIC_ERROR_MESSAGE, SESSION_ENDED_MESSAGE } from './messages';
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
 * :purpose: Why the server would not act on the caller's session, handed to every
 *   registered handler so the session store can tell an EXPIRED session (HTTP
 *   ``401`` — the cookie session is gone, a state the operator did not cause and is
 *   told about) from a REFUSED one (HTTP ``403`` — the session is live but this call
 *   is not permitted, which the answering program reports in its own screen message).
 * :note: Only ``'expired'`` is raised from this module: a refusal is recoverable in
 *   place, so a ``403`` leaves the session, the screen and the operator's entry
 *   fields alone and is reported by the screen that received it. ``'refused'``
 *   remains part of the published handler contract for a caller that must report a
 *   refusal as a loss of local authority.
 */
export type SessionRejectionReason = 'expired' | 'refused';

/**
 * :purpose: Name of the script-readable double-submit cookie the api-gateway issues,
 *   whose value must be echoed on every state-changing request.
 */
const CSRF_COOKIE_NAME = 'XSRF-TOKEN';

/**
 * :purpose: Safe endpoint used solely to obtain a CSRF token when the cookie is
 *   absent. ``POST /logout`` clears the cookie, so without this the next write on
 *   the same document would be sent with no token and refused; the identity probe
 *   is chosen because it is idempotent, carries no payload and issues the cookie
 *   whether or not a session is live.
 */
const CSRF_PRIME_PATH = '/session';

/**
 * :purpose: Name of the request header carrying the echoed CSRF token.
 */
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

/**
 * :purpose: Upper bound, in milliseconds, on how long one request may remain
 *   outstanding. It bounds the interval for which the shell holds the keyboard
 *   locked, so no fault can leave a screen permanently unable to accept a key.
 */
const REQUEST_TIMEOUT_MS = 30_000;

/**
 * :purpose: HTTP methods that carry no CSRF requirement because they change no state.
 */
const CSRF_SAFE_METHODS = new Set(['get', 'head', 'options', 'trace']);



/**
 * :purpose: Line-23 text for a request that never reached the server — offline, DNS
 *   failure, timeout or a dropped connection. It replaces axios's own ``Network
 *   Error``, which is library wording no operator can act on, with a single stable
 *   sentence in the register the screens use elsewhere.
 */
const TRANSPORT_ERROR_MESSAGE =
  'Unable to reach the server. Please try again.';

/**
 * :purpose: Line-23 text for a request the server refused although the session is
 *   still usable — the double-submit CSRF token was missing or stale, or the
 *   principal lacks the authority for that call. Both are recoverable in place, so
 *   the message asks for the action rather than ending the session.
 */
const REQUEST_REFUSED_MESSAGE =
  'Request could not be authorized. Please try again.';

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
 * :param cancelled: ``true`` only when the request was ABORTED by the caller (a
 *   superseded or abandoned call) rather than rejected by the server or the network.
 *   Always accompanied by ``status`` ``0``, and never a failure the operator caused,
 *   so it is not reported on a screen's message line.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body?: ApiErrorResponse;
  readonly isOptimisticLockConflict: boolean;
  readonly correlationId?: string;
  readonly cancelled: boolean;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorResponse,
    isOptimisticLockConflict = false,
    correlationId?: string,
    cancelled = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
    this.correlationId = correlationId;
    this.cancelled = cancelled;
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
  // A 3270 keyboard stays locked for the whole of a transaction, and the shell
  // reproduces that, so a request must always end. The bound is far above the 200 ms
  // 95th-percentile target the project holds itself to, and a request that reaches it
  // fails as a transport error and releases the keyboard rather than stranding the
  // operator on a screen that can no longer be left.
  timeout: REQUEST_TIMEOUT_MS,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

/**
 * :purpose: Callbacks the session store registers so it learns, from one place, that the
 *   server no longer accepts the caller's session and must drop its local authority.
 */
const sessionExpiryHandlers = new Set<(reason: SessionRejectionReason) => void>();

/**
 * :purpose: Register a callback invoked once per rejected request when the server reports
 *   that the session is no longer usable (``401``) or refuses it (``403``).
 * :param handler: the callback to invoke; receives the
 *   :ts:type:`SessionRejectionReason` so the store can distinguish an expiry the
 *   operator must be told about from a refusal the answering program reports itself.
 * :returns: an unregister function that removes the callback.
 */
export function registerSessionExpiryHandler(
  handler: (reason: SessionRejectionReason) => void,
): () => void {
  sessionExpiryHandlers.add(handler);
  return () => {
    sessionExpiryHandlers.delete(handler);
  };
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
 * :purpose: Notify every registered handler that the server will not act on the caller's
 *   session. Runs at most once per rejected request and is skipped for a request that
 *   opted out.
 * :param config: the originating request config, consulted for the opt-out flag.
 * :param reason: why the session was rejected; ``'expired'`` for the ``401`` this
 *   module raises it on.
 * :note: This module performs NO navigation. Dropping the local authority is what moves
 *   the operator: the route guard sees a resolved, signed-out session and navigates to
 *   the sign-on screen CLIENT-SIDE, so the single-page application is not re-downloaded
 *   and re-parsed on every expiry, and the notice the store publishes survives the hop
 *   onto line 23. The previous ``window.location.assign`` replaced the document, which
 *   discarded that message along with everything else and left the operator on a blank
 *   sign-on screen with no explanation.
 */
function handleSessionRejected(
  config: InternalAxiosRequestConfig | undefined,
  reason: SessionRejectionReason,
): void {
  if (config?.skipAuthRedirect === true) {
    return;
  }
  sessionExpiryHandlers.forEach((handler) => handler(reason));
}

/**
 * :purpose: Memo of the in-flight CSRF priming request, so several writes issued
 *   before the cookie exists share one round trip instead of racing.
 */
let csrfPrime: Promise<string | undefined> | null = null;

/**
 * :purpose: Obtain a CSRF token when the double-submit cookie is absent, by issuing
 *   the safe identity probe the gateway answers with a fresh ``XSRF-TOKEN`` cookie.
 * :returns: the token now in the cookie jar, or ``undefined`` when the probe produced
 *   none. A failed probe is not propagated: the caller's own request is the one whose
 *   outcome matters, and it will be refused with the server's own message if the token
 *   is still missing.
 */
async function primeCsrfToken(): Promise<string | undefined> {
  csrfPrime ??= (async (): Promise<string | undefined> => {
    try {
      await apiClient.get(CSRF_PRIME_PATH, { skipAuthRedirect: true });
    } catch {
      // A 401 is the expected answer for a signed-out caller and still carries the
      // Set-Cookie, so the outcome of the probe is read from the jar, not from here.
    }
    return readCookie(CSRF_COOKIE_NAME);
  })().finally(() => {
    csrfPrime = null;
  });
  return csrfPrime;
}

/**
 * :purpose: The ``AbortSignal`` bound to the call currently being issued, or
 *   ``undefined`` outside such a call. Read by the request interceptor, which runs
 *   SYNCHRONOUSLY inside ``apiClient.get`` / ``.post`` / ``.put`` / ``.delete``
 *   (see :func:`runWithRequestSignal`), so the value it reads is always the signal
 *   of the call being issued even when several calls are started in one tick.
 */
let ambientRequestSignal: AbortSignal | undefined;

/**
 * :purpose: Bind an ``AbortSignal`` to every request the given call issues, so a
 *   superseded or abandoned call stops the work it no longer needs instead of
 *   leaving the server to service a response nobody will read. It is the transport
 *   half of the one-in-flight-task-per-terminal model: the caller (``useApi``)
 *   aborts the controller, and this scope is what makes that abort reach axios.
 * :param signal: the signal to bind; a request that carries its own ``signal``
 *   keeps it and is never re-bound.
 * :param call: the function that issues the request(s); invoked immediately.
 * :returns: whatever ``call`` returns (normally the pending ``Promise``).
 * :note: The binding covers the requests ``call`` starts SYNCHRONOUSLY. That is
 *   every domain api function, each of which issues exactly one request before it
 *   awaits; a request started after an ``await`` inside ``call`` carries no ambient
 *   signal. The scope restores the previous value on the way out, so nesting is
 *   safe, and it never leaks across ticks.
 */
export function runWithRequestSignal<T>(
  signal: AbortSignal,
  call: () => T,
): T {
  const previous = ambientRequestSignal;
  ambientRequestSignal = signal;
  try {
    return call();
  } finally {
    ambientRequestSignal = previous;
  }
}

/**
 * :purpose: Whether a caught value is a request that was cancelled rather than a
 *   request that failed — the outcome of aborting a superseded call. A cancellation
 *   is not an error the operator caused and must never reach a screen's message
 *   line.
 * :param value: the caught value; an ``ApiError`` normalized from axios, an
 *   ``AxiosError``, or a ``DOMException`` raised by the abort itself.
 * :returns: ``true`` when the value reports a cancelled/aborted request.
 */
export function isCancelledRequest(value: unknown): boolean {
  if (axios.isCancel(value)) {
    return true;
  }
  if (value instanceof ApiError) {
    return value.status === 0 && value.cancelled;
  }
  return value instanceof Error && value.name === 'AbortError';
}

/**
 * :purpose: Produce a correlation id for the ``X-Correlation-Id`` header, and for a
 *   client-side fault that never reached the server and so was never stamped with
 *   one by the gateway.
 * :returns: An RFC-4122 v4 UUID when the Web Crypto API is available, otherwise
 *   a timestamp-based fallback id that remains unique per request.
 */
export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `cid-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * :purpose: Request interceptor. Guarantees an ``X-Correlation-Id`` header so tracing
 *   spans the SPA -> gateway -> services boundary, echoes the CSRF cookie on every
 *   unsafe method, and binds the cancellation signal of the call being issued (see
 *   :func:`runWithRequestSignal`) so an abandoned call really stops. The session
 *   cookie is the only credential and is carried automatically by
 *   ``withCredentials``, so no credential code belongs here.
 * :param config: the outgoing request configuration.
 * :returns: the (possibly header-augmented) request configuration.
 * :note: Registered ``synchronous`` — every step below is pure and synchronous, and
 *   axios then runs the whole request-interceptor chain INSIDE the ``apiClient.get``
 *   / ``.post`` / ``.put`` / ``.delete`` call rather than in a later microtask. That
 *   is what makes the ambient signal binding exact: several calls started in one tick
 *   each read their own signal instead of racing for the last one written.
 */
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig): InternalAxiosRequestConfig => {
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
    // An explicit per-request signal always wins; only a request that declares none
    // adopts the signal of the call issuing it.
    if (config.signal === undefined && ambientRequestSignal !== undefined) {
      config.signal = ambientRequestSignal;
    }
    return config;
  },
  (error: unknown): Promise<never> =>
    Promise.reject(error instanceof Error ? error : new Error(String(error))),
  { synchronous: true },
);

/**
 * :purpose: Whether a request has to obtain a CSRF token before it can be sent — an
 *   unsafe method with no token of its own and none in the cookie jar.
 * :param config: the outgoing request configuration, already method-normalized and
 *   header-flattened by axios.
 * :returns: ``true`` when the token must be primed for this request.
 */
function needsCsrfPriming(config: InternalAxiosRequestConfig): boolean {
  const method = (config.method ?? 'get').toLowerCase();
  return (
    !CSRF_SAFE_METHODS.has(method) &&
    !config.headers[CSRF_HEADER_NAME] &&
    readCookie(CSRF_COOKIE_NAME) === undefined
  );
}

/**
 * :purpose: Obtain a CSRF token for the rare write that has none, so a first write
 *   after the cookie was dropped (a sign-off, a restored tab, a cleared cookie jar)
 *   succeeds on the current document instead of needing a reload.
 * :note: This step has to AWAIT a round trip, so it is registered separately and gated
 *   by ``runWhen``: axios skips a gated-out interceptor before it decides whether the
 *   chain can run synchronously, so every request that already has a token — which is
 *   every request but this one case — keeps the fully synchronous chain the interceptor
 *   above depends on for its exact signal binding. The one request that does prime runs
 *   through the promise chain and therefore carries no ambient cancellation signal; it
 *   is a recovery step that must complete, not a browse the operator can supersede.
 */
apiClient.interceptors.request.use(
  async (config: InternalAxiosRequestConfig): Promise<InternalAxiosRequestConfig> => {
    const csrfToken = await primeCsrfToken();
    if (csrfToken) {
      config.headers[CSRF_HEADER_NAME] = csrfToken;
    }
    return config;
  },
  (error: unknown): Promise<never> =>
    Promise.reject(error instanceof Error ? error : new Error(String(error))),
  { runWhen: needsCsrfPriming },
);

/**
 * :purpose: Resolve the line-23 message for a failed response.
 * :param status: the HTTP status code, or ``undefined`` for a network failure.
 * :param body: the parsed backend error body when present.
 * :returns: the backend ``message`` when non-empty; for a ``409`` the verbatim
 *   optimistic-lock literal when the body omits one; otherwise
 *   :data:`GENERIC_ERROR_MESSAGE`. An axios diagnostic is never returned.
 */
function resolveMessage(
  status: number | undefined,
  body: ApiErrorResponse | undefined,
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
  // A rejected session and a refused request both answer with a zero-byte body, so
  // the reason has to be supplied here or the screen reports nothing at all.
  if (status === 401) {
    return SESSION_ENDED_MESSAGE;
  }
  if (status === 403) {
    return REQUEST_REFUSED_MESSAGE;
  }
  // No response at all: axios's own ``Network Error`` is library wording, so the
  // transport failure is reported in the register the screens use.
  if (status === undefined) {
    return TRANSPORT_ERROR_MESSAGE;
  }
  // Deliberately NOT `error.message`. A failure that produced no application
  // envelope — an nginx or gateway rejection, a StrictHttpFirewall refusal — leaves
  // axios' own diagnostic there ("Request failed with status code 400"), and a library
  // diagnostic is not a screen message: line 23 is a BMS field and only ever carries
  // the application's own text.
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
 * :behavior: a request ABORTED by its caller rejects with a cancelled ``ApiError``
 *   (``status`` ``0``, ``cancelled`` ``true``) and triggers no session handling at
 *   all — it is not a failure; ``409`` becomes an optimistic-lock conflict carrying
 *   the verbatim legacy message; ``401`` means the session itself is gone, so it
 *   drops the locally held authority and lets the route guard return the caller to
 *   sign-on with the reason on line 23; ``403`` means the session is still live but
 *   this request was refused — a missing or stale double-submit token, or a call the
 *   principal has no authority for — so it is reported in place, the screen and its
 *   entry fields are kept, and the CSRF token is refreshed so the operator's retry
 *   can succeed; every other status, and network failures (``status`` 0), reject with
 *   a normalized ``ApiError``. A request that set ``skipAuthRedirect`` is exempt from
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
    // A cancelled request is the caller having moved on, not a failure: it carries no
    // response, must not clear the session, and must never reach a message line.
    if (axios.isCancel(error)) {
      return Promise.reject(
        new ApiError(
          0,
          error.message.length > 0 ? error.message : 'canceled',
          undefined,
          false,
          undefined,
          true,
        ),
      );
    }

    const status = error.response?.status;
    const body = error.response?.data;
    const message = resolveMessage(status, body);
    const correlationId = resolveCorrelationId(error, body);

    if (status === 409) {
      return Promise.reject(
        new ApiError(409, message, body, true, correlationId),
      );
    }

    if (status === 401) {
      handleSessionRejected(error.config, 'expired');
      return Promise.reject(
        new ApiError(status, message, body, false, correlationId),
      );
    }

    if (status === 403) {
      // The session is untouched: a refused request is recoverable, so the only
      // repair made here is to replace the double-submit token that most likely
      // caused it. The caller keeps its screen and reports the message itself.
      void primeCsrfToken();
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
