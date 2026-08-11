/**
 * MainMenuPage
 * ============
 *
 * :purpose: The authenticated CardDemo main menu — a 1:1 replacement for the BMS
 *     mapset ``app/bms/COMEN01.bms`` (CICS transaction ``CM00``, program
 *     ``COMEN01C``). It renders the role-filtered option list returned by the
 *     api-gateway, submits a two-character option selection to the gateway's own
 *     selection endpoint, and navigates to the screen the gateway dispatched to,
 *     so the ``COMEN01C`` ``PROCESS-ENTER-KEY`` validation order and its
 *     administrator-only gate are applied by the server rather than re-implemented
 *     here. PF3 returns to the sign-on screen.
 * :output: The default-exported ``MainMenuPage`` function component. It renders
 *     the screen BODY only — the shared ``Layout`` shell renders the header, the
 *     line-23 message banner, and the line-24 PF-key bar from the chrome this
 *     page publishes through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useLayoutEffect, useState, useRef } from 'react';
import type { ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  PfKeyAction,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_INVALID_KEY,
  SCREEN_NAMES,
} from '../types';
import type { MenuOption, MenuResponseDto } from '../types';
import { getMainMenu, selectMenuOption } from '../api';
import {
  placeCursor,
  useApi,
  useInitialFocus,
  useFocusOnChange,
  useScreenAction,
  useSession,
} from '../hooks';
import {
  displayZoned,
  guardScreenMessage,
  isRejectedValue,
  resolveApiErrorMessage,
} from '../components/display';
import { limitToFieldWidth } from './screenFilters';
import {
  CDEMO_MENU_OPT_COUNT,
  MSG_INVALID_OPTION,
  isOptionRefused,
} from './menuOptionEdit';
import { resolveProgramRoute } from './programRoutes';

/** CICS transaction id of this screen (``COMEN01C`` ``WS-TRANID``). */
const TRAN_ID = 'CM00';

/** Legacy program name of this screen (``COMEN01C`` ``WS-PGMNAME``). */
const PROGRAM_NAME = 'COMEN01C';

/**
 * Option slots the BMS map provides (``OPTN001``..``OPTN012``, each ``PIC X(40)``);
 * the option list is truncated to this many lines.
 */
const MENU_OPTION_SLOTS = 12;

/** Sign-on route reached by PF3 (legacy ``XCTL PROGRAM('COSGN00C')``). */
const SIGNON_ROUTE = '/signon';

/** Row-4 screen name of ``app/bms/COMEN01.bms``. */
const SCREEN_NAME = SCREEN_NAMES.COMEN01;

/** Row-20 entry prompt (BMS ``COLOR=TURQUOISE``), verbatim. */
const OPTION_PROMPT = 'Please select an option :';

/** Width of the ``OPTION`` entry field (``OPTIONI PIC X(2)``). */
const OPTION_MAX_LENGTH = 2;

/**
 * Digits of the option number as ``COMEN02Y`` declares it: ``CDEMO-MENU-OPT-NUM PIC
 * 9(02)``. ``COMEN01C`` L243-L246 strings the field ``DELIMITED BY SIZE``, so all ten
 * rows carry two digits and every option name begins in the same column.
 */
const MENU_OPT_NUM_DIGITS = 2;

/** DOM id tying the row-20 prompt to the ``OPTION`` entry field. */
const OPTION_FIELD_ID = 'option';



/**
 * :purpose: The option lines the screen can display, capped at the number of BMS
 *     option slots.
 * :param menu: the loaded menu payload, or ``null`` before the first response.
 * :returns: the visible options in server order; an empty list while unloaded.
 */
function visibleMenuOptions(menu: MenuResponseDto | null): MenuOption[] {
  if (menu === null) {
    return [];
  }
  return menu.options.slice(0, MENU_OPTION_SLOTS);
}

