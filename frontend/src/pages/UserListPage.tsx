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
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidValueProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { UserListItemDto, UserListRequestDto } from '../types';
import { listUsers, ApiError } from '../api';
import {
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
} from '../hooks';
import { displayText, resolveApiErrorMessage } from '../components/display';

/** ``Sel`` value routing to the update-user screen (``COUSR02C``). */
const SELECT_UPDATE = 'U';

/** ``Sel`` value routing to the delete-user screen (``COUSR03C``). */
const SELECT_DELETE = 'D';

/** Line-23 text for a ``Sel`` value that is neither ``U`` nor ``D``. */
const INVALID_SELECTION_MESSAGE = 'Invalid selection. Valid values are U and D';

/**
 * :purpose: Whether a ``Sel`` entry is a value ``COUSR00C`` rejects. A blank entry is
 *     not a selection at all, so only a non-blank value other than ``U`` or ``D``
 *     faults its control.
 * :param value: the row's current ``Sel`` entry.
 * :returns: ``true`` when the entry would raise the invalid-selection message.
 */
function isRowSelectionInvalid(value: string | undefined): boolean {
  const canonical = (value ?? '').trim().toUpperCase();
  return canonical !== '' && canonical !== SELECT_UPDATE && canonical !== SELECT_DELETE;
}

/** Route of the update-user screen ``COUSR02`` reached by selecting ``U``. */
const UPDATE_USER_ROUTE = '/users/update';

/** Route of the delete-user screen ``COUSR03`` reached by selecting ``D``. */
const DELETE_USER_ROUTE = '/users/delete';

/** Route of the administrator menu ``COADM01`` reached with F3. */
const ADMIN_MENU_ROUTE = '/admin';

/** First page of the browse, on the one-based counter the screen displays. */
const FIRST_PAGE = 1;

/** Line-23 text for PF7 pressed on the first page (``COUSR00C`` L251). */
const ALREADY_TOP_MESSAGE = 'You are already at the top of the page...';

/** Line-23 text for PF8 pressed on the last page (``COUSR00C`` L272). */
const ALREADY_BOTTOM_MESSAGE = 'You are already at the bottom of the page...';

/** ``COUSR00`` line-24 legend keys, verbatim. */
const PF_ENTER_LABEL = 'ENTER=Continue';
const PF3_LABEL = 'F3=Back';
const PF7_LABEL = 'F7=Backward';
const PF8_LABEL = 'F8=Forward';

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
 * :purpose: Resolve the line-23 text for a failed list request.
 * :param error: the normalized error of the most recent call, or ``null``.
 * :returns: the backend message when the response carried one, the client
 *     message otherwise, and an empty string when the last call succeeded.
 */
function resolveErrorMessage(error: ApiError | null): string {
  return error === null ? '' : resolveApiErrorMessage(error);
}

/**
 * :purpose: The administrator user-list screen (``CU00`` / ``COUSR00C``).
 * :returns: The rendered screen body.
 */
