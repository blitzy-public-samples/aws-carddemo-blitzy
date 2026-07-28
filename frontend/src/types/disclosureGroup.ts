/**
 * Disclosure-group DTO (interest-rate lookup by account group + tran type/category).
 * Mirrors `backend/app/schemas/disclosure_group.py`.
 * References: app/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD).
 * `interest_rate` is a `string` (Decimal NUMERIC(6,2), Finding #2) - never a number.
 */

export interface DisclosureGroupRead {
    acct_group_id: string;  // X(10)
    tran_type_cd: string;   // X(2)
    tran_cat_cd: string;    // 9(4) - string
    interest_rate: string;  // S9(04)V99 -> NUMERIC(6,2) as string
}
