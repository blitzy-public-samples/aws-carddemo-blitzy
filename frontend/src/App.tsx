/**
 * App
 * ===
 *
 * :purpose: The routed application: the seventeen migrated BMS screens mounted on
 *     their routes inside the shared 24x80 :func:`Layout` frame, with the CICS
 *     transaction-security gate re-expressed as two route guards. It replaces the
 *     legacy program-to-program ``XCTL`` transfer graph — ``COSGN00C`` handing to
 *     ``COMEN01C`` or ``COADM01C``, each of those handing to the screen its selected
 *     option names — with client-side navigation over the same transitions.
 * :output: The named default ``App`` component.
 * :note: The guards mirror, and never replace, the gateway's own authorization: the
 *     api-gateway gates ``/admin/**`` and ``/users/**`` to ``ROLE_ADMIN`` and every
 *     other business route to a signed-on role, so a bypassed guard changes what is
 *     drawn and never what a caller is allowed to read or write.
 * :note: A guard must not answer before the server has: the session store starts
 *     signed out and resolves the identity through ``GET /session``, so until
 *     ``isSessionResolved`` is true a deep link that carries a valid session cookie
 *     waits instead of being bounced to the sign-on screen.
 * :note: The entry route and the catch-all resolve by ROLE rather than to a fixed
 *     screen, re-expressing the ``COSGN00C`` sign-on transfer and the 3270's absence of
 *     a not-found state. Both hops use ``replace``, so neither leaves a history entry.
 */
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import { Navigate, Outlet, Route, Routes } from 'react-router';
import Layout from './components/Layout';
import { SESSION_ENDED_MESSAGE } from './api/messages';
import { useSession } from './hooks';
import type { Role } from './types';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from './types';
import SignonPage from './pages/SignonPage';
import MainMenuPage from './pages/MainMenuPage';
import AdminMenuPage from './pages/AdminMenuPage';
import AccountViewPage from './pages/AccountViewPage';
import AccountUpdatePage from './pages/AccountUpdatePage';
import CardListPage from './pages/CardListPage';
import CardDetailPage from './pages/CardDetailPage';
import CardUpdatePage from './pages/CardUpdatePage';
import TranListPage from './pages/TranListPage';
import TranViewPage from './pages/TranViewPage';
import TranAddPage from './pages/TranAddPage';
import BillPayPage from './pages/BillPayPage';
import ReportPage from './pages/ReportPage';
import UserListPage from './pages/UserListPage';
import UserAddPage from './pages/UserAddPage';
import UserUpdatePage from './pages/UserUpdatePage';
import UserDeletePage from './pages/UserDeletePage';

/** Route of the sign-on screen (``COSGN00`` / ``CC00``). */
const SIGNON_ROUTE = '/signon';

/** Route of the main menu (``COMEN01`` / ``CM00``). */
const MAIN_MENU_ROUTE = '/menu';

/** Route of the administrator menu (``COADM01`` / ``CA00``). */
const ADMIN_MENU_ROUTE = '/admin';

/** Entry route of the application, resolved by role rather than by a fixed screen. */
const ENTRY_ROUTE = '/';

/*
 * The line-23 text a guard carries to the sign-on screen when it returns a caller whose
 * session the server no longer honours — it expired, it was revoked when the caller's own
 * privileges changed, or the caller deleted their own record. Without it the operator is
 * put back on the sign-on screen with nothing said, which is indistinguishable from
 * having pressed F3. The literal itself is shared with every other producer of this
 * condition (``api/messages.ts``), so all of them report it in one wording.
 */

/**
 * :purpose: Line-23 text carried to the main menu when a standard user reaches an
 *     administrator-only screen. Verbatim ``COMEN01C`` L140, the literal that program
 *     publishes for exactly this refusal, including its trailing spaces.
 */
const NO_ACCESS_MESSAGE = 'No access - Admin Only option... ';

/**
 * :purpose: Text announced while the server identity probe is still outstanding, so a
 *     screen reader is told the frame is waiting rather than being left silent. The
 *     shared stylesheet hides it, so no screen gains visible output.
 */
const RESOLVING_ANNOUNCEMENT = 'Checking sign-on';

