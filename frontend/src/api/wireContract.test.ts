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
 *     card number; and that no transaction request carries its key in the URL.
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
const postCalls: RecordedCall[] = [];

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

const postMock = jest.fn(
  (
    url: string,
    body: unknown,
    config?: { params?: Record<string, unknown> },
  ) => {
    postCalls.push({ url, body, config });
    return Promise.resolve({ data: {} });
  },
);

jest.unstable_mockModule('./client', () => ({
  __esModule: true,
  default: {
    get: getMock,
    put: putMock,
    post: postMock,
    delete: jest.fn(),
  },
}));

const { listUsers } = await import('./users');
const { listCards, getCard, updateCard } = await import('./cards');
const { getTransaction, resolveAddKey, addTransaction } = await import(
  './transactions'
);
const {
  CARD_DETAIL_ROUTE,
  CARD_UPDATE_ROUTE,
  MAIN_MENU_ROUTE,
  readCardSelection,
  resolveExitRoute,
} = await import(
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
  postCalls.length = 0;
  getMock.mockClear();
  putMock.mockClear();
  postMock.mockClear();
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
  it('submits the composite key in the BODY of the detail read, not the URL', async () => {
    await getCard('4111111111111111', '00000000011');

    // The read is a POST because its key is a PAN: a URL is written verbatim into every
    // access log, proxy log and trace on the path, a request body is not.
    expect(postCalls).toHaveLength(1);
    expect(postCalls[0].url).toBe('/cards/detail');
    expect(postCalls[0].body).toEqual({
      cardNumber: '4111111111111111',
      accountId: '00000000011',
    });
    expect(postCalls[0].config?.params).toBeUndefined();
    expect(getCalls).toHaveLength(0);
  });

  it('omits ACCTSID when the screen collected none', async () => {
    await getCard('4111111111111111');
    expect(postCalls[0].body).toEqual({
      cardNumber: '4111111111111111',
      accountId: undefined,
    });
  });

  it('sends the key, ACCTSID and the snapshot in the update BODY', async () => {
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

    expect(putCalls[0].url).toBe('/cards');
    expect(putCalls[0].config?.params).toBeUndefined();
    expect(putCalls[0].body).toMatchObject({
      cardNumber: '4111111111111111',
      accountId: '00000000011',
      version: 4,
      oldCardActiveStatus: 'Y',
    });
  });

  it('never puts a card number in a request URL or query string', async () => {
    const pan = '4111111111111111';
    await getCard(pan, '00000000011');
    await updateCard(
      pan,
      {
        cardEmbossedName: 'JANE Q DOE',
        cardActiveStatus: 'Y',
        cardExpiraionDate: '2028-01-31',
        version: 1,
      },
      '00000000011',
    );
    await listCards({ cardNum: pan, page: 1 });

    // The browse filter is a user-entered search term the legacy map also echoed, and it
    // travels as a query parameter; the two record-addressing calls must not put the PAN
    // anywhere a log can reach.
    for (const call of [...postCalls, ...putCalls]) {
      expect(call.url).not.toContain(pan);
      expect(JSON.stringify(call.config?.params ?? {})).not.toContain(pan);
    }
    for (const call of getCalls) {
      expect(call.url).not.toContain(pan);
    }
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
      readCardSelection({
        cardNumber: ' 4111111111111111 ',
        accountId: ' 11 ',
        from: ' /cards ',
      }),
    ).toEqual({ cardNumber: '4111111111111111', accountId: '11', from: '/cards' });
  });

  it('carries the caller route so PF3 can return to it, and the menu when it is absent', () => {
    // CDEMO-FROM-PROGRAM in COMMAREA form: COCRDSLC L308-321 and COCRDUPC L442-455
    // transfer to the recorded caller and substitute LIT-MENUPGM only when none arrived.
    expect(resolveExitRoute('/cards')).toBe('/cards');
    expect(resolveExitRoute('')).toBe(MAIN_MENU_ROUTE);
    expect(MAIN_MENU_ROUTE).toBe('/menu');
    expect(readCardSelection({ cardNumber: '4111111111111111' }).from).toBe('');
  });

  it('yields an empty selection when no state arrived', () => {
    expect(readCardSelection(null)).toEqual({ cardNumber: '', accountId: '', from: '' });
    expect(readCardSelection(undefined)).toEqual({
      cardNumber: '',
      accountId: '',
      from: '',
    });
    expect(readCardSelection({ cardNumber: 42 })).toEqual({
      cardNumber: '',
      accountId: '',
      from: '',
    });
  });
});

/** :purpose: A 16-digit PAN, the one payload member no request URL may carry. */
const CARD_NUM = '4111111111111111';

describe('transaction read/key wire contract (COTRN01C / COTRN02C)', () => {
  it('sends the transaction id as a request parameter, never as a path segment', async () => {
    await getTransaction('0000000000000042');
    // TRNIDIN is an X(16) field an operator may fill with any characters. A value
    // holding a path separator does not survive a path segment: an intermediary
    // decodes the encoded form and collapses the resulting ".." before it routes,
    // which lifted the request clean out of the API prefix and had the SPA document
    // answered instead of JSON -- the screen sat silently dead. A query string is
    // not path-normalized, so the value reaches the service verbatim.
    expect(getCalls[0].url).toBe('/transactions/detail');
    expect(lastGetParams().tranId).toBe('0000000000000042');
  });

  it('keeps a separator-bearing id out of the request path', async () => {
    await getTransaction('../../etc/passwd');
    expect(getCalls[0].url).toBe('/transactions/detail');
    expect(getCalls[0].url).not.toContain('..');
    expect(lastGetParams().tranId).toBe('../../etc/passwd');
  });

  it('resolves the add screen keys through a POST body, never a URL', async () => {
    await resolveAddKey({ tranCardNum: CARD_NUM });
    expect(postCalls[0].url).toBe('/transactions/key');
    expect(postCalls[0].url).not.toMatch(/\d{11,}/);
    expect(postCalls[0].body).toEqual({ tranCardNum: CARD_NUM });
  });

  it('never puts a card number in a request URL or query string', async () => {
    // The transaction id itself is a legitimate query parameter -- it is the value the
    // read is keyed on, and it is neither a credential nor an account identifier. The
    // card number is different: it is the one member of these payloads a URL must never
    // carry, because a URL is recorded verbatim by every intermediary on the path
    // (access logs, proxies, browser history) whereas a POST body is not. That is why
    // key resolution and the add itself are POSTs.
    await getTransaction('0000000000000042');
    await resolveAddKey({ acctId: '00000000050', tranCardNum: CARD_NUM });
    await addTransaction({
      acctId: '00000000050',
      tranCardNum: CARD_NUM,
    } as Parameters<typeof addTransaction>[0]);

    const urls = [...getCalls, ...postCalls, ...putCalls].map(
      (call) =>
        call.url +
        (call.config?.params
          ? '?' +
            new URLSearchParams(
              Object.entries(call.config.params).map(([k, v]) => [k, String(v)]),
            ).toString()
          : ''),
    );
    expect(urls).not.toHaveLength(0);
    for (const url of urls) {
      expect(url).not.toContain(CARD_NUM);
      expect(url).not.toMatch(/\d{16}\b(?!$)/);
    }
    // and the card number is present in the bodies, which is where it belongs
    expect(JSON.stringify(postCalls.map((call) => call.body))).toContain(CARD_NUM);
  });
});
