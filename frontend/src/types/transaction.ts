/**
 * Transaction DTOs.
 * Mirrors `backend/app/schemas/transaction.py`.
 * References: app/cpy/CVTRA05Y.cpy (TRAN-RECORD) + app/cpy/CVTRA06Y.cpy (daily);
 * screens COTRN00 / COTRN01 / COTRN02.
 * `tran_amt` is a `string` (Decimal NUMERIC(11,2)) - never a number.
 * SECURITY: `card_num` arrives MASKED (last-4).
 * NOTE: `tran_desc` max length differs by direction - 100 on read, 60 on create.
 */

/** Full transaction read model. */
export interface TransactionRead {
    tran_id: string;         // X(16)
    tran_type_cd: string;    // X(2)
    tran_cat_cd: string;     // 9(4) - string
    tran_source: string;     // X(10)
    tran_desc: string;       // X(100)
    tran_amt: string;        // S9(09)V99 -> NUMERIC(11,2) as string
    merchant_id: string;     // 9(9) - string
    merchant_name: string;   // X(50)
    merchant_city: string;   // X(50)
    merchant_zip: string;    // X(10)
    card_num: string;        // X(16) - MASKED
    // orig_ts / proc_ts mirror backend TransactionRead.orig_ts / proc_ts which
    // are Optional[datetime]; proc_ts is null until a daily transaction is posted
    // (CVTRA05Y.TRAN-PROC-TS is only stamped at posting time). Must be nullable
    // so the detail screen never assumes a value is present.
    orig_ts: string | null;  // ISO datetime, null when not yet available
    proc_ts: string | null;  // ISO datetime, null until posted
}

/** Lightweight transaction row for the list screen (COTRN00). */
export interface TransactionSummary {
    tran_id: string;
    card_num: string;        // MASKED
    tran_type_cd: string;
    tran_cat_cd: string;
    tran_amt: string;
    tran_source: string;
    // Mirrors backend TransactionSummary.orig_ts (Optional[datetime]); nullable
    // so the browse grid's date column never assumes a value is present.
    orig_ts: string | null;
}

/** Add-transaction payload (COTRN02). tran_desc max 60 on create. */
export interface TransactionCreate {
    // acct_id / card_num are OPTIONAL, mirroring the backend `Optional[str]`
    // (schemas/transaction.py): the COTRN02 "(or)" rule requires at least one of
    // the two, and an unused key must be OMITTED (not sent as an empty string,
    // which the backend digit validator rejects — QA finding C5).
    acct_id?: string;        // 9(11) - target account (optional)
    card_num?: string;       // X(16) (optional)
    tran_type_cd: string;    // X(2)
    tran_cat_cd: string;     // 9(4)
    tran_source: string;     // X(10)
    tran_desc: string;       // X(60) - add screen truncates to 60
    tran_amt: string;        // NUMERIC(11,2) as string
    merchant_id: string;     // 9(9)
    merchant_name: string;   // X(50)
    merchant_city: string;   // X(50)
    merchant_zip: string;    // X(10)
    orig_ts: string;         // date / datetime
    proc_ts: string;         // date / datetime
}
