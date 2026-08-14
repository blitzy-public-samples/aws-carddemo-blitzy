package com.carddemo.account.api.dto;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Account projection returned by {@code GET /accounts/{accountId}}.
 *
 * <p>Spring Security protects this route. {@code config/SecurityConfig} authenticates every caller
 * over HTTP Basic and admits a {@code USER} or an {@code ADMIN} identity whose scope names the
 * requested account; a route named by no rule is denied. The projection carries a credit limit, a
 * balance and both cycle counters, so the demo stack binds each published port to the loopback
 * address and seeds only the synthetic rows of {@code app/data/ASCII/acctdata.txt}. Do not expose
 * the port, and do not load real account data.
 *
 * <p>Eleven components carry the eleven values that {@code 1200-SETUP-SCREEN-VARS.} at
 * {@code app/cbl/COACTVWC.cbl:L460} moves for a located account. The gate
 * {@code IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER} at
 * {@code app/cbl/COACTVWC.cbl:L471-L472} guards ten of those moves, and
 * {@code app/cbl/COACTVWC.cbl:L468} supplies the eleventh.
 *
 * <p>The components appear in the order the source moves them: L468 first, then L473 through L490.
 * That order differs from the declaration order of {@code 01 ACCOUNT-RECORD.} at
 * {@code app/cpy/CVACT01Y.cpy:L4}. The copybook declares the three dates at L10 through L12 ahead
 * of the two billing-cycle accumulators at L13 and L14. The source moves the two accumulators
 * first, and this record follows the source order rather than the copybook order. This module ships
 * no {@code openapi.yaml} yet, so no document beside this record describes that order.
 *
 * <p>Three copybook fields reach this record under a changed shape or not at all.
 * {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy:L15} is absent, and the paragraph at
 * {@code app/cbl/COACTVWC.cbl:L473-L490} never moves it. {@code ACCT-EXPIRAION-DATE} at
 * {@code app/cpy/CVACT01Y.cpy:L11} arrives as {@link #expirationDate()}, the field name
 * {@code AccountEntity} carries. The trailing {@code FILLER PIC X(178)} at
 * {@code app/cpy/CVACT01Y.cpy:L17} maps to no component.
 *
 * <p>The record holds no customer identifier under any name. {@code 01 ACCOUNT-RECORD.} declares
 * none, and an account reaches a customer through the card cross-reference alone, which another
 * module owns.
 *
 * <p>Every component name matches the field of the same value on {@code AccountEntity}. A mapper
 * between the two renames nothing. The record carries values and holds no arithmetic, no format
 * check and no status check.
 *
 * @param accountId          eleven-character account identifier, left-padded with zeros.
 *                           {@code app/cbl/COACTVWC.cbl:L468} moves {@code CC-ACCT-ID} into the
 *                           screen field {@code ACCTSIDO}. The declared source field is
 *                           {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 00000000001} at
 *                           columns 1 through 11, and text keeps those eight leading zeros through
 *                           serialization.
 * @param activeStatus       one-character status flag. Source
 *                           {@code ACCT-ACTIVE-STATUS PIC X(01)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L6}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L473}. The component carries the flag and
 *                           applies no rule. No source program reads the flag before it posts a
 *                           transaction. All 50 records of {@code app/data/ASCII/acctdata.txt} hold
 *                           {@code Y}.
 * @param currentBalance     posted balance at scale 2, twelve total digits. Source
 *                           {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L7}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L475}.
 * @param creditLimit        credit limit at scale 2, twelve total digits. Source
 *                           {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L8}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L477}. {@code app/cbl/CBTRN02C.cbl:L407}
 *                           compares the field with the working balance the two billing-cycle
 *                           accumulators produce.
 * @param cashCreditLimit    cash credit limit at scale 2, twelve total digits. Source
 *                           {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L9}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L479-L480}.
 * @param currentCycleCredit billing-cycle credit accumulator at scale 2, twelve total digits.
 *                           Source {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L13}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L482-L483}.
 *                           {@code app/cbl/CBTRN02C.cbl:L403} reads the field as the first term of
 *                           the credit-limit comparison.
 * @param currentCycleDebit  billing-cycle debit accumulator at scale 2, twelve total digits. Source
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L14}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L485}.
 *                           {@code app/cbl/CBTRN02C.cbl:L404} subtracts the field in the
 *                           credit-limit comparison.
 * @param openDate           open date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-OPEN-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L10}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L487}.
 *                           {@code app/cbl/COACTUPC.cbl:L4127-L4129} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, which places a
 *                           separator at position 5 and at position 8. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2014-11-20} at
 *                           columns 49 through 58.
 * @param expirationDate     expiration date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L11}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L488}.
 *                           {@code app/cbl/CBTRN02C.cbl:L414} compares the field as text with the
 *                           first ten characters of a 26-character transaction origin timestamp.
 *                           {@code app/cbl/COACTUPC.cbl:L4131-L4133} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2025-05-20} at
 *                           columns 59 through 68.
 * @param reissueDate        reissue date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-REISSUE-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L12}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L489}.
 *                           {@code app/cbl/COACTUPC.cbl:L4135-L4137} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2025-05-20} at
 *                           columns 69 through 78.
 * @param groupId            account group identifier, ten characters. Source
 *                           {@code ACCT-GROUP-ID PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L16}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L490}.
 *                           {@code app/cbl/COACTUPC.cbl:L4139-L4140} applies
 *                           {@code FUNCTION LOWER-CASE} to both sides when it compares the field.
 *                           All 50 records of {@code app/data/ASCII/acctdata.txt} hold ten spaces.
 */
