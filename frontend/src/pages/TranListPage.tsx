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
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { TranListItemDto } from '../types';
import { listTransactions, ApiError } from '../api';
import { useApi, usePagination, TRANSACTION_LIST_PAGE_SIZE } from '../hooks';

/** CICS transaction identifier of the legacy screen. */
const TRANSACTION_ID = 'CT00';

/** Legacy program name of the screen. */
const PROGRAM_NAME = 'COTRN00C';

/** First header title line. */
const TITLE01 = 'CardDemo';

/** Second header title line, which is also the screen heading. */
const TITLE02 = 'List Transactions';

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

/** Empty-result text shown in place of the ten row lines. */
const MSG_NO_RECORDS = 'NO RECORDS FOUND';

/** Static line-21 instruction literal of the mapset. */
const SELECTION_HINT = "Type 'S' to View Transaction details from the list";

/** Static ``Page:`` caption of the mapset (line 4). */
const PAGE_LABEL = 'Page:';

/** ENTER entry of the mapset line-24 legend, carried by the submit control. */
const SUBMIT_LABEL = 'ENTER=Continue';

/** Server action indicator for a page-backward (PF7) browse. */
const ACTION_BACKWARD = 'PF7';

/** Server action indicator for a page-forward (PF8) browse. */
const ACTION_FORWARD = 'PF8';

/** Route of the main menu, reached with PF3 (legacy ``XCTL`` to ``COMEN01C``). */
const MENU_ROUTE = '/menu';

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
 * :purpose: Render a wire value as display text without ever surfacing ``null``
 *     or ``undefined`` to the user.
 * :param value: the value carried by the list-row DTO.
 * :returns: the value, or the empty string when it is absent.
 */
function displayText(value: string | null | undefined): string {
  return value ?? '';
}

/**
 * :purpose: Render the amount column of a row.
 * :param value: the amount as carried by the list-row DTO — a ``NUMERIC(11,2)``
 *     decimal string, or the same value deserialized as a JSON number.
 * :returns: a string value verbatim; a numeric value at the declared two-decimal
 *     scale, so the cents of a whole amount are never dropped.
 */
