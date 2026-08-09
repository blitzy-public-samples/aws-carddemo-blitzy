/**
 * :module: ``frontend/src/pages/UserDeletePage.tsx``
 * :purpose: Administrator delete-user screen — the 1:1 replacement for the BMS
 *   mapset ``app/bms/COUSR03.bms`` (map ``COUSR3A``, CICS transaction ``CU03``,
 *   program ``app/cbl/COUSR03C.cbl``). The operator keys an eight-character user
 *   id, ENTER reads the record and displays it read-only, and a deliberate F5
 *   keypress deletes it. Field labels, ``L=`` / PIC widths, the line-24
 *   function-key legend, and every line-23 message are reproduced verbatim.
 * :output: The default export :func:`UserDeletePage`, the routed component for
 *   ``/users/delete`` (administrator-only).
 * :note: Renders the screen BODY only. The transaction id, program name, title
 *   lines, line-23 message, and line-24 key legend are published to the shared
 *   terminal shell through ``useScreenChrome``; this module never renders
 *   ``Header``, ``ErrorBanner``, or ``PFKeyBar`` itself.
 */

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { UserDto } from '../types';
import { ApiError, deleteUser, getUser } from '../api';
import OutputField from '../components/OutputField';
import {
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
  useSelfRevocationExit,
} from '../hooks';

/** CICS transaction id of the delete-user screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CU03';

/** Legacy program name shown on header line 2 (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COUSR03C';

/** Route F3 returns to, replacing the legacy ``XCTL`` to ``COADM01C``. */
const ADMIN_MENU_ROUTE = '/admin';

/** BMS line-4 screen heading (``INITIAL='Delete User'``). */
const HEADING = 'Delete User';

/** BMS line-6 caption of the enterable key field. */
const LABEL_USER_ID = 'Enter User ID:';

/** BMS line-11 caption of the ``FNAME`` display field. */
const LABEL_FIRST_NAME = 'First Name:';

/** BMS line-13 caption of the ``LNAME`` display field. */
const LABEL_LAST_NAME = 'Last Name:';

/** BMS line-15 caption of the ``USRTYPE`` display field; the trailing space is verbatim. */
const LABEL_USER_TYPE = 'User Type: ';

/** BMS line-15 role legend rendered after ``USRTYPE``. */
const USER_TYPE_HINT = '(A=Admin, U=User)';

/** Width of ``USRIDIN`` (``PIC X(8)``). */
const USER_ID_LENGTH = 8;

/** Width of ``FNAMEI`` and ``LNAMEI`` (``PIC X(20)``). */
const NAME_LENGTH = 20;

/** Width of ``USRTYPEI`` (``PIC X(1)``). */
const USER_TYPE_LENGTH = 1;

/** Width of the decorative BMS line-8 rule (70 asterisks). */
const SEPARATOR_LENGTH = 70;

/** Line-23 text when no user id was keyed (``PROCESS-ENTER-KEY`` / ``DELETE-USER-INFO``). */
const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';

/** Line-23 text for ``DFHRESP(NOTFND)`` on the ``USRSEC`` read or delete. */
const MSG_USER_NOT_FOUND = 'User ID NOT found...';

/** Line-23 text for the catch-all branch of ``READ-USER-SEC-FILE``. */
const MSG_UNABLE_LOOKUP = 'Unable to lookup User...';

/** Line-23 text for the catch-all branch of ``DELETE-USER-SEC-FILE``. */
const MSG_UNABLE_UPDATE = 'Unable to Update User...';

/** Line-23 confirmation prompt shown once the record has been read for display. */
const MSG_PRESS_PF5_DELETE = 'Press PF5 key to delete this user ...';

/** Leading fragment of the delete confirmation (``STRING 'User '``). */
const MSG_USER_PREFIX = 'User ';

/** Trailing fragment of the delete confirmation (``' has been deleted ...'``). */
const MSG_DELETED_SUFFIX = ' has been deleted ...';

/** BMS line-24 legend caption for ENTER. */
const PFKEY_LABEL_ENTER = 'ENTER=Fetch';

/** BMS line-24 legend caption for F3. */
const PFKEY_LABEL_PF3 = 'F3=Back';

/** BMS line-24 legend caption for F4. */
const PFKEY_LABEL_PF4 = 'F4=Clear';

