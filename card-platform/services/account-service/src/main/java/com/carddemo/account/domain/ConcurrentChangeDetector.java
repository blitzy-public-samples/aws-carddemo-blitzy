package com.carddemo.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.cobol.PicClause;

/**
 * Reports whether a stored account record or its customer record changed between a read the caller
 * already performed and a later re-read of the same two rows.
 *
 * <p>Realises {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109-L4191}. The
 * paragraph runs two conditions. The first reads ten account fields at
 * {@code app/cbl/COACTUPC.cbl:L4115-L4140}. The second reads seventeen customer fields at
 * {@code app/cbl/COACTUPC.cbl:L4152-L4186}, and a comment at {@code app/cbl/COACTUPC.cbl:L4148}
 * records the split. Either condition failing sets {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, from
 * {@code app/cbl/COACTUPC.cbl:L4143} and {@code app/cbl/COACTUPC.cbl:L4189}.</p>
 *
 * <p>The ten account fields, in the order the source tests them:</p>
 * <ul>
 *   <li>{@code ACCT-ACTIVE-STATUS} at {@code app/cbl/COACTUPC.cbl:L4115}, as supplied</li>
 *   <li>{@code ACCT-CURR-BAL} at {@code app/cbl/COACTUPC.cbl:L4117}, numeric</li>
 *   <li>{@code ACCT-CREDIT-LIMIT} at {@code app/cbl/COACTUPC.cbl:L4119}, numeric</li>
 *   <li>{@code ACCT-CASH-CREDIT-LIMIT} at {@code app/cbl/COACTUPC.cbl:L4121}, numeric</li>
 *   <li>{@code ACCT-CURR-CYC-CREDIT} at {@code app/cbl/COACTUPC.cbl:L4123}, numeric</li>
 *   <li>{@code ACCT-CURR-CYC-DEBIT} at {@code app/cbl/COACTUPC.cbl:L4125}, numeric</li>
 *   <li>{@code ACCT-OPEN-DATE} at {@code app/cbl/COACTUPC.cbl:L4127-L4129}, three slices</li>
 *   <li>{@code ACCT-EXPIRAION-DATE} at {@code app/cbl/COACTUPC.cbl:L4131-L4133}, three slices</li>
 *   <li>{@code ACCT-REISSUE-DATE} at {@code app/cbl/COACTUPC.cbl:L4135-L4137}, three slices</li>
 *   <li>{@code ACCT-GROUP-ID} at {@code app/cbl/COACTUPC.cbl:L4139-L4140}, folded to lower case</li>
 * </ul>
 *
 * <p>The seventeen customer fields, in the order the source tests them:</p>
 * <ul>
 *   <li>{@code CUST-FIRST-NAME} at {@code app/cbl/COACTUPC.cbl:L4152}, folded to upper case</li>
 *   <li>{@code CUST-MIDDLE-NAME} at {@code app/cbl/COACTUPC.cbl:L4154}, folded to upper case</li>
 *   <li>{@code CUST-LAST-NAME} at {@code app/cbl/COACTUPC.cbl:L4156}, folded to upper case</li>
 *   <li>{@code CUST-ADDR-LINE-1} at {@code app/cbl/COACTUPC.cbl:L4158}, folded to upper case</li>
 *   <li>{@code CUST-ADDR-LINE-2} at {@code app/cbl/COACTUPC.cbl:L4160}, folded to upper case</li>
 *   <li>{@code CUST-ADDR-LINE-3} at {@code app/cbl/COACTUPC.cbl:L4162}, folded to upper case</li>
 *   <li>{@code CUST-ADDR-STATE-CD} at {@code app/cbl/COACTUPC.cbl:L4164}, folded to upper case</li>
 *   <li>{@code CUST-ADDR-COUNTRY-CD} at {@code app/cbl/COACTUPC.cbl:L4166}, folded to upper
 *       case</li>
 *   <li>{@code CUST-ADDR-ZIP} at {@code app/cbl/COACTUPC.cbl:L4168}, as supplied</li>
 *   <li>{@code CUST-PHONE-NUM-1} at {@code app/cbl/COACTUPC.cbl:L4169}, as supplied</li>
 *   <li>{@code CUST-PHONE-NUM-2} at {@code app/cbl/COACTUPC.cbl:L4170}, as supplied</li>
 *   <li>{@code CUST-SSN} at {@code app/cbl/COACTUPC.cbl:L4171}, numeric</li>
 *   <li>{@code CUST-GOVT-ISSUED-ID} at {@code app/cbl/COACTUPC.cbl:L4172-L4173}, folded to upper
 *       case</li>
 *   <li>{@code CUST-DOB-YYYY-MM-DD} at {@code app/cbl/COACTUPC.cbl:L4174-L4179}, three slices</li>
 *   <li>{@code CUST-EFT-ACCOUNT-ID} at {@code app/cbl/COACTUPC.cbl:L4181-L4182}, as supplied</li>
 *   <li>{@code CUST-PRI-CARD-HOLDER-IND} at {@code app/cbl/COACTUPC.cbl:L4183-L4185}, as
 *       supplied</li>
 *   <li>{@code CUST-FICO-CREDIT-SCORE} at {@code app/cbl/COACTUPC.cbl:L4186}, numeric</li>
 * </ul>
 *
 * <p>{@code ACCT-ID} and {@code CUST-ID} appear in neither condition. The five account amounts read
 * the numeric redefinitions of the saved copy at {@code app/cbl/COACTUPC.cbl:L676-L707}, each
 * declared {@code PIC S9(10)V99} over a {@code PIC X(12)} display field.</p>
 *
 * <p>Each sliced date is read at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Positions 4 and 7
 * hold a separator, and no condition reads either position, so two dates differing in a separator
 * alone compare as unchanged. {@code card-platform/docs/business-rule-flags.md} carries that
 * finding, the comparison of {@code CUST-ADDR-LINE-2} against the optional-field comment at
 * {@code app/cbl/COACTUPC.cbl:L1613}, and the exit both failure branches take at
 * {@code app/cbl/COACTUPC.cbl:L4144} and {@code app/cbl/COACTUPC.cbl:L4190}.</p>
 *
 * <p>The date of birth carries two offset sets in the source. The re-read side is
 * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19}, ten bytes holding two
 * separators, read at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. The saved side is
 * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:L746}, eight bytes
 * holding none, read at {@code (1:4)}, {@code (5:2)} and {@code (7:2)}. Both arguments here supply
 * {@link CustomerEntity#getDateOfBirth()}, one ten-character form, and one offset set covers both.
 * {@code card-platform/docs/decision-log.md} records that and two further deviations.</p>
 *
 * <p>A caller reads both rows, re-reads them, and passes the four states to
 * {@link #storedRecordChanged}. This class holds no repository, no transaction boundary and no
 * logging, it names no field in any output, and it returns a verdict for every input.
 * {@code card-platform/docs/data-model.md} draws the two tables and the path between them.</p>
 */
