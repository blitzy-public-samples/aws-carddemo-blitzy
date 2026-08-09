/**
 * AdminMenuPage
 * =============
 *
 * :purpose: Administrator menu screen — the React replacement for BMS mapset
 *     ``COADM01`` (map ``COADM1A``, 24x80) and program ``COADM01C`` under CICS
 *     transaction ``CA00``. It lists the administrator-only options returned by
 *     ``GET /admin/menu`` in the mapset's option slots (``OPTN001``-``OPTN012``,
 *     ``L=40``), accepts a two-character numeric option (``OPTIONI PIC X(2)``), and
 *     submits it to the gateway's own selection endpoint, navigating to the
 *     administration screen the gateway dispatched to. The ``PROCESS-ENTER-KEY``
 *     validation is therefore applied by the server, not re-implemented here; the
 *     line-24 ``ENTER=Continue`` / ``F3=Exit`` key semantics are preserved.
 * :output: The screen body only — heading, option slots, and the ``OPTION``
 *     entry field. The header, the line-23 message region and the line-24
 *     function-key bar are rendered by the shared ``Layout`` shell from the
 *     chrome this page publishes through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useLayoutEffect, useState, useRef } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';
import type { MenuOption, MenuResponseDto } from '../types';
import { getAdminMenu, selectAdminMenuOption } from '../api';
import {
  placeCursor,
  useApi,
  useFocusOnChange,
  useInitialFocus,
  useScreenAction,
  useSession,
} from '../hooks';
import { displayZoned, isRejectedValue, resolveApiErrorMessage } from '../components/display';
import { limitToFieldWidth } from './screenFilters';
import {
  CDEMO_ADMIN_OPT_COUNT,
  MSG_INVALID_OPTION,
  isOptionRefused,
} from './menuOptionEdit';
import { resolveProgramRoute } from './programRoutes';

/** CICS transaction id of the admin menu (``WS-TRANID``). */
const TRANSACTION_ID = 'CA00';

/** Legacy program name of the admin menu (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COADM01C';

/** Row-4 screen heading (BMS ``COLOR=NEUTRAL``, ``ATTRB=BRT``). */
const SCREEN_HEADING = SCREEN_NAMES.COADM01;

/** Row-20 entry prompt (BMS ``COLOR=TURQUOISE``, ``ATTRB=BRT``). */
const OPTION_PROMPT = 'Please select an option :';

/** Width of the ``OPTION`` entry field (``OPTIONI PIC X(2)``). */
const OPTION_MAX_LENGTH = 2;

/** DOM id tying the row-20 prompt to the ``OPTION`` entry field. */
const OPTION_FIELD_ID = 'option';

/**
 * Digits of the option number as ``COADM02Y`` declares it: ``CDEMO-ADMIN-OPT-NUM PIC
 * 9(02)``. ``COADM01C`` L233-L236 strings the field ``DELIMITED BY SIZE``, so all four
 * rows carry two digits and every option name begins in the same column.
 */
const ADMIN_OPT_NUM_DIGITS = 2;

/** Line-24 legend and handler label for the ENTER action. */
const ENTER_KEY_LABEL = 'ENTER=Continue';

/** Line-24 legend and handler label for the PF3 action. */
const EXIT_KEY_LABEL = 'F3=Exit';

/**
 * Route PF3 returns to. ``COADM01C`` L96-98 moves ``'COSGN00C'`` to
 * ``CDEMO-TO-PROGRAM`` and performs ``RETURN-TO-SIGNON-SCREEN``, so PF3 leaves the
 * application rather than dropping to the main menu.
 */
const EXIT_ROUTE = '/signon';

/**
 * :purpose: Clamp the entry to the two positions the ``OPTION`` field physically has
 *     (``LENGTH=2``), and nothing more. Discarding the characters a 3270 ``NUM`` field
 *     would have refused looks equivalent but is not: it rewrites what the operator
 *     entered into a DIFFERENT, valid option and dispatches that one. ``COADM01C`` keeps
 *     the value as received and tests it with ``IF WS-OPTION IS NOT NUMERIC``, so the
 *     value is carried through unaltered and :func:`isOptionRefused` refuses it.
 * :param value: raw value typed into the field.
 * :returns: the value, at most two characters long.
 */
function sanitizeOption(value: string): string {
  return limitToFieldWidth(value, OPTION_MAX_LENGTH);
}

/**
 * :purpose: The administrator menu screen (transaction ``CA00``). Loads the admin
 *     options, submits the typed option number to the gateway's selection endpoint,
 *     and navigates to the administration screen it dispatched to; PF3 revokes the
 *     session and returns to the sign-on screen (``COADM01C``
 *     ``RETURN-TO-SIGNON-SCREEN``).
 * :returns: The rendered screen body.
 */
