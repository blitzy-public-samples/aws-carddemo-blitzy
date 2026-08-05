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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  CDEMO_USRTYP_ADMIN,
  PfKeyAction,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_THANK_YOU,
} from '../types';
import type { Role } from '../types';
import { useFocusOnChange, useInitialFocus, useSession } from '../hooks';
import { ApiError, getAppId, getSysId } from '../api';

/** CICS transaction id of the sign-on screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CC00';

/** Legacy program name shown after ``Prog :`` (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COSGN00C';

/** Instructional prompt at BMS ``POS=(17,16)``, ``COLOR=TURQUOISE``. */
const PROMPT_TEXT = 'Type your User ID and Password, then press ENTER:';

/** Row-5 description literal at BMS ``POS=(5,6)``, ``COLOR=NEUTRAL``, ``LENGTH=66``. */
const SCREEN_DESCRIPTION =
  'This is a Credit Card Demo Application for Mainframe Modernization';

/**
 * The rows 7-15 note block of ``app/bms/COSGN00.bms``, nine ``LENGTH=42``
 * ``COLOR=BLUE`` literals in map order, verbatim.
 */
const NOTE_BLOCK_LINES: readonly string[] = [
  '+========================================+',
  '|%%%%%%%  NATIONAL RESERVE NOTE  %%%%%%%%|',
  '|%(1)  THE UNITED STATES OF KICSLAND (1)%|',
  '|%$$              ___       ********  $$%|',
  '|%$    {x}       (o o)                 $%|',
  '|%$     ******  (  V  )      O N E     $%|',
  '|%(1)          ---m-m---             (1)%|',
  '|%%~~~~~~~~~~~ ONE DOLLAR ~~~~~~~~~~~~~%%|',
  '+========================================+',
];

/** Caption of the user-id field at BMS ``POS=(19,29)``, ``LENGTH=13``, verbatim. */
const USER_ID_CAPTION = 'User ID     :';

/** Caption of the password field at BMS ``POS=(20,29)``, ``LENGTH=13``, verbatim. */
const PASSWORD_CAPTION = 'Password    :';

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

/** ``COSGN00C`` unknown-user message, returned verbatim by the service. */
const MSG_USER_NOT_FOUND = 'User not found. Try again ...';

/** ``COSGN00C`` wrong-password message, returned verbatim by the service. */
const MSG_WRONG_PASSWORD = 'Wrong Password. Try again ...';

/**
 * :purpose: The control each ``COSGN00C`` message faults for the value it rejected.
 *     ``MSG_UNABLE_TO_VERIFY`` reports a failed verification rather than a rejected
 *     credential, so it is absent and marks neither control invalid.
 */
