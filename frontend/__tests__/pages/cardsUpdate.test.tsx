/** Card Update page spec — legacy origin BMS COCRDUP / Tx CCUP / program COCRDUPC. card_num masked; cvv NEVER rendered; no 409 flow. */

/**
 * cardsUpdate.test.tsx — Jest + React Testing Library component/integration spec
 * for the Card Update page (`@/app/cards/update/page`, default export
 * `CardsUpdatePage`), the modern replacement for legacy BMS map COCRDUP
 * (CICS Tx CCUP, COBOL program COCRDUPC).
 *
 * The real page component is rendered against a MOCKED `@/lib/apiClient`
 * (`CardsApi`) and a MOCKED `next/navigation` (router + search params) — there is
 * NO real network traffic. The editable form carries exactly THREE fields
 * (`embossed_name`, `expiration_date`, `active_status`); the account id and card
 * number are read-only and the card number is masked. There is deliberately NO
 * `cvv` field anywhere, and — unlike the accounts/update screen — NO HTTP 409
 * optimistic-lock conflict branch: every update failure is surfaced generically.
 *
 * Field lengths/types/options and the success route are taken from the real
 * `cards/update/page.tsx`; field origins trace to the BMS symbolic-map copybook
 * app/cpy-bms/COCRDUP.CPY (CRDNAMEI X(50), CRDSTCDI X(1), ACCTSIDI X(11),
 * CARDSIDI X(16)). Greenfield test infrastructure.
 */

import CardsUpdatePage from '@/app/cards/update/page';
import { RenderWithProviders, screen, waitFor, SetupUser } from '../testUtils';
import { CardsApi, ApiError } from '@/lib/apiClient';
import type { CardRead, CardUpdate } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module-scope mock handles (mock-prefixed so the jest.mock factory may      */
/* reference them past the hoist guard). The arrow factories below read these */
/* only when invoked at render time, after initialization — no TDZ hazard.    */
/* ------------------------------------------------------------------------- */

/** Captures `router.push(...)` navigations for assertion. */
const mockPush = jest.fn();

/**
 * The resettable `useSearchParams()` result. Reassigned in `beforeEach` to the
 * default (a present `cardNum`) and overridden per-spec (e.g. the empty case).
 */
let mockSearchParams = new URLSearchParams();

/* ------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).               */
/* ------------------------------------------------------------------------- */

/**
 * A full 16-digit PAN used ONLY as a defensive-masking fixture value (fed as a
 * card_num to prove the read-only card field masks before display). QA C1: it is
 * never a route key — the screen is keyed on the owning-account id.
 */
const CARD_NUMBER = '4111111111111111';

/** Result of masking {@link CARD_NUMBER} (last-4 visible) — defensive-mask check. */
const CARD_NUMBER_MASKED = '************1111';

/** Already-masked card number returned by default (honors the masked contract). */
const MASKED_CARD_NUM = '************1234';

/** Zero-padded 11-digit account id (string preserves leading zeros). */
const DEFAULT_ACCT_ID = '00000000011';

/**
 * Success navigation target, mirroring the page's BuildCardViewRoute helper. Per
 * QA C1 the detail/update screens are keyed on the UNMASKED owning-account id,
 * so the success route carries `?acctId=` (never a masked PAN). Declared after
 * {@link DEFAULT_ACCT_ID} to avoid a temporal-dead-zone reference.
 */
const EXPECTED_VIEW_ROUTE = `/cards/view?acctId=${encodeURIComponent(DEFAULT_ACCT_ID)}`;

/** Prefilled name-on-card value used to assert the initial form state. */
const DEFAULT_EMBOSSED_NAME = 'TEST CARDHOLDER';

/** Prefilled ISO expiration date (YYYY-MM-DD) used by the date field. */
const DEFAULT_EXPIRATION_DATE = '2027-12-31';

/** Prefilled active-status flag ('Y' -> "Active (Y)" in the status select). */
const DEFAULT_ACTIVE_STATUS = 'Y';

/** Edited name-on-card value submitted in the success scenario. */
const UPDATED_EMBOSSED_NAME = 'NEW NAME';

/** Max accepted length of the name-on-card input (CRDNAMEI PIC X(50)). */
const EMBOSSED_NAME_LENGTH = 50;

/** HTTP 409 — used to prove this page does NOT special-case a conflict. */
const HTTP_CONFLICT = 409;

/** Generic server error text asserted verbatim in the failure scenario. */
const SERVER_ERROR_MESSAGE = 'Update failed on server';

/* ------------------------------------------------------------------------- */
/* Mocks. next/navigation is fully stubbed; @/lib/apiClient keeps everything  */
/* real (ApiError / IsApiError) except CardsApi, whose methods become mocks.  */
/* ------------------------------------------------------------------------- */

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockPush,
        replace: jest.fn(),
        back: jest.fn(),
        forward: jest.fn(),
        refresh: jest.fn(),
        prefetch: jest.fn(),
    }),
    useSearchParams: () => mockSearchParams,
    usePathname: () => '/cards/update',
}));

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    CardsApi: {
        ListCards: jest.fn(),
        GetCard: jest.fn(),
        GetCardByAccount: jest.fn(),
        UpdateCard: jest.fn(),
        UpdateCardByAccount: jest.fn(),
    },
}));

