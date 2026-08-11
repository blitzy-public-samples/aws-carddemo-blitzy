/**
 * Session context types for the CardDemo React SPA.
 *
 * :module: ``frontend/src/types/session.ts``
 *
 * Externalizes the CICS pseudo-conversational COMMAREA ``CARDDEMO-COMMAREA``
 * (COBOL copybook ``COCOM01Y``) as a plain TypeScript contract. In the legacy
 * mainframe application the COMMAREA carried routing plus user / customer /
 * account / card state across screen interactions; in the modern stack that
 * state is held server-side (Spring Session on Redis / JWT claims) and surfaced
 * to the client as :ts:type:`SessionContext`.
 *
 * Member names mirror the frozen backend wire contract
 * ``com.carddemo.common.dto.SessionContext`` (camelCase JSON properties) exactly,
 * so REST responses bind without field remapping. The verbatim COBOL 88-level
 * condition names are preserved per the spec-literal fidelity rule.
 */

/**
 * User role, mirroring COMMAREA ``CDEMO-USER-TYPE`` (``PIC X(01)``).
 *
 * The single-character wire values are the COBOL 88-level conditions
 * ``88 CDEMO-USRTYP-ADMIN VALUE 'A'`` (administrator) and
 * ``88 CDEMO-USRTYP-USER VALUE 'U'`` (standard user), which the backend maps to
 * the Spring Security authorities ``ROLE_ADMIN`` and ``ROLE_USER`` respectively.
 *
 * :value 'A': administrator (``ROLE_ADMIN``)
 * :value 'U': standard user (``ROLE_USER``)
 */
export type Role = 'A' | 'U';

/**
 * Administrator role code — verbatim COMMAREA condition
 * ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``.
 */
export const CDEMO_USRTYP_ADMIN: Role = 'A';

/**
 * Standard-user role code — verbatim COMMAREA condition
 * ``88 CDEMO-USRTYP-USER VALUE 'U'``.
 */
export const CDEMO_USRTYP_USER: Role = 'U';

/**
 * Maps a :ts:type:`Role` wire code to its backend Spring Security authority
 * (``'A'`` → ``ROLE_ADMIN``, ``'U'`` → ``ROLE_USER``).
 */
export const ROLE_AUTHORITY: Record<Role, 'ROLE_ADMIN' | 'ROLE_USER'> = {
  A: 'ROLE_ADMIN',
  U: 'ROLE_USER',
};

/**
 * Pseudo-conversational program context, mirroring COMMAREA
 * ``CDEMO-PGM-CONTEXT`` (``PIC 9(01)``).
 *
 * Models the first-entry versus re-entry flag used across the 17 online
 * programs (for example, to highlight re-entry-only fields per the ``CSSETATY``
 * screen-attribute rules). Values are the verbatim COBOL 88-level conditions
 * ``88 CDEMO-PGM-ENTER VALUE 0`` and ``88 CDEMO-PGM-REENTER VALUE 1``.
 *
 * :value 0: first entry into the program
 * :value 1: re-entry into the program
 */
export type ProgramContext = 0 | 1;

/**
 * First-entry context — verbatim COMMAREA condition
 * ``88 CDEMO-PGM-ENTER VALUE 0``.
 */
export const CDEMO_PGM_ENTER: ProgramContext = 0;

/**
 * Re-entry context — verbatim COMMAREA condition
 * ``88 CDEMO-PGM-REENTER VALUE 1``.
 */
export const CDEMO_PGM_REENTER: ProgramContext = 1;

/**
 * Externalized replacement for the CICS COMMAREA ``CARDDEMO-COMMAREA`` (copybook
 *     ``COCOM01Y``). Carries routing plus user / customer / account / card context across
 *     stateless REST calls, persisted server-side via Spring Session (Redis) / JWT claims.
 *     Member names and order mirror the backend ``com.carddemo.common.dto.SessionContext``
 *     JSON contract exactly. Before sign-on the context is largely empty: only
 *     :ts:member:`userId` and
 * :ts: member:`userType` are guaranteed once authenticated, so the routing and customer /
 *     account / card members are optional.
 * :member fromTranid: originating CICS transaction id (``CDEMO-FROM-TRANID`` X(04))
 * :member fromProgram: originating program name (``CDEMO-FROM-PROGRAM`` X(08))
 * :member toTranid: target CICS transaction id (``CDEMO-TO-TRANID`` X(04))
 * :member toProgram: target program name (``CDEMO-TO-PROGRAM`` X(08))
 * :member userId: authenticated user id (``CDEMO-USER-ID`` X(08))
 * :member userType: user role (``CDEMO-USER-TYPE`` X(01))
 * :member programContext: first-entry / re-entry flag (``CDEMO-PGM-CONTEXT`` 9(01))
 * :member custId: customer id (``CDEMO-CUST-ID`` 9(09))
 * :member custFname: customer first name (``CDEMO-CUST-FNAME`` X(25))
 * :member custMname: customer middle name (``CDEMO-CUST-MNAME`` X(25))
 * :member custLname: customer last name (``CDEMO-CUST-LNAME`` X(25))
 * :member acctId: account id (``CDEMO-ACCT-ID`` 9(11))
 * :member acctStatus: account status flag (``CDEMO-ACCT-STATUS`` X(01))
 * :member cardNum: card number / PAN (``CDEMO-CARD-NUM`` 9(16))
 * :member lastMap: last BMS map name (``CDEMO-LAST-MAP`` X(7))
 * :member lastMapset: last BMS mapset name (``CDEMO-LAST-MAPSET`` X(7))
 */
export interface SessionContext {
  fromTranid?: string;
  fromProgram?: string;
  toTranid?: string;
  toProgram?: string;
  userId: string;
  userType: Role;
  programContext: ProgramContext;
  custId?: string;
  custFname?: string;
  custMname?: string;
  custLname?: string;
  acctId?: string;
  acctStatus?: string;
  cardNum?: string;
  lastMap?: string;
  lastMapset?: string;
}

/**
 * :purpose: Identity half of the externalized COMMAREA returned by ``GET /session``.
 *   It is the SERVER's answer to "who is signed on", so the SPA resolves the user id
 *   and role from it rather than from client-writable browser storage.
 * :field userId: the signed-on user id (``CDEMO-USER-ID`` / ``SEC-USR-ID``).
 * :field userType: the signed-on role code (``CDEMO-USER-TYPE``) — ``'A'`` for
 *   ``CDEMO-USRTYP-ADMIN`` or ``'U'`` for ``CDEMO-USRTYP-USER``.
 */
export interface SessionIdentityDto {
  userId: string;
  userType: Role;
}
