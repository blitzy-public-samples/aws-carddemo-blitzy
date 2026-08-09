/**
 * :module: ``frontend/src/pages/programRoutes.ts``
 * :purpose: Resolve the legacy program a menu selection dispatched to into the single
 *     screen of this application that replaces it — the presentation half of the
 *     ``XCTL PROGRAM(CDEMO-TO-PROGRAM)`` transfer the two menu programs perform.
 * :output: The named :ts:type:`PROGRAM_SCREEN_ROUTES` map and the
 *     :func:`resolveProgramRoute` reader.
 * :note: This module carries no authorization data and makes no authorization
 *     decision. Whether a selection is valid, whether the option exists, and whether
 *     the signed-on user may have it are decided solely by the ``/menu/select`` and
 *     ``/admin/menu/select`` endpoints; only a selection the server already
 *     dispatched reaches this map.
 * :note: This map is the ONLY route authority. The gateway publishes the dispatched
 *     program name and nothing else, because a program name is what ``XCTL`` carries and
 *     the only routes a gateway could name are its own downstream service prefixes --
 *     which are not screens. Three card programs and four user programs would each
 *     collapse onto one prefix, so a route published there would contradict where this
 *     application actually navigates.
 */

/**
 * :purpose: Legacy program name to the route of the screen that replaces it, covering
 *     every program named by ``app/cpy/COMEN02Y.cpy`` (main menu) and
 *     ``app/cpy/COADM02Y.cpy`` (admin menu), plus the sign-on program both menus move to
 *     ``CDEMO-TO-PROGRAM`` for PF3 (``COMEN01C`` L96-98, ``COADM01C`` L96-98) — so every
 *     value either program can dispatch resolves here.
 */
export const PROGRAM_SCREEN_ROUTES: ReadonlyMap<string, string> = new Map([
  ['COSGN00C', '/signon'],
  ['COACTVWC', '/accounts'],
  ['COACTUPC', '/accounts/update'],
  ['COCRDLIC', '/cards'],
  ['COCRDSLC', '/cards/view'],
  ['COCRDUPC', '/cards/update'],
  ['COTRN00C', '/transactions'],
  ['COTRN01C', '/transactions/view'],
  ['COTRN02C', '/transactions/add'],
  ['CORPT00C', '/reports'],
  ['COBIL00C', '/billpay'],
  ['COUSR00C', '/users'],
  ['COUSR01C', '/users/add'],
  ['COUSR02C', '/users/update'],
  ['COUSR03C', '/users/delete'],
]);

/**
 * :purpose: Resolve a dispatched program name to its screen route.
 * :param programName: the program the selection endpoint dispatched to, as carried on
 *     the selection response; may be ``null``.
 * :returns: the screen route, or ``null`` when the name is absent or names no
 *     implemented screen.
 */
export function resolveProgramRoute(programName: string | null): string | null {
  if (programName === null) {
    return null;
  }
  return PROGRAM_SCREEN_ROUTES.get(programName.trim()) ?? null;
}
