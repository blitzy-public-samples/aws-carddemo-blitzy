/** Card View page spec — legacy origin BMS COCRDSL / Tx CCDL / program COCRDSLC. Read-only; card_num masked; cvv NEVER rendered. */

/*
 * Component / integration spec for the modern Card Detail (view) page — the
 * greenfield replacement for the legacy BMS map COCRDSL (mapset CCRDSLA), CICS
 * transaction CCDL, COBOL program COCRDSLC. The page is read-only: it fetches a
 * single card by its `cardNum` query-string parameter and renders the five
 * business fields carried by the legacy symbolic map (Account Id, Card Number,
 * Name on Card, Card Active status, Expiry). The real page is rendered against a
 * mocked `@/lib/apiClient` and mocked `next/navigation`, so NO real network or
 * router is involved.
 *
 * Hard security invariants exercised here (AAP 0.7.8, NON-NEGOTIABLE):
 *   - `card_num` is displayed MASKED (only the last four digits are visible); a
 *     full PAN is never rendered even if one is (defensively) supplied.
 *   - The card security code (CVV) is NEVER fetched, referenced, or rendered —
 *     the `CardRead` type has no cvv field and the rendered DOM contains no
 *     "cvv" text (scenario 3).
 */

import { RenderWithProviders, screen, waitFor, SetupUser } from '../testUtils';
import { CardsApi, ApiError } from '@/lib/apiClient';
import type { CardRead } from '@/types';
import CardsViewPage from '@/app/cards/view/page';

/* ------------------------------------------------------------------------- */
/* next/navigation mock — a controlled, resettable searchParams plus a spied  */
/* router. The default export wraps its content in <Suspense>; the inner      */
/* component reads useSearchParams().get('cardNum'), so the search params must */
/* be resettable per test. Only `mock`-prefixed identifiers are referenced by  */
/* the (hoisted) factory, and each is dereferenced lazily at render time.      */
/* ------------------------------------------------------------------------- */

let mockSearchParams = new URLSearchParams();
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => mockSearchParams,
    usePathname: () => '/cards/view',
}));

/* ------------------------------------------------------------------------- */
/* @/lib/apiClient mock — keep the real module (so ApiError and the IsApiError */
/* type guard used by ErrorAlert remain the genuine class / instanceof check) */
/* and replace ONLY the CardsApi surface with jest spies.                     */
/* ------------------------------------------------------------------------- */

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
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores). Values mirror  */
/* the real page's display helpers so the expectations are exact.            */
/* ------------------------------------------------------------------------- */

/** Zero-padded 11-digit account id (string preserves leading zeros). */
const DEFAULT_ACCT_ID = '00000000011';

/**
 * A full 16-digit PAN used deliberately as the fixture card_num. Feeding a full
 * PAN (rather than an already-masked value) proves the page masks defensively:
 * scenario 2 asserts this exact string is never rendered.
 */
const DEFAULT_CARD_NUM = '4111111111111111';

/** Placeholder embossed cardholder name. */
const DEFAULT_EMBOSSED_NAME = 'JOHN DOE';

/** Single-character active-status flag that renders as the "Active" chip. */
const ACTIVE_STATUS_ACTIVE = 'Y';

/** Single-character active-status flag that renders as the "Inactive" chip. */
const ACTIVE_STATUS_INACTIVE = 'N';

/** Backend ISO expiration date (X(10) "YYYY-MM-DD") returned by GetCard. */
const DEFAULT_EXPIRATION_DATE = '2027-08-15';

/** The `MM/YYYY` string the page's FormatExpiration derives from the ISO date. */
const EXPECTED_EXPIRATION_DISPLAY = '08/2027';

/** Glyph the page uses to mask every non-visible card digit (matches page). */
const MASK_GLYPH = '\u2022';

/** Trailing card digits kept visible when masking (matches page constant). */
const VISIBLE_CARD_DIGITS = 4;

/**
 * The exact masked rendering of {@link DEFAULT_CARD_NUM}: every leading digit
 * replaced by {@link MASK_GLYPH}, only the last four visible
 * (e.g. `••••••••••••1111`).
 */
const MASKED_CARD_DISPLAY =
    MASK_GLYPH.repeat(DEFAULT_CARD_NUM.length - VISIBLE_CARD_DIGITS) +
    DEFAULT_CARD_NUM.slice(-VISIBLE_CARD_DIGITS);

