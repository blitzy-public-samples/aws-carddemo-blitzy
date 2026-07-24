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
 * :note: Credentials are transmitted exactly as entered — this module performs
 *   no client-side upper-casing or other mutation of ``userId`` / ``password``
 *   and never logs them. The legacy ``COSGN00C`` upper-casing (and its resulting
 *   case-insensitive comparison) is a deliberate, decision-logged behavior
 *   change to case-sensitive encoder verification; the rationale lives in
 *   ``docs/decision-log.md`` (AAP 0.6.7), not in this code. Errors are left to
 *   propagate: the shared ``apiClient`` already normalizes every failure into an
 *   ``ApiError`` (for example, a ``401`` triggers the guarded redirect to the
 *   sign-on route), so this module neither catches nor swallows them.
 */

import apiClient from './client';
import type { SignonRequestDto, SignonResponseDto } from '../types';

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
