/**
 * CardUpdatePage tests
 * ====================
 *
 * :purpose: Verify the ``COCRDUP`` card-update workflow (CICS ``CCUP``,
 *     ``app/cbl/COCRDUPC.cbl``): the initial read of the routed card, the seeded
 *     and editable ``CRDNAME`` / ``CRDSTCD`` / ``EXPMON`` / ``EXPYEAR`` fields
 *     beside the protected ``ACCTSID`` / ``CARDSID`` keys, the verbatim edit
 *     messages of ``1200-EDIT-MAP-INPUTS``, the ``Changes validated.Press F5 to
 *     save`` confirmation prompt, the F5 rewrite (request shape, preserved
 *     ``cardExpiraionDate``, failure and HTTP 409 outcomes), and the line-24
 *     function keys ``ENTER=Process`` / ``F3=Exit`` / ``F5=Save`` /
 *     ``F12=Cancel``.
 * :note: ``../api`` is replaced by a module mock, so no axios instance, no
 *     network, and no Vite ``import.meta`` is evaluated. Under Jest's native-ESM
 *     runtime the registration is ``jest.unstable_mockModule``, and the page, the
 *     screen shell, and the session seam are imported dynamically afterwards so
 *     they bind to the mock while sharing one React instance with Testing
 *     Library.
 * :note: The page publishes its line-23 message and line-24 function keys into
 *     the shared shell through ``useScreenChrome``, so every screen is rendered
 *     inside ``Layout`` where those regions exist.
 */

