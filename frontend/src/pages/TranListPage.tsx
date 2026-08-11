/**
 * TranListPage
 * ============
 *
 * :purpose: Paginated transaction-list screen: the React replacement for BMS
 *     mapset ``app/bms/COTRN00.bms`` (CICS transaction ``CT00``, program
 *     ``COTRN00C``). It browses the transaction file ten rows per page, positions
 *     the browse from an optional starting transaction id, and drills through to
 *     the transaction-view screen when a row is marked ``S``.
 * :output: The screen body only — the heading, the ``Search Tran ID:`` filter and
 *     the row table. The header, the line-23 message banner and the line-24
 *     PF-key bar are published to the shared ``Layout`` chrome instead of being
 *     rendered here.
 * :note: Row fields reproduce the symbolic map ``app/cpy-bms/COTRN00.CPY``:
 *     ``TRNIDIN`` (16) for the filter and ``SEL0001``..``SEL0010`` (1) for the
 *     ten row-selection flags. Wire values (``MM/DD/YY`` date, scale-2 amount
 *     string, 16-character transaction id) are rendered verbatim.
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { isBrowseNotice } from '../components/browseNotices';
import { invalidFieldProps, invalidValueProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  PfKeyAction,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_INVALID_KEY,
  SCREEN_NAMES,
} from '../types';

/**
 * Row-4 screen name of ``app/bms/COTRN00.bms``, rendered as the screen's own
 * body heading. The two header title lines are the shared ``COTTL01Y`` pair.
 */
const SCREEN_NAME = SCREEN_NAMES.COTRN00;
import type { TranListItemDto, TranListResponseDto } from '../types';
import { listTransactions, ApiError } from '../api';
import {
  placeCursor,
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
} from '../hooks';
import { displayText } from '../components/display';
import { isFilterAllDigits, isFilterNotSupplied, limitToFieldWidth } from './screenFilters';

/** CICS transaction identifier of the legacy screen. */
const TRANSACTION_ID = 'CT00';

/** Legacy program name of the screen. */
const PROGRAM_NAME = 'COTRN00C';

/** Prompt of the ``TRNIDIN`` browse filter. */
const FILTER_LABEL = 'Search Tran ID:';

/** Field name of the browse filter (BMS ``TRNIDIN``). */
const FILTER_FIELD_NAME = 'TRNIDIN';

/** Width of the ``TRNIDIN`` filter and of a transaction id (``PIC X(16)``). */
const TRAN_ID_LENGTH = 16;

/** Width of a row-selection flag (BMS ``SEL0001``..``SEL0010``, ``PIC X(01)``). */
const SELECTION_LENGTH = 1;

/**
 * :purpose: The map's row-slot count. ``COTRN00.bms`` declares ``SEL0001``..``SEL0010``,
 *     ``TRNID01``..``TRNID10`` and their date, description and amount companions at
 *     fixed ``POS`` values, and ``COTRN00C`` blanks all ten
 *     (``PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10 / PERFORM
 *     INITIALIZE-TRAN-DATA``, L289-L292) before filling only the ones it read. The grid
 *     therefore holds ten rows on every send, whatever the browse returned.
 */
const ROW_SLOT_COUNT = 10;

/** The only accepted row-selection flag; ``COTRN00C`` accepts ``'S'`` or ``'s'``. */
const SELECTION_VALUE = 'S';

/**
 * :purpose: Row-table columns in ``COTRN00.bms`` order, each with the alignment
 *     class it carries; the amount column is right-aligned like its ``TAMT00n``
 *     numeric-edited source field.
 */
/**
 * :purpose: The browse columns of ``COTRN00``, each with the mapset's own column pitch.
 * :note: ``pitch`` is the distance from one dashed rule to the next in the mapset --
 *     ``POS=(9,2) (9,8) (9,27) (9,38) (9,67)`` gives 6, 19, 11, 29 and 12 -- and it is
 *     what the ``<colgroup>`` declares, so every column begins on the column the mapset
 *     places it on. It is NOT the rule length: a rule is shorter than its pitch by the
 *     blank cells the mapset leaves after it, and sizing a column to the rule instead
 *     accumulated a shortfall that moved the last column four cells left of its `POS`.
 */