export default function UserListPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  // ``USRIDIN`` — the text currently in the filter field.
  const [searchUserId, setSearchUserId] = useState('');

  // ``SEL0001``..``SEL0010`` values, keyed by the user id of the row they were
  // typed against.
  const [selections, setSelections] = useState<Record<string, string>>({});

  // Line-23 validation text produced by the most recent ENTER or PF7.
  const [selectionError, setSelectionError] = useState('');

  // ``CDEMO-CU00-PAGE-NUM`` — the one-based counter the screen displays after
  // ``Page:``. The program owns it: ENTER restarts it at one and a completed
  // PF7 / PF8 browse steps it, so it is never derived from the rows.
  const [pageNumber, setPageNumber] = useState(FIRST_PAGE);

  const { data, loading, error, run } = useApi(listUsers);

  // One response is one screen: the service returns at most ten rows and the
  // page renders exactly those, so the rows are never re-sliced here.
  const pageRows = data?.users ?? EMPTY_ROWS;

  // PROCESS-PF8-KEY browses forward while NEXT-PAGE-YES holds, and
  // PROCESS-PF7-KEY browses backward while CDEMO-CU00-PAGE-NUM > 1. Both keys
  // stay live either way: the 3270 legend on line 24 is static text and every
  // AID reaches the program, which answers a boundary with its own message
  // rather than ignoring the key (L248-L253, L270-L276).
  const hasNext = data?.nextPage === true;
  const hasPrevious = pageNumber > FIRST_PAGE;

  useEffect(() => {
    void run({ page: FIRST_PAGE });
  }, [run]);

  const handleExit = useCallback((): void => {
    void navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

  /**
   * :purpose: Issue one browse and step the displayed page counter only when it
   *     completed, so a boundary rejection leaves the counter where it was.
   */
  const browse = useCallback(
    async (request: UserListRequestDto, step: number): Promise<void> => {
      const response = await run(request);
      if (response !== undefined) {
        setPageNumber((current) => Math.max(current + step, FIRST_PAGE));
      }
    },
    [run],
  );

  const prevPage = useCallback((): void => {
    if (!hasPrevious) {
      setSelectionError(ALREADY_TOP_MESSAGE);
      return;
    }
    setSelectionError('');
    void browse(
      { direction: 'PF7', cursor: data?.userIdFirst ?? undefined },
      -1,
    );
  }, [browse, data, hasPrevious]);

  const nextPage = useCallback((): void => {
    if (!hasNext) {
      setSelectionError(ALREADY_BOTTOM_MESSAGE);
      return;
    }
    setSelectionError('');
    void browse({ direction: 'PF8', cursor: data?.userIdLast ?? undefined }, 1);
  }, [browse, data, hasNext]);

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
        void navigate(UPDATE_USER_ROUTE, { state: { userId: selectedRow.userId } });
        return;
      }
      if (selectFlag === SELECT_DELETE) {
        void navigate(DELETE_USER_ROUTE, { state: { userId: selectedRow.userId } });
        return;
      }
      setSelectionError(INVALID_SELECTION_MESSAGE);
    } else {
      setSelectionError('');
    }

    // ENTER also re-positions the browse at the filter field and restarts the
    // page counter, exactly as PROCESS-ENTER-KEY does (L218-L228).
    const startId = searchUserId.trim();
    setPageNumber(FIRST_PAGE);
    void run({ userId: startId === '' ? undefined : startId, page: FIRST_PAGE });
  }, [pageRows, selections, navigate, searchUserId, run]);

  // One line-23 region, filled in the legacy order: the validation text of the
  // current key press first, then a failed browse, then the banner the service
  // carried with a boundary or empty page.
  const errorMessage =
    selectionError !== ''
      ? selectionError
      : error !== null
        ? resolveErrorMessage(error)
        : displayText(data?.message);

  const searchRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, searchRef);
  useFocusOnChange(errorMessage === '' ? null : errorMessage, searchRef);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        onActivate: handleEnter,
      },
      { action: PfKeyAction.PF3, label: PF3_LABEL, onActivate: handleExit },
      { action: PfKeyAction.PF7, label: PF7_LABEL, onActivate: prevPage },
      { action: PfKeyAction.PF8, label: PF8_LABEL, onActivate: nextPage },
    ];
    setChrome({
      transactionId: 'CU00',
      programName: 'COUSR00C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      busy: loading,
    });
  }, [
    setChrome,
    errorMessage,
    loading,
    handleEnter,
    handleExit,
    prevPage,
    nextPage,
  ]);

  return (
    <section aria-labelledby="user-list-heading">
      <div className="screenTitleLine">
        <h2 className="neutral" id="user-list-heading">
          List Users
        </h2>
        <p className="prompt screenTitleLine__page">
          <span>Page:</span>{' '}
          <span className="label" data-testid="page-number">
            {pageNumber}
          </span>
        </p>
      </div>

      <div className="userList__body">
        <div className="userListSearch">
          <label className="prompt" htmlFor="USRIDIN">
            Search User ID:
          </label>{' '}
          <input
            ref={searchRef}
            className="field"
            id="USRIDIN"
            name="USRIDIN"
            type="text"
            autoComplete="off"
            disabled={loading}
            maxLength={USER_ID_FIELD_WIDTH}
            size={USER_ID_FIELD_WIDTH}
            value={searchUserId}
            onChange={(event) => setSearchUserId(event.target.value)}
          />
        </div>

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
                      disabled={loading}
                      aria-label={`Select user ${row.userId}`}
                      {...invalidValueProps(
                        isRowSelectionInvalid(selections[row.userId]),
                      )}
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

        <p className="neutral screenNote">
          Type &apos;U&apos; to Update or &apos;D&apos; to Delete a User from the
          list
        </p>
      </div>
    </section>
  );
}
