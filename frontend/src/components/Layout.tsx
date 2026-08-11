/**
 * Layout
 * ======
 *
 * :purpose: Shared 24x80 screen shell that reproduces the fixed BMS mapset frame
 *     every CardDemo screen inherits (``app/bms/COSGN00.bms`` and the sixteen
 *     sibling mapsets): the header rows 1-3 (:func:`Header`), the routed screen
 *     body rows 4-22, the line-23 message region (:func:`ErrorBanner`), and the
 *     line-24 function-key legend (:func:`PFKeyBar`). The routed page publishes
 *     its own transaction id, program name, titles, message, and PF keys into the
 *     shell through the :func:`useScreenChrome` context, mirroring the way a CICS
 *     program moved its own fields into the shared symbolic map before
 *     ``SEND MAP``.
 * :output: The rendered ``.screen`` frame wrapping the routed children.
 * :note: The frame is keyed by the routed location, so navigating discards every
 *     field of the previous screen exactly as a CICS ``XCTL`` sent a fresh map:
 *     no title, legend, message, or busy state survives the transfer, and two
 *     screens can never be shown at once. :func:`useScreenChrome` additionally
 *     exposes ``resetChrome`` for a page that clears the frame in its own cleanup.
 * :note: The published chrome and the publishing actions live in two separate
 *     contexts. Pages consume only the action context, whose value never changes
 *     identity, so publishing chrome re-renders the frame regions and not the
 *     routed page.
 * :note: A published function key is never handed to the key bar directly. The frame
 *     records the newest legend synchronously as the page publishes it and hands the
 *     key bar an identity-stable indirection per AID that resolves the handler at the
 *     moment the key is struck. Together with the pages publishing from a LAYOUT
 *     effect, that is what guarantees a key acts on the screen state the operator can
 *     see: a passive publication is deferred past the commit, so a key struck straight
 *     after a keystroke would otherwise run the handler closure of the render that
 *     preceded it and send the value the field held before that keystroke.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactElement, ReactNode } from 'react';
import { useLocation } from 'react-router';
import Header from './Header';
import type { HeaderCaptionStyle } from './Header';
import ErrorBanner from './ErrorBanner';
import PFKeyBar from './PFKeyBar';
import type { PFKeyBarTone, PFKeyDef } from './PFKeyBar';
import { useSession } from '../hooks/useSession';
import { PfKeyAction } from '../types';

/**
 * :purpose: The per-screen frame values a routed page publishes into the shell.
 * :param transactionId: 4-char CICS transaction id shown after the transaction caption.
 * :param programName: Legacy program / screen name shown after the program caption.
 * :param title01: First title line (screen title), rendered YELLOW.
 * :param title02: Second title line, rendered YELLOW.
 * :param currentDate: Authoritative server ``MM/DD/YY`` date; omitted uses the header's
 *     own mount snapshot.
 * :param currentTime: Authoritative server 24-hour ``HH:MM:SS`` time; omitted uses the
 *     header's own mount snapshot.
 * :param captionStyle: Which mapset's header caption literals the screen uses; omitted
 *     renders the spelling shared by sixteen of the seventeen mapsets.
 * :param appId: Application id shown after ``AppID:`` on the sign-on frame.
 * :param sysId: System id shown after ``SysID:`` on the sign-on frame.
 * :param errorMessage: Line-23 error text, rendered RED with ``role="alert"``.
 * :param infoMessage: Informational text in the line-23 ``ERRMSG`` field itself, rendered
 *     GREEN with ``role="status"`` for the programs that move ``DFHGREEN`` into it.
 * :param infoFieldMessage: Text of the separate ``INFOMSG`` field the five detail mapsets
 *     declare ABOVE their ``ERRMSG`` region, rendered NEUTRAL. Publishing the key at all --
 *     even as an empty string -- reserves the row that field occupies.
 * :param pfKeys: Line-24 function keys and handlers declared for the screen.
 * :param onUnhandledKey: What the screen does with an attention identifier it does NOT
 *     declare — the ``WHEN OTHER`` arm of its program's ``EVALUATE EIBAID``. Twelve programs
 *     publish ``CCDA-MSG-INVALID-KEY`` on line 23 and place the cursor; the five account and
 *     card screens instead remap the key to ENTER (``IF PFK-INVALID SET CCARD-AID-ENTER TO
 *     TRUE``), so those publish their ENTER activator here. A screen that publishes nothing
 *     discards the key, which no program does.
 * :param pfKeyTone: Colour the screen's mapset declares on its line-24 legend field;
 *     omitted renders the YELLOW that fifteen of the seventeen mapsets declare.
 * :param noticeMessage: a non-failure condition -- the end of a browse, an empty page --
 *     reported on line 23 in the RED the mapset declares, but announced politely.
 * :param busy: ``true`` while the screen waits for the server, which marks the body region
 *     ``aria-busy`` and announces the wait in a polite live region.
 * :param locked: ``true`` while a write the screen issued is outstanding. Every declared
 *     function key stays legended but stops acting, reproducing the 3270 keyboard lock, so the
 *     screen cannot be left before the write it started reports back.
 * :param plainText: When set, the frame renders this single line of text and nothing else,
 *     reproducing ``EXEC CICS SEND TEXT ... ERASE``: the header, the body, the line-23 region
 *     and the line-24 legend are all cleared.
 */
