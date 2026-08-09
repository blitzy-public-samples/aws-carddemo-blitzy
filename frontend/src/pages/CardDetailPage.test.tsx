/**
 * CardDetailPage.test
 * ===================
 *
 * :purpose: Verify the card-detail workflow — BMS mapset ``app/bms/COCRDSL.bms``
 *     with symbolic map ``app/cpy-bms/COCRDSL.CPY``, CICS transaction ``CCDL``,
 *     program ``app/cbl/COCRDSLC.cbl``. Covers the route-parameter read through
 *     ``getCard``, the ``ACCTSID`` / ``CARDSID`` filter widths, the
 *     ``Name on card`` / ``Card Active Y/N`` / ``MM/YYYY`` expiry display, the
 *     verbatim filter-edit messages, and the ``ENTER=Search Cards`` / ``F3=Exit``
 *     line-24 handlers.
 * :output: Jest assertions only; the suite writes no files and performs no I/O.
 * :note: ``../api`` is replaced through ``jest.unstable_mockModule``, so ``getCard``
 *     is a spy, ``ApiError`` passes through as a constructible class, and neither
 *     axios nor ``import.meta`` is ever evaluated. The page and the screen shell
 *     are imported dynamically after the mock is registered, sharing one React
 *     instance with the statically imported Testing Library.
 */

// Jest's native-ESM runtime does not inject ``jest`` as a global (unlike
// ``describe`` / ``it`` / ``expect``), so it is imported explicitly.
import { jest } from '@jest/globals';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { GENERIC_ERROR_MESSAGE } from '../api/messages';
import { CCDA_TITLE02 } from '../types';
import type { ApiErrorResponse, CardDetailResponseDto } from '../types';

/** ``ACCTSID`` width (``PIC X(11)``). */
const ACCOUNT_FILTER_LENGTH = 11;

/** ``CARDSID`` width (``PIC X(16)``). */
const CARD_NUMBER_LENGTH = 16;

/** ``CRDNAME`` width (``PIC X(50)``). */
const CARD_NAME_LENGTH = 50;

/** ``ACCTSID`` caption (BMS line 7). */
const LABEL_ACCOUNT_NUMBER = 'Account Number    :';

/** ``CARDSID`` caption (BMS line 8). */
const LABEL_CARD_NUMBER = 'Card Number       :';

/** ``CRDNAME`` caption (BMS line 11). */
const LABEL_NAME_ON_CARD = 'Name on card      :';

/** ``CRDSTCD`` caption (BMS line 13). */
const LABEL_CARD_ACTIVE = 'Card Active Y/N   :';

/** ``EXPMON`` / ``EXPYEAR`` caption (BMS line 15). */
const LABEL_EXPIRY_DATE = 'Expiry Date       :';

/** ENTER entry of the BMS ``FKEYS`` legend (line 24). */
const PF_ENTER_LABEL = 'ENTER=Search Cards';

/** PF3 entry of the BMS ``FKEYS`` legend (line 24). */
const PF_EXIT_LABEL = 'F3=Exit';

/**
 * ``2210-EDIT-ACCOUNT`` non-numeric literal, MOVEd directly. ``COCRDSLC`` declares
 * ``SEARCHED-ACCT-ZEROES`` / ``SEARCHED-ACCT-NOT-NUMERIC`` ('Account number must be a
 * non zero 11 digit number') but never SETs either, so this upper-case form is the only
 * account-filter text the program can put on line 23.
 */
const MSG_ACCOUNT_FILTER_11 = 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/**
 * ``2220-EDIT-CARD`` non-numeric literal, MOVEd directly for the same reason:
 * ``SEARCHED-CARD-NOT-NUMERIC`` is an unreachable 88-level.
 */
const MSG_CARD_FILTER_16 = 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/** ``WS-PROMPT-FOR-CARD`` — the read key was not supplied. */
const MSG_CARD_NOT_PROVIDED = 'Card number not provided';

