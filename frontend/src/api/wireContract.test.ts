/**
 * :module: wireContract.test
 * :purpose: Lock the wire contract of the two list/detail domain API modules whose
 *     parameters the screens depend on: that ``listUsers`` converts the screen's
 *     one-based page counter to the zero-based ``page`` the ``/users`` route binds
 *     and forwards the ``COUSR00C`` browse key, paging action, cursor and row
 *     selection; that ``listCards`` forwards the ``COCRDLI`` filters, the one-based
 *     page, the paging key and the row selection; that ``getCard`` and
 *     ``updateCard`` send the ``ACCTSID`` completing the ``COCRDSLC`` composite
 *     selection; and that the card screens are reached by a static route carrying no
 *     card number.
 * :note: ``./client`` is mocked via ``jest.unstable_mockModule``, so no axios
 *     instance is created and ``./config`` (which reads the Vite ``import.meta``
 *     environment) is never loaded.
 */

import { jest } from '@jest/globals';

/** Captured axios-style calls, one entry per request the module under test issued. */
interface RecordedCall {
  url: string;
  config?: { params?: Record<string, unknown> };
  body?: unknown;
}

const getCalls: RecordedCall[] = [];
const putCalls: RecordedCall[] = [];

const getMock = jest.fn(
  (url: string, config?: { params?: Record<string, unknown> }) => {
    getCalls.push({ url, config });
    return Promise.resolve({ data: {} });
  },
);
const putMock = jest.fn(
  (
    url: string,
    body: unknown,
    config?: { params?: Record<string, unknown> },
  ) => {
    putCalls.push({ url, body, config });
    return Promise.resolve({ data: {} });
  },
);

jest.unstable_mockModule('./client', () => ({
  __esModule: true,
  default: {
    get: getMock,
    put: putMock,
    post: jest.fn(),
    delete: jest.fn(),
  },
}));

const { listUsers } = await import('./users');
const { listCards, getCard, updateCard } = await import('./cards');
const { CARD_DETAIL_ROUTE, CARD_UPDATE_ROUTE, readCardSelection } = await import(
  '../pages/cardSelection'
);

/**
 * :purpose: Read the query parameters of the single recorded GET.
 * :returns: the parameter record axios was handed.
 */
function lastGetParams(): Record<string, unknown> {
  return getCalls[getCalls.length - 1].config?.params ?? {};
}

beforeEach(() => {
  getCalls.length = 0;
  putCalls.length = 0;
  getMock.mockClear();
  putMock.mockClear();
});

describe('listUsers wire contract (COUSR00C / CU00)', () => {
  it('converts the one-based screen page to the zero-based route index', async () => {
    await listUsers({ page: 1 });
    expect(lastGetParams().page).toBe(0);

    await listUsers({ page: 3 });
    expect(lastGetParams().page).toBe(2);
  });

  it('clamps a page below one to the first page and omits an absent page', async () => {
    await listUsers({ page: 0 });
    expect(lastGetParams().page).toBe(0);

    await listUsers({});
    expect(lastGetParams().page).toBeUndefined();
  });

  it('forwards the browse key, paging action, cursor and row selection', async () => {
    await listUsers({
      userId: 'USER0003',
      page: 1,
      direction: 'PF8',
      cursor: 'USER0005',
      selection: 'U',
      selectedUserId: 'USER0005',
    });

    expect(getCalls[0].url).toBe('/users');
    expect(lastGetParams()).toEqual({
      userId: 'USER0003',
      page: 0,
      direction: 'PF8',
      cursor: 'USER0005',
      selection: 'U',
      selectedUserId: 'USER0005',
    });
  });
});

describe('listCards wire contract (COCRDLIC / CCLI)', () => {
  it('sends the card filter under the route parameter name and a one-based page', async () => {
    await listCards({ accountId: '00000000011', cardNum: '4111111111111111', page: 2 });

    expect(getCalls[0].url).toBe('/cards');
    expect(lastGetParams()).toEqual({
      accountId: '00000000011',
      cardNumber: '4111111111111111',
      page: 2,
      aid: undefined,
      action: undefined,
      selectedCardNumber: undefined,
    });
  });

  it('forwards the paging key and the row selection', async () => {
    await listCards({
      page: 1,
      aid: 'PF8',
      action: 'S',
      selectedCardNumber: '4111111111111111',
    });

    const params = lastGetParams();
    expect(params.aid).toBe('PF8');
    expect(params.action).toBe('S');
    expect(params.selectedCardNumber).toBe('4111111111111111');
  });
});

describe('card detail / update composite selection (COCRDSLC 2200-EDIT-MAP-INPUTS)', () => {
  it('sends ACCTSID alongside the card key on the detail read', async () => {
    await getCard('4111111111111111', '00000000011');

    expect(getCalls[0].url).toBe('/cards/4111111111111111');
    expect(lastGetParams()).toEqual({ accountId: '00000000011' });
  });

  it('omits ACCTSID when the screen collected none', async () => {
    await getCard('4111111111111111');
    expect(lastGetParams()).toEqual({ accountId: undefined });
  });

  it('sends ACCTSID and the snapshot body on the update', async () => {
    await updateCard(
      '4111111111111111',
      {
        cardEmbossedName: 'JANE Q DOE',
        cardActiveStatus: 'N',
        cardExpiraionDate: '2028-01-31',
        version: 4,
        oldCardActiveStatus: 'Y',
      },
      '00000000011',
    );

    expect(putCalls[0].url).toBe('/cards/4111111111111111');
    expect(putCalls[0].config?.params).toEqual({ accountId: '00000000011' });
    expect(putCalls[0].body).toMatchObject({
      version: 4,
      oldCardActiveStatus: 'Y',
    });
  });
});

describe('card screen routes carry no card number', () => {
  it('exposes static routes with no path parameter', () => {
    expect(CARD_DETAIL_ROUTE).toBe('/cards/view');
    expect(CARD_UPDATE_ROUTE).toBe('/cards/update');
    expect(CARD_DETAIL_ROUTE).not.toContain(':');
    expect(CARD_UPDATE_ROUTE).not.toContain(':');
  });

  it('reads the hand-over selection from the router state and trims it', () => {
    expect(
      readCardSelection({ cardNumber: ' 4111111111111111 ', accountId: ' 11 ' }),
    ).toEqual({ cardNumber: '4111111111111111', accountId: '11' });
  });

  it('yields an empty selection when no state arrived', () => {
    expect(readCardSelection(null)).toEqual({ cardNumber: '', accountId: '' });
    expect(readCardSelection(undefined)).toEqual({ cardNumber: '', accountId: '' });
    expect(readCardSelection({ cardNumber: 42 })).toEqual({
      cardNumber: '',
      accountId: '',
    });
  });
});