@Component
public final class ConcurrentChangeDetector {

    /**
     * The message a caller reports on a detected change, reproduced from
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code app/cbl/COACTUPC.cbl:L521-L522}. The
     * condition name sets {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479},
     * whose unset state is {@code 88 WS-RETURN-MSG-OFF VALUE SPACES.} at
     * {@code app/cbl/COACTUPC.cbl:L480}.
     */
    public static final String RECORD_CHANGED_MESSAGE =
            "Record changed by some one else. Please review";

    /**
     * First position of the year slice, from {@code (1:4)} in one-based source positions.
     */
    private static final int YEAR_BEGIN_INDEX = 0;

    /**
     * Position after the year slice, from {@code (1:4)}.
     */
    private static final int YEAR_END_INDEX = 4;

    /**
     * First position of the month slice, from {@code (6:2)}. Position 4 carries a separator and no
     * slice covers it.
     */
    private static final int MONTH_BEGIN_INDEX = 5;

    /**
     * Position after the month slice, from {@code (6:2)}.
     */
    private static final int MONTH_END_INDEX = 7;

    /**
     * First position of the day slice, from {@code (9:2)}. Position 7 carries a separator and no
     * slice covers it.
     */
    private static final int DAY_BEGIN_INDEX = 8;

    /**
     * Position after the day slice, from {@code (9:2)}, and the shortest length the three slices
     * need.
     */
    private static final int DAY_END_INDEX = 10;

