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
import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { isBrowseNotice } from '../components/browseNotices';
import { faultedFieldProps, invalidValueProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  CardListItemDto,
  CardListRequestDto,
  CardListResponseDto,
} from '../types';
import { listCards } from '../api';
import {
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
} from '../hooks';
import { CARD_DETAIL_ROUTE, CARD_LIST_ROUTE, CARD_UPDATE_ROUTE } from './cardSelection';
import { isFilterAllDigits, isFilterNotSupplied } from './screenFilters';

/** Width of the account filter (``COCRDLI`` ``ACCTSIDI PIC X(11)``). */
const ACCOUNT_FILTER_LENGTH = 11;

/** Width of the card filter (``COCRDLI`` ``CARDSIDI PIC X(16)``). */
const CARD_FILTER_LENGTH = 16;

/** Width of a row-action field (``COCRDLI`` ``CRDSEL1I..CRDSEL7I PIC X(1)``). */
const ROW_ACTION_LENGTH = 1;

/**
 * :purpose: The browse-table column widths ``COCRDLI`` declares as the runs of hyphens it
 *     paints on row 10 -- 6, 15, 15 and 8 characters at columns 10, 21, 45 and 66.
 * :note: Those runs ARE the column widths on a 3270, so the table is laid out from them
 *     rather than from its content.
 */
const COLUMN_RULES: readonly number[] = [6, 15, 15, 8];

/**
 * :purpose: The table's floor width in character cells: every declared column width plus
 *     the one blank separator cell each carries.
 * :note: Without a floor the columns are content-derived, so the trailing column's fit
 *     depends on the data rather than on the mapset -- at the narrowest tier the table
 *     cleared its container by a twentieth of a character cell. Holding the table to this
 *     floor puts any shortfall into the scroll range instead.
 */
const TABLE_MIN_WIDTH_CH = COLUMN_RULES.reduce((total, run) => total + run + 2, 0);

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
  return value.length === length && isFilterAllDigits(value);
}

/**
 * :purpose: The value a supplied filter contributes to the browse. ``2210-EDIT-ACCOUNT``
 *     moves the field itself into ``CDEMO-ACCT-ID`` once it has passed the numeric edit,
 *     so a filter that reaches the browse is by then all digits and identical to the one
 *     on display; a filter that was not supplied contributes nothing.
 * :param value: the raw entry value.
 * :returns: the filter value, or an empty string when it was not supplied.
 */
