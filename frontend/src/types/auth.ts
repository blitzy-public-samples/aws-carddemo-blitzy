/**
 * :module: auth
 * :purpose: Declares the sign-on request/response DTO contracts for the CardDemo
 *   single-page-application sign-on screen (``SignonPage``). Re-platforms the
 *   input (``COSGN0AI``) and output (``COSGN0AO``) views of the BMS symbolic map
 *   in mapset ``COSGN00`` driven by legacy CICS program ``COSGN00C`` (transaction
 *   ``CC00``), and mirrors the backend ``auth-service`` wire contract for
 *   ``POST /auth/signon`` (``com.carddemo.auth.dto.SignonRequestDto`` /
 *   ``com.carddemo.auth.dto.SignonResponseDto``) so axios responses deserialize
 *   without field remapping.
 * :note: Types-only module — no runtime code and no environment access, so it is
 *   safe to import from Jest (jsdom). The single-character ``Role`` wire code is
 *   reused from :ts:module:`./session` and the ``ErrMsg`` alias from
 *   :ts:module:`./common`; neither is redefined here.
 */

import type { Role } from './session';
import type { ErrMsg } from './common';

/**
 * :purpose: Request body for ``POST /auth/signon``, carrying the credentials
 *   entered on the sign-on screen. Mirrors the backend
 *   ``com.carddemo.auth.dto.SignonRequestDto`` JSON contract exactly.
 * :field userId: user id captured from the ``COSGN00`` ``USERIDI`` field
 *   (``PIC X(08)``), bounded to the frozen ``SEC-USR-ID`` width of 8 characters.
 * :field password: password captured from the ``COSGN00`` ``PASSWDI`` field
 *   (``PIC X(08)``), bounded to the frozen ``SEC-USR-PWD`` width of 8 characters.
 * :note: Legacy ``COSGN00C`` upper-cases both values before a plaintext compare
 *   (effectively case-insensitive); the BCrypt-based target is case-sensitive.
 *   This type applies no upper-casing; the deviation rationale is recorded in
 *   ``docs/decision-log.md``. The ``PIC X(08)`` widths are documented for
 *   reference and cannot be enforced on a TypeScript ``string``.
 */
export interface SignonRequestDto {
  userId: string;
  password: string;
}

/**
 * :purpose: Success response body for ``POST /auth/signon`` representing a
 *   completed authentication. Mirrors the backend
 *   ``com.carddemo.auth.dto.SignonResponseDto`` JSON contract exactly — a focused
 *   three-field result rather than the full :ts:type:`SessionContext`, which this
 *   endpoint does not return.
 * :field userId: authenticated user id echoed back (legacy ``CDEMO-USER-ID`` /
 *   ``WS-USER-ID``); 8 characters maximum.
 * :field userType: granted role as the frozen one-character ``SEC-USR-TYPE`` wire
 *   code (:ts:type:`Role` — ``'A'`` administrator / ``'U'`` standard user). The
 *   client derives the ``ROLE_ADMIN`` / ``ROLE_USER`` authority from this code.
 * :field redirectTarget: post-login navigation target as the legacy CICS menu
 *   transaction id — ``'CA00'`` (administrator menu, legacy ``COADM01C``) or
 *   ``'CM00'`` (standard-user menu, legacy ``COMEN01C``) — derived from the
 *   ``COSGN00C`` ``XCTL`` routing. Always populated on the success path.
 */
export interface SignonResponseDto {
  userId: string;
  userType: Role;
  redirectTarget: string;
}

/**
 * :purpose: Minimal client-side view state for the sign-on screen, modelling the
 *   line-23 error-text region of the ``COSGN00`` map.
 * :field errMsg: error text rendered beneath the sign-on form, mirroring the
 *   ``COSGN00`` ``ERRMSG`` field (:ts:type:`ErrMsg`; ``PIC X(78)``). The empty
 *   string denotes no error.
 * :note: Deliberately minimal; broader form / field-highlight state reuses the
 *   :ts:module:`./common` primitives rather than redefining them here.
 */
export interface SignonScreenState {
  errMsg: ErrMsg;
}