const COLUMNS: ReadonlyArray<{
  caption: string;
  rule: string;
  pitch: number;
  className?: string;
}> = [
  { caption: 'Sel', rule: '-'.repeat(3), pitch: 6 },
  { caption: 'Transaction ID', rule: '-'.repeat(16), pitch: 19 },
  { caption: 'Date', rule: '-'.repeat(8), pitch: 11 },
  { caption: 'Description', rule: '-'.repeat(26), pitch: 29 },
  { caption: 'Amount', rule: '-'.repeat(12), pitch: 12, className: 'amount' },
];

/**
 * :purpose: Accessible name of the browse-table scroll region, so the columns that fall
 *     outside a narrow viewport are reachable from the keyboard. The clipped columns
 *     hold no focusable field of their own, so without this the region could only be
 *     scrolled with a pointer.
 */
const TABLE_REGION_LABEL = 'Transaction list columns';

/**
 * :purpose: The table's floor width in character cells: the sum of every column width
 *     declared by :data:`COLUMNS`. It is what keeps the last column reachable on a narrow
 *     viewport. Under ``table-layout: fixed`` the columns carrying an explicit width are
 *     allocated FIRST and the width-less last one takes only what is left over, so in a
 *     container narrower than the fixed columns it is allocated zero -- its cells then
 *     clip to nothing, and because a zero-width column adds nothing to the scrollable
 *     range, scrolling the region never reveals it either. Holding the table to this
 *     floor gives the last column its own cells back and puts the shortfall into the
 *     scroll range instead, while a container WIDER than the floor still hands the slack
 *     to that column so the right-aligned amount stays against the right edge of the
 *     frame, where the mapset puts it.
 */
const TABLE_MIN_WIDTH_CH = COLUMNS.reduce(
  (total, column) => total + column.pitch,
  0,
);

/** Rejection message for a row-selection flag other than ``S`` (``COTRN00C``). */
const MSG_INVALID_SELECTION = 'Invalid selection. Valid value is S';

/** Rejection message for a non-numeric browse filter (``COTRN00C``). */
const MSG_TRAN_ID_NUMERIC = 'Tran ID must be Numeric ...';

/**
 * :purpose: Whether a ``Sel`` entry is a value ``COTRN00C`` rejects. A blank entry is
 *     not a selection at all, so only a non-blank value other than ``S`` faults its
 *     control.
 * :param value: the row's current ``Sel`` entry.
 * :returns: ``true`` when the entry would raise the invalid-selection message.
 */
function isRowSelectionInvalid(value: string | undefined): boolean {
  const canonical = (value ?? '').trim().toUpperCase();
  return canonical !== '' && canonical !== SELECTION_VALUE;
}

/** Static line-21 instruction literal of the mapset. */
const SELECTION_HINT = "Type 'S' to View Transaction details from the list";

/** Static ``Page:`` caption of the mapset (line 4). */
const PAGE_LABEL = 'Page:';

/** First page of a browse, shown before the first response arrives. */
const FIRST_PAGE = 1;

/** ENTER entry of the mapset line-24 legend, carried by the submit control. */
const SUBMIT_LABEL = 'ENTER=Continue';

/** Server action indicator for a page-backward (PF7) browse. */
const ACTION_BACKWARD = 'PF7';

/** Server action indicator for a page-forward (PF8) browse. */
const ACTION_FORWARD = 'PF8';

/** Route of the main menu, reached with PF3 (legacy ``XCTL`` to ``COMEN01C``). */
const MENU_ROUTE = '/menu';

/** PF3 entry of the mapset line-24 legend. */
const EXIT_LABEL = 'F3=Back';

/** PF7 entry of the mapset line-24 legend. */
const BACKWARD_LABEL = 'F7=Backward';

/** PF8 entry of the mapset line-24 legend. */
const FORWARD_LABEL = 'F8=Forward';

/** Route prefix of the transaction-view screen (``CT01`` / ``COTRN01C``). */
const TRANSACTION_VIEW_ROUTE = '/transactions/';

