/**
 * :module: ``frontend/src/api/messages.ts``
 * :purpose: Hold the one message the client puts on the BMS line-23 region for a
 *   failure that produced no application error envelope, in a module of its own so
 *   both the axios response interceptor and the request hook can read it without
 *   either of them depending on the other.
 * :output: The named ``GENERIC_ERROR_MESSAGE`` and ``SESSION_ENDED_MESSAGE``.
 * :note: Line 23 is a BMS field (``ERRMSG``, ``X(78)``) and carries only the
 *   application's own text. A failure that never reached a CardDemo service — an
 *   nginx or api-gateway rejection, a Spring Security ``StrictHttpFirewall``
 *   refusal, or a transport failure while the browser is offline — leaves a library
 *   diagnostic in ``AxiosError.message`` ("Request failed with status code 400",
 *   "Network Error"), which must never be rendered. This literal stands in for all
 *   of them, and is character-for-character the text the backend's own
 *   ``GlobalExceptionHandler`` emits for an unhandled fault, so the screen reads the
 *   same whichever side of the gateway the fault occurred on.
 */

/** :purpose: Line-23 text for any failure that carried no application envelope. */
export const GENERIC_ERROR_MESSAGE = 'An unexpected error occurred';

/**
 * :purpose: Line-23 text reported when the server-held session ended without the
 *   operator ending it — a ``401`` on any request, a session revoked because the
 *   caller's own privileges changed, or a caller who deleted their own record. It has
 *   NO legacy analogue: a CICS terminal session did not lapse mid-flow, and
 *   ``RETURN-TO-SIGNON-SCREEN`` (``app/cbl/COMEN01C.cbl:L170-177``, taken when
 *   ``EIBCALEN = 0``) transferred to ``COSGN00C`` carrying no message. It is therefore
 *   a deliberate deviation, recorded in ``docs/decision-log.md`` section 51.2.
 * :note: Declared ONCE here and imported by every producer — the response interceptor
 *   (a ``401`` with a zero-byte body), the route guards, the session store's expiry
 *   notice and the self-revocation exit — because one condition must not be reported
 *   in two different wordings depending on which of them answered first.
 */
export const SESSION_ENDED_MESSAGE = 'Your session has ended. Please sign on again.';
