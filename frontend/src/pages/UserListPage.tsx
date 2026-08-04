/**
 * UserListPage
 * ============
 *
 * :purpose: Administrator user-list screen — a ten-rows-per-page browse of the
 *     security-user file with a per-row ``Sel`` column accepting ``U`` (update)
 *     or ``D`` (delete), an optional ``Search User ID:`` filter that positions
 *     the browse, and PF7 / PF8 paging. One-for-one replacement of the BMS
 *     mapset ``app/bms/COUSR00.bms`` (CICS transaction ``CU00``, program
 *     ``app/cbl/COUSR00C.cbl``), served at the administrator-only route
 *     ``/users``.
 * :output: The rendered screen body. The header, the line-23 message region and
 *     the line-24 function-key bar are published to the shared terminal shell
 *     through :func:`useScreenChrome` and are never rendered here.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { UserListItemDto } from '../types';
import { listUsers, ApiError } from '../api';
import { useApi, usePagination, USER_LIST_PAGE_SIZE } from '../hooks';

/** ``Sel`` value routing to the update-user screen (``COUSR02C``). */
const SELECT_UPDATE = 'U';

/** ``Sel`` value routing to the delete-user screen (``COUSR03C``). */
const SELECT_DELETE = 'D';

/** Line-23 text for a ``Sel`` value that is neither ``U`` nor ``D``. */
const INVALID_SELECTION_MESSAGE = 'Invalid selection. Valid values are U and D';

/** Route of the update-user screen ``COUSR02`` reached by selecting ``U``. */
const UPDATE_USER_ROUTE = '/users/update';

/** Route of the delete-user screen ``COUSR03`` reached by selecting ``D``. */
const DELETE_USER_ROUTE = '/users/delete';

/** Route of the administrator menu ``COADM01`` reached with F3. */
const ADMIN_MENU_ROUTE = '/admin';

/** First one-based server page requested from ``GET /users``. */
const FIRST_PAGE = 1;

/** Width of the ``USRIDIN`` filter field (``PIC X(8)``). */
const USER_ID_FIELD_WIDTH = 8;

/** Width of a ``SEL0001``..``SEL0010`` row-select field (``PIC X(1)``). */
const SELECT_FIELD_WIDTH = 1;

/** Digits in a zero-padded row-select field name (``SEL0001``). */
const SELECT_FIELD_DIGITS = 4;

/**
 * :purpose: Stable empty row list rendered before the first response resolves.
 */
const EMPTY_ROWS: UserListItemDto[] = [];

/**
 * :purpose: Build the row-select field name for a displayed row.
 * :param index: zero-based row index within the current page.
 * :returns: the one-based, zero-padded field name (``SEL0001``..``SEL0010``).
 */
function selectFieldName(index: number): string {
  return `SEL${String(index + 1).padStart(SELECT_FIELD_DIGITS, '0')}`;
}

/**
 * :purpose: Render a row value as display text.
 * :param value: the value held by the row, possibly absent on the wire.
 * :returns: the value, or an empty string when it is ``null`` / ``undefined``.
 */
function displayText(value: string | null | undefined): string {
  return value ?? '';
}

/**
 * :purpose: Resolve the line-23 text for a failed list request.
 * :param error: the normalized error of the most recent call, or ``null``.
 * :returns: the backend message when the response carried one, the client
 *     message otherwise, and an empty string when the last call succeeded.
 */
function resolveErrorMessage(error: ApiError | null): string {
  if (error === null) {
    return '';
  }
  const backendMessage = error.body?.message;
  if (backendMessage !== undefined && backendMessage !== '') {
    return backendMessage;
  }
  return error.message;
}

/**
 * :purpose: The administrator user-list screen (``CU00`` / ``COUSR00C``).
 * :returns: The rendered screen body.
 */
