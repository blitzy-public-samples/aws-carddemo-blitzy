/** Account View page spec — legacy origin BMS COACTVW / Tx CAVW / program COACTVWC. Read-only. */

/*
 * accountsView.test.tsx — Jest + React Testing Library component/integration
 * suite for the Account View page (`@/app/accounts/view/page`, default export
 * `AccountsViewPage`), the Next.js + Material UI replacement for the legacy 3270
 * account-view screen.
 *
 * TRACEABILITY (Minimal Change Clause, AAP §0.8.1):
 *   - BMS map:       COACTVW  (mapset COACTVW, map CACTVWA — app/bms/COACTVW.bms).
 *   - Symbolic map:  app/cpy-bms/COACTVW.CPY (field names/lengths).
 *   - CICS tx:       CAVW.
 *   - COBOL program: COACTVWC (app/cbl/COACTVWC.cbl) — "Displaying details of a
 *                    given Account".
 *
 * WHAT IS VERIFIED (all against the REAL page — no snapshot guessing):
 *   1. On mount the page reads `?acctId=` and fetches exactly that id via the
 *      (mocked) `AccountsApi.GetAccount`, then renders the account + customer
 *      fields.
 *   2. SECURITY — the SSN is masked (bullet prefix + last four); the raw nine
 *      digits are never in the DOM (AAP §0.7.8).
 *   3. Monetary fields render VERBATIM (Decimal strings, only a `$` prefix) with
 *      no `Number()`/`toFixed()`/locale reformatting — floating-point rounding
 *      would be a compliance failure (AAP §0.7.1).
 *   4. `fico_credit_score` (the ONLY numeric DTO field) renders via `String()`.
 *   5. `active_status` renders as a semantic MUI `Chip` ('Y' → Active, else
 *      Inactive).
 *   6. The screen is READ-ONLY — there are no editable inputs.
 *   7. A missing `acctId` performs NO fetch and shows a friendly prompt.
 *   8. A failed fetch (e.g. HTTP 404 `ApiError`) surfaces an error alert.
 *
 * MOCKING STRATEGY (do not "simplify" away):
 *   - `next/navigation` is fully mocked; `useSearchParams` returns a module-scope,
 *     resettable `URLSearchParams` (`mockSearchParams`) so each test controls the
 *     `acctId` query value. `clearMocks: true` only clears jest.fn call history,
 *     so `mockSearchParams` is RE-ASSIGNED (not mutated) in `beforeEach` and the
 *     resolved/rejected value is RE-ARMED per test.
 *   - `@/lib/apiClient` keeps its REAL exports (`ApiError`, `IsApiError`, ...) via
 *     `jest.requireActual`; only `AccountsApi` is replaced with jest fns. The real
 *     `IsApiError`/`ErrorAlert` normalization therefore runs unchanged, and the
 *     test constructs real `ApiError`s.
 *   - The page is NOT role-gated and does not import `@/lib/auth`, so no auth mock
 *     is installed. There is NO real network here. Greenfield test infra.
 */

// --- Controlled next/navigation state (module scope; `mock`-prefixed so the ----
// --- SWC jest hoister permits the factory below to close over them). ----------
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
    usePathname: () => '/accounts/view',
}));

// Keep the real apiClient module (ApiError / IsApiError stay real) and replace
// only AccountsApi with controllable jest fns.
jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    AccountsApi: { GetAccount: jest.fn(), UpdateAccount: jest.fn() },
}));

import AccountsViewPage from '@/app/accounts/view/page';
import { RenderWithProviders, screen, waitFor, SetupUser } from '../testUtils';
import { AccountsApi, ApiError } from '@/lib/apiClient';
import type { AccountDetail } from '@/types';

/* --------------------------------------------------------------------------- */
/* Typed handle to the mocked collaborator (identity-stable across tests --     */
/* `clearMocks: true` clears its call history but never the fn object itself).  */
/* --------------------------------------------------------------------------- */

const mockGetAccount = jest.mocked(AccountsApi.GetAccount);

/* --------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).                  */
/* --------------------------------------------------------------------------- */

/** Zero-padded 11-digit account id under test (string preserves leading zeros). */
const EXPECTED_ACCT_ID = '00000000011';

/** Raw nine-digit SSN placed in the fixture SOLELY to prove it is never shown. */
const RAW_SSN = '123456789';

/**
 * Masked SSN the page must render: the page's `SSN_MASK_PREFIX`
 * (`\u2022\u2022\u2022-\u2022\u2022-`, i.e. bullet chars) followed by the last
 * four digits of {@link RAW_SSN}. Built from escapes so the expectation matches
 * byte-for-byte and carries no non-ASCII literal in source.
 */
const MASKED_SSN = '\u2022\u2022\u2022-\u2022\u2022-6789';

/** Monetary value carrying trailing zeros — reformatting to a number would drop them. */
const MONEY_CURR_BAL = '1000.00';