public record AccountView(
        String accountId,
        String activeStatus,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentBalance,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal creditLimit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashCreditLimit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleCredit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleDebit,
        String openDate,
        String expirationDate,
        String reissueDate,
        String groupId) {

    /**
     * Shape every monetary component holds once stored: an optional leading minus, at most ten
     * integer digits, a point, then exactly two fractional digits.
     *
     * <p>The ten integer digits come from {@code PIC S9(10)V99}, which
     * {@code app/cpy/CVACT01Y.cpy:L7} declares for the balance, {@code :L8} and {@code :L9} for the
     * two credit limits, and {@code :L13} and {@code :L14} for the two cycle accumulators.</p>
     */
    static final String MONEY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** {@link #MONEY_PATTERN} compiled, and the check every monetary component runs. */
    private static final Pattern MONEY_MATCHER = Pattern.compile(MONEY_PATTERN);

    /**
     * Stores every monetary component at the scale its source field declares, then checks its shape.
     *
     * <p>Five components carry money, and each reaches the wire as a decimal string rather than as a
     * JavaScript Object Notation (JSON) number. A JSON number is read back through a binary
     * floating-point type by most parsers, which is the one thing a platform whose correctness rests
     * on fixed-point arithmetic cannot allow. {@code TransactionAuthorized} in
     * {@code card-platform/libs/event-contracts} carries its amount the same way, so an amount reads
     * alike whether it arrives on an event or in a response.
     *
     * <p>Scale is pinned here rather than trusted from the caller. Each component is stored at the
     * scale of its own {@code PIC} clause, so {@code 100} and {@code 100.0} and {@code 100.00} all
     * serialize as {@code "100.00"} and a response never reports a balance to one decimal place.
     *
     * <p>Truncation is toward zero, {@link RoundingMode#DOWN}, which is what every arithmetic store
     * in the source does: the {@code ROUNDED} phrase appears nowhere across the twenty-eight programs
     * of {@code app/cbl/}. Half-up rounding here would report a cent the ledger never held.
     *
     * <p>No message this constructor raises carries a component value: a refusal reports the stored
     * width, precision and scale, and each one describes one cardholder's finances.
     *
     * @throws NullPointerException     when a monetary component is null
     * @throws IllegalArgumentException when a monetary component is too wide for its source field
     */
    public AccountView {
        currentBalance = stored(currentBalance, PicClause.ACCT_CURR_BAL_SCALE, "currentBalance");
        creditLimit = stored(creditLimit, PicClause.ACCT_CREDIT_LIMIT_SCALE, "creditLimit");
        cashCreditLimit = stored(cashCreditLimit, PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE,
                "cashCreditLimit");
        currentCycleCredit = stored(currentCycleCredit, PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                "currentCycleCredit");
        currentCycleDebit = stored(currentCycleDebit, PicClause.ACCT_CURR_CYC_DEBIT_SCALE,
                "currentCycleDebit");
    }

    /**
     * Stores one monetary component at its declared scale and checks the shape it serializes as.
     *
     * @param value     the component as the caller supplied it, at any scale
     * @param scale     the scale the source field declares, from {@link PicClause}
     * @param component the component name a refusal reports
     * @return the component at {@code scale}, truncated toward zero
     * @throws NullPointerException     when {@code value} is null
     * @throws IllegalArgumentException when the stored value is too wide for its source field
     */
    private static BigDecimal stored(BigDecimal value, int scale, String component) {
        Objects.requireNonNull(value, component + " is required");

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
     * Names all eleven components and withholds every value.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier, the balance, both credit limits and both cycle
     * accumulators.
     *
     * <p>Every component of an account view describes one cardholder's finances, so the text
     * discloses none of them. Each appears as {@link EventEnvelope#WITHHELD}, the platform-wide
     * redaction marker.
     *
     * @return a rendering that names all eleven components and discloses none, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountView[accountId=" + EventEnvelope.WITHHELD + ", activeStatus="
                + EventEnvelope.WITHHELD + ", currentBalance=" + EventEnvelope.WITHHELD
                + ", creditLimit=" + EventEnvelope.WITHHELD + ", cashCreditLimit="
                + EventEnvelope.WITHHELD + ", currentCycleCredit=" + EventEnvelope.WITHHELD
                + ", currentCycleDebit=" + EventEnvelope.WITHHELD + ", openDate="
                + EventEnvelope.WITHHELD + ", expirationDate=" + EventEnvelope.WITHHELD
                + ", reissueDate=" + EventEnvelope.WITHHELD + ", groupId=" + EventEnvelope.WITHHELD
                + "]";
    }
}