export default function AdminMenuPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { signOut } = useSession();
  const { data, loading, error, run } = useApi(getAdminMenu);
  const {
    loading: selecting,
    error: selectError,
    run: runSelect,
  } = useApi(selectAdminMenuOption);

  const [option, setOption] = useState('');
  const [actionMessage, setActionMessage] = useState('');

  const menu: MenuResponseDto | null = data;
  const options: MenuOption[] = menu?.options ?? [];
  const busy = loading || selecting;

  // BMS ``IC`` on the OPTION field places the cursor there when the map is sent, and
  // a message returns it there, which is the ``MOVE -1 TO OPTIONL`` the program
  // performs alongside its message.
  const optionRef = useInitialFocus<HTMLInputElement>();

  // One line-23 field carrying one message: the outcome of the current key press
  // first, then a refused selection, then a failed load.
  let errorMessage = '';
  if (actionMessage !== '') {
    errorMessage = actionMessage;
  } else if (selectError !== null) {
    errorMessage = resolveApiErrorMessage(selectError);
  } else if (error !== null) {
    errorMessage = resolveApiErrorMessage(error);
  }

  // ``COADM01C`` rejects the entered option; a transport failure faults nothing.
  /*
   * The option field is faulted whenever the option itself was refused: by this screen's
   * own edit, or by the server rejecting the value it was sent (``400``). A transport or
   * server failure faults no field, because nothing was found wrong with what was typed.
   */
  const faultedOption = actionMessage !== '' || isRejectedValue(selectError);

  // The third argument is the keyboard-locked interval: an outcome published while the
  // entry field is still disabled must place the cursor once the field is live again, and
  // a second identical refusal must place it a second time, because every re-send of the
  // map re-applies the ``IC`` attribute the mapset puts on this field.
  useFocusOnChange(errorMessage === '' ? null : errorMessage, optionRef, !busy);

  useEffect(() => {
    void run();
  }, [run]);

  /**
   * :purpose: Keyboard-lock latch: ``true`` from the instant a selection is dispatched
   *     until its answer has been applied. A 3270 locked the keyboard for exactly that
   *     interval, so one selection could never be sent twice. The latch is a ref rather
   *     than the ``selecting`` state because two activations in the same task both
   *     observe the state as it was before either of them, and both would be admitted.
   */
  const submitLatch = useRef<boolean>(false);

  /**
   * :purpose: Handle ENTER — submit the entered option to the gateway's selection
   *     endpoint and navigate to the screen it dispatched to. The option edits and
   *     the coming-soon outcome are the server's (``COADM01C``
   *     ``PROCESS-ENTER-KEY``); this screen only resolves the dispatched program to
   *     its own route.
   */
  const handleSubmit = useCallback(async (): Promise<void> => {
    if (submitLatch.current) {
      return;
    }
    submitLatch.current = true;
    setActionMessage('');
    try {
      // ``PROCESS-ENTER-KEY`` runs the option edit before it reaches its ``XCTL``, so a
      // refused entry never leaves the screen: the map is re-sent with the refusal on
      // line 23 and the cursor back on the option field, and no other program is entered.
      if (isOptionRefused(option, CDEMO_ADMIN_OPT_COUNT)) {
        setActionMessage(MSG_INVALID_OPTION);
        placeCursor(optionRef.current);
        return;
      }
      const outcome = await runSelect({ option, aid: 'ENTER' });
      if (outcome === undefined) {
        return;
      }
      if (!outcome.dispatched) {
        setActionMessage(outcome.message ?? '');
        return;
      }
      const route = resolveProgramRoute(outcome.programName);
      if (route === null) {
        setActionMessage(outcome.message ?? '');
        return;
      }
      void navigate(route);
    } finally {
      submitLatch.current = false;
    }
  }, [navigate, option, optionRef, runSelect]);

  /**
   * :purpose: Handle PF3 — revoke the server session, then return to the sign-on
   *     screen (``COADM01C`` L96-98 ``RETURN-TO-SIGNON-SCREEN``).
   * :note: The sign-on screen is presented only after the server has revoked the
   *     session; a failed revocation keeps the menu in place and surfaces the
   *     server's own message, because the session is still live.
   */
  const handleExit = useCallback(async (): Promise<void> => {
    try {
      await signOut();
    } catch (error: unknown) {
      setActionMessage(error instanceof Error ? error.message : '');
      return;
    }
    setActionMessage('');
    void navigate(EXIT_ROUTE);
  }, [navigate, signOut]);

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
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: ENTER_KEY_LABEL,
        onActivate: activateSubmit,
      },
      {
        action: PfKeyAction.PF3,
        label: EXIT_KEY_LABEL,
        onActivate: activateExit,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      busy,
    });
  }, [activateExit, activateSubmit, busy, errorMessage, setChrome]);

  return (
    <>
      <h3 className="neutral">{SCREEN_HEADING}</h3>
      <div role="list">
        {options.map((entry) => (
          <div className="label" role="listitem" key={entry.optionNumber}>
            {displayZoned(entry.optionNumber, ADMIN_OPT_NUM_DIGITS)}.{' '}
            {entry.optionName}
          </div>
        ))}
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void handleSubmit();
        }}
      >
        <label className="prompt" htmlFor={OPTION_FIELD_ID}>
          {OPTION_PROMPT}
        </label>{' '}
        <input
          ref={optionRef}
          id={OPTION_FIELD_ID}
          {...invalidFieldProps(faultedOption)}
          name={OPTION_FIELD_ID}
          className="field"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={OPTION_MAX_LENGTH}
          size={OPTION_MAX_LENGTH}
          disabled={selecting}
          value={option}
          onChange={(event) => {
            setOption(sanitizeOption(event.target.value));
          }}
        />
      </form>
    </>
  );
}
