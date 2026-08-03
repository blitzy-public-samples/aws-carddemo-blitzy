package com.carddemo.account.api.dto;

import jakarta.validation.constraints.Size;

/**
 * The account section of an account update request, as a caller submits it.
 *
 * <p>Transformed field by field from the group {@code 10 ACUP-NEW-ACCT-DATA.} at
 * {@code app/cbl/COACTUPC.cbl:L758}, whose submitted fields close at
 * {@code app/cbl/COACTUPC.cbl:L796}. The ten components below appear in that source order.</p>
 *
 * <p>Every component holds text, and the enclosing Representational State Transfer (REST) request
 * body carries each one as a string. The five money components hold the raw fifteen-character form
 * of {@code 05 ALPHA-VARS-FOR-DATA-EDITING.} at {@code app/cbl/COACTUPC.cbl:L411-L416}, the form
 * every signed-decimal edit reads. The three date components hold eight characters in
 * {@code CCYYMMDD} order with no separator, while the stored columns hold ten separated characters,
 * {@code ACCT-UPDATE-RECORD} at {@code app/cbl/COACTUPC.cbl:L427-L429}. Row 1 of
 * {@code app/data/ASCII/acctdata.txt} carries {@code 2014-11-20} at columns 49 through 58.
 * {@code app/cbl/CBTRN02C.cbl:L414} compares a stored expiry as raw text.</p>
 *
 * <p>Each component declares one width constraint and nothing else. No component declares a
 * presence constraint, a format constraint, a digit constraint or a bound. The money widths admit a
 * currency sign and grouping commas, so {@code $1,234.56} reaches the parse that
 * {@code FUNCTION TEST-NUMVAL-C} at {@code app/cbl/COACTUPC.cbl:L2201} gates.
 * {@code domain/validation} holds the field edits and returns one message per validation pass, and
 * {@code domain} performs the parse. Neither runs here.</p>
 *
 * <p>The record carries no account identifier. {@code ACUP-NEW-ACCT-ID-X PIC X(11)} at
 * {@code app/cbl/COACTUPC.cbl:L759} reaches the enclosing request type. The record also carries no
 * customer identifier, since {@code 10 ACUP-NEW-CUST-DATA.} opens at
 * {@code app/cbl/COACTUPC.cbl:L797} with {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 * {@code app/cbl/COACTUPC.cbl:L798}. No component holds a status the service tests before a write,
 * no component holds a checksum, and the record declares no version column and no method.</p>
 *
 * <p>{@code card-platform/docs/traceability-matrix.md} maps each component to its source field,
 * including the corrected spelling of {@link #expirationDate()} against
 * {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11}.
 * {@code card-platform/docs/decision-log.md} covers the eight-character request date against the
 * ten-character stored column, the money components carried as raw text, the truncated label below,
 * and that rename.</p>
 *
 * @param activeStatus       one character of account status,
 *                           {@code ACUP-NEW-ACTIVE-STATUS PIC X(01)} at
 *                           {@code app/cbl/COACTUPC.cbl:L762}. The yes-or-no edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1856} rejects any value that is neither
 *                           {@code Y} nor {@code N}, under {@link #ACCOUNT_STATUS_LABEL} moved at
 *                           {@code app/cbl/COACTUPC.cbl:L1472}
 * @param currentBalance     raw text of the current balance,
 *                           {@code ACUP-NEW-CURR-BAL-X PIC X(15)} at
 *                           {@code app/cbl/COACTUPC.cbl:L414}, moved to the signed-decimal edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1510} under
 *                           {@link #CURRENT_BALANCE_LABEL}. The edit at
 *                           {@code app/cbl/COACTUPC.cbl:L2180} rejects content that the gate at
 *                           {@code app/cbl/COACTUPC.cbl:L2201} reports invalid. The parse writes
 *                           {@code ACUP-NEW-CURR-BAL PIC X(12)} at
 *                           {@code app/cbl/COACTUPC.cbl:L763-L765}
 * @param creditLimit        raw text of the credit limit,
 *                           {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)} at
 *                           {@code app/cbl/COACTUPC.cbl:L412}, moved to the same edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1485} under {@link #CREDIT_LIMIT_LABEL}.
 *                           The parse writes {@code ACUP-NEW-CREDIT-LIMIT PIC X(12)} at
 *                           {@code app/cbl/COACTUPC.cbl:L766-L768}
 * @param cashCreditLimit    raw text of the cash credit limit,
 *                           {@code ACUP-NEW-CASH-CREDIT-LIMIT-X PIC X(15)} at
 *                           {@code app/cbl/COACTUPC.cbl:L413}, moved to the same edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1497-L1498} under
 *                           {@link #CASH_CREDIT_LIMIT_LABEL}. The parse writes
 *                           {@code ACUP-NEW-CASH-CREDIT-LIMIT PIC X(12)} at
 *                           {@code app/cbl/COACTUPC.cbl:L769-L771}
 * @param openDate           eight characters of the open date,
 *                           {@code ACUP-NEW-OPEN-DATE PIC X(08)} at
 *                           {@code app/cbl/COACTUPC.cbl:L772}, sliced into a four-character year
 *                           and two two-character parts at
 *                           {@code app/cbl/COACTUPC.cbl:L773-L777}. The calendar edit reached at
 *                           {@code app/cbl/COACTUPC.cbl:L1480} carries {@link #OPEN_DATE_LABEL},
 *                           and {@code domain/validation/CalendarDateValidator} composes every date
 *                           message
 * @param expirationDate     eight characters of the expiration date,
 *                           {@code ACUP-NEW-EXPIRAION-DATE PIC X(08)} at
 *                           {@code app/cbl/COACTUPC.cbl:L778}, sliced at
 *                           {@code app/cbl/COACTUPC.cbl:L779-L783}. The calendar edit reached at
 *                           {@code app/cbl/COACTUPC.cbl:L1492} carries {@link #EXPIRY_DATE_LABEL}
 * @param reissueDate        eight characters of the reissue date,
 *                           {@code ACUP-NEW-REISSUE-DATE PIC X(08)} at
 *                           {@code app/cbl/COACTUPC.cbl:L784}, sliced at
 *                           {@code app/cbl/COACTUPC.cbl:L785-L789}. The calendar edit reached at
 *                           {@code app/cbl/COACTUPC.cbl:L1505} carries {@link #REISSUE_DATE_LABEL}
 * @param currentCycleCredit raw text of the billing-cycle credit total,
 *                           {@code ACUP-NEW-CURR-CYC-CREDIT-X PIC X(15)} at
 *                           {@code app/cbl/COACTUPC.cbl:L415}, moved to the signed-decimal edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1516-L1517} under
 *                           {@link #CURRENT_CYCLE_CREDIT_LABEL}. The parse writes
 *                           {@code ACUP-NEW-CURR-CYC-CREDIT PIC X(12)} at
 *                           {@code app/cbl/COACTUPC.cbl:L790-L792}
 * @param currentCycleDebit  raw text of the billing-cycle debit total,
 *                           {@code ACUP-NEW-CURR-CYC-DEBIT-X PIC X(15)} at
 *                           {@code app/cbl/COACTUPC.cbl:L416}, moved to the signed-decimal edit at
 *                           {@code app/cbl/COACTUPC.cbl:L1523-L1524} under
 *                           {@link #CURRENT_CYCLE_DEBIT_LABEL}. The parse writes
 *                           {@code ACUP-NEW-CURR-CYC-DEBIT PIC X(12)} at
 *                           {@code app/cbl/COACTUPC.cbl:L793-L795}
 * @param groupId            ten characters of the account group,
 *                           {@code ACUP-NEW-GROUP-ID PIC X(10)} at
 *                           {@code app/cbl/COACTUPC.cbl:L796}. No edit reads this component and the
 *                           program moves no label for it, so width alone bounds it
 */