import { act, render, screen, fireEvent, waitFor, within } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { ReactElement } from 'react';
import type {
  ApiErrorResponse,
  CardDetailResponseDto,
  CardUpdateRequestDto,
  CardUpdateResponseDto,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';

/** Card number (PAN) of the routed record; ``app/data/ASCII/carddata.txt`` row 1. */
const CARD_NUMBER = '0500024453765740';

/** Owning account id of that card (``CARD-ACCT-ID``, 11 digits). */
const ACCOUNT_ID = '00000000050';

/** Owning customer id from the ``CXACAIX`` cross-reference (``app/data/ASCII/cardxref.txt``). */
const CUSTOMER_ID = '000000050';

/** Signed-in standard user (``CDEMO-USER-ID`` / ``CDEMO-USRTYP-USER``). */
const SESSION_USER = 'USER0001';

/** The record ``getCard`` returns for :data:`CARD_NUMBER`. */
const cardRecord: CardDetailResponseDto = {
  cardNum: CARD_NUMBER,
  cardAcctId: ACCOUNT_ID,
  cardEmbossedName: 'Aniya Von',
  cardActiveStatus: 'Y',
  cardExpiraionDate: '2023-03-09',
  custId: CUSTOMER_ID,
  version: 3,
};

/** The refreshed record ``updateCard`` returns after a successful rewrite. */
const updatedRecord: CardUpdateResponseDto = {
  ...cardRecord,
  cardEmbossedName: 'ANIYA VON UPDATED',
  cardActiveStatus: 'N',
  cardExpiraionDate: '2023-07-09',
  version: 4,
};

/** ``WS-PROMPT-FOR-NAME`` (COCRDUPC L182). */
const PROMPT_FOR_NAME = 'Card name not provided';

/** ``WS-NAME-MUST-BE-ALPHA`` (COCRDUPC L184). */
const NAME_MUST_BE_ALPHA = 'Card name can only contain alphabets and spaces';

/** ``WS-NO-CHANGES-DETECTED`` (COCRDUPC L188). */
const NO_CHANGES_DETECTED = 'No change detected with respect to values fetched.';

/** ``WS-PROMPT-FOR-ACCT`` (COCRDUPC L178). */
const PROMPT_FOR_ACCT = 'Account number not provided';

/** ``WS-NO-INPUT-RECEIVED`` (COCRDUPC L186). */
const NO_SEARCH_CRITERIA_RECEIVED = 'No input received';

/**
 * The literal ``1210-EDIT-ACCOUNT`` MOVEs for a non-numeric account key (COCRDUPC L745).
 * ``SEARCHED-ACCT-NOT-NUMERIC`` and ``SEARCHED-ACCT-ZEROES`` (L189-L192) carry the
 * mixed-case text but are never SET, so this upper-case form is the only one reachable.
 */
const ACCOUNT_FILTER_NOT_NUMERIC = 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/** ``FOUND-CARDS-FOR-ACCOUNT``. */
const FOUND_CARDS_FOR_ACCOUNT = 'Details of selected card shown above';

/** ``PROMPT-FOR-SEARCH-KEYS`` — the ``CDEMO-PGM-ENTER`` branch of 3250-SETUP-INFOMSG. */
const PROMPT_FOR_SEARCH_KEYS = 'Please enter Account and Card Number';

/** ``PROMPT-FOR-CHANGES`` — the ``CCUP-CHANGES-NOT-OK`` branch of 3250-SETUP-INFOMSG. */
const PROMPT_FOR_CHANGES = 'Update card details presented above.';

/** ``PROMPT-FOR-CONFIRMATION`` — no space follows the period. */
const PROMPT_FOR_CONFIRMATION = 'Changes validated.Press F5 to save';

/** ``CONFIRM-UPDATE-SUCCESS``. */
const CONFIRM_UPDATE_SUCCESS = 'Changes committed to database';

/** ``INFORM-FAILURE``. */
const INFORM_FAILURE = 'Changes unsuccessful. Please try again';

/**
 * The literal ``1220-EDIT-CARD`` MOVEs for a non-numeric card key (COCRDUPC L789), the
 * unreachable ``SEARCHED-CARD-NOT-NUMERIC`` 88-level (L193-L194) notwithstanding.
 */
const CARD_FILTER_NOT_NUMERIC = 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/** ``CARD-STATUS-MUST-BE-YES-NO``. */
const CARD_STATUS_MUST_BE_YES_NO = 'Card Active Status must be Y or N';

/** ``CARD-EXPIRY-MONTH-NOT-VALID``. */
const CARD_EXPIRY_MONTH_NOT_VALID = 'Card expiry month must be between 1 and 12';

/** ``CARD-EXPIRY-YEAR-NOT-VALID``. */
const CARD_EXPIRY_YEAR_NOT_VALID = 'Invalid card expiry year';

/** ``DATA-WAS-CHANGED-BEFORE-UPDATE`` — the HTTP 409 optimistic-lock outcome. */
const DATA_WAS_CHANGED_BEFORE_UPDATE = 'Record changed by some one else. Please review';

/** ``GET /cards/{cardNumber}`` (CICS ``CCDL`` read performed on entry). */
const getCardMock = jest.fn<(cardNumber: string) => Promise<CardDetailResponseDto>>();

/**
 * ``PUT /cards/{cardNumber}`` (the ``COCRDUPC`` rewrite). The ``ACCTSID`` completing
 * the composite selection travels as the third argument, exactly as on ``getCard``.
 */
const updateCardMock =
  jest.fn<
    (
      cardNumber: string,
      request: CardUpdateRequestDto,
      accountId?: string,
    ) => Promise<CardUpdateResponseDto>
  >();

/** ``POST /auth/signon``; reached only through the session store's own import. */
const signonMock = jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

/**
 * :purpose: The normalized REST error the api client raises, reproduced with the
 *     same public surface and exported by the ``../api`` mock so the page's
 *     ``instanceof`` branches bind to this class.
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: resolved error message.
 * :param body: standardized backend error body when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the ``409`` conflict.
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
  updateCard: updateCardMock,
  signon: signonMock,
  ApiError,
}));

type CardUpdatePageComponent = (typeof import('./CardUpdatePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let CardUpdatePage: CardUpdatePageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock registration so every ``../api`` binding in the
  // graph resolves to the mock; no module reset, so React stays shared with
  // Testing Library.
  ({ default: CardUpdatePage } = await import('./CardUpdatePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getCardMock.mockReset();
  updateCardMock.mockReset();
  signonMock.mockReset();
  getCardMock.mockResolvedValue(cardRecord);
  updateCardMock.mockResolvedValue(cardRecord);
  await seedSignedOnSession(SESSION_USER, 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: Publish the current router pathname so a PF-key navigation is
 *     observable.
 * :returns: The rendered pathname probe.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

/**
 * :purpose: Render the card-update screen inside the shared shell on the
 *     ``/cards/update`` route, alongside the card-detail and card-list routes its
 *     function keys navigate to. The composite selection arrives in the router
 *     location state, so neither the account id nor the card number is ever part of
 *     this screen's URL.
 * :param cardNumber: the handed-over ``CARDSID`` key.
 */
function renderScreen(cardNumber: string = CARD_NUMBER): void {
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: '/cards/update',
          // `from` carries CDEMO-FROM-PROGRAM: the list records itself so PF3 returns there.
          state: { cardNumber, accountId: ACCOUNT_ID, from: '/cards' },
        },
      ]}
    >
      <Routes>
        <Route
          path="/cards/update"
          element={
            <Layout>
              <CardUpdatePage />
            </Layout>
          }
        />
        <Route path="/cards/view" element={<span data-testid="card-detail-screen" />} />
        <Route path="/cards" element={<span data-testid="card-list-screen" />} />
        <Route path="/menu" element={<span data-testid="main-menu-screen" />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * :purpose: Render the screen with NO hand-over in the router location state — the
 *     state a reload, a bookmark or main-menu option 5 arrives in, and the state a 3270
 *     transaction started without a COMMAREA is in.
 */
function renderEntryScreen(): void {
  render(
    <MemoryRouter initialEntries={['/cards/update']}>
      <Routes>
        <Route
          path="/cards/update"
          element={
            <Layout>
              <CardUpdatePage />
            </Layout>
          }
        />
        <Route path="/cards/view" element={<span data-testid="card-detail-screen" />} />
        <Route path="/cards" element={<span data-testid="card-list-screen" />} />
        <Route path="/menu" element={<span data-testid="main-menu-screen" />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * :purpose: Render the screen and wait for the entry read to seed the fields.
 * :param cardNumber: the routed ``CARDSID`` key.
 */
async function renderLoadedScreen(cardNumber: string = CARD_NUMBER): Promise<void> {
  renderScreen(cardNumber);
  await waitFor(() => {
    expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
  });
}

/**
 * :purpose: Read the line-23 error region (rendered RED, ``role="alert"``).
 * :returns: The exact error text currently displayed.
 */
function errorText(): string {
  return screen.getByRole('alert').textContent ?? '';
}

/**
 * :purpose: Read the line-23 informational region (``role="status"``).
 * :returns: The exact informational text currently displayed.
 */
function infoText(): string {
  return infoBanner()?.textContent ?? '';
}

/**
 * :purpose: Activate a line-24 function key by its legend label.
 * :param label: the full legend text, for example ``F5=Save``.
 */
function pressKey(label: string): void {
  fireEvent.click(screen.getByRole('button', { name: label }));
}

/**
 * :purpose: Raise a physical AID keystroke on the document, which is how a darkened
 *     function key reaches the screen: the shell withholds a dark key from the rendered
 *     legend but still dispatches it from the keyboard.
 * :param key: the ``KeyboardEvent.key`` value, for example ``F5``.
 */
function pressPhysicalKey(key: string): void {
  fireEvent.keyDown(document, { key });
}

/**
 * :purpose: Read the line-24 legend the screen currently publishes, in rendered order.
 * :returns: The legend labels of the function keys that are not darkened.
 */
function legendLabels(): (string | null)[] {
  return within(screen.getByRole('group', { name: 'Function keys' }))
    .getAllByRole('button')
    .map((button) => button.textContent);
}

/**
 * :purpose: Type a value into one of the editable map fields.
 * :param testId: the field's test id (``crdname`` / ``crdstcd`` / ``expmon`` /
 *     ``expyear``).
 * :param value: the value to type.
 */
function typeInto(testId: string, value: string): void {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

/**
 * :purpose: Alter one field so the submission differs from the record that was
 *     fetched. ``1200-EDIT-MAP-INPUTS`` tests ``CCUP-NEW-CARDDATA`` against
 *     ``CCUP-OLD-CARDDATA`` BEFORE any field edit and exits when they match, so a
 *     screen that has not been altered never reaches validation or the rewrite.
 */
function makeChange(): void {
  typeInto('crdname', 'ANIYA VON UPDATED');
}

/**
 * :purpose: The mapset's own INFOMSG field -- the NEUTRAL line the map declares ABOVE the
 *     line-23 ERRMSG region. It is matched on that field's class rather than on the role,
 *     both because the shared shell renders a visually hidden ``role="status"`` busy
 *     announcer and because the ERRMSG field itself carries ``role="status"`` on the
 *     screens whose programs green it.
 * :returns: the INFOMSG line, or ``null`` when the field is not rendered.
 */
function infoBanner(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.errorBanner--infoField[role="status"]');
}

describe('CardUpdatePage — entry read (COCRDUP / CCUP)', () => {
  it('reads the routed card once and seeds the editable fields', async () => {
    await renderLoadedScreen();

    expect(getCardMock).toHaveBeenCalledTimes(1);
    // COCRDUPC reads by card number and qualifies the record with ACCTSID, both
    // of which the card list handed over.
    expect(getCardMock).toHaveBeenCalledWith(CARD_NUMBER, ACCOUNT_ID);
    expect(screen.getByTestId('crdname')).toHaveValue('Aniya Von');
    expect(screen.getByTestId('crdstcd')).toHaveValue('Y');
    expect(screen.getByTestId('expmon')).toHaveValue('03');
    expect(screen.getByTestId('expyear')).toHaveValue('2023');
    expect(infoText()).toBe(FOUND_CARDS_FOR_ACCOUNT);
  });

  it('passes the card number as a string, never a number', async () => {
    await renderLoadedScreen();

    expect(typeof getCardMock.mock.calls[0][0]).toBe('string');
    expect(getCardMock.mock.calls[0][0]).toBe(CARD_NUMBER);
  });

  it('keeps the account number and the card number protected', async () => {
    await renderLoadedScreen();

    // ACCTSID and CARDSID are ``DFHBMPRF`` on this map, so they render as labelled
    // output cells: nothing about them is enterable.
    const accountField = screen.getByTestId('acctsid');
    expect(accountField.textContent).toBe(ACCOUNT_ID);
    expect(accountField.tagName).toBe('DD');

    const cardField = screen.getByTestId('cardsid');
    expect(cardField.textContent).toBe(CARD_NUMBER);
    expect(cardField.tagName).toBe('DD');
  });

  it('renders the BMS field widths of COCRDUP.CPY', async () => {
    await renderLoadedScreen();

    expect(screen.getByTestId('acctsid').style.minWidth).toBe('11ch');
    expect(screen.getByTestId('cardsid').style.minWidth).toBe('16ch');
    expect(screen.getByTestId('crdname')).toHaveAttribute('maxlength', '50');
    expect(screen.getByTestId('crdstcd')).toHaveAttribute('maxlength', '1');
    expect(screen.getByTestId('expmon')).toHaveAttribute('maxlength', '2');
    expect(screen.getByTestId('expyear')).toHaveAttribute('maxlength', '4');
  });

  it('renders the BMS captions verbatim and associates them with their fields', async () => {
    await renderLoadedScreen();

    // The protected cells are named through ``aria-labelledby``, so their captions
    // are read from the caption term rather than from a ``label[for]`` pairing. The
    // caption is compared against the term's own text because a text or accessible-name
    // query collapses the column padding these BMS literals carry.
    expect(document.querySelector('dt#acctsid-label')?.textContent).toBe(
      'Account Number    :',
    );
    expect(screen.getByTestId('acctsid')).toHaveAccessibleName('Account Number :');
    expect(document.querySelector('dt#cardsid-label')?.textContent).toBe(
      'Card Number       :',
    );
    expect(screen.getByTestId('cardsid')).toHaveAccessibleName('Card Number :');
    expect(document.querySelector('label[for="crdname"]')?.textContent).toBe(
      'Name on card      :',
    );
    expect(document.querySelector('label[for="crdstcd"]')?.textContent).toBe(
      'Card Active Y/N   :',
    );
    expect(document.querySelector('label[for="expmon"]')?.textContent).toBe(
      'Expiry Date       :',
    );
    expect(screen.getByLabelText('Name on card :')).toBe(screen.getByTestId('crdname'));
    // Row 15 carries one caption for two fields, so each field names itself, and both
    // names begin with the rendered caption text.
    expect(screen.getByLabelText('Expiry Date Month')).toBe(screen.getByTestId('expmon'));
    expect(screen.getByLabelText('Expiry Date Year')).toBe(screen.getByTestId('expyear'));
  });

  it('publishes the transaction id, program name and titles to the shell', async () => {
    await renderLoadedScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CCUP');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COCRDUPC');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
  });

  it('lets the operator edit the name, the status, the month and the year', async () => {
    await renderLoadedScreen();

    typeInto('crdname', 'ANIYA VON UPDATED');
    typeInto('crdstcd', 'N');
    typeInto('expmon', '11');
    typeInto('expyear', '2031');

    expect(screen.getByTestId('crdname')).toHaveValue('ANIYA VON UPDATED');
    expect(screen.getByTestId('crdstcd')).toHaveValue('N');
    expect(screen.getByTestId('expmon')).toHaveValue('11');
    expect(screen.getByTestId('expyear')).toHaveValue('2031');
  });

  it('surfaces a failed read on line 23', async () => {
    getCardMock.mockRejectedValue(new ApiError(404, 'Card not found'));
    renderScreen();

    await waitFor(() => {
      expect(errorText()).toBe('Card not found');
    });
    expect(screen.getByTestId('crdname')).toHaveValue('');
  });
});

describe('CardUpdatePage — map-input edits (1200-EDIT-MAP-INPUTS)', () => {
  it('rejects an active status that is neither Y nor N', async () => {
    await renderLoadedScreen();

    typeInto('crdstcd', 'X');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_STATUS_MUST_BE_YES_NO);
    // 1200-EDIT-MAP-INPUTS SETs CCUP-CHANGES-NOT-OK (L696) before it runs the field
    // edits, and 3250-SETUP-INFOMSG answers that state with PROMPT-FOR-CHANGES, so the
    // INFOMSG line invites the correction beside the refusal on ERRMSG.
    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CHANGES);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('rejects a blank active status', async () => {
    await renderLoadedScreen();

    typeInto('crdstcd', '');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_STATUS_MUST_BE_YES_NO);
  });

  it('accepts both Y and N as the active status', async () => {
    await renderLoadedScreen();

    // The record was fetched with status 'Y', so each value is exercised against a
    // submission that differs from it somewhere: 'N' differs on the status itself, and
    // 'Y' is carried alongside an altered name.
    typeInto('crdstcd', 'N');
    pressKey('ENTER=Process');
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);

    makeChange();
    typeInto('crdstcd', 'Y');
    pressKey('ENTER=Process');
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
  });

  it('rejects an expiry month above 12', async () => {
    await renderLoadedScreen();

    typeInto('expmon', '13');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_EXPIRY_MONTH_NOT_VALID);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('rejects an expiry month below 1', async () => {
    await renderLoadedScreen();

    typeInto('expmon', '0');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_EXPIRY_MONTH_NOT_VALID);
  });

  it('rejects a blank expiry month', async () => {
    await renderLoadedScreen();

    typeInto('expmon', '');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_EXPIRY_MONTH_NOT_VALID);
  });

  it('rejects an expiry year outside 1950 through 2099', async () => {
    await renderLoadedScreen();

    typeInto('expyear', '1949');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_EXPIRY_YEAR_NOT_VALID);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('rejects a non-numeric expiry year', async () => {
    await renderLoadedScreen();

    typeInto('expyear', '20X3');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_EXPIRY_YEAR_NOT_VALID);
  });

  it('rejects a handed-over card number that is not 16 digits and issues no read', () => {
    renderScreen('12345');

    expect(errorText()).toBe(CARD_FILTER_NOT_NUMERIC);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('publishes only the first failing edit in the COCRDUPC edit order', async () => {
    await renderLoadedScreen();

    typeInto('crdstcd', 'X');
    typeInto('expmon', '13');
    typeInto('expyear', '1949');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_STATUS_MUST_BE_YES_NO);
  });

  it('reports the card name ahead of the status, the month and the year', async () => {
    await renderLoadedScreen();

    // 1200-EDIT-MAP-INPUTS runs 1230-EDIT-NAME first, then 1240-EDIT-CARDSTATUS,
    // 1250-EDIT-EXPIRY-MON and 1260-EDIT-EXPIRY-YEAR, and only the first failure
    // publishes its message (IF WS-RETURN-MSG-OFF).
    typeInto('crdname', '   ');
    typeInto('crdstcd', 'X');
    typeInto('expmon', '13');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(PROMPT_FOR_NAME);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('reports the alphabetic rule only once the name is present', async () => {
    await renderLoadedScreen();

    // 1230-EDIT-NAME reports absence and stops (GO TO 1230-EDIT-NAME-EXIT), so the
    // alphabetic rule is reached only by a name that was actually supplied.
    typeInto('crdname', 'ANIYA 9');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(NAME_MUST_BE_ALPHA);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('accepts a name of alphabetic characters and spaces', async () => {
    await renderLoadedScreen();

    typeInto('crdname', 'ANIYA VON DEL RIO');
    pressKey('ENTER=Process');

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('refuses a submission that changes nothing, and offers no rewrite', async () => {
    await renderLoadedScreen();

    // 1200-EDIT-MAP-INPUTS compares UPPER-CASE(CCUP-NEW-CARDDATA) with
    // UPPER-CASE(CCUP-OLD-CARDDATA) BEFORE any field edit and exits when they match,
    // so an unchanged screen is never validated and never reported as committed.
    pressKey('ENTER=Process');

    expect(errorText()).toBe(NO_CHANGES_DETECTED);
    // That comparison GO TOes 1200-EDIT-MAP-INPUTS-EXIT at L691, BEFORE L696 SETs
    // CCUP-CHANGES-NOT-OK, so the state is still CCUP-SHOW-DETAILS and 3250 keeps
    // FOUND-CARDS-FOR-ACCOUNT on the INFOMSG line.
    expect(infoBanner()).toHaveTextContent(FOUND_CARDS_FOR_ACCOUNT);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('compares the submission against the record without regard to case', async () => {
    await renderLoadedScreen();

    // The legacy comparison is between two FUNCTION UPPER-CASE values, so re-keying the
    // fetched name in a different case is not a change.
    typeInto('crdname', (cardRecord.cardEmbossedName ?? '').toUpperCase());
    pressKey('ENTER=Process');

    expect(errorText()).toBe(NO_CHANGES_DETECTED);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('refuses an unchanged rewrite arriving on F5 as well as on ENTER', async () => {
    await renderLoadedScreen();

    pressPhysicalKey('F5');

    expect(errorText()).toBe(NO_CHANGES_DETECTED);
    expect(updateCardMock).not.toHaveBeenCalled();
  });
});

describe('CardUpdatePage — confirmation prompt (PROMPT-FOR-CONFIRMATION)', () => {
  it('asks for the F5 confirmation once the edits are clean, without saving', async () => {
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    expect(screen.getByText(PROMPT_FOR_CONFIRMATION)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('protects every field while the F5 confirmation is outstanding', async () => {
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);

    // WHEN CCUP-CHANGES-OK-NOT-CONFIRMED moves DFHBMPRF over ALL SIX fields: the two keys
    // are already output cells once a record is displayed, and the four detail fields
    // stop accepting input until the rewrite is confirmed or abandoned.
    for (const field of ['crdname', 'crdstcd', 'expmon', 'expyear']) {
      expect(screen.getByTestId(field)).toHaveAttribute('readonly');
    }
    expect(screen.getByTestId('acctsid').tagName).toBe('DD');
    expect(screen.getByTestId('cardsid').tagName).toBe('DD');
  });

  it('renders the prompt with no space after the period', async () => {
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');

    expect(infoText()).toBe('Changes validated.Press F5 to save');
    expect(infoText()).not.toContain('validated. Press');
  });
});

describe('CardUpdatePage — in-flight duplicate-rewrite guard', () => {
  it('closes the entry fields while the rewrite is in flight and writes once for a repeated F5', async () => {
    let acknowledge!: (value: CardUpdateResponseDto) => void;
    updateCardMock.mockReturnValueOnce(
      new Promise<CardUpdateResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    await renderLoadedScreen();

    typeInto('crdname', 'ANIYA VON UPDATED');
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
    // ``ATTRB=ASKIP`` for the whole in-flight interval: the record cannot be re-edited
    // while the optimistic-locked PUT is outstanding.
    expect(screen.getByTestId('crdname')).toBeDisabled();
    expect(screen.getByTestId('crdstcd')).toBeDisabled();

    // A second F5 must not re-issue the rewrite against a stale ``version``.
    pressKey('F5=Save');
    pressPhysicalKey('F5');
    expect(updateCardMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge(updatedRecord);
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(updateCardMock).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('crdname')).toBeEnabled();
  });
});

describe('CardUpdatePage — F5 rewrite (PUT /cards/{cardNumber})', () => {
  it('sends the edited fields, the rebuilt expiration date and the read version', async () => {
    updateCardMock.mockResolvedValue(updatedRecord);
    await renderLoadedScreen();

    typeInto('crdname', 'ANIYA VON UPDATED');
    typeInto('crdstcd', 'N');
    typeInto('expmon', '7');
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
    // Alongside the edited fields and the read version, the request carries the
    // values read at display time so the service can reproduce the field-by-field
    // comparison COCRDUPC performs before its REWRITE.
    expect(updateCardMock).toHaveBeenCalledWith(
      CARD_NUMBER,
      {
        cardEmbossedName: 'ANIYA VON UPDATED',
        cardActiveStatus: 'N',
        cardExpiraionDate: '2023-07-09',
        version: 3,
        oldCardEmbossedName: 'Aniya Von',
        oldCardActiveStatus: 'Y',
        oldCardExpiraionDate: '2023-03-09',
      },
      ACCOUNT_ID,
    );
    expect(typeof updateCardMock.mock.calls[0][0]).toBe('string');
  });

  it('preserves an untouched expiration date verbatim', async () => {
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
    expect(updateCardMock.mock.calls[0][1].cardExpiraionDate).toBe('2023-03-09');
  });

  it('confirms the committed rewrite and re-seeds the fields from the refreshed record', async () => {
    updateCardMock.mockResolvedValue(updatedRecord);
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(infoText()).toBe(CONFIRM_UPDATE_SUCCESS);
    });
    expect(screen.getByTestId('crdname')).toHaveValue('ANIYA VON UPDATED');
    expect(screen.getByTestId('crdstcd')).toHaveValue('N');
    expect(screen.getByTestId('expmon')).toHaveValue('07');
    expect(screen.getByTestId('expyear')).toHaveValue('2023');
  });

  it('edits the map inputs first when F5 arrives before ENTER=Process', async () => {
    await renderLoadedScreen();

    // COCRDUP.bms declares FKEYSC — the field carrying both ``F5=Save`` and
    // ``F12=Cancel`` — DRK, and L1315-1317 un-darkens it only while the confirmation
    // is prompted, so the legend offers no F5 yet. The AID still reaches the screen
    // from the keyboard, and COCRDUPC L413-423 turns an out-of-state F5 into the edit
    // pass rather than into a rewrite.
    makeChange();
    pressPhysicalKey('F5');

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    expect(updateCardMock).not.toHaveBeenCalled();

    // Now that the edits are confirmed the same key commits them. The two presses are two
    // keystrokes, so the test yields between them: a repeat inside ONE task is the
    // duplicate AID the keyboard latch exists to swallow.
    await waitFor(() => {
      expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    });
    pressPhysicalKey('F5');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
  });

  it('does not rewrite when an edit fails', async () => {
    await renderLoadedScreen();

    typeInto('expmon', '13');
    pressPhysicalKey('F5');

    expect(errorText()).toBe(CARD_EXPIRY_MONTH_NOT_VALID);
    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CHANGES);
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('reports an unsuccessful rewrite', async () => {
    updateCardMock.mockRejectedValue(new ApiError(500, 'Internal Server Error'));
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(INFORM_FAILURE);
    });
    // INFORM-FAILURE is declared under WS-INFO-MSG (L170-171), and 3250 answers
    // CCUP-CHANGES-OKAYED-BUT-FAILED with it, so it belongs on the INFOMSG line.
    expect(infoBanner()).toHaveTextContent(INFORM_FAILURE);
  });

  it('reports an unsuccessful rewrite for a transport failure', async () => {
    updateCardMock.mockRejectedValue(new Error('socket hang up'));
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(INFORM_FAILURE);
    });
  });

  it('prefers the backend message when the rejection carries an error body', async () => {
    const errorBody: ApiErrorResponse = {
      timestamp: '2026-01-15T10:20:30.123456',
      status: 400,
      error: 'Bad Request',
      message: 'Card Active Status must be Y or N',
      path: `/cards/${CARD_NUMBER}`,
    };
    updateCardMock.mockRejectedValue(new ApiError(400, 'Bad Request', errorBody));
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe('Card Active Status must be Y or N');
    });
  });

  it('reports a concurrent change on an HTTP 409 optimistic-lock conflict', async () => {
    updateCardMock.mockRejectedValue(new ApiError(409, 'Conflict', undefined, true));
    await renderLoadedScreen();

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(DATA_WAS_CHANGED_BEFORE_UPDATE);
    });
    // L997-998 answers DATA-WAS-CHANGED-BEFORE-UPDATE with CCUP-SHOW-DETAILS, not with
    // a failure state, so 3250 selects FOUND-CARDS-FOR-ACCOUNT.
    expect(infoBanner()).toHaveTextContent(FOUND_CARDS_FOR_ACCOUNT);
  });

  it('puts the record back on display after the conflict so the retry is not doomed', async () => {
    // L997-998 answers the concurrency branch with CCUP-SHOW-DETAILS, and L1107-1112
    // paints that state from CCUP-OLD-* — the record, not the edited CCUP-NEW-*. So the
    // winner's value is what the operator reviews, and the version a retry carries is the
    // one just read rather than the one that already lost.
    const winner = { ...cardRecord, cardEmbossedName: 'WINNER NAME', version: 9 };
    updateCardMock.mockRejectedValueOnce(new ApiError(409, 'Conflict', undefined, true));
    await renderLoadedScreen();
    getCardMock.mockResolvedValue(winner);

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(DATA_WAS_CHANGED_BEFORE_UPDATE);
    });
    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveValue('WINNER NAME');
    });
    // The conflict message stays on the line-24 error field through the re-read.
    expect(errorText()).toBe(DATA_WAS_CHANGED_BEFORE_UPDATE);

    updateCardMock.mockResolvedValue({ ...winner, cardEmbossedName: 'RETRY NAME', version: 10 });
    typeInto('crdname', 'RETRY NAME');
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(2);
    });
    const retried = updateCardMock.mock.calls[1][1] as { version: number };
    expect(retried.version).toBe(9);
  });
});

