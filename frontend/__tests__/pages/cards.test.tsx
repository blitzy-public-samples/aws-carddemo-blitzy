/** Card List page spec — legacy origin BMS COCRDLI / Tx CCLI / program COCRDLIC. ≤7 rows/page (F-004); card_num masked; no cvv. */

/*
 * Component / integration spec for the Card List page (`@/app/cards/page`,
 * default export `CardsPage`) — the modern replacement for the legacy 3270 card
 * browse screen. It renders the REAL page inside the application MUI theme and
 * exercises it end to end with `@/lib/apiClient` and `next/navigation` mocked,
 * so no network or router side effect ever escapes the test.
 *
 * The suite locks the two hard invariants carried over from the mainframe origin:
 *   - F-004: the browse request is capped at DEFAULT_PAGE_SIZE (= 7) rows/page,
 *     and exactly the server-provided page of rows is rendered.
 *   - Security: `card_num` is only ever shown MASKED (last-4), the full PAN is
 *     never rendered, and no card security code (`cvv`) is present anywhere — it
 *     is absent from the `CardSummary` type by design (enforced here both at
 *     runtime and at compile time).
 *
 * All header labels, the mask format, the filter field lengths, the row-click
 * route, and the empty-state message asserted below were read from the REAL
 * `src/app/cards/page.tsx` + `src/components/DataTable.tsx`, not assumed.
 */