public record AccountDataRequest(

        @Size(max = ACTIVE_STATUS_MAX_LENGTH,
                message = ACCOUNT_STATUS_LABEL + MUST_BE_YES_OR_NO_MESSAGE_SUFFIX)
        String activeStatus,

        @Size(max = MONEY_MAX_LENGTH,
                message = CURRENT_BALANCE_LABEL + IS_NOT_VALID_MESSAGE_SUFFIX)
        String currentBalance,

        @Size(max = MONEY_MAX_LENGTH,
                message = CREDIT_LIMIT_LABEL + IS_NOT_VALID_MESSAGE_SUFFIX)
        String creditLimit,

        @Size(max = MONEY_MAX_LENGTH,
                message = CASH_CREDIT_LIMIT_LABEL + IS_NOT_VALID_MESSAGE_SUFFIX)
        String cashCreditLimit,

        @Size(max = DATE_MAX_LENGTH)
        String openDate,

        @Size(max = DATE_MAX_LENGTH)
        String expirationDate,

        @Size(max = DATE_MAX_LENGTH)
        String reissueDate,

        @Size(max = MONEY_MAX_LENGTH,
                message = CURRENT_CYCLE_CREDIT_LABEL + IS_NOT_VALID_MESSAGE_SUFFIX)
        String currentCycleCredit,

        @Size(max = MONEY_MAX_LENGTH,
                message = CURRENT_CYCLE_DEBIT_LABEL + IS_NOT_VALID_MESSAGE_SUFFIX)
        String currentCycleDebit,

        @Size(max = GROUP_ID_MAX_LENGTH)
        String groupId) {

    /**
     * Widest {@link #activeStatus()} this record holds, from
     * {@code ACUP-NEW-ACTIVE-STATUS PIC X(01)} at {@code app/cbl/COACTUPC.cbl:L762}.
     */
    public static final int ACTIVE_STATUS_MAX_LENGTH = 1;

    /**
     * Widest money component this record holds. The five members of
     * {@code 05 ALPHA-VARS-FOR-DATA-EDITING.} at {@code app/cbl/COACTUPC.cbl:L412-L416} share one
     * declared width, and {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at
     * {@code app/cbl/COACTUPC.cbl:L55} receives each of them whole.
     */
    public static final int MONEY_MAX_LENGTH = 15;

    /**
     * Widest date component this record holds. {@code ACUP-NEW-OPEN-DATE PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl:L772}, {@code ACUP-NEW-EXPIRAION-DATE PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl:L778} and {@code ACUP-NEW-REISSUE-DATE PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl:L784} share one declared width. Eight characters hold
     * {@code CCYYMMDD} with no separator, the shape the mask {@code 'YYYYMMDD'} at
     * {@code app/cpy/CSUTLDPY.cpy:L291} names. The calendar check is strict: the closing call
     * accepts a date only when the severity read at {@code app/cpy/CSUTLDPY.cpy:L298} is zero.
     */
    public static final int DATE_MAX_LENGTH = 8;

    /**
     * Widest {@link #groupId()} this record holds, from {@code ACUP-NEW-GROUP-ID PIC X(10)} at
     * {@code app/cbl/COACTUPC.cbl:L796}.
     */
    public static final int GROUP_ID_MAX_LENGTH = 10;

    /**
     * Width of the label field every message opens with,
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53}. A label literal
     * wider than this loses its trailing characters on the move.
     */
    public static final int EDIT_VARIABLE_NAME_WIDTH = 25;

    /**
     * Text the yes-or-no edit appends at {@code app/cbl/COACTUPC.cbl:L1886}, with a leading space
     * and a trailing period: {@value #MUST_BE_YES_OR_NO_MESSAGE_SUFFIX}.
     */
    public static final String MUST_BE_YES_OR_NO_MESSAGE_SUFFIX = " must be Y or N.";

    /**
     * Text the signed-decimal edit appends at {@code app/cbl/COACTUPC.cbl:L2209}, with a leading
     * space and no trailing period: {@value #IS_NOT_VALID_MESSAGE_SUFFIX}.
     */
    public static final String IS_NOT_VALID_MESSAGE_SUFFIX = " is not valid";

    /**
     * Label the account status edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1472}:
     * {@value #ACCOUNT_STATUS_LABEL}.
     */
    public static final String ACCOUNT_STATUS_LABEL = "Account Status";

    /**
     * Label the open date edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1478}:
     * {@value #OPEN_DATE_LABEL}.
     */
    public static final String OPEN_DATE_LABEL = "Open Date";

    /**
     * Label the credit limit edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1484}:
     * {@value #CREDIT_LIMIT_LABEL}.
     */
    public static final String CREDIT_LIMIT_LABEL = "Credit Limit";

    /**
     * Label the expiration date edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1490}:
     * {@value #EXPIRY_DATE_LABEL}. The label spells {@code Expiry} in full, while the field name at
     * {@code app/cbl/COACTUPC.cbl:L778} omits a letter from {@code EXPIRATION}.
     */
    public static final String EXPIRY_DATE_LABEL = "Expiry Date";

    /**
     * Label the cash credit limit edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1496}:
     * {@value #CASH_CREDIT_LIMIT_LABEL}.
     */
    public static final String CASH_CREDIT_LIMIT_LABEL = "Cash Credit Limit";

    /**
     * Label the reissue date edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1503}:
     * {@value #REISSUE_DATE_LABEL}.
     */
    public static final String REISSUE_DATE_LABEL = "Reissue Date";

    /**
     * Label the current balance edit runs under, moved at {@code app/cbl/COACTUPC.cbl:L1509}:
     * {@value #CURRENT_BALANCE_LABEL}.
     */
    public static final String CURRENT_BALANCE_LABEL = "Current Balance";

    /**
     * Label the {@link #currentCycleCredit()} edit runs under:
     * {@value #CURRENT_CYCLE_CREDIT_LABEL}, twenty-five characters. The program moves the
     * twenty-six character literal {@code 'Current Cycle Credit Limit'} at
     * {@code app/cbl/COACTUPC.cbl:L1515} into a label field of
     * {@link #EDIT_VARIABLE_NAME_WIDTH} characters. The move drops the final letter, and a trim
     * cannot restore it, so every message under this label opens with these twenty-five characters.
     * {@code card-platform/docs/business-rule-flags.md} carries that finding and the naming of two
     * running totals as limits.
     */
    public static final String CURRENT_CYCLE_CREDIT_LABEL = "Current Cycle Credit Limi";

    /**
     * Label the {@link #currentCycleDebit()} edit runs under, moved at
     * {@code app/cbl/COACTUPC.cbl:L1522}: {@value #CURRENT_CYCLE_DEBIT_LABEL}. The literal is
     * exactly {@link #EDIT_VARIABLE_NAME_WIDTH} characters wide, so the move keeps every character.
     */
    public static final String CURRENT_CYCLE_DEBIT_LABEL = "Current Cycle Debit Limit";
}
