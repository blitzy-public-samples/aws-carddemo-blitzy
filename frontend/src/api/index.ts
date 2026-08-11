/**
 * :module: ``frontend/src/api/index.ts``
 * :purpose: Barrel entry point for the CardDemo API client layer. Aggregates the eight
 *     domain modules (``auth``, ``accounts``, ``cards``, ``transactions``, ``billpay``,
 *     ``reports``, ``users``, ``menu``) together with the shared axios instance and its
 *     normalized error contract, so consumers (pages, hooks, and tests) import from a single
 *     specifier (``../api``) rather than reaching into individual modules.
 * :output: The camelCase REST functions of every domain module, plus ``apiClient`` (the
 *     shared ``AxiosInstance``), ``ApiError`` and ``isApiError`` (the normalized error type
 *     and its type-guard), ``isCancelledRequest`` and ``runWithRequestSignal`` (the
 *     request-cancellation contract), ``registerSessionExpiryHandler`` (the centralized
 *     session-expiry hook), ``generateCorrelationId`` (the shared correlation-id source), and
 *     ``getApiBaseUrl`` (the api-gateway base-URL resolver).
 * :note: Pure ES-module re-export surface: it declares no runtime logic, no own default
 *     export, and no side effects, and it never reads the Vite build-time environment
 *     directly, so it is evaluable under Jest (jsdom) without any Vite environment injection.
 *     DTO types are intentionally not re-exported here; those are provided by ``../types``.
 */

export * from './auth';
export * from './accounts';
export * from './cards';
export * from './transactions';
export * from './billpay';
export * from './reports';
export * from './users';
export * from './menu';

export {
  default as apiClient,
  ApiError,
  isApiError,
  isCancelledRequest,
  runWithRequestSignal,
  generateCorrelationId,
  registerSessionExpiryHandler,
} from './client';
export type { SessionRejectionReason } from './client';
export { GENERIC_ERROR_MESSAGE, SESSION_ENDED_MESSAGE } from './messages';
export { getApiBaseUrl, getAppId, getSysId } from './config';
