/**
 * Transaction-category lookup DTO.
 * Mirrors `backend/app/schemas/transaction_category.py`.
 * References: app/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD).
 */

export interface TransactionCategoryRead {
    tran_type_cd: string;        // X(2)
    tran_cat_cd: string;         // 9(4) - string
    tran_cat_type_desc: string;  // X(50)
}