/**
 * Route the Edit button pushes: the update screen keyed on the UNMASKED
 * owning-account id (QA C1), URL-encoded. A masked PAN is never placed in a URL.
 */
const CARD_UPDATE_TARGET =
    '/cards/update?acctId=' + encodeURIComponent(DEFAULT_ACCT_ID);

/** Route the Back button pushes (the card-list screen, PF3-exit equivalent). */
const CARDS_LIST_TARGET = '/cards';

/** Validation prompt the page shows when no acctId query param is present. */
const MISSING_ACCT_MESSAGE =
    'No account number provided. Select a card from the list or enter an ' +
    'account number to view its card.';

/** Human-readable message carried by the simulated GetCard failure. */
const API_ERROR_MESSAGE = 'Card not found';

/** HTTP status carried by the simulated GetCard failure (a 404 not-found). */
const NOT_FOUND_STATUS = 404;

/* ------------------------------------------------------------------------- */
/* Fixtures (PascalCase factory per the Ochs rule). Returns a fully-populated */
/* CardRead; a single optional overrides object honors the "<= 4 params" rule.*/
/* ------------------------------------------------------------------------- */

/**
 * Build a `CardRead` fixture. Includes every field of the real type and — by
 * construction — carries NO cvv field, mirroring the security contract that
 * CARD-CVV-CD is never exposed.
 *
 * @param overrides - Partial fields that replace any card default.
 * @returns A valid `CardRead`.
 */
function MakeCardRead(overrides?: Partial<CardRead>): CardRead {
    const baseCard: CardRead = {
        card_num: DEFAULT_CARD_NUM,
        acct_id: DEFAULT_ACCT_ID,
        embossed_name: DEFAULT_EMBOSSED_NAME,
        expiration_date: DEFAULT_EXPIRATION_DATE,
        active_status: ACTIVE_STATUS_ACTIVE,
    };

    return { ...baseCard, ...overrides };
}

/** Typed accessor for the mocked GetCardByAccount spy (avoids repeated casts). */
const GetCardByAccountMock = jest.mocked(CardsApi.GetCardByAccount);

beforeEach(() => {
    // QA C1: the page keys the card on its UNMASKED owning-account id read from
    // the `acctId` query param (a masked PAN can never be a valid card key).
    // Reset the search params to the happy-path acctId before every test; the
    // router / GetCardByAccount spies are cleared automatically by clearMocks.
    mockSearchParams = new URLSearchParams({ acctId: DEFAULT_ACCT_ID });
});

