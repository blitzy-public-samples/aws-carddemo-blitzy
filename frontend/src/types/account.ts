import type { CustomerRead } from './customer';

/**
 * Account DTOs.
 * Mirrors `backend/app/schemas/account.py`.
 * References: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD); screens COACTVW / COACTUP.
 * All five monetary fields are `string` (Decimal NUMERIC(12,2)) - never a number (Section 0.7.1).
 * `AccountDetail` is the composite payload for GET /accounts/{acctId}.
 */

/** Account read model. Monetary fields are Decimal strings; dates are ISO strings. */
export interface AccountRead {
    acct_id: string;            // 9(11) - string preserves leading zeros
    active_status: string;      // X(1)
    curr_bal: string;           // S9(10)V99 -> NUMERIC(12,2) as string
    credit_limit: string;       // NUMERIC(12,2)
    cash_credit_limit: string;  // NUMERIC(12,2)
    open_date: string;          // ISO date
    expiration_date: string;    // ISO date
    reissue_date: string;       // ISO date
    curr_cyc_credit: string;    // NUMERIC(12,2)
    curr_cyc_debit: string;     // NUMERIC(12,2)
    addr_zip: string;           // X(10)
    group_id: string;           // X(10)
}

/** Composite account + owning customer (payload for GET /accounts/{acctId}). */
export type AccountDetail = AccountRead & {
    customer: CustomerRead;
};

/**
 * Client-echoed before-image of the editable account fields (COACTUP).
 * Mirrors the backend `AccountBeforeImage` schema (backend/app/schemas/account.py).
 *
 * This is the optimistic-lock token that reproduces the legacy COACTUPC
 * READ-for-UPDATE -> REWRITE lost-update guard (AAP §0.7.4). The ORM model
 * carries no `version`/`updated_at` column (Minimal Change Clause), so the
 * client instead echoes the values it last read for the editable fields. The
 * service re-reads the `SELECT ... FOR UPDATE` locked row and compares each
 * supplied field; any divergence is a concurrent modification and is rejected
 * with HTTP 409.
 *
 * The five monetary fields and `active_status` are REQUIRED (they always exist
 * on a read account and anchor the lost-update check); the two dates and
 * `group_id` are optional (they may be null on the record) and are compared
 * only when supplied. Monetary values are Decimal strings (never numbers,
 * Section 0.7.1); dates are ISO strings.
 */
export interface AccountBeforeImage {
    active_status: string;      // X(1) - REQUIRED
    curr_bal: string;           // NUMERIC(12,2) as string - REQUIRED
    credit_limit: string;       // NUMERIC(12,2) - REQUIRED
    cash_credit_limit: string;  // NUMERIC(12,2) - REQUIRED
    curr_cyc_credit: string;    // NUMERIC(12,2) - REQUIRED
    curr_cyc_debit: string;     // NUMERIC(12,2) - REQUIRED
    expiration_date?: string;   // ISO date - optional (compared only when sent)
    reissue_date?: string;      // ISO date - optional (compared only when sent)
    group_id?: string;          // X(10) - optional (compared only when sent)
}

/**
 * Editable fields for the account-update screen (COACTUP).
 * Mirrors the backend `AccountUpdate` schema exactly (extra="forbid"): only the
 * fields below are accepted by PUT /accounts/{acctId}. `open_date` and the
 * customer `addr_zip` are read-only/derived on this screen and are intentionally
 * excluded from the mutable payload (sending them yields HTTP 422 extra_forbidden).
 *
 * `before_image` is REQUIRED (QA finding C2): the backend `AccountUpdate` schema
 * declares it as a mandatory nested echo used for the optimistic-lock compare,
 * so omitting it made every save fail with HTTP 422. It is populated from the
 * pre-edit account snapshot and is a control field, not an edited value.
 */
export interface AccountUpdate {
    before_image: AccountBeforeImage;
    active_status: string;
    credit_limit: string;
    cash_credit_limit: string;
    curr_bal?: string;
    curr_cyc_credit?: string;
    curr_cyc_debit?: string;
    expiration_date: string;
    reissue_date: string;
    group_id: string;
}
