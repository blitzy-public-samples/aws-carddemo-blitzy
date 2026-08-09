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
import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { guardScreenMessage } from '../components/display';
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
import { useFocusOnChange, useInitialFocus, useScreenAction, useSession } from '../hooks';
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

/**
 * :purpose: The field each ``COSGN00C`` message puts the cursor on, one entry per
 *     ``MOVE -1 TO <field>L`` the program performs before it re-sends the map:
 *     ``USERIDL`` for the blank user id (L121), the unknown user (L250) and the failed
 *     verification (L255); ``PASSWDL`` for the blank password (L126) and the wrong
 *     password (L244).
 *
 *     This is deliberately a second table rather than a reuse of
 *     ``FAULTED_FIELD_BY_MESSAGE``: the two encode different decisions. ``MOVE -1`` is
 *     where the operator resumes typing, whereas ``aria-invalid`` asserts that the value
 *     in a control was rejected. ``MSG_UNABLE_TO_VERIFY`` separates them -- the read of
 *     the security file failed, so no entered value is known to be wrong, yet the program
 *     still returns the cursor to the user id.
 */
const CURSOR_FIELD_BY_MESSAGE: Readonly<Record<string, 'userId' | 'password'>> = {
  [MSG_ENTER_USER_ID]: 'userId',
  [MSG_USER_NOT_FOUND]: 'userId',
  [MSG_UNABLE_TO_VERIFY]: 'userId',
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
  const location = useLocation();
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  // A guard that returned the caller here carries its reason in the navigation state,
  // so the screen opens with that reason on line 23 instead of appearing for no
  // stated cause. Read once, as the initial value: the operator's own next outcome
  // replaces it, exactly as any other line-23 message is replaced.
  const [errorMessage, setErrorMessage] = useState(() =>
    guardScreenMessage(location.state),
  );

  // The control the current line-23 message faults, mirroring ``MOVE -1 TO <field>L``.
  const faultedField = FAULTED_FIELD_BY_MESSAGE[errorMessage] ?? null;
  const [submitting, setSubmitting] = useState(false);
  const [plainText, setPlainText] = useState<string | undefined>(undefined);

  const navigate = useNavigate();
  const { isAuthenticated, role, sessionNotice, clearSessionNotice, signIn, signOut } =
    useSession();
  const { setChrome } = useScreenChrome();

  // The row-23 region carries this screen's own edits first; when it has none, it
  // reports why the operator is back here — a session that ended on its own is the one
  // outcome no screen edit can produce, and the operator is told rather than silently
  // returned to sign-on.
  const screenMessage = errorMessage !== '' ? errorMessage : (sessionNotice ?? '');

  /**
   * Guard held in a ref rather than in state, so a second key press in the same
   * frame as the first is refused: state updates are batched and would let two
   * credential requests race the session-cookie rotation.
   */
  const submitLatch = useRef(false);

  // The field this outcome returns the cursor to, resolved before the focus rules below.
  const cursorField = CURSOR_FIELD_BY_MESSAGE[errorMessage] ?? null;

  // BMS ``IC`` on the USERID field places the cursor on the first send.
  const userIdRef = useInitialFocus<HTMLInputElement>();
  const passwordRef = useRef<HTMLInputElement>(null);

  /*
   * One rule per field, each armed only for the messages that name it, so the cursor
   * lands where the program's own ``MOVE -1`` puts it instead of always returning to the
   * user id. Entering a user id and pressing ENTER with the password still blank is the
   * case the operator meets first: `COSGN00C` answers `Please enter Password ...` with
   * the cursor on PASSWD, which is what advances the operator through the two fields.
   * A message this screen did not raise names no field of its own -- the session-ended
   * notice is the one such case -- and `COSGN00C` opens with the cursor on USERID, so it
   * returns there.
   */
  useFocusOnChange(cursorField === 'userId' ? errorMessage : null, userIdRef);
  useFocusOnChange(cursorField === 'password' ? errorMessage : null, passwordRef);
  useFocusOnChange(errorMessage === '' && sessionNotice !== null ? sessionNotice : null, userIdRef);

  const handleSubmit = useCallback(async (): Promise<void> => {
    if (submitLatch.current) {
      return;
    }
    // The operator has read why they are here and is acting on it, so the notice is
    // withdrawn and this turn's own outcome owns the message region from here on.
    clearSessionNotice();
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
  }, [userId, password, role, signIn, navigate, clearSessionNotice]);

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

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateSubmit = useScreenAction((): void => {
    void handleSubmit();
  });
  const activateExit = useScreenAction((): void => {
    void handleExit();
  });

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
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
        onActivate: activateSubmit,
        enabled: !submitting,
      },
      {
        action: PfKeyAction.PF3,
        label: PF_EXIT_LABEL,
        onActivate: activateExit,
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
      errorMessage: screenMessage,
      infoMessage: '',
      pfKeys,
      busy: submitting,
    });
  }, [
    activateExit,
    activateSubmit,
    screenMessage,
    plainText,
    setChrome,
    submitting,
  ]);

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
          ref={passwordRef}
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
      {/*
        No VISIBLE submit control is rendered in the screen body. `COSGN00.bms` declares two
        entry fields and one row-24 legend field, `'ENTER=Sign-on  F3=Exit'`, so a button
        beside the fields would be observable output the mapset does not declare and would
        duplicate the key the shell already renders on line 24. The shell binds the physical
        Enter key to that same key, which is what submits this form; the control below is
        hidden from sight, from assistive technology and from keyboard navigation, and exists
        only so the form still carries its own implicit submission.
      */}
      <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
    </form>
  );
}
