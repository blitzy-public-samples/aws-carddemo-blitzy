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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { UserDto } from '../types';
import { ApiError, deleteUser, getUser } from '../api';
import { useApi } from '../hooks';

/** CICS transaction id of the delete-user screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CU03';

/** Legacy program name shown on header line 2 (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COUSR03C';

/** First header title line. */
const TITLE01 = 'CardDemo';

/** Second header title line. */
const TITLE02 = 'Delete User';

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

/** In-body caption of the read-for-display action. */
const BUTTON_LABEL_FETCH = 'Fetch';

/** In-body caption of the delete action. */
const BUTTON_LABEL_DELETE = 'Delete';

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
  const [infoMessage, setInfoMessage] = useState<string>('');

  const { loading: fetching, run: runFetch } = useApi(fetchUserOutcome);
  const { loading: deleting, run: runDelete } = useApi(deleteUserOutcome);
  const busy = fetching || deleting;

  // Latches the id the entry read has already been performed for, so the read
  // happens exactly once per pre-selected id however often the effect is
  // re-invoked (React StrictMode remounts it in development).
  const autoReadUserIdRef = useRef<string | null>(null);

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
      const key = id.trim();
      if (key === '') {
        setErrorMessage(MSG_USER_ID_EMPTY);
        setInfoMessage('');
        return;
      }
      setErrorMessage('');
      setInfoMessage('');
      const outcome = await runDelete(key);
      if (outcome === undefined) {
        setErrorMessage(MSG_UNABLE_UPDATE);
        return;
      }
      if ('deleted' in outcome) {
        setUserId('');
        setDetail(null);
        setInfoMessage(MSG_USER_PREFIX + key + MSG_DELETED_SUFFIX);
        return;
      }
      setErrorMessage(outcome.message);
    },
    [runDelete],
  );

  /**
   * :purpose: ENTER handler bound to the key bar and to the lookup form.
   */
  const handleFetch = useCallback((): void => {
    void fetchUser(userId);
  }, [fetchUser, userId]);

  /**
   * :purpose: F5 handler; the only path that removes a record.
   */
  const handleDelete = useCallback((): void => {
    void removeUser(userId);
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
    navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

  /**
   * :purpose: Keep the keyed user id in state as it is typed.
   * :param event: the input change event.
   */
  const handleUserIdChange = useCallback((event: ChangeEvent<HTMLInputElement>): void => {
    setUserId(event.target.value);
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
  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PFKEY_LABEL_ENTER,
        onActivate: handleFetch,
        enabled: !busy,
      },
      { action: PfKeyAction.PF3, label: PFKEY_LABEL_PF3, onActivate: handleExit },
      {
        action: PfKeyAction.PF4,
        label: PFKEY_LABEL_PF4,
        onActivate: handleClear,
        enabled: !busy,
      },
      {
        action: PfKeyAction.PF5,
        label: PFKEY_LABEL_PF5,
        onActivate: handleDelete,
        enabled: !busy,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [
    setChrome,
    errorMessage,
    infoMessage,
    busy,
    handleFetch,
    handleDelete,
    handleClear,
    handleExit,
  ]);

  return (
    <section className="userDelete" aria-labelledby="user-delete-heading">
      <h3 id="user-delete-heading" className="userDelete__heading neutral">
        {HEADING}
      </h3>

      <form className="userDelete__lookup" onSubmit={handleSubmit}>
        <label className="label" htmlFor="usridin">
          {LABEL_USER_ID}
        </label>{' '}
        <input
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
          autoFocus
          onChange={handleUserIdChange}
        />{' '}
        <button type="submit" data-testid="fetch-button" disabled={busy}>
          {BUTTON_LABEL_FETCH}
        </button>
      </form>

      <div className="userDelete__rule title" aria-hidden="true">
        {'*'.repeat(SEPARATOR_LENGTH)}
      </div>

      <div className="userDelete__detail">
        <div className="userDelete__row">
          <label className="prompt" htmlFor="fname">
            {LABEL_FIRST_NAME}
          </label>{' '}
          <input
            id="fname"
            name="fname"
            data-testid="first-name"
            className="label"
            type="text"
            value={detail?.firstName ?? ''}
            maxLength={NAME_LENGTH}
            size={NAME_LENGTH}
            readOnly
          />
        </div>

        <div className="userDelete__row">
          <label className="prompt" htmlFor="lname">
            {LABEL_LAST_NAME}
          </label>{' '}
          <input
            id="lname"
            name="lname"
            data-testid="last-name"
            className="label"
            type="text"
            value={detail?.lastName ?? ''}
            maxLength={NAME_LENGTH}
            size={NAME_LENGTH}
            readOnly
          />
        </div>

        <div className="userDelete__row">
          <label className="prompt" htmlFor="usrtype">
            {LABEL_USER_TYPE}
          </label>{' '}
          <input
            id="usrtype"
            name="usrtype"
            data-testid="user-type"
            className="label"
            type="text"
            value={detail?.userType ?? ''}
            maxLength={USER_TYPE_LENGTH}
            size={USER_TYPE_LENGTH}
            readOnly
          />{' '}
          <span className="label">{USER_TYPE_HINT}</span>
        </div>
      </div>

      <div className="userDelete__actions">
        <button
          type="button"
          data-testid="delete-button"
          disabled={busy}
          onClick={handleDelete}
        >
          {BUTTON_LABEL_DELETE}
        </button>
      </div>
    </section>
  );
}