/**
 * ``WS-PROMPT-FOR-ACCT`` — ``2210-EDIT-ACCOUNT`` found no account key. Reached by a
 * blank ACCTSID *and* by an all-zeros one, because the paragraph's "not supplied" test
 * is ``CC-ACCT-ID EQUAL LOW-VALUES OR SPACES OR CC-ACCT-ID-N EQUAL ZEROS``.
 */
const MSG_ACCOUNT_NOT_PROVIDED = 'Account number not provided';

/** ``NO-SEARCH-CRITERIA-RECEIVED`` — the unguarded both-blank cross-field test. */
const MSG_NO_SEARCH_CRITERIA = 'No input received';

/** A valid 11-digit ``ACCTSID`` entry, used where the account edit must pass. */
const VALID_ACCOUNT_FILTER = '00000000050';

/** Aliases used by the later cases for the same three literals. */
const MSG_ACCOUNT_FILTER_11_DIGITS = MSG_ACCOUNT_FILTER_11;
const MSG_CARD_FILTER_16_DIGITS = MSG_CARD_FILTER_16;
const MSG_NO_INPUT_RECEIVED = MSG_NO_SEARCH_CRITERIA;

/** ``DID-NOT-FIND-ACCTCARD-COMBO``; the card-service message on a failed read. */
const MSG_NO_CARDS_FOUND = 'Did not find cards for this search condition';

/** ``XREF-READ-ERROR``; carried by a failure the client never normalized. */
const MSG_XREF_READ_ERROR = 'Error reading Card Data File';

/** ``LIT-THISTRANID`` of this screen. */
const TRANSACTION_ID = 'CCDL';

/** ``LIT-THISPGM`` of this screen. */
const PROGRAM_NAME = 'COCRDSLC';

/** Body heading of the mapset (BMS line 4). */
const SCREEN_TITLE = 'View Credit Card Detail';

/** Route of the card list screen (``COCRDLI`` / ``CCLI``), the PF3 target. */
const CARD_LIST_ROUTE = '/cards';

/** ``LIT-MENUPGM`` — the PF3 fallback when no caller is recorded. */
const MAIN_MENU_ROUTE = '/menu';

/** ``FOUND-CARDS-FOR-ACCOUNT`` (COCRDSLC L129-130), three leading spaces preserved. */
const FOUND_CARDS_FOR_ACCOUNT = '   Displaying requested details';

/** Seeded user id (``CDEMO-USER-ID``); no credential is involved. */
const SESSION_USER = 'USER0001';

/**
 * Card fixture taken from the repository's own ASCII seed, row 1 of
 * ``app/data/ASCII/carddata.txt`` joined to ``app/data/ASCII/cardxref.txt``.
 */
const cardDetail: CardDetailResponseDto = {
  cardNum: '0500024453765740',
  cardAcctId: '00000000050',
  cardEmbossedName: 'Aniya Von',
  cardActiveStatus: 'Y',
  cardExpiraionDate: '2023-03-09',
  custId: '000000050',
  version: 0,
};

/** Second card fixture, row 2 of the same seed; the target of a re-search. */
const otherCardDetail: CardDetailResponseDto = {
  cardNum: '0683586198171516',
  cardAcctId: '00000000027',
  cardEmbossedName: 'Ward Jones',
  cardActiveStatus: 'N',
  cardExpiraionDate: '2025-07-13',
  custId: '000000027',
  version: 0,
};

/**
 * Over-wide fixture: the embossed name exceeds ``PIC X(50)`` and the status flag
 * exceeds ``PIC X(1)``, so the display widths are observable.
 */
const overlongCardDetail: CardDetailResponseDto = {
  ...cardDetail,
  cardEmbossedName: 'W'.repeat(CARD_NAME_LENGTH + 14),
  cardActiveStatus: 'YN',
};

/** The 16-digit route parameter under test, carried as a ``string``. */
const ROUTE_CARD_NUMBER: string = cardDetail.cardNum;