export interface ScreenChrome {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
  currentDate?: string;
  currentTime?: string;
  captionStyle?: HeaderCaptionStyle;
  appId?: string;
  sysId?: string;
  errorMessage?: string;
  noticeMessage?: string;
  infoMessage?: string;
  infoFieldMessage?: string;
  messageReference?: string | null;
  pfKeys?: PFKeyDef[];
  onUnhandledKey?: () => void;
  pfKeyTone?: PFKeyBarTone;
  busy?: boolean;
  locked?: boolean;
  plainText?: string;
}

/**
 * :purpose: The publishing actions a routed page uses to drive the shared frame.
 * :param setChrome: Replace the frame values; an identity-stable reference safe to
 *     use as a ``useEffect`` dependency.
 * :param resetChrome: Return the frame to its published-nothing state; called by a
 *     page in its own effect cleanup.
 */
export interface ScreenChromeActions {
  setChrome: (next: ScreenChrome) => void;
  resetChrome: () => void;
}

/**
 * :purpose: Frame values of a screen that has published nothing yet. A single
 *     shared reference so the initial context value never changes identity.
 */
const EMPTY_CHROME: ScreenChrome = {};

/**
 * :purpose: Legend of a screen that declares no function keys. A single shared
 *     reference, so the legend passed to :func:`PFKeyBar` keeps a stable identity
 *     across renders.
 */
const NO_PF_KEYS: PFKeyDef[] = [];

/**
 * :purpose: Every value member of :class:`ScreenChrome` — the whole interface except
 *     ``pfKeys``, which is an array compared element by element, and
 *     ``onUnhandledKey``, which is a handler the frame records in a ref rather than a
 *     value it paints.
 */
const CHROME_VALUE_KEYS = [
  'transactionId',
  'programName',
  'title01',
  'title02',
  'currentDate',
  'currentTime',
  'captionStyle',
  'appId',
  'sysId',
  'errorMessage',
  'noticeMessage',
  'infoMessage',
  'infoFieldMessage',
  'messageReference',
  'pfKeyTone',
  'busy',
  'locked',
  'plainText',
] as const satisfies readonly (keyof ScreenChrome)[];

/**
 * :purpose: Whether two published legends would paint the same line 24.
 * :param current: the legend already published.
 * :param incoming: the legend being published.
 * :returns: ``true`` when both declare the same keys, in the same order, with the same label
 *     and the same enabled/darkened state.
 */
function isSameLegend(current: PFKeyDef[], incoming: PFKeyDef[]): boolean {
  if (current.length !== incoming.length) {
    return false;
  }
  return current.every((key, index) => {
    const other = incoming[index];
    return (
      other !== undefined &&
      key.action === other.action &&
      key.label === other.label &&
      key.enabled === other.enabled &&
      key.dark === other.dark
    );
  });
}

