package com.carddemo.account.api.dto;

import java.math.BigDecimal;

/**
 * Response body of the billing-cycle close operation. Three components carry the account identifier
 * and the two billing-cycle accumulators the operation zeroed.
 *
 * <p>Paragraph {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350} performs three
 * statements, and the cycle-close operation of this module reproduces two of them:
 * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at {@code app/cbl/CBACT04C.cbl:L353} and
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at {@code app/cbl/CBACT04C.cbl:L354}. The third statement,
 * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} at {@code app/cbl/CBACT04C.cbl:L352}, has no
 * counterpart in this module, so no component here carries {@code ACCT-CURR-BAL}.
 * {@code card-platform/docs/traceability-matrix.md} records that omission.</p>
 *
 * <p>Both accumulators feed the credit-limit rule of the authorization service.
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} computes a working balance from
 * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}, and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with
 * {@code ACCT-CREDIT-LIMIT}. A failed comparison assigns reject reason 102,
 * {@code OVERLIMIT TRANSACTION}, at {@code app/cbl/CBTRN02C.cbl:L410-L412}.</p>
 *
 * <p>The record holds no arithmetic and sets no scale. {@code domain/BillingCycleService} zeroes
 * both accumulators and fixes their scale before it builds this response.
 * {@code card-platform/docs/decision-log.md} records the component type and identifier form
 * decisions, and {@code card-platform/docs/business-rule-flags.md} carries the flagged account
 * rules.</p>
 *
 * @param accountId          the eleven-digit identifier of the account whose billing cycle closed,
 *                           carried as text. {@code ACCT-ID PIC 9(11)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L5} is numeric display, so row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} holds {@code 00000000001} at
 *                           columns 1 through 11. Text keeps those ten leading zeros through
 *                           serialization.
 * @param currentCycleCredit the billing-cycle credit accumulator, zero once the operation succeeds.
 *                           {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L13} holds twelve digits at scale 2, and
 *                           {@code app/cbl/CBACT04C.cbl:L353} moves zero into it. The component
 *                           name matches the field of {@code entity/AccountEntity}.
 * @param currentCycleDebit  the billing-cycle debit accumulator, zero once the operation succeeds.
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L14} holds twelve digits at scale 2, and
 *                           {@code app/cbl/CBACT04C.cbl:L354} moves zero into it. The component
 *                           name matches the field of {@code entity/AccountEntity}.
 */
public record CycleCloseResponse(String accountId, BigDecimal currentCycleCredit,
        BigDecimal currentCycleDebit) {

    /**
     * Digits an account identifier holds, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Checks that every component is present and that the account identifier holds exactly eleven
     * digits. A shorter identifier has lost the leading zeros of {@code ACCT-ID PIC 9(11)}, and a
     * caller pads to eleven characters before it builds this response.
     *
     * @throws NullPointerException     when any component is null
     * @throws IllegalArgumentException when {@code accountId} holds a width other than eleven, or
     *                                  holds a character other than a digit
     */
    public CycleCloseResponse {
        if (accountId == null) {
            throw new NullPointerException("accountId is required");
        }
        if (currentCycleCredit == null) {
            throw new NullPointerException("currentCycleCredit is required");
        }
        if (currentCycleDebit == null) {
            throw new NullPointerException("currentCycleDebit is required");
        }
        if (accountId.length() != ACCOUNT_ID_DIGITS) {
            throw new IllegalArgumentException("accountId holds " + accountId.length()
                    + " characters and ACCT-ID holds " + ACCOUNT_ID_DIGITS);
        }
        for (int position = 0; position < accountId.length(); position++) {
            char digit = accountId.charAt(position);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("accountId holds a character other than a digit"
                        + " at position " + (position + 1));
            }
        }
    }
}