/**
 * The account handed over alongside the card. ``COCRDLIC`` always transfers the browse
 * row's account id with its card number, and ``2210-EDIT-ACCOUNT`` refuses a search that
 * arrives without one, so the hand-over fixture carries both halves of the composite key.
 */
const ROUTE_ACCOUNT_ID: string = cardDetail.cardAcctId;

/** Entry location of the screen. */
const CARD_DETAIL_ROUTE = '/cards/view';

/**
 * :purpose: Normalized REST error contract of ``../api``, redeclared with the same
 *     constructor shape so the mocked module exports a constructible ``ApiError``,
 *     which ``useApi`` both narrows with ``instanceof`` and constructs.
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: already-resolved, human-readable message.
 * :param body: standardized backend error body when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
 */
class ApiError extends Error {
  readonly status: number;
  readonly body?: ApiErrorResponse;
  readonly isOptimisticLockConflict: boolean;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorResponse,
    isOptimisticLockConflict = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
  }
}

/**
 * Spy standing in for the ``../api`` ``getCard`` named export. Declared at module
 * scope so the mock factory can close over it.
 */
const getCardMock =
  jest.fn<(cardNumber: string) => Promise<CardDetailResponseDto>>();

// The factory exposes every name the loaded graph binds from ``../api``:
// ``getCard`` (this page), ``ApiError`` (``useApi``), ``signon`` (``useSession``,
// reached through the hook barrel and the shell).
jest.unstable_mockModule('../api', () => ({
  // The request-cancellation contract ``useApi`` binds to: the real scope hands the
  // caller's AbortSignal to axios, and the double simply invokes the call.
  runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
  isCancelledRequest: (): boolean => false,
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. ``getSessionIdentity`` is the production
  // ``GET /session`` probe the session harness drives; unanswered by this suite it
  // reports no session, and the harness is the only thing that changes that.
  getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
  logout: jest.fn(() => Promise.resolve(undefined)),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getCard: getCardMock,
  signon: jest.fn(),
  ApiError,
}));