    /**
     * Declared width of every sliced field. {@link PicClause#ACCT_OPEN_DATE_WIDTH},
     * {@link PicClause#ACCT_EXPIRATION_DATE_WIDTH}, {@link PicClause#ACCT_REISSUE_DATE_WIDTH} and
     * {@link PicClause#CUST_DOB_WIDTH} each publish ten.
     */
    private static final int SLICED_FIELD_WIDTH = PicClause.CUST_DOB_WIDTH;

    /**
     * Scale of the two integral customer amounts. {@code CUST-SSN PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L17} and {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L22} carry no decimal position, and
     * {@link CustomerEntity#getSocialSecurityNumber()} and
     * {@link CustomerEntity#getFicoCreditScore()} map at this scale.
     */
    private static final int INTEGRAL_SCALE = 0;

    /**
     * The character a fixed-width field carries in its unused positions.
     */
    private static final String PADDING_CHARACTER = " ";

    /**
     * Reports whether the stored records changed under the caller.
     *
     * <p>The verdict names no field and carries no value, so a caller reports
     * {@link #RECORD_CHANGED_MESSAGE} alone. A pair holding {@code null} on one side counts as a
     * change, and this method returns a verdict for every input.</p>
     *
     * @param reReadAccount the account record read again, holding the state of
     *     {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4}
     * @param reReadCustomer the customer record read again, holding the state of
     *     {@code CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy:L4}
     * @param fetchedAccount the account record the caller read earlier, holding the state of
     *     {@code ACUP-OLD-ACCT-DATA}
     * @param fetchedCustomer the customer record the caller read earlier, holding the state of
     *     {@code ACUP-OLD-CUST-DATA}
     * @return {@code true} when either record changed, {@code false} when both pairs match
     */
    public boolean storedRecordChanged(AccountEntity reReadAccount, CustomerEntity reReadCustomer,
            AccountEntity fetchedAccount, CustomerEntity fetchedCustomer) {

        return !accountMatches(reReadAccount, fetchedAccount)
                || !customerMatches(reReadCustomer, fetchedCustomer);
    }