/** BMS line-24 legend caption for F5. */
const PFKEY_LABEL_PF5 = 'F5=Delete';

/*
 * ``COUSR03C`` evaluates ``DFHPF12`` and returns to the administrator menu without
 * deleting, but ``COUSR03.bms`` paints only four keys on line 24. The AID is claimed
 * so the key behaves as the program specifies, while the caption stays off the
 * legend: the mapset, not the program, decides what line 24 shows.
 */
const PFKEY_LABEL_PF12 = 'F12=Cancel';

/* ``COUSR03.bms`` declares no button anywhere in the screen body: ENTER fetches and F5
   deletes, both from line 24. Captions for an in-body Fetch and Delete control are
   therefore not declared here -- rendering them put an irreversible action in the tab
   order, reachable by a single click, and gave it the same weight as the harmless
   lookup. */

/** HTTP status the backend returns for ``RecordNotFoundException``. */
const HTTP_NOT_FOUND = 404;

/** Query-parameter name accepted as an alternative to router state. */
const USER_ID_QUERY_PARAM = 'userId';

/**
 * :purpose: Navigation state accepted from the user-list screen ``COUSR00`` when a
 *   row is marked ``D``, mirroring COMMAREA ``CDEMO-CU03-USR-SELECTED``.
 * :field userId: user id to pre-fill and read on entry.
 */
interface UserDeleteNavigationState {
  userId?: string;
}

/**
 * :purpose: Outcome of a read-for-display attempt: either the record to show or the
 *   verbatim line-23 message explaining why it cannot be shown.
 */
type FetchOutcome = { user: UserDto } | { message: string };

/**
 * :purpose: Outcome of a delete attempt: either confirmation that the record is
 *   gone or the verbatim line-23 message explaining the failure.
 */
type DeleteOutcome = { deleted: true } | { message: string };

/**
 * :purpose: Map a failed ``USRSEC`` read to its verbatim line-23 message.
 * :param error: the value the api call rejected with.
 * :returns: ``'User ID NOT found...'`` for a 404, otherwise the backend
 *   ``ApiErrorResponse.message``, falling back to ``'Unable to lookup User...'``.
 */
function resolveLookupMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === HTTP_NOT_FOUND) {
      return MSG_USER_NOT_FOUND;
    }
    if (error.message.length > 0) {
      return error.message;
    }
  }
  return MSG_UNABLE_LOOKUP;
}

/**
 * :purpose: Map a failed ``USRSEC`` delete to its verbatim line-23 message.
 * :param error: the value the api call rejected with.
 * :returns: ``'User ID NOT found...'`` for a 404, otherwise the backend
 *   ``ApiErrorResponse.message``, falling back to ``'Unable to Update User...'``.
 */
function resolveDeleteMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === HTTP_NOT_FOUND) {
      return MSG_USER_NOT_FOUND;
    }
    if (error.message.length > 0) {
      return error.message;
    }
  }
  return MSG_UNABLE_UPDATE;
}

/**
 * :purpose: Read one security user for read-only display, never rejecting so the
 *   caller can branch on the outcome synchronously.
 * :param userId: the trimmed eight-character user id (``SEC-USR-ID``).
 * :returns: the record, or the line-23 message explaining the miss.
 */
async function fetchUserOutcome(userId: string): Promise<FetchOutcome> {
  try {
    return { user: await getUser(userId) };
  } catch (error) {
    return { message: resolveLookupMessage(error) };
  }
}

/**
 * :purpose: Delete one security user, never rejecting so the caller can branch on
 *   the outcome synchronously.
 * :param userId: the trimmed eight-character user id (``SEC-USR-ID``).
 * :returns: confirmation, or the line-23 message explaining the failure.
 */
async function deleteUserOutcome(userId: string): Promise<DeleteOutcome> {
  try {
    await deleteUser(userId);
    return { deleted: true };
  } catch (error) {
    return { message: resolveDeleteMessage(error) };
  }
}

/**
 * :purpose: Resolve the user id supplied on entry, accepted either as router state
 *   or as a ``userId`` query parameter.
 * :param state: the router location state, of unknown shape.
 * :param search: the location query string.
 * :returns: the trimmed id, or an empty string when none was supplied.
 */
