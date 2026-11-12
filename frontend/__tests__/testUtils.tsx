/**
 * testUtils.tsx — Shared frontend test infrastructure (render wrapper + typed,
 * throwaway fixtures) imported by every component/page/lib spec via a RELATIVE
 * path, e.g. `import { RenderWithProviders, screen, userEvent } from '../testUtils'`.
 *
 * It is intentionally NOT a Jest suite: it defines no describe/it/test and is
 * never selected by `testMatch` (`*.(test|spec).(ts|tsx)`), so importing it
 * raises no "empty suite" error. Greenfield test infra — no legacy COBOL origin.
 */

import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import type { RenderOptions, RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import theme from '@/app/theme';
import { DEFAULT_PAGE_SIZE } from '@/types';
import type {
    PaginatedResponse,
    CurrentUser,
    UserSummary,
    CardSummary,
} from '@/types';

// ---------------------------------------------------------------------------
// Shared fixture defaults (ALL_UPPERCASE constants per the Ochs Rule). These
// are seed-style, non-sensitive placeholder values only — never real
// credentials, and there is deliberately no password/CVV constant here.
// ---------------------------------------------------------------------------

/** Seed-style regular (non-admin) user id — matches README sample USER0001. */
const DEFAULT_USER_ID = 'USER0001';

/** Seed-style administrator user id — matches README sample ADMIN001. */
const DEFAULT_ADMIN_ID = 'ADMIN001';

/** Placeholder given name used across identity fixtures. */
const DEFAULT_FIRST_NAME = 'Test';

/** Placeholder surname used across identity fixtures. */
const DEFAULT_LAST_NAME = 'User';

/** Zero-padded 11-digit account id (string preserves leading zeros). */
const DEFAULT_ACCT_ID = '00000000011';

/** Already-masked card number (last-4 visible) — satisfies the mask invariant. */
const MASKED_CARD_NUM = '************1234';

/** Placeholder embossed cardholder name for card fixtures. */
const DEFAULT_EMBOSSED_NAME = 'TEST CARDHOLDER';

/** Active status flag as stored on card/account records (X(1) = 'Y'). */
const ACTIVE_STATUS_ACTIVE = 'Y';

// ---------------------------------------------------------------------------
// RenderWithProviders — the core render helper
// ---------------------------------------------------------------------------

/**
 * Test-only theme derived from the real application theme with the MUI touch
 * ripple globally disabled.
 *
 * The `TouchRipple` animation schedules asynchronous state updates AFTER an
 * interaction settles; in jsdom those updates land outside Testing Library's
 * `act()` scope and emit repeated `act(...)` console warnings (QA N-07).
 * Disabling the ripple on every `ButtonBase` removes that async work at the
 * harness level while preserving all real MD3 tokens (primary `#1976d2`, etc.),
 * so specs still exercise the production theme.
 */
const TEST_THEME = createTheme(theme, {
    components: {
        MuiButtonBase: {
            defaultProps: {
                disableRipple: true,
            },
        },
    },
});

/**
 * Render `ui` inside the application MUI theme (ripple disabled for test
 * hygiene) so specs exercise the same Material Design 3 tokens
 * (primary `#1976d2`, etc.) the app uses at runtime.
 *
 * Only a single `ThemeProvider` wrapper is applied: `CssBaseline` is omitted (it
 * injects global styles that add noise to jsdom without benefit) and no router
 * provider is added (Next App Router hooks are mocked per-spec via
 * `jest.mock('next/navigation')`).
 *
 * @param ui - The React element under test.
 * @param options - Standard Testing Library render options, minus `wrapper`
 *     (the theme wrapper is provided here and must not be overridden).
 * @returns The Testing Library render result (queries, rerender, unmount, ...).
 */
export function RenderWithProviders(
    ui: ReactElement,
    options?: Omit<RenderOptions, 'wrapper'>,
): RenderResult {
    function Wrapper({ children }: { children: ReactNode }): ReactElement {
        return <ThemeProvider theme={TEST_THEME}>{children}</ThemeProvider>;
    }

    return render(ui, { wrapper: Wrapper, ...options });
}

// ---------------------------------------------------------------------------
// Re-exports — let specs import the whole Testing Library surface plus
// user-event from this single module (`import { screen, waitFor } from
// '../testUtils'`).
// ---------------------------------------------------------------------------

export * from '@testing-library/react';
export { userEvent };

/**
 * Return a freshly configured `user-event` instance so specs can consistently
 * do `const user = SetupUser();` before dispatching interactions.
 *
 * @returns A configured user-event session bound to the current document.
 */
export function SetupUser(): ReturnType<typeof userEvent.setup> {
    return userEvent.setup();
}

// ---------------------------------------------------------------------------
// Fixture factories — typed, minimal, overridable, security-correct. Each takes
// a single optional `overrides` object (honoring the "<= 4 params" rule) and
// returns a valid instance of the matching `@/types` interface.
// ---------------------------------------------------------------------------

/**
 * Build a standard paginated-list envelope around `items`. Defaults describe a
 * single, complete page sized to the legacy card-browse limit
 * (`DEFAULT_PAGE_SIZE` = 7, F-004); pass `overrides` for multi-page scenarios.
 *
 * @param items - The rows contained on this page.
 * @param overrides - Partial fields that replace any envelope default.
 * @returns A fully-populated `PaginatedResponse<T>`.
 */
export function MakePaginatedResponse<T>(
    items: T[],
    overrides?: Partial<PaginatedResponse<T>>,
): PaginatedResponse<T> {
    const baseResponse: PaginatedResponse<T> = {
        items,
        page: 1,
        page_size: DEFAULT_PAGE_SIZE,
        total_items: items.length,
        total_pages: 1,
        has_next: false,
        has_previous: false,
    };

    return { ...baseResponse, ...overrides };
}

/**
 * Build the authenticated principal fixture. Defaults to a regular
 * (`user_type: 'U'`) seed user; override `user_type` to `'A'` for admin paths.
 * Never carries a password — reads never expose credentials.
 *
 * @param overrides - Partial fields that replace any identity default.
 * @returns A valid `CurrentUser`.
 */
export function MakeCurrentUser(overrides?: Partial<CurrentUser>): CurrentUser {
    const baseUser: CurrentUser = {
        user_id: DEFAULT_USER_ID,
        user_type: 'U',
        first_name: DEFAULT_FIRST_NAME,
        last_name: DEFAULT_LAST_NAME,
    };

    return { ...baseUser, ...overrides };
}

/**
 * Convenience wrapper returning an administrator principal (`user_type: 'A'`,
 * seed id `ADMIN001`). `overrides` still win, so any field can be adjusted.
 *
 * @param overrides - Partial fields that replace the admin defaults.
 * @returns A valid admin `CurrentUser`.
 */
export function MakeAdminUser(overrides?: Partial<CurrentUser>): CurrentUser {
    return MakeCurrentUser({
        user_id: DEFAULT_ADMIN_ID,
        user_type: 'A',
        ...overrides,
    });
}

/**
 * Build a card-list row fixture for card-browse / DataTable specs. The
 * `card_num` is already masked and there is intentionally no `cvv` field.
 *
 * @param overrides - Partial fields that replace any card default.
 * @returns A valid `CardSummary`.
 */
export function MakeCardSummary(overrides?: Partial<CardSummary>): CardSummary {
    const baseCard: CardSummary = {
        card_num: MASKED_CARD_NUM,
        acct_id: DEFAULT_ACCT_ID,
        embossed_name: DEFAULT_EMBOSSED_NAME,
        active_status: ACTIVE_STATUS_ACTIVE,
    };

    return { ...baseCard, ...overrides };
}

/**
 * Build an admin user-list row fixture for user-management / DataTable specs.
 * Contains no password field — list reads never carry credentials.
 *
 * @param overrides - Partial fields that replace any user default.
 * @returns A valid `UserSummary`.
 */
export function MakeUserSummary(overrides?: Partial<UserSummary>): UserSummary {
    const baseUser: UserSummary = {
        user_id: DEFAULT_USER_ID,
        first_name: DEFAULT_FIRST_NAME,
        last_name: DEFAULT_LAST_NAME,
        user_type: 'U',
    };

    return { ...baseUser, ...overrides };
}