    /**
     * Reports whether the ten account fields of the first condition all match, from
     * {@code app/cbl/COACTUPC.cbl:L4115-L4140}. Each term below carries the source line it
     * reproduces.
     *
     * @param reRead the account record read again
     * @param fetched the account record the caller read earlier
     * @return {@code true} when all ten comparisons match
     */
    private static boolean accountMatches(AccountEntity reRead, AccountEntity fetched) {

        if (reRead == null || fetched == null) {
            return reRead == fetched;
        }

        return
                // L4115 ACCT-ACTIVE-STATUS EQUAL ACUP-OLD-ACTIVE-STATUS
                matchesAsSupplied(reRead.getActiveStatus(), fetched.getActiveStatus(),
                        PicClause.ACCT_ACTIVE_STATUS_WIDTH)
                // L4117 ACCT-CURR-BAL EQUAL ACUP-OLD-CURR-BAL-N
                && matchesNumerically(reRead.getCurrentBalance(), fetched.getCurrentBalance(),
                        PicClause.ACCT_CURR_BAL_SCALE)
                // L4119 ACCT-CREDIT-LIMIT EQUAL ACUP-OLD-CREDIT-LIMIT-N
                && matchesNumerically(reRead.getCreditLimit(), fetched.getCreditLimit(),
                        PicClause.ACCT_CREDIT_LIMIT_SCALE)
                // L4121 ACCT-CASH-CREDIT-LIMIT EQUAL ACUP-OLD-CASH-CREDIT-LIMIT-N
                && matchesNumerically(reRead.getCashCreditLimit(), fetched.getCashCreditLimit(),
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE)
                // L4123 ACCT-CURR-CYC-CREDIT EQUAL ACUP-OLD-CURR-CYC-CREDIT-N
                && matchesNumerically(reRead.getCurrentCycleCredit(),
                        fetched.getCurrentCycleCredit(), PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
                // L4125 ACCT-CURR-CYC-DEBIT EQUAL ACUP-OLD-CURR-CYC-DEBIT-N
                && matchesNumerically(reRead.getCurrentCycleDebit(),
                        fetched.getCurrentCycleDebit(), PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
                // L4127-L4129 ACCT-OPEN-DATE (1:4) (6:2) (9:2)
                && matchesBySlices(reRead.getOpenDate(), fetched.getOpenDate())
                // L4131-L4133 ACCT-EXPIRAION-DATE (1:4) (6:2) (9:2)
                && matchesBySlices(reRead.getExpirationDate(), fetched.getExpirationDate())
                // L4135-L4137 ACCT-REISSUE-DATE (1:4) (6:2) (9:2)
                && matchesBySlices(reRead.getReissueDate(), fetched.getReissueDate())
                // L4139-L4140 FUNCTION LOWER-CASE (ACCT-GROUP-ID)
                && matchesFoldedToLowerCase(reRead.getGroupId(), fetched.getGroupId(),
                        PicClause.ACCT_GROUP_ID_WIDTH);
    }

    /**
     * Reports whether the seventeen customer fields of the second condition all match, from
     * {@code app/cbl/COACTUPC.cbl:L4152-L4186}. Each term below carries the source line it
     * reproduces.
     *
     * @param reRead the customer record read again
     * @param fetched the customer record the caller read earlier
     * @return {@code true} when all seventeen comparisons match
     */
    private static boolean customerMatches(CustomerEntity reRead, CustomerEntity fetched) {

        if (reRead == null || fetched == null) {
            return reRead == fetched;
        }

        return
                // L4152 FUNCTION UPPER-CASE (CUST-FIRST-NAME)
                matchesFoldedToUpperCase(reRead.getFirstName(), fetched.getFirstName(),
                        PicClause.CUST_FIRST_NAME_WIDTH)
                // L4154 FUNCTION UPPER-CASE (CUST-MIDDLE-NAME)
                && matchesFoldedToUpperCase(reRead.getMiddleName(), fetched.getMiddleName(),
                        PicClause.CUST_MIDDLE_NAME_WIDTH)
                // L4156 FUNCTION UPPER-CASE (CUST-LAST-NAME)
                && matchesFoldedToUpperCase(reRead.getLastName(), fetched.getLastName(),
                        PicClause.CUST_LAST_NAME_WIDTH)
                // L4158 FUNCTION UPPER-CASE (CUST-ADDR-LINE-1)
                && matchesFoldedToUpperCase(reRead.getAddressLine1(), fetched.getAddressLine1(),
                        PicClause.CUST_ADDR_LINE_1_WIDTH)
                // L4160 FUNCTION UPPER-CASE (CUST-ADDR-LINE-2)
                && matchesFoldedToUpperCase(reRead.getAddressLine2(), fetched.getAddressLine2(),
                        PicClause.CUST_ADDR_LINE_2_WIDTH)
                // L4162 FUNCTION UPPER-CASE (CUST-ADDR-LINE-3)
                && matchesFoldedToUpperCase(reRead.getAddressCity(), fetched.getAddressCity(),
                        PicClause.CUST_ADDR_LINE_3_WIDTH)
                // L4164 FUNCTION UPPER-CASE (CUST-ADDR-STATE-CD)
                && matchesFoldedToUpperCase(reRead.getAddressStateCode(),
                        fetched.getAddressStateCode(), PicClause.CUST_ADDR_STATE_CD_WIDTH)
                // L4166 FUNCTION UPPER-CASE (CUST-ADDR-COUNTRY-CD)
                && matchesFoldedToUpperCase(reRead.getAddressCountryCode(),
                        fetched.getAddressCountryCode(), PicClause.CUST_ADDR_COUNTRY_CD_WIDTH)
                // L4168 CUST-ADDR-ZIP EQUAL ACUP-OLD-CUST-ADDR-ZIP
                && matchesAsSupplied(reRead.getAddressZip(), fetched.getAddressZip(),
                        PicClause.CUST_ADDR_ZIP_WIDTH)
                // L4169 CUST-PHONE-NUM-1 EQUAL ACUP-OLD-CUST-PHONE-NUM-1
                && matchesAsSupplied(reRead.getPhoneNumber1(), fetched.getPhoneNumber1(),
                        PicClause.CUST_PHONE_NUM_1_WIDTH)
                // L4170 CUST-PHONE-NUM-2 EQUAL ACUP-OLD-CUST-PHONE-NUM-2
                && matchesAsSupplied(reRead.getPhoneNumber2(), fetched.getPhoneNumber2(),
                        PicClause.CUST_PHONE_NUM_2_WIDTH)
                // L4171 CUST-SSN EQUAL ACUP-OLD-CUST-SSN
                && matchesNumerically(reRead.getSocialSecurityNumber(),
                        fetched.getSocialSecurityNumber(), INTEGRAL_SCALE)
                // L4172-L4173 FUNCTION UPPER-CASE (CUST-GOVT-ISSUED-ID)
                && matchesFoldedToUpperCase(reRead.getGovernmentIssuedId(),
                        fetched.getGovernmentIssuedId(), PicClause.CUST_GOVT_ISSUED_ID_WIDTH)
                // L4174-L4179 CUST-DOB-YYYY-MM-DD (1:4) (6:2) (9:2)
                && matchesBySlices(reRead.getDateOfBirth(), fetched.getDateOfBirth())
                // L4181-L4182 CUST-EFT-ACCOUNT-ID EQUAL ACUP-OLD-CUST-EFT-ACCOUNT-ID
                && matchesAsSupplied(reRead.getEftAccountId(), fetched.getEftAccountId(),
                        PicClause.CUST_EFT_ACCOUNT_ID_WIDTH)
                // L4183-L4185 CUST-PRI-CARD-HOLDER-IND EQUAL ACUP-OLD-CUST-PRI-HOLDER-IND
                && matchesAsSupplied(reRead.getPrimaryCardHolderIndicator(),
                        fetched.getPrimaryCardHolderIndicator(),
                        PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH)
                // L4186 CUST-FICO-CREDIT-SCORE EQUAL ACUP-OLD-CUST-FICO-SCORE
                && matchesNumerically(reRead.getFicoCreditScore(), fetched.getFicoCreditScore(),
                        INTEGRAL_SCALE);
    }

    /**
     * Compares two text fields with the case each side carries.
     *
     * <p>Both operands reach the declared width first, so trailing padding never separates two
     * equal values. A leading space survives that step and separates them.</p>
     *
     * @param reRead the value read again
     * @param fetched the value the caller read earlier
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return {@code true} when the two padded values match
     */
    private static boolean matchesAsSupplied(String reRead, String fetched, int declaredWidth) {

        return Objects.equals(atDeclaredWidth(reRead, declaredWidth),
                atDeclaredWidth(fetched, declaredWidth));
    }

    /**
     * Compares two text fields with both sides folded to upper case, reproducing
     * {@code FUNCTION UPPER-CASE}. No operand is trimmed.
     *
     * @param reRead the value read again
     * @param fetched the value the caller read earlier
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return {@code true} when the two folded values match
     */
    private static boolean matchesFoldedToUpperCase(String reRead, String fetched,
            int declaredWidth) {

        return Objects.equals(
                atDeclaredWidth(reRead, declaredWidth).toUpperCase(Locale.ROOT),
                atDeclaredWidth(fetched, declaredWidth).toUpperCase(Locale.ROOT));
    }

    /**
     * Compares two text fields with both sides folded to lower case, reproducing
     * {@code FUNCTION LOWER-CASE} at {@code app/cbl/COACTUPC.cbl:L4139-L4140}. No operand is
     * trimmed.
     *
     * @param reRead the value read again
     * @param fetched the value the caller read earlier
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return {@code true} when the two folded values match
     */
    private static boolean matchesFoldedToLowerCase(String reRead, String fetched,
            int declaredWidth) {

        return Objects.equals(
                atDeclaredWidth(reRead, declaredWidth).toLowerCase(Locale.ROOT),
                atDeclaredWidth(fetched, declaredWidth).toLowerCase(Locale.ROOT));
    }

    /**
     * Compares two amounts by value at the declared scale.
     *
     * <p>{@link BigDecimal#compareTo} reads value alone, so two operands holding one amount at two
     * scales match. An unset operand counts as zero, the value a fixed-point source field holds
     * when it carries no digits.</p>
     *
     * @param reRead the amount read again
     * @param fetched the amount the caller read earlier
     * @param scale the scale {@link PicClause} publishes for the field
     * @return {@code true} when the two amounts hold one value
     */
    private static boolean matchesNumerically(BigDecimal reRead, BigDecimal fetched, int scale) {

        return atScale(reRead, scale).compareTo(atScale(fetched, scale)) == 0;
    }

    /**
     * Compares two ten-character dates through the year, month and day slices the source reads,
     * from {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Positions 4 and 7 hold the separators and
     * no slice covers them.
     *
     * <p>An unset operand and an operand shorter than {@link #SLICED_FIELD_WIDTH} both count as a
     * change, and no slice runs against a value that cannot supply it.</p>
     *
     * @param reRead the date read again
     * @param fetched the date the caller read earlier
     * @return {@code true} when the three slice pairs match
     */
    private static boolean matchesBySlices(String reRead, String fetched) {

        if (reRead == null || fetched == null) {
            return false;
        }

        if (reRead.length() < SLICED_FIELD_WIDTH || fetched.length() < SLICED_FIELD_WIDTH) {
            return false;
        }

        return reRead.substring(YEAR_BEGIN_INDEX, YEAR_END_INDEX)
                        .equals(fetched.substring(YEAR_BEGIN_INDEX, YEAR_END_INDEX))
                && reRead.substring(MONTH_BEGIN_INDEX, MONTH_END_INDEX)
                        .equals(fetched.substring(MONTH_BEGIN_INDEX, MONTH_END_INDEX))
                && reRead.substring(DAY_BEGIN_INDEX, DAY_END_INDEX)
                        .equals(fetched.substring(DAY_BEGIN_INDEX, DAY_END_INDEX));
    }

    /**
     * Returns a value padded on the right to the width the field declares. An unset value becomes
     * that width in spaces, and a longer value is returned whole.
     *
     * @param value the value to pad
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return the padded value, never {@code null}
     */
    private static String atDeclaredWidth(String value, int declaredWidth) {

        String present = value == null ? "" : value;

        if (present.length() >= declaredWidth) {
            return present;
        }

        return present + PADDING_CHARACTER.repeat(declaredWidth - present.length());
    }

    /**
     * Returns an amount at the declared scale, with the digits past that scale dropped. An unset
     * amount becomes zero at that scale.
     *
     * @param value the amount to scale
     * @param scale the scale {@link PicClause} publishes for the field
     * @return the scaled amount, never {@code null}
     */
    private static BigDecimal atScale(BigDecimal value, int scale) {

        BigDecimal present = value == null ? BigDecimal.ZERO : value;

        return present.setScale(scale, RoundingMode.DOWN);
    }
}
