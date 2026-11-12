/**
 * Transaction-type lookup DTO.
 * Mirrors `backend/app/schemas/transaction_type.py`.
 * References: app/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD).
 */

export interface TransactionTypeRead {
    tran_type: string;       // X(2)
    tran_type_desc: string;  // X(50)
}