/**
 * :purpose: Clamp the entry to the two positions the ``OPTION`` field physically has
 *     (``LENGTH=2``), and nothing more. Discarding the characters a 3270 ``NUM`` field
 *     would have refused looks equivalent but is not: it rewrites what the operator
 *     entered into a DIFFERENT, valid option and dispatches that. ``-1`` became ``1``
 *     and opened Account View. ``COMEN01C`` keeps the value as received and tests it
 *     with ``IF WS-OPTION IS NOT NUMERIC`` -- a test that would be dead code if the
 *     field could only ever hold digits -- so the value is carried through unaltered
 *     and the edit refuses it.
 * :param value: the raw value typed into the field.
 * :returns: the value, at most two characters long.
 */
function sanitizeOption(value: string): string {
  return limitToFieldWidth(value, OPTION_MAX_LENGTH);
}

/**
 * :purpose: The main-menu screen (transaction ``CM00``).
 * :returns: The rendered screen body: the ``Main Menu`` heading, the option
 *     lines, and the ``OPTION`` entry field.
 */
export default function MainMenuPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { signOut } = useSession();
  const {
    data: menu,
    loading,
    error,
    run: loadMenu,
  } = useApi<MenuResponseDto>(getMainMenu);
  const {
    loading: selecting,
    error: selectError,
    run: runSelect,
  } = useApi(selectMenuOption);
  const location = useLocation();
  const [option, setOption] = useState('');
  // ``COMEN01C`` publishes its own refusal literal on line 23 when a standard user
  // reaches an administrator-only option; the route guard hands the same literal over
  // in the navigation state, so the menu opens with it rather than in silence.
  const [validationMessage, setValidationMessage] = useState(() =>
    guardScreenMessage(location.state),
  );

  // BMS ``IC`` on the OPTION field: the cursor is placed there when the map is
  // sent, so the field takes focus on entry.
  const optionRef = useInitialFocus<HTMLInputElement>();

  // Fetched once per mount, as legacy first entry (CDEMO-PGM-ENTER) sent the
  // built map once. The gateway returns the list already role-filtered.
  useEffect(() => {
    void loadMenu();
  }, [loadMenu]);

  const options = visibleMenuOptions(menu);
  const busy = loading || selecting;

  // One line-23 message field (ERRMSG, PIC X(78)) carrying one message at a time:
  // the outcome of the current key press first, then a refused selection, then a
  // failed load.
  let errorMessage = '';
  if (validationMessage !== '') {
    errorMessage = validationMessage;
  } else if (selectError !== null) {
    errorMessage = resolveApiErrorMessage(selectError);
  } else if (error !== null) {
    errorMessage = resolveApiErrorMessage(error);
  }

  /*
   * The option field is faulted whenever the option itself was refused: by this screen's
   * own edit, or by the server rejecting the value it was sent (``400``). A transport or
   * server failure faults no field, because nothing was found wrong with what was typed.
   */
  const faultedOption =
    validationMessage !== '' || isRejectedValue(selectError);

  // The third argument is the keyboard-locked interval: an outcome published while the
  // entry field is still disabled must place the cursor once the field is live again, and
  // a second identical refusal must place it a second time, because every re-send of the
  // map re-applies the ``IC`` attribute the mapset puts on this field.
  useFocusOnChange(errorMessage === '' ? null : errorMessage, optionRef, !busy);

  /**
   * :purpose: Keyboard-lock latch: ``true`` from the instant a selection is dispatched
   *     until its answer has been applied, so a repeated activation in the same task
   *     cannot send the option twice.
   */
  const submitLatch = useRef<boolean>(false);

  /**
   * :purpose: Handle ENTER — submit the entered option to the gateway's selection
   *     endpoint and navigate to the screen it dispatched to. The option edits, the
   *     administrator-only gate and the coming-soon outcome are the server's
   *     (``COMEN01C`` ``PROCESS-ENTER-KEY`` followed by ``XCTL``); this screen only
   *     resolves the dispatched program to its own route.
   */
  const handleSubmit = useCallback(async (): Promise<void> => {
    // A 3270 keyboard was locked from the instant an AID was sent until the program
    // replied, so one selection could never be sent twice. The latch is a ref rather
    // than the `selecting` state because two activations in one task both read the
    // state as it was before either of them, and both would be admitted.
    if (submitLatch.current) {
      return;
    }
    submitLatch.current = true;
    setValidationMessage('');
    try {
      // ``PROCESS-ENTER-KEY`` runs the option edits before it reaches its ``XCTL``, so a
      // refused entry never leaves the screen: the map is re-sent with the refusal on
      // line 23 and the cursor back on the option field, and no other program is entered.
      if (isOptionRefused(option, CDEMO_MENU_OPT_COUNT)) {
        setValidationMessage(MSG_INVALID_OPTION);
        placeCursor(optionRef.current);
        return;
      }
      const outcome = await runSelect({ option, aid: 'ENTER' });
      if (outcome === undefined) {
        return;
      }
      if (!outcome.dispatched) {
        setValidationMessage(outcome.message ?? '');
        return;
      }
      const route = resolveProgramRoute(outcome.programName);
      if (route === null) {
        setValidationMessage(outcome.message ?? '');
        return;
      }
      void navigate(route);
    } finally {
      submitLatch.current = false;
    }
  }, [navigate, option, optionRef, runSelect]);

  /**
   * :purpose: Handle PF3 — revoke the server session, then return to the sign-on
   *     screen (legacy ``RETURN-TO-SIGNON-SCREEN``, ``COMEN01C`` L96-98).
   * :note: The sign-on screen is presented only after the server has revoked the
   *     session; a failed revocation leaves the menu in place and reports the
   *     failure, because the session is still live.
   */
  const handleExit = useCallback(async (): Promise<void> => {
    try {
      await signOut();
    } catch (error: unknown) {
      setValidationMessage(error instanceof Error ? error.message : '');
      return;
    }
    setValidationMessage('');
    void navigate(SIGNON_ROUTE);
  }, [navigate, signOut]);

  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COMEN01C`` L99-103) — publish
   *     ``CCDA-MSG-INVALID-KEY`` on line 23 and re-send the map, which returns the
   *     cursor to the mapset's ``IC`` field. No entered value is rejected, so no
   *     field is faulted.
   */
  const handleUnhandledKey = useCallback((): void => {
    setValidationMessage(CCDA_MSG_INVALID_KEY);
    placeCursor(optionRef.current);
  }, [optionRef]);

  // The published activators are identity-stable and always dispatch to the newest
  // render's handler, so the legend is not rebuilt on every keystroke and an AID can
  // never act on an option the screen has already replaced.
  const activateSubmit = useScreenAction((): void => {
    void handleSubmit();
  });
  const activateExit = useScreenAction((): void => {
    void handleExit();
  });
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);

  // ``pfKeys`` is built inside the effect and must stay out of its dependency
  // list; the effect re-runs only when a handler, the message or the busy state changes.
  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: 'ENTER=Continue',
        onActivate: activateSubmit,
      },
      {
        action: PfKeyAction.PF3,
        label: 'F3=Exit',
        onActivate: activateExit,
      },
    ];
    setChrome({
      transactionId: TRAN_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy,
    });
  }, [activateExit, activateSubmit, activateUnhandledKey, busy, errorMessage, setChrome]);

  return (
    <>
      <h3 className="neutral">{SCREEN_NAME}</h3>
      <div role="list">
        {options.map((menuOption) => (
          <div className="label" role="listitem" key={menuOption.optionNumber}>
            {displayZoned(menuOption.optionNumber, MENU_OPT_NUM_DIGITS)}.{' '}
            {menuOption.optionName}
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
          onChange={(event) => setOption(sanitizeOption(event.target.value))}
        />
      </form>
    </>
  );
}
