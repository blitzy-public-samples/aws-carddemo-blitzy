/**
 * AdminMenuPage
 * =============
 *
 * :purpose: Administrator menu screen — the React replacement for BMS mapset
 *     ``COADM01`` (map ``COADM1A``, 24x80) and program ``COADM01C`` under CICS
 *     transaction ``CA00``. It lists the administrator-only options returned by
 *     ``GET /admin/menu`` in the mapset's option slots (``OPTN001``-``OPTN012``,
 *     ``L=40``), accepts a two-character numeric option (``OPTIONI PIC X(2)``), and
 *     dispatches to the mapped administration screen, reproducing the
 *     ``PROCESS-ENTER-KEY`` validation and the line-24 ``ENTER=Continue``
 *     / ``F3=Exit`` key semantics.
 * :output: The screen body only — heading, option slots, and the ``OPTION``
 *     entry field. The header, the line-23 message region and the line-24
 *     function-key bar are rendered by the shared ``Layout`` shell from the
 *     chrome this page publishes through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { MenuOption, MenuResponseDto } from '../types';
import { getAdminMenu } from '../api';
import { useApi, useSession } from '../hooks';

/** CICS transaction id of the admin menu (``WS-TRANID``). */
const TRANSACTION_ID = 'CA00';

/** Legacy program name of the admin menu (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COADM01C';

/** First header title line published to the shell. */
const TITLE01 = 'CardDemo';

/** Second header title line published to the shell. */
const TITLE02 = 'Admin Menu';

/** Row-4 screen heading (BMS ``COLOR=NEUTRAL``, ``ATTRB=BRT``). */
const SCREEN_HEADING = 'Admin Menu';

/** Row-20 entry prompt (BMS ``COLOR=TURQUOISE``, ``ATTRB=BRT``). */
const OPTION_PROMPT = 'Please select an option :';

/** Width of the ``OPTION`` entry field (``OPTIONI PIC X(2)``). */
const OPTION_MAX_LENGTH = 2;

/** DOM id tying the row-20 prompt to the ``OPTION`` entry field. */
const OPTION_FIELD_ID = 'option';

/** Accessible name of the ``OPTION`` entry field. */
const OPTION_FIELD_LABEL = 'Option';

/** Line-24 legend and handler label for the ENTER action. */
const ENTER_KEY_LABEL = 'ENTER=Continue';

/** Line-24 legend and handler label for the PF3 action. */
const EXIT_KEY_LABEL = 'F3=Exit';

/** Route PF3 returns to (the main menu, ``COMEN01C``). */
const EXIT_ROUTE = '/menu';

/**
 * Message for a blank, zero, non-numeric, or out-of-range option, verbatim from
 * ``COADM01C`` ``PROCESS-ENTER-KEY``.
 */
const INVALID_OPTION_MESSAGE = 'Please enter a valid option number...';

/**
 * Message shown when a signed-in non-administrator reaches this screen,
 * verbatim from the admin-only guard in ``COMEN01C``.
 */
const ADMIN_ONLY_MESSAGE = 'No access - Admin Only option... ';

/**
 * Message for an option whose legacy program has no implemented screen,
 * verbatim from the ``COADM01C`` non-dispatch branch.
 */
const COMING_SOON_MESSAGE = 'This option is coming soon ...';

/**
 * :purpose: One administrator menu option and the single-page-application route
 *     that replaces the ``XCTL`` transfer its legacy program performed.
 * :field optionNumber: option number typed on the map
 *     (``CDEMO-ADMIN-OPT-NUM``).
 * :field programName: legacy transfer target (``CDEMO-ADMIN-OPT-PGMNAME``).
 * :field route: route the option navigates to.
 */
interface AdminOptionRoute {
  readonly optionNumber: number;
  readonly programName: string;
  readonly route: string;
}

/**
 * Admin option-to-route table, mirroring the four populated rows of
 * ``app/cpy/COADM02Y.cpy`` (``CDEMO-ADMIN-OPT-COUNT`` = 4).
 */
const ADMIN_OPTION_ROUTES: readonly AdminOptionRoute[] = [
  { optionNumber: 1, programName: 'COUSR00C', route: '/users' },
  { optionNumber: 2, programName: 'COUSR01C', route: '/users/add' },
  { optionNumber: 3, programName: 'COUSR02C', route: '/users/update' },
  { optionNumber: 4, programName: 'COUSR03C', route: '/users/delete' },
];

/**
 * :purpose: Keep only the characters a 3270 numeric field (``ATTRB=NUM``)
 *     accepts and clamp the entry to the two positions of ``OPTIONI``.
 * :param value: raw value typed into the field.
 * :returns: the digits-only value, at most two characters long.
 */
function sanitizeOption(value: string): string {
  return value.replace(/\D/g, '').slice(0, OPTION_MAX_LENGTH);
}

