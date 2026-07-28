/**
 * Card Update page spec — legacy origin BMS COCRDUP / Tx CCUP / program
 * COCRDUPC. card_num masked; cvv NEVER rendered; before_image echoed;
 * 409 surfaced.
 */

/**
 * cardsUpdate.test.tsx — Jest + React Testing Library component/integration spec
 * for the Card Update page (`@/app/cards/update/page`, default export
 * `CardsUpdatePage`), the modern replacement for legacy BMS map COCRDUP
 * (CICS Tx CCUP, COBOL program COCRDUPC).
 *
 * The real page component is rendered against a MOCKED `@/lib/apiClient`
 * (`CardsApi`) and a MOCKED `next/navigation` (router + search params) — there is
 * NO real network traffic. The editable form carries the three editable fields
 * (`embossed_name`, `expiration_date`, `active_status`) PLUS the required
 * client-echoed `before_image` optimistic-lock token (QA finding C06); the
 * account id and card number are read-only and the card number is masked. There
 * is deliberately NO `cvv` field anywhere.
 *
 * Navigation is keyed on the card number the operator entered — the legacy
 * COCRDSL/COCRDUP `CARDSID` input — read from the `cardNum` query param, over
 * the frozen GET/PUT /cards/{cardNum} contract (AAP 0.5.5). The earlier
 * by-account variant was removed (QA C07/C08): it resolved an account to one
 * card with `.limit(1)` and silently edited the wrong card on the NONUNIQUE
 * account->card relationship, and was outside the frozen route list.
 *
 * Optimistic locking (QA C06): the submitted `CardUpdate` carries `before_image`
 * (the editable-field values as loaded); the backend compares it against the
 * freshly locked row and rejects a stale write with HTTP 409, whose message is
 * surfaced to the operator through the error alert.
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
 * The card number the operator ENTERS (the route key). Faithful to the legacy
 * COCRDSL/COCRDUP `CARDSID` input; it addresses the frozen /cards/{cardNum}
 * endpoint (AAP 0.5.5). It is the operator's own lookup entry — distinct from
 * any record-sourced card_num, which the page never renders.
 */
const ROUTE_CARD_NUM = '4000123412341234';

/**
 * A full 16-digit PAN used ONLY as a defensive fixture value (fed as the
 * response `card_num`) to prove the update page never renders a record-sourced
 * PAN. Deliberately different from {@link ROUTE_CARD_NUM} so a positive picker
 * assertion cannot accidentally match this "must-never-render" value.
 */
const CARD_NUMBER = '4111111111111111';

/** Already-masked card number returned by default (honors the masked contract). */
const MASKED_CARD_NUM = '************1234';

/** Zero-padded 11-digit account id (string preserves leading zeros). */
const DEFAULT_ACCT_ID = '00000000011';

/**
 * Success navigation target, mirroring the page's BuildCardViewRoute helper. The
 * detail/update screens are keyed on the card number the operator entered
 * (QA C07/C08), so the success route carries `?cardNum=`. Declared after
 * {@link ROUTE_CARD_NUM} to avoid a temporal-dead-zone reference.
 */
const EXPECTED_VIEW_ROUTE = `/cards/view?cardNum=${encodeURIComponent(ROUTE_CARD_NUM)}`;

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

/** HTTP 409 — the optimistic-lock conflict the backend raises on a stale write. */
const HTTP_CONFLICT = 409;

