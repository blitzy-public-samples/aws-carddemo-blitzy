/** Main Menu page spec — legacy origin BMS COMEN01 / Tx CM00 / program COMEN01C. */

/*
 * Component / integration spec for the regular-user Main Menu page
 * (`@/app/menu/page`, default export `MenuPage`) — the modern replacement for
 * the legacy 3270 map COMEN01 (CICS transaction CM00, COBOL program COMEN01C).
 *
 * The REAL page is rendered inside the application Material UI theme (via
 * `RenderWithProviders`); only its two collaborators are mocked so that no real
 * network call and no real router navigation are ever exercised:
 *
 *   - `next/navigation` — `useRouter().push` (and friends) are captured as jest
 *     fns so route dispatch can be asserted. This is the modern redesign of the
 *     legacy `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)` menu dispatch in COMEN01C.
 *
 *   - `@/lib/apiClient` — everything is kept REAL through `jest.requireActual`
 *     (so the genuine `ApiError` class and the `IsApiError` guard that the
 *     shared `ErrorAlert` depends on stay intact); ONLY `MenuApi` is stubbed,
 *     with `GetMenu` armed per test. The page is the SERVER's client: it renders
 *     exactly the options `GetMenu` returns and never hardcodes or role-filters
 *     them (asserted explicitly by the server-authority scenario below).
 *
 * This page is the regular-user menu and is NOT admin-gated at the page level,
 * so `@/lib/auth` is intentionally NOT mocked — the real page does not import it.
 */

/* ------------------------------------------------------------------------- */
/* Mock next/navigation. The jest fns are declared with the `mock` prefix so   */
/* the (hoisted) jest.mock factory may reference them safely.                  */
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
    usePathname: () => '/menu',
}));

/* ------------------------------------------------------------------------- */
/* Mock @/lib/apiClient: keep every real export (ApiError, IsApiError, the     */
/* axios singleton, the other resource APIs) and stub ONLY MenuApi.            */
/* ------------------------------------------------------------------------- */

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    MenuApi: { GetMenu: jest.fn(), GetAdminMenu: jest.fn() },
}));

import MenuPage from '@/app/menu/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    fireEvent,
    SetupUser,
} from '../testUtils';
import { MenuApi, ApiError } from '@/lib/apiClient';
import type { MenuResponse, MenuOption } from '@/types';

/* ------------------------------------------------------------------------- */
/* Constants mirrored VERBATIM from the real page (frontend/src/app/menu/      */
/* page.tsx) so the spec asserts the SAME strings the component renders        */
/* (Ochs rule: ALL_UPPERCASE with underscores).                                */
/* ------------------------------------------------------------------------- */

/** Page-heading fallback used when the backend omits `menu_title`. */
const DEFAULT_MENU_TITLE = 'Main Menu';

/** Empty-state text shown when the backend returns no selectable options. */
const NO_OPTIONS_MESSAGE = 'No menu options are available.';

/**
 * A COBOL program name that is intentionally ABSENT from the page's
 * PROGRAM_ROUTE_MAP, used to prove that unmapped options render non-navigable.
 */
const UNMAPPED_PROGRAM_NAME = 'COXXXXXX';

/**
 * Typed handle to the mocked menu loader. `clearMocks: true` (global) clears its
 * call history before every test but never replaces the fn object, so this
 * reference stays valid across the whole suite.
 */
const mockGetMenu = jest.mocked(MenuApi.GetMenu);

/* ------------------------------------------------------------------------- */
/* Fixture factories (Ochs rule: PascalCase function names, camelCase vars).   */
/* ------------------------------------------------------------------------- */

/**
 * Build a single {@link MenuOption}. Defaults describe a mapped, regular-user
 * entry (option 1 -> Account View -> COACTVWC); pass `overrides` to vary the
 * number, label, backing program, or role.
 *
 * @param overrides - Partial fields that replace any option default.
 * @returns A fully-populated MenuOption.
 */
function MakeMenuOption(overrides?: Partial<MenuOption>): MenuOption {
    const baseOption: MenuOption = {
        option_number: 1,
        option_name: 'Account View',
        program_name: 'COACTVWC',
        user_type: 'U',
    };

    return { ...baseOption, ...overrides };
}

/**
 * Wrap a list of options in the `MenuResponse` envelope the backend returns from
 * `GET /menu`.
 *
 * @param options - The menu options the server would send.
 * @param overrides - Partial envelope fields (for example `menu_title`).
 * @returns A fully-populated MenuResponse.
 */
function MakeMenuResponse(
    options: MenuOption[],
    overrides?: Partial<MenuResponse>,
): MenuResponse {
    const baseResponse: MenuResponse = {
        menu_options: options,
        user_type: 'U',
    };

    return { ...baseResponse, ...overrides };
}