type CardDetailPageComponent = (typeof import('./CardDetailPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let CardDetailPage: CardDetailPageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the page, the hooks, and the shell
  // all bind to the mocked ``../api``; no module reset, so React stays shared.
  ({ default: CardDetailPage } = await import('./CardDetailPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getCardMock.mockReset();
  getCardMock.mockResolvedValue(cardDetail);
  await seedSignedOnSession(SESSION_USER, 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: Match caption text exactly as the mapset spaces it, bypassing Testing
 *     Library's default collapsing of the BMS column padding.
 * :param text: the candidate element text.
 * :returns: the text unchanged.
 */
function verbatim(text: string): string {
  return text;
}

/**
 * :purpose: Render the screen inside the shared 24x80 shell at a
 *     ``/cards/:cardNumber`` location, so the line-23 message region and the
 *     line-24 function-key legend the page publishes through ``useScreenChrome``
 *     are observable, and so PF3 has a route to leave for.
 * :param initialPath: the entry location; its last segment supplies ``cardNumber``.
 */
function renderCardDetail(
  cardNumber: string = ROUTE_CARD_NUMBER,
  accountId: string = ROUTE_ACCOUNT_ID,
  from: string = CARD_LIST_ROUTE,
): void {
  render(
    <MemoryRouter
      initialEntries={[
        { pathname: CARD_DETAIL_ROUTE, state: { cardNumber, accountId, from } },
      ]}
    >
      <Routes>
        <Route
          path={CARD_DETAIL_ROUTE}
          element={
            <Layout>
              <CardDetailPage />
            </Layout>
          }
        />
        <Route path={CARD_LIST_ROUTE} element={<div data-testid="card-list-route" />} />
        <Route path={MAIN_MENU_ROUTE} element={<div data-testid="main-menu-route" />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CardDetailPage — load by route parameter', () => {
  it('reads the card named by the route parameter and renders the mocked detail', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    expect(getCardMock).toHaveBeenCalledWith(ROUTE_CARD_NUMBER, ROUTE_ACCOUNT_ID);

    // The 16-digit PAN travels as a string, never a number.
    const [passedCardNumber] = getCardMock.mock.calls[0];
    expect(typeof passedCardNumber).toBe('string');
    expect(passedCardNumber).toHaveLength(CARD_NUMBER_LENGTH);
    expect(passedCardNumber).toBe('0500024453765740');

    expect(await screen.findByTestId('crdname')).toHaveTextContent(
      cardDetail.cardEmbossedName,
    );
    expect(screen.getByTestId('crdstcd')).toHaveTextContent(cardDetail.cardActiveStatus);
    expect(screen.getByTestId('cardsid')).toHaveValue(ROUTE_CARD_NUMBER);
  });

  it('publishes the CCDL transaction identity into the shared header', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    expect(screen.getByTestId('tran-id')).toHaveTextContent(TRANSACTION_ID);
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(PROGRAM_NAME);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    // The screen name lives in the body heading (BMS row 4), not in title02.
    expect(
      screen.getByRole('heading', { level: 3, name: SCREEN_TITLE }),
    ).toBeInTheDocument();
    expect(screen.getByRole('region', { name: SCREEN_TITLE })).toBeInTheDocument();
  });
});

describe('CardDetailPage — mapset fields and field widths', () => {
  it('gives the ACCTSID and CARDSID filters their symbolic-map widths', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    const accountFilter = screen.getByTestId<HTMLInputElement>('acctsid');
    expect(accountFilter).toHaveAttribute('name', 'acctsid');
    expect(accountFilter.maxLength).toBe(ACCOUNT_FILTER_LENGTH);
    expect(accountFilter).toHaveAttribute('maxlength', '11');

    const cardFilter = screen.getByTestId<HTMLInputElement>('cardsid');
    expect(cardFilter).toHaveAttribute('name', 'cardsid');
    expect(cardFilter.maxLength).toBe(CARD_NUMBER_LENGTH);
    expect(cardFilter).toHaveAttribute('maxlength', '16');
  });

  it('renders every mapset caption with its column padding intact', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    expect(
      screen.getByText(LABEL_ACCOUNT_NUMBER, { normalizer: verbatim }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(LABEL_CARD_NUMBER, { normalizer: verbatim }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(LABEL_NAME_ON_CARD, { normalizer: verbatim }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(LABEL_CARD_ACTIVE, { normalizer: verbatim }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(LABEL_EXPIRY_DATE, { normalizer: verbatim }),
    ).toBeInTheDocument();
  });

  it('clips the name on card to 50 characters and the active flag to 1', async () => {
    getCardMock.mockResolvedValue(overlongCardDetail);
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    const cardName = await screen.findByTestId('crdname');
    expect(cardName.textContent).toHaveLength(CARD_NAME_LENGTH);
    expect(cardName.textContent).toBe('W'.repeat(CARD_NAME_LENGTH));
    expect(screen.getByTestId('crdstcd').textContent).toBe('Y');
  });

  it('renders the expiry as MM/YYYY around the mapset separator', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    expect((await screen.findByTestId('expmon')).textContent).toBe('03');
    expect(screen.getByTestId('expyear').textContent).toBe('2023');
    expect(screen.getByTestId('expiry-date').textContent).toBe('03/2023');
  });
});

describe('CardDetailPage — preserved cardExpiraionDate spelling', () => {
  it('binds the expiry display to the DTO cardExpiraionDate member', async () => {
    // The legacy misspelling (COBOL ``CARD-EXPIRAION-DATE``, missing the second
    // ``T``) is the frozen wire member the screen reads.
    expect(Object.prototype.hasOwnProperty.call(cardDetail, 'cardExpiraionDate')).toBe(
      true,
    );
    expect(cardDetail.cardExpiraionDate).toBe('2023-03-09');

    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    expect((await screen.findByTestId('expiry-date')).textContent).toBe('03/2023');
  });

  it('tracks a different cardExpiraionDate value on the same screen', async () => {
    getCardMock.mockResolvedValue({
      ...cardDetail,
      cardExpiraionDate: otherCardDetail.cardExpiraionDate,
    });
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    expect((await screen.findByTestId('expmon')).textContent).toBe('07');
    expect(screen.getByTestId('expyear').textContent).toBe('2025');
    expect(screen.getByTestId('expiry-date').textContent).toBe('07/2025');
  });
});

describe('CardDetailPage — filter edits', () => {
  it('treats an all-zeros account filter as not supplied, per the blank branch', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), {
      target: { value: '0'.repeat(ACCOUNT_FILTER_LENGTH) },
    });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    // 2210-EDIT-ACCOUNT's "not supplied" test includes CC-ACCT-ID-N EQUAL ZEROS, so a
    // zero-filled field takes the prompt branch, NOT the non-numeric branch.
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent(MSG_ACCOUNT_NOT_PROVIDED);
    expect(banner).not.toHaveTextContent(MSG_ACCOUNT_FILTER_11);
    // The edit fails before the read, so no further request is issued.
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('requires the account key, reporting WS-PROMPT-FOR-ACCT when only a card is typed', async () => {
    renderCardDetail('', '');
    expect(getCardMock).not.toHaveBeenCalled();

    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: ROUTE_CARD_NUMBER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_ACCOUNT_NOT_PROVIDED);
    expect(screen.getByTestId('acctsid')).toHaveAttribute('aria-invalid', 'true');
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('requires the card key, reporting WS-PROMPT-FOR-CARD when only an account is typed', async () => {
    renderCardDetail('');

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: VALID_ACCOUNT_FILTER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_CARD_NOT_PROVIDED);
    expect(screen.getByTestId('cardsid')).toHaveAttribute('aria-invalid', 'true');
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('rejects an account filter that is not 11 digits before reading', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_ACCOUNT_FILTER_11);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('reports the both-blank cross-field literal when ENTER finds neither key', async () => {
    // No selection is handed over, so the screen presents its own entry fields and runs
    // no edit until an AID arrives -- the fresh-entry branch of the source.
    renderCardDetail('', '');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(getCardMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    const banner = await screen.findByRole('alert');
    // The cross-field test is unguarded, so it replaces both field prompts.
    expect(banner).toHaveTextContent(MSG_NO_SEARCH_CRITERIA);
    expect(banner).not.toHaveTextContent(MSG_CARD_NOT_PROVIDED);
    // The mixed-case 88-level COCRDSLC declares but never SETs must not be emitted.
    expect(banner).not.toHaveTextContent('Card number if supplied must be a 16 digit');
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('rejects a card filter that is not 16 digits with the verbatim message', async () => {
    renderCardDetail('');

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: VALID_ACCOUNT_FILTER } });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_CARD_FILTER_16);
    expect(getCardMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('cardsid')).toHaveValue('123');
  });

  it('lets the account edit win over a card edit, preserving the legacy order', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent(MSG_ACCOUNT_FILTER_11);
    expect(banner).not.toHaveTextContent(MSG_CARD_FILTER_16);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('reddens BOTH key controls when neither was supplied', async () => {
    renderCardDetail('', '');

    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');

    // 1300-SETUP-SCREEN-ATTRS reddens the two fields through four independent IFs, so
    // FLG-ACCTFILTER-BLANK and FLG-CARDFILTER-BLANK both fire on a both-blank send.
    expect(screen.getByTestId('acctsid')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('cardsid')).toHaveAttribute('aria-invalid', 'true');
    // The fault must be VISIBLE, not only announced: the colour half of the paragraph
    // is the `fieldError` class, which turns the field's frame red.
    expect(screen.getByTestId('acctsid')).toHaveClass('fieldError');
    expect(screen.getByTestId('cardsid')).toHaveClass('fieldError');
  });

  it("marks a BLANK key with the paragraph's '*' and a rejected one with colour alone", async () => {
    renderCardDetail('');

    // Blank card, valid account: FLG-CARDFILTER-BLANK -> MOVE '*' + DFHRED.
    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: VALID_ACCOUNT_FILTER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');
    expect(screen.getByTestId('cardsid')).toHaveClass('fieldError');
    expect(screen.getByTestId('cardsid').previousElementSibling).toHaveTextContent('*');
    // The account passed its edit, so it carries neither the colour nor the marker.
    expect(screen.getByTestId('acctsid')).not.toHaveClass('fieldError');
    expect(screen.getByTestId('acctsid').previousElementSibling).toBeEmptyDOMElement();
  });

  it('reddens a wrong-length key without marking it blank', async () => {
    renderCardDetail('');

    // FLG-ACCTFILTER-NOT-OK takes MOVE DFHRED only: the field holds what was typed, so
    // the paragraph does not overwrite it with '*'.
    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: ROUTE_CARD_NUMBER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');

    expect(screen.getByTestId('acctsid')).toHaveClass('fieldError');
    expect(screen.getByTestId('acctsid').previousElementSibling).toBeEmptyDOMElement();
  });

  it('clears every fault marking once the keys pass their edits', async () => {
    renderCardDetail('', '');

    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');
    expect(screen.getByTestId('acctsid')).toHaveClass('fieldError');

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: VALID_ACCOUNT_FILTER } });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: ROUTE_CARD_NUMBER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByTestId('acctsid')).not.toHaveClass('fieldError');
    expect(screen.getByTestId('cardsid')).not.toHaveClass('fieldError');
    expect(screen.getByTestId('acctsid')).not.toHaveAttribute('aria-invalid');
    expect(screen.getByTestId('cardsid')).not.toHaveAttribute('aria-invalid');
  });

  it('puts the cursor on the control the edit rejected, not always on ACCTSID', async () => {
    renderCardDetail('');

    // Account valid, card absent: the cursor EVALUATE falls through the two account
    // conditions to WHEN FLG-CARDFILTER-BLANK -> MOVE -1 TO CARDSIDL.
    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: VALID_ACCOUNT_FILTER } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');

    expect(document.activeElement).toBe(screen.getByTestId('cardsid'));
  });

  it('homes the cursor on ACCTSID when the account condition matches first', async () => {
    renderCardDetail('', '');

    // Neither key: the EVALUATE tests FLG-ACCTFILTER-BLANK before the card conditions.
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
    await screen.findByRole('alert');

    expect(document.activeElement).toBe(screen.getByTestId('acctsid'));
  });

  it('runs no edit on the hand-over read, whose criteria the list already validated', async () => {
    // COCRDSLC's "COMING FROM CREDIT CARD LIST SCREEN / SELECTION CRITERIA ALREADY
    // VALIDATED" branch sets INPUT-OK and reads without reaching 2200-EDIT-MAP-INPUTS,
    // so a selection carried in without an account filter still displays its card.
    renderCardDetail(ROUTE_CARD_NUMBER, '');

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    expect(getCardMock).toHaveBeenCalledWith(ROUTE_CARD_NUMBER, undefined);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

/**
 * :purpose: Wait until the screen's transaction has completed and the shell has
 *     released the keyboard, mirroring the fact that a 3270 accepts no attention
 *     identifier while `X SYSTEM` is showing.
 */
async function waitForKeyboardRelease(): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('screen-busy')).toBeEmptyDOMElement();
  });
}

describe('CardDetailPage — line-24 function keys', () => {
  it('publishes exactly the two BMS legend entries', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    const legend = screen.getByRole('group', { name: 'Function keys' });
    expect(screen.getByRole('button', { name: PF_ENTER_LABEL })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: PF_EXIT_LABEL })).toBeInTheDocument();
    expect(legend.querySelectorAll('button')).toHaveLength(2);
  });

  it('ENTER=Search Cards reads the card number currently entered', async () => {
    // Both keys are seeded from the hand-over, as the card list supplies them, so the
    // 2210/2220 edits pass and the re-read exercises only the typed card change.
    renderCardDetail(ROUTE_CARD_NUMBER, VALID_ACCOUNT_FILTER);

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    getCardMock.mockResolvedValue(otherCardDetail);
    fireEvent.change(screen.getByTestId('cardsid'), {
      target: { value: otherCardDetail.cardNum },
    });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(2);
    });
    expect(getCardMock).toHaveBeenLastCalledWith(
      otherCardDetail.cardNum,
      VALID_ACCOUNT_FILTER,
    );
    expect(await screen.findByTestId('crdname')).toHaveTextContent(
      otherCardDetail.cardEmbossedName,
    );
    expect(screen.getByTestId('crdstcd').textContent).toBe('N');
  });

  it('binds the physical ENTER key to the same search handler', async () => {
    renderCardDetail(ROUTE_CARD_NUMBER, VALID_ACCOUNT_FILTER);

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    await waitForKeyboardRelease();
    fireEvent.keyDown(document.body, { key: 'Enter' });

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(2);
    });
    expect(getCardMock).toHaveBeenLastCalledWith(ROUTE_CARD_NUMBER, VALID_ACCOUNT_FILTER);
  });

  it('F3=Exit leaves the screen for the card list', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    await waitForKeyboardRelease();
    fireEvent.click(screen.getByRole('button', { name: PF_EXIT_LABEL }));

    expect(await screen.findByTestId('card-list-route')).toBeInTheDocument();
  });

  it('binds the physical F3 key to the exit handler', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    await waitForKeyboardRelease();
    fireEvent.keyDown(document.body, { key: 'F3' });

    expect(await screen.findByTestId('card-list-route')).toBeInTheDocument();
  });
});

