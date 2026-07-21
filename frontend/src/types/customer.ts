/**
 * Customer DTOs.
 * Mirrors `backend/app/schemas/customer.py`.
 * References: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD); screens COACTVW / COACTUP.
 * SECURITY: `ssn` arrives already MASKED (last-4 only).
 */

/** Full customer read model. Field max lengths noted in comments (from CVCUS01Y). */
export interface CustomerRead {
    cust_id: string;              // 9(09) - string preserves leading zeros
    first_name: string;           // X(25)
    middle_name: string;          // X(25)
    last_name: string;            // X(25)
    addr_line_1: string;          // X(50)
    addr_line_2: string;          // X(50)
    addr_line_3: string;          // X(50)
    addr_state_cd: string;        // X(2)
    addr_country_cd: string;      // X(3)
    addr_zip: string;             // X(10)
    phone_num_1: string;          // X(15)
    phone_num_2: string;          // X(15)
    ssn: string;                  // 9(09) - MASKED (last-4 only)
    govt_issued_id: string;       // X(20)
    date_of_birth: string;        // ISO date (YYYY-MM-DD)
    eft_account_id: string;       // X(10)
    pri_card_holder_ind: string;  // X(1)
    fico_credit_score: number;    // 9(03) - integer
}

/** Editable subset for the account-maintenance screen (COACTUP). cust_id & ssn are not updated here. */
export interface CustomerUpdate {
    first_name: string;
    middle_name?: string;
    last_name: string;
    addr_line_1: string;
    addr_line_2?: string;
    addr_line_3?: string;
    addr_state_cd: string;
    addr_country_cd: string;
    addr_zip: string;
    phone_num_1: string;
    phone_num_2?: string;
    govt_issued_id: string;
    date_of_birth: string;
    eft_account_id?: string;
    pri_card_holder_ind: string;
    fico_credit_score: number;
}

/** Lightweight customer row. */
export interface CustomerSummary {
    cust_id: string;
    first_name: string;
    last_name: string;
}
