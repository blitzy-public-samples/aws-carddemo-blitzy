/**
 * Transaction-category balance DTO.
 * Mirrors `backend/app/schemas/tran_category_balance.py`.
 * References: app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD).
 * `balance` is a `string` (Decimal NUMERIC(11,2)) - never a number.
 */

export interface TranCategoryBalanceRead {
    acct_id: string;       // 9(11)
    tran_type_cd: string;  // X(2)
    tran_cat_cd: string;   // 9(4) - string
    balance: string;       // S9(09)V99 -> NUMERIC(11,2) as string
}
