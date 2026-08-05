/**
 * :module: ``frontend/src/api/users.ts``
 * :purpose: Administrator-only user-management domain API module. Wraps the
 *   ``/users`` routes on the CardDemo api-gateway and re-expresses the four
 *   legacy CICS user-maintenance transactions — ``CU00`` list
 *   (``app/cbl/COUSR00C.cbl``), ``CU01`` add (``app/cbl/COUSR01C.cbl``),
 *   ``CU02`` update (``app/cbl/COUSR02C.cbl``), and ``CU03`` delete
 *   (``app/cbl/COUSR03C.cbl``) — as REST calls issued through the shared
 *   ``apiClient``. Backs the ``UserListPage``, ``UserAddPage``,
 *   ``UserUpdatePage``, and ``UserDeletePage`` screens.
 * :output: The named async functions ``listUsers``, ``getUser``, ``addUser``,
 *   ``updateUser``, and ``deleteUser``.
 * :note: These operations are ``ROLE_ADMIN``-only; authorization is enforced by
 *   the api-gateway/backend, not here (a non-admin caller receives ``401`` /
 *   ``403`` normalized by ``./client``). This module performs no role checks.
 * :note: The raw password is carried only on the add / update request bodies
 *   (``UserAddRequestDto`` / ``UserUpdateRequestDto``); it is encoded server-side
 *   and never appears in any response type (``UserDto`` is password-free). The
 *   request DTO is forwarded to ``apiClient`` as an opaque body and is never
 *   logged here.
 * :note: The rationale for the design choices below (path-id delete returning
 *   ``void``/``204``; a database sequence for id generation) is recorded in
 *   ``docs/decision-log.md`` per the Explainability rule.
 */

import apiClient from './client';
import type {
  UserListRequestDto,
  UserListResponseDto,
  UserDto,
  UserAddRequestDto,
  UserAddResponseDto,
  UserUpdateRequestDto,
  UserUpdateResponseDto,
} from '../types';

/**
 * :purpose: Translate the screen's one-based page counter to the zero-based
 *   ``page`` index the ``GET /users`` route binds.
 * :param page: the one-based page number held by the screen
 *   (``CDEMO-CU00-PAGE-NUM``), or ``undefined``.
 * :returns: the zero-based wire index, or ``undefined`` when no page was given.
 *   Values below one clamp to the first page.
 */
function toWirePage(page: number | undefined): number | undefined {
  if (page === undefined) {
    return undefined;
  }
  return page > 1 ? page - 1 : 0;
}

/**
 * :purpose: List security users for the user-list screen ``COUSR00`` (CICS
 *   ``CU00``). Reproduces the legacy ``STARTBR`` / ``READNEXT`` browse over the
 *   ``USRSEC`` file, paged ten rows per page (``USER-REC OCCURS 10 TIMES``).
 * :param request: the list query. ``userId`` positions the browse at the
 *   ``Search User ID`` key; ``page`` is the screen's one-based counter and is sent
 *   as the route's zero-based ``page``; ``direction`` plus ``cursor`` drive PF7 /
 *   PF8 keyset paging; ``selection`` plus ``selectedUserId`` carry the row
 *   selection. Every member is optional and ``undefined`` members are omitted
 *   from the query string.
 * :returns: a promise resolving to the password-free user list with its paging
 *   cursors, resolved selection, and legacy banner.
 */
export async function listUsers(
  request: UserListRequestDto,
): Promise<UserListResponseDto> {
  const response = await apiClient.get<UserListResponseDto>('/users', {
    params: {
      userId: request.userId,
      page: toWirePage(request.page),
      direction: request.direction,
      cursor: request.cursor,
      selection: request.selection,
      selectedUserId: request.selectedUserId,
    },
  });
  return response.data;
}

/**
 * :purpose: Fetch a single security user by id for the update / delete screens
 *   ``COUSR02`` / ``COUSR03``, reproducing the legacy ``READ`` of the ``USRSEC``
 *   record keyed by ``SEC-USR-ID`` (route ``GET /users/{id}``).
 * :param userId: the eight-character user id (``SEC-USR-ID``); path-encoded.
 * :returns: a promise resolving to the password-free ``UserDto`` for that user.
 */
export async function getUser(userId: string): Promise<UserDto> {
  const response = await apiClient.get<UserDto>(
    `/users/${encodeURIComponent(userId)}`,
  );
  return response.data;
}

/**
 * :purpose: Add a new regular/admin security user for the add-user screen
 *   ``COUSR01`` (CICS ``CU01``). Reproduces the legacy ``EXEC CICS WRITE`` to the
 *   ``USRSEC`` file (route ``POST /users``).
 * :param request: the add-user body (``UserAddRequestDto``), which carries the
 *   raw password the service encodes; forwarded as an opaque body.
 * :returns: a promise resolving to the created, password-free user together with
 *   the verbatim legacy confirmation banner (``UserAddResponseDto``).
 */
export async function addUser(
  request: UserAddRequestDto,
): Promise<UserAddResponseDto> {
  const response = await apiClient.post<UserAddResponseDto>('/users', request);
  return response.data;
}

/**
 * :purpose: Update an existing security user for the update-user screen
 *   ``COUSR02`` (CICS ``CU02``). Reproduces the legacy ``EXEC CICS REWRITE`` of
 *   the ``USRSEC`` record; the target id travels in the path
 *   (route ``PUT /users/{id}``).
 * :param userId: the eight-character user id (``SEC-USR-ID``) to update;
 *   path-encoded.
 * :param request: the update-user body (``UserUpdateRequestDto``); forwarded as
 *   an opaque body.
 * :returns: a promise resolving to the updated, password-free user together with
 *   the verbatim legacy confirmation banner (``UserUpdateResponseDto``).
 */
export async function updateUser(
  userId: string,
  request: UserUpdateRequestDto,
): Promise<UserUpdateResponseDto> {
  const response = await apiClient.put<UserUpdateResponseDto>(
    `/users/${encodeURIComponent(userId)}`,
    request,
  );
  return response.data;
}

/**
 * :purpose: Delete a security user for the delete-user screen ``COUSR03`` (CICS
 *   ``CU03``). Reproduces the legacy ``EXEC CICS DELETE`` from the ``USRSEC``
 *   file; the id travels in the path (route ``DELETE /users/{id}``).
 * :param userId: the eight-character user id (``SEC-USR-ID``) to delete;
 *   path-encoded.
 * :returns: a promise resolving to ``void``; the backend responds ``204 No
 *   Content`` and returns no body.
 */
export async function deleteUser(userId: string): Promise<void> {
  await apiClient.delete(`/users/${encodeURIComponent(userId)}`);
}
