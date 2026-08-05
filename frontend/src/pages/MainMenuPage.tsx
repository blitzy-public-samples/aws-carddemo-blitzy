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
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';
import type { MenuOption, MenuResponseDto } from '../types';
import { getMainMenu, selectMenuOption } from '../api';
import { useApi, useInitialFocus, useFocusOnChange, useSession } from '../hooks';
import { resolveApiErrorMessage } from '../components/display';
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
 * :purpose: Keep only the characters the ``OPTION`` field accepts and clamp the entry
 *     to its two positions. The mapset declares ``ATTRB=(FSET,IC,NORM,NUM,UNPROT)``
 *     with ``LENGTH=2``, and a 3270 ``NUM`` field admits digits only, so the same
 *     restriction is applied at the point of entry.
 * :param value: the raw value typed into the field.
 * :returns: the digits-only value, at most two characters long.
 */
function sanitizeOption(value: string): string {
  return value.replace(/\D/g, '').slice(0, OPTION_MAX_LENGTH);
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
  const [option, setOption] = useState('');
  const [validationMessage, setValidationMessage] = useState('');

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

  // A refused or rejected selection returns the cursor to the OPTION field, which
  // is the ``MOVE -1 TO OPTIONL`` the legacy program performs with its message.
  // ``COMEN01C`` rejects the entered option; a transport failure faults nothing.
  const faultedOption = validationMessage !== '';

  useFocusOnChange(errorMessage === '' ? null : errorMessage, optionRef);

  /**
   * :purpose: Handle ENTER — submit the entered option to the gateway's selection
   *     endpoint and navigate to the screen it dispatched to. The option edits, the
   *     administrator-only gate and the coming-soon outcome are the server's
   *     (``COMEN01C`` ``PROCESS-ENTER-KEY`` followed by ``XCTL``); this screen only
   *     resolves the dispatched program to its own route.
   */
  const handleSubmit = useCallback(async (): Promise<void> => {
    if (selecting) {
      return;
    }
    setValidationMessage('');
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
  }, [navigate, option, runSelect, selecting]);

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

  // ``pfKeys`` is built inside the effect and must stay out of its dependency
  // list; the effect re-runs only when a handler or the message changes.
  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: 'ENTER=Continue',
        onActivate: () => {
          void handleSubmit();
        },
      },
      {
        action: PfKeyAction.PF3,
        label: 'F3=Exit',
        onActivate: () => {
          void handleExit();
        },
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
      busy,
    });
  }, [busy, errorMessage, handleExit, handleSubmit, setChrome]);

  return (
    <>
      <h2 className="neutral">{SCREEN_NAME}</h2>
      <div role="list">
        {options.map((menuOption) => (
          <div className="label" role="listitem" key={menuOption.optionNumber}>
            {menuOption.optionNumber}. {menuOption.optionName}
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