describe('CardsViewPage', () => {
    /* --------------------------------------------------------------------- */
    /* 1. Fetch by cardNum on mount.                                         */
    /* --------------------------------------------------------------------- */

    it('fetches the card by owning-account id on mount and renders its detail fields (C1)', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsViewPage />);

        await waitFor(() =>
            expect(GetCardByAccountMock).toHaveBeenCalledWith(DEFAULT_ACCT_ID),
        );

        // The page header renders regardless; the business fields render once
        // the fetch resolves.
        expect(
            screen.getByText('View Credit Card Detail'),
        ).toBeInTheDocument();
        expect(await screen.findByText(DEFAULT_EMBOSSED_NAME)).toBeInTheDocument();
        expect(screen.getByText(DEFAULT_ACCT_ID)).toBeInTheDocument();
        expect(GetCardByAccountMock).toHaveBeenCalledTimes(1);
    });

    /* --------------------------------------------------------------------- */
    /* 2. card_num masked — full PAN never rendered, only last-4 visible.    */
    /* --------------------------------------------------------------------- */

    it('masks the card number so the full PAN is never rendered', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsViewPage />);

        // Wait for the detail panel to render before asserting on the value.
        await screen.findByText(DEFAULT_EMBOSSED_NAME);

        expect(screen.queryByText(DEFAULT_CARD_NUM)).not.toBeInTheDocument();
        expect(screen.getByText(MASKED_CARD_DISPLAY)).toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 3. cvv NEVER rendered — HARD security invariant.                      */
    /* --------------------------------------------------------------------- */

    it('NEVER renders the card security code (cvv)', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsViewPage />);

        await screen.findByText(DEFAULT_EMBOSSED_NAME);

        // No field, label, or value mentioning the security code may appear.
        expect(screen.queryByText(/cvv/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/security code/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/card verification/i)).not.toBeInTheDocument();

        // The fixture itself carries no cvv field (the CardRead type has none),
        // and a case-insensitive scan of the whole rendered DOM finds no "cvv".
        expect(MakeCardRead()).not.toHaveProperty('cvv');
        expect(MakeCardRead()).not.toHaveProperty('cvv_cd');
        expect((document.body.textContent ?? '').toLowerCase()).not.toContain(
            'cvv',
        );
    });

    /* --------------------------------------------------------------------- */
    /* 4. active_status chip — 'Y' -> Active, 'N' -> Inactive.               */
    /* --------------------------------------------------------------------- */

    it('renders an "Active" status chip when active_status is Y', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(
            MakeCardRead({ active_status: ACTIVE_STATUS_ACTIVE }),
        );

        RenderWithProviders(<CardsViewPage />);

        expect(await screen.findByText('Active')).toBeInTheDocument();
    });

    it('renders an "Inactive" status chip when active_status is N', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(
            MakeCardRead({ active_status: ACTIVE_STATUS_INACTIVE }),
        );

        RenderWithProviders(<CardsViewPage />);

        expect(await screen.findByText('Inactive')).toBeInTheDocument();
        // Exact-string query: 'Active' must not match the 'Inactive' chip text.
        expect(screen.queryByText('Active')).not.toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 5. expiration formatted MM/YYYY (not the raw ISO value).              */
    /* --------------------------------------------------------------------- */

    it('formats the expiration date as MM/YYYY rather than the raw ISO value', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());

        RenderWithProviders(<CardsViewPage />);

        await screen.findByText(DEFAULT_EMBOSSED_NAME);

        expect(
            screen.getByText(EXPECTED_EXPIRATION_DISPLAY),
        ).toBeInTheDocument();
        // The unformatted backend value must not leak through to the UI.
        expect(
            screen.queryByText(DEFAULT_EXPIRATION_DATE),
        ).not.toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 6. Edit navigates to the update screen with an encoded cardNum.       */
    /* --------------------------------------------------------------------- */

    it('navigates to the card-update screen with the encoded owning-account id on Edit (C1)', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());
        const user = SetupUser();

        RenderWithProviders(<CardsViewPage />);

        // The action buttons only exist once the card has loaded.
        await screen.findByText(DEFAULT_EMBOSSED_NAME);
        await user.click(screen.getByRole('button', { name: /edit/i }));

        expect(mockPush).toHaveBeenCalledWith(CARD_UPDATE_TARGET);
    });

    /* --------------------------------------------------------------------- */
    /* 7. Back navigates to the card list.                                   */
    /* --------------------------------------------------------------------- */

    it('navigates back to the card list on Back', async () => {
        GetCardByAccountMock.mockResolvedValueOnce(MakeCardRead());
        const user = SetupUser();

        RenderWithProviders(<CardsViewPage />);

        await screen.findByText(DEFAULT_EMBOSSED_NAME);
        await user.click(screen.getByRole('button', { name: /back/i }));

        expect(mockPush).toHaveBeenCalledWith(CARDS_LIST_TARGET);
    });

    /* --------------------------------------------------------------------- */
    /* 8. Missing cardNum — no fetch, a validation prompt is shown.          */
    /* --------------------------------------------------------------------- */

    it('does not fetch and shows a prompt when the acctId param is missing', async () => {
        // Override the happy-path default with an empty query string.
        mockSearchParams = new URLSearchParams();

        RenderWithProviders(<CardsViewPage />);

        expect(await screen.findByText(MISSING_ACCT_MESSAGE)).toBeInTheDocument();
        expect(GetCardByAccountMock).not.toHaveBeenCalled();
    });

    /* --------------------------------------------------------------------- */
    /* 9. API error / 404 — the failure surfaces through the error alert.    */
    /* --------------------------------------------------------------------- */

    it('surfaces an error alert when the card fetch fails', async () => {
        GetCardByAccountMock.mockRejectedValueOnce(
            new ApiError({
                status: NOT_FOUND_STATUS,
                message: API_ERROR_MESSAGE,
            }),
        );

        RenderWithProviders(<CardsViewPage />);

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent(API_ERROR_MESSAGE);
    });
});