describe('CardUpdatePage — line-24 function keys', () => {
  it('renders ENTER=Process, F3=Exit, F5=Save and F12=Cancel in mapset order', async () => {
    await renderLoadedScreen();

    // COCRDUP.bms row 24 carries 'ENTER=Process  F3=Exit' unconditionally and the
    // DRK field holding 'F5=Save  F12=Cancel' only while the confirmation is
    // prompted (L1315-1317), so the freshly displayed screen legends two keys.
    expect(legendLabels()).toEqual(['ENTER=Process', 'F3=Exit']);

    makeChange();
    pressKey('ENTER=Process');

    expect(legendLabels()).toEqual([
      'ENTER=Process',
      'F3=Exit',
      'F5=Save',
      'F12=Cancel',
    ]);
  });

  it('repaints the card in place on F12=Cancel, discarding the edits', async () => {
    await renderLoadedScreen();
    expect(getCardMock).toHaveBeenCalledTimes(1);

    // F12 joins the legend with F5, once the edits are validated.
    makeChange();
    pressKey('ENTER=Process');
    pressKey('F12=Cancel');

    // COCRDUPC L958-966 answers PF12 by re-running 9000-READ-DATA and setting
    // CCUP-SHOW-DETAILS: the operator keeps this screen and this record, with the
    // stored values restored over their edits.
    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
    });
    expect(screen.getByTestId('location').textContent).toBe('/cards/update');
    expect(screen.queryByTestId('card-detail-screen')).toBeNull();
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('returns to the card list screen on F3=Exit', async () => {
    await renderLoadedScreen();

    pressKey('F3=Exit');

    expect(screen.getByTestId('location').textContent).toBe('/cards');
    expect(screen.getByTestId('card-list-screen')).toBeInTheDocument();
  });

  it('processes the map inputs on the physical ENTER key', async () => {
    await renderLoadedScreen();

    makeChange();
    fireEvent.keyDown(document, { key: 'Enter' });

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
  });

  it('rewrites the record on the physical F5 key', async () => {
    await renderLoadedScreen();

    makeChange();
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);

    fireEvent.keyDown(document, { key: 'F5' });

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
  });

  it('repaints the card in place on the physical F12 key', async () => {
    await renderLoadedScreen();
    expect(getCardMock).toHaveBeenCalledTimes(1);
    typeInto('crdname', 'DISCARDED EDIT');

    // PF12 is claimed as soon as the card details are fetched (L418), so the keyboard
    // AID abandons the edits even while the legend still withholds the key.
    fireEvent.keyDown(document, { key: 'F12' });

    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
    });
    expect(getCardMock).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('location').textContent).toBe('/cards/update');
    expect(screen.queryByTestId('card-detail-screen')).toBeNull();
  });

  it('returns to the card list screen on the physical F3 key', async () => {
    await renderLoadedScreen();

    fireEvent.keyDown(document, { key: 'F3' });

    expect(screen.getByTestId('location').textContent).toBe('/cards');
  });

  it('rewrites the record from the line-24 legend alone, with no body control', async () => {
    await renderLoadedScreen();

    // COCRDUP.bms carries no push-button field: every action on this screen is an AID
    // key, so the body offers no control of its own and the legend is the only action
    // surface.
    expect(within(screen.getByRole('main')).queryAllByRole('button')).toEqual([]);

    makeChange();
    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
  });
});

