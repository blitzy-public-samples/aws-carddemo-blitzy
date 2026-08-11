/**
 * :module: user
 * :purpose: Request/response DTO types for the four administrator-only
 *   user-management screens ``COUSR00`` (list, ``CU00``), ``COUSR01`` (add,
 *   ``CU01``), ``COUSR02`` (update, ``CU02``), and ``COUSR03`` (delete, ``CU03``).
 * :output: :ts:type:`UserDto`, the list request/response helpers, and the add /
 *   update / delete request DTOs.
 * :note: Member names mirror the backend ``UserResponseDto`` /
 *   ``UserListResponseDto`` / ``UserWriteResponseDto`` / ``AddUserRequestDto`` /
 *   ``UpdateUserRequestDto`` JSON contracts (``userId``, ``firstName``,
 *   ``lastName``, ``userType``) so REST payloads bind without field remapping.
 *   ``userType`` reuses the shared :ts:type:`Role` (``'A'`` / ``'U'``).
 * :note: The raw ``password`` is a request-only field encoded server-side and is
 *   never present in any response type.
 */

import type { Role } from './session';

/**
 * :purpose: Password-free security user, shared by list rows and the add / update
 *   / delete responses. Mirrors ``UserResponseDto``.
 * :field userId: user id (``SEC-USR-ID``), primary key.
 * :field firstName: first name (``SEC-USR-FNAME``).
 * :field lastName: last name (``SEC-USR-LNAME``).
 * :field userType: role code (``SEC-USR-TYPE`` — ``'A'`` admin / ``'U'`` user).
 */
export interface UserDto {
  userId: string;
  firstName: string;
  lastName: string;
  userType: Role;
}

/**
 * :purpose: One row of the user list screen ``COUSR00``; the password-free
 *   :ts:type:`UserDto` shape.
 */
export type UserListItemDto = UserDto;

/**
 * :purpose: Paging action carried by the user list screen ``COUSR00``, using the
 *   legacy ``DFHPF7`` / ``DFHPF8`` vocabulary the ``/users`` route binds on its
 *   ``direction`` parameter.
 */
export type UserListDirection = 'PF7' | 'PF8';

/**
 * :purpose: Row-selection flag of the user list screen ``COUSR00``
 *   (``CDEMO-CU00-USR-SEL-FLG``): ``'U'`` transfers to the update program
 *   ``COUSR02C`` and ``'D'`` to the delete program ``COUSR03C``.
 */
export type UserListSelection = 'U' | 'D';

/**
 * :purpose: Query parameters for the user list screen ``COUSR00``, mirroring the
 *     parameters the ``GET /users`` route binds.
 * :field userId: optional browse-start user id — the ``Search User ID`` field
 *     (``USRIDIN``). The browse is positioned at the first id greater than or equal to it, and
 *     it is ignored when ``direction`` is supplied because PF7 / PF8 browse from the cursors
 *     instead (``COUSR00C`` L218-L221, L239-L265).
 * :field page: optional **one-based** page index, matching the legacy
 *     ``CDEMO-CU00-PAGE-NUM`` counter the screen displays under ``Page:``. The ``api/users``
 *     module converts it to the zero-based ``page`` the route binds.
 * :field direction: optional paging action; ``'PF8'`` pages forward from ``cursor`` and
 *     ``'PF7'`` pages backward from it.
 * :field cursor: the user id anchoring keyset paging — ``userIdLast`` for ``'PF8'`` and
 *     ``userIdFirst`` for ``'PF7'``.
 * :field selection: optional row-selection flag.
 * :field selectedUserId: the user id of the selected row (``CDEMO-CU00-USR-SELECTED``).
 */
export interface UserListRequestDto {
  userId?: string;
  page?: number;
  direction?: UserListDirection;
  cursor?: string;
  selection?: UserListSelection;
  selectedUserId?: string;
}

