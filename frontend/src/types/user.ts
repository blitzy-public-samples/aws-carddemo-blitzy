/**
 * :module: user
 * :purpose: Request/response DTO types for the four administrator-only
 *   user-management screens ``COUSR00`` (list, ``CU00``), ``COUSR01`` (add,
 *   ``CU01``), ``COUSR02`` (update, ``CU02``), and ``COUSR03`` (delete, ``CU03``).
 * :output: :ts:type:`UserDto`, the list request/response helpers, and the add /
 *   update / delete request DTOs.
 * :note: Member names mirror the backend ``UserResponseDto`` /
 *   ``AddUserRequestDto`` / ``UpdateUserRequestDto`` JSON contracts (``userId``,
 *   ``firstName``, ``lastName``, ``userType``) so REST payloads bind without field
 *   remapping. ``userType`` reuses the shared :ts:type:`Role` (``'A'`` / ``'U'``);
 *   list responses reuse the generic :ts:type:`Page` wrapper.
 * :note: The raw ``password`` is a request-only field encoded server-side and is
 *   never present in any response type.
 */

import type { Page } from './common';
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
 * :purpose: Query parameters for the user list screen ``COUSR00``.
 * :field userId: optional user-id filter positioning the browse.
 * :field page: optional one-based page index driving PF7 / PF8 paging.
 */
export interface UserListRequestDto {
  userId?: string;
  page?: number;
}

/**
 * :purpose: Paged response for the user list screen ``COUSR00`` (ten rows per
 *   page), using the generic :ts:type:`Page` wrapper.
 */
export type UserListResponseDto = Page<UserListItemDto>;

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
 *   ``UpdateUserRequestDto``; the target user id travels in the request path.
 * :field firstName: first name (``SEC-USR-FNAME``).
 * :field lastName: last name (``SEC-USR-LNAME``).
 * :field userType: role code (``SEC-USR-TYPE``), a :ts:type:`Role`.
 * :field password: raw password (``SEC-USR-PWD``); request-only, required — the
 *   service verifies it against the stored hash and re-encodes it when changed.
 */
export interface UserUpdateRequestDto {
  firstName: string;
  lastName: string;
  userType: Role;
  password: string;
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
 * :purpose: Response for the add-user screen ``COUSR01``; the created user as a
 *   password-free :ts:type:`UserDto`.
 */
export type UserAddResponseDto = UserDto;

/**
 * :purpose: Response for the update-user screen ``COUSR02``; the updated user as a
 *   password-free :ts:type:`UserDto`.
 */
export type UserUpdateResponseDto = UserDto;

/**
 * :purpose: Response for the delete-user screen ``COUSR03``; the deleted user as a
 *   password-free :ts:type:`UserDto`.
 */
export type UserDeleteResponseDto = UserDto;
