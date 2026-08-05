/**
 * CardListPage
 * ============
 *
 * :purpose: Paginated credit-card browse screen. Replaces the BMS mapset
 *     ``app/bms/COCRDLI.bms`` (CICS transaction ``CCLI``, program
 *     ``app/cbl/COCRDLIC.cbl``): the ``ACCTSID`` / ``CARDSID`` browse filters,
 *     the seven-rows-per-page card table with its one-character row-action
 *     column, and the ``F3`` / ``F7`` / ``F8`` function keys.
 * :output: The screen body only. The transaction id, program name, titles,
 *     line-23 message and line-24 function keys are published into the shared
 *     terminal shell through :func:`useScreenChrome`, so this page renders no
 *     ``Header``, ``ErrorBanner`` or ``PFKeyBar`` of its own.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps, invalidValueProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  CardListItemDto,
  CardListRequestDto,
  CardListResponseDto,
} from '../types';
import { listCards } from '../api';
import { useApi, useFocusOnChange, useFocusOnSettled, useInitialFocus } from '../hooks';
import { CARD_DETAIL_ROUTE, CARD_UPDATE_ROUTE } from './cardSelection';

/** Width of the account filter (``COCRDLI`` ``ACCTSIDI PIC X(11)``). */
const ACCOUNT_FILTER_LENGTH = 11;

/** Width of the card filter (``COCRDLI`` ``CARDSIDI PIC X(16)``). */
const CARD_FILTER_LENGTH = 16;

/** Width of a row-action field (``COCRDLI`` ``CRDSEL1I..CRDSEL7I PIC X(1)``). */
const ROW_ACTION_LENGTH = 1;

/** The accepted row-action code (``COCRDLIC`` ``SELECT-OK``). */
const SELECT_ACTION_VIEW = 'S';

/** ``COCRDLIC`` ``88 UPDATE-REQUESTED-ON VALUE 'U'`` (``COCRDLIC.cbl:L79``). */
const SELECT_ACTION_UPDATE = 'U';

/** First page of the browse, matching the one-based ``PAGENO`` field of the mapset. */
const FIRST_PAGE = 1;

/**
 * :purpose: Name of the ENTER action. The ``COCRDLI`` line-24 legend does not display
 *     it, so the legend is darkened, but the label still names the key for assistive
 *     technology should the screen ever un-darken it.
 */
const PF_ENTER_LABEL = 'ENTER=Continue';

/** ``COCRDLIC`` ``FLG-ACCTFILTER-NOT-OK`` message (``COCRDLIC.cbl:L1022``). */
const ACCOUNT_FILTER_MESSAGE =
  'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/** ``COCRDLIC`` ``FLG-CARDFILTER-NOT-OK`` message (``COCRDLIC.cbl:L1058``). */
const CARD_FILTER_MESSAGE =
  'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/** ``COCRDLIC`` ``WS-MORE-THAN-1-ACTION`` message (``COCRDLIC.cbl:L124``). */
const MULTIPLE_ACTIONS_MESSAGE =
  'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE';

/** ``COCRDLIC`` ``WS-INVALID-ACTION-CODE`` message (``COCRDLIC.cbl:L126``). */
const INVALID_ACTION_MESSAGE = 'INVALID ACTION CODE';

/**
 * :purpose: Text of the single row rendered when the browse returned no cards, so the
 *     table never presents an empty body to a screen reader.
 */
const EMPTY_ROW_TEXT = 'No cards to display';

/**
 * :purpose: Whether a row's action code is one the source rejects — anything other
 *     than blank, ``'S'`` or ``'U'`` (``COCRDLIC`` ``2250-EDIT-ARRAY`` ``WHEN OTHER``).
 * :param action: the row's entered action code, when it has one.
 * :returns: ``true`` when the code would raise ``INVALID ACTION CODE``.
 */
function isRowActionInvalid(action: string | undefined): boolean {
  const canonical = (action ?? '').trim().toUpperCase();
  return (
    canonical !== '' &&
    canonical !== SELECT_ACTION_VIEW &&
    canonical !== SELECT_ACTION_UPDATE
  );
}

