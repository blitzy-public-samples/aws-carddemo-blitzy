/** Account Update page spec — legacy origin BMS COACTUP / Tx CAUP / program COACTUPC. Optimistic-lock (409) preserved. */

/*
 * accountsUpdate.test.tsx — Jest + React Testing Library component/integration
 * spec for the modern Account Update page (@/app/accounts/update/page), the
 * redesign of the legacy 3270 "Update Account" screen.
 *
 *   Legacy origin (Minimal Change Clause, AAP §0.8.1):
 *     BMS map  COACTUP  (app/bms/COACTUP.bms)
 *     CICS Tx  CAUP
 *     COBOL    COACTUPC (app/cbl/COACTUPC.cbl)
 *
 * The REAL page is rendered against a mocked API client and mocked Next.js
 * navigation — there is NO real network here.
 *
 * CRITICAL mock invariant: `ApiError` and `IsApiError` are kept REAL (spread via
 * jest.requireActual) while only `AccountsApi.GetAccount` / `.UpdateAccount` are
 * replaced with jest mocks. The page narrows the optimistic-lock branch with
 * `if (IsApiError(err) && err.status === 409)`; auto-mocking those helpers would
 * break that branch, so they must stay real.
 */

// 'next/navigation' mock — mock*-prefixed jest.fns so the (hoisted) factory may
// close over them lazily without tripping the out-of-scope / TDZ rule.
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();
let mockSearchParams = new URLSearchParams();

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => mockSearchParams,
    usePathname: () => '/accounts/update',
}));

// '@/lib/apiClient' mock — keep ApiError / IsApiError REAL; mock ONLY the two
// AccountsApi methods this page calls (GetAccount for Load, UpdateAccount for Save).
jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    AccountsApi: {
        GetAccount: jest.fn(),
        UpdateAccount: jest.fn(),
    },
}));

import { RenderWithProviders, screen, waitFor, SetupUser } from '../testUtils';
import { AccountsApi, ApiError, IsApiError } from '@/lib/apiClient';
import type { AccountDetail, AccountUpdate, CustomerRead } from '@/types';
import AccountsUpdatePage from '@/app/accounts/update/page';

/* ------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).               */
/* ------------------------------------------------------------------------- */

/** Zero-padded 11-digit account key (string preserves leading zeros; PIC 9(11)). */
const TYPED_ACCT_ID = '00000000011';

/** Exact optimistic-lock message the page renders on HTTP 409 (COACTUPC L521-522). */
const CONFLICT_MESSAGE = 'Record changed by another user; reload and retry.';

/** Exact success banner text the page renders after a 200 save (COACTUPC L475). */
const SAVE_SUCCESS_MESSAGE = 'Changes committed successfully.';

/** Distinct non-conflict error message used to prove the generic (non-409) branch. */
const GENERIC_ERROR_MESSAGE = 'Account update failed validation.';

/** Named HTTP status codes (no magic numbers). */
const HTTP_CONFLICT = 409;
const HTTP_BAD_REQUEST = 400;

/**
 * AccountUpdate carries exactly the 9 body keys the backend schema permits
 * (extra="forbid"): active_status, credit_limit, cash_credit_limit, curr_bal,
 * curr_cyc_credit, curr_cyc_debit, expiration_date, reissue_date, group_id.
 * `acct_id` is a PATH param (never a body key); `open_date` and the customer
 * `addr_zip` are read-only/derived and must NOT be sent (sending them yields
 * HTTP 422 extra_forbidden — QA issue #11).
 */
const ACCOUNT_UPDATE_KEY_COUNT = 9;

/* ------------------------------------------------------------------------- */
/* Fixture factories (PascalCase per Ochs; single optional overrides object). */
/* ------------------------------------------------------------------------- */

/**
 * Build a full snake_case CustomerRead fixture. `ssn` is already MASKED (last-4
 * only) and there is deliberately no CVV/password field.
 *
 * @param overrides - Partial fields that replace any customer default.
 * @returns A valid CustomerRead.
 */
function MakeCustomer(overrides?: Partial<CustomerRead>): CustomerRead {
    const baseCustomer: CustomerRead = {
        cust_id: '000000001',
        first_name: 'JANE',
        middle_name: 'Q',
        last_name: 'DOE',
        addr_line_1: '123 MAIN ST',
        addr_line_2: 'APT 4B',
        addr_line_3: '',
        addr_state_cd: 'CA',
        addr_country_cd: 'USA',
        addr_zip: '90210',
        phone_num_1: '(555)111-2222',
        phone_num_2: '(555)333-4444',
        ssn: '***-**-1234',
        govt_issued_id: 'DL-1234567',
        date_of_birth: '1985-03-20',
        eft_account_id: 'EFT0001',
        pri_card_holder_ind: 'Y',
        fico_credit_score: 720,
    };

    return { ...baseCustomer, ...overrides };
}