describe('CardDetailPage — failed read', () => {
  it('surfaces the service ApiError message verbatim and leaves the fields blank', async () => {
    getCardMock.mockReset();
    getCardMock.mockRejectedValueOnce(new ApiError(404, MSG_NO_CARDS_FOUND));
    renderCardDetail();

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_NO_CARDS_FOUND);
    expect(getCardMock).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('crdname')).toBeEmptyDOMElement();
    expect(screen.getByTestId('expiry-date').textContent).toBe('/');
  });

  it('normalizes a plain Error through the passthrough ApiError class', async () => {
    // ``useApi`` builds an ``ApiError`` with status 0 from any value the client
    // never wrapped, so the message still reaches the line-23 region.
    getCardMock.mockReset();
    getCardMock.mockRejectedValueOnce(new Error(MSG_XREF_READ_ERROR));
    renderCardDetail();

    // A plain Error never passed through the client interceptor, so it carries a
    // library or programming diagnostic rather than a screen literal; line 23 shows
    // the application's own generic instead of rendering it.
    expect(await screen.findByRole('alert')).toHaveTextContent(GENERIC_ERROR_MESSAGE);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });
});

describe('CardDetailPage — mandatory composite key (2200-EDIT-MAP-INPUTS)', () => {
  it('arrives clean with no hand-over, and reads nothing until asked', () => {
    renderCardDetail('', '', '');

    // A transaction started without a COMMAREA has received no input to complain about:
    // the screen waits with both filters enterable and the message line free.
    expect(screen.queryByRole('alert')).toBeNull();
    expect(getCardMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('acctsid')).toHaveValue('');
    expect(screen.getByTestId('cardsid')).toHaveValue('');
  });

  it('reports no input received when ENTER arrives with neither filter supplied', async () => {
    renderCardDetail('', '', '');

    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    // The CROSS FIELD EDIT sets NO-SEARCH-CRITERIA-RECEIVED unconditionally when both
    // FLG-ACCTFILTER-BLANK and FLG-CARDFILTER-BLANK hold, replacing the account prompt
    // 2210-EDIT-ACCOUNT had already published.
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_NO_INPUT_RECEIVED);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('refuses a card-only search rather than widening it, per 2210 INPUT-ERROR', async () => {
    // The edits belong to the CDEMO-PGM-REENTER path (L357-L371 performs
    // 2000-PROCESS-INPUTS, which reaches 2200-EDIT-MAP-INPUTS at L585); the hand-over
    // branch at L339-L348 SETs INPUT-OK and reads without editing, so a mandatory-key
    // rule is proven on the path the operator drives.
    renderCardDetail('', '');

    fireEvent.change(screen.getByTestId('cardsid'), {
      target: { value: ROUTE_CARD_NUMBER },
    });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    // 2210-EDIT-ACCOUNT's 'Not supplied' branch SETs INPUT-ERROR, so the read never runs
    // and the card is not disclosed on the strength of its number alone.
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_ACCOUNT_NOT_PROVIDED);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('reports a missing card number when only the account is supplied', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_CARD_NOT_PROVIDED);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('treats the * wildcard as not supplied on both filters', async () => {
    renderCardDetail('', '');

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '*' } });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '*' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    // 2200-EDIT-MAP-INPUTS moves LOW-VALUES for a '*' entry before the field edits run.
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_NO_INPUT_RECEIVED);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('withdraws the details-shown notice while an edit is being reported', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveTextContent(
        cardDetail.cardEmbossedName ?? '',
      );
    });

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    // The screen cannot report a rejected key and simultaneously claim the details it
    // was asked for are on display; COCRDSLC reaches 'SETUP MESSAGE' only after a read.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      MSG_ACCOUNT_FILTER_11_DIGITS,
    );
    expect(screen.queryByText(FOUND_CARDS_FOR_ACCOUNT)).toBeNull();
  });
});