/** Rendered monetary text (verbatim value with the display-only `$` prefix). */
const MONEY_CURR_BAL_DISPLAY = '$1000.00';

/** A second rendered monetary value, asserted to prove multiple fields stay verbatim. */
const MONEY_CURR_CYC_DEBIT_DISPLAY = '$250.25';

/** Locale-reformatted variant that MUST NOT appear (proves no thousands separator). */
const MONEY_REFORMATTED_FORBIDDEN = '$1,000.00';

/** The only numeric DTO field, rendered via `String()`. */
const FICO_SCORE = 750;

/** Stringified FICO score the page must render. */
const FICO_SCORE_DISPLAY = '750';

/** Exact prompt shown when no account id is supplied (verbatim from the page). */
const MISSING_ACCT_MESSAGE =
    'No account number provided. Select an account to view its details.';

/** HTTP status used for the not-found error path. */
const NOT_FOUND_HTTP_STATUS = 404;

/**
 * Distinctive error message used for the failure scenario. Deliberately NOT the
 * page's own `NOT_FOUND_MESSAGE` ("Account not found.") so the alert assertion
 * cannot be satisfied by the body's not-found text.
 */
const API_ERROR_MESSAGE = 'Requested account could not be located.';

/* --------------------------------------------------------------------------- */
/* Fixture factory (Ochs rule: PascalCase function; single `overrides` object   */
/* honors the <=4-parameter rule). Returns a fully-populated `AccountDetail`     */
/* (every field the real type declares) with realistic snake_case string values.*/
/* --------------------------------------------------------------------------- */

/**
 * Build a complete {@link AccountDetail} (account fields + nested `customer`).
 * Monetary and identifier fields are strings (Decimal / zero-padded); the FICO
 * score is the sole number. `overrides` win, so any account field (e.g.
 * `active_status`) can be adjusted per test.
 *
 * @param overrides - Partial account fields that replace the defaults.
 * @returns A valid `AccountDetail`.
 */
function MakeAccountDetail(overrides?: Partial<AccountDetail>): AccountDetail {
    const baseDetail: AccountDetail = {
        acct_id: EXPECTED_ACCT_ID,
        active_status: 'Y',
        curr_bal: MONEY_CURR_BAL,
        credit_limit: '5000.00',
        cash_credit_limit: '2500.00',
        open_date: '2020-01-15',
        expiration_date: '2027-01-31',
        reissue_date: '2024-06-01',
        curr_cyc_credit: '3000.00',
        curr_cyc_debit: '250.25',
        addr_zip: '12345',
        group_id: 'GRP001',
        customer: {
            cust_id: '000000011',
            first_name: 'JOHN',
            middle_name: 'Q',
            last_name: 'DOE',
            addr_line_1: '123 MAIN ST',
            addr_line_2: 'APT 4B',
            addr_line_3: 'BLDG C',
            addr_state_cd: 'CA',
            addr_country_cd: 'USA',
            addr_zip: '90210',
            phone_num_1: '555-0100',
            phone_num_2: '555-0200',
            ssn: RAW_SSN,
            govt_issued_id: 'DL1234567',
            date_of_birth: '1985-03-20',
            eft_account_id: 'EFT000001',
            pri_card_holder_ind: 'Y',
            fico_credit_score: FICO_SCORE,
        },
    };

    return { ...baseDetail, ...overrides };
}

beforeEach(() => {
    // Re-assign (never mutate) so each test starts from a known query string;
    // `clearMocks: true` has already reset jest.fn call history by this point.
    mockSearchParams = new URLSearchParams({ acctId: EXPECTED_ACCT_ID });
});