const FAULTED_FIELD_BY_MESSAGE: Readonly<Record<string, 'userId' | 'password'>> = {
  [MSG_ENTER_USER_ID]: 'userId',
  [MSG_USER_NOT_FOUND]: 'userId',
  [MSG_ENTER_PASSWORD]: 'password',
  [MSG_WRONG_PASSWORD]: 'password',
};

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

  // The control the current line-23 message faults, mirroring ``MOVE -1 TO <field>L``.
  const faultedField = FAULTED_FIELD_BY_MESSAGE[errorMessage] ?? null;
  const [submitting, setSubmitting] = useState(false);
  const [plainText, setPlainText] = useState<string | undefined>(undefined);

  const navigate = useNavigate();
  const { isAuthenticated, role, signIn, signOut } = useSession();
  const { setChrome } = useScreenChrome();

  /**
   * Guard held in a ref rather than in state, so a second key press in the same
   * frame as the first is refused: state updates are batched and would let two
   * credential requests race the session-cookie rotation.
   */
  const submitLatch = useRef(false);

  // BMS ``IC`` on the USERID field, and the cursor the program returns there with
  // its message (``MOVE -1 TO USERIDL``).
  const userIdRef = useInitialFocus<HTMLInputElement>();
  useFocusOnChange(errorMessage === '' ? null : errorMessage, userIdRef);

  const handleSubmit = useCallback(async (): Promise<void> => {
    if (submitLatch.current) {
      return;
    }
    // Edit order and single-message behavior of COSGN00C PROCESS-ENTER-KEY.
    if (userId.trim() === '') {
      setErrorMessage(MSG_ENTER_USER_ID);
      return;
    }
    // Reproduces ``IF PASSWDI = SPACES``: an emptiness edit on the entry field, not a
    // comparison against a stored credential, and evaluated in the browser where no
    // secret is present to leak through timing.
    // eslint-disable-next-line security/detect-possible-timing-attacks
    if (password === '') {
      setErrorMessage(MSG_ENTER_PASSWORD);
      return;
    }
    submitLatch.current = true;
    setSubmitting(true);
    setErrorMessage('');

    // MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID.
    const userIdUpper = userId.toUpperCase();
    try {
      const result = await signIn(userIdUpper, password);
      // The hook resolves the sign-on response or the established session
      // context; both carry the CDEMO-USER-TYPE role code.
      const signedInRole: Role | null = result?.userType ?? role;
      void navigate(
        signedInRole === CDEMO_USRTYP_ADMIN ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE,
      );
    } catch (error: unknown) {
      setErrorMessage(resolveSignonError(error));
    } finally {
      submitLatch.current = false;
      setSubmitting(false);
    }
  }, [userId, password, role, signIn, navigate]);

  /**
   * :purpose: Handle PF3 — end the session. ``COSGN00C`` moves
   *     ``CCDA-MSG-THANK-YOU`` to ``WS-MESSAGE`` and performs ``SEND-PLAIN-TEXT``
   *     (L88-L89), which sends the text with ``ERASE`` and returns without a
   *     transaction id, so the erased screen carries that one line and nothing else.
   * :note: A live session is revoked first, because the legacy transaction ends here;
   *     a refused revocation leaves the sign-on screen in place with the server's own
   *     message, since the session is still live.
   */
  const handleExit = useCallback(async (): Promise<void> => {
    setErrorMessage('');
    if (isAuthenticated) {
      try {
        await signOut();
      } catch (error: unknown) {
        setErrorMessage(error instanceof Error ? error.message : '');
        return;
      }
    }
    setUserId('');
    setPassword('');
    setPlainText(CCDA_MSG_THANK_YOU);
  }, [isAuthenticated, signOut]);

  useEffect(() => {
    // The transaction has ended: the erased screen carries only the plain text, so
    // no header, no message region and no key legend are published with it.
    if (plainText !== undefined) {
      setChrome({ plainText });
      return;
    }
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        onActivate: () => {
          void handleSubmit();
        },
        enabled: !submitting,
      },
      {
        action: PfKeyAction.PF3,
        label: PF_EXIT_LABEL,
        onActivate: () => {
          void handleExit();
        },
        enabled: !submitting,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      // COSGN00.bms is the one mapset whose captions carry a space before the colon
      // and the only one with the AppID / SysID row.
      captionStyle: 'signon',
      appId: getAppId(),
      sysId: getSysId(),
      errorMessage,
      infoMessage: '',
      pfKeys,
      busy: submitting,
    });
  }, [errorMessage, handleSubmit, handleExit, plainText, submitting, setChrome]);

  // The screen is gone once the transaction has ended; the shell renders the plain
  // text in the erased frame.
  if (plainText !== undefined) {
    return <></>;
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void handleSubmit();
      }}
    >
      <p className="neutral signon__description">{SCREEN_DESCRIPTION}</p>

      {/*
       * Rows 7-15 of the mapset, nine fixed-width literals. Decorative: it is
       * hidden from assistive technology so it is not read out character by
       * character, while every column is preserved visually.
       */}
      <pre aria-hidden="true" className="label signon__note">
        {NOTE_BLOCK_LINES.join('\n')}
      </pre>

      <p className="prompt signon__prompt">{PROMPT_TEXT}</p>

      <div className="signon__row">
        <label className="prompt" htmlFor="userId">
          {USER_ID_CAPTION}
        </label>{' '}
        <input
          ref={userIdRef}
          {...invalidFieldProps(faultedField === 'userId')}
          id="userId"
          name="userId"
          type="text"
          className="field"
          maxLength={USER_ID_MAX_LENGTH}
          size={USER_ID_MAX_LENGTH}
          value={userId}
          autoComplete="username"
          disabled={submitting}
          onChange={(event) => setUserId(event.target.value.toUpperCase())}
        />{' '}
        <span className="label">{FIELD_WIDTH_HINT}</span>
      </div>

      <div className="signon__row">
        <label className="prompt" htmlFor="password">
          {PASSWORD_CAPTION}
        </label>{' '}
        <input
          {...invalidFieldProps(faultedField === 'password')}
          id="password"
          name="password"
          type="password"
          className="field"
          maxLength={PASSWORD_MAX_LENGTH}
          size={PASSWORD_MAX_LENGTH}
          value={password}
          autoComplete="current-password"
          disabled={submitting}
          onChange={(event) => setPassword(event.target.value)}
        />{' '}
        <span className="label">{FIELD_WIDTH_HINT}</span>
      </div>

      <div>
        <button type="submit" disabled={submitting}>
          Sign-on
        </button>
      </div>
    </form>
  );
}
