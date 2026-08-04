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
 */
import { createContext, useContext, useMemo, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import Header from './Header';
import ErrorBanner from './ErrorBanner';
import PFKeyBar from './PFKeyBar';
import type { PFKeyDef } from './PFKeyBar';
import { useSession } from '../hooks/useSession';

/**
 * :purpose: The per-screen frame values a routed page publishes into the shell.
 * :param transactionId: 4-char CICS transaction id shown after ``Tran :``.
 * :param programName: Legacy program / screen name shown after ``Prog :``.
 * :param title01: First title line (screen title), rendered YELLOW.
 * :param title02: Second title line, rendered YELLOW.
 * :param currentDate: Authoritative server ``MM/DD/YY`` date; omitted uses the
 *     header's own mount snapshot.
 * :param currentTime: Authoritative server 24-hour ``HH:MM:SS`` time; omitted
 *     uses the header's own mount snapshot.
 * :param appId: Application id shown after ``AppID:``; its presence renders the
 *     optional third header row.
 * :param sysId: System id shown after ``SysID:``; its presence renders the
 *     optional third header row.
 * :param errorMessage: Line-23 error text, rendered RED with ``role="alert"``.
 * :param infoMessage: Line-23 informational fallback, rendered ``role="status"``.
 * :param pfKeys: Line-24 function keys and handlers enabled for the screen.
 */
export interface ScreenChrome {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
  currentDate?: string;
  currentTime?: string;
  appId?: string;
  sysId?: string;
  errorMessage?: string;
  infoMessage?: string;
  pfKeys?: PFKeyDef[];
}

/**
 * :purpose: Value published by the :func:`Layout` screen-chrome context.
 * :param chrome: The frame values currently displayed by the shell.
 * :param setChrome: Replace the frame values; a stable reference safe to use as a
 *     ``useEffect`` dependency.
 */
export interface ScreenChromeContextValue {
  chrome: ScreenChrome;
  setChrome: (next: ScreenChrome) => void;
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
 * :purpose: Inert context supplied to a page rendered outside a :func:`Layout`.
 *     Its :func:`setChrome` is a no-op, so chrome published by a detached page is
 *     discarded and the page still renders.
 */
const DETACHED_CONTEXT: ScreenChromeContextValue = {
  chrome: EMPTY_CHROME,
  setChrome: () => undefined,
};

/**
 * :purpose: Carries the screen-chrome value from the shell down to the routed
 *     page. Corresponds to the COMMAREA fields a CICS program moved into the
 *     shared symbolic map before ``SEND MAP``.
 */
const ScreenChromeContext = createContext<ScreenChromeContextValue>(DETACHED_CONTEXT);

/**
 * :purpose: Access the screen-chrome context from a routed page so it can publish
 *     its transaction id, program name, titles, message, and PF keys into the
 *     shared frame.
 * :returns: The current :class:`ScreenChromeContextValue`; an inert value when
 *     the caller is rendered outside a :func:`Layout`.
 */
export function useScreenChrome(): ScreenChromeContextValue {
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
 * :purpose: The shared screen shell. Renders the header, the routed screen body,
 *     the line-23 message region, and the line-24 function-key bar, and exposes
 *     the current authentication state on the frame so route guards and styling
 *     can react to it.
 * :param children: The routed screen to render in the body region.
 * :returns: The rendered screen frame.
 */
export default function Layout({ children }: LayoutProps): ReactElement {
  // Published directly: the React state setter is identity-stable across
  // renders, so a page may list it as a useEffect dependency.
  const [chrome, setChrome] = useState<ScreenChrome>(EMPTY_CHROME);
  const { isAuthenticated } = useSession();
  const contextValue = useMemo<ScreenChromeContextValue>(
    () => ({ chrome, setChrome }),
    [chrome],
  );

  return (
    <ScreenChromeContext.Provider value={contextValue}>
      <div className="screen" data-authenticated={String(isAuthenticated)}>
        <Header
          transactionId={chrome.transactionId}
          programName={chrome.programName}
          title01={chrome.title01}
          title02={chrome.title02}
          currentDate={chrome.currentDate}
          currentTime={chrome.currentTime}
          appId={chrome.appId}
          sysId={chrome.sysId}
        />
        <main className="screen__body">{children}</main>
        <ErrorBanner message={chrome.errorMessage} infoMessage={chrome.infoMessage} />
        <PFKeyBar keys={chrome.pfKeys ?? NO_PF_KEYS} />
      </div>
    </ScreenChromeContext.Provider>
  );
}