/**
 * :purpose: Pick the served option a typed option number selects, reproducing
 *     the ``CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)`` table lookup and the
 *     ``WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS`` rejection.
 * :param options: options served for the screen.
 * :param selected: option number typed into the ``OPTION`` field.
 * :returns: the selected option, or ``undefined`` when the number is out of
 *     range and therefore invalid.
 */
function selectOption(
  options: MenuOption[],
  selected: number,
): MenuOption | undefined {
  if (!Number.isInteger(selected) || selected < 1) {
    return undefined;
  }
  const byNumber = options.find(
    (candidate) => candidate.optionNumber === selected,
  );
  if (byNumber !== undefined) {
    return byNumber;
  }
  return selected <= options.length ? options[selected - 1] : undefined;
}

/**
 * :purpose: Resolve the route a selected admin option navigates to. The option's
 *     legacy program is matched first, then its option number, then the route
 *     the gateway already resolved.
 * :param option: option selected from the served admin menu.
 * :returns: the route to navigate to, or ``null`` when the option maps to no
 *     implemented screen.
 */
function resolveAdminRoute(option: MenuOption): string | null {
  const byProgram = ADMIN_OPTION_ROUTES.find(
    (entry) => entry.programName === option.programName.trim(),
  );
  if (byProgram !== undefined) {
    return byProgram.route;
  }
  const byNumber = ADMIN_OPTION_ROUTES.find(
    (entry) => entry.optionNumber === option.optionNumber,
  );
  if (byNumber !== undefined) {
    return byNumber.route;
  }
  const gatewayRoute = option.targetRoute;
  if (gatewayRoute !== null && gatewayRoute.trim() !== '') {
    return gatewayRoute;
  }
  return null;
}

/**
 * :purpose: The administrator menu screen (transaction ``CA00``). Loads the
 *     admin options, validates the typed option number, and navigates to the
 *     selected administration screen; PF3 returns to the main menu.
 * :returns: The rendered screen body.
 */
export default function AdminMenuPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { isAuthenticated, isAdmin } = useSession();
  const { data, error, run } = useApi(getAdminMenu);

  const [option, setOption] = useState('');
  const [actionMessage, setActionMessage] = useState('');

  const menu: MenuResponseDto | null = data;
  const options: MenuOption[] = menu?.options ?? [];

  // A signed-in non-administrator is refused every admin option; the RequireAdmin
  // route guard in App.tsx normally prevents this entry.
  const accessDenied = isAuthenticated && !isAdmin;

  let errorMessage = '';
  if (accessDenied) {
    errorMessage = ADMIN_ONLY_MESSAGE;
  } else if (actionMessage !== '') {
    errorMessage = actionMessage;
  } else if (error !== null) {
    errorMessage = error.message;
  }

  useEffect(() => {
    void run();
  }, [run]);

  const handleSubmit = useCallback((): void => {
    if (accessDenied) {
      setActionMessage('');
      return;
    }
    const available = menu?.options ?? [];
    const entered = option.trim();
    const selected = entered === '' ? 0 : Number.parseInt(entered, 10);
    const chosen = selectOption(available, selected);
    if (chosen === undefined) {
      setActionMessage(INVALID_OPTION_MESSAGE);
      return;
    }
    const route = resolveAdminRoute(chosen);
    if (route === null) {
      setActionMessage(COMING_SOON_MESSAGE);
      return;
    }
    setActionMessage('');
    navigate(route);
  }, [accessDenied, menu, navigate, option]);

  const handleExit = useCallback((): void => {
    setActionMessage('');
    navigate(EXIT_ROUTE);
  }, [navigate]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: ENTER_KEY_LABEL,
        onActivate: handleSubmit,
      },
      {
        action: PfKeyAction.PF3,
        label: EXIT_KEY_LABEL,
        onActivate: handleExit,
      },
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
  }, [setChrome, errorMessage, handleSubmit, handleExit]);

  return (
    <>
      <h3 className="neutral">{SCREEN_HEADING}</h3>
      <div role="list">
        {options.map((entry) => (
          <div className="label" role="listitem" key={entry.optionNumber}>
            {entry.optionNumber}. {entry.optionName}
          </div>
        ))}
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          handleSubmit();
        }}
      >
        <label className="prompt" htmlFor={OPTION_FIELD_ID}>
          {OPTION_PROMPT}
        </label>
        <input
          id={OPTION_FIELD_ID}
          className="field"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={OPTION_MAX_LENGTH}
          size={OPTION_MAX_LENGTH}
          aria-label={OPTION_FIELD_LABEL}
          value={option}
          onChange={(event) => {
            setOption(sanitizeOption(event.target.value));
          }}
          onKeyDown={(event) => {
            // ENTER on the entry field submits the screen here; every other key
            // still propagates to the shell key bar.
            if (event.key === 'Enter') {
              event.preventDefault();
              event.stopPropagation();
              handleSubmit();
            }
          }}
          autoFocus
        />
      </form>
    </>
  );
}
