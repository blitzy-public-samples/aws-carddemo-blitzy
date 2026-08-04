/**
 * SignonPage
 * ==========
 *
 * :purpose: Public sign-on screen of the CardDemo single-page application and the
 *     1:1 replacement for BMS mapset ``COSGN00`` (``app/bms/COSGN00.bms``), driven
 *     in the legacy stack by CICS transaction ``CC00`` / program ``COSGN00C``.
 *     Collects the 8-character user id and password, authenticates through
 *     ``POST /auth/signon``, and routes on the granted ``SEC-USR-TYPE`` role,
 *     reproducing the ``COSGN00C`` ``XCTL`` to ``COADM01C`` (administrator menu)
 *     or ``COMEN01C`` (standard-user menu).
 * :params: None. The component takes no props; the router mounts it on the public
 *     ``/signon`` route, the only unauthenticated route of the application.
 * :output: The rendered sign-on form body. The screen header (BMS lines 1-3), the
 *     line-23 message region, and the line-24 function-key legend are published as
 *     chrome and rendered by the shared ``Layout``, not by this page.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { CDEMO_USRTYP_ADMIN, PfKeyAction } from '../types';
import type { Role } from '../types';
import { useSession } from '../hooks';
import { ApiError } from '../api';

/** CICS transaction id of the sign-on screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CC00';

/** Legacy program name shown after ``Prog :`` (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COSGN00C';

/** First header title line (``CCDA-TITLE01``), rendered YELLOW by the header. */
const TITLE01 = 'AWS Mainframe Modernization';

/** Second header title line (``CCDA-TITLE02``), rendered YELLOW by the header. */
const TITLE02 = 'CardDemo';

/** Instructional prompt at BMS ``POS=(17,16)``, ``COLOR=TURQUOISE``. */
const PROMPT_TEXT = 'Type your User ID and Password, then press ENTER:';

/** Field-width hint at BMS ``POS=(19,52)`` / ``(20,52)``, ``COLOR=BLUE``. */
const FIELD_WIDTH_HINT = '(8 Char)';

/** ``USERIDI PIC X(8)`` — BMS ``USERID`` field ``LENGTH=8``. */
const USER_ID_MAX_LENGTH = 8;

/** ``PASSWDI PIC X(8)`` — BMS ``PASSWD`` field ``LENGTH=8``. */
const PASSWORD_MAX_LENGTH = 8;

/** ENTER half of the BMS line-24 legend ``ENTER=Sign-on  F3=Exit``. */
const PF_ENTER_LABEL = 'ENTER=Sign-on';

/** PF3 half of the BMS line-24 legend ``ENTER=Sign-on  F3=Exit``. */
const PF_EXIT_LABEL = 'F3=Exit';

/** Public sign-on route; the PF3 exit target. */
const SIGNON_ROUTE = '/signon';

/** Administrator menu route, replacing the ``XCTL`` to ``COADM01C``. */
const ADMIN_MENU_ROUTE = '/admin';

/** Standard-user menu route, replacing the ``XCTL`` to ``COMEN01C``. */
const MAIN_MENU_ROUTE = '/menu';

/** Blank user-id message, verbatim from ``COSGN00C`` ``PROCESS-ENTER-KEY``. */
const MSG_ENTER_USER_ID = 'Please enter User ID ...';

/** Blank password message, verbatim from ``COSGN00C`` ``PROCESS-ENTER-KEY``. */
const MSG_ENTER_PASSWORD = 'Please enter Password ...';

/** Unverifiable-credential message, verbatim from ``READ-USER-SEC-FILE``. */
const MSG_UNABLE_TO_VERIFY = 'Unable to verify the User ...';

/** HTTP status the api layer raises for a rejected credential. */
const HTTP_UNAUTHORIZED = 401;

/**
 * :purpose: Resolve the line-23 message for a failed sign-on attempt. A ``401``
 *     carries the backend's verbatim legacy text (``Wrong Password. Try again
 *     ...`` / ``User not found. Try again ...``); every other failure, and a
 *     ``401`` without a body message, resolves to the ``WHEN OTHER`` text.
 * :param error: the value thrown by the sign-in call.
 * :returns: the message to display, never an empty string.
 */
function resolveSignonError(error: unknown): string {
  if (error instanceof ApiError && error.status === HTTP_UNAUTHORIZED) {
    const serverMessage = error.body?.message;
    if (serverMessage !== undefined && serverMessage !== '') {
      return serverMessage;
    }
  }
  return MSG_UNABLE_TO_VERIFY;
}

/**
 * :purpose: The sign-on screen. Validates the two entry fields, authenticates the
 *     credentials, routes to the administrator or standard-user menu on success,
 *     and publishes the screen chrome (transaction id, titles, line-23 message,
 *     ``ENTER=Sign-on`` / ``F3=Exit`` legend) to the shared layout.
 * :params: None.
 * :output: The rendered sign-on form.
 */
export default function SignonPage(): ReactElement {
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [errorMessage, setErrorMessage] = useState('');

  const navigate = useNavigate();
  const { role, signIn, signOut } = useSession();
  const { setChrome } = useScreenChrome();

  const handleSubmit = useCallback(async (): Promise<void> => {
    // Edit order and single-message behavior of COSGN00C PROCESS-ENTER-KEY.
    if (userId.trim() === '') {
      setErrorMessage(MSG_ENTER_USER_ID);
      return;
    }
    if (password === '') {
      setErrorMessage(MSG_ENTER_PASSWORD);
      return;
    }
    setErrorMessage('');

    // MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID.
    const userIdUpper = userId.toUpperCase();
    try {
      const result = await signIn(userIdUpper, password);
      // The hook resolves the sign-on response or the established session
      // context; both carry the CDEMO-USER-TYPE role code.
      const signedInRole: Role | null = result?.userType ?? role;
      navigate(
        signedInRole === CDEMO_USRTYP_ADMIN ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE,
      );
    } catch (error: unknown) {
      setErrorMessage(resolveSignonError(error));
    }
  }, [userId, password, role, signIn, navigate]);

  const handleExit = useCallback((): void => {
    setUserId('');
    setPassword('');
    setErrorMessage('');
    void signOut();
    navigate(SIGNON_ROUTE);
  }, [signOut, navigate]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: handleSubmit },
      { action: PfKeyAction.PF3, label: PF_EXIT_LABEL, onActivate: handleExit },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [errorMessage, handleSubmit, handleExit]);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void handleSubmit();
      }}
    >
      <p className="prompt">{PROMPT_TEXT}</p>

      <div>
        <label className="prompt" htmlFor="userId">
          User ID
        </label>{' '}
        <input
          id="userId"
          name="userId"
          type="text"
          className="field"
          maxLength={USER_ID_MAX_LENGTH}
          value={userId}
          autoComplete="username"
          autoFocus
          onChange={(event) => setUserId(event.target.value.toUpperCase())}
        />{' '}
        <span className="label">{FIELD_WIDTH_HINT}</span>
      </div>

      <div>
        <label className="prompt" htmlFor="password">
          Password
        </label>{' '}
        <input
          id="password"
          name="password"
          type="password"
          className="field"
          maxLength={PASSWORD_MAX_LENGTH}
          value={password}
          autoComplete="current-password"
          onChange={(event) => setPassword(event.target.value)}
        />{' '}
        <span className="label">{FIELD_WIDTH_HINT}</span>
      </div>

      <div>
        <button type="submit">Sign-on</button>
      </div>
    </form>
  );
}
