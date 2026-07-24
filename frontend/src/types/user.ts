/**
 * User administration DTO types for the CardDemo React SPA.
 *
 * :module: ``frontend/src/types/user.ts``
 *
 * Declares the request / response contracts for the four administrator-only
 * user-management screens, mirroring the BMS symbolic maps ``COUSR00`` (list,
 * program ``COUSR00C``, transaction ``CU00``, 10 rows per page), ``COUSR01``
 * (add, ``COUSR01C``, ``CU01``), ``COUSR02`` (update, ``COUSR02C``, ``CU02``),
 * and ``COUSR03`` (delete, ``COUSR03C``, ``CU03``). Every screen is gated to the
 * ``ROLE_ADMIN`` authority.
 *
 * Member names mirror the frozen backend domain ``SecurityUser`` (COBOL copybook
 * ``CSUSR01Y``) camelCase properties exactly — ``secUsrId``, ``secUsrFname``,
 * ``secUsrLname``, ``secUsrPwd``, ``secUsrType`` — so REST payloads bind without
 * field remapping. ``secUsrType`` reuses the shared :ts:type:`Role` (``'A'`` /
 * ``'U'``) rather than redefining the role, and list pages reuse the generic
 * :ts:type:`Page` wrapper rather than redefining pagination.
 *
 * Security invariant: the password / hash ``secUsrPwd`` is a request-only field
 * (required on add, optional on update) and is NEVER present in any response
 * type; response DTOs reuse the password-free :ts:type:`UserDto`.
 *
 * :consumed by: the user-service REST client, the ``UserListPage`` /
 *   ``UserAddPage`` / ``UserUpdatePage`` / ``UserDeletePage`` components, and
 *   their form hooks.
 */

import type { Page } from './common';
import type { Role } from './session';

/**
 * Password-free representation of a security user, shared by the list rows and
 * the add / update / delete response payloads.
 *
 * Mirrors the non-secret fields of the backend ``SecurityUser`` domain
 * (copybook ``CSUSR01Y``); the ``secUsrPwd`` credential is deliberately absent.
 *
 * :member secUsrId: user id (``SEC-USR-ID`` ``PIC X(08)``), primary key.
 * :member secUsrFname: first name (``SEC-USR-FNAME`` ``PIC X(20)``).
 * :member secUsrLname: last name (``SEC-USR-LNAME`` ``PIC X(20)``).
 * :member secUsrType: role code (``SEC-USR-TYPE`` ``PIC X(01)`` — ``'A'`` admin /
 *   ``'U'`` user), reusing the shared :ts:type:`Role`.
 */
export interface UserDto {
  secUsrId: string;
  secUsrFname: string;
  secUsrLname: string;
  secUsrType: Role;
}

/**
 * Single row of the user list screen ``COUSR00`` (program ``COUSR00C``).
 *
 * Each row is populated from the per-row symbolic-map fields ``USRID<nn>`` /
 * ``FNAME<nn>`` / ``LNAME<nn>`` / ``UTYPE<nn>`` (``nn`` = ``01``..``10``), which
 * carry exactly the password-free :ts:type:`UserDto` shape.
 */
export type UserListItemDto = UserDto;

/**
 * Query parameters for the user list screen ``COUSR00``.
 *
 * :member userId: optional user-id filter, mirroring the ``USRIDIN`` ``PIC X(08)``
 *   entry field used to position the browse; absent lists from the start.
 * :member page: optional one-based page index driving PF7 / PF8 paging; absent
 *   requests the first page.
 */
export interface UserListRequestDto {
  userId?: string;
  page?: number;
}

/**
 * Paged response for the user list screen ``COUSR00``, reusing the generic
 * :ts:type:`Page` wrapper. The list is paginated 10 rows per page, matching the
 * ten ``USRID<nn>`` row slots of the mapset.
 */
export type UserListResponseDto = Page<UserListItemDto>;

/**
 * Request body for the add-user screen ``COUSR01`` (program ``COUSR01C``,
 * transaction ``CU01``).
 *
 * Carries the plaintext password entered in the ``PASSWD`` ``PIC X(08)`` field;
 * the service encodes it before persistence. The password is REQUIRED on add.
 *
 * :member secUsrId: new user id (``USERID`` ``PIC X(08)``).
 * :member secUsrFname: first name (``FNAME`` ``PIC X(20)``).
 * :member secUsrLname: last name (``LNAME`` ``PIC X(20)``).
 * :member secUsrPwd: password to encode (``PASSWD`` ``PIC X(08)``); request-only.
 * :member secUsrType: role code (``USRTYPE`` ``PIC X(01)``), a :ts:type:`Role`.
 */
export interface UserAddRequestDto {
  secUsrId: string;
  secUsrFname: string;
  secUsrLname: string;
  secUsrPwd: string;
  secUsrType: Role;
}

/**
 * Request body for the update-user screen ``COUSR02`` (program ``COUSR02C``,
 * transaction ``CU02``).
 *
 * ``secUsrId`` identifies the target user (``USRIDIN`` ``PIC X(08)``). The
 * password (``PASSWD`` ``PIC X(08)``) is OPTIONAL and is sent only when the
 * administrator is changing it; when omitted the stored credential is retained.
 *
 * :member secUsrId: target user id (``USRIDIN`` ``PIC X(08)``).
 * :member secUsrFname: first name (``FNAME`` ``PIC X(20)``).
 * :member secUsrLname: last name (``LNAME`` ``PIC X(20)``).
 * :member secUsrPwd: optional new password to encode (``PASSWD`` ``PIC X(08)``);
 *   request-only.
 * :member secUsrType: role code (``USRTYPE`` ``PIC X(01)``), a :ts:type:`Role`.
 */
export interface UserUpdateRequestDto {
  secUsrId: string;
  secUsrFname: string;
  secUsrLname: string;
  secUsrPwd?: string;
  secUsrType: Role;
}

/**
 * Request body for the delete-user screen ``COUSR03`` (program ``COUSR03C``,
 * transaction ``CU03``).
 *
 * Deletion targets a single user by id; the ``COUSR03`` map carries no password
 * field.
 *
 * :member secUsrId: user id to delete (``USRIDIN`` ``PIC X(08)``).
 */
export interface UserDeleteRequestDto {
  secUsrId: string;
}

/**
 * Response for the add-user screen ``COUSR01``: the created user in its
 * password-free :ts:type:`UserDto` form.
 */
export type UserAddResponseDto = UserDto;

/**
 * Response for the update-user screen ``COUSR02``: the updated user in its
 * password-free :ts:type:`UserDto` form.
 */
export type UserUpdateResponseDto = UserDto;

/**
 * Response for the delete-user screen ``COUSR03``: the deleted user echoed back
 * in its password-free :ts:type:`UserDto` form.
 */
export type UserDeleteResponseDto = UserDto;
