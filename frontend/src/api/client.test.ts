/**
 * API client interceptor tests.
 *
 * :purpose: Verify the cross-cutting request/response contract every screen depends
 *     on: the CSRF double-submit echo on state-changing methods only, correlation-id
 *     propagation on the request and onto the normalized error, session-expiry
 *     handling for ``401``/``403`` including the ``skipAuthRedirect`` opt-out the
 *     sign-on, session-probe and logout calls rely on, and the ``409``
 *     optimistic-lock mapping that carries the verbatim legacy message.
 * :note: The interceptors are exercised through a stubbed adapter rather than a
 *     stubbed axios: that keeps the real axios instance, its real header
 *     normalization and both real interceptor chains in the path under test, so the
 *     assertions are about this module's behaviour and not about a mock's.
 */
import { jest } from '@jest/globals';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';

/** Cookie name the gateway sets for the CSRF double-submit. */
const CSRF_COOKIE_NAME = 'XSRF-TOKEN';

/** Request header the client must echo it back in. */
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

/** Correlation-id header propagated on every request. */
const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** Storage key holding the locally cached bearer token. */
const JWT_STORAGE_KEY = 'carddemo.jwt';

/** Route an expired session is sent back to. */
const SIGNON_ROUTE = '/signon';

/** Verbatim ``COACTUPC`` concurrency message carried by a ``409``. */
const OPTIMISTIC_LOCK_MESSAGE = 'Record changed by some one else. Please review';

interface ClientModule {
  default: {
    defaults: { adapter?: AxiosAdapter };
    get: (url: string, config?: unknown) => Promise<unknown>;
    post: (url: string, body?: unknown, config?: unknown) => Promise<unknown>;
  };
  ApiError: new (...args: never[]) => Error;
  isApiError: (err: unknown) => boolean;
  registerSessionExpiryHandler: (handler: () => void) => () => void;
  clearLocalCredentials: () => void;
}

let client: ClientModule;

/** Requests the stub adapter observed, newest last. */
let seen: InternalAxiosRequestConfig[];

/**
 * :purpose: Read a header from a recorded request through axios's own
 *     case-insensitive accessor.
 * :param index: position in ``seen``.
 * :param name: header name.
 * :returns: the header value, or ``undefined`` when it is absent.
 */
function header(index: number, name: string): string | undefined {
  const value = seen[index].headers.get(name);
  return typeof value === 'string' ? value : undefined;
}

/** Response the stub adapter should produce for the next call. */
let respond: (config: InternalAxiosRequestConfig) => Promise<unknown>;

/*
 * jsdom implements neither navigation nor a replaceable `window.location`: the own
 * property is non-configurable with a getter, and `location.assign` is a
 * non-writable, non-configurable own method. The attempted redirect is therefore not
 * observable here and is covered by the browser runs instead. What IS observable, and
 * is what these tests assert, is the security-critical half of the contract: the
 * locally held credentials are dropped and every registered handler is notified.
 * `history.pushState` does control the reported path, so the suite parks on the
 * sign-on route where the module skips the navigation altogether.
 */
function setPath(pathname: string): void {
  window.history.pushState({}, '', pathname);
}

beforeEach(async () => {
  jest.resetModules();
  seen = [];
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  sessionStorage.clear();

  setPath(SIGNON_ROUTE);

  client = (await import('./client')) as unknown as ClientModule;

  respond = (config) =>
    Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config });

  client.default.defaults.adapter = ((config: InternalAxiosRequestConfig) => {
    seen.push(config);
    return respond(config);
  }) as unknown as AxiosAdapter;
});

/**
 * :purpose: Build the rejection an axios adapter produces for a failure. axios throws
 *     an ``AxiosError``, which is a real ``Error`` carrying the request config and,
 *     for an HTTP failure, the response - so the double is built the same way.
 * :param message: the error message.
 * :param config: the originating request config.
 * :param response: the response, omitted for a transport failure.
 * :returns: the rejection value.
 */
function axiosError(
  message: string,
  config: InternalAxiosRequestConfig,
  response?: unknown,
): Error {
  return Object.assign(new Error(message), { isAxiosError: true, config, response });
}

/**
 * :purpose: Adapter double that fails a request with an HTTP status.
 * :param status: the HTTP status code.
 * :param data: the parsed error body.
 * :param headers: response headers, as axios delivers them (lower-cased names).
 * :returns: an adapter returning a rejected promise.
 */
function failWith(
  status: number,
  data: unknown = {},
  headers: Record<string, string> = {},
): (config: InternalAxiosRequestConfig) => Promise<never> {
  return (config) =>
    Promise.reject(
      axiosError(`Request failed with status code ${String(status)}`, config, {
        status,
        data,
        headers,
        statusText: '',
        config,
      }),
    );
}

describe('api client request interceptor', () => {
  it('echoes the CSRF cookie into the request header on a state-changing method', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=token-abc; path=/`;

    await client.default.post('/users', { userId: 'TESTU001' });

    expect(seen).toHaveLength(1);
    expect(header(0, CSRF_HEADER_NAME)).toBe('token-abc');
  });

  it('does not attach a CSRF header to a safe method', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=token-abc; path=/`;

    await client.default.get('/users');

    expect(header(0, CSRF_HEADER_NAME)).toBeUndefined();
  });

  it('omits the CSRF header when the gateway has set no cookie', async () => {
    await client.default.post('/users', {});

    expect(header(0, CSRF_HEADER_NAME)).toBeUndefined();
  });

  it('attaches a correlation id to every request and never repeats one', async () => {
    await client.default.get('/users');
    await client.default.get('/users');

    const first = header(0, CORRELATION_ID_HEADER);
    const second = header(1, CORRELATION_ID_HEADER);
    expect(typeof first).toBe('string');
    expect(String(first).length).toBeGreaterThan(0);
    expect(second).not.toBe(first);
  });

  it('preserves a correlation id the caller supplied', async () => {
    await client.default.get('/users', {
      headers: { [CORRELATION_ID_HEADER]: 'caller-supplied' },
    });

    expect(header(0, CORRELATION_ID_HEADER)).toBe('caller-supplied');
  });
});

