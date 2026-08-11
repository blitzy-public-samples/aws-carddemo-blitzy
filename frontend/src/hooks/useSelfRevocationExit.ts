/**
 * :module: ``frontend/src/hooks/useSelfRevocationExit.ts``
 * :purpose: Close the gap a web session opens that a 3270 terminal could not: an
 *     administrator maintaining the ``COUSR*`` screens may act on their OWN security record,
 *     and ``UserService`` answers a changed authority by revoking every live session of the
 *     affected user (``ROLE_CHANGED``, ``CREDENTIAL_CHANGED``, ``USER_DELETED``). The
 *     maintenance request itself still succeeds, so the screen published its confirmation
 *     while the operator's own session had already stopped authorizing — and the operator only
 *     discovered it on their next action, which was answered ``401`` and bounced them to the
 *     sign-on screen with nothing said. This hook makes that consequence part of the action
 *     that caused it.
 * :output: The named ``useSelfRevocationExit`` hook and the ``SESSION_REVOKED_SENTENCE``
 *     it appends.
 * :note: The probe is issued ONLY when the maintained record is the signed-on operator's
 *     own, so an ordinary maintenance action on somebody else's record costs no additional
 *     request.
 * :note: The probe deliberately uses :func:`getSessionIdentity`, which carries
 *     ``skipAuthRedirect``, so asking the question cannot itself mutate the session store.
 *     Mutating it here would hand the answer to whichever route guard re-rendered first, and
 *     the guard's generic wording would replace the confirmation that names what actually
 *     happened.
 */

import { useCallback } from 'react';
import { useNavigate } from 'react-router';
import { getSessionIdentity } from '../api';
import { SESSION_ENDED_MESSAGE } from '../api/messages';
import { useSession } from './useSession';

/** Route of the sign-on screen (``COSGN00`` / ``CC00``). */
const SIGNON_ROUTE = '/signon';

/**
 * :purpose: The sentence appended to the screen's own confirmation when the completed
 *   maintenance revoked the operator's session. The confirmation literal keeps its
 *   verbatim legacy wording and this sentence states the consequence the legacy
 *   program had no way to produce.
 */
export const SESSION_REVOKED_SENTENCE = SESSION_ENDED_MESSAGE;

/**
 * :purpose: Ask whether a completed maintenance action revoked the operator's own
 *   session, and if it did, carry the screen's confirmation to the sign-on screen with
 *   the consequence stated, then drop the local authority the server no longer honours.
 * :returns: A callback taking the maintained user id and the screen's confirmation
 *   text, resolving ``true`` when the operator was signed out (the caller must not
 *   publish its own confirmation, which now travels on line 23 of the sign-on screen)
 *   and ``false`` when the session survived (the caller publishes it unchanged).
 */
export function useSelfRevocationExit(): (
  affectedUserId: string,
  confirmation: string,
) => Promise<boolean> {
  const navigate = useNavigate();
  const { user, revalidate } = useSession();

  return useCallback(
    async (affectedUserId: string, confirmation: string): Promise<boolean> => {
      const affected = affectedUserId.trim().toUpperCase();
      const signedOn = (user ?? '').trim().toUpperCase();
      if (affected === '' || signedOn === '' || affected !== signedOn) {
        // Somebody else's record: the operator's own authority is untouched.
        return false;
      }

      try {
        await getSessionIdentity();
        // The change altered nothing the session was issued against -- renaming a user
        // revokes no session -- so the screen stays where it is.
        return false;
      } catch {
        // The session no longer authorizes. Reach the public sign-on screen FIRST so
        // the confirmation cannot be overwritten by a guard bouncing off this
        // administrator-only route, and only then clear the stale local identity.
        void navigate(SIGNON_ROUTE, {
          replace: true,
          state: {
            screenMessage: `${confirmation} ${SESSION_REVOKED_SENTENCE}`,
          },
        });
        void revalidate();
        return true;
      }
    },
    [navigate, revalidate, user],
  );
}
