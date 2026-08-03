/**
 * Layout
 * ======
 *
 * :purpose: The fixed 24x80 terminal shell that frames every routed screen. It
 *     renders the shared :func:`Header`, the routed page content, the line-23
 *     :func:`ErrorBanner`, and the line-24 :func:`PFKeyBar`, and exposes a
 *     :func:`useScreenChrome` context so each page can publish its transaction id,
 *     program name, titles, message, and function keys. Replaces the BMS mapset
 *     frame plus COMMAREA-carried screen context (``app/bms/COSGN00.bms``).
 */
import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import Header from './Header';
import ErrorBanner from './ErrorBanner';
import PFKeyBar from './PFKeyBar';
import type { PFKeyDef } from './PFKeyBar';
import { useSession } from '../hooks';

/**
 * :purpose: The per-screen chrome a page publishes into the shell.
 * :param transactionId: Transaction id shown in the header.
 * :param programName: Program / screen name shown in the header.
 * :param title01: First (screen) title line.
 * :param title02: Second title line.
 * :param errorMessage: Line-23 error message.
 * :param infoMessage: Line-23 informational fallback.
 * :param pfKeys: Line-24 function keys for the screen.
 */
export interface ScreenChrome {
  transactionId: string;
  programName: string;
  title01: string;
  title02: string;
  errorMessage: string;
  infoMessage: string;
  pfKeys: PFKeyDef[];
}

const DEFAULT_CHROME: ScreenChrome = {
  transactionId: '',
  programName: '',
  title01: '',
  title02: '',
  errorMessage: '',
  infoMessage: '',
  pfKeys: [],
};

/**
 * :purpose: The value exposed by the screen-chrome context.
 * :param chrome: The current chrome state.
 * :param setChrome: Merge a partial chrome update into the current state.
 * :param resetChrome: Restore the empty default chrome.
 */
export interface ScreenChromeContextValue {
  chrome: ScreenChrome;
  setChrome: (partial: Partial<ScreenChrome>) => void;
  resetChrome: () => void;
}

const ScreenChromeContext = createContext<ScreenChromeContextValue>({
  chrome: DEFAULT_CHROME,
  setChrome: () => undefined,
  resetChrome: () => undefined,
});

/**
 * :purpose: Access the shell chrome controls from a page component. Returns a
 *     safe no-op default when called outside a :func:`Layout` provider.
 * :returns: The screen-chrome context value.
 */
export function useScreenChrome(): ScreenChromeContextValue {
  return useContext(ScreenChromeContext);
}

/**
 * :purpose: Props for :func:`Layout`.
 * :param children: The routed page content rendered between header and key bar.
 */
export interface LayoutProps {
  children: ReactNode;
}

/**
 * :purpose: Wrap all routes in the terminal shell and provide screen chrome.
 * :param children: The routed page content.
 * :returns: The rendered shell.
 */
export default function Layout({ children }: LayoutProps): ReactElement {
  const [chrome, setChromeState] = useState<ScreenChrome>(DEFAULT_CHROME);
  const { isAuthenticated } = useSession();

  const setChrome = useCallback((partial: Partial<ScreenChrome>): void => {
    setChromeState((prev) => ({ ...prev, ...partial }));
  }, []);

  const resetChrome = useCallback((): void => {
    setChromeState(DEFAULT_CHROME);
  }, []);

  const contextValue = useMemo<ScreenChromeContextValue>(
    () => ({ chrome, setChrome, resetChrome }),
    [chrome, setChrome, resetChrome],
  );

  return (
    <ScreenChromeContext.Provider value={contextValue}>
      <div className="screen" data-authenticated={isAuthenticated}>
        <Header
          transactionId={chrome.transactionId}
          programName={chrome.programName}
          title01={chrome.title01}
          title02={chrome.title02}
        />
        <main className="screenBody">{children}</main>
        <ErrorBanner message={chrome.errorMessage} infoMessage={chrome.infoMessage} />
        <PFKeyBar keys={chrome.pfKeys} />
      </div>
    </ScreenChromeContext.Provider>
  );
}