/**
 * :purpose: The shared frame every screen is drawn inside; the routed screen is
 *     rendered in its body region.
 * :returns: The frame wrapping the matched child route.
 */
function AppFrame(): ReactElement {
  return (
    <Layout>
      <Outlet />
    </Layout>
  );
}

/**
 * :purpose: Announce that the frame is waiting for the server to resolve the session.
 * :returns: The hidden live region shown in place of a guarded screen.
 */
function ResolvingSession(): ReactElement {
  return (
    <p className="screen__busy" role="status" data-testid="session-resolving">
      {RESOLVING_ANNOUNCEMENT}
    </p>
  );
}

/**
 * :purpose: Resolve the screen a role starts on, re-expressing the ``COSGN00C`` sign-on
 *     transfer: ``XCTL PROGRAM('COADM01C')`` for user type ``'A'`` and
 *     ``XCTL PROGRAM('COMEN01C')`` otherwise, with the sign-on screen itself for a caller
 *     the server reports as signed out.
 * :param role: the session role (``CDEMO-USER-TYPE``), or ``null`` when signed out.
 * :returns: The route of that role's first screen.
 */
function homeRouteForRole(role: Role | null): string {
  if (role === CDEMO_USRTYP_ADMIN) {
    return ADMIN_MENU_ROUTE;
  }
  return role === CDEMO_USRTYP_USER ? MAIN_MENU_ROUTE : SIGNON_ROUTE;
}

/**
 * :purpose: Send the entry route to the screen the caller's role starts on, so a reload
 *     or a bookmark of the application root lands where ``COSGN00C`` would have
 *     transferred, instead of re-presenting the sign-on screen to a signed-on operator.
 * :returns: A redirect to the role's first screen, or the waiting announcement while the
 *     session is still being resolved.
 */
function HomeRedirect(): ReactElement {
  const { role, isSessionResolved } = useSession();
  if (!isSessionResolved) {
    return <ResolvingSession />;
  }
  return <Navigate to={homeRouteForRole(role)} replace />;
}

/**
 * :purpose: Admit a signed-on caller to the screens every role reached, and send a
 *     caller with no session to the sign-on screen — the client-side counterpart of
 *     the CICS sign-on requirement each transaction carried.
 * :returns: The matched child route, the waiting announcement while the session is
 *     still being resolved, or a redirect to the sign-on screen.
 */
function RequireAuth(): ReactElement {
  const { isAuthenticated, isSessionResolved } = useSession();
  if (!isSessionResolved) {
    return <ResolvingSession />;
  }
  if (isAuthenticated) {
    return <Outlet />;
  }
  // The refusal carries its reason, so the sign-on screen can say why it is being
  // presented rather than appearing for no stated cause.
  return (
    <Navigate
      to={SIGNON_ROUTE}
      replace
      state={{ screenMessage: SESSION_ENDED_MESSAGE }}
    />
  );
}

/**
 * :purpose: Admit only ``CDEMO-USRTYP-ADMIN`` (``'A'``) to the administration
 *     screens, mirroring the transaction security that kept ``CA00`` and the
 *     ``COUSR*`` transactions away from a standard user, and returning a standard
 *     user to the menu that does list the screens they may reach.
 * :returns: The matched child route, the waiting announcement while the session is
 *     still being resolved, or a redirect to the main menu.
 */
function RequireAdmin(): ReactElement {
  const { isAdmin, isAuthenticated, isSessionResolved } = useSession();
  if (!isSessionResolved) {
    return <ResolvingSession />;
  }
  if (!isAuthenticated) {
    return (
      <Navigate
        to={SIGNON_ROUTE}
        replace
        state={{ screenMessage: SESSION_ENDED_MESSAGE }}
      />
    );
  }
  if (isAdmin) {
    return <Outlet />;
  }
  // ``COMEN01C`` L137-142 answers an administrator-only option chosen by a standard
  // user by re-sending the menu with its own refusal literal on line 23; the same
  // literal travels with the guard's bounce so the refusal is never silent.
  return (
    <Navigate
      to={MAIN_MENU_ROUTE}
      replace
      state={{ screenMessage: NO_ACCESS_MESSAGE }}
    />
  );
}