export default function UserListPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  // ``USRIDIN`` — the text currently in the filter field, and the value the
  // browse is positioned on, which ENTER commits.
  const [searchUserId, setSearchUserId] = useState('');
  const [appliedUserId, setAppliedUserId] = useState('');

  // ``SEL0001``..``SEL0010`` values, keyed by the user id of the row they were
  // typed against.
  const [selections, setSelections] = useState<Record<string, string>>({});

  // Line-23 validation text produced by the most recent ENTER.
  const [selectionError, setSelectionError] = useState('');

  // One-based server page (``CDEMO-CU00-PAGE-NUM``) currently requested.
  const [serverPage, setServerPage] = useState(FIRST_PAGE);

  const { data, loading, error, run } = useApi(listUsers);

  const rows = data?.items ?? EMPTY_ROWS;

  // The rows of one server response are sliced at USER_LIST_PAGE_SIZE: the
  // screen renders at most ten rows.
  const {
    page: clientPage,
    pageRows,
    hasPrevious: clientHasPrevious,
    hasNext: clientHasNext,
    prevPage: clientPrevPage,
    nextPage: clientNextPage,
    reset: resetClientPage,
  } = usePagination(rows, USER_LIST_PAGE_SIZE);

  // ``PageInfo`` from the response continues the browse past the slice held in
  // memory.
  const serverPageInfo = data?.page;
  const serverHasPrevious = serverPageInfo?.hasPrevious === true;
  const serverHasNext = serverPageInfo?.hasNext === true;

  const hasPrevious = clientHasPrevious || serverHasPrevious;
  const hasNext = clientHasNext || serverHasNext;

  // One-based page number shown after the ``Page:`` caption: the server page
  // plus the current client slice.
  const displayedPage = serverPage + clientPage;

  useEffect(() => {
    void run({
      userId: appliedUserId === '' ? undefined : appliedUserId,
      page: serverPage,
    });
  }, [run, appliedUserId, serverPage]);

  const handleExit = useCallback((): void => {
    navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

  const prevPage = useCallback((): void => {
    setSelectionError('');
    if (clientHasPrevious) {
      clientPrevPage();
      return;
    }
    if (serverHasPrevious) {
      resetClientPage();
      setServerPage((current) => Math.max(current - 1, FIRST_PAGE));
    }
  }, [clientHasPrevious, clientPrevPage, serverHasPrevious, resetClientPage]);

  const nextPage = useCallback((): void => {
    setSelectionError('');
    if (clientHasNext) {
      clientNextPage();
      return;
    }
    if (serverHasNext) {
      resetClientPage();
      setServerPage((current) => current + 1);
    }
  }, [clientHasNext, clientNextPage, serverHasNext, resetClientPage]);

  const handleSelectionChange = useCallback(
    (userId: string, value: string): void => {
      setSelections((current) => ({ ...current, [userId]: value }));
    },
    [],
  );

  const handleEnter = useCallback((): void => {
    // COUSR00C evaluates SEL0001..SEL0010 in row order and acts on the first
    // non-blank entry whose row carries a user id.
    const selectedRow = pageRows.find(
      (row) =>
        row.userId.trim() !== '' &&
        (selections[row.userId] ?? '').trim() !== '',
    );

    if (selectedRow !== undefined) {
      const selectFlag = (selections[selectedRow.userId] ?? '')
        .trim()
        .toUpperCase();
      if (selectFlag === SELECT_UPDATE) {
        navigate(UPDATE_USER_ROUTE, { state: { userId: selectedRow.userId } });
        return;
      }
      if (selectFlag === SELECT_DELETE) {
        navigate(DELETE_USER_ROUTE, { state: { userId: selectedRow.userId } });
        return;
      }
      setSelectionError(INVALID_SELECTION_MESSAGE);
    } else {
      setSelectionError('');
    }

    // ENTER also re-positions the browse from the filter field and restarts at
    // the first page.
    setAppliedUserId(searchUserId.trim());
    resetClientPage();
    setServerPage(FIRST_PAGE);
  }, [pageRows, selections, navigate, searchUserId, resetClientPage]);

  const errorMessage =
    selectionError !== '' ? selectionError : resolveErrorMessage(error);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
      {
        action: PfKeyAction.PF7,
        label: 'F7=Backward',
        onActivate: prevPage,
        enabled: hasPrevious,
      },
      {
        action: PfKeyAction.PF8,
        label: 'F8=Forward',
        onActivate: nextPage,
        enabled: hasNext,
      },
    ];
    setChrome({
      transactionId: 'CU00',
      programName: 'COUSR00C',
      title01: 'CardDemo',
      title02: 'List Users',
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [
    setChrome,
    errorMessage,
    handleExit,
    prevPage,
    nextPage,
    hasPrevious,
    hasNext,
  ]);

  return (
    <section aria-labelledby="user-list-heading">
      <h2 className="neutral" id="user-list-heading">
        List Users
      </h2>

      <p className="prompt">
        <span>Page:</span>{' '}
        <span className="label" data-testid="page-number">
          {displayedPage}
        </span>
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          handleEnter();
        }}
      >
        <p>
          <label className="prompt" htmlFor="USRIDIN">
            Search User ID:
          </label>{' '}
          <input
            className="field"
            id="USRIDIN"
            name="USRIDIN"
            type="text"
            autoComplete="off"
            maxLength={USER_ID_FIELD_WIDTH}
            size={USER_ID_FIELD_WIDTH}
            value={searchUserId}
            onChange={(event) => setSearchUserId(event.target.value)}
          />
        </p>

        <table
          className="dataTable"
          aria-label="List Users"
          aria-busy={loading}
          data-testid="user-list-table"
        >
          <thead>
            <tr>
              <th scope="col">Sel</th>
              <th scope="col">User ID</th>
              <th scope="col">First Name</th>
              <th scope="col">Last Name</th>
              <th scope="col">Type</th>
            </tr>
          </thead>
          <tbody>
            {pageRows.map((row, index) => {
              const fieldName = selectFieldName(index);
              return (
                <tr key={row.userId} data-testid="user-row">
                  <td>
                    <input
                      className="field"
                      id={fieldName}
                      name={fieldName}
                      type="text"
                      autoComplete="off"
                      aria-label={`Select user ${row.userId}`}
                      maxLength={SELECT_FIELD_WIDTH}
                      size={SELECT_FIELD_WIDTH}
                      value={selections[row.userId] ?? ''}
                      onChange={(event) =>
                        handleSelectionChange(row.userId, event.target.value)
                      }
                    />
                  </td>
                  <td>{displayText(row.userId)}</td>
                  <td>{displayText(row.firstName)}</td>
                  <td>{displayText(row.lastName)}</td>
                  <td>{displayText(row.userType)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <p className="neutral">
          Type &apos;U&apos; to Update or &apos;D&apos; to Delete a User from the
          list
        </p>

        <button type="submit">ENTER=Continue</button>
      </form>
    </section>
  );
}