function amountText(value: string | number | null | undefined): string {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value.toFixed(2) : '';
  }
  return displayText(value);
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

  const { data, error, run } = useApi(listTransactions);

  const rows: TranListItemDto[] = data?.transactions ?? [];
  const serverPageNumber = data?.pageNumber ?? 0;
  const serverHasNextPage = data?.nextPage ?? false;
  const serverMessage = data?.message ?? '';
  const serverTranIdFirst = data?.tranIdFirst ?? '';
  const serverTranIdLast = data?.tranIdLast ?? '';

  const {
    page: localPage,
    pageRows,
    hasPrevious,
    hasNext,
    prevPage,
    nextPage,
    reset: resetLocalPage,
  } = usePagination(rows, TRANSACTION_LIST_PAGE_SIZE);

  // PF7 / PF8 stay enabled while either the client slice or the server browse
  // still has a page in that direction.
  const canPageBackward = hasPrevious || serverPageNumber > 1;
  const canPageForward = hasNext || serverHasNextPage;

  // Displayed page number: the server page carrying the rows, advanced by the
  // client slice when a caller supplied more than one page of rows at once.
  const displayedPageNumber = (serverPageNumber > 0 ? serverPageNumber : 1) + localPage;

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
    for (const [index, row] of pageRows.entries()) {
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
        navigate(`${TRANSACTION_VIEW_ROUTE}${selectedTranId}`);
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
    resetLocalPage();
    void run(filter === '' ? {} : { tranIdFilter: filter });
  }, [navigate, pageRows, resetLocalPage, run, selectionFlags, tranIdFilter]);

  /** :purpose: PF7 — page backward within the slice, else browse the previous server page. */
  const handleBackward = useCallback((): void => {
    setValidationMessage('');
    setSelectionFlags({});
    if (hasPrevious) {
      prevPage();
      return;
    }
    resetLocalPage();
    void run({
      action: ACTION_BACKWARD,
      pageNumber: serverPageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: serverHasNextPage,
    });
  }, [
    hasPrevious,
    prevPage,
    resetLocalPage,
    run,
    serverHasNextPage,
    serverPageNumber,
    serverTranIdFirst,
    serverTranIdLast,
  ]);

  /** :purpose: PF8 — page forward within the slice, else browse the next server page. */
  const handleForward = useCallback((): void => {
    setValidationMessage('');
    setSelectionFlags({});
    if (hasNext) {
      nextPage();
      return;
    }
    resetLocalPage();
    void run({
      action: ACTION_FORWARD,
      pageNumber: serverPageNumber,
      tranIdFirst: serverTranIdFirst,
      tranIdLast: serverTranIdLast,
      nextPage: serverHasNextPage,
    });
  }, [
    hasNext,
    nextPage,
    resetLocalPage,
    run,
    serverHasNextPage,
    serverPageNumber,
    serverTranIdFirst,
    serverTranIdLast,
  ]);

  /** :purpose: PF3 — leave the screen for the main menu (legacy ``COMEN01C``). */
  const handleExit = useCallback((): void => {
    navigate(MENU_ROUTE);
  }, [navigate]);

  // A client-side rejection wins the single line-23 region; otherwise the
  // normalized request error, if the most recent browse failed, is shown.
  const errorMessage =
    validationMessage !== '' ? validationMessage : requestErrorText(error);
  const infoMessage = errorMessage === '' ? serverMessage : '';

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
      {
        action: PfKeyAction.PF7,
        label: 'F7=Backward',
        onActivate: handleBackward,
        enabled: canPageBackward,
      },
      {
        action: PfKeyAction.PF8,
        label: 'F8=Forward',
        onActivate: handleForward,
        enabled: canPageForward,
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
    canPageBackward,
    canPageForward,
    errorMessage,
    handleBackward,
    handleExit,
    handleForward,
    infoMessage,
    setChrome,
  ]);

  return (
    <section className="tranList" aria-label={TITLE02}>
      <h2 className="title">{TITLE02}</h2>

      <div className="tranListPage">
        <span className="prompt">{PAGE_LABEL}</span>{' '}
        <span className="label" data-testid="page-number">
          {displayedPageNumber}
        </span>
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          handleEnter();
        }}
      >
        <div className="tranListSearch">
          <label className="prompt" htmlFor={FILTER_FIELD_NAME}>
            {FILTER_LABEL}
          </label>{' '}
          <input
            className="field"
            id={FILTER_FIELD_NAME}
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
            {pageRows.length === 0 ? (
              <tr>
                <td colSpan={COLUMNS.length} data-testid="tran-list-empty">
                  {MSG_NO_RECORDS}
                </td>
              </tr>
            ) : (
              pageRows.map((row: TranListItemDto, index: number) => {
                const fieldName = selectionFieldName(index);
                const flag = displayText(selectionFlags[fieldName]);
                const tranId = displayText(row.tranId);
                return (
                  <tr key={fieldName} className={flag.trim() === '' ? undefined : 'selected'}>
                    <td>
                      {/* BLITZY [A11Y]: one-character field per the BMS ``SEL000n``
                          ``PIC X(01)`` layout; its target is below the WCAG 2.5.8
                          minimum. Flagged for designer review. */}
                      <input
                        className="field"
                        id={fieldName}
                        name={fieldName}
                        type="text"
                        aria-label={`${COLUMNS[0].caption} ${tranId}`}
                        maxLength={SELECTION_LENGTH}
                        size={SELECTION_LENGTH}
                        value={flag}
                        onChange={(event) => handleSelectionChange(fieldName, event.target.value)}
                      />
                    </td>
                    <td>{tranId}</td>
                    <td>{displayText(row.tranDate)}</td>
                    <td>{displayText(row.tranDesc)}</td>
                    <td className="amount">{amountText(row.tranAmt)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>

        <button type="submit">{SUBMIT_LABEL}</button>
      </form>

      <p className="neutral">{SELECTION_HINT}</p>
    </section>
  );
}