/**
 * Return the trimmed text labels of every rendered option button, in DOM order.
 * Scoping the query to the `list` role excludes any button that lives outside
 * the options list (for example the error alert's dismiss button, which renders
 * in a Snackbar portal).
 *
 * @returns The rendered option labels in the order they appear.
 */
function GetRenderedOptionLabels(): string[] {
    const optionsList = screen.getByRole('list');
    return within(optionsList)
        .getAllByRole('button')
        .map((optionButton) => optionButton.textContent ?? '');
}

describe('MenuPage', () => {
    /* --------------------------------------------------------------------- */
    /* 1. Loading indicator, then the server's options render.               */
    /* --------------------------------------------------------------------- */

    it('shows the loading indicator, then renders every option the server returns', async () => {
        const serverOptions = [
            MakeMenuOption({
                option_number: 1,
                option_name: 'Account View',
                program_name: 'COACTVWC',
            }),
            MakeMenuOption({
                option_number: 2,
                option_name: 'Credit Card List',
                program_name: 'COCRDLIC',
            }),
            MakeMenuOption({
                option_number: 3,
                option_name: 'Bill Payment',
                program_name: 'COBIL00C',
            }),
        ];
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse(serverOptions));

        RenderWithProviders(<MenuPage />);

        // The CircularProgress (role="progressbar") is present on first render.
        expect(screen.getByRole('progressbar')).toBeInTheDocument();

        // Each server-supplied label appears once the async load resolves.
        expect(await screen.findByText('Account View')).toBeInTheDocument();
        expect(screen.getByText('Credit Card List')).toBeInTheDocument();
        expect(screen.getByText('Bill Payment')).toBeInTheDocument();

        // The loader ran exactly once (single mount fetch).
        expect(mockGetMenu).toHaveBeenCalledTimes(1);

        // The loading indicator is gone once options are rendered.
        await waitFor(() => {
            expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
        });
    });

    /* --------------------------------------------------------------------- */
    /* 2. Options are sorted by ascending option_number, regardless of the   */
    /*    order the server sent them in.                                     */
    /* --------------------------------------------------------------------- */

    it('renders options sorted by ascending option_number regardless of server order', async () => {
        const outOfOrderOptions = [
            MakeMenuOption({
                option_number: 3,
                option_name: 'Third Option',
                program_name: 'COBIL00C',
            }),
            MakeMenuOption({
                option_number: 1,
                option_name: 'First Option',
                program_name: 'COACTVWC',
            }),
            MakeMenuOption({
                option_number: 2,
                option_name: 'Second Option',
                program_name: 'COCRDLIC',
            }),
        ];
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse(outOfOrderOptions));

        RenderWithProviders(<MenuPage />);
        await screen.findByText('First Option');

        expect(GetRenderedOptionLabels()).toEqual([
            'First Option',
            'Second Option',
            'Third Option',
        ]);
    });

    /* --------------------------------------------------------------------- */
    /* 3. Menu title: server-provided value wins; otherwise the default.     */
    /* --------------------------------------------------------------------- */

    it('renders the server-provided menu_title when one is supplied', async () => {
        const customTitle = 'Regular User Menu';
        mockGetMenu.mockResolvedValueOnce(
            MakeMenuResponse([MakeMenuOption()], { menu_title: customTitle }),
        );

        RenderWithProviders(<MenuPage />);

        expect(
            await screen.findByRole('heading', { name: customTitle }),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('heading', { name: DEFAULT_MENU_TITLE }),
        ).not.toBeInTheDocument();
    });

    it('falls back to DEFAULT_MENU_TITLE when menu_title is omitted', async () => {
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse([MakeMenuOption()]));

        RenderWithProviders(<MenuPage />);

        // The default heading is shown immediately (initial state) and remains
        // after the load completes because the response omitted menu_title.
        expect(
            screen.getByRole('heading', { name: DEFAULT_MENU_TITLE }),
        ).toBeInTheDocument();
        await screen.findByText('Account View');
        expect(
            screen.getByRole('heading', { name: DEFAULT_MENU_TITLE }),
        ).toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 4. Clicking a mapped option navigates via PROGRAM_ROUTE_MAP.          */
    /* --------------------------------------------------------------------- */

    it('navigates via PROGRAM_ROUTE_MAP when a mapped option is clicked', async () => {
        const serverOptions = [
            MakeMenuOption({
                option_number: 1,
                option_name: 'Credit Card List',
                program_name: 'COCRDLIC',
            }),
            MakeMenuOption({
                option_number: 2,
                option_name: 'Bill Payment',
                program_name: 'COBIL00C',
            }),
        ];
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse(serverOptions));

        const user = SetupUser();
        RenderWithProviders(<MenuPage />);
        await screen.findByText('Bill Payment');

        // COBIL00C -> /billpay (proves the mapping is wired end to end).
        await user.click(screen.getByRole('button', { name: 'Bill Payment' }));
        expect(mockPush).toHaveBeenCalledWith('/billpay');

        // COCRDLIC -> /cards (a second mapping to prove the route table).
        await user.click(screen.getByRole('button', { name: 'Credit Card List' }));
        expect(mockPush).toHaveBeenCalledWith('/cards');

        expect(mockPush).toHaveBeenCalledTimes(2);
    });

    /* --------------------------------------------------------------------- */
    /* 5. An unmapped program renders disabled and never navigates.          */
    /* --------------------------------------------------------------------- */

    it('renders an unmapped program as a disabled, non-navigable option', async () => {
        const serverOptions = [
            MakeMenuOption({
                option_number: 1,
                option_name: 'Mapped Option',
                program_name: 'COACTVWC',
            }),
            MakeMenuOption({
                option_number: 2,
                option_name: 'Unmapped Option',
                program_name: UNMAPPED_PROGRAM_NAME,
            }),
        ];
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse(serverOptions));

        RenderWithProviders(<MenuPage />);
        await screen.findByText('Unmapped Option');

        // MUI renders a disabled ListItemButton as a <div role="button"
        // aria-disabled="true"> (not a native disabled control), so the disabled
        // state is asserted via aria-disabled rather than toBeDisabled().
        const unmappedButton = screen.getByRole('button', {
            name: 'Unmapped Option',
        });
        expect(unmappedButton).toHaveAttribute('aria-disabled', 'true');

        // Even a forced click (fireEvent bypasses the pointer-events guard that
        // blocks real user clicks) must not navigate: the page's route guard
        // suppresses dispatch for programs absent from PROGRAM_ROUTE_MAP.
        fireEvent.click(unmappedButton);
        expect(mockPush).not.toHaveBeenCalled();
    });

    /* --------------------------------------------------------------------- */
    /* 6. Empty options -> the empty-state message and no option buttons.    */
    /* --------------------------------------------------------------------- */

    it('shows the empty-state message and no option buttons when the server returns none', async () => {
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse([]));

        RenderWithProviders(<MenuPage />);

        expect(await screen.findByText(NO_OPTIONS_MESSAGE)).toBeInTheDocument();

        const optionsList = screen.getByRole('list');
        expect(within(optionsList).queryAllByRole('button')).toHaveLength(0);
    });

    /* --------------------------------------------------------------------- */
    /* 7. A failed load surfaces an error alert and renders no options.      */
    /* --------------------------------------------------------------------- */

    it('surfaces an error alert and renders no options when the load fails', async () => {
        const loadFailure = new ApiError({
            status: 500,
            message: 'Menu service unavailable',
        });
        mockGetMenu.mockRejectedValueOnce(loadFailure);

        RenderWithProviders(<MenuPage />);

        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent('Menu service unavailable');

        // No option buttons render inside the list, and no navigation occurs.
        const optionsList = screen.getByRole('list');
        expect(within(optionsList).queryAllByRole('button')).toHaveLength(0);
        expect(mockPush).not.toHaveBeenCalled();
    });

    /* --------------------------------------------------------------------- */
    /* 8. Server-authority guard: the page renders EXACTLY what GetMenu       */
    /*    returned — same count, same labels — with no client-side filtering. */
    /* --------------------------------------------------------------------- */

    it('renders exactly the options the server returns without hardcoding or role-filtering', async () => {
        // Deliberately include an option flagged user_type 'A' to prove the page
        // does NOT filter options by role on the client; the server is the
        // authority on what a user may see (mirrors the real page's contract).
        const serverOptions = [
            MakeMenuOption({
                option_number: 1,
                option_name: 'Alpha',
                program_name: 'COACTVWC',
                user_type: 'U',
            }),
            MakeMenuOption({
                option_number: 2,
                option_name: 'Bravo',
                program_name: 'COCRDLIC',
                user_type: 'U',
            }),
            MakeMenuOption({
                option_number: 3,
                option_name: 'Charlie',
                program_name: 'COBIL00C',
                user_type: 'A',
            }),
        ];
        mockGetMenu.mockResolvedValueOnce(MakeMenuResponse(serverOptions));

        RenderWithProviders(<MenuPage />);
        await screen.findByText('Alpha');

        const renderedLabels = GetRenderedOptionLabels();
        expect(renderedLabels).toEqual(['Alpha', 'Bravo', 'Charlie']);
        expect(renderedLabels).toHaveLength(serverOptions.length);
    });
});