/**
 * :purpose: Re-ask the server who the caller is whenever the document is restored
 *     rather than loaded — a back-forward-cache restore, or a tab becoming visible
 *     again. A restored document is reinstated with its rendered output intact and
 *     issues no network request of its own, so a screen drawn before a sign-off would
 *     otherwise keep showing account numbers, card numbers and customer details after
 *     the session that authorized them had been revoked.
 * :note: ``revalidate`` returns the session store to its pre-probe state before it
 *     asks, so the guards render the waiting announcement and the protected screen is
 *     unmounted for the whole round trip instead of staying on display until the
 *     answer arrives.
 */
function useRestoreRevalidation(): void {
  const { revalidate } = useSession();
  useEffect(() => {
    function onPageShow(event: PageTransitionEvent): void {
      if (event.persisted) {
        void revalidate();
      }
    }
    function onVisibilityChange(): void {
      if (document.visibilityState === 'visible') {
        void revalidate();
      }
    }
    window.addEventListener('pageshow', onPageShow);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('pageshow', onPageShow);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [revalidate]);
}

/**
 * :purpose: Mount every migrated screen on its route inside the shared frame.
 * :returns: The rendered route tree.
 */
export default function App(): ReactElement {
  useRestoreRevalidation();
  return (
    <Routes>
      <Route element={<AppFrame />}>
        {/* The entry route names no mapset; it resolves by role (COSGN00C transfer). */}
        <Route path={ENTRY_ROUTE} element={<HomeRedirect />} />

        {/* COSGN00 / CC00 — the only screen reachable without a session. */}
        <Route path={SIGNON_ROUTE} element={<SignonPage />} />

        <Route element={<RequireAuth />}>
          {/* COMEN01 / CM00 */}
          <Route path={MAIN_MENU_ROUTE} element={<MainMenuPage />} />

          {/* COACTVW / CAVW — the account key is keyed on the screen or routed in. */}
          <Route path="/accounts" element={<AccountViewPage />} />
          {/* COACTUP / CAUP */}
          <Route path="/accounts/update" element={<AccountUpdatePage />} />
          <Route path="/accounts/:accountId" element={<AccountViewPage />} />
          <Route
            path="/accounts/:accountId/update"
            element={<AccountUpdatePage />}
          />

          {/* COCRDLI / CCLI */}
          <Route path="/cards" element={<CardListPage />} />
          {/* COCRDSL / CCDL - the selected card travels in the router location
              state, never in a path segment, so no card number reaches the address
              bar or the session history. */}
          <Route path="/cards/view" element={<CardDetailPage />} />
          {/* COCRDUP / CCUP - same hand-over as COCRDSL. */}
          <Route path="/cards/update" element={<CardUpdatePage />} />

          {/* COTRN00 / CT00 */}
          <Route path="/transactions" element={<TranListPage />} />
          {/* COTRN02 / CT02 */}
          <Route path="/transactions/add" element={<TranAddPage />} />
          {/* COTRN01 / CT01 */}
          <Route path="/transactions/view" element={<TranViewPage />} />
          <Route path="/transactions/:transactionId" element={<TranViewPage />} />

          {/* COBIL00 / CB00 */}
          <Route path="/billpay" element={<BillPayPage />} />
          {/* CORPT00 / CR00 */}
          <Route path="/reports" element={<ReportPage />} />

          <Route element={<RequireAdmin />}>
            {/* COADM01 / CA00 */}
            <Route path="/admin" element={<AdminMenuPage />} />
            {/* COUSR00 / CU00 */}
            <Route path="/users" element={<UserListPage />} />
            {/* COUSR01 / CU01 */}
            <Route path="/users/add" element={<UserAddPage />} />
            {/* COUSR02 / CU02 */}
            <Route path="/users/update" element={<UserUpdatePage />} />
            {/* COUSR03 / CU03 */}
            <Route path="/users/delete" element={<UserDeletePage />} />
          </Route>
        </Route>

        {/* No mapset answers any other path, and the 3270 had no not-found state: an
            unrecognised transaction returned the operator to a menu. The catch-all
            therefore re-enters at the role-resolved entry route. Both hops replace, so
            no intermediate entry is left in history. */}
        <Route path="*" element={<Navigate to={ENTRY_ROUTE} replace />} />
      </Route>
    </Routes>
  );
}