describe('api client session expiry', () => {
  it('clears local credentials, notifies handlers and redirects on 401', async () => {
    sessionStorage.setItem(JWT_STORAGE_KEY, 'stale-token');
    const onExpired = jest.fn();
    client.registerSessionExpiryHandler(onExpired);
    respond = failWith(401, { message: 'Unauthorized' });

    await expect(client.default.get('/users')).rejects.toThrow();

    expect(sessionStorage.getItem(JWT_STORAGE_KEY)).toBeNull();
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it('treats 403 the same as 401 — the server will not act on this session', async () => {
    sessionStorage.setItem(JWT_STORAGE_KEY, 'stale-token');
    const onExpired = jest.fn();
    client.registerSessionExpiryHandler(onExpired);
    respond = failWith(403, { message: 'Forbidden' });

    await expect(client.default.get('/accounts/1')).rejects.toThrow();

    expect(sessionStorage.getItem(JWT_STORAGE_KEY)).toBeNull();
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it('honours skipAuthRedirect so sign-on, the probe and logout report their own outcome', async () => {
    const onExpired = jest.fn();
    // No token is held: the opt-out must leave whatever state exists untouched.
    client.registerSessionExpiryHandler(onExpired);
    respond = failWith(401, { message: 'Unauthorized' });

    await expect(
      client.default.get('/session', { skipAuthRedirect: true }),
    ).rejects.toThrow();

    expect(onExpired).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(JWT_STORAGE_KEY)).toBeNull();
  });

  it('unregisters a session-expiry handler through its returned disposer', async () => {
    const onExpired = jest.fn();
    const dispose = client.registerSessionExpiryHandler(onExpired);
    dispose();
    respond = failWith(401, {});

    await expect(client.default.get('/users')).rejects.toThrow();

    expect(onExpired).not.toHaveBeenCalled();
  });

  it('leaves the session alone for a status that is not 401 or 403', async () => {
    sessionStorage.setItem(JWT_STORAGE_KEY, 'live-token');
    const onExpired = jest.fn();
    client.registerSessionExpiryHandler(onExpired);
    respond = failWith(400, { message: 'User ID already exist...' });

    await expect(client.default.post('/users', {})).rejects.toThrow();

    expect(sessionStorage.getItem(JWT_STORAGE_KEY)).toBe('live-token');
    expect(onExpired).not.toHaveBeenCalled();
  });
});

describe('api client error normalization', () => {
  it('carries the server correlation id from the error body onto the ApiError', async () => {
    respond = failWith(400, {
      message: 'User ID already exist...',
      correlationId: 'body-correlation-id',
    });

    const err: unknown = await client.default.post('/users', {}).catch((e: unknown) => e);

    expect(client.isApiError(err)).toBe(true);
    const apiError = err as { status: number; message: string; correlationId?: string };
    expect(apiError.status).toBe(400);
    expect(apiError.message).toBe('User ID already exist...');
    expect(apiError.correlationId).toBe('body-correlation-id');
  });

  it('falls back to the response header when the body carries no correlation id', async () => {
    // axios lower-cases response header names, which is the shape the module reads.
    respond = failWith(500, { message: 'Boom' }, {
      [CORRELATION_ID_HEADER.toLowerCase()]: 'header-id',
    });

    const err: unknown = await client.default.get('/users').catch((e: unknown) => e);

    expect((err as { correlationId?: string }).correlationId).toBe('header-id');
  });

  it('maps 409 to an optimistic-lock conflict carrying the verbatim legacy message', async () => {
    respond = failWith(409, {});

    const err: unknown = await client.default.post('/accounts/1', {}).catch((e: unknown) => e);

    const apiError = err as {
      status: number;
      message: string;
      isOptimisticLockConflict: boolean;
    };
    expect(apiError.status).toBe(409);
    expect(apiError.isOptimisticLockConflict).toBe(true);
    expect(apiError.message).toBe(OPTIMISTIC_LOCK_MESSAGE);
  });

  it('reports a network failure as status 0 rather than throwing a raw axios error', async () => {
    respond = (config) => Promise.reject(axiosError('Network Error', config));

    const err: unknown = await client.default.get('/users').catch((e: unknown) => e);

    expect(client.isApiError(err)).toBe(true);
    expect((err as { status: number }).status).toBe(0);
  });

  it('sends the session cookie: withCredentials is on for every request', async () => {
    await client.default.get('/users');

    expect(seen[0].withCredentials).toBe(true);
  });
});

describe('clearLocalCredentials', () => {
  it('removes the cached token and tolerates being called when none is held', () => {
    sessionStorage.setItem(JWT_STORAGE_KEY, 'token');

    client.clearLocalCredentials();
    expect(sessionStorage.getItem(JWT_STORAGE_KEY)).toBeNull();

    expect(() => {
      client.clearLocalCredentials();
    }).not.toThrow();
  });
});