/**
 * Build a full snake_case AccountDetail fixture (account + owning customer).
 * Monetary fields are exact decimal STRINGS and dates are ISO STRINGS — never
 * numbers (AAP §0.7.1). `active_status` defaults to 'Y'.
 *
 * @param overrides - Partial fields that replace any account default.
 * @returns A valid AccountDetail.
 */
function MakeAccountDetail(overrides?: Partial<AccountDetail>): AccountDetail {
    const baseDetail: AccountDetail = {
        acct_id: TYPED_ACCT_ID,
        active_status: 'Y',
        curr_bal: '1500.00',
        credit_limit: '1000.00',
        cash_credit_limit: '500.00',
        open_date: '2020-01-15',
        expiration_date: '2027-01-31',
        reissue_date: '2024-06-01',
        curr_cyc_credit: '250.00',
        curr_cyc_debit: '75.00',
        addr_zip: '90210',
        group_id: 'GROUP001',
        customer: MakeCustomer(),
    };

    return { ...baseDetail, ...overrides };
}

/* ------------------------------------------------------------------------- */
/* Suite.                                                                    */
/* ------------------------------------------------------------------------- */

describe('AccountsUpdatePage', () => {
    beforeEach(() => {
        // `clearMocks: true` resets call history; also drop any queued/persistent
        // implementations so a persistent resolve in one test never leaks forward.
        (AccountsApi.GetAccount as jest.Mock).mockReset();
        (AccountsApi.UpdateAccount as jest.Mock).mockReset();
        mockPush.mockReset();
        mockReplace.mockReset();
        mockBack.mockReset();
        mockRefresh.mockReset();
        mockPrefetch.mockReset();
        mockSearchParams = new URLSearchParams();
    });

    /**
     * Type the account key then click Load, waiting for the editable form to
     * appear. Assumes `AccountsApi.GetAccount` is already armed to resolve. The
     * required "Credit Limit" label carries a trailing `*`, so it is matched with
     * a regex rather than an exact string.
     *
     * @param user - The user-event session driving the interactions.
     */
    async function TypeAndLoad(user: ReturnType<typeof SetupUser>): Promise<void> {
        await user.type(screen.getByLabelText(/Account Number/i), TYPED_ACCT_ID);
        await user.click(screen.getByRole('button', { name: 'Load' }));
        await screen.findByLabelText(/Credit Limit/);
    }

    // ----------------------------------------------------------------------
    // Mock-wiring guard — proves ApiError / IsApiError are the REAL exports.
    // ----------------------------------------------------------------------

    it('keeps ApiError and IsApiError real (not auto-mocked)', () => {
        const conflictError = new ApiError({
            status: HTTP_CONFLICT,
            message: CONFLICT_MESSAGE,
        });

        expect(IsApiError(conflictError)).toBe(true);
        expect(conflictError.status).toBe(HTTP_CONFLICT);
        // A bare look-alike object is NOT an ApiError instance.
        expect(IsApiError({ status: HTTP_CONFLICT })).toBe(false);
    });

    // ----------------------------------------------------------------------
    // 1. Load populates the editable form.
    // ----------------------------------------------------------------------

    it('loads the account and populates the editable form', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        RenderWithProviders(<AccountsUpdatePage />);

        await user.type(screen.getByLabelText(/Account Number/i), TYPED_ACCT_ID);
        await user.click(screen.getByRole('button', { name: 'Load' }));

        await waitFor(() => {
            expect(AccountsApi.GetAccount).toHaveBeenCalledWith(TYPED_ACCT_ID);
        });

        expect(await screen.findByLabelText(/Credit Limit/)).toHaveValue('1000.00');
        expect(screen.getByLabelText('Current Balance')).toHaveValue('1500.00');
        expect(screen.getByLabelText(/Account Group/)).toHaveValue('GROUP001');
    });

    // ----------------------------------------------------------------------
    // 2. Customer fields are read-only (they are not part of AccountUpdate).
    // ----------------------------------------------------------------------

    it('renders the customer fields read-only', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        // Customer fields use FormField `readOnly` -> the DOM `readonly` attribute
        // (NOT `disabled`); they are display-only context, never submitted.
        expect(screen.getByLabelText('First Name')).toHaveAttribute('readonly');
        expect(screen.getByLabelText('Last Name')).toHaveAttribute('readonly');
        expect(screen.getByLabelText('SSN')).toHaveAttribute('readonly');
        // SSN arrives already masked (last-4 only) — never the raw 9-digit value.
        expect(screen.getByLabelText('SSN')).toHaveValue('***-**-1234');
    });

    // ----------------------------------------------------------------------
    // 3. Successful save (200): body carries the 9 AccountUpdate keys, NO acct_id.
    // ----------------------------------------------------------------------

    it('saves edits and shows the success banner with no acct_id in the body', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        (AccountsApi.UpdateAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail({ credit_limit: '2000.00' }),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        // Edit an editable money field via the resolved input (FormField.onChange
        // is (name, value), so drive the underlying element with userEvent).
        const creditInput = screen.getByLabelText(/Credit Limit/);
        await user.clear(creditInput);
        await user.type(creditInput, '2000.00');

        await user.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(AccountsApi.UpdateAccount).toHaveBeenCalledWith(
                TYPED_ACCT_ID,
                expect.objectContaining({
                    active_status: 'Y',
                    credit_limit: '2000.00',
                    cash_credit_limit: '500.00',
                    expiration_date: '2027-01-31',
                    reissue_date: '2024-06-01',
                    group_id: 'GROUP001',
                }),
            );
        });

        // The PATH param carries the id; the body must NOT repeat it, and it must
        // contain exactly the 9 AccountUpdate keys.
        const submittedPayload = (AccountsApi.UpdateAccount as jest.Mock).mock
            .calls[0][1] as AccountUpdate;
        expect(submittedPayload).not.toHaveProperty('acct_id');
        // QA issue #11: open_date and addr_zip are forbidden by the backend schema
        // and must never be sent (their presence previously caused a 422 on save).
        expect(submittedPayload).not.toHaveProperty('open_date');
        expect(submittedPayload).not.toHaveProperty('addr_zip');
        expect(Object.keys(submittedPayload)).toHaveLength(ACCOUNT_UPDATE_KEY_COUNT);

        expect(await screen.findByText(SAVE_SUCCESS_MESSAGE)).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // 3b. QA #11 — the "Opened" (open_date) field is read-only, sourced from the
    //     fetched record, and never enters the mutable payload.
    // ----------------------------------------------------------------------

    it('renders open_date read-only and omits it from the save payload', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        (AccountsApi.UpdateAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        // "Opened" is display-only context: it carries the DOM `readonly` attribute
        // and shows the fetched open_date; it is NOT an editable account field.
        const openedInput = screen.getByLabelText('Opened');
        expect(openedInput).toHaveAttribute('readonly');
        expect(openedInput).toHaveValue('2020-01-15');

        await user.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(AccountsApi.UpdateAccount).toHaveBeenCalledTimes(1);
        });
        const submittedPayload = (AccountsApi.UpdateAccount as jest.Mock).mock
            .calls[0][1] as AccountUpdate;
        expect(submittedPayload).not.toHaveProperty('open_date');
    });

    // ----------------------------------------------------------------------
    // 3c. QA #9 — arriving with ?acctId= auto-loads the account on mount
    //     (no manual Load click), mirroring /cards/update.
    // ----------------------------------------------------------------------

    it('auto-loads the account when reached with an ?acctId= query parameter', async () => {
        mockSearchParams = new URLSearchParams(`acctId=${TYPED_ACCT_ID}`);
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        RenderWithProviders(<AccountsUpdatePage />);

        // No Load click: the editable form appears purely from the deep link.
        await waitFor(() => {
            expect(AccountsApi.GetAccount).toHaveBeenCalledWith(TYPED_ACCT_ID);
        });
        expect(await screen.findByLabelText(/Credit Limit/)).toHaveValue('1000.00');
    });

    // ----------------------------------------------------------------------
    // 4. CRITICAL — optimistic-lock (HTTP 409) conflict path.
    // ----------------------------------------------------------------------

    it('surfaces the optimistic-lock conflict message on HTTP 409', async () => {
        const user = SetupUser();
        // Persistent resolve: the 409 branch re-fetches (await HandleLoad), so
        // GetAccount runs for BOTH the initial Load and the post-conflict reload.
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValue(MakeAccountDetail());
        (AccountsApi.UpdateAccount as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: HTTP_CONFLICT, message: 'Conflict' }),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        const creditInput = screen.getByLabelText(/Credit Limit/);
        await user.clear(creditInput);
        await user.type(creditInput, '3000.00');

        await user.click(screen.getByRole('button', { name: 'Save' }));

        // Exact conflict message, surfaced through ErrorAlert.
        expect(await screen.findByText(CONFLICT_MESSAGE)).toBeInTheDocument();
        // No success banner and no navigation-away.
        expect(screen.queryByText(SAVE_SUCCESS_MESSAGE)).not.toBeInTheDocument();
        expect(mockPush).not.toHaveBeenCalled();
        expect(mockReplace).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // 5. Non-409 API error → generic alert (NOT the conflict message), no reload.
    // ----------------------------------------------------------------------

    it('shows a generic error (not the conflict message) for a non-409 failure', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        (AccountsApi.UpdateAccount as jest.Mock).mockRejectedValueOnce(
            new ApiError({
                status: HTTP_BAD_REQUEST,
                message: GENERIC_ERROR_MESSAGE,
            }),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        const creditInput = screen.getByLabelText(/Credit Limit/);
        await user.clear(creditInput);
        await user.type(creditInput, '4000.00');

        await user.click(screen.getByRole('button', { name: 'Save' }));

        expect(await screen.findByText(GENERIC_ERROR_MESSAGE)).toBeInTheDocument();
        expect(screen.queryByText(CONFLICT_MESSAGE)).not.toBeInTheDocument();
        // A non-409 failure does NOT trigger the reload, so GetAccount ran once.
        expect(AccountsApi.GetAccount).toHaveBeenCalledTimes(1);
    });

    // ----------------------------------------------------------------------
    // 6. active_status renders as a Y/N select whose value updates on change.
    // ----------------------------------------------------------------------

    it('renders active_status as a Y/N select and updates the selected value', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail(),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        // MUI Select renders a combobox; its accessible name derives from the
        // "Active Y/N" label (regex tolerates the required `*` / value suffix).
        const statusSelect = screen.getByRole('combobox', { name: /Active/i });
        expect(statusSelect).toHaveTextContent('Active (Y)');

        // Options are portal-rendered only after the combobox is opened.
        await user.click(statusSelect);
        expect(
            screen.getByRole('option', { name: 'Active (Y)' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('option', { name: 'Inactive (N)' }),
        ).toBeInTheDocument();

        await user.click(screen.getByRole('option', { name: 'Inactive (N)' }));

        await waitFor(() => {
            expect(
                screen.getByRole('combobox', { name: /Active/i }),
            ).toHaveTextContent('Inactive (N)');
        });
    });

    // ----------------------------------------------------------------------
    // 7. Money fields are type="text" (verbatim strings); dates are type="date".
    // ----------------------------------------------------------------------

    it('keeps money fields as text strings and date fields as date inputs', async () => {
        const user = SetupUser();
        (AccountsApi.GetAccount as jest.Mock).mockResolvedValueOnce(
            MakeAccountDetail({ credit_limit: '1000.00' }),
        );
        RenderWithProviders(<AccountsUpdatePage />);
        await TypeAndLoad(user);

        const creditInput = screen.getByLabelText(/Credit Limit/);
        expect(creditInput).toHaveAttribute('type', 'text');
        expect(creditInput).toHaveValue('1000.00');

        // Re-typing the same money string leaves it verbatim (no numeric coercion).
        await user.clear(creditInput);
        await user.type(creditInput, '1000.00');
        expect(creditInput).toHaveValue('1000.00');

        const openedInput = screen.getByLabelText('Opened');
        expect(openedInput).toHaveAttribute('type', 'date');
        expect(openedInput).toHaveValue('2020-01-15');
    });

    // ----------------------------------------------------------------------
    // 8. Load-before-save guard: Save is disabled until an account is loaded.
    // ----------------------------------------------------------------------

    it('disables Save until an account is loaded', () => {
        RenderWithProviders(<AccountsUpdatePage />);

        // Before a successful Load the editable form is absent and Save is disabled
        // (`disabled={!isLoaded || isSaving}`), so the operator cannot submit an
        // update — COACTUP requires a keyed, loaded account first. A disabled MUI
        // Button carries `pointer-events: none`, so it is unclickable by design;
        // asserting the disabled state IS the guard, and UpdateAccount stays unused.
        expect(screen.queryByLabelText(/Credit Limit/)).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
        expect(AccountsApi.UpdateAccount).not.toHaveBeenCalled();
    });
});
