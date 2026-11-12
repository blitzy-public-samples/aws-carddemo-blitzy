/**
 * Bill-payment DTOs.
 * Mirrors `backend/app/schemas/billpay.py`.
 * References: app/cpy-bms/COBIL00.CPY (bill-pay screen), app/cpy/CVACT01Y.cpy (balances).
 * F-006: available_credit = credit_limit - curr_bal.
 * All monetary fields are `string` (Decimal) - never a number.
 */

/**
 * Bill-payment request (COBIL00). Inputs are acct_id + confirm ('Y'/'N')
 * only; the server always pays the FULL balance (no partial-payment field).
 */
export interface BillPayRequest {
    acct_id: string;
    confirm: string;
}

/** Bill-payment result. available_credit = credit_limit - curr_bal (F-006). */
export interface BillPayResponse {
    acct_id: string;
    curr_bal: string;
    credit_limit: string;
    available_credit: string;
    payment_amount: string;
    tran_id?: string;
    message?: string;
}