describe('AccountsViewPage', () => {
    it('fetches the account on mount using the acctId query parameter', async () => {
        mockGetAccount.mockResolvedValueOnce(MakeAccountDetail());

        RenderWithProviders(<AccountsViewPage />);

        await waitFor(() => {
            expect(mockGetAccount).toHaveBeenCalledWith(EXPECTED_ACCT_ID);
        });
        expect(mockGetAccount).toHaveBeenCalledTimes(1);

        // Key fields render once the load resolves.
        expect(await screen.findByText(EXPECTED_ACCT_ID)).toBeInTheDocument();
        expect(screen.getByText('JOHN')).toBeInTheDocument();
        expect(screen.getByText('DOE')).toBeInTheDocument();
        expect(screen.getByText('Account Details')).toBeInTheDocument();
        expect(screen.getByText('Customer Details')).toBeInTheDocument();
    });

    it('masks the SSN and never renders the raw nine-digit value', async () => {
        mockGetAccount.mockResolvedValueOnce(MakeAccountDetail());

        RenderWithProviders(<AccountsViewPage />);

        // Masked form (bullet prefix + last four) is present ...
        expect(await screen.findByText(MASKED_SSN)).toBeInTheDocument();
        // ... and the raw SSN is never in the document.
        expect(screen.queryByText(RAW_SSN)).not.toBeInTheDocument();
    });

    it('renders monetary fields verbatim without numeric reformatting', async () => {
        mockGetAccount.mockResolvedValueOnce(MakeAccountDetail());

        RenderWithProviders(<AccountsViewPage />);

        // Trailing zeros preserved and only the display `$` prefix is added.
        expect(await screen.findByText(MONEY_CURR_BAL_DISPLAY)).toBeInTheDocument();
        expect(screen.getByText(MONEY_CURR_CYC_DEBIT_DISPLAY)).toBeInTheDocument();

        // No `Number()`/`toLocaleString()` reformatting: a thousands-separated
        // variant must NOT exist, and the value is never rendered without its
        // decimal places.
        expect(
            screen.queryByText(MONEY_REFORMATTED_FORBIDDEN),
        ).not.toBeInTheDocument();
        expect(screen.queryByText('1000')).not.toBeInTheDocument();
    });

    it('renders the FICO score (the only numeric field) via String()', async () => {
        mockGetAccount.mockResolvedValueOnce(MakeAccountDetail());

        RenderWithProviders(<AccountsViewPage />);

        expect(await screen.findByText(FICO_SCORE_DISPLAY)).toBeInTheDocument();
        expect(screen.getByText('FICO Score')).toBeInTheDocument();
    });

    it('renders an "Active" status chip when active_status is Y', async () => {
        mockGetAccount.mockResolvedValueOnce(
            MakeAccountDetail({ active_status: 'Y' }),
        );

        RenderWithProviders(<AccountsViewPage />);

        expect(await screen.findByText('Active')).toBeInTheDocument();
        expect(screen.queryByText('Inactive')).not.toBeInTheDocument();
    });

    it('renders an "Inactive" status chip when active_status is not Y', async () => {
        mockGetAccount.mockResolvedValueOnce(
            MakeAccountDetail({ active_status: 'N' }),
        );

        RenderWithProviders(<AccountsViewPage />);

        expect(await screen.findByText('Inactive')).toBeInTheDocument();
        expect(screen.queryByText('Active')).not.toBeInTheDocument();
    });

    it('renders read-only detail content with only the account-id picker editable', async () => {
        mockGetAccount.mockResolvedValueOnce(MakeAccountDetail());

        RenderWithProviders(<AccountsViewPage />);

        // Wait for the load to complete so the assertion reflects the loaded UI.
        await screen.findByText(EXPECTED_ACCT_ID);

        // QA #12: the account-id picker is the ONLY editable input on the page.
        // The loaded account/customer detail is rendered as read-only Typography
        // (never inputs), so exactly one textbox — the picker — exists, and there
        // are no numeric (spinbutton) inputs.
        const textboxes = screen.queryAllByRole('textbox');
        expect(textboxes).toHaveLength(1);
        expect(
            screen.getByRole('textbox', { name: /account number/i }),
        ).toBeInTheDocument();
        expect(screen.queryAllByRole('spinbutton')).toHaveLength(0);
    });

    it('does not fetch and shows a prompt when acctId is missing', async () => {
        // No acctId in the query string for this test only.
        mockSearchParams = new URLSearchParams();

        RenderWithProviders(<AccountsViewPage />);

        expect(await screen.findByText(MISSING_ACCT_MESSAGE)).toBeInTheDocument();
        expect(mockGetAccount).not.toHaveBeenCalled();
    });

    it('navigates to ?acctId= when an id is entered in the picker and LOAD is clicked (QA #12)', async () => {
        // Start from the picker-only state (no acctId supplied) so the page is
        // NOT a dead-end: the user can type an id and load it.
        mockSearchParams = new URLSearchParams();
        const user = SetupUser();

        RenderWithProviders(<AccountsViewPage />);

        // The friendly prompt is shown, but now beside an actionable picker.
        expect(await screen.findByText(MISSING_ACCT_MESSAGE)).toBeInTheDocument();

        await user.type(
            screen.getByRole('textbox', { name: /account number/i }),
            EXPECTED_ACCT_ID,
        );
        await user.click(screen.getByRole('button', { name: /load/i }));

        // The picker drives a shareable query-param navigation (which then
        // re-runs the existing fetch effect); it does not fetch imperatively.
        expect(mockPush).toHaveBeenCalledWith(
            `/accounts/view?acctId=${EXPECTED_ACCT_ID}`,
        );
        expect(mockGetAccount).not.toHaveBeenCalled();
    });

    it('surfaces an error alert when the account fetch fails', async () => {
        mockGetAccount.mockRejectedValueOnce(
            new ApiError({
                status: NOT_FOUND_HTTP_STATUS,
                message: API_ERROR_MESSAGE,
            }),
        );

        RenderWithProviders(<AccountsViewPage />);

        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent(API_ERROR_MESSAGE);
    });
});