/**
 * :purpose: Build the symbolic-map field name of a row-selection flag.
 * :param rowIndex: zero-based index of the row on the current page.
 * :returns: the BMS field name, ``SEL0001`` through ``SEL0010``.
 */
function selectionFieldName(rowIndex: number): string {
  return `SEL${String(rowIndex + 1).padStart(4, '0')}`;
}

/**
 * :purpose: Resolve the line-23 banner text of a failed list request.
 * :param error: the normalized error surfaced by :func:`useApi`, or ``null`` when
 *     the most recent request succeeded.
 * :returns: the error message, or the empty string when there is no error.
 */
function requestErrorText(error: ApiError | null): string {
  return error === null ? '' : displayText(error.message);
}

/**
 * :purpose: The transaction-list screen.
 * :returns: The rendered screen body; the chrome is published to ``Layout``.
 */
export default function TranListPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  /** Current ``TRNIDIN`` browse filter as typed by the user. */
  const [tranIdFilter, setTranIdFilter] = useState('');

  /** Row-selection flags keyed by their ``SEL0001``..``SEL0010`` field name. */
  const [selectionFlags, setSelectionFlags] = useState<Record<string, string>>({});

  /** Client-side rejection text for the current turn, or the empty string. */
  const [validationMessage, setValidationMessage] = useState('');

  const { data, error, loading, isInFlight, run } = useApi(listTransactions);

  // COTRN00C browses the file itself and returns exactly one screen of rows, so the
  // page shown is the page the service reports; there is no second, client-side slice.
  /*
   * The last browse that SUCCEEDED, held so a failed one does not blank the screen.
   * COTRN00C re-sends the map with ``SET SEND-ERASE-NO TO TRUE`` whenever it publishes a
   * message about a browse it did not perform -- both paging-boundary branches do exactly
   * that -- so the rows already painted, and the page number beside them, stay on screen
   * while line 23 carries the message. Dropping them would discard the operator's browse
   * position on a message that never claimed the position had changed.
   */
  const [lastPage, setLastPage] = useState<TranListResponseDto | null>(null);
  useEffect(() => {
    if (data !== null) {
      setLastPage(data);
    }
  }, [data]);
  const shown = data ?? lastPage;

  const rows: TranListItemDto[] = useMemo(() => shown?.transactions ?? [], [shown]);
  /*
   * The ten fixed row slots of the map, each holding the row the browse put there or
   * nothing. A short page therefore paints its rows and then blank slots, exactly as
   * COTRN00C leaves the ten initialised row fields it did not fill. Rendering only the
   * rows that carried data made the grid shrink to fit them, so the line-21 instruction
   * literal and the line-24 key legend below it climbed the frame by the height of every
   * missing row -- a 3270 field never moves off its declared POS.
   */
  const slots: ReadonlyArray<TranListItemDto | null> = useMemo(
    () => Array.from({ length: ROW_SLOT_COUNT }, (_slot, index) => rows[index] ?? null),
    [rows],
  );
  const pageNumber = shown?.pageNumber ?? FIRST_PAGE;
  const hasNextPage = shown?.nextPage ?? false;
  // The message belongs to the CURRENT turn alone: a stale success message must not
  // reappear beside a fresh failure, so it is never taken from the held page.
  const serverMessage = data?.message ?? '';
  const serverTranIdFirst = shown?.tranIdFirst ?? '';
  const serverTranIdLast = shown?.tranIdLast ?? '';

  // Read the first forward page on entry, from the top of the file.
  useEffect(() => {
    void run({});
  }, [run]);

  const handleFilterChange = useCallback((value: string): void => {
    // ``TRNIDIN DFHMDF ... LENGTH=16`` (COTRN00.bms L95-99): the field cannot hold a
    // seventeenth character, so neither can this one. Applying the limit here rather
    // than relying on ``maxlength`` alone means a pasted or programmatically supplied
    // over-length value is refused at the field instead of being clipped on its way to
    // the browse, so what is submitted is always what is on display.
    setTranIdFilter(limitToFieldWidth(value, TRAN_ID_LENGTH));
  }, []);

  const handleSelectionChange = useCallback((fieldName: string, value: string): void => {
    setSelectionFlags((previous) => ({ ...previous, [fieldName]: value }));
  }, []);

  /**
   * :purpose: ENTER turn. Honour a row selection first — ``COTRN00C`` evaluates
   *     ``SEL0001`` through ``SEL0010`` in order and acts on the first flagged
   *     row that carries a transaction id — then validate the filter and read the
   *     first forward page.
   */
  const handleEnter = useCallback((): void => {
    // The 3270 keyboard is locked from the moment an attention identifier is sent
    // until the next map arrives, so an ENTER pressed while the browse is outstanding
    // is inhibited rather than sent a second time.
    if (isInFlight()) {
      return;
    }
    let selectedFlag = '';
    let selectedTranId = '';
    for (const [index, row] of rows.entries()) {
      const flag = displayText(selectionFlags[selectionFieldName(index)]).trim();
      const rowTranId = displayText(row.tranId).trim();
      if (flag !== '' && rowTranId !== '') {
        selectedFlag = flag;
        selectedTranId = rowTranId;
        break;
      }
    }

    if (selectedFlag !== '') {
      if (selectedFlag.toUpperCase() === SELECTION_VALUE) {
        setValidationMessage('');
        void navigate(`${TRANSACTION_VIEW_ROUTE}${selectedTranId}`);
        return;
      }
      setValidationMessage(MSG_INVALID_SELECTION);
      return;
    }

    // L206-217 edits the field as received: ``EQUAL SPACES OR LOW-VALUES`` browses from
    // LOW-VALUES, and anything else must be NUMERIC. The test is on the raw value, so a
    // tab or a surrounding space is reported rather than trimmed away — trimming first
    // is what let a tab browse the whole file while reporting success, and what let
    // ``" 15 "`` stay on the screen while ``15`` went on the wire.
    const supplied = !isFilterNotSupplied(tranIdFilter);
    if (supplied && !isFilterAllDigits(tranIdFilter)) {
      setValidationMessage(MSG_TRAN_ID_NUMERIC);
      return;
    }
    const filter = supplied ? tranIdFilter : '';

    setValidationMessage('');
    setSelectionFlags({});
    void run(filter === '' ? {} : { tranIdFilter: filter });
  }, [isInFlight, navigate, rows, run, selectionFlags, tranIdFilter]);

  /**
   * :purpose: PF7 — page backward. The key is never withdrawn: ``PROCESS-PF7-KEY``
   *     receives the AID unconditionally and its own ``IF CDEMO-CT00-PAGE-NUM > 1``
   *     branch decides between browsing and re-sending the screen with the
   *     top-boundary message, so the AID is always sent and the service decides.
   * :note: Inhibited while a browse is outstanding (the locked keyboard). The browse
   *     is keyed by the boundary ids of the page CURRENTLY displayed, so accepting a
   *     second press before the first answered would re-send the same key and lose
   *     the step.
   */
  const handleBackward = useCallback((): void => {
    if (isInFlight()) {
      return;
    }
    setValidationMessage('');
    setSelectionFlags({});
    void run({
      action: ACTION_BACKWARD,
      pageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: hasNextPage,
    });
  }, [hasNextPage, isInFlight, pageNumber, run, serverTranIdFirst, serverTranIdLast]);

  /**
   * :purpose: PF8 — page forward. Also never withdrawn: ``PROCESS-PF8-KEY`` tests
   *     ``NEXT-PAGE-YES`` itself and otherwise re-sends the screen carrying the
   *     bottom-boundary message.
   */
  const handleForward = useCallback((): void => {
    if (isInFlight()) {
      return;
    }
    setValidationMessage('');
    setSelectionFlags({});
    void run({
      action: ACTION_FORWARD,
      pageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: hasNextPage,
    });
  }, [hasNextPage, isInFlight, pageNumber, run, serverTranIdFirst, serverTranIdLast]);

  /** :purpose: PF3 — leave the screen for the main menu (legacy ``COMEN01C``). */
  const handleExit = useCallback((): void => {
    void navigate(MENU_ROUTE);
  }, [navigate]);

  // A client-side rejection wins the single line-23 region; otherwise the
  // normalized request error, if the most recent browse failed, is shown.
  let errorMessage = validationMessage !== '' ? validationMessage : requestErrorText(error);
  if (errorMessage === '') {
    errorMessage = serverMessage;
  }
  const infoMessage = '';

  // Every COTRN00C path ends with ``MOVE -1 TO TRNIDINL``, placing the cursor on the
  // browse filter. The screen reads its rows before painting them and disables the
  // entry fields while that read is outstanding, so the placement is driven by each
  // completed map send rather than by mount alone.
  const filterRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, filterRef);
  // A browse boundary or an empty result keeps the RED COTRN00.bms declares statically
  // but is announced politely rather than as an alert. The cursor still returns to the
  // filter for either kind of outcome.
  const noticeMessage = isBrowseNotice(errorMessage) ? errorMessage : '';
  const alertMessage = noticeMessage === '' ? errorMessage : '';

  useFocusOnChange(errorMessage === '' ? null : errorMessage, filterRef);

  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COTRN00C`` L130-134) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map. ``COTRN00.bms`` declares no
   *     ``IC`` field, so the program moves the cursor itself with
   *     ``MOVE -1 TO TRNIDINL``, placing it on the transaction-id filter.
   */
  const handleUnhandledKey = useCallback((): void => {
    setValidationMessage(CCDA_MSG_INVALID_KEY);
    placeCursor(filterRef.current);
  }, [filterRef]);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateEnter = useScreenAction(handleEnter);
  const activateExit = useScreenAction(handleExit);
  const activateBackward = useScreenAction(handleBackward);
  const activateForward = useScreenAction(handleForward);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    // Every key the mapset's line-24 legend declares is registered and stays live:
    // COTRN00C rewrites nothing away, and each handler's own branch — or the service's
    // — decides the outcome, so gating a key here would make that branch unreachable.
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: SUBMIT_LABEL, onActivate: activateEnter },
      { action: PfKeyAction.PF3, label: EXIT_LABEL, onActivate: activateExit },
      { action: PfKeyAction.PF7, label: BACKWARD_LABEL, onActivate: activateBackward },
      { action: PfKeyAction.PF8, label: FORWARD_LABEL, onActivate: activateForward },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage: alertMessage,
      noticeMessage,
      infoMessage,
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy: loading,
    });
  }, [
    activateBackward,
    activateEnter,
    activateExit,
    activateForward,
    activateUnhandledKey,
    alertMessage,
    noticeMessage,
    infoMessage,
    loading,
    setChrome,
  ]);

  return (
    <section className="tranList" aria-label={SCREEN_NAME}>
      <div className="screenTitleLine">
        {/* COTRN00 row 4 declares the screen name COLOR=NEUTRAL, not the YELLOW of
            the two header title lines. */}
        <h3 className="neutral">{SCREEN_NAME}</h3>
        <p className="screenTitleLine__page">
          <span className="prompt">{PAGE_LABEL}</span>{' '}
          <span className="label" data-testid="page-number">
            {pageNumber}
          </span>
        </p>
      </div>

      <div className="tranList__body">
        <div className="tranListSearch">
          <label className="prompt" htmlFor={FILTER_FIELD_NAME}>
            {FILTER_LABEL}
          </label>{' '}
          <input
            ref={filterRef}
            className="field"
            data-testid={FILTER_FIELD_NAME}
            disabled={loading}
            id={FILTER_FIELD_NAME}
            {...invalidFieldProps(validationMessage === MSG_TRAN_ID_NUMERIC)}
            name={FILTER_FIELD_NAME}
            type="text"
            maxLength={TRAN_ID_LENGTH}
            size={TRAN_ID_LENGTH}
            value={tranIdFilter}
            onChange={(event) => handleFilterChange(event.target.value)}
          />
        </div>

        <div
          className="tableScroll"
          role="group"
          aria-label={TABLE_REGION_LABEL}
          tabIndex={0}
        >
        <table
          className="dataTable dataTable--fixed"
          style={{ minWidth: `${String(TABLE_MIN_WIDTH_CH)}ch` }}
        >
          {/*
            The BMS rule runs under each caption ARE the column widths -- COTRN00 row 9
            paints 3 / 16 / 8 / 26 / 12 hyphens -- so they are declared here and the table
            is laid out from them rather than from its content. Content-driven layout made
            every header cell move as rows arrived, changed or emptied, which a 3270 column
            never does. The last column is left to absorb the slack so the right-aligned
            Amount stays against the right edge of the frame, where the mapset puts it;
            the table's own floor width (TABLE_MIN_WIDTH_CH) is what stops that slack from
            being negative on a narrow viewport and collapsing the column to nothing.
          */}
          <colgroup>
            {COLUMNS.map((column, index) => (
              <col
                key={column.caption}
                style={
                  index === COLUMNS.length - 1
                    ? undefined
                    : { width: `${String(column.pitch)}ch` }
                }
              />
            ))}
          </colgroup>
          <thead>
            <tr>
              {COLUMNS.map((column) => (
                <th key={column.caption} scope="col" className={column.className}>
                  {column.caption}
                </th>
              ))}
            </tr>
            {/*
              COTRN00 row 9 paints a run of hyphens under each caption -- 3, 16, 8, 26
              and 12 characters at columns 2, 8, 27, 38 and 67 -- with the gaps between
              them left blank. The row is decoration, so it is hidden from assistive
              technology and takes no part in the header associations.
            */}
            <tr className="dataTable__rule" aria-hidden="true">
              {COLUMNS.map((column) => (
                <td key={column.caption} className={column.className}>
                  {column.rule}
                </td>
              ))}
            </tr>
          </thead>
          <tbody>
            {/*
              All ten slots are rendered on every send, carrying data or blank. COTRN00C
              paints no "nothing found" literal into the body -- it leaves the ten row
              fields at LOW-VALUES and publishes its message on line 23 -- so a blank slot
              renders as blank cells rather than as an invented placeholder row. The
              ``SEL000n`` field of a blank slot stays enterable because the mapset leaves
              it unprotected, and a flag typed there is ignored exactly as it is on the
              3270: the program's selection branch requires the row's ``TRNID0n`` to be
              non-blank as well, so it neither navigates nor faults.
            */}
            {slots.map((row: TranListItemDto | null, index: number) => {
                const fieldName = selectionFieldName(index);
                const flag = displayText(selectionFlags[fieldName]);
                const selectable = row !== null && displayText(row.tranId).trim() !== '';
                return (
                  <tr
                    key={fieldName}
                    className={selectable && flag.trim() !== '' ? 'selected' : undefined}
                  >
                    <td>
                      {/* One-character field per the BMS ``SEL000n`` ``PIC X(01)``
                          layout; the shared stylesheet enlarges its hit area to the
                          adopted 24x24 WCAG 2.5.8 minimum. */}
                      <input
                        className="field"
                        data-testid={`tran-select-${String(index + 1)}`}
                        disabled={loading}
                        id={fieldName}
                        name={fieldName}
                        type="text"
                        aria-label={`Select transaction in row ${String(index + 1)}`}
                        {...invalidValueProps(selectable && isRowSelectionInvalid(flag))}
                        maxLength={SELECTION_LENGTH}
                        size={SELECTION_LENGTH}
                        value={flag}
                        onChange={(event) => handleSelectionChange(fieldName, event.target.value)}
                      />
                    </td>
                    <td>{row === null ? '' : displayText(row.tranId)}</td>
                    <td>{row === null ? '' : displayText(row.tranDate)}</td>
                    <td>{row === null ? '' : displayText(row.tranDesc)}</td>
                    <td className="amount">{row === null ? '' : displayText(row.tranAmt)}</td>
                  </tr>
                );
            })}
          </tbody>
        </table>
        </div>

      </div>

      <p className="neutral screenNote">{SELECTION_HINT}</p>
    </section>
  );
}