function suppliedFilter(value: string): string {
  return isFilterNotSupplied(value) ? '' : value;
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

  const { data, error, loading, isInFlight, run } = useApi<
    CardListResponseDto,
    [CardListRequestDto]
  >(listCards);
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
   * :purpose: ``2210-EDIT-ACCOUNT`` then ``2220-EDIT-CARD``, against the fields exactly
   *     as they are held. ``2000-PROCESS-INPUTS`` runs both on every pass and the
   *     dispatch that follows opens with ``WHEN INPUT-ERROR`` (L418-419), ahead of the
   *     PF7 and PF8 branches — so a filter that fails its edit refuses a paging key too,
   *     rather than being dropped on the way to the browse and silently unfiltering it.
   * :returns: the line-23 message for the first filter that fails, or ``null``.
   */
  const editFilters = useCallback((): string | null => {
    if (
      !isFilterNotSupplied(accountId) &&
      !isFilterNumeric(accountId, ACCOUNT_FILTER_LENGTH)
    ) {
      return ACCOUNT_FILTER_MESSAGE;
    }
    if (
      !isFilterNotSupplied(cardNumber) &&
      !isFilterNumeric(cardNumber, CARD_FILTER_LENGTH)
    ) {
      return CARD_FILTER_MESSAGE;
    }
    return null;
  }, [accountId, cardNumber]);

  /**
   * :purpose: Process one ENTER, reproducing ``COCRDLIC`` ``2000-PROCESS-INPUTS``
   *     — edit the account filter, then the card filter, then the row actions of
   *     the displayed page, and finally either open the selected card or
   *     re-drive the browse from its first page.
   * :returns: nothing; the outcome is the line-23 message, a navigation, or a
   *     refreshed result set.
   */
  const submitScreen = useCallback((): void => {
    // The 3270 keyboard is locked from the moment an attention identifier is sent
    // until the next map arrives, so an ENTER pressed while the browse is
    // outstanding is inhibited rather than sent a second time. It is answered from a
    // ref, not from `loading`: two activations in the same task both observe render
    // state as it was before either of them, and both would be admitted.
    if (isInFlight()) {
      return;
    }
    const filterError = editFilters();
    if (filterError !== null) {
      setScreenMessage(filterError);
      return;
    }
    const accountFilter = suppliedFilter(accountId);
    const cardFilter = suppliedFilter(cardNumber);

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
        // `from` carries what CDEMO-FROM-PROGRAM carries, so PF3 on the receiving screen
        // returns here rather than to the main menu.
        state: {
          cardNumber: selected.cardNum,
          accountId: selected.cardAcctId,
          from: CARD_LIST_ROUTE,
        },
      });
      return;
    }

    // A fresh browse re-reads the rows and re-sends the screen with its seven
    // action fields blank, from the first page.
    setScreenMessage('');
    setRowActions({});
    void run(buildBrowseRequest(accountFilter, cardFilter, FIRST_PAGE));
  }, [
    accountId,
    cardNumber,
    editFilters,
    isInFlight,
    navigate,
    pageRows,
    rowActions,
    run,
  ]);

  /**
   * :purpose: PF7 — page backward. The key is always live: ``COCRDLIC`` reaches the
   *     program on every declared AID and its own ``WHEN CCARD-AID-PFK07 AND
   *     CA-FIRST-PAGE`` branch re-reads the same page, whereupon
   *     ``1400-SETUP-MESSAGE`` publishes ``NO PREVIOUS PAGES TO DISPLAY``. The AID is
   *     therefore sent with the browse and the service decides the banner.
   * :note: Inhibited while a browse is outstanding (the locked keyboard), so the page
   *     the target is derived from is always the page currently displayed and a burst
   *     of presses can neither issue concurrent identical browses nor lose a step.
   */
  const pageBackward = useCallback((): void => {
    if (isInFlight()) {
      return;
    }
    const filterError = editFilters();
    if (filterError !== null) {
      setScreenMessage(filterError);
      return;
    }
    setScreenMessage('');
    setRowActions({});
    // ``CA-FIRST-PAGE`` is the state of the screen BEFORE the transition, so it is
    // evaluated here, against the page currently displayed. The AID travels with the
    // browse only when that test holds — the one case in which ``1400-SETUP-MESSAGE``
    // publishes ``NO PREVIOUS PAGES TO DISPLAY`` — so a page-back that succeeds is
    // never annotated with the literal that refuses one.
    const atFirstPage = pageNumber <= FIRST_PAGE;
    const target = atFirstPage ? FIRST_PAGE : pageNumber - 1;
    void run(
      buildBrowseRequest(
        suppliedFilter(accountId),
        suppliedFilter(cardNumber),
        target,
        atFirstPage ? 'PF7' : undefined,
      ),
    );
  }, [accountId, cardNumber, editFilters, isInFlight, pageNumber, run]);

  /**
   * :purpose: PF8 — page forward. Also always live: with no further page the source
   *     falls through to ``WHEN OTHER`` and re-reads the page already displayed, so the
   *     rows stay on screen and the service publishes the browse-boundary banner.
   */
  const pageForward = useCallback((): void => {
    if (isInFlight()) {
      return;
    }
    const filterError = editFilters();
    if (filterError !== null) {
      setScreenMessage(filterError);
      return;
    }
    setScreenMessage('');
    setRowActions({});
    // ``CA-NEXT-PAGE-NOT-EXISTS AND CA-LAST-PAGE-SHOWN`` is likewise the state before
    // the transition, so the AID travels with the browse only when the screen already
    // holds the last page and the advance is therefore refused.
    const atLastPage = !hasNextPage;
    const target = atLastPage ? pageNumber : pageNumber + 1;
    void run(
      buildBrowseRequest(
        suppliedFilter(accountId),
        suppliedFilter(cardNumber),
        target,
        atLastPage ? 'PF8' : undefined,
      ),
    );
  }, [
    accountId,
    cardNumber,
    editFilters,
    hasNextPage,
    isInFlight,
    pageNumber,
    run,
  ]);

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

  // WS-INFO-MSG and WS-ERROR-MSG are DIFFERENT map fields on DIFFERENT rows -- INFOMSG
  // is ``POS=(20,19) COLOR=NEUTRAL LENGTH=45``, a body field, while ERRMSG is
  // ``POS=(23,1) COLOR=RED``. 3000-SEND-MAP moves both on every send, so a browse that
  // both found rows and hit a paging boundary shows its guidance AND its boundary
  // banner. Suppressing one because the other carries text is what made the row
  // guidance vanish at the moment the operator had a row to act on.
  const infoMessage = data?.infoMessage ?? '';

  // A browse boundary or an empty result keeps the RED COCRDLI.bms declares statically
  // but is announced politely rather than as an alert.
  const noticeMessage = isBrowseNotice(errorMessage) ? errorMessage : '';
  const alertMessage = noticeMessage === '' ? errorMessage : '';

  // COCRDLI marks ACCTSID ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor starts there
  // and returns there whenever the screen reports a new outcome. This screen reads its
  // rows before painting them and disables the entry fields while that read is in
  // flight, so the placement is driven by each completed map send — the first one and
  // every PF7 / PF8 browse alike — rather than by mount alone.
  const accountFilterRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, accountFilterRef);
  useFocusOnChange(errorMessage === '' ? null : errorMessage, accountFilterRef);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateSubmitScreen = useScreenAction(submitScreen);
  const activatePageBackward = useScreenAction(pageBackward);
  const activatePageForward = useScreenAction(pageForward);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      // COCRDLI's line-24 legend carries no ENTER text, but CCARD-AID-ENTER is what
      // drives the row selection, so the AID is declared with its legend darkened.
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        dark: true,
        onActivate: activateSubmitScreen,
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
        onActivate: activatePageBackward,
      },
      {
        action: PfKeyAction.PF8,
        label: 'F8=Forward',
        onActivate: activatePageForward,
      },
    ];
    setChrome({
      transactionId: 'CCLI',
      programName: 'COCRDLIC',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage: alertMessage,
      noticeMessage,
      // INFOMSG is not published to the shared message region: this mapset declares it as
      // its own body field on row 20, and the screen renders it there itself.
      pfKeys,
      // ``COACTVWC``/``COACTUPC``/``COCRDLIC``/``COCRDSLC``/``COCRDUPC`` do not answer an
      // unhandled AID with a message: they ``SET PFK-INVALID TO TRUE``, and when the
      // struck key is not in the valid set they ``SET CCARD-AID-ENTER TO TRUE`` -- the
      // key is REWRITTEN to ENTER and the ENTER path runs.
      onUnhandledKey: activateSubmitScreen,
      // COCRDLI declares its line-24 legend field COLOR=TURQUOISE, not the YELLOW
      // fifteen of the seventeen mapsets declare.
      pfKeyTone: 'turquoise',
      busy: loading,
    });
  }, [
    activatePageBackward,
    activatePageForward,
    activateSubmitScreen,
    alertMessage,
    noticeMessage,
    loading,
    navigate,
    setChrome,
  ]);

  return (
    <section aria-labelledby="card-list-heading">
      <div className="screenTitleLine">
        <h3 className="neutral" id="card-list-heading">
          List Credit Cards
        </h3>
        <p className="neutral screenTitleLine__page" data-testid="page-number">
          Page {pageNumber}
        </p>
      </div>
      <div className="cardList__body">
        <div className="cardList__search">
          <label className="prompt" htmlFor="acctsid">
            {'Account Number    :'}
          </label>
          {/*
            COCRDLIC L873 moves DFHRED into ACCTSIDC when the account filter is refused,
            so this control is PAINTED as well as marked: it takes `faultedFieldProps`
            rather than `invalidFieldProps`.
          */}
          <input
            className="field charField charField--acctId"
            data-testid="acctsid"
            disabled={loading}
            id="acctsid"
            {...faultedFieldProps(screenMessage === ACCOUNT_FILTER_MESSAGE)}
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
        <div className="cardList__search">
          <label className="prompt" htmlFor="cardsid">
            {'Credit Card Number:'}
          </label>
          {/* COCRDLIC L878 moves DFHRED into CARDSIDC for the card filter, likewise. */}
          <input
            className="field"
            data-testid="cardsid"
            disabled={loading}
            id="cardsid"
            {...faultedFieldProps(screenMessage === CARD_FILTER_MESSAGE)}
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
        <div
          className="tableScroll"
          role="group"
          aria-label="Credit card list columns"
          tabIndex={0}
        >
        <table
          className="dataTable dataTable--fixed"
          style={{ minWidth: `${String(TABLE_MIN_WIDTH_CH)}ch` }}
          data-testid="card-list-table"
          aria-labelledby="card-list-heading"
        >
          {/*
            The BMS rule runs under each caption ARE the column widths, so they are
            declared here and the table is laid out from them rather than from its
            content. The last column is left width-less so a wider container hands it the
            slack, exactly as the 3270 frame does.
          */}
          <colgroup>
            {COLUMN_RULES.map((run, index) => (
              <col
                key={String(index)}
                style={
                  index === COLUMN_RULES.length - 1
                    ? undefined
                    : { width: `${String(run + 2)}ch` }
                }
              />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th scope="col">Select</th>
              <th scope="col">Account Number</th>
              <th scope="col">{' Card Number'}</th>
              <th scope="col">Active</th>
            </tr>
            {/*
              COCRDLI row 10 paints a run of hyphens under each caption -- 6, 15, 15 and
              8 characters at columns 10, 20, 43 and 65 -- with the gaps between them
              left blank. Decoration only, so it is hidden from assistive technology.
            */}
            <tr className="dataTable__rule" aria-hidden="true">
              {COLUMN_RULES.map((run, index) => (
                <td key={String(index)}>{'-'.repeat(run)}</td>
              ))}
            </tr>
          </thead>
          <tbody>
            {/*
              An empty result renders NO row. COCRDLIC paints no "nothing found" literal
              into the body: it leaves the seven row fields blank and moves its own
              literal into WS-ERROR-MSG for line 23, so a placeholder row would be output
              the program never produces -- and nothing is claimed about a browse that has
              not answered either, which is the state the screen is in before its first
              read settles.
            */}
            {pageRows.map((row, rowIndex) => (
              <tr data-testid="card-list-row" key={row.cardNum}>
                <td>
                  {/* Single-character field per COCRDLI ``CRDSELn``; the shared
                      stylesheet enlarges its hit area to the adopted 24x24 WCAG
                      2.5.8 minimum. */}
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
        {/*
          COCRDLI declares INFOMSG as its own field at ``POS=(20,19) COLOR=NEUTRAL
          LENGTH=45`` -- a BODY row, in neutral, below the seven browse rows. It is not
          the line-23 ERRMSG region, so it is rendered here rather than published to the
          shared banner, which keeps row 23 for the RED error the mapset reserves it for
          and lets both appear at once as the source's single SEND MAP does.
        */}
        <p
          className="screenNote neutral cardList__infoMsg"
          data-testid="card-list-infomsg"
          role="status"
        >
          {infoMessage}
        </p>
      </div>
    </section>
  );
}