describe('CardDetailPage — PF3 returns to the caller (CDEMO-FROM-PROGRAM)', () => {
  it('returns to the card list when the list handed the selection over', async () => {
    renderCardDetail(ROUTE_CARD_NUMBER, ROUTE_ACCOUNT_ID, CARD_LIST_ROUTE);

    // The read has to have SETTLED, not merely started: the keyboard is locked between
    // the AID and its reply, so an F3 transmitted while the read is outstanding is
    // discarded exactly as the terminal discards it.
    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveTextContent(
        cardDetail.cardEmbossedName,
      );
    });
    expect(getCardMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: PF_EXIT_LABEL }));

    expect(await screen.findByTestId('card-list-route')).toBeInTheDocument();
  });

  it('returns to the main menu when no caller was recorded', async () => {
    // `CDEMO-FROM-PROGRAM EQUAL SPACES` substitutes LIT-MENUPGM, so a screen entered from
    // the menu or by deep link goes back to the menu rather than to a list never visited.
    renderCardDetail(ROUTE_CARD_NUMBER, ROUTE_ACCOUNT_ID, '');

    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveTextContent(
        cardDetail.cardEmbossedName,
      );
    });
    expect(getCardMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: PF_EXIT_LABEL }));

    expect(await screen.findByTestId('main-menu-route')).toBeInTheDocument();
    expect(screen.queryByTestId('card-list-route')).toBeNull();
  });
});

describe('CardDetailPage — cursor placement (MOVE -1 TO <field>L)', () => {
  it('leaves the cursor on the card filter when the card edit refused it', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '4444' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_CARD_FILTER_16_DIGITS);
    // The field marked invalid and the field holding the cursor must be the same one.
    expect(screen.getByTestId('cardsid')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('cardsid')).toHaveFocus();
  });

  it('leaves the cursor on the account filter when the account edit refused it', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      MSG_ACCOUNT_FILTER_11_DIGITS,
    );
    expect(screen.getByTestId('acctsid')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('acctsid')).toHaveFocus();
  });
});
