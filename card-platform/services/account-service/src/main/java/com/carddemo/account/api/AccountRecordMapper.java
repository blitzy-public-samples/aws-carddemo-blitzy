package com.carddemo.account.api;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.domain.AccountSnapshot;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.domain.validation.NumericRequiredValidator;
import com.carddemo.account.domain.validation.SignedDecimalValidator;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts the request records of this package into persistent entities, and entities into the view
 * records a response carries.
 *
 * <p>The request carries every value as text, matching the screen fields of
 * {@code app/cbl/COACTUPC.cbl}. The group {@code ALPHA-VARS-FOR-DATA-EDITING} at
 * {@code app/cbl/COACTUPC.cbl:L411} declares five members at {@code app/cbl/COACTUPC.cbl:L412-L416},
 * each {@code PIC X(15)}, and the three account dates at {@code app/cbl/COACTUPC.cbl:L772},
 * {@code app/cbl/COACTUPC.cbl:L778} and {@code app/cbl/COACTUPC.cbl:L784} are each
 * {@code PIC X(08)}. The stored columns hold a decimal and a ten-character date, so this class
 * carries the conversion between the two shapes.
 *
 * <p>Two conversions can fail on a value a caller supplied: a monetary field and the credit score.
 * {@link #convertibleValues} reports the first such field in the order
 * {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:L1470-L1676} edits them, using the
 * validator that owns the message text. Every other edit runs inside
 * {@code domain/AccountUpdateService}.
 *
 * <p>Field mapping and the source constructs that reach no component:
 * {@code card-platform/docs/traceability-matrix.md}.
 */
final class AccountRecordMapper {

    /**
     * Records a store that dropped a high-order digit. It names the field's integer capacity and
     * withholds the figure, matching {@code domain/PostedTransactionService}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountRecordMapper.class);

    /**
     * Characters a request date holds, from {@code ACUP-NEW-OPEN-DATE PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl:L772}.
     */
    static final int REQUEST_DATE_WIDTH = 8;

    /** The character a stored date carries between its three parts. */
    private static final String DATE_SEPARATOR = "-";

    /** Index past the four-character year of a request date. */
    private static final int YEAR_END_INDEX = 4;

    /** Index past the two-character month of a request date. */
    private static final int MONTH_END_INDEX = 6;

    /** Index past the two-character day of a request date. */
    private static final int DAY_END_INDEX = 8;

    /** Scale of a whole-number field. The credit score carries no fractional digit. */
    private static final int INTEGRAL_SCALE = 0;

    /** The character a numeric identifier pads with, from a numeric {@code MOVE}. */
    private static final String LEADING_ZERO = "0";

    /** The character a fixed-width text field pads with. */
    private static final String PADDING = " ";

    /** The character {@code LOW-VALUES} fills a field with. */
    private static final char NULL_CHARACTER = '\u0000';

    /** No instance of this class exists. Every member is static. */
    private AccountRecordMapper() {
        throw new AssertionError("AccountRecordMapper holds only static members");
    }

    /**
     * Reports the first supplied value this class cannot convert, in source edit order.
     *
     * <p>The five monetary fields run in the order
     * {@code app/cbl/COACTUPC.cbl:L1484}, {@code app/cbl/COACTUPC.cbl:L1496},
     * {@code app/cbl/COACTUPC.cbl:L1509}, {@code app/cbl/COACTUPC.cbl:L1515} and
     * {@code app/cbl/COACTUPC.cbl:L1522} edit them, and the credit score runs last from
     * {@code app/cbl/COACTUPC.cbl:L1545}. A value that is absent, empty, all spaces or
     * {@code LOW-VALUES} passes here and reaches the edit inside
     * {@code domain/AccountUpdateService}.
     *
     * @param account  the account section of the request, or {@code null} when the body carries
     *                 none
     * @param customer the customer section of the request, or {@code null} when the body carries
     *                 none
     * @return a passing verdict when every supplied value converts, otherwise a failing verdict
     *         carrying the message the source edit writes
     */
    static EditResult convertibleValues(AccountDataRequest account, CustomerDataRequest customer) {

        EditResult verdict = amountConverts(AccountDataRequest.CREDIT_LIMIT_LABEL,
                account == null ? null : account.creditLimit());
        if (!verdict.valid()) {
            return verdict;
        }
        verdict = amountConverts(AccountDataRequest.CASH_CREDIT_LIMIT_LABEL,
                account == null ? null : account.cashCreditLimit());
        if (!verdict.valid()) {
            return verdict;
        }
        verdict = amountConverts(AccountDataRequest.CURRENT_BALANCE_LABEL,
                account == null ? null : account.currentBalance());
        if (!verdict.valid()) {
            return verdict;
        }
        verdict = amountConverts(AccountDataRequest.CURRENT_CYCLE_CREDIT_LABEL,
                account == null ? null : account.currentCycleCredit());
        if (!verdict.valid()) {
            return verdict;
        }
        verdict = amountConverts(AccountDataRequest.CURRENT_CYCLE_DEBIT_LABEL,
                account == null ? null : account.currentCycleDebit());
        if (!verdict.valid()) {
            return verdict;
        }
        return creditScoreConverts(customer == null ? null : customer.ficoCreditScore());
    }

    /**
     * Builds the account values a caller submits.
     *
     * <p>The identifier arrives at eleven digit characters, matching
     * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} at {@code app/cbl/COACTVWC.cbl:L78}. Each date arrives
     * as eight characters and reaches the column as ten. Each monetary field arrives as text and
     * reaches the column truncated to the scale of its Picture clause. Each text component reaches
     * the column at the width its Picture clause declares, as the moves at
     * {@code app/cbl/COACTUPC.cbl:L3962-L4002} and the rewrite at {@code :L4066} leave it. A
     * component the request leaves absent stays absent, and
     * {@code app/cbl/COACTUPC.cbl:L2184-L2185} counts spaces and {@code LOW-VALUES} as absent.
     *
     * @param accountId the account identifier at its declared width
     * @param request   the account section of the request, or {@code null} when the body carries
     *                  none
     * @return the account values, with a value this class cannot convert left absent
     */
    static AccountEntity accountOf(String accountId, AccountDataRequest request) {

        AccountEntity account = new AccountEntity();
        account.setAccountId(accountId);
        if (request == null) {
            return account;
        }

        account.setActiveStatus(textAtDeclaredWidth(request.activeStatus(),
                PicClause.ACCT_ACTIVE_STATUS_WIDTH));
        account.setCurrentBalance(amountOf(request.currentBalance(),
                PicClause.ACCT_CURR_BAL_PRECISION, PicClause.ACCT_CURR_BAL_SCALE));
        account.setCreditLimit(
                amountOf(request.creditLimit(), PicClause.ACCT_CREDIT_LIMIT_PRECISION,
                        PicClause.ACCT_CREDIT_LIMIT_SCALE));
        account.setCashCreditLimit(
                amountOf(request.cashCreditLimit(), PicClause.ACCT_CASH_CREDIT_LIMIT_PRECISION,
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE));
        if (isSupplied(request.openDate())) {
            account.setOpenDate(storedDateOf(request.openDate()));
        }
        if (isSupplied(request.expirationDate())) {
            account.setExpirationDate(storedDateOf(request.expirationDate()));
        }
        if (isSupplied(request.reissueDate())) {
            account.setReissueDate(storedDateOf(request.reissueDate()));
        }
        account.setCurrentCycleCredit(
                amountOf(request.currentCycleCredit(), PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
        account.setCurrentCycleDebit(
                amountOf(request.currentCycleDebit(), PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE));
        account.setGroupId(textAtDeclaredWidth(request.groupId(), PicClause.ACCT_GROUP_ID_WIDTH));
        return account;
    }

    /**
     * Builds the customer values a caller submits.
     *
     * <p>The three Social Security Number parts reach one nine-character field, in the order
     * {@code app/cbl/COACTUPC.cbl:L831} declares, and only when all three are supplied. The date of
     * birth arrives as eight characters and reaches the column as ten. Each text component reaches
     * the column at the width its Picture clause declares, as the moves at
     * {@code app/cbl/COACTUPC.cbl:L4010-L4059} and the rewrite of the 500-byte record at
     * {@code :L4086} leave it. A component the request leaves absent stays absent.
     *
     * @param request the customer section of the request, or {@code null} when the body carries none
     * @return the customer values, with a value this class cannot convert left absent
     */
    static CustomerEntity customerOf(CustomerDataRequest request) {

        CustomerEntity customer = new CustomerEntity();
        if (request == null) {
            return customer;
        }

        if (isSupplied(request.customerId())) {
            customer.setCustomerId(identifierAtWidth(request.customerId(),
                    PicClause.CUST_ID_WIDTH));
        }
        customer.setFirstName(textAtDeclaredWidth(request.firstName(),
                PicClause.CUST_FIRST_NAME_WIDTH));
        customer.setMiddleName(textAtDeclaredWidth(request.middleName(),
                PicClause.CUST_MIDDLE_NAME_WIDTH));
        customer.setLastName(textAtDeclaredWidth(request.lastName(),
                PicClause.CUST_LAST_NAME_WIDTH));
        customer.setAddressLine1(textAtDeclaredWidth(request.addressLine1(),
                PicClause.CUST_ADDR_LINE_1_WIDTH));
        customer.setAddressLine2(textAtDeclaredWidth(request.addressLine2(),
                PicClause.CUST_ADDR_LINE_2_WIDTH));
        customer.setAddressCity(textAtDeclaredWidth(request.addressCity(),
                PicClause.CUST_ADDR_LINE_3_WIDTH));
        customer.setAddressStateCode(textAtDeclaredWidth(request.addressStateCode(),
                PicClause.CUST_ADDR_STATE_CD_WIDTH));
        customer.setAddressCountryCode(textAtDeclaredWidth(request.addressCountryCode(),
                PicClause.CUST_ADDR_COUNTRY_CD_WIDTH));
        customer.setAddressZip(textAtDeclaredWidth(request.addressZip(),
                PicClause.CUST_ADDR_ZIP_WIDTH));
        customer.setPhoneNumber1(textAtDeclaredWidth(request.phoneNumber1(),
                PicClause.CUST_PHONE_NUM_1_WIDTH));
        customer.setPhoneNumber2(textAtDeclaredWidth(request.phoneNumber2(),
                PicClause.CUST_PHONE_NUM_2_WIDTH));
        if (isSupplied(request.socialSecurityPart1()) && isSupplied(request.socialSecurityPart2())
                && isSupplied(request.socialSecurityPart3())) {
            customer.setSocialSecurityNumber(socialSecurityNumberOf(request));
        }
        customer.setGovernmentIssuedId(textAtDeclaredWidth(request.governmentIssuedId(),
                PicClause.CUST_GOVT_ISSUED_ID_WIDTH));
        if (isSupplied(request.dateOfBirth())) {
            customer.setDateOfBirth(storedDateOf(request.dateOfBirth()));
        }
        customer.setEftAccountId(textAtDeclaredWidth(request.eftAccountId(),
                PicClause.CUST_EFT_ACCOUNT_ID_WIDTH));
        customer.setPrimaryCardHolderIndicator(textAtDeclaredWidth(
                request.primaryCardHolderIndicator(),
                PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH));
        customer.setFicoCreditScore(creditScoreOf(request.ficoCreditScore()));
        return customer;
    }

    /**
     * Builds the eleven values {@code 1200-SETUP-SCREEN-VARS} at
     * {@code app/cbl/COACTVWC.cbl:L460-L537} moves to the screen.
     *
     * <p>{@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy:L15} reaches no component. The
     * omission is recorded in {@code card-platform/docs/traceability-matrix.md}.
     *
     * @param account one stored account row
     * @return the eleven values a read returns
     */
    static AccountView viewOf(AccountEntity account) {
        return viewOf(AccountSnapshot.of(account));
    }

    /**
     * Builds the same eleven values from a snapshot an update already read off its row.
     *
     * <p>This is the one place the eleven values become a response, and {@link #viewOf(AccountEntity)}
     * reaches it through {@link AccountSnapshot#of}. A read and an update therefore describe an
     * account through the same code, and a field added to one is added to both.
     *
     * @param account the eleven values a transaction read off the row it wrote
     * @return the eleven values a read returns
     */
    static AccountView viewOf(AccountSnapshot account) {
        return new AccountView(account.accountId(), account.activeStatus(),
                account.currentBalance(), account.creditLimit(),
                account.cashCreditLimit(), account.currentCycleCredit(),
                account.currentCycleDebit(), account.openDate(),
                account.expirationDate(), account.reissueDate(), account.groupId());
    }

    /**
     * Builds the sixteen values a customer read returns.
     *
     * <p>{@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy:L17} and
     * {@code CUST-GOVT-ISSUED-ID} at {@code app/cpy/CVCUS01Y.cpy:L18} reach no component. Both
     * omissions are recorded in {@code card-platform/docs/traceability-matrix.md}.
     *
     * @param customer one stored customer row
     * @return the sixteen values a read returns
     */
    static CustomerView viewOf(CustomerEntity customer) {
        BigDecimal creditScore = customer.getFicoCreditScore();
        return new CustomerView(customer.getCustomerId(),
                creditScore == null ? null : Integer.valueOf(creditScore.intValue()),
                customer.getDateOfBirth(), customer.getFirstName(), customer.getMiddleName(),
                customer.getLastName(), customer.getAddressLine1(), customer.getAddressLine2(),
                customer.getAddressCity(), customer.getAddressStateCode(),
                customer.getAddressZip(), customer.getAddressCountryCode(),
                customer.getPhoneNumber1(), customer.getPhoneNumber2(),
                customer.getEftAccountId(), customer.getPrimaryCardHolderIndicator());
    }

    /**
     * Pads a numeric identifier with leading zeros to its declared width.
     *
     * <p>{@code MOVE WS-CARD-RID-ACCT-ID-X TO WS-CARD-RID-ACCT-ID} at
     * {@code app/cbl/COACTVWC.cbl:L691} lands in {@code PIC 9(11)}, which right-justifies the
     * digits over zeros.
     *
     * @param value         the identifier a caller supplied, or {@code null}
     * @param declaredWidth the width the Picture clause declares
     * @return the identifier at that width, and an empty value padded to it
     */
    static String identifierAtWidth(String value, int declaredWidth) {
        String present = value == null ? "" : value;
        if (present.length() >= declaredWidth) {
            return present;
        }
        return LEADING_ZERO.repeat(declaredWidth - present.length()) + present;
    }

    /**
     * Reports whether one monetary text converts.
     *
     * @param fieldLabel the label the message opens with
     * @param text       the value a caller supplied
     * @return a passing verdict when the value is absent or converts, otherwise the verdict
     *         {@code 1250-EDIT-SIGNED-9V2} at {@code app/cbl/COACTUPC.cbl:L2180-L2217} writes
     */
    private static EditResult amountConverts(String fieldLabel, String text) {
        if (!isSupplied(text) || NumvalParser.isValidNumvalCurrency(text)) {
            return EditResult.ok();
        }
        return SignedDecimalValidator.validate(fieldLabel, text);
    }

    /**
     * Reports whether the credit score text converts.
     *
     * @param text the value a caller supplied
     * @return a passing verdict when the value is absent or converts, otherwise the verdict
     *         {@code 1245-EDIT-NUM-REQD} at {@code app/cbl/COACTUPC.cbl:L2111} writes
     */
    private static EditResult creditScoreConverts(String text) {
        if (!isSupplied(text) || NumvalParser.isValidNumval(text)) {
            return EditResult.ok();
        }
        return NumericRequiredValidator.validate(CustomerDataRequest.FICO_CREDIT_SCORE_LABEL, text,
                PicClause.CUST_FICO_CREDIT_SCORE_WIDTH);
    }

    /**
     * Converts one monetary text into the value its column holds.
     *
     * <p>Two narrowings apply, and both are the store semantics of a COBOL {@code MOVE} into the
     * field. The scale drops the digits past the two {@code PIC S9(10)V99} keeps after the point.
     * The precision drops the high-order digits that do not fit its ten integer digits, which is
     * what {@link CobolDecimal#truncateToPictureField(java.math.BigDecimal, int, int)} reproduces
     * and what {@code app/cbl/COBIL00C.cbl:L224} does carrying {@code ACCT-CURR-BAL} into the
     * narrower {@code TRAN-AMT}.</p>
     *
     * <p>The precision narrowing is what this method exists for.
     * {@code AccountDataRequest.MONEY_MAX_LENGTH} admits fifteen characters, the width
     * {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L55} receives,
     * and the currency-tolerant grammar accepts eleven integer digits inside that width. Every
     * money column is {@code NUMERIC(12,2)}, so without this narrowing such a value reached the
     * database, raised SQLSTATE 22003 and answered 500. A database overflow is not validation:
     * the source has no {@code ON SIZE ERROR} phrase in any of the twenty-eight programs under
     * {@code app/cbl/}, so it stores the digits that fit and keeps the sign.</p>
     *
     * @param text      the value a caller supplied
     * @param precision total digits the Picture clause declares
     * @param scale     fractional digits the Picture clause declares
     * @return the value as the column holds it, and {@code null} when the text is absent or does
     *         not convert
     */
    private static BigDecimal amountOf(String text, int precision, int scale) {
        if (!isSupplied(text) || !NumvalParser.isValidNumvalCurrency(text)) {
            return null;
        }
        BigDecimal converted = NumvalParser.numvalCurrency(text);
        BigDecimal stored = CobolDecimal.truncateToPictureField(converted, precision, scale);
        if (stored.compareTo(converted) != 0) {
            LOG.warn("A submitted account figure needed more than the {} integer digits the account"
                            + " record holds at app/cpy/CVACT01Y.cpy:L7-L9 and :L13-L14, so the"
                            + " high-order digits were dropped as a COBOL MOVE into the field drops"
                            + " them. The figure is withheld. See docs/business-rule-flags.md.",
                    precision - scale);
        }
        return stored;
    }

    /**
     * Converts the credit score text into the value its column holds.
     *
     * @param text the value a caller supplied
     * @return the whole number the text carries, and {@code null} when the text is absent or does
     *         not convert
     */
    private static BigDecimal creditScoreOf(String text) {
        if (!isSupplied(text) || !NumvalParser.isValidNumval(text)) {
            return null;
        }
        return CobolDecimal.truncateToScale(NumvalParser.numval(text), INTEGRAL_SCALE);
    }

    /**
     * Converts an eight-character request date into the ten characters its column holds.
     *
     * <p>{@code ACCT-UPDATE-RECORD} at {@code app/cbl/COACTUPC.cbl:L418} declares its three dates
     * as {@code PIC X(10)} at {@code app/cbl/COACTUPC.cbl:L427-L429}, and
     * {@code app/cbl/COACTUPC.cbl:L4127-L4137} slices a stored date as {@code (1:4)},
     * {@code (6:2)} and {@code (9:2)}.
     *
     * @param requestDate the eight characters a caller supplied, or {@code null}
     * @return the ten characters the column holds
     */
    private static String storedDateOf(String requestDate) {
        String field = atDeclaredWidth(requestDate, REQUEST_DATE_WIDTH);
        return field.substring(0, YEAR_END_INDEX) + DATE_SEPARATOR
                + field.substring(YEAR_END_INDEX, MONTH_END_INDEX) + DATE_SEPARATOR
                + field.substring(MONTH_END_INDEX, DAY_END_INDEX);
    }

    /**
     * Joins the three Social Security Number parts into the nine characters its column holds.
     *
     * @param request the customer section of the request
     * @return the nine characters the column holds
     */
    private static String socialSecurityNumberOf(CustomerDataRequest request) {
        return atDeclaredWidth(request.socialSecurityPart1(),
                        CustomerDataRequest.SOCIAL_SECURITY_PART_1_MAX_LENGTH)
                + atDeclaredWidth(request.socialSecurityPart2(),
                        CustomerDataRequest.SOCIAL_SECURITY_PART_2_MAX_LENGTH)
                + atDeclaredWidth(request.socialSecurityPart3(),
                        CustomerDataRequest.SOCIAL_SECURITY_PART_3_MAX_LENGTH);
    }

    /**
     * Brings a supplied text value to its declared width, and leaves an absent one absent.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L4010-L4059} moves each screen field into the fixed-width field
     * of {@code CUSTOMER-RECORD} and rewrites the 500-byte record at {@code :L4086}, so a stored
     * value always occupies its whole field. The account record at {@code :L3962-L4002} and
     * {@code :L4066} works the same way. A value that is {@code null}, empty, all spaces or
     * {@code LOW-VALUES} is returned unchanged, because {@code api/AccountController} reads absence
     * to mean the stored value stands.
     *
     * @param value         the value a caller supplied, or {@code null}
     * @param declaredWidth the width the Picture clause declares
     * @return the supplied value at that width, a longer value unchanged, and an absent value as it
     *         arrived
     */
    private static String textAtDeclaredWidth(String value, int declaredWidth) {
        if (!isSupplied(value)) {
            return value;
        }
        return atDeclaredWidth(value, declaredWidth);
    }

    /**
     * Pads a text value with trailing spaces to its declared width.
     *
     * @param value         the value a caller supplied, or {@code null}
     * @param declaredWidth the width the Picture clause declares
     * @return the value at that width, and a longer value unchanged
     */
    private static String atDeclaredWidth(String value, int declaredWidth) {
        String present = value == null ? "" : value;
        if (present.length() >= declaredWidth) {
            return present;
        }
        return present + PADDING.repeat(declaredWidth - present.length());
    }

    /**
     * Reports whether a caller supplied a value.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L2184-L2185} tests {@code LOW-VALUES} and {@code SPACES}
     * together, and both count as absent.
     *
     * @param text the value a caller supplied, or {@code null}
     * @return {@code true} when the value carries a character other than a space or a null
     */
    static boolean isSupplied(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != ' ' && character != NULL_CHARACTER) {
                return true;
            }
        }
        return false;
    }
}
