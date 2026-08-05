package com.carddemo.account.api.dto;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Response body of the billing-cycle close operation. Three components carry the account identifier
 * and the two billing-cycle accumulators the operation zeroed.
 *
 * <p>Paragraph {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350} performs three
 * statements, and the cycle-close operation of this module reproduces two of them:
 * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at {@code app/cbl/CBACT04C.cbl:L353} and
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at {@code app/cbl/CBACT04C.cbl:L354}. The third statement,
 * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} at {@code app/cbl/CBACT04C.cbl:L352}, has no
 * counterpart in this module, so no component here carries {@code ACCT-CURR-BAL}.</p>
 *
 * <p>Both accumulators feed the credit-limit rule of the authorization service.
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} computes a working balance from
 * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}, and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with
 * {@code ACCT-CREDIT-LIMIT}. A failed comparison assigns reject reason 102,
 * {@code OVERLIMIT TRANSACTION}, at {@code app/cbl/CBTRN02C.cbl:L410-L412}.</p>
 *
 * <p>The record holds no arithmetic and sets no scale. {@code domain/BillingCycleService} zeroes
 * both accumulators, fixes their scale at two, and builds this response.</p>
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
public record CycleCloseResponse(String accountId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleCredit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleDebit) {

    /**
     * Digits an account identifier holds, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Shape both cycle accumulators hold once stored: an optional leading minus, at most ten integer
     * digits, a point, then exactly two fractional digits.
     *
     * <p>{@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13} and
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14} declare the ten
     * integer digits and the two fractional ones.</p>
     */
    static final String MONEY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** {@link #MONEY_PATTERN} compiled, and the check both accumulators run. */
    private static final Pattern MONEY_MATCHER = Pattern.compile(MONEY_PATTERN);

    /**
     * Checks that every component is present and that the account identifier holds exactly eleven
     * digits. A shorter identifier has lost the leading zeros of {@code ACCT-ID PIC 9(11)}, and a
     * caller pads to eleven characters before it builds this response.
     *
     * <p>Both accumulators are stored at the scale their source fields declare and reach the wire as
     * decimal strings rather than as JavaScript Object Notation (JSON) numbers. A JSON number is read
     * back through a binary floating-point type by most parsers, which a platform whose correctness
     * rests on fixed-point arithmetic cannot allow. {@code api/dto/AccountView} and
     * {@code TransactionAuthorized} carry money the same way, so a figure reads alike wherever it
     * appears.
     *
     * <p>Truncation is toward zero, {@link RoundingMode#DOWN}, which is what every arithmetic store
     * in the source does: the {@code ROUNDED} phrase appears nowhere across the twenty-eight programs
     * of {@code app/cbl/}. After a cycle close both accumulators read {@code "0.00"} rather than
     * {@code 0}, which is the value {@code app/cbl/CBACT04C.cbl:L353-L354} leaves.
     *
     * @throws NullPointerException     when any component is null
     * @throws IllegalArgumentException when {@code accountId} holds a width other than eleven, or
     *                                  holds a character other than a digit, or when an accumulator
     *                                  is too wide for its source field
     */
    public CycleCloseResponse {
        if (accountId == null) {
            throw new NullPointerException("accountId is required");
        }
        currentCycleCredit = stored(currentCycleCredit, PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                "currentCycleCredit");
        currentCycleDebit = stored(currentCycleDebit, PicClause.ACCT_CURR_CYC_DEBIT_SCALE,
                "currentCycleDebit");
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
    /**
     * Stores one accumulator at its declared scale and checks the shape it serializes as.
     *
     * <p>No message this method raises carries the component value: a refusal reports the stored
     * width, precision and scale.</p>
     *
     * @param value     the accumulator as the caller supplied it, at any scale
     * @param scale     the scale the source field declares, from {@link PicClause}
     * @param component the component name a refusal reports
     * @return the accumulator at {@code scale}, truncated toward zero
     * @throws NullPointerException     when {@code value} is null
     * @throws IllegalArgumentException when the stored value is too wide for its source field
     */
    private static BigDecimal stored(BigDecimal value, int scale, String component) {
        if (value == null) {
            throw new NullPointerException(component + " is required");
        }

        BigDecimal atScale = value.setScale(scale, RoundingMode.DOWN);
        String text = atScale.toPlainString();
        if (!MONEY_MATCHER.matcher(text).matches()) {
            throw new IllegalArgumentException(component + " must match " + MONEY_PATTERN
                    + " once stored at scale " + scale + ", and the supplied value stores as "
                    + text.length() + " characters with precision " + atScale.precision()
                    + " and scale " + atScale.scale());
        }
        return atScale;
    }

    /**
     * Names all three components and withholds every value.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier and both cycle accumulators.
     *
     * <p>Each appears as {@link EventEnvelope#WITHHELD}, the platform-wide redaction marker.
     *
     * @return a rendering that names all three components and discloses none, never {@code null}
     */
    @Override
    public String toString() {
        return "CycleCloseResponse[accountId=" + EventEnvelope.WITHHELD + ", currentCycleCredit="
                + EventEnvelope.WITHHELD + ", currentCycleDebit=" + EventEnvelope.WITHHELD + "]";
    }
}