function resolveSelectedUserId(state: unknown, search: string): string {
  const fromState = (state as UserDeleteNavigationState | null | undefined)?.userId;
  if (typeof fromState === 'string' && fromState.trim().length > 0) {
    return fromState.trim();
  }
  const fromQuery = new URLSearchParams(search).get(USER_ID_QUERY_PARAM);
  return fromQuery === null ? '' : fromQuery.trim();
}

/**
 * :purpose: The administrator delete-user screen (``COUSR03`` / ``CU03``). Keys a
 *   user id, reads the record for read-only display, and deletes it on a
 *   deliberate F5 / Delete action.
 * :returns: The rendered screen body.
 */
export default function UserDeletePage(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const { setChrome } = useScreenChrome();

  const selectedUserId = resolveSelectedUserId(location.state, location.search);

  const [userId, setUserId] = useState<string>(selectedUserId);
  const [detail, setDetail] = useState<UserDto | null>(null);
  const [errorMessage, setErrorMessage] = useState<string>('');

  // ``COUSR03C`` faults USRIDIN for its own two edits only.
  const faultedUserId =
    errorMessage === MSG_USER_ID_EMPTY || errorMessage === MSG_USER_NOT_FOUND;
  const [infoMessage, setInfoMessage] = useState<string>('');

  const { loading: fetching, run: runFetch } = useApi(fetchUserOutcome);
  const { loading: deleting, run: runDelete } = useApi(deleteUserOutcome);
  const busy = fetching || deleting;
  const exitOnSelfRevocation = useSelfRevocationExit();

  /**
   * :purpose: Whether a DELETE is already in flight, tracked synchronously so a repeated
   *   F5 in the same task cannot issue a second one (see :func:`removeUser`).
   */
  const deletingRef = useRef(false);

  // Latches the id the entry read has already been performed for, so the read
  // happens exactly once per pre-selected id however often the effect is
  // re-invoked (React StrictMode remounts it in development).
  const autoReadUserIdRef = useRef<string | null>(null);

  /**
   * :purpose: Keyboard-lock latch: ``true`` from the instant a read or a delete is
   *     dispatched until its answer has been applied. A 3270 locked the keyboard for
   *     exactly that interval, so one intent could never be sent twice. The latch is a
   *     ref rather than ``busy`` because two activations in the same task both observe
   *     the state as it was before either of them, and both would be admitted.
   */
  const requestLatch = useRef<boolean>(false);

  /**
   * :purpose: ``PROCESS-ENTER-KEY`` — read the record for display. The display
   *   fields are cleared before the read, and an empty key is refused without
   *   disturbing them.
   * :param id: the keyed user id.
   */
  const fetchUser = useCallback(
    async (id: string): Promise<void> => {
      const key = id.trim();
      if (key === '') {
        setErrorMessage(MSG_USER_ID_EMPTY);
        setInfoMessage('');
        return;
      }
      setErrorMessage('');
      setInfoMessage('');
      setDetail(null);
      const outcome = await runFetch(key);
      if (outcome === undefined) {
        setErrorMessage(MSG_UNABLE_LOOKUP);
        return;
      }
      if ('user' in outcome) {
        setDetail(outcome.user);
        setInfoMessage(MSG_PRESS_PF5_DELETE);
        return;
      }
      setErrorMessage(outcome.message);
    },
    [runFetch],
  );

  /**
   * :purpose: ``DELETE-USER-INFO`` — delete the record on a deliberate F5 action.
   *   On success every field is cleared and the confirmation is published.
   * :param id: the keyed user id.
   */
  const removeUser = useCallback(
    async (id: string): Promise<void> => {
      /*
       * Set synchronously, before anything can yield. A 3270 keyboard is locked from the
       * moment an AID is sent until the program replies, so a second F5 struck in the
       * meantime is discarded rather than queued. `deleting` alone could not enforce that:
       * it is React state, so several activations dispatched within one JavaScript task
       * all observe it still false and each issues its own DELETE -- the first removing
       * the record and the rest reporting it missing.
       */
      if (deletingRef.current) {
        return;
      }
      const key = id.trim();
      if (key === '') {
        setErrorMessage(MSG_USER_ID_EMPTY);
        setInfoMessage('');
        return;
      }
      setErrorMessage('');
      setInfoMessage('');
      deletingRef.current = true;
      let outcome;
      try {
        outcome = await runDelete(key);
      } finally {
        deletingRef.current = false;
      }
      if (outcome === undefined) {
        setErrorMessage(MSG_UNABLE_UPDATE);
        return;
      }
      if ('deleted' in outcome) {
        // Name the user the way the service STORES it (upper-cased per COSGN00C L132 /
        // 3270 UCTRAN) rather than the way it was typed, so the banner identifies the
        // row that was actually removed. The delete route returns no body, so the id
        // comes from the record fetched for confirmation.
        const deletedId = detail?.userId ?? key;
        setUserId('');
        setDetail(null);
        const confirmation = MSG_USER_PREFIX + deletedId + MSG_DELETED_SUFFIX;
        // Deleting one's own record revokes one's own session: report that with the
        // action that caused it rather than letting the next action be refused blankly.
        if (await exitOnSelfRevocation(key, confirmation)) {
          return;
        }
        setInfoMessage(confirmation);
        return;
      }
      setErrorMessage(outcome.message);
    },
    [detail, exitOnSelfRevocation, runDelete],
  );

  /**
   * :purpose: ENTER handler bound to the key bar and to the lookup form.
   */
  const handleFetch = useCallback((): void => {
    if (requestLatch.current) {
      return;
    }
    requestLatch.current = true;
    void fetchUser(userId).finally(() => {
      requestLatch.current = false;
    });
  }, [fetchUser, userId]);

  /**
   * :purpose: F5 handler; the only path that removes a record.
   */
  const handleDelete = useCallback((): void => {
    if (requestLatch.current) {
      return;
    }
    requestLatch.current = true;
    void removeUser(userId).finally(() => {
      requestLatch.current = false;
    });
  }, [removeUser, userId]);

  /**
   * :purpose: F4 handler — ``INITIALIZE-ALL-FIELDS``: clear the key, the display
   *   fields, and the line-23 message.
   */
  const handleClear = useCallback((): void => {
    setUserId('');
    setDetail(null);
    setErrorMessage('');
    setInfoMessage('');
  }, []);

  /**
   * :purpose: F3 handler — return to the administrator menu.
   */
  const handleExit = useCallback((): void => {
    void navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

  /**
   * :purpose: Keep the keyed user id in state as it is typed.
   * :param event: the input change event.
   */
  const handleUserIdChange = useCallback((event: ChangeEvent<HTMLInputElement>): void => {
    setUserId(event.target.value);
    // ``DELETE-USER-INFO`` keys off the live ``USRIDINI`` and re-reads before it
    // deletes, and ``SEND-USRDEL-SCREEN`` sends with ``ERASE``, so the protected
    // detail fields never survive a send that did not repopulate them. Dropping the
    // loaded record the moment the key stops matching it reproduces that: the
    // details on screen always belong to the key beside them.
    setDetail(null);
    setInfoMessage('');
  }, []);

  /**
   * :purpose: Submit the lookup form, so ENTER in the key field reads the record.
   * :param event: the form submit event.
   */
  const handleSubmit = useCallback(
    (event: FormEvent<HTMLFormElement>): void => {
      event.preventDefault();
      handleFetch();
    },
    [handleFetch],
  );

  // ``COUSR03`` declares ``USRIDIN ATTRB=(FSET,IC,NORM,UNPROT)`` and every
  // ``COUSR03C`` path ends ``MOVE -1 TO USRIDINL``, and CICS honours the insert
  // cursor on every ``SEND MAP`` - not only the first. The settle hook re-places it
  // after each request because the browser blurs a control the instant it is
  // disabled, and the token hook covers the paths that publish a message without
  // issuing a request at all.
  const userIdRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(busy, userIdRef);
  useFocusOnChange(
    errorMessage === '' && infoMessage === '' ? null : errorMessage + '\u0000' + infoMessage,
    userIdRef,
  );

  // Entry with a pre-selected id keys it into the field and reads the record
  // immediately, mirroring the CDEMO-CU03-USR-SELECTED branch that moves the id
  // into USRIDINI and performs PROCESS-ENTER-KEY before the send. The latch keeps
  // the key field and the displayed record in step with the id actually read.
  useEffect(() => {
    if (selectedUserId === '' || autoReadUserIdRef.current === selectedUserId) {
      return;
    }
    autoReadUserIdRef.current = selectedUserId;
    setUserId(selectedUserId);
    void fetchUser(selectedUserId);
  }, [selectedUserId, fetchUser]);

  // Publish this screen's chrome into the shared shell: header ids and titles, the
  // line-23 message, and the line-24 function-key legend.
  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateFetch = useScreenAction(handleFetch);
  const activateExit = useScreenAction(handleExit);
  const activateClear = useScreenAction(handleClear);
  const activateDelete = useScreenAction(handleDelete);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PFKEY_LABEL_ENTER,
        onActivate: activateFetch,
        enabled: !busy,
      },
      // A pending DELETE must not be abandoned by navigating away: unmounting the
      // page aborts the request, so its outcome would never be known. CICS cannot
      // accept a new AID while the task runs either, so refusing keys in flight is
      // the faithful rendering rather than an added restriction.
      {
        action: PfKeyAction.PF3,
        label: PFKEY_LABEL_PF3,
        onActivate: activateExit,
        enabled: !busy,
      },
      {
        action: PfKeyAction.PF4,
        label: PFKEY_LABEL_PF4,
        onActivate: activateClear,
        enabled: !busy,
      },
      {
        action: PfKeyAction.PF5,
        label: PFKEY_LABEL_PF5,
        onActivate: activateDelete,
        enabled: !busy,
      },
      {
        action: PfKeyAction.PF12,
        label: PFKEY_LABEL_PF12,
        onActivate: activateExit,
        enabled: !busy,
        dark: true,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      busy,
      pfKeys,
    });
  }, [
    activateClear,
    activateDelete,
    activateExit,
    activateFetch,
    busy,
    errorMessage,
    infoMessage,
    setChrome,
  ]);

  return (
    <section className="userDelete" aria-labelledby="user-delete-heading">
      <h3 id="user-delete-heading" className="userDelete__heading neutral">
        {HEADING}
      </h3>

      <form className="userDelete__lookup" onSubmit={handleSubmit}>
        {/* COUSR03.bms:L80-L84 -- LENGTH=14 POS=(6,6) COLOR=GREEN. */}
        <label className="green" htmlFor="usridin">
          {LABEL_USER_ID}
        </label>{' '}
        <input
          {...invalidFieldProps(faultedUserId)}
          id="usridin"
          name="usridin"
          data-testid="user-id"
          className="field"
          type="text"
          value={userId}
          maxLength={USER_ID_LENGTH}
          size={USER_ID_LENGTH}
          autoComplete="off"
          spellCheck={false}
          ref={userIdRef}
          disabled={busy}
          onChange={handleUserIdChange}
        />
        {/* ENTER submits the lookup. The mapset places no button in the screen
            body, so this control is hidden from sight, from assistive technology
            and from keyboard navigation. */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>

      <div className="userDelete__rule title" aria-hidden="true">
        {'*'.repeat(SEPARATOR_LENGTH)}
      </div>

      <div className="userDelete__detail">
        <dl className="userDelete__row">
          <OutputField
            label={LABEL_FIRST_NAME}
            value={detail?.firstName ?? ''}
            testId="first-name"
            width={NAME_LENGTH}
          />
        </dl>

        <dl className="userDelete__row">
          <OutputField
            label={LABEL_LAST_NAME}
            value={detail?.lastName ?? ''}
            testId="last-name"
            width={NAME_LENGTH}
          />
        </dl>

        {/*
          COUSR03 row 15 paints the caption at column 6, the value at column 17 and the
          `(A=Admin, U=User)` BLUE literal at column 19, so all three share one terminal
          row. The hint is a literal of its own and belongs OUTSIDE the description
          list: a `dl` may contain only `dt`/`dd` groups, and a bare `div` between them
          is the structure violation an audit reports.
        */}
        <div className="userDelete__row">
          <dl className="userDelete__typeRow">
            <OutputField
              label={LABEL_USER_TYPE}
              value={detail?.userType ?? ''}
              testId="user-type"
              width={USER_TYPE_LENGTH}
            />
          </dl>
          <span className="label">{USER_TYPE_HINT}</span>
        </div>
      </div>

      {/*
        No Delete control is rendered in the screen body. `COUSR03.bms` declares the key
        field, three protected display fields, the `(A=Admin, U=User)` literal and one
        row-24 legend field -- nothing else. The deletion affordance is the `F5=Delete` key
        the shell renders on line 24, which the physical F5 key also activates; a second
        control duplicated it as observable output the mapset does not declare. Both paths
        run the same `handleDelete`, so the in-flight guard covers either one.
      */}
    </section>
  );
}
