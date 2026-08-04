/**
 * MainMenuPage
 * ============
 *
 * :purpose: The authenticated CardDemo main menu — a 1:1 replacement for the BMS
 *     mapset ``app/bms/COMEN01.bms`` (CICS transaction ``CM00``, program
 *     ``COMEN01C``). It renders the role-filtered option list returned by the
 *     api-gateway and accepts a two-character option selection that navigates to
 *     the chosen screen, reproducing the ``COMEN01C`` ``PROCESS-ENTER-KEY``
 *     validation order and the PF3 return to the sign-on screen.
 * :output: The default-exported ``MainMenuPage`` function component. It renders
 *     the screen BODY only — the shared ``Layout`` shell renders the header, the
 *     line-23 message banner, and the line-24 PF-key bar from the chrome this
 *     page publishes through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER, PfKeyAction } from '../types';
import type { MenuOption, MenuResponseDto, Role } from '../types';
import { getMainMenu } from '../api';
import { useApi, useSession } from '../hooks';

/** CICS transaction id of this screen (``COMEN01C`` ``WS-TRANID``). */
const TRAN_ID = 'CM00';

/** Legacy program name of this screen (``COMEN01C`` ``WS-PGMNAME``). */
const PROGRAM_NAME = 'COMEN01C';

/** First header title line published to the shell. */
const TITLE01 = 'CardDemo';

/** Second header title line published to the shell. */
const TITLE02 = 'Main Menu';

/**
 * Option slots the BMS map provides (``OPTN001``..``OPTN012``, each ``PIC X(40)``);
 * the option list is truncated to this many lines.
 */
const MENU_OPTION_SLOTS = 12;

/** Sign-on route reached by PF3 (legacy ``XCTL PROGRAM('COSGN00C')``). */
const SIGNON_ROUTE = '/signon';

/** Invalid-option message (``COMEN01C`` ``PROCESS-ENTER-KEY``), verbatim. */
const MSG_INVALID_OPTION = 'Please enter a valid option number...';

/**
 * Admin-only refusal message (``COMEN01C`` ``PROCESS-ENTER-KEY``), verbatim;
 * the trailing blank belongs to the legacy literal.
 */
const MSG_NO_ACCESS = 'No access - Admin Only option... ';

/**
 * :purpose: One row of the legacy main-menu option table
 *     (``app/cpy/COMEN02Y.cpy`` ``CDEMO-MENU-OPT``) paired with the single-page
 *     application route that replaces its ``XCTL`` target.
 * :field programName: legacy target program (``CDEMO-MENU-OPT-PGMNAME``).
 * :field userType: allowed user type (``CDEMO-MENU-OPT-USRTYPE``); ``'A'`` marks
 *     an administrator-only option.
 * :field route: route navigated to when the option is selected.
 */
interface MainMenuTarget {
  readonly programName: string;
  readonly userType: Role;
  readonly route: string;
}

/**
 * :purpose: Option number → navigation target for the ten populated rows of
 *     ``CDEMO-MENU-OPTIONS``, in copybook order. Options that address a single
 *     record in the legacy flow resolve to their feature's entry screen, which
 *     is where the identifier is keyed in.
 */
const MAIN_MENU_TARGETS: ReadonlyMap<number, MainMenuTarget> = new Map([
  [1, { programName: 'COACTVWC', userType: CDEMO_USRTYP_USER, route: '/accounts' }],
  [2, { programName: 'COACTUPC', userType: CDEMO_USRTYP_USER, route: '/accounts/update' }],
  [3, { programName: 'COCRDLIC', userType: CDEMO_USRTYP_USER, route: '/cards' }],
  [4, { programName: 'COCRDSLC', userType: CDEMO_USRTYP_USER, route: '/cards/view' }],
  [5, { programName: 'COCRDUPC', userType: CDEMO_USRTYP_USER, route: '/cards/update' }],
  [6, { programName: 'COTRN00C', userType: CDEMO_USRTYP_USER, route: '/transactions' }],
  [7, { programName: 'COTRN01C', userType: CDEMO_USRTYP_USER, route: '/transactions/view' }],
  [8, { programName: 'COTRN02C', userType: CDEMO_USRTYP_USER, route: '/transactions/add' }],
  [9, { programName: 'CORPT00C', userType: CDEMO_USRTYP_USER, route: '/reports' }],
  [10, { programName: 'COBIL00C', userType: CDEMO_USRTYP_USER, route: '/billpay' }],
]);

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
 * :purpose: Parse an entered option into its numeric form.
 * :param entered: the digits held by the ``OPTION`` field; may be empty.
 * :returns: the option number, or ``NaN`` when nothing was entered — an unmatched
 *     value that reproduces the legacy blank-to-zero rejection.
 */