/* ------------------------------------------------------------------------- */
/* Fixture factory (PascalCase per the Ochs Rule). Returns a valid CardRead;  */
/* `overrides` win so any field can be adjusted per scenario.                 */
/* ------------------------------------------------------------------------- */

/**
 * Build a `CardRead` fixture for the card-update prefill. The `card_num` is
 * already masked by default and there is intentionally no `cvv` field.
 *
 * @param overrides - Partial fields that replace any card default.
 * @returns A valid `CardRead`.
 */
function MakeCardRead(overrides?: Partial<CardRead>): CardRead {
    const baseCard: CardRead = {
        card_num: MASKED_CARD_NUM,
        acct_id: DEFAULT_ACCT_ID,
        embossed_name: DEFAULT_EMBOSSED_NAME,
        expiration_date: DEFAULT_EXPIRATION_DATE,
        active_status: DEFAULT_ACTIVE_STATUS,
    };

    return { ...baseCard, ...overrides };
}

/* ------------------------------------------------------------------------- */
/* Suites.                                                                    */
/* ------------------------------------------------------------------------- */

describe('CardsUpdatePage', () => {
    beforeEach(() => {
        // QA C1: the screen is keyed on the UNMASKED owning-account id from the
        // `acctId` query param. A card is present by default; specs may override.
        mockSearchParams = new URLSearchParams({ acctId: DEFAULT_ACCT_ID });
    });

    it('prefills the three editable fields from CardsApi.GetCardByAccount (C1)', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        await waitFor(() =>
            expect(CardsApi.GetCardByAccount).toHaveBeenCalledWith(DEFAULT_ACCT_ID),
        );

        // embossed_name / expiration_date prefill into their inputs; the status
        // select displays the label of the prefilled option ('Y' -> Active (Y)).
        await waitFor(() =>
            expect(screen.getByLabelText(/Name on Card/i)).toHaveValue(
                DEFAULT_EMBOSSED_NAME,
            ),
        );
        expect(screen.getByLabelText(/Expiration Date/i)).toHaveValue(
            DEFAULT_EXPIRATION_DATE,
        );
        expect(
            screen.getByRole('combobox', { name: /Status/i }),
        ).toHaveTextContent('Active (Y)');
    });

    it('renders acct_id and card_num as read-only, with card_num masked', async () => {
        // Feed a full PAN to prove the page defensively masks before display.
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(
            MakeCardRead({ card_num: CARD_NUMBER }),
        );

        RenderWithProviders(<CardsUpdatePage />);

        const acctInput = await screen.findByLabelText(/Account ID/i);
        const cardInput = screen.getByLabelText(/Card Number/i);

        expect(acctInput).toHaveAttribute('readonly');
        expect(cardInput).toHaveAttribute('readonly');
        expect(acctInput).toHaveValue(DEFAULT_ACCT_ID);

        // The full PAN must never be rendered — only the last-4 masked form.
        await waitFor(() => expect(cardInput).toHaveValue(CARD_NUMBER_MASKED));
        expect(cardInput).not.toHaveValue(CARD_NUMBER);
        expect(screen.queryByDisplayValue(CARD_NUMBER)).not.toBeInTheDocument();
    });

    it('never renders a CVV field or label', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        // Wait for the form to finish loading before asserting the absence.
        await screen.findByLabelText(/Name on Card/i);

        expect(screen.queryByText(/cvv/i)).not.toBeInTheDocument();
        expect(screen.queryByLabelText(/cvv/i)).not.toBeInTheDocument();
    });

    it('bounds the name-on-card input to 50 characters', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        const nameInput = await screen.findByLabelText(/Name on Card/i);
        expect(nameInput).toHaveAttribute(
            'maxlength',
            String(EMBOSSED_NAME_LENGTH),
        );
    });

    it('offers Active (Y) and Inactive (N) and updates on selection', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());
        const user = SetupUser();

        RenderWithProviders(<CardsUpdatePage />);

        const statusCombobox = await screen.findByRole('combobox', {
            name: /Status/i,
        });

        // Options are portal-rendered only after the combobox is opened.
        await user.click(statusCombobox);
        expect(
            await screen.findByRole('option', { name: 'Active (Y)' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('option', { name: 'Inactive (N)' }),
        ).toBeInTheDocument();

        await user.click(screen.getByRole('option', { name: 'Inactive (N)' }));

        await waitFor(() =>
            expect(
                screen.getByRole('combobox', { name: /Status/i }),
            ).toHaveTextContent('Inactive (N)'),
        );
    });

    it('renders the expiration date as a native date input', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        const dateInput = await screen.findByLabelText(/Expiration Date/i);
        expect(dateInput).toHaveAttribute('type', 'date');
    });

    it('submits only the 3 editable fields and navigates to the card view', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());
        (CardsApi.UpdateCardByAccount as jest.Mock).mockResolvedValueOnce(
            MakeCardRead({ embossed_name: UPDATED_EMBOSSED_NAME }),
        );
        const user = SetupUser();

        RenderWithProviders(<CardsUpdatePage />);

        const nameInput = await screen.findByLabelText(/Name on Card/i);
        await waitFor(() =>
            expect(nameInput).toHaveValue(DEFAULT_EMBOSSED_NAME),
        );

        await user.clear(nameInput);
        await user.type(nameInput, UPDATED_EMBOSSED_NAME);

        await user.click(screen.getByRole('button', { name: /save/i }));

        await waitFor(() =>
            expect(CardsApi.UpdateCardByAccount).toHaveBeenCalledWith(
                DEFAULT_ACCT_ID,
                expect.objectContaining({
                    embossed_name: UPDATED_EMBOSSED_NAME,
                    expiration_date: expect.any(String),
                    active_status: expect.any(String),
                }),
            ),
        );

        // The payload must carry ONLY the three editable keys — never the
        // read-only identifiers and never a (non-existent) cvv.
        const updateBody = (CardsApi.UpdateCardByAccount as jest.Mock).mock
            .calls[0][1] as CardUpdate;
        expect(Object.keys(updateBody).sort()).toEqual([
            'active_status',
            'embossed_name',
            'expiration_date',
        ]);
        expect(updateBody).not.toHaveProperty('card_num');
        expect(updateBody).not.toHaveProperty('acct_id');
        expect(updateBody).not.toHaveProperty('cvv');

        await waitFor(() =>
            expect(mockPush).toHaveBeenCalledWith(EXPECTED_VIEW_ROUTE),
        );
    });

    it('surfaces a generic error alert on failure with no 409 conflict handling', async () => {
        (CardsApi.GetCardByAccount as jest.Mock).mockResolvedValueOnce(MakeCardRead());
        // A 409 is armed on purpose: this page must treat it exactly like any
        // other error (no bespoke optimistic-lock conflict branch).
        (CardsApi.UpdateCardByAccount as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: HTTP_CONFLICT, message: SERVER_ERROR_MESSAGE }),
        );
        const user = SetupUser();

        RenderWithProviders(<CardsUpdatePage />);

        const nameInput = await screen.findByLabelText(/Name on Card/i);
        await waitFor(() =>
            expect(nameInput).toHaveValue(DEFAULT_EMBOSSED_NAME),
        );

        await user.click(screen.getByRole('button', { name: /save/i }));

        // The alert shows the server message verbatim (generic passthrough)...
        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent(SERVER_ERROR_MESSAGE);

        // ...and NONE of the conflict-specific phrasing the accounts/update
        // screen uses for its 409 branch appears here.
        expect(
            screen.queryByText(
                /version conflict|modified by another|reload the page|refresh and try again/i,
            ),
        ).not.toBeInTheDocument();
        expect(mockPush).not.toHaveBeenCalled();
    });

    it('shows a prompt and skips GetCardByAccount when acctId is absent', async () => {
        mockSearchParams = new URLSearchParams();

        RenderWithProviders(<CardsUpdatePage />);

        expect(
            await screen.findByText(/select a card from the card list/i),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: /back to cards/i }),
        ).toBeInTheDocument();
        expect(CardsApi.GetCardByAccount).not.toHaveBeenCalled();
    });
});