/**
 * :purpose: Whether a publication would paint the screen exactly as it is painted now, so
 *     the frame can keep the chrome it holds instead of replacing it.
 * :param current: the chrome already published.
 * :param incoming: the chrome being published, with its legend handlers already replaced
 *     by the frame's stable indirections.
 * :returns: ``true`` when every painted member and the whole legend are unchanged.
 * :note: This is what keeps a keystroke from repainting the frame. A page republishes from
 *     a layout effect whenever any dependency of that effect changes, and its dependencies
 *     include the ``useCallback`` handlers it publishes — whose identity turns over on every
 *     edit to a field. The published VALUES are almost always unchanged across those
 *     republications, and replacing the chrome anyway re-rendered the header, the message
 *     region and the key legend on every character the operator keyed. A 3270 ``SEND MAP`` of
 *     identical content leaves an identical screen, so declining the update is unobservable.
 */
function isSameChrome(current: ScreenChrome, incoming: ScreenChrome): boolean {
  if (!CHROME_VALUE_KEYS.every((key) => current[key] === incoming[key])) {
    return false;
  }
  return isSameLegend(current.pfKeys ?? NO_PF_KEYS, incoming.pfKeys ?? NO_PF_KEYS);
}

/**
 * :purpose: The 3270 input-inhibited indicator, shown in the Operator Information
 *     Area while a transaction is outstanding and the keyboard is locked. The OIA is
 *     the terminal's own status line below the 24 application rows, so this replaces no
 *     BMS field and is never part of a screen's own message line.
 */
const BUSY_ANNOUNCEMENT = 'X SYSTEM';

/**
 * :purpose: DOM id of the region holding rows 23 and 24, so the skip link can jump
 *     to the message line and the function keys.
 */
const SCREEN_STATUS_REGION_ID = 'screenStatusRegion';

/**
 * :purpose: The application name the document title always ends with, so a screen is
 *     identifiable in a tab, a history entry and a bookmark while the product remains
 *     recognisable. ``CardDemo`` is the application's own name in
 *     ``app/csd/CARDDEMO.CSD``.
 */
const APPLICATION_TITLE = 'CardDemo';

/**
 * :purpose: Build the document title for a screen from the pair its own header rows
 *     identify it by — the CICS transaction id and the program name. The two BMS title
 *     lines are deliberately not used: every mapset carries the same
 *     ``AWS Mainframe Modernization`` / ``CardDemo`` pair, so they identify the
 *     application rather than the screen.
 * :param transactionId: The published 4-character transaction id, if any.
 * :param programName: The published program name, if any.
 * :returns: ``"<tran> <program> - CardDemo"``, degrading to the parts that are present
 *     and to the application name alone before a screen has published anything.
 */
function buildDocumentTitle(transactionId?: string, programName?: string): string {
  const parts = [transactionId, programName]
    .map((part) => (part ?? '').trim())
    .filter((part) => part !== '');
  return parts.length === 0
    ? APPLICATION_TITLE
    : `${parts.join(' ')} - ${APPLICATION_TITLE}`;
}

/**
 * :purpose: Name the BMS mapset a screen is the migration of, so the absolute placement
 *     rules in ``bmsGrid.css`` can address one screen's regions without any page needing
 *     to carry coordinates of its own.
 * :param programName: The legacy program name a screen publishes on its header row
 *     (``COMEN01C``).
 * :returns: The seven-character mapset name (``COMEN01``), or ``undefined`` before a
 *     screen has published a program name or when the name is not an eight-character
 *     ``CO*C`` program.
 * :note: Seven characters is the mapset name's own width, which the COMMAREA fixes at
 *     ``CDEMO-LAST-MAP PIC X(7)``; the eighth character of a program name is the ``C``
 *     suffix every online program carries.
 */
function mapsetName(programName?: string): string | undefined {
  const name = (programName ?? '').trim().toUpperCase();
  return /^CO[A-Z0-9]{5}C$/.test(name) ? name.slice(0, 7) : undefined;
}