import CardsPage from '@/app/cards/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    SetupUser,
    MakeCardSummary,
    MakePaginatedResponse,
} from '../testUtils';
import { CardsApi, ApiError } from '@/lib/apiClient';
import type { CardSummary, PaginatedResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* next/navigation mock — router push/replace/back/refresh/prefetch are       */
/* `mock`-prefixed jest.fns (the only names a jest.mock factory may reference  */
/* from the enclosing scope). They are read lazily inside the returned         */
/* `useRouter` closure, so factory hoisting is safe.                          */
/* ------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/cards',
}));

/* ------------------------------------------------------------------------- */
/* apiClient mock — keep the module REAL (so `ApiError` / `IsApiError` behave  */
/* exactly as production) except for `CardsApi`, whose methods become jest     */
/* mocks the specs arm per scenario.                                          */
/* ------------------------------------------------------------------------- */

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
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/** The legacy F-004 browse limit — every ListCards request must cap here. */
const EXPECTED_PAGE_SIZE = 7;

/** Account-number filter maxLength — legacy ACCTSID PIC X(11). */
const ACCOUNT_FILTER_MAX_LENGTH = 11;

/** Card-number filter maxLength — legacy CARDSID PIC X(16). */
const CARD_FILTER_MAX_LENGTH = 16;

/** `name` attribute the page assigns to the account-number filter input. */
const ACCOUNT_FILTER_NAME = 'accountIdFilter';

/** `name` attribute the page assigns to the card-number filter input. */
const CARD_FILTER_NAME = 'cardIdFilter';

/** A full (unmasked) PAN fed to the page to prove it masks before rendering. */
const RAW_CARD_NUMBER = '4111111111111111';

/** The expected masked rendering of {@link RAW_CARD_NUMBER} (12 * + last-4). */
const MASKED_CARD_NUMBER = '************1111';

/** Embossed name carried by the default {@link MakeCardSummary} fixture. */
const DEFAULT_EMBOSSED_NAME = 'TEST CARDHOLDER';

/** Empty-state message the page passes to DataTable (overrides its default). */
const EMPTY_CARDS_MESSAGE = 'No cards found.';

/* ------------------------------------------------------------------------- */
/* Compile-time security invariant: `CardSummary` must never gain a `cvv`     */
/* member. If it ever does, `CardSummaryHasNoCvv` resolves to `false` and the  */
/* assignment below fails to type-check, breaking the build by design.        */
/* ------------------------------------------------------------------------- */

type CardSummaryHasNoCvv = 'cvv' extends keyof CardSummary ? false : true;
const CARD_SUMMARY_HAS_NO_CVV: CardSummaryHasNoCvv = true;

/* ------------------------------------------------------------------------- */
/* Typed accessor + fixtures (PascalCase helpers — Ochs naming rule).        */
/* ------------------------------------------------------------------------- */

/**
 * Narrows the mocked `CardsApi.ListCards` to a `jest.Mock` so specs can arm
 * resolutions / rejections and assert on its calls.
 *
 * @returns The mocked ListCards function.
 */
function ListCardsMock(): jest.Mock {
    return CardsApi.ListCards as jest.Mock;
}

/**
 * Builds a typed `PaginatedResponse<CardSummary>` around `items`, delegating the
 * envelope defaults (page 1, page_size 7, single page) to the shared
 * `MakePaginatedResponse` factory.
 *
 * @param items - The card rows contained on this page.
 * @param overrides - Partial envelope fields (multi-page scenarios).
 * @returns The fully-populated paginated card page.
 */
function BuildCardPage(
    items: CardSummary[],
    overrides?: Partial<PaginatedResponse<CardSummary>>,
): PaginatedResponse<CardSummary> {
    return MakePaginatedResponse<CardSummary>(items, overrides);
}

/**
 * Generates `count` distinct `CardSummary` rows (unique masked card numbers)
 * for the fixed-size browse-page specs.
 *
 * @param count - Number of card rows to generate.
 * @returns The generated, security-correct card rows.
 */
function MakeCardRows(count: number): CardSummary[] {
    const rows: CardSummary[] = [];
    for (let index = 0; index < count; index += 1) {
        const lastFour = String(4000 + index).padStart(4, '0');
        rows.push(MakeCardSummary({ card_num: `************${lastFour}` }));
    }
    return rows;
}

/**
 * Returns only the `<tbody>` rows (excludes the header row) by scoping to the
 * last MUI `rowgroup`, mirroring the shared DataTable contract.
 *
 * @returns The body rows currently rendered by the grid.
 */
function GetBodyRows(): HTMLElement[] {
    const rowGroups = screen.getAllByRole('rowgroup');
    const tableBody = rowGroups[rowGroups.length - 1];
    return within(tableBody).getAllByRole('row');
}

/* ------------------------------------------------------------------------- */
/* Suite.                                                                     */
/* ------------------------------------------------------------------------- */

describe('CardsPage', () => {
    // Reset the ListCards implementation before every spec. The global
    // `clearMocks` only clears call data (mockClear), so a persistent
    // `mockResolvedValue` from a prior spec would otherwise leak; `mockReset`
    // clears the implementation too, guaranteeing per-spec isolation.
    beforeEach(() => {
        ListCardsMock().mockReset();
    });

    it('renders a row per card and requests page_size 7 (F-004)', async () => {
        ListCardsMock().mockResolvedValueOnce(
            BuildCardPage([
                MakeCardSummary(),
                MakeCardSummary({ card_num: '4222222222222222' }),
            ]),
        );

        RenderWithProviders(<CardsPage />);

        await waitFor(() => expect(ListCardsMock()).toHaveBeenCalled());

        // The browse request is capped at the F-004 limit of 7 rows/page.
        expect(ListCardsMock()).toHaveBeenCalledWith(
            expect.objectContaining({ page_size: EXPECTED_PAGE_SIZE }),
        );

        // Both card rows render (embossed name + account id are visible).
        const nameCells = await screen.findAllByText(DEFAULT_EMBOSSED_NAME);
        expect(nameCells).toHaveLength(2);
        expect(screen.getAllByText('00000000011')).toHaveLength(2);
    });

    it('renders exactly 7 data rows and a multi-page control (F-004)', async () => {
        const sevenPage: PaginatedResponse<CardSummary> = BuildCardPage(
            MakeCardRows(EXPECTED_PAGE_SIZE),
            { total_items: 20, total_pages: 3, has_next: true },
        );
        ListCardsMock().mockResolvedValueOnce(sevenPage);

        RenderWithProviders(<CardsPage />);

        await waitFor(() =>
            expect(GetBodyRows()).toHaveLength(EXPECTED_PAGE_SIZE),
        );
        // Never more than the 7-row browse cap.
        expect(GetBodyRows().length).toBeLessThanOrEqual(EXPECTED_PAGE_SIZE);
        // A multi-page Pagination control (a <nav>) is present for 3 pages.
        expect(screen.getByRole('navigation')).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: /go to page 2/i }),
        ).toBeInTheDocument();
    });

    it('masks card_num (last-4 only) and never renders a cvv', async () => {
        ListCardsMock().mockResolvedValueOnce(
            BuildCardPage([MakeCardSummary({ card_num: RAW_CARD_NUMBER })]),
        );

        RenderWithProviders(<CardsPage />);

        // The masked form is what the user sees...
        expect(await screen.findByText(MASKED_CARD_NUMBER)).toBeInTheDocument();
        // ...and the full PAN is never present in the document.
        expect(screen.queryByText(RAW_CARD_NUMBER)).not.toBeInTheDocument();

        // No card security code is shown anywhere: no text, no column header.
        expect(screen.queryByText(/cvv/i)).not.toBeInTheDocument();
        expect(
            screen.queryByRole('columnheader', { name: /cvv/i }),
        ).not.toBeInTheDocument();

        // Compile-time guarantee (also asserted at runtime for coverage).
        expect(CARD_SUMMARY_HAS_NO_CVV).toBe(true);
    });

    it('navigates to the card detail route with an encoded card number on row click', async () => {
        const user = SetupUser();
        ListCardsMock().mockResolvedValueOnce(
            BuildCardPage([MakeCardSummary({ card_num: RAW_CARD_NUMBER })]),
        );

        RenderWithProviders(<CardsPage />);

        const maskedCell = await screen.findByText(MASKED_CARD_NUMBER);
        await user.click(maskedCell);

        expect(mockPush).toHaveBeenCalledWith(
            `/cards/view?cardNum=${encodeURIComponent(RAW_CARD_NUMBER)}`,
        );
    });

    it('refetches with page 2 when the pagination control advances', async () => {
        const user = SetupUser();
        // Persistent resolution: both the mount fetch and the page-2 refetch
        // resolve with a valid multi-page envelope.
        ListCardsMock().mockResolvedValue(
            BuildCardPage(MakeCardRows(EXPECTED_PAGE_SIZE), {
                total_items: 20,
                total_pages: 3,
                has_next: true,
            }),
        );

        RenderWithProviders(<CardsPage />);

        // Wait for the grid (and its Pagination control) to finish rendering the
        // mounted page before paging — the control only exists once the first
        // page has resolved into state (until then the page shows a spinner).
        const pageTwoButton = await screen.findByRole('button', {
            name: /go to page 2/i,
        });
        await user.click(pageTwoButton);

        await waitFor(() =>
            expect(ListCardsMock()).toHaveBeenLastCalledWith(
                expect.objectContaining({ page: 2 }),
            ),
        );
    });

    it('caps the filter inputs and refetches from page 1 on Search', async () => {
        const user = SetupUser();
        ListCardsMock().mockResolvedValue(
            BuildCardPage([MakeCardSummary()]),
        );

        RenderWithProviders(<CardsPage />);

        await waitFor(() => expect(ListCardsMock()).toHaveBeenCalledTimes(1));

        // Two filter textboxes render, in order: account, then card. Identity is
        // pinned by the page-assigned `name`, and each is length-bounded to its
        // legacy symbolic-map width (ACCTSID X(11) / CARDSID X(16)).
        const [accountInput, cardInput] =
            screen.getAllByRole('textbox') as HTMLInputElement[];

        expect(accountInput).toHaveAttribute('name', ACCOUNT_FILTER_NAME);
        expect(accountInput).toHaveAttribute(
            'maxlength',
            String(ACCOUNT_FILTER_MAX_LENGTH),
        );
        expect(cardInput).toHaveAttribute('name', CARD_FILTER_NAME);
        expect(cardInput).toHaveAttribute(
            'maxlength',
            String(CARD_FILTER_MAX_LENGTH),
        );

        await user.type(accountInput, '123');
        await user.type(cardInput, '4567');
        expect(accountInput).toHaveValue('123');
        expect(cardInput).toHaveValue('4567');

        await user.click(screen.getByRole('button', { name: 'Search' }));

        // The real page keeps filters as client state and, on Search, refetches
        // page 1 with only { page, page_size } — it does not forward the filter
        // values to ListCards. Assert the observable refetch contract.
        await waitFor(() => expect(ListCardsMock()).toHaveBeenCalledTimes(2));
        expect(ListCardsMock()).toHaveBeenLastCalledWith(
            expect.objectContaining({
                page: 1,
                page_size: EXPECTED_PAGE_SIZE,
            }),
        );
    });

    it('shows the empty-state message when no cards are returned', async () => {
        ListCardsMock().mockResolvedValueOnce(
            BuildCardPage([], { total_items: 0, total_pages: 0 }),
        );

        RenderWithProviders(<CardsPage />);

        expect(
            await screen.findByText(EMPTY_CARDS_MESSAGE),
        ).toBeInTheDocument();
    });

    it('surfaces an error alert when the list request fails', async () => {
        ListCardsMock().mockRejectedValueOnce(
            new ApiError({ status: 500, message: 'Internal Server Error' }),
        );

        RenderWithProviders(<CardsPage />);

        const alert = await screen.findByRole('alert');
        expect(alert).toBeInTheDocument();
        expect(
            within(alert).getByText('Internal Server Error'),
        ).toBeInTheDocument();
    });
});
