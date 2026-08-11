/**
 * :module: ``frontend/src/api/menu.ts``
 * :purpose: Domain API module for the two CardDemo menu screens — ``MainMenuPage`` (the
 *     role-filtered user menu) and ``AdminMenuPage`` (the administrator-only menu). It wraps
 *     the api-gateway ``MenuController`` routes, re-expressing the legacy CICS
 *     pseudo-conversational menu programs as stateless REST calls: ``COMEN01C`` / transaction
 *     ``CM00`` (user menu) and ``COADM01C`` / transaction ``CA00`` (admin menu). Behavior
 *     references ``app/cbl/COMEN01C.cbl`` and ``app/cbl/COADM01C.cbl``.
 * :output: The named async functions ``getMainMenu`` and ``getAdminMenu`` (the role-scoped
 *     menu listings) plus ``selectMenuOption`` and ``selectAdminMenuOption`` (the
 *     option-dispatch calls that mirror the legacy ``PROCESS-ENTER-KEY`` / ``XCTL``
 *     navigation).
 * :note: The two role-scoped listings are memoized for the life of the signed-on session
 *     and invalidated explicitly by :func:`invalidateMenuCache`, which sign-on, sign-off and a
 *     rejected session all call. The listing is a pure function of ``CDEMO-USER-TYPE`` and the
 *     static ``COMEN02Y`` / ``COADM02Y`` option tables, so it cannot change while one
 *     principal holds the session; re-reading it on every mount spent a round trip per menu
 *     entry to receive a byte-identical answer. The screen's observable behavior is unchanged:
 *     the same options in the same order, still role-filtered by the gateway, still fetched on
 *     first entry.
 * :note: This module only fetches and posts menu data through the shared ``apiClient``.
 *     Role gating — the legacy ``COADM01`` versus ``COMEN01`` ``XCTL`` split — is enforced by
 *     the ``App.tsx`` route guards and the api-gateway (which requires ``ROLE_ADMIN`` for the
 *     admin routes); it is deliberately not duplicated here. Rationale is recorded in
 *     ``docs/decision-log.md``.
 */

import apiClient, { registerSessionExpiryHandler } from './client';
import type {
  MenuResponseDto,
  MenuSelectionRequestDto,
  MenuSelectionResponseDto,
} from '../types';

/**
 * :purpose: The memoized listings for the signed-on session, one per menu, each holding
 *   either the resolved listing or the in-flight promise that will resolve it so
 *   concurrent mounts share a single round trip.
 * :note: The scope is deliberately this application instance and nothing wider. Four
 *   in-app menu entries perform one fetch per menu; a full document reload starts a new
 *   instance and fetches once more, which is correct rather than redundant on two counts:
 *   the reloaded instance has no listing yet, and the service answers ``/menu`` with
 *   ``Cache-Control: no-store``, so persisting the response into web storage would defy an
 *   explicit directive from the owner of the data. Holding it in memory honours the
 *   directive — nothing is written anywhere — while removing the repeat within one screen
 *   session, which is the only repeat the operator's navigation actually produces.
 */
const menuCache = new Map<string, Promise<MenuResponseDto>>();

/**
 * :purpose: Drop every memoized menu listing, so the next mount reads the server again.
 * :returns: nothing.
 * :note: Called on sign-on (a new principal may hold a different ``CDEMO-USER-TYPE``), on
 *   sign-off, and whenever the server rejects the session. Those are the only events that
 *   can change what the listing contains, which is why invalidation is explicit rather
 *   than time-based: a cache that expires on a timer would still serve a stale listing for
 *   the window before it expired, and would keep re-reading an unchanged one afterwards.
 */
export function invalidateMenuCache(): void {
  menuCache.clear();
}

// A session the server has rejected can no longer scope a memoized listing, so the cache
// is dropped from HERE rather than from the session store: the dependency points the same
// way as every other module in this layer (api -> client), and the session store keeps its
// existing import surface, which is what the screen tests mock.
registerSessionExpiryHandler(invalidateMenuCache);

/**
 * :purpose: Read a menu listing through the session cache, storing the in-flight promise
 *   so simultaneous mounts share one request, and forgetting a failed one so the failure
 *   is never cached.
 * :param path: the gateway route of the listing.
 * :returns: the listing.
 */
async function readCachedMenu(path: string): Promise<MenuResponseDto> {
  const cached = menuCache.get(path);
  if (cached !== undefined) {
    return cached;
  }
  const pending = apiClient
    .get<MenuResponseDto>(path)
    .then((response) => response.data)
    .catch((cause: unknown) => {
      menuCache.delete(path);
      throw cause;
    });
  menuCache.set(path, pending);
  return pending;
}

/**
 * :purpose: Fetch the role-filtered main (user) menu — legacy program
 *   ``COMEN01C``, CICS transaction ``CM00``.
 * :returns: A :ts:type:`MenuResponseDto` listing the visible main-menu options
 *   and their resolved downstream routes.
 */
export async function getMainMenu(): Promise<MenuResponseDto> {
  return readCachedMenu('/menu');
}

/**
 * :purpose: Fetch the administrator menu — legacy program ``COADM01C``, CICS
 *   transaction ``CA00``. Admin-only: the api-gateway enforces ``ROLE_ADMIN``,
 *   so a non-admin request is rejected upstream and this module performs no
 *   gating of its own.
 * :returns: A :ts:type:`MenuResponseDto` listing the admin-menu options and
 *   their resolved downstream routes.
 */
export async function getAdminMenu(): Promise<MenuResponseDto> {
  return readCachedMenu('/admin/menu');
}

/**
 * :purpose: Submit a main-menu option selection — legacy ``COMEN01C``
 *   ``PROCESS-ENTER-KEY`` under CICS transaction ``CM00`` — mirroring the legacy
 *   ``XCTL`` transfer to the chosen program.
 * :param request: the selection payload; ``option`` is the entered
 *   two-character option text preserved verbatim (matching COBOL ``OPTIONI``
 *   ``PIC X(2)``), and ``aid`` is the optional action key (``'ENTER'`` to
 *   select, ``'PF3'`` to exit).
 * :returns: A :ts:type:`MenuSelectionResponseDto` describing the resolved
 *   navigation target (dispatch route), the PF3 back navigation, or an
 *   informational coming-soon message.
 */
export async function selectMenuOption(
  request: MenuSelectionRequestDto,
): Promise<MenuSelectionResponseDto> {
  const response = await apiClient.post<MenuSelectionResponseDto>(
    '/menu/select',
    request,
  );
  return response.data;
}

/**
 * :purpose: Submit an administrator-menu option selection — legacy ``COADM01C``
 *   ``PROCESS-ENTER-KEY`` under CICS transaction ``CA00`` — mirroring the legacy
 *   ``XCTL`` transfer to the chosen program. Admin-only: the api-gateway
 *   enforces ``ROLE_ADMIN`` upstream.
 * :param request: the selection payload; ``option`` is the entered
 *   two-character option text preserved verbatim (matching COBOL ``OPTIONI``
 *   ``PIC X(2)``), and ``aid`` is the optional action key (``'ENTER'`` to
 *   select, ``'PF3'`` to exit).
 * :returns: A :ts:type:`MenuSelectionResponseDto` describing the resolved
 *   navigation target (dispatch route), the PF3 back navigation, or an
 *   informational coming-soon message.
 */
export async function selectAdminMenuOption(
  request: MenuSelectionRequestDto,
): Promise<MenuSelectionResponseDto> {
  const response = await apiClient.post<MenuSelectionResponseDto>(
    '/admin/menu/select',
    request,
  );
  return response.data;
}