/**
 * :purpose: Inert actions supplied to a page rendered outside a :func:`Layout`, so
 *     chrome published by a detached page is discarded and the page still renders.
 */
const DETACHED_ACTIONS: ScreenChromeActions = {
  setChrome: () => undefined,
  resetChrome: () => undefined,
};

/**
 * :purpose: Carries the published frame values from the shell to the frame regions.
 *     Corresponds to the COMMAREA fields a CICS program moved into the shared
 *     symbolic map before ``SEND MAP``.
 */
const ScreenChromeContext = createContext<ScreenChrome>(EMPTY_CHROME);

/**
 * :purpose: Carries the publishing actions to the routed page. Separate from
 *     :data:`ScreenChromeContext` and identity-stable, so a page that publishes
 *     chrome is not re-rendered by its own publication.
 */
const ScreenChromeActionsContext =
  createContext<ScreenChromeActions>(DETACHED_ACTIONS);

/**
 * :purpose: Carries the number of attention identifiers this screen has sent, so the
 *     regions that a CICS ``SEND MAP`` rewrites unconditionally can do the same:
 *     re-announce an unchanged message, re-capture the header clock, and re-place the
 *     cursor. A page rendered outside a :func:`Layout` reads ``0``.
 */
const SendCountContext = createContext<number>(0);

/**
 * :purpose: Access the screen-chrome publishing actions from a routed page, so it
 *     can publish its transaction id, program name, titles, message, busy state and
 *     PF keys into the shared frame, and clear them again in its cleanup.
 * :returns: The :class:`ScreenChromeActions`; inert actions when the caller is
 *     rendered outside a :func:`Layout`.
 */
export function useScreenChrome(): ScreenChromeActions {
  return useContext(ScreenChromeActionsContext);
}

/**
 * :purpose: Read the frame values currently published, for a frame region rendered
 *     inside the shell.
 * :returns: The current :class:`ScreenChrome`.
 */
export function usePublishedChrome(): ScreenChrome {
  return useContext(ScreenChromeContext);
}

/**
 * :purpose: Read how many attention identifiers the screen has sent. Every accepted
 *     AID — a physical PF key or its legend button — advances the count by one, which
 *     is the SPA's equivalent of a CICS ``SEND MAP``: the map is repainted, the header
 *     date and time are re-captured (``POPULATE-HEADER-INFO``), the message region is
 *     written again even when the text is unchanged, and the cursor is re-placed on the
 *     screen's insert-cursor field.
 * :returns: The send count of the enclosing frame; ``0`` outside a :func:`Layout`.
 */
export function useSendCount(): number {
  return useContext(SendCountContext);
}

/**
 * :purpose: Props for :func:`Layout`.
 * :param children: The routed screen rendered in the body region (rows 4-22).
 */
export interface LayoutProps {
  children?: ReactNode;
}

/**
 * :purpose: One screen's frame: the header, the routed body, the line-23 message
 *     region and the line-24 key bar, together with the chrome the routed page
 *     publishes. Mounted fresh for each routed location by :func:`Layout`, so its
 *     state starts empty on every screen transfer.
 * :param children: The routed screen to render in the body region.
 * :returns: The rendered screen frame.
 */
