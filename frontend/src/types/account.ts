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

/** Editable fields for the account-update screen (COACTUP). */
export interface AccountUpdate {
    active_status: string;
    credit_limit: string;
    cash_credit_limit: string;
    curr_bal?: string;
    curr_cyc_credit?: string;
    curr_cyc_debit?: string;
    open_date?: string;
    expiration_date: string;
    reissue_date: string;
    addr_zip?: string;
    group_id: string;
}