/**
 * :purpose: Whether a supplied browse filter is acceptable, reproducing the
 *     COBOL ``IS NOT NUMERIC`` edit of ``2210-EDIT-ACCOUNT`` /
 *     ``2220-EDIT-CARD``, which tests the whole ``PIC X(n)`` field and so
 *     requires every one of its ``n`` positions to hold a digit.
 * :param value: the trimmed filter value.
 * :param length: the legacy field width.
 * :returns: ``true`` when the filter may be sent to the browse.
 */
function isFilterNumeric(value: string, length: number): boolean {
  return value.length === length && /^[0-9]+$/.test(value);
}

/**
 * :purpose: Build the card-list browse request, omitting a filter that was left
 *     blank so it is never sent as an empty query parameter.
 * :param accountFilter: the trimmed account filter, or an empty string.
 * :param cardFilter: the trimmed card-number filter, or an empty string.
 * :returns: the request for :func:`listCards`.
 */
function buildBrowseRequest(
  accountFilter: string,
  cardFilter: string,
  page: number,
  aid?: 'PF7' | 'PF8',
): CardListRequestDto {
  return {
    accountId: accountFilter === '' ? undefined : accountFilter,
    cardNum: cardFilter === '' ? undefined : cardFilter,
    page,
    aid,
  };
}

/**
 * :purpose: Render the card list screen — the two browse filters, the card table
 *     whose rows and page number come from the server's browse response, and the
 *     row-action column that opens a card's detail or update screen.
 * :returns: The rendered screen body.
 */
