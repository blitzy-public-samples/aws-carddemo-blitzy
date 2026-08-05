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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps, invalidValueProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';

/**
 * Row-4 screen name of ``app/bms/COTRN00.bms``, rendered as the screen's own
 * body heading. The two header title lines are the shared ``COTTL01Y`` pair.
 */
const SCREEN_NAME = SCREEN_NAMES.COTRN00;
import type { TranListItemDto } from '../types';
import { listTransactions, ApiError } from '../api';
import { useApi, useFocusOnChange, useFocusOnSettled, useInitialFocus } from '../hooks';
import { displayText } from '../components/display';

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

/** The only accepted row-selection flag; ``COTRN00C`` accepts ``'S'`` or ``'s'``. */
const SELECTION_VALUE = 'S';

/**
 * :purpose: Row-table columns in ``COTRN00.bms`` order, each with the alignment
 *     class it carries; the amount column is right-aligned like its ``TAMT00n``
 *     numeric-edited source field.
 */
const COLUMNS: ReadonlyArray<{ caption: string; className?: string }> = [
  { caption: 'Sel' },
  { caption: 'Transaction ID' },
  { caption: 'Date' },
  { caption: 'Description' },
  { caption: 'Amount', className: 'amount' },
];

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

/**
 * Placeholder occupying the row region when the browse returns nothing. The line-23
 * banner is the only carrier of a legacy message, so this text is deliberately not
 * shaped like one.
 */
const EMPTY_ROW_TEXT = 'No transactions to display';

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

/** A COBOL ``IS NUMERIC`` class test on the filter: digits only, no sign. */
const NUMERIC_PATTERN = /^\d+$/;

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

  const { data, error, loading, run } = useApi(listTransactions);

  // COTRN00C browses the file itself and returns exactly one screen of rows, so the
  // page shown is the page the service reports; there is no second, client-side slice.
  const rows: TranListItemDto[] = useMemo(() => data?.transactions ?? [], [data]);
  const pageNumber = data?.pageNumber ?? FIRST_PAGE;
  const hasNextPage = data?.nextPage ?? false;
  const serverMessage = data?.message ?? '';
  const serverTranIdFirst = data?.tranIdFirst ?? '';
  const serverTranIdLast = data?.tranIdLast ?? '';

  // Read the first forward page on entry, from the top of the file.
  useEffect(() => {
    void run({});
  }, [run]);

  const handleFilterChange = useCallback((value: string): void => {
    setTranIdFilter(value);
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

    const filter = tranIdFilter.trim();
    if (filter !== '' && !NUMERIC_PATTERN.test(filter)) {
      setValidationMessage(MSG_TRAN_ID_NUMERIC);
      return;
    }

    setValidationMessage('');
    setSelectionFlags({});
    void run(filter === '' ? {} : { tranIdFilter: filter });
  }, [navigate, rows, run, selectionFlags, tranIdFilter]);

  /**
   * :purpose: PF7 — page backward. The key is never withdrawn: ``PROCESS-PF7-KEY``
   *     receives the AID unconditionally and its own ``IF CDEMO-CT00-PAGE-NUM > 1``
   *     branch decides between browsing and re-sending the screen with the
   *     top-boundary message, so the AID is always sent and the service decides.
   */
  const handleBackward = useCallback((): void => {
    setValidationMessage('');
    setSelectionFlags({});
    void run({
      action: ACTION_BACKWARD,
      pageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: hasNextPage,
    });
  }, [hasNextPage, pageNumber, run, serverTranIdFirst, serverTranIdLast]);

  /**
   * :purpose: PF8 — page forward. Also never withdrawn: ``PROCESS-PF8-KEY`` tests
   *     ``NEXT-PAGE-YES`` itself and otherwise re-sends the screen carrying the
   *     bottom-boundary message.
   */
  const handleForward = useCallback((): void => {
    setValidationMessage('');
    setSelectionFlags({});
    void run({
      action: ACTION_FORWARD,
      pageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: hasNextPage,
    });
  }, [hasNextPage, pageNumber, run, serverTranIdFirst, serverTranIdLast]);

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
  useFocusOnChange(errorMessage === '' ? null : errorMessage, filterRef);

  useEffect(() => {
    // Every key the mapset's line-24 legend declares is registered and stays live:
    // COTRN00C rewrites nothing away, and each handler's own branch — or the service's
    // — decides the outcome, so gating a key here would make that branch unreachable.
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: SUBMIT_LABEL, onActivate: handleEnter },
      { action: PfKeyAction.PF3, label: EXIT_LABEL, onActivate: handleExit },
      { action: PfKeyAction.PF7, label: BACKWARD_LABEL, onActivate: handleBackward },
      { action: PfKeyAction.PF8, label: FORWARD_LABEL, onActivate: handleForward },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      busy: loading,
    });
  }, [
    errorMessage,
    handleBackward,
    handleEnter,
    handleExit,
    handleForward,
    infoMessage,
    loading,
    setChrome,
  ]);

  return (
    <section className="tranList" aria-label={SCREEN_NAME}>
      <div className="screenTitleLine">
        <h2 className="title">{SCREEN_NAME}</h2>
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

        <table className="dataTable">
          <thead>
            <tr>
              {COLUMNS.map((column) => (
                <th key={column.caption} scope="col" className={column.className}>
                  {column.caption}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td className="neutral" colSpan={COLUMNS.length} data-testid="tran-list-empty">
                  {EMPTY_ROW_TEXT}
                </td>
              </tr>
            ) : (
              rows.map((row: TranListItemDto, index: number) => {
                const fieldName = selectionFieldName(index);
                const flag = displayText(selectionFlags[fieldName]);
                const tranId = displayText(row.tranId);
                return (
                  <tr key={fieldName} className={flag.trim() === '' ? undefined : 'selected'}>
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
                        {...invalidValueProps(isRowSelectionInvalid(flag))}
                        maxLength={SELECTION_LENGTH}
                        size={SELECTION_LENGTH}
                        value={flag}
                        onChange={(event) => handleSelectionChange(fieldName, event.target.value)}
                      />
                    </td>
                    <td>{tranId}</td>
                    <td>{displayText(row.tranDate)}</td>
                    <td>{displayText(row.tranDesc)}</td>
                    <td className="amount">{displayText(row.tranAmt)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>

      </div>

      <p className="neutral screenNote">{SELECTION_HINT}</p>
    </section>
  );
}
