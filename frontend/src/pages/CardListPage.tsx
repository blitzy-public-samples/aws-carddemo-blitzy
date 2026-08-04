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
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type {
  CardListItemDto,
  CardListRequestDto,
  CardListResponseDto,
} from '../types';
import { listCards } from '../api';
import { useApi, usePagination, CARD_LIST_PAGE_SIZE } from '../hooks';

/** Width of the account filter (``COCRDLI`` ``ACCTSIDI PIC X(11)``). */
const ACCOUNT_FILTER_LENGTH = 11;

/** Width of the card filter (``COCRDLI`` ``CARDSIDI PIC X(16)``). */
const CARD_FILTER_LENGTH = 16;

/** Width of a row-action field (``COCRDLI`` ``CRDSEL1I..CRDSEL7I PIC X(1)``). */
const ROW_ACTION_LENGTH = 1;

/** The accepted row-action code (``COCRDLIC`` ``SELECT-OK``). */
const SELECT_ACTION_VIEW = 'S';

/** ``COCRDLIC`` ``FLG-ACCTFILTER-NOT-OK`` message (``COCRDLIC.cbl:L1022``). */
const ACCOUNT_FILTER_MESSAGE =
  'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/** ``COCRDLIC`` ``FLG-CARDFILTER-NOT-OK`` message (``COCRDLIC.cbl:L1058``). */
const CARD_FILTER_MESSAGE =
  'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/** ``COCRDLIC`` ``WS-NO-RECORDS-FOUND`` message (``COCRDLIC.cbl:L122``). */
const NO_RECORDS_MESSAGE = 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.';

/** ``COCRDLIC`` ``WS-MORE-THAN-1-ACTION`` message (``COCRDLIC.cbl:L124``). */
const MULTIPLE_ACTIONS_MESSAGE =
  'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE';

/** ``COCRDLIC`` ``WS-INVALID-ACTION-CODE`` message (``COCRDLIC.cbl:L126``). */
const INVALID_ACTION_MESSAGE = 'INVALID ACTION CODE';

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
): CardListRequestDto {
  return {
    accountId: accountFilter === '' ? undefined : accountFilter,
    cardNum: cardFilter === '' ? undefined : cardFilter,
  };
}

/**
 * :purpose: Render the card list screen — the two browse filters, the card
 *     table paged at :data:`CARD_LIST_PAGE_SIZE` rows, and the row-action
 *     column that opens a card's detail screen.
 * :returns: The rendered screen body.
 */
export default function CardListPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  const [accountId, setAccountId] = useState('');
  const [cardNumber, setCardNumber] = useState('');
  const [screenMessage, setScreenMessage] = useState('');
  const [rowActions, setRowActions] = useState<Record<string, string>>({});

  const { data, error, run } = useApi<CardListResponseDto, [CardListRequestDto]>(
    listCards,
  );
  const items: CardListItemDto[] = data?.cards ?? [];
  const { page, pageRows, hasPrevious, hasNext, prevPage, nextPage, reset } =
    usePagination(items, CARD_LIST_PAGE_SIZE);

  // First entry into the transaction browses the card file from the start with
  // no filters. `run` is stable for a stable api function, so this fires once.
  useEffect(() => {
    void run(buildBrowseRequest('', ''));
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
        action: (rowActions[row.cardNum] ?? '').trim(),
      }))
      .filter((entry) => entry.action !== '');
    const selectedRows = enteredActions.filter(
      (entry) => entry.action.toUpperCase() === SELECT_ACTION_VIEW,
    );

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
      navigate('/cards/' + selectedRows[0].cardNum);
      return;
    }

    // A fresh browse re-reads the rows and re-sends the screen with its seven
    // action fields blank, from the first page.
    setScreenMessage('');
    setRowActions({});
    reset();
    void run(buildBrowseRequest(accountFilter, cardFilter));
  }, [accountId, cardNumber, navigate, pageRows, reset, rowActions, run]);

  // Line-23 message precedence: the input edits first, then a failed request,
  // then the empty-result condition of a completed browse.
  let errorMessage = screenMessage;
  if (errorMessage === '' && error !== null) {
    errorMessage = error.message;
  }
  if (errorMessage === '' && data !== null && items.length === 0) {
    errorMessage = NO_RECORDS_MESSAGE;
  }

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.PF3,
        label: 'F3=Exit',
        onActivate: () => {
          navigate('/menu');
        },
      },
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
      transactionId: 'CCLI',
      programName: 'COCRDLIC',
      title01: 'CardDemo',
      title02: 'List Credit Cards',
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [
    errorMessage,
    hasNext,
    hasPrevious,
    navigate,
    nextPage,
    prevPage,
    setChrome,
  ]);

  return (
    <section aria-labelledby="card-list-heading">
      <div>
        <h2 className="neutral" id="card-list-heading">
          List Credit Cards
        </h2>
        <p className="neutral" data-testid="page-number">
          Page {page + 1}
        </p>
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submitScreen();
        }}
      >
        <div>
          <label className="prompt" htmlFor="acctsid">
            {'Account Number    :'}
          </label>
          <input
            className="field"
            data-testid="acctsid"
            id="acctsid"
            maxLength={ACCOUNT_FILTER_LENGTH}
            name="acctsid"
            onChange={(event) => {
              setAccountId(event.target.value);
            }}
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
            id="cardsid"
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
            {pageRows.map((row, rowIndex) => (
              <tr data-testid="card-list-row" key={row.cardNum}>
                <td>
                  {/* BLITZY [A11Y]: single-character field per COCRDLI CRDSELn;
                      smaller than the 24x24 target-size recommendation. */}
                  <input
                    aria-label={'Select card ' + row.cardNum}
                    className="field"
                    data-testid={'card-select-' + row.cardNum}
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
        <button type="submit">ENTER</button>
      </form>
    </section>
  );
}
