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

import { render, screen, fireEvent, act, waitFor, within } from '@testing-library/react';
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

/** ``FOUND-CARDS-FOR-ACCOUNT``. */
const FOUND_CARDS_FOR_ACCOUNT = 'Details of selected card shown above';

/** ``PROMPT-FOR-CONFIRMATION`` — no space follows the period. */
const PROMPT_FOR_CONFIRMATION = 'Changes validated.Press F5 to save';

/** ``CONFIRM-UPDATE-SUCCESS``. */
const CONFIRM_UPDATE_SUCCESS = 'Changes committed to database';

/** ``INFORM-FAILURE``. */
const INFORM_FAILURE = 'Changes unsuccessful. Please try again';

/** ``SEARCHED-CARD-NOT-NUMERIC``. */
const SEARCHED_CARD_NOT_NUMERIC = 'Card number if supplied must be a 16 digit number';

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
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getCard: getCardMock,
  updateCard: updateCardMock,
  signon: signonMock,
  ApiError,
}));

type CardUpdatePageComponent = (typeof import('./CardUpdatePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SetSession = (typeof import('../hooks/useSession'))['__setSession'];

let CardUpdatePage: CardUpdatePageComponent;
let Layout: LayoutComponent;
let setSession: SetSession;

beforeAll(async () => {
  // Imported after the mock registration so every ``../api`` binding in the
  // graph resolves to the mock; no module reset, so React stays shared with
  // Testing Library.
  ({ default: CardUpdatePage } = await import('./CardUpdatePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ __setSession: setSession } = await import('../hooks/useSession'));
});

beforeEach(() => {
  getCardMock.mockReset();
  updateCardMock.mockReset();
  signonMock.mockReset();
  getCardMock.mockResolvedValue(cardRecord);
  updateCardMock.mockResolvedValue(cardRecord);
  act(() => {
    setSession(SESSION_USER, 'U');
  });
});

afterEach(() => {
  act(() => {
    setSession(null, null);
  });
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
        { pathname: '/cards/update', state: { cardNumber, accountId: ACCOUNT_ID } },
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
  return within(screen.getByRole('toolbar', { name: 'Function keys' }))
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
 * :purpose: The line-23 informational message region. The shared shell also renders a
 *     visually hidden ``role="status"`` busy announcer, so the banner is matched on its
 *     own class rather than on the role alone.
 * :returns: the informational banner, or ``null`` when line 23 carries no
 *     informational message.
 */
function infoBanner(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.errorBanner[role="status"]');
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
    expect(screen.getByLabelText('Expiry Year')).toBe(screen.getByTestId('expyear'));
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
    expect(infoBanner()).toBeNull();
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

    typeInto('crdstcd', 'N');
    pressKey('ENTER=Process');
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);

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

  it('rejects a card number that is not 16 digits and issues no read', () => {
    renderScreen('12345');

    expect(errorText()).toBe(SEARCHED_CARD_NOT_NUMERIC);
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
});

describe('CardUpdatePage — confirmation prompt (PROMPT-FOR-CONFIRMATION)', () => {
  it('asks for the F5 confirmation once the edits are clean, without saving', async () => {
    await renderLoadedScreen();

    pressKey('ENTER=Process');

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    expect(screen.getByText(PROMPT_FOR_CONFIRMATION)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('renders the prompt with no space after the period', async () => {
    await renderLoadedScreen();

    pressKey('ENTER=Process');

    expect(infoText()).toBe('Changes validated.Press F5 to save');
    expect(infoText()).not.toContain('validated. Press');
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
    pressPhysicalKey('F5');

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
    expect(updateCardMock).not.toHaveBeenCalled();

    // Now that the edits are confirmed the same key commits them.
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
    expect(infoBanner()).toBeNull();
    expect(updateCardMock).not.toHaveBeenCalled();
  });

  it('reports an unsuccessful rewrite', async () => {
    updateCardMock.mockRejectedValue(new ApiError(500, 'Internal Server Error'));
    await renderLoadedScreen();

    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(INFORM_FAILURE);
    });
    expect(infoBanner()).toBeNull();
  });

  it('reports an unsuccessful rewrite for a transport failure', async () => {
    updateCardMock.mockRejectedValue(new Error('socket hang up'));
    await renderLoadedScreen();

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

    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe('Card Active Status must be Y or N');
    });
  });

  it('reports a concurrent change on an HTTP 409 optimistic-lock conflict', async () => {
    updateCardMock.mockRejectedValue(new ApiError(409, 'Conflict', undefined, true));
    await renderLoadedScreen();

    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(errorText()).toBe(DATA_WAS_CHANGED_BEFORE_UPDATE);
    });
    expect(infoBanner()).toBeNull();
  });
});

describe('CardUpdatePage — line-24 function keys', () => {
  it('renders ENTER=Process, F3=Exit, F5=Save and F12=Cancel in mapset order', async () => {
    await renderLoadedScreen();

    // COCRDUP.bms row 24 carries 'ENTER=Process  F3=Exit' unconditionally and the
    // DRK field holding 'F5=Save  F12=Cancel' only while the confirmation is
    // prompted (L1315-1317), so the freshly displayed screen legends two keys.
    expect(legendLabels()).toEqual(['ENTER=Process', 'F3=Exit']);

    pressKey('ENTER=Process');

    expect(legendLabels()).toEqual([
      'ENTER=Process',
      'F3=Exit',
      'F5=Save',
      'F12=Cancel',
    ]);
  });

  it('returns to the card detail screen on F12=Cancel', async () => {
    await renderLoadedScreen();

    // F12 joins the legend with F5, once the edits are validated.
    pressKey('ENTER=Process');
    pressKey('F12=Cancel');

    // The selection travels in the router location state, so the card number never
    // reaches the address bar.
    expect(screen.getByTestId('location').textContent).toBe('/cards/view');
    expect(screen.getByTestId('card-detail-screen')).toBeInTheDocument();
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

    fireEvent.keyDown(document, { key: 'Enter' });

    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);
  });

  it('rewrites the record on the physical F5 key', async () => {
    await renderLoadedScreen();

    fireEvent.keyDown(document, { key: 'Enter' });
    expect(infoText()).toBe(PROMPT_FOR_CONFIRMATION);

    fireEvent.keyDown(document, { key: 'F5' });

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
  });

  it('returns to the card detail screen on the physical F12 key', async () => {
    await renderLoadedScreen();

    // PF12 is claimed as soon as the card details are fetched, so the keyboard AID
    // abandons the edits even while the legend still withholds the key.
    fireEvent.keyDown(document, { key: 'F12' });

    expect(screen.getByTestId('location').textContent).toBe('/cards/view');
    expect(screen.getByTestId('card-detail-screen')).toBeInTheDocument();
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

    pressKey('ENTER=Process');
    pressKey('F5=Save');

    await waitFor(() => {
      expect(updateCardMock).toHaveBeenCalledTimes(1);
    });
  });
});