export default function CardListPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  const [accountId, setAccountId] = useState('');
  const [cardNumber, setCardNumber] = useState('');
  const [screenMessage, setScreenMessage] = useState('');
  const [rowActions, setRowActions] = useState<Record<string, string>>({});

  const { data, error, loading, run } = useApi<CardListResponseDto, [CardListRequestDto]>(
    listCards,
  );
  // The service returns one screen of at most seven rows together with its own paging
  // state, so the rows are rendered as received and never re-sliced here.
  const pageRows: CardListItemDto[] = useMemo(() => data?.cards ?? [], [data]);
  const pageNumber = data?.pageNumber ?? FIRST_PAGE;
  const hasNextPage = data?.nextPage === true;

  // First entry into the transaction browses the card file from the start with
  // no filters. `run` is stable for a stable api function, so this fires once.
  useEffect(() => {
    void run(buildBrowseRequest('', '', FIRST_PAGE));
  }, [run]);

  const handleRowActionChange = useCallback(
    (cardNum: string, action: string): void => {
      setRowActions((previous) => ({ ...previous, [cardNum]: action }));
    },
    [],
  );

  /**
   * :purpose: Process one ENTER, reproducing ``COCRDLIC`` ``2000-PROCESS-INPUTS``
   *     — edit the account filter, then the card filter, then the row actions of
   *     the displayed page, and finally either open the selected card or
   *     re-drive the browse from its first page.
   * :returns: nothing; the outcome is the line-23 message, a navigation, or a
   *     refreshed result set.
   */
  const submitScreen = useCallback((): void => {
    const accountFilter = accountId.trim();
    const cardFilter = cardNumber.trim();

    if (
      accountFilter !== '' &&
      !isFilterNumeric(accountFilter, ACCOUNT_FILTER_LENGTH)
    ) {
      setScreenMessage(ACCOUNT_FILTER_MESSAGE);
      return;
    }

    if (cardFilter !== '' && !isFilterNumeric(cardFilter, CARD_FILTER_LENGTH)) {
      setScreenMessage(CARD_FILTER_MESSAGE);
      return;
    }

    // Only the rows currently on the screen carry action codes, exactly as the
    // seven CRDSELn fields of the mapset did.
    const enteredActions = pageRows
      .map((row) => ({
        cardNum: row.cardNum,
        cardAcctId: row.cardAcctId,
        action: (rowActions[row.cardNum] ?? '').trim(),
      }))
      .filter((entry) => entry.action !== '');
    // 2250-EDIT-ARRAY tallies BOTH 'S' and 'U' when counting selections, and either
    // flag is a valid action code.
    const selectedRows = enteredActions.filter((entry) => {
      const canonical = entry.action.toUpperCase();
      return canonical === SELECT_ACTION_VIEW || canonical === SELECT_ACTION_UPDATE;
    });

    // ``2250-EDIT-ARRAY``: the multi-selection guard is raised before the
    // per-row action-code edit, and either one blocks the transfer.
    if (selectedRows.length > 1) {
      setScreenMessage(MULTIPLE_ACTIONS_MESSAGE);
      return;
    }

    if (selectedRows.length < enteredActions.length) {
      setScreenMessage(INVALID_ACTION_MESSAGE);
      return;
    }

    if (selectedRows.length === 1) {
      setScreenMessage('');
      const [selected] = selectedRows;
      // 'S' transfers to COCRDSLC and 'U' to COCRDUPC, each carrying the row's
      // account id and card number. The composite selection travels in the router
      // location state, so the card number never reaches the address bar or history.
      const target =
        selected.action.toUpperCase() === SELECT_ACTION_UPDATE
          ? CARD_UPDATE_ROUTE
          : CARD_DETAIL_ROUTE;
      void navigate(target, {
        state: { cardNumber: selected.cardNum, accountId: selected.cardAcctId },
      });
      return;
    }

    // A fresh browse re-reads the rows and re-sends the screen with its seven
    // action fields blank, from the first page.
    setScreenMessage('');
    setRowActions({});
    void run(buildBrowseRequest(accountFilter, cardFilter, FIRST_PAGE));
  }, [accountId, cardNumber, navigate, pageRows, rowActions, run]);

  /**
   * :purpose: PF7 — page backward. The key is always live: ``COCRDLIC`` reaches the
   *     program on every declared AID and its own ``WHEN CCARD-AID-PFK07 AND
   *     CA-FIRST-PAGE`` branch re-reads the same page, whereupon
   *     ``1400-SETUP-MESSAGE`` publishes ``NO PREVIOUS PAGES TO DISPLAY``. The AID is
   *     therefore sent with the browse and the service decides the banner.
   */
  const pageBackward = useCallback((): void => {
    setScreenMessage('');
    setRowActions({});
    const target = pageNumber <= FIRST_PAGE ? FIRST_PAGE : pageNumber - 1;
    void run(buildBrowseRequest(accountId.trim(), cardNumber.trim(), target, 'PF7'));
  }, [accountId, cardNumber, pageNumber, run]);

  /**
   * :purpose: PF8 — page forward. Also always live: with no further page the source
   *     falls through to ``WHEN OTHER`` and re-reads the page already displayed, so the
   *     rows stay on screen and the service publishes the browse-boundary banner.
   */
  const pageForward = useCallback((): void => {
    setScreenMessage('');
    setRowActions({});
    const target = hasNextPage ? pageNumber + 1 : pageNumber;
    void run(buildBrowseRequest(accountId.trim(), cardNumber.trim(), target, 'PF8'));
  }, [accountId, cardNumber, hasNextPage, pageNumber, run]);

  // Line-23 message precedence, reproducing the source's single WS-ERROR-MSG and the
  // IF WS-ERROR-MSG-OFF first-message-wins guard: the client-side input edits first,
  // then a failed request, then the banner 1400-SETUP-MESSAGE resolved for this browse.
  let errorMessage = screenMessage;
  if (errorMessage === '' && error !== null) {
    errorMessage = error.message;
  }
  if (errorMessage === '' && data !== null) {
    errorMessage = data.message ?? '';
  }

  // WS-INFO-MSG is suppressed while WS-ERROR-MSG carries text, and while the browse
  // found nothing at all (1400-SETUP-MESSAGE's NOT WS-NO-RECORDS-FOUND guard).
  const infoMessage = errorMessage === '' ? (data?.infoMessage ?? '') : '';

  // COCRDLI marks ACCTSID ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor starts there
  // and returns there whenever the screen reports a new outcome. This screen reads its
  // rows before painting them and disables the entry fields while that read is in
  // flight, so the placement is driven by each completed map send — the first one and
  // every PF7 / PF8 browse alike — rather than by mount alone.
  const accountFilterRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, accountFilterRef);
  useFocusOnChange(errorMessage === '' ? null : errorMessage, accountFilterRef);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      // COCRDLI's line-24 legend carries no ENTER text, but CCARD-AID-ENTER is what
      // drives the row selection, so the AID is declared with its legend darkened.
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        dark: true,
        onActivate: submitScreen,
      },
      {
        action: PfKeyAction.PF3,
        label: 'F3=Exit',
        onActivate: () => {
          void navigate('/menu');
        },
      },
      // Both paging keys are declared by the mapset's static line-24 legend and are
      // never withdrawn: the program itself decides between browsing and publishing a
      // boundary banner, so gating them here would make that decision unreachable.
      {
        action: PfKeyAction.PF7,
        label: 'F7=Backward',
        onActivate: pageBackward,
      },
      {
        action: PfKeyAction.PF8,
        label: 'F8=Forward',
        onActivate: pageForward,
      },
    ];
    setChrome({
      transactionId: 'CCLI',
      programName: 'COCRDLIC',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      busy: loading,
    });
  }, [
    errorMessage,
    infoMessage,
    loading,
    navigate,
    pageBackward,
    pageForward,
    setChrome,
    submitScreen,
  ]);

  return (
    <section aria-labelledby="card-list-heading">
      <div className="screenTitleLine">
        <h2 className="neutral" id="card-list-heading">
          List Credit Cards
        </h2>
        <p className="neutral screenTitleLine__page" data-testid="page-number">
          Page {pageNumber}
        </p>
      </div>
      <div className="cardList__body">
        <div>
          <label className="prompt" htmlFor="acctsid">
            {'Account Number    :'}
          </label>
          <input
            className="field"
            data-testid="acctsid"
            disabled={loading}
            id="acctsid"
            {...invalidFieldProps(screenMessage === ACCOUNT_FILTER_MESSAGE)}
            maxLength={ACCOUNT_FILTER_LENGTH}
            name="acctsid"
            onChange={(event) => {
              setAccountId(event.target.value);
            }}
            ref={accountFilterRef}
            size={ACCOUNT_FILTER_LENGTH}
            type="text"
            value={accountId}
          />
        </div>
        <div>
          <label className="prompt" htmlFor="cardsid">
            {'Credit Card Number:'}
          </label>
          <input
            className="field"
            data-testid="cardsid"
            disabled={loading}
            id="cardsid"
            {...invalidFieldProps(screenMessage === CARD_FILTER_MESSAGE)}
            maxLength={CARD_FILTER_LENGTH}
            name="cardsid"
            onChange={(event) => {
              setCardNumber(event.target.value);
            }}
            size={CARD_FILTER_LENGTH}
            type="text"
            value={cardNumber}
          />
        </div>
        <table className="dataTable" data-testid="card-list-table">
          <thead>
            <tr>
              <th scope="col">Select</th>
              <th scope="col">Account Number</th>
              <th scope="col">{' Card Number'}</th>
              <th scope="col">Active</th>
            </tr>
          </thead>
          <tbody>
            {pageRows.length === 0 && (
              <tr data-testid="card-list-empty-row">
                <td colSpan={4} className="neutral">
                  {EMPTY_ROW_TEXT}
                </td>
              </tr>
            )}
            {pageRows.map((row, rowIndex) => (
              <tr data-testid="card-list-row" key={row.cardNum}>
                <td>
                  <input
                    {...invalidValueProps(isRowActionInvalid(rowActions[row.cardNum]))}
                    aria-label={'Select card in row ' + String(rowIndex + 1)}
                    className={
                      isRowActionInvalid(rowActions[row.cardNum])
                        ? 'field fieldError'
                        : 'field'
                    }
                    data-testid={'card-select-' + String(rowIndex + 1)}
                    disabled={loading}
                    id={'crdsel' + String(rowIndex + 1)}
                    maxLength={ROW_ACTION_LENGTH}
                    name={'crdsel' + String(rowIndex + 1)}
                    onChange={(event) => {
                      handleRowActionChange(row.cardNum, event.target.value);
                    }}
                    size={ROW_ACTION_LENGTH}
                    type="text"
                    value={rowActions[row.cardNum] ?? ''}
                  />
                </td>
                <td>{row.cardAcctId}</td>
                <td>{row.cardNum}</td>
                <td>{row.cardActiveStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
