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
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import { isBrowseNotice } from '../components/browseNotices';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  UserListItemDto,
  UserListRequestDto,
  UserListResponseDto,
} from '../types';
import { listUsers, ApiError } from '../api';
import {
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
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
 * :purpose: The five browse columns in mapset order, each with the run of hyphens
 *     ``COUSR00`` paints beneath its caption on row 9 -- 3, 8, 20, 20 and 4 characters at
 *     columns 5, 12, 24, 48 and 72.
 * :note: Those runs ARE the column widths on a 3270, so the table is laid out from them
 *     rather than from its content.
 */
const COLUMNS: ReadonlyArray<{ caption: string; rule: string }> = [
  { caption: 'Sel', rule: '-'.repeat(3) },
  { caption: 'User ID', rule: '-'.repeat(8) },
  { caption: 'First Name', rule: '-'.repeat(20) },
  { caption: 'Last Name', rule: '-'.repeat(20) },
  { caption: 'Type', rule: '-'.repeat(4) },
];

/**
 * :purpose: The table's floor width in character cells: the sum of every column width
 *     declared by :data:`COLUMNS`, one blank separator column each.
 * :note: Under ``table-layout: fixed`` the columns carrying an explicit width are
 *     allocated FIRST and the width-less last one takes only what is left over, so in a
 *     container narrower than the fixed columns it is allocated zero -- its cells clip to
 *     nothing and, because a zero-width column adds nothing to the scrollable range,
 *     scrolling never reveals it either. Holding the table to this floor gives the last
 *     column its cells back and puts the shortfall into the scroll range instead.
 */
const TABLE_MIN_WIDTH_CH = COLUMNS.reduce(
  (total, column) => total + column.rule.length + 2,
  0,
);

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

  const { data, loading, isInFlight, error, run } = useApi(listUsers);

  /*
   * The last browse that SUCCEEDED, held so a failed one does not blank the screen.
   * Both of COUSR00C's paging-boundary branches publish their message with
   * ``SET SEND-ERASE-NO TO TRUE`` and re-send the map (L252-L253, L275-L276), so the
   * rows already painted stay on screen while line 23 carries the message. `useApi`
   * clears `data` on a failure, which had left the operator with a header, a stale
   * ``Page:`` counter and no rows -- a browse position destroyed by a message that
   * never claimed the position had moved.
   */
  const [lastPage, setLastPage] = useState<UserListResponseDto | null>(null);
  useEffect(() => {
    if (data !== null) {
      setLastPage(data);
    }
  }, [data]);
  const shown = data ?? lastPage;

  // One response is one screen: the service returns at most ten rows and the
  // page renders exactly those, so the rows are never re-sliced here.
  const pageRows = shown?.users ?? EMPTY_ROWS;

  // PROCESS-PF8-KEY browses forward while NEXT-PAGE-YES holds, and
  // PROCESS-PF7-KEY browses backward while CDEMO-CU00-PAGE-NUM > 1. Both keys
  // stay live either way: the 3270 legend on line 24 is static text and every
  // AID reaches the program, which answers a boundary with its own message
  // rather than ignoring the key (L248-L253, L270-L276).
  const hasNext = shown?.nextPage === true;
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
   * :note: Inhibited while a browse is outstanding — the 3270 keyboard is locked from
   *     the moment an attention identifier is sent until the next map arrives, so a
   *     press that arrives in that window issues no second, identical browse.
   */
  const browse = useCallback(
    async (request: UserListRequestDto, step: number): Promise<void> => {
      if (isInFlight()) {
        return;
      }
      const response = await run(request);
      if (response !== undefined) {
        setPageNumber((current) => Math.max(current + step, FIRST_PAGE));
      }
    },
    [isInFlight, run],
  );

  const prevPage = useCallback((): void => {
    if (!hasPrevious) {
      setSelectionError(ALREADY_TOP_MESSAGE);
      return;
    }
    setSelectionError('');
    void browse(
      { direction: 'PF7', cursor: shown?.userIdFirst ?? undefined },
      -1,
    );
  }, [browse, shown, hasPrevious]);

  const nextPage = useCallback((): void => {
    if (!hasNext) {
      setSelectionError(ALREADY_BOTTOM_MESSAGE);
      return;
    }
    setSelectionError('');
    void browse({ direction: 'PF8', cursor: shown?.userIdLast ?? undefined }, 1);
  }, [browse, shown, hasNext]);

  const handleSelectionChange = useCallback(
    (userId: string, value: string): void => {
      setSelections((current) => ({ ...current, [userId]: value }));
    },
    [],
  );

  // COUSR00C evaluates SEL0001..SEL0010 in row order and acts on the FIRST non-blank
  // entry whose row carries a user id; every later entry is ignored. Resolving it once
  // here lets the row highlight mark the row that will actually be acted on, so a
  // second keyed row cannot draw the operator's eye to a row nothing will happen to.
  const actionRow: UserListItemDto | undefined = pageRows.find(
    (row) =>
      row.userId.trim() !== '' && (selections[row.userId] ?? '').trim() !== '',
  );

  const handleEnter = useCallback((): void => {
    // The locked keyboard again: an ENTER arriving while the browse is outstanding is
    // inhibited rather than sent a second time.
    if (isInFlight()) {
      return;
    }
    const selectedRow = actionRow;

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
      // COUSR00C L236-L240 moves the message into WS-MESSAGE and falls straight
      // through to SEND-USRLST-SCREEN: the rows already on the screen are re-sent
      // and the file is NOT re-read, so no browse is issued for a rejected flag.
      setSelectionError(INVALID_SELECTION_MESSAGE);
      return;
    }
    setSelectionError('');

    // ENTER also re-positions the browse at the filter field and restarts the
    // page counter, exactly as PROCESS-ENTER-KEY does (L218-L228).
    const startId = searchUserId.trim();
    setPageNumber(FIRST_PAGE);
    void run({ userId: startId === '' ? undefined : startId, page: FIRST_PAGE });
  }, [actionRow, isInFlight, selections, navigate, searchUserId, run]);

  /*
   * The one row COUSR00C's ``EVALUATE TRUE`` actually looked at, when its flag is a value
   * the program rejects. The EVALUATE is first-match-wins over SEL0001..SEL0010, so a
   * second entry further down the page is never inspected -- marking every bad entry
   * would assert a rejection the program never made. Only this row is marked, and the
   * marking is accompanied by the line-23 message that produced it, which is why the
   * accessible description is emitted here.
   */
  const firstSelectedRow = pageRows.find(
    (row) =>
      row.userId.trim() !== '' && (selections[row.userId] ?? '').trim() !== '',
  );
  const rejectedSelectionRow =
    selectionError === INVALID_SELECTION_MESSAGE &&
    firstSelectedRow !== undefined &&
    isRowSelectionInvalid(selections[firstSelectedRow.userId])
      ? firstSelectedRow.userId
      : null;

  // One line-23 region, filled in the legacy order: the validation text of the
  // current key press first, then a failed browse, then the banner the service
  // carried with a boundary or empty page.
  const publishedMessage =
    selectionError !== ''
      ? selectionError
      : error !== null
        ? resolveErrorMessage(error)
        : displayText(data?.message);

  // A browse boundary or an empty page is not a failure. It keeps the RED that
  // COUSR00.bms declares statically, but is announced politely instead of as an alert.
  const notice = isBrowseNotice(publishedMessage);
  const errorMessage = notice ? '' : publishedMessage;
  const noticeMessage = notice ? publishedMessage : '';

  const searchRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, searchRef);
  useFocusOnChange(publishedMessage === '' ? null : publishedMessage, searchRef);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateEnter = useScreenAction(handleEnter);
  const activateExit = useScreenAction(handleExit);
  const activatePrevPage = useScreenAction(prevPage);
  const activateNextPage = useScreenAction(nextPage);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        onActivate: activateEnter,
      },
      { action: PfKeyAction.PF3, label: PF3_LABEL, onActivate: activateExit },
      { action: PfKeyAction.PF7, label: PF7_LABEL, onActivate: activatePrevPage },
      { action: PfKeyAction.PF8, label: PF8_LABEL, onActivate: activateNextPage },
    ];
    setChrome({
      transactionId: 'CU00',
      programName: 'COUSR00C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      noticeMessage,
      infoMessage: '',
      pfKeys,
      busy: loading,
    });
  }, [
    activateEnter,
    activateExit,
    activateNextPage,
    activatePrevPage,
    errorMessage,
    loading,
    noticeMessage,
    setChrome,
  ]);

  return (
    <section aria-labelledby="user-list-heading">
      <div className="screenTitleLine">
        <h3 className="neutral" id="user-list-heading">
          List Users
        </h3>
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

        <div
          className="tableScroll"
          role="group"
          aria-label="User list columns"
          tabIndex={0}
        >
        <table
          className="dataTable dataTable--fixed"
          style={{ minWidth: `${String(TABLE_MIN_WIDTH_CH)}ch` }}
          aria-label="List Users"
          aria-busy={loading}
          data-testid="user-list-table"
        >
          {/*
            The BMS rule runs under each caption ARE the column widths, so they are
            declared here and the table is laid out from them rather than from its
            content. Content-driven layout let a narrow viewport shrink the columns until
            `overflow: hidden` clipped surnames mid-word and left the last column with no
            glyphs at all; the floor width puts that shortfall into the scroll range
            instead. The last column is left width-less so a wider container hands it the
            slack, exactly as the 3270 frame does.
          */}
          <colgroup>
            {COLUMNS.map((column, index) => (
              <col
                key={column.caption}
                style={
                  index === COLUMNS.length - 1
                    ? undefined
                    : { width: `${String(column.rule.length + 2)}ch` }
                }
              />
            ))}
          </colgroup>
          <thead>
            <tr>
              {COLUMNS.map((column) => (
                <th key={column.caption} scope="col">
                  {column.caption}
                </th>
              ))}
            </tr>
            {/*
              COUSR00 row 9 paints a run of hyphens under each caption -- 3, 8, 20, 20
              and 4 characters at columns 5, 12, 24, 48 and 72 -- with the gaps between
              them left blank. Decoration only, so it is hidden from assistive
              technology.
            */}
            <tr className="dataTable__rule" aria-hidden="true">
              {COLUMNS.map((column) => (
                <td key={column.caption}>{column.rule}</td>
              ))}
            </tr>
          </thead>
          <tbody>
            {/*
              An empty page renders NO row. COUSR00C paints no "nothing found" literal
              into the body: it leaves SEL0001..SEL0010 and their rows blank and publishes
              its own literal on line 23 (``'You are at the top of the page...'`` from the
              ``STARTBR`` ``NOTFND`` branch), so a placeholder row would be output the
              program never produces. Nothing is claimed about a browse that has not
              answered either, which is the state the screen is in before its first read
              settles. The card and transaction list screens read the same way.
            */}
            {pageRows.map((row, index) => {
              const fieldName = selectFieldName(index);
              return (
                <tr
                  key={row.userId}
                  data-testid="user-row"
                  className={row.userId === actionRow?.userId ? 'selected' : undefined}
                >
                  <td>
                    <input
                      className="field"
                      id={fieldName}
                      name={fieldName}
                      type="text"
                      autoComplete="off"
                      disabled={loading}
                      aria-label={`Select user ${row.userId}`}
                      {...invalidFieldProps(row.userId === rejectedSelectionRow)}
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
        </div>

        <p className="neutral screenNote">
          Type &apos;U&apos; to Update or &apos;D&apos; to Delete a User from the
          list
        </p>
      </div>
    </section>
  );
}