describe('CardUpdatePage — entry state (CCUP-DETAILS-NOT-FETCHED)', () => {
  it('offers both search keys as inputs and reads nothing without a hand-over', () => {
    renderEntryScreen();

    // 3300-SETUP-SCREEN-ATTRS moves DFHBMFSE to ACCTSIDA and CARDSIDA for the whole of
    // CCUP-DETAILS-NOT-FETCHED, so this screen is a usable entry point in its own right.
    const account = screen.getByTestId('acctsid');
    const cardKey = screen.getByTestId('cardsid');
    expect(account.tagName).toBe('INPUT');
    expect(cardKey.tagName).toBe('INPUT');
    expect(account).not.toBeDisabled();
    expect(cardKey).not.toBeDisabled();
    expect(account).toHaveValue('');
    expect(cardKey).toHaveValue('');
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('renders the two key fields at the widths COCRDUP.CPY declares', () => {
    renderEntryScreen();

    // ACCTSID is ``PIC X(11)`` and CARDSID ``PIC X(16)``, and both are sized in character
    // cells so an eleven- or sixteen-digit value keeps every glyph of its declared field.
    const account = screen.getByTestId('acctsid');
    const cardKey = screen.getByTestId('cardsid');
    expect(account).toHaveAttribute('maxlength', '11');
    expect(account).toHaveAttribute('size', '11');
    expect(account.className).toContain('charField--acctId');
    expect(cardKey).toHaveAttribute('maxlength', '16');
    expect(cardKey).toHaveAttribute('size', '16');
    expect(cardKey.className).toContain('charField');
  });

  it('publishes no message before the operator has entered anything', () => {
    renderEntryScreen();

    // A transaction that has received no input has nothing to complain about, so ERRMSG
    // is empty; the legacy screen arrives clean and waits. INFOMSG is a separate field
    // and 3250-SETUP-INFOMSG answers CDEMO-PGM-ENTER with PROMPT-FOR-SEARCH-KEYS, so the
    // screen states what it is waiting for.
    expect(screen.queryByRole('alert')).toBeNull();
    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_SEARCH_KEYS);
  });

  it('rests the cursor on the account key, the first field it may be keyed into', () => {
    renderEntryScreen();

    expect(screen.getByTestId('acctsid')).toHaveFocus();
  });

  it('reads the record ENTER is pressed on and protects the keys once it is shown', async () => {
    renderEntryScreen();

    typeInto('acctsid', ACCOUNT_ID);
    typeInto('cardsid', CARD_NUMBER);
    pressKey('ENTER=Process');

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    expect(getCardMock).toHaveBeenCalledWith(CARD_NUMBER, ACCOUNT_ID);
    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
    });

    // 3300-SETUP-SCREEN-ATTRS turns both keys DFHBMPRF once CCUP-SHOW-DETAILS holds.
    expect(screen.getByTestId('acctsid').tagName).not.toBe('INPUT');
    expect(screen.getByTestId('cardsid').tagName).not.toBe('INPUT');
    expect(infoText()).toBe(FOUND_CARDS_FOR_ACCOUNT);
  });

  it('reports both keys blank as no input received, and reads nothing', () => {
    renderEntryScreen();

    // 1210-EDIT-ACCOUNT / 1220-EDIT-CARD: with both filters blank COCRDUPC publishes
    // WS-NO-INPUT-RECEIVED and exits.
    pressKey('ENTER=Process');

    expect(errorText()).toBe(NO_SEARCH_CRITERIA_RECEIVED);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('reports a missing account number when only the card is keyed', () => {
    renderEntryScreen();

    typeInto('cardsid', CARD_NUMBER);
    pressKey('ENTER=Process');

    expect(errorText()).toBe(PROMPT_FOR_ACCT);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('reports an account number that is not eleven digits, and reads nothing', () => {
    renderEntryScreen();

    typeInto('acctsid', '123');
    typeInto('cardsid', CARD_NUMBER);
    pressKey('ENTER=Process');

    expect(errorText()).toBe(ACCOUNT_FILTER_NOT_NUMERIC);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('reports a card number that is not sixteen digits, and reads nothing', () => {
    renderEntryScreen();

    typeInto('acctsid', ACCOUNT_ID);
    typeInto('cardsid', '4444');
    pressKey('ENTER=Process');

    expect(errorText()).toBe(CARD_FILTER_NOT_NUMERIC);
    expect(getCardMock).not.toHaveBeenCalled();
  });

  it('keeps the refused keys on the screen so they can be corrected in place', () => {
    renderEntryScreen();

    typeInto('acctsid', '123');
    typeInto('cardsid', CARD_NUMBER);
    pressKey('ENTER=Process');

    expect(screen.getByTestId('acctsid')).toHaveValue('123');
    expect(screen.getByTestId('cardsid')).toHaveValue(CARD_NUMBER);
    expect(screen.getByTestId('acctsid')).toHaveAttribute('aria-invalid', 'true');
  });

  it('marks the refused key and leaves the cursor on it', () => {
    renderEntryScreen();

    typeInto('acctsid', ACCOUNT_ID);
    typeInto('cardsid', '4444');
    pressKey('ENTER=Process');

    expect(screen.getByTestId('cardsid')).toHaveFocus();
    expect(screen.getByTestId('cardsid')).toHaveAttribute('aria-invalid', 'true');
  });

  it('does not offer a rewrite while no record has been fetched', () => {
    renderEntryScreen();

    // PF5 is a valid AID only WHEN CCUP-CHANGES-OK-NOT-CONFIRMED (L416); with nothing
    // fetched it becomes the key read, never a confirmation for an absent record.
    pressPhysicalKey('F5');

    expect(updateCardMock).not.toHaveBeenCalled();
    expect(errorText()).toBe(NO_SEARCH_CRITERIA_RECEIVED);
  });

  it('legends only ENTER=Process and F3=Exit in the entry state', () => {
    renderEntryScreen();

    expect(legendLabels()).toEqual(['ENTER=Process', 'F3=Exit']);
  });
});

describe('CardUpdatePage — a failed re-read leaves no stale record (COCRDUPC L1053)', () => {
  it('blanks the displayed record when a re-read finds nothing', async () => {
    await renderLoadedScreen();
    // The record really is on screen first, or the assertion below proves nothing.
    expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
    expect(screen.getByTestId('crdstcd')).toHaveValue(cardRecord.cardActiveStatus);

    getCardMock.mockReset();
    getCardMock.mockRejectedValue(new ApiError(404, 'Card not found'));

    // F12 is CANCEL, whose COCRDUPC path re-reads the record (9000-READ-DATA) to restore
    // the screen from the file. A re-read that fails must not leave the record it was
    // replacing on display: 1100-SCREEN-INIT opens `MOVE LOW-VALUES TO CCRDUPAO` and only
    // a successful read moves a record back into the map. The physical key is used
    // because COCRDUP.bms keeps the F5/F12 legend field DRK until the confirmation is
    // prompted -- the AID is live either way.
    pressPhysicalKey('F12');

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(errorText()).toBe('Card not found');
    });
    expect(screen.getByTestId('crdname')).toHaveValue('');
    expect(screen.getByTestId('crdstcd')).toHaveValue('');
    expect(screen.getByTestId('expmon')).toHaveValue('');
    expect(screen.getByTestId('expyear')).toHaveValue('');
  });

  it('repopulates the record when the re-read succeeds', async () => {
    await renderLoadedScreen();

    getCardMock.mockReset();
    getCardMock.mockResolvedValue(cardRecord);

    pressPhysicalKey('F12');

    await waitFor(() => {
      expect(getCardMock).toHaveBeenCalledTimes(1);
    });
    // Clearing before the read must not stop a good read from painting the record.
    await waitFor(() => {
      expect(screen.getByTestId('crdname')).toHaveValue(cardRecord.cardEmbossedName);
    });
    expect(screen.getByTestId('crdstcd')).toHaveValue(cardRecord.cardActiveStatus);
  });
});

describe('CardUpdatePage — F3 returns to the caller (CDEMO-FROM-PROGRAM)', () => {
  it('returns to the card list when the list handed the selection over', async () => {
    await renderLoadedScreen();

    pressKey('F3=Exit');

    expect(screen.getByTestId('location').textContent).toBe('/cards');
    expect(screen.getByTestId('card-list-screen')).toBeInTheDocument();
  });

  it('returns to the main menu when no caller was recorded', () => {
    // COCRDUPC L449-453 substitutes LIT-MENUPGM when CDEMO-FROM-PROGRAM is blank, so a
    // screen entered from the menu or by deep link goes back where the operator was.
    renderEntryScreen();

    pressKey('F3=Exit');

    expect(screen.getByTestId('location').textContent).toBe('/menu');
    expect(screen.getByTestId('main-menu-screen')).toBeInTheDocument();
  });
});