/**
 * :purpose: Response for the user list screen ``COUSR00`` (ten rows per page). Mirrors the
 *     backend ``UserListResponseDto`` field for field.
 * :field users: the page of password-free rows (up to ten).
 * :field pageNumber: **zero-based** index of the returned page for page-based listing;
 *     ``0`` for a cursor-driven or search-key-driven browse, in which case the screen's own
 *     ``CDEMO-CU00-PAGE-NUM`` counter governs the ``Page:`` display.
 * :field userIdFirst: first user id on the page (``CDEMO-CU00-USRID-FIRST``, the PF7
 *     anchor), or ``null`` when the page is empty.
 * :field userIdLast: last user id on the page (``CDEMO-CU00-USRID-LAST``, the PF8 anchor),
 *     or ``null`` when the page is empty.
 * :field nextPage: ``true`` when a further forward page exists (PF8 available).
 * :field selectedUserId: echoed selected row id, or ``null``.
 * :field selectedAction: the resolved selection — ``'U'`` or ``'D'`` — or ``null`` when no
 *     row was selected.
 * :field message: the verbatim legacy banner (``WS-MESSAGE``), or ``null``.
 */
export interface UserListResponseDto {
  users: UserListItemDto[];
  pageNumber: number;
  userIdFirst: string | null;
  userIdLast: string | null;
  nextPage: boolean;
  selectedUserId: string | null;
  selectedAction: UserListSelection | null;
  message: string | null;
}

/**
 * :purpose: Request body for the add-user screen ``COUSR01``. Mirrors
 *   ``AddUserRequestDto`` and carries the raw ``password`` the service encodes.
 * :field userId: new user id (``SEC-USR-ID``).
 * :field firstName: first name (``SEC-USR-FNAME``).
 * :field lastName: last name (``SEC-USR-LNAME``).
 * :field userType: role code (``SEC-USR-TYPE``), a :ts:type:`Role`.
 * :field password: raw password to encode (``SEC-USR-PWD``); request-only,
 *   required on add.
 */
export interface UserAddRequestDto {
  userId: string;
  firstName: string;
  lastName: string;
  userType: Role;
  password: string;
}

/**
 * :purpose: Request body for the update-user screen ``COUSR02``. Mirrors
 *     ``UpdateUserRequestDto``; the target user id travels in the request path.
 * :field firstName: first name (``SEC-USR-FNAME``).
 * :field lastName: last name (``SEC-USR-LNAME``).
 * :field userType: role code (``SEC-USR-TYPE``), a :ts:type:`Role`.
 * :field password: raw password (``SEC-USR-PWD``); request-only and OPTIONAL. Omit it to
 *     change the profile fields while leaving the stored credential untouched; supply it to
 *     set a new one, which the service verifies against the stored hash and re-encodes when it
 *     differs. Absence carries the meaning ``COUSR02C`` gave the pre-filled field: that
 *     program filled ``PASSWD`` from ``SEC-USR-PWD`` (L169) and rewrote the credential only
 *     when the returned value differed, whereas a hashed credential cannot be pre-filled and
 *     the update route never returns it -- so the screen cannot repopulate the field after a
 *     fetch and must be able to save without it.
 */
export interface UserUpdateRequestDto {
  firstName: string;
  lastName: string;
  userType: Role;
  password?: string;
}

/**
 * :purpose: Identifies the user to delete on the delete screen ``COUSR03``; the
 *   id travels in the request path.
 * :field userId: user id to delete (``SEC-USR-ID``).
 */
export interface UserDeleteRequestDto {
  userId: string;
}

/**
 * :purpose: Password-free security user plus the verbatim legacy banner returned by
 *   the add and update routes. Mirrors ``UserWriteResponseDto``.
 * :field message: the legacy confirmation / prompt text (``WS-MESSAGE``) the screen
 *   renders on line 23, or ``null`` when the operation carries no banner.
 */
export interface UserWriteResponseDto extends UserDto {
  message: string | null;
}

/**
 * :purpose: Response for the add-user screen ``COUSR01``; the created user with the
 *   legacy confirmation banner.
 */
export type UserAddResponseDto = UserWriteResponseDto;

/**
 * :purpose: Response for the update-user screen ``COUSR02``; the updated user with
 *   the legacy confirmation banner.
 */
export type UserUpdateResponseDto = UserWriteResponseDto;