function parseOptionNumber(entered: string): number {
  if (entered === '') {
    return Number.NaN;
  }
  return Number.parseInt(entered, 10);
}

/**
 * :purpose: The main-menu screen (transaction ``CM00``).
 * :returns: The rendered screen body: the ``Main Menu`` heading, the option
 *     lines, and the ``OPTION`` entry field.
 */
export default function MainMenuPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { isAdmin, signOut } = useSession();
  const {
    data: menu,
    loading,
    error,
    run: loadMenu,
  } = useApi<MenuResponseDto>(getMainMenu);
  const [option, setOption] = useState('');
  const [validationMessage, setValidationMessage] = useState('');

  // Fetched once per mount, as legacy first entry (CDEMO-PGM-ENTER) sent the
  // built map once. The gateway returns the list already role-filtered.
  useEffect(() => {
    void loadMenu();
  }, [loadMenu]);

  const options = visibleMenuOptions(menu);

  // One line-23 message field (ERRMSG, PIC X(78)): a message raised by this
  // screen's own validation supersedes a failed load.
  const errorMessage = validationMessage !== '' ? validationMessage : (error?.message ?? '');

  /**
   * :purpose: Handle ENTER — validate the entered option against the listed
   *     options, apply the administrator-only gate, then navigate to the target
   *     screen (legacy ``PROCESS-ENTER-KEY`` followed by ``XCTL``).
   */
  const handleSubmit = useCallback((): void => {
    const listed = visibleMenuOptions(menu);
    const optionNumber = parseOptionNumber(option);
    const selected = listed.find((candidate) => candidate.optionNumber === optionNumber);
    if (selected === undefined) {
      setValidationMessage(MSG_INVALID_OPTION);
      return;
    }

    const target = MAIN_MENU_TARGETS.get(selected.optionNumber);
    if (target !== undefined && target.userType === CDEMO_USRTYP_ADMIN && !isAdmin) {
      setValidationMessage(MSG_NO_ACCESS);
      return;
    }

    const route = target === undefined ? selected.targetRoute : target.route;
    if (route === null || route === '') {
      setValidationMessage(MSG_INVALID_OPTION);
      return;
    }

    setValidationMessage('');
    navigate(route);
  }, [isAdmin, menu, navigate, option]);

  /**
   * :purpose: Handle PF3 — clear the session and return to the sign-on screen
   *     (legacy ``RETURN-TO-SIGNON-SCREEN``).
   */
  const handleExit = useCallback((): void => {
    void signOut();
    navigate(SIGNON_ROUTE);
  }, [navigate, signOut]);

  // ``pfKeys`` is built inside the effect and must stay out of its dependency
  // list; the effect re-runs only when a handler or the message changes.
  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Continue', onActivate: handleSubmit },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
    ];
    setChrome({
      transactionId: TRAN_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [errorMessage, handleExit, handleSubmit, setChrome]);

  return (
    <>
      <h2 className="neutral">Main Menu</h2>
      <div role="list" aria-busy={loading}>
        {options.map((menuOption) => (
          <div className="label" role="listitem" key={menuOption.optionNumber}>
            {menuOption.optionNumber}. {menuOption.optionName}
          </div>
        ))}
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          handleSubmit();
        }}
      >
        <span className="prompt">Please select an option :</span>{' '}
        <input
          className="field"
          inputMode="numeric"
          maxLength={2}
          size={2}
          aria-label="Option"
          value={option}
          onChange={(event) => setOption(event.target.value.replace(/[^0-9]/g, ''))}
        />
      </form>
    </>
  );
}