/** Conflict message the backend returns on a 409; surfaced verbatim (C06). */
const CONFLICT_MESSAGE = 'The record was changed by another user';

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
        UpdateCard: jest.fn(),
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
        // QA C07/C08: the screen is keyed on the card number the operator entered
        // from the `cardNum` query param. A card is present by default; specs may
        // override.
        mockSearchParams = new URLSearchParams({ cardNum: ROUTE_CARD_NUM });
    });

    it('prefills the three editable fields from CardsApi.GetCard (C07/C08)', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        await waitFor(() =>
            expect(CardsApi.GetCard).toHaveBeenCalledWith(ROUTE_CARD_NUM),
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

    it('titles the page with a single accessible h1 heading (N-01)', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        // Let the on-mount GetCard load settle inside act() so the trailing
        // setIsLoading(false) is captured (no act warning), matching the other
        // specs in this file.
        await waitFor(() =>
            expect(CardsApi.GetCard).toHaveBeenCalledWith(ROUTE_CARD_NUM),
        );
        await screen.findByLabelText(/Name on Card/i);

        // The CardHeader title now renders as a semantic level-1 heading (was a
        // non-heading <span> before the QA N-01 fix), so the page exposes exactly
        // one h1 naming the update screen.
        const h1s = screen.getAllByRole('heading', { level: 1 });
        expect(h1s).toHaveLength(1);
        expect(h1s[0]).toHaveTextContent('Update Credit Card Details');
    });

    it('renders the owning account id read-only and never renders a record-sourced PAN (0.7.8/C05)', async () => {
        // Feed a full PAN as the response card_num to prove the update page never
        // renders a record-sourced card number. The single "Card Number" field is
        // the lookup ENTRY (the operator's own input), mirroring the account-id
        // picker and the legacy COCRDUP CARDSID input.
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(
            MakeCardRead({ card_num: CARD_NUMBER }),
        );

        RenderWithProviders(<CardsUpdatePage />);

        const acctInput = await screen.findByLabelText(/Account ID/i);
        expect(acctInput).toHaveAttribute('readonly');
        expect(acctInput).toHaveValue(DEFAULT_ACCT_ID);

        // The response's full PAN is never rendered anywhere on the page.
        expect(screen.queryByDisplayValue(CARD_NUMBER)).not.toBeInTheDocument();
        expect(screen.queryByText(CARD_NUMBER)).not.toBeInTheDocument();

        // The single card-number field is the lookup entry: it shows the number
        // the operator typed (the route key), not a record-sourced value.
        const cardInput = screen.getByLabelText(/Card Number/i);
        await waitFor(() => expect(cardInput).toHaveValue(ROUTE_CARD_NUM));
    });

    it('never renders a CVV field or label', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        // Wait for the form to finish loading before asserting the absence.
        await screen.findByLabelText(/Name on Card/i);

        expect(screen.queryByText(/cvv/i)).not.toBeInTheDocument();
        expect(screen.queryByLabelText(/cvv/i)).not.toBeInTheDocument();
    });

    it('bounds the name-on-card input to 50 characters', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        const nameInput = await screen.findByLabelText(/Name on Card/i);
        expect(nameInput).toHaveAttribute(
            'maxlength',
            String(EMBOSSED_NAME_LENGTH),
        );
    });

    it('offers Active (Y) and Inactive (N) and updates on selection', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());
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
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsUpdatePage />);

        const dateInput = await screen.findByLabelText(/Expiration Date/i);
        expect(dateInput).toHaveAttribute('type', 'date');
    });

    it('submits the editable fields plus the before_image echo and navigates to the card view (C06)', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());
        (CardsApi.UpdateCard as jest.Mock).mockResolvedValueOnce(
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
            expect(CardsApi.UpdateCard).toHaveBeenCalledWith(
                ROUTE_CARD_NUM,
                expect.objectContaining({
                    before_image: expect.objectContaining({
                        embossed_name: DEFAULT_EMBOSSED_NAME,
                        active_status: DEFAULT_ACTIVE_STATUS,
                        expiration_date: DEFAULT_EXPIRATION_DATE,
                    }),
                    embossed_name: UPDATED_EMBOSSED_NAME,
                    expiration_date: expect.any(String),
                    active_status: expect.any(String),
                }),
            ),
        );

        // The payload must carry the three editable keys plus the REQUIRED
        // before_image optimistic-lock token (C06) — never the read-only
        // identifiers and never a (non-existent) cvv.
        const updateBody = (CardsApi.UpdateCard as jest.Mock).mock
            .calls[0][1] as CardUpdate;
        expect(Object.keys(updateBody).sort()).toEqual([
            'active_status',
            'before_image',
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

    it('surfaces the backend conflict message on a 409 stale-write rejection (C06)', async () => {
        (CardsApi.GetCard as jest.Mock).mockResolvedValueOnce(MakeCardRead());
        // The backend rejects a stale write (before_image mismatch under the
        // SELECT ... FOR UPDATE lock) with HTTP 409; the page surfaces the
        // server-supplied conflict message through the error alert.
        (CardsApi.UpdateCard as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: HTTP_CONFLICT, message: CONFLICT_MESSAGE }),
        );
        const user = SetupUser();

        RenderWithProviders(<CardsUpdatePage />);

        const nameInput = await screen.findByLabelText(/Name on Card/i);
        await waitFor(() =>
            expect(nameInput).toHaveValue(DEFAULT_EMBOSSED_NAME),
        );

        await user.click(screen.getByRole('button', { name: /save/i }));

        // The alert shows the backend's conflict message; no navigation occurs.
        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent(CONFLICT_MESSAGE);
        expect(mockPush).not.toHaveBeenCalled();
    });

    it('shows a prompt and skips GetCard when cardNum is absent', async () => {
        mockSearchParams = new URLSearchParams();

        RenderWithProviders(<CardsUpdatePage />);

        expect(
            await screen.findByText(/select a card from the card list/i),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: /back to cards/i }),
        ).toBeInTheDocument();
        expect(CardsApi.GetCard).not.toHaveBeenCalled();
    });
});
