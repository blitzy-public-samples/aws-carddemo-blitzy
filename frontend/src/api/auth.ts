/**
 * :module: ``frontend/src/api/auth.ts``
 * :purpose: Domain API module for the CardDemo sign-on screen (``SignonPage``).
 *   Wraps the api-gateway authentication route, re-expressing the legacy CICS
 *   transaction ``CC00`` / program ``COSGN00C`` (credential verification and
 *   role-based menu routing) as a single stateless REST call issued through the
 *   shared ``apiClient``. Behavior reference: ``app/cbl/COSGN00C.cbl``.
 * :output: The named export ``signon`` — posts sign-on credentials to
 *   ``POST /auth/signon`` and resolves the backend result (authenticated user
 *   id, granted role, and post-login redirect target).
 * :note: Credentials are transmitted exactly as entered: this module applies no
 *   upper-casing or other mutation to ``userId`` / ``password`` and never logs
 *   them, so verification is case-sensitive where ``COSGN00C`` was not. That
 *   deviation is recorded in ``docs/decision-log.md`` (AAP 0.6.7).
 * :note: Errors propagate. The shared ``apiClient`` normalizes every failure into
 *   an ``ApiError``, so this module neither catches nor swallows them.
 */

import apiClient from './client';
import type {
  SessionIdentityDto,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';

/**
 * :purpose: Authenticate a set of sign-on credentials by issuing
 *   ``POST /auth/signon`` (CICS transaction ``CC00``, program ``COSGN00C``). On
 *   success the backend verifies the credentials against the encoded
 *   ``SEC-USR-PWD``, establishes the externalized session context, and derives
 *   the granted role from ``SEC-USR-TYPE`` (``'A'`` administrator / ``'U'``
 *   standard user) together with the post-login redirect target — the
 *   administrator menu (legacy ``COADM01C``) or the standard-user menu (legacy
 *   ``COMEN01C``). This module returns that result unchanged.
 * :param request: the sign-on credentials (``userId`` and ``password``) captured
 *   from the sign-on screen, sent verbatim without any client-side mutation.
 * :returns: a promise resolving to the ``SignonResponseDto`` — the authenticated
 *   user id, the granted ``userType`` role code, and the redirect target.
 */
export async function signon(
  request: SignonRequestDto,
): Promise<SignonResponseDto> {
  const response = await apiClient.post<SignonResponseDto>(
    '/auth/signon',
    request,
  );
  return response.data;
}

/**
 * :purpose: Resolve who is signed on from the SERVER-held session by issuing
 *   ``GET /session``. The legacy screens received ``CDEMO-USER-ID`` and
 *   ``CDEMO-USER-TYPE`` in the COMMAREA on every pseudo-conversational turn; this
 *   route returns the same two fields so the SPA never derives authority from
 *   client-writable browser storage.
 * :returns: a promise resolving to the ``SessionIdentityDto`` of the signed-on
 *   caller.
 * :raises ApiError: with status ``401`` when the session carries no sign-on
 *   context, which is how an expired or revoked session is reported.
 */
export async function getSessionIdentity(): Promise<SessionIdentityDto> {
  const response = await apiClient.get<SessionIdentityDto>('/session', {
    skipAuthRedirect: true,
  });
  return response.data;
}

/**
 * :purpose: Revoke the server-side session by issuing the CSRF-protected
 *   ``POST /logout`` the api-gateway exposes. The gateway clears the
 *   authentication, invalidates the HTTP session (so the Redis entry and its
 *   cookie stop being valid) and deletes the CSRF cookie, answering ``204``.
 * :returns: a promise that resolves once the server has revoked the session.
 * :raises ApiError: when the revocation call itself fails, so a caller can avoid
 *   presenting a signed-out screen over a session that is still live.
 */
export async function logout(): Promise<void> {
  await apiClient.post<void>('/logout', null, { skipAuthRedirect: true });
}
