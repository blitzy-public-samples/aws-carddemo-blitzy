/**
 * Card cross-reference DTO (card <-> account <-> customer linkage).
 * Mirrors `backend/app/schemas/card_xref.py`.
 * References: app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD).
 * SECURITY: `card_num` arrives MASKED (last-4).
 */

export interface CardXrefRead {
    card_num: string;  // X(16) - MASKED (last-4)
    cust_id: string;   // 9(09)
    acct_id: string;   // 9(11)
}
