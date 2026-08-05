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
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
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

/** ``SEARCHED-ACCT-ZEROES`` / ``SEARCHED-ACCT-NOT-NUMERIC``. */
const MSG_ACCOUNT_NON_ZERO_11 = 'Account number must be a non zero 11 digit number';

/** ``SEARCHED-CARD-NOT-NUMERIC``. */
const MSG_CARD_16_DIGITS = 'Card number if supplied must be a 16 digit number';

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
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getCard: getCardMock,
  signon: jest.fn(),
  ApiError,
}));

type CardDetailPageComponent = (typeof import('./CardDetailPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SetSession = (typeof import('../hooks/useSession'))['__setSession'];

let CardDetailPage: CardDetailPageComponent;
let Layout: LayoutComponent;
let __setSession: SetSession;

beforeAll(async () => {
  // Imported after the mock is registered so the page, the hooks, and the shell
  // all bind to the mocked ``../api``; no module reset, so React stays shared.
  ({ default: CardDetailPage } = await import('./CardDetailPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ __setSession } = await import('../hooks/useSession'));
});

beforeEach(() => {
  getCardMock.mockReset();
  getCardMock.mockResolvedValue(cardDetail);
  act(() => {
    __setSession(SESSION_USER, 'U');
  });
});

afterEach(() => {
  act(() => {
    __setSession(null, null);
  });
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
  accountId = '',
): void {
  render(
    <MemoryRouter
      initialEntries={[
        { pathname: CARD_DETAIL_ROUTE, state: { cardNumber, accountId } },
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
    expect(getCardMock).toHaveBeenCalledWith(ROUTE_CARD_NUMBER, undefined);

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
      screen.getByRole('heading', { level: 2, name: SCREEN_TITLE }),
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
  it('rejects a zero account filter with the verbatim non zero 11 digit message', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), {
      target: { value: '0'.repeat(ACCOUNT_FILTER_LENGTH) },
    });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_ACCOUNT_NON_ZERO_11);
    // The edit fails before the read, so no further request is issued.
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('rejects an account filter that is not 11 digits before reading', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), { target: { value: '12345' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_ACCOUNT_NON_ZERO_11);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });

  it('rejects a card filter that is not 16 digits with the verbatim message', async () => {
    renderCardDetail('123');

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_CARD_16_DIGITS);
    expect(getCardMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('cardsid')).toHaveValue('123');
  });

  it('lets the account edit win over a card edit, preserving the legacy order', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByTestId('acctsid'), {
      target: { value: '0'.repeat(ACCOUNT_FILTER_LENGTH) },
    });
    fireEvent.change(screen.getByTestId('cardsid'), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent(MSG_ACCOUNT_NON_ZERO_11);
    expect(banner).not.toHaveTextContent(MSG_CARD_16_DIGITS);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });
});

describe('CardDetailPage — line-24 function keys', () => {
  it('publishes exactly the two BMS legend entries', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    const legend = screen.getByRole('toolbar', { name: 'Function keys' });
    expect(screen.getByRole('button', { name: PF_ENTER_LABEL })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: PF_EXIT_LABEL })).toBeInTheDocument();
    expect(legend.querySelectorAll('button')).toHaveLength(2);
  });

  it('ENTER=Search Cards reads the card number currently entered', async () => {
    renderCardDetail();

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
    expect(getCardMock).toHaveBeenLastCalledWith(otherCardDetail.cardNum, undefined);
    expect(await screen.findByTestId('crdname')).toHaveTextContent(
      otherCardDetail.cardEmbossedName,
    );
    expect(screen.getByTestId('crdstcd').textContent).toBe('N');
  });

  it('binds the physical ENTER key to the same search handler', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.keyDown(document.body, { key: 'Enter' });

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(2);
    });
    expect(getCardMock).toHaveBeenLastCalledWith(ROUTE_CARD_NUMBER, undefined);
  });

  it('F3=Exit leaves the screen for the card list', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: PF_EXIT_LABEL }));

    expect(await screen.findByTestId('card-list-route')).toBeInTheDocument();
  });

  it('binds the physical F3 key to the exit handler', async () => {
    renderCardDetail();

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });

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

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_XREF_READ_ERROR);
    expect(getCardMock).toHaveBeenCalledTimes(1);
  });
});