function ScreenFrame({ children }: LayoutProps): ReactElement {
  const [chrome, setChromeState] = useState<ScreenChrome>(EMPTY_CHROME);
  // How many attention identifiers this screen has sent. A CICS program repaints the
  // whole map on every send, so the count is what lets the regions that must be
  // rewritten unconditionally tell one send from the next.
  const [sendCount, setSendCount] = useState<number>(0);
  const { isAuthenticated } = useSession();

  // The legend a page publishes, captured SYNCHRONOUSLY at publish time. A key is
  // dispatched through a stable indirection that reads this, so the handler that runs
  // is always the one from the newest publication even when the legend the key bar
  // holds is a render behind.
  const latestKeysRef = useRef<PFKeyDef[]>(NO_PF_KEYS);
  const dispatchersRef = useRef<Map<PfKeyAction, () => void>>(new Map());
  // The screen's answer to an AID it does not declare, captured the same way and for the
  // same reason: the key bar is handed one stable indirection, and the handler it reaches
  // is always the newest publication's.
  const latestUnhandledRef = useRef<(() => void) | undefined>(undefined);

  const stableDispatcher = useCallback((action: PfKeyAction): (() => void) => {
    const existing = dispatchersRef.current.get(action);
    if (existing !== undefined) {
      return existing;
    }
    const dispatch = (): void => {
      const declared = latestKeysRef.current.find((key) => key.action === action);
      declared?.onActivate();
    };
    dispatchersRef.current.set(action, dispatch);
    return dispatch;
  }, []);

  const setChrome = useCallback(
    (next: ScreenChrome): void => {
      const incoming = next.pfKeys ?? NO_PF_KEYS;
      // Recorded UNCONDITIONALLY, and before the comparison below. These two refs are
      // what a struck key resolves through, so the newest publication's handlers must
      // take effect even when nothing on the screen changes -- an unchanged legend
      // still has to act on the values the operator has since keyed.
      latestKeysRef.current = incoming;
      latestUnhandledRef.current = next.onUnhandledKey;
      const published: ScreenChrome = {
        ...next,
        pfKeys: incoming.map((key) => ({
          ...key,
          onActivate: stableDispatcher(key.action),
        })),
      };
      // Returning the state it already holds is how React is told a publication paints
      // nothing new: it compares the two references, finds them identical and schedules
      // no render at all.
      setChromeState((current) => (isSameChrome(current, published) ? current : published));
    },
    [stableDispatcher],
  );
  const resetChrome = useCallback((): void => {
    latestKeysRef.current = NO_PF_KEYS;
    latestUnhandledRef.current = undefined;
    setChromeState(EMPTY_CHROME);
  }, []);
  const actions = useMemo<ScreenChromeActions>(
    () => ({ setChrome, resetChrome }),
    [setChrome, resetChrome],
  );
  const handleAidDispatched = useCallback((): void => {
    setSendCount((previous) => previous + 1);
  }, []);
  // Identity-stable, so publishing chrome never rebinds the key bar's listener, and it
  // reads the newest policy at the moment the key is struck.
  const handleUnhandledAid = useCallback((): void => {
    latestUnhandledRef.current?.();
  }, []);

  const busy = chrome.busy === true;

  // A 3270 operator identifies a screen by the transaction id and title the map
  // carries, so the document title carries the same pair. Without it every route,
  // history entry and bookmark reads the bare application name.
  const documentTitle = buildDocumentTitle(chrome.transactionId, chrome.programName);
  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }
    document.title = documentTitle;
  }, [documentTitle]);

  // A 3270 locked the keyboard from the instant an AID was transmitted until the reply
  // arrived, so no second AID could be raised while that transaction was in flight. The
  // legends stay on screen exactly as they were — the terminal did not blank line 24 —
  // but none of them acts, which is what makes leaving a screen mid-transaction
  // unreachable rather than merely unlikely: a screen abandoned while its write was
  // outstanding committed that write with nothing left to report it.
  //
  // Only a screen that declares `locked` is held this way, and only its own writes
  // declare it. A read in flight paints nothing and commits nothing, so locking the
  // keyboard for a read would trap the operator behind a slow or hung GET for no gain.
  const locked = chrome.locked === true;
  const lockedKeys = useMemo<PFKeyDef[]>(
    () => (chrome.pfKeys ?? NO_PF_KEYS).map((key) => ({ ...key, enabled: false })),
    [chrome.pfKeys],
  );

  // ``EXEC CICS SEND TEXT ... ERASE`` clears the whole 24x80 device and writes one
  // line of text, so a screen that ends that way renders neither the header, nor
  // its body, nor the line-23 and line-24 regions.
  if (chrome.plainText !== undefined) {
    return (
      <ScreenChromeActionsContext.Provider value={actions}>
        <SendCountContext.Provider value={sendCount}>
          <ScreenChromeContext.Provider value={chrome}>
            <div className="screen" data-authenticated={String(isAuthenticated)}>
              <p className="screen__plainText" data-testid="screen-plain-text">
                {chrome.plainText}
              </p>
            </div>
          </ScreenChromeContext.Provider>
        </SendCountContext.Provider>
      </ScreenChromeActionsContext.Provider>
    );
  }

  return (
    <ScreenChromeActionsContext.Provider value={actions}>
      <SendCountContext.Provider value={sendCount}>
        <ScreenChromeContext.Provider value={chrome}>
          <div
            className="screen"
            data-authenticated={String(isAuthenticated)}
            data-map={mapsetName(chrome.programName)}
          >
            <a className="screen__skipLink" href={`#${SCREEN_STATUS_REGION_ID}`}>
              Skip to message line and function keys
            </a>
            <Header
              transactionId={chrome.transactionId}
              programName={chrome.programName}
              title01={chrome.title01}
              title02={chrome.title02}
              currentDate={chrome.currentDate}
              currentTime={chrome.currentTime}
              captionStyle={chrome.captionStyle}
              appId={chrome.appId}
              sysId={chrome.sysId}
              sendCount={sendCount}
            />
            <main className="screen__body" aria-busy={busy}>
              {children}
            </main>
            {/*
             * Rows 23 and 24 are the two lines a 3270 operator watches, so they are
             * reachable as a landmark of their own instead of sitting outside every
             * region, and the skip link above jumps straight to them.
             */}
            <footer
              className="screen__status"
              id={SCREEN_STATUS_REGION_ID}
              role="contentinfo"
              /*
               * The skip link's target must be able to hold focus, or activating the link
               * moves the browser's scroll position and its sequential starting point but
               * leaves `document.activeElement` on the body -- so the next Tab is the only
               * evidence the jump happened. A negative index is focusable
               * programmatically without joining the tab sequence, which is what a skip
               * target needs.
               */
              tabIndex={-1}
            >
              <ErrorBanner
                message={chrome.errorMessage}
                noticeMessage={chrome.noticeMessage}
                infoMessage={chrome.infoMessage}
                infoFieldMessage={chrome.infoFieldMessage}
                messageReference={chrome.messageReference}
                sendCount={sendCount}
              />
              <PFKeyBar
                keys={locked ? lockedKeys : (chrome.pfKeys ?? NO_PF_KEYS)}
                tone={chrome.pfKeyTone}
                inputInhibited={busy}
                onAidDispatched={handleAidDispatched}
                /*
                 * Published whenever the screen has an answer for an undeclared AID, and
                 * NOT withdrawn while a send is outstanding: the key bar refuses it there
                 * (the 3270 keyboard lock) but only AFTER claiming the key, which is what
                 * stops the browser acting on F5 or F12 in the middle of a write. Every
                 * screen that publishes `locked` publishes `busy` with it, so the bar's own
                 * inhibit covers both.
                 */
                onUnhandledAid={
                  chrome.onUnhandledKey === undefined ? undefined : handleUnhandledAid
                }
              />
            </footer>
            {/*
             * The 3270 Operator Information Area: the status line a terminal paints
             * BELOW the 24 application rows, never one of them. `X SYSTEM` is its
             * input-inhibited indicator, shown for exactly as long as the keyboard is
             * locked, and it is also the polite announcement of the wait. The row is
             * always reserved so no screen's geometry changes when a request starts.
             */}
            <p
              className="screen__oia"
              role="status"
              aria-live="polite"
              data-testid="screen-busy"
            >
              {busy ? BUSY_ANNOUNCEMENT : ''}
            </p>
          </div>
        </ScreenChromeContext.Provider>
      </SendCountContext.Provider>
    </ScreenChromeActionsContext.Provider>
  );
}

/**
 * :purpose: The shared screen shell. Mounts one :func:`ScreenFrame` per routed
 *     location so every screen transfer starts from an empty frame, and exposes the
 *     current authentication state on the frame so route guards and styling can
 *     react to it.
 * :param children: The routed screen to render in the body region.
 * :returns: The rendered screen frame.
 */
export default function Layout({ children }: LayoutProps): ReactElement {
  const location = useLocation();
  return <ScreenFrame key={location.pathname}>{children}</ScreenFrame>;
}
