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
 */
import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { useLocation } from 'react-router';
import Header from './Header';
import type { HeaderCaptionStyle } from './Header';
import ErrorBanner from './ErrorBanner';
import PFKeyBar from './PFKeyBar';
import type { PFKeyDef } from './PFKeyBar';
import { useSession } from '../hooks/useSession';

/**
 * :purpose: The per-screen frame values a routed page publishes into the shell.
 * :param transactionId: 4-char CICS transaction id shown after the transaction
 *     caption.
 * :param programName: Legacy program / screen name shown after the program caption.
 * :param title01: First title line (screen title), rendered YELLOW.
 * :param title02: Second title line, rendered YELLOW.
 * :param currentDate: Authoritative server ``MM/DD/YY`` date; omitted uses the
 *     header's own mount snapshot.
 * :param currentTime: Authoritative server 24-hour ``HH:MM:SS`` time; omitted
 *     uses the header's own mount snapshot.
 * :param captionStyle: Which mapset's header caption literals the screen uses;
 *     omitted renders the spelling shared by sixteen of the seventeen mapsets.
 * :param appId: Application id shown after ``AppID:`` on the sign-on frame.
 * :param sysId: System id shown after ``SysID:`` on the sign-on frame.
 * :param errorMessage: Line-23 error text, rendered RED with ``role="alert"``.
 * :param infoMessage: Line-23 informational fallback, rendered ``role="status"``.
 * :param pfKeys: Line-24 function keys and handlers declared for the screen.
 * :param busy: ``true`` while the screen waits for the server, which marks the body
 *     region ``aria-busy`` and announces the wait in a polite live region.
 * :param plainText: When set, the frame renders this single line of text and nothing
 *     else, reproducing ``EXEC CICS SEND TEXT ... ERASE``: the header, the body, the
 *     line-23 region and the line-24 legend are all cleared.
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
  infoMessage?: string;
  pfKeys?: PFKeyDef[];
  busy?: boolean;
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
 * :purpose: Text announced in the body region's live area while a screen waits for
 *     the server. Announcement only: it replaces no BMS field and is never part of
 *     a screen's own message line.
 */
const BUSY_ANNOUNCEMENT = 'Working';

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
  const { isAuthenticated } = useSession();

  const setChrome = useCallback((next: ScreenChrome): void => {
    setChromeState(next);
  }, []);
  const resetChrome = useCallback((): void => {
    setChromeState(EMPTY_CHROME);
  }, []);
  const actions = useMemo<ScreenChromeActions>(
    () => ({ setChrome, resetChrome }),
    [setChrome, resetChrome],
  );

  const busy = chrome.busy === true;

  // ``EXEC CICS SEND TEXT ... ERASE`` clears the whole 24x80 device and writes one
  // line of text, so a screen that ends that way renders neither the header, nor
  // its body, nor the line-23 and line-24 regions.
  if (chrome.plainText !== undefined) {
    return (
      <ScreenChromeActionsContext.Provider value={actions}>
        <ScreenChromeContext.Provider value={chrome}>
          <div className="screen" data-authenticated={String(isAuthenticated)}>
            <p className="screen__plainText" data-testid="screen-plain-text">
              {chrome.plainText}
            </p>
          </div>
        </ScreenChromeContext.Provider>
      </ScreenChromeActionsContext.Provider>
    );
  }

  return (
    <ScreenChromeActionsContext.Provider value={actions}>
      <ScreenChromeContext.Provider value={chrome}>
        <div className="screen" data-authenticated={String(isAuthenticated)}>
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
          />
          <main className="screen__body" aria-busy={busy}>
            {children}
          </main>
          {/*
           * Announcement-only live region: it is outside the aria-busy body so the
           * announcement is not suppressed, and it is visually hidden so the 24-row
           * frame geometry and the screen's visible output are unchanged.
           */}
          <p className="screen__busy" role="status" data-testid="screen-busy">
            {busy ? BUSY_ANNOUNCEMENT : ''}
          </p>
          <ErrorBanner
            message={chrome.errorMessage}
            infoMessage={chrome.infoMessage}
          />
          <PFKeyBar keys={chrome.pfKeys ?? NO_PF_KEYS} />
        </div>
      </ScreenChromeContext.Provider>
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
