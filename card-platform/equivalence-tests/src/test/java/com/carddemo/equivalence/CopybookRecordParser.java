package com.carddemo.equivalence;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Reads fixed-width CardDemo fixture records into typed values.
 *
 * <p>Every record layout has one nested record type and one parse method. Field offsets are running
 * sums over the byte widths {@link PicClause} publishes, taken in copybook declaration order. Each
 * nested type names its copybook and line range.</p>
 *
 * <p>Each parse method checks the whole record width before it reads a field. Ten layouts accept
 * one width each, the length their copybook declares. The card cross-reference accepts two, the
 * {@link PicClause#CARDXREF_FIXTURE_RECORD_WIDTH} the text fixture delivers and the
 * {@link PicClause#CARD_XREF_RECORD_LENGTH} the dataset definition declares. Any other width
 * raises {@link IllegalArgumentException}.</p>
 *
 * <p>No rendering and no failure message here carries a field value. A nested type renders a
 * component as {@link #REDACTED} when that component identifies a card, an account, a customer or a
 * money amount. A failed read names the layout, the field, the width and the character
 * position.</p>
 *
 * <p>Signed numeric fields carry the sign as an overpunch on the trailing digit, which
 * {@code new BigDecimal(String)} rejects. The nine fixtures under {@code app/data/ASCII} hold 651
 * overpunched positions, and all twenty overpunch characters appear in the amount column of
 * {@code app/data/ASCII/dailytran.txt}.</p>
 *
 * <p>Three readings here are ADDITIVE, with no ancestor in the COBOL source: the overpunch decode
 * at {@link #signedDecimal}, tolerance of a short record, and the hundredths truncation at
 * {@link #truncateProcessingTimestampToHundredths}. All three are recorded in
 * {@code card-platform/docs/decision-log.md} (planned).</p>
 *
 * <p>Six nested types override {@link Object#toString()}. No rendered record carries a full
 * Primary Account Number (PAN), a card verification value, a social security number, a
 * government-issued identifier or an electronic funds transfer account identifier.
 * {@link CardRecord}, {@link CardCrossReferenceRecord}, {@link PostedTransactionRecord} and
 * {@link DailyTransactionRecord} publish a masked card number. {@link CardRecord} redacts its
 * verification value, {@link CustomerRecord} redacts its three identifiers, and
 * {@link RejectedTransactionRecord} redacts the whole 350-byte transaction blob, which carries a
 * card number at its offset 15. Each redaction preserves the width of the value it replaces, so a
 * failure still reports the width a reader needs to find an offset drift.</p>
 *
 * <p>Every failure this class reports names the field, the position inside the field and the width
 * involved. None quotes the text of the field, so a failing parse discloses no fixture value.</p>
 */
public final class CopybookRecordParser {

    /**
     * Trailing characters that carry a positive sign, listed in digit order zero through nine. A
     * COBOL signed display numeric overpunches its sign onto the last digit of the field.
     */
    public static final String POSITIVE_SIGN_OVERPUNCH_DIGITS = "{ABCDEFGHI";

    /**
     * Trailing characters that carry a negative sign, listed in digit order zero through nine.
     * The list holds as many characters as {@link #POSITIVE_SIGN_OVERPUNCH_DIGITS}.
     */
    public static final String NEGATIVE_SIGN_OVERPUNCH_DIGITS = "}JKLMNOPQR";

    /** Character COBOL pads a {@code PIC X(n)} field with on the right. */
    private static final char COBOL_TEXT_PAD = ' ';

    /**
     * Text a diagnostic rendering carries in place of a component value. ADDITIVE, with no COBOL
     * ancestor, and recorded in {@code card-platform/docs/decision-log.md} (planned). Every record
     * below whose components identify a card, an account, a customer, or a money amount renders
     * that component as this text.
     */
    public static final String REDACTED = "<redacted>";

    /** Ordinal of the first character of a record, used when a read reports a position. */
    private static final int FIRST_POSITION = 1;

    private CopybookRecordParser() {
        throw new AssertionError(CopybookRecordParser.class.getName() + " holds static operations only");
    }

    // Record layouts. One nested type per copybook, in the order the copybooks are numbered.
    // Identifier and key fields keep their zero padding as text; the COBOL programs compare
    // several of them as text. Descriptive fields arrive with the space padding removed.

    /**
     * Fields of {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17}. The trailing
     * {@code FILLER} at {@code app/cpy/CVACT01Y.cpy:L17} is not modelled.
     *
     * @param accountId          {@code ACCT-ID}, {@code PIC 9(11)} at L5
     * @param activeStatus       {@code ACCT-ACTIVE-STATUS}, {@code PIC X(01)} at L6
     * @param currentBalance     {@code ACCT-CURR-BAL}, {@code PIC S9(10)V99} at L7
     * @param creditLimit        {@code ACCT-CREDIT-LIMIT}, {@code PIC S9(10)V99} at L8
     * @param cashCreditLimit    {@code ACCT-CASH-CREDIT-LIMIT}, {@code PIC S9(10)V99} at L9
     * @param openDate           {@code ACCT-OPEN-DATE}, {@code PIC X(10)} at L10
     * @param expirationDate     {@code ACCT-EXPIRAION-DATE}, {@code PIC X(10)} at L11, spelled
     *                           that way in the copybook
     * @param reissueDate        {@code ACCT-REISSUE-DATE}, {@code PIC X(10)} at L12
     * @param currentCycleCredit {@code ACCT-CURR-CYC-CREDIT}, {@code PIC S9(10)V99} at L13
     * @param currentCycleDebit  {@code ACCT-CURR-CYC-DEBIT}, {@code PIC S9(10)V99} at L14
     * @param addressZip         {@code ACCT-ADDR-ZIP}, {@code PIC X(10)} at L15
     * @param groupId            {@code ACCT-GROUP-ID}, {@code PIC X(10)} at L16
     */
    public static record AccountRecord(
            String accountId,
            String activeStatus,
            BigDecimal currentBalance,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            String openDate,
            String expirationDate,
            String reissueDate,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit,
            String addressZip,
            String groupId) {

        /**
         * Renders the type alone, and no field value.
         *
         * <p>Every field of an account record is protected: the account identifier, five money fields,
         * three dates, the address zip and the group identifier. A generated record rendering carries
         * every field, and a rendering reaches an
         * assertion message, a log line or a debugger view without a caller intending it. A test
         * that needs a field reads it through its accessor.</p>
         *
         * @return a fixed description carrying no field value
         */
        @Override
        public String toString() {
            return "AccountRecord[all fields redacted]";
        }
    }

    /**
     * Fields of {@code CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:L4-L11}. The trailing
     * {@code FILLER} at {@code app/cpy/CVACT02Y.cpy:L11} is not modelled.
     *
     * @param cardNumber            {@code CARD-NUM}, {@code PIC X(16)} at L5, the full Primary
     *                              Account Number (PAN)
     * @param accountId             {@code CARD-ACCT-ID}, {@code PIC 9(11)} at L6
     * @param cardVerificationValue {@code CARD-CVV-CD}, {@code PIC 9(03)} at L7, which reaches no
     *                              event, no log, and no response body
     * @param embossedName          {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at L8
     * @param expirationDate        {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)} at L9, spelled
     *                              that way in the copybook
     * @param activeStatus          {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at L10
     */
    public static record CardRecord(
            String cardNumber,
            String accountId,
            String cardVerificationValue,
            String embossedName,
            String expirationDate,
            String activeStatus) {

        /**
         * Renders this record with its card number masked and its cardholder values redacted.
         *
         * <p>The embossed name is the cardholder's own name and the expiry date is one of the two
         * values a card-not-present authorization asks for alongside the number, so neither
         * belongs in a log line beside a masked number that already carries four real digits.
         *
         * @return the six components, the card number as twelve mask characters and its last four
         *         digits, and the verification value, embossed name and expiry date each as mask
         *         characters at their own width
         */
        @Override
        public String toString() {
            return "CardRecord[cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", accountId=" + accountId
                    + ", cardVerificationValue=" + redacted(cardVerificationValue)
                    + ", embossedName=" + redacted(embossedName)
                    + ", expirationDate=" + redacted(expirationDate)
                    + ", activeStatus=" + activeStatus + "]";
        }
    }

    /**
     * Fields of {@code CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4-L8}. The trailing
     * {@code FILLER} at {@code app/cpy/CVACT03Y.cpy:L8} is not modelled.
     *
     * @param cardNumber {@code XREF-CARD-NUM}, {@code PIC X(16)} at L5, the key of the
     *                   cross-reference dataset per {@code app/jcl/XREFFILE.jcl:L43}
     * @param customerId {@code XREF-CUST-ID}, {@code PIC 9(09)} at L6
     * @param accountId  {@code XREF-ACCT-ID}, {@code PIC 9(11)} at L7, the last field the text
     *                   fixture delivers
     */
    public static record CardCrossReferenceRecord(
            String cardNumber,
            String customerId,
            String accountId) {

        /**
         * Renders this record with its card number masked.
         *
         * @return the three components, the card number as twelve mask characters and its last
         *         four digits
         */
        @Override
        public String toString() {
            return "CardCrossReferenceRecord[cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", customerId=" + customerId
                    + ", accountId=" + accountId + "]";
        }
    }

    /**
     * Fields of {@code CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy:L4-L23}. The trailing
     * {@code FILLER} at {@code app/cpy/CVCUS01Y.cpy:L23} is not modelled.
     *
     * @param customerId                 {@code CUST-ID}, {@code PIC 9(09)} at L5
     * @param firstName                  {@code CUST-FIRST-NAME}, {@code PIC X(25)} at L6
     * @param middleName                 {@code CUST-MIDDLE-NAME}, {@code PIC X(25)} at L7
     * @param lastName                   {@code CUST-LAST-NAME}, {@code PIC X(25)} at L8
     * @param addressLine1               {@code CUST-ADDR-LINE-1}, {@code PIC X(50)} at L9
     * @param addressLine2               {@code CUST-ADDR-LINE-2}, {@code PIC X(50)} at L10
     * @param addressLine3               {@code CUST-ADDR-LINE-3}, {@code PIC X(50)} at L11
     * @param stateCode                  {@code CUST-ADDR-STATE-CD}, {@code PIC X(02)} at L12
     * @param countryCode                {@code CUST-ADDR-COUNTRY-CD}, {@code PIC X(03)} at L13
     * @param addressZip                 {@code CUST-ADDR-ZIP}, {@code PIC X(10)} at L14
     * @param phoneNumber1               {@code CUST-PHONE-NUM-1}, {@code PIC X(15)} at L15
     * @param phoneNumber2               {@code CUST-PHONE-NUM-2}, {@code PIC X(15)} at L16
     * @param socialSecurityNumber       {@code CUST-SSN}, {@code PIC 9(09)} at L17
     * @param governmentIssuedId         {@code CUST-GOVT-ISSUED-ID}, {@code PIC X(20)} at L18
     * @param dateOfBirth                {@code CUST-DOB-YYYY-MM-DD}, {@code PIC X(10)} at L19
     * @param eftAccountId               {@code CUST-EFT-ACCOUNT-ID}, {@code PIC X(10)} at L20
     * @param primaryCardHolderIndicator {@code CUST-PRI-CARD-HOLDER-IND}, {@code PIC X(01)} at L21
     * @param ficoCreditScore            {@code CUST-FICO-CREDIT-SCORE}, {@code PIC 9(03)} at L22
     */
    public static record CustomerRecord(
            String customerId,
            String firstName,
            String middleName,
            String lastName,
            String addressLine1,
            String addressLine2,
            String addressLine3,
            String stateCode,
            String countryCode,
            String addressZip,
            String phoneNumber1,
            String phoneNumber2,
            String socialSecurityNumber,
            String governmentIssuedId,
            String dateOfBirth,
            String eftAccountId,
            String primaryCardHolderIndicator,
            int ficoCreditScore) {

        /**
         * Renders this record with every identifying component redacted.
         *
         * <p>Masking only the three obvious identifiers left a name, a home address, a postal
         * code, two telephone numbers, a date of birth and a credit score in plain view, and
         * those together identify a person as surely as a Social Security Number does. Each is
         * replaced by mask characters at its own width, which for a fixed width copybook field is
         * a constant the layout already declares and therefore discloses nothing.
         *
         * <p>{@code CUST-ID}, the state and country codes and the cardholder indicator are kept,
         * because a failing equivalence assertion has to name which record diverged and none of
         * the four narrows a record to a person.
         *
         * @return the eighteen components, with every identifying value replaced by mask
         *         characters at its own width
         */
        @Override
        public String toString() {
            return "CustomerRecord[customerId=" + customerId
                    + ", firstName=" + redacted(firstName)
                    + ", middleName=" + redacted(middleName)
                    + ", lastName=" + redacted(lastName)
                    + ", addressLine1=" + redacted(addressLine1)
                    + ", addressLine2=" + redacted(addressLine2)
                    + ", addressLine3=" + redacted(addressLine3)
                    + ", stateCode=" + stateCode
                    + ", countryCode=" + countryCode
                    + ", addressZip=" + redacted(addressZip)
                    + ", phoneNumber1=" + redacted(phoneNumber1)
                    + ", phoneNumber2=" + redacted(phoneNumber2)
                    + ", socialSecurityNumber=" + redacted(socialSecurityNumber)
                    + ", governmentIssuedId=" + redacted(governmentIssuedId)
                    + ", dateOfBirth=" + redacted(dateOfBirth)
                    + ", eftAccountId=" + redacted(eftAccountId)
                    + ", primaryCardHolderIndicator=" + primaryCardHolderIndicator
                    + ", ficoCreditScore=" + redacted(String.valueOf(ficoCreditScore)) + "]";
        }
    }

    /**
     * Fields of {@code TRAN-CAT-BAL-RECORD} at {@code app/cpy/CVTRA01Y.cpy:L4-L10}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA01Y.cpy:L10} is not modelled.
     *
     * <p>The first three components are the three parts of the seventeen-byte
     * {@code TRAN-CAT-KEY} group at {@code app/cpy/CVTRA01Y.cpy:L5-L8}, a width
     * {@code app/jcl/TCATBALF.jcl:L40} repeats as {@code KEYS(17 0)}. A group of the same name at
     * {@code app/cpy/CVTRA04Y.cpy:L5} spans six bytes and two parts, and
     * {@link TransactionCategoryRecord} carries that one.</p>
     *
     * @param accountId    {@code TRANCAT-ACCT-ID}, {@code PIC 9(11)} at L6
     * @param typeCode     {@code TRANCAT-TYPE-CD}, {@code PIC X(02)} at L7
     * @param categoryCode {@code TRANCAT-CD}, {@code PIC 9(04)} at L8, named without a
     *                     {@code -CAT-} segment
     * @param balance      {@code TRAN-CAT-BAL}, {@code PIC S9(09)V99} at L9
     */
    public static record TransactionCategoryBalanceRecord(
            String accountId,
            String typeCode,
            String categoryCode,
            BigDecimal balance) {

        /**
         * Renders the two reference codes, and neither the account identifier nor the balance.
         *
         * <p>The type code and the category code are reference values shared by every account.
         * The account identifier and the balance are protected and are omitted here.</p>
         *
         * @return a description carrying the two reference codes alone
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceRecord[typeCode=" + typeCode
                    + ", categoryCode=" + categoryCode
                    + ", accountId and balance redacted]";
        }
    }

    /**
     * Fields of {@code DIS-GROUP-RECORD} at {@code app/cpy/CVTRA02Y.cpy:L4-L10}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA02Y.cpy:L10} is not modelled.
     *
     * <p>The first three components are the three parts of the sixteen-byte
     * {@code DIS-GROUP-KEY} group at {@code app/cpy/CVTRA02Y.cpy:L5-L8}, a width
     * {@code app/jcl/DISCGRP.jcl:L40} repeats as {@code KEYS(16 0)}.</p>
     *
     * @param accountGroupId           {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)} at L6, which
     *                                 keeps its space padding across all ten bytes
     * @param transactionTypeCode      {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)} at L7
     * @param transactionCategoryCode  {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)} at L8
     * @param interestRate             {@code DIS-INT-RATE}, {@code PIC S9(04)V99} at L9
     */
    public static record DisclosureGroupRecord(
            String accountGroupId,
            String transactionTypeCode,
            String transactionCategoryCode,
            BigDecimal interestRate) { }

    /**
     * Fields of {@code TRAN-TYPE-RECORD} at {@code app/cpy/CVTRA03Y.cpy:L4-L7}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA03Y.cpy:L7} is not modelled.
     *
     * @param transactionType {@code TRAN-TYPE}, {@code PIC X(02)} at L5, which the copybook names
     *                        without a {@code -CD} suffix
     * @param description     {@code TRAN-TYPE-DESC}, {@code PIC X(50)} at L6
     */
    public static record TransactionTypeRecord(
            String transactionType,
            String description) { }

    /**
     * Fields of {@code TRAN-CAT-RECORD} at {@code app/cpy/CVTRA04Y.cpy:L4-L9}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA04Y.cpy:L9} is not modelled.
     *
     * <p>The first two components are the two parts of the six-byte {@code TRAN-CAT-KEY} group at
     * {@code app/cpy/CVTRA04Y.cpy:L5-L7}, a width {@code app/jcl/TRANCATG.jcl:L40} repeats as
     * {@code KEYS(6 0)}. A group of the same name at {@code app/cpy/CVTRA01Y.cpy:L5} spans
     * seventeen bytes and three parts, and {@link TransactionCategoryBalanceRecord} carries that
     * one.</p>
     *
     * @param transactionTypeCode     {@code TRAN-TYPE-CD}, {@code PIC X(02)} at L6
     * @param transactionCategoryCode {@code TRAN-CAT-CD}, {@code PIC 9(04)} at L7
     * @param description             {@code TRAN-CAT-TYPE-DESC}, {@code PIC X(50)} at L8
     */
    public static record TransactionCategoryRecord(
            String transactionTypeCode,
            String transactionCategoryCode,
            String description) { }

    /**
     * Fields of {@code TRAN-RECORD} at {@code app/cpy/CVTRA05Y.cpy:L4-L18}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA05Y.cpy:L18} is not modelled.
     *
     * <p>Both timestamps stay twenty-six-character strings. The two layouts differ: a space and
     * colons separate the parts of {@code originTimestamp}, while
     * {@link PicClause#PROCESSING_TIMESTAMP_SHAPE} gives {@code processingTimestamp} a dash and
     * dots.</p>
     *
     * @param transactionId       {@code TRAN-ID}, {@code PIC X(16)} at L5
     * @param typeCode            {@code TRAN-TYPE-CD}, {@code PIC X(02)} at L6
     * @param categoryCode        {@code TRAN-CAT-CD}, {@code PIC 9(04)} at L7
     * @param source              {@code TRAN-SOURCE}, {@code PIC X(10)} at L8
     * @param description         {@code TRAN-DESC}, {@code PIC X(100)} at L9
     * @param amount              {@code TRAN-AMT}, {@code PIC S9(09)V99} at L10
     * @param merchantId          {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)} at L11
     * @param merchantName        {@code TRAN-MERCHANT-NAME}, {@code PIC X(50)} at L12
     * @param merchantCity        {@code TRAN-MERCHANT-CITY}, {@code PIC X(50)} at L13
     * @param merchantZip         {@code TRAN-MERCHANT-ZIP}, {@code PIC X(10)} at L14
     * @param cardNumber          {@code TRAN-CARD-NUM}, {@code PIC X(16)} at L15
     * @param originTimestamp     {@code TRAN-ORIG-TS}, {@code PIC X(26)} at L16
     * @param processingTimestamp {@code TRAN-PROC-TS}, {@code PIC X(26)} at L17, which
     *                            {@code app/cbl/CBTRN02C.cbl:L438} fills from {@code DB2-FORMAT-TS}
     */
    public static record PostedTransactionRecord(
            String transactionId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originTimestamp,
            String processingTimestamp) {
        /**
         * Renders this record with its card number masked and its spending detail redacted.
         *
         * <p>A masked number plus an amount, a description and a merchant name and city is a
         * statement line, and a statement line placed beside the same cardholder's other lines
         * reconstructs where a person was and what they bought. The merchant identifier is kept
         * because it is a number the merchant table resolves and a diverging record has to be
         * identifiable; the merchant name, city and postal code are not.
         *
         * @return the thirteen components, the card number as twelve mask characters and its last
         *         four digits, and the amount, description and merchant name, city and postal code
         *         each as mask characters at their own width
         */
        @Override
        public String toString() {
            return "PostedTransactionRecord[transactionId=" + transactionId
                    + ", typeCode=" + typeCode
                    + ", categoryCode=" + categoryCode
                    + ", source=" + source
                    + ", description=" + redacted(description)
                    + ", amount=" + redacted(String.valueOf(amount))
                    + ", merchantId=" + merchantId
                    + ", merchantName=" + redacted(merchantName)
                    + ", merchantCity=" + redacted(merchantCity)
                    + ", merchantZip=" + redacted(merchantZip)
                    + ", cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", originTimestamp=" + originTimestamp
                    + ", processingTimestamp=" + processingTimestamp + "]";
        }
    }

    /**
     * Fields of {@code DALYTRAN-RECORD} at {@code app/cpy/CVTRA06Y.cpy:L4-L18}. The trailing
     * {@code FILLER} at {@code app/cpy/CVTRA06Y.cpy:L18} is not modelled.
     *
     * <p>The layout matches {@link PostedTransactionRecord} byte for byte at the same copybook
     * lines, and every field name carries the {@code DALYTRAN-} prefix.
     * {@code app/cbl/CBTRN02C.cbl:L425-L436} copies twelve of these fields across one at a
     * time.</p>
     *
     * @param transactionId       {@code DALYTRAN-ID}, {@code PIC X(16)} at L5
     * @param typeCode            {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)} at L6
     * @param categoryCode        {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)} at L7
     * @param source              {@code DALYTRAN-SOURCE}, {@code PIC X(10)} at L8
     * @param description         {@code DALYTRAN-DESC}, {@code PIC X(100)} at L9
     * @param amount              {@code DALYTRAN-AMT}, {@code PIC S9(09)V99} at L10, the field
     *                            that carries all twenty overpunch characters across the fixture
     * @param merchantId          {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)} at L11
     * @param merchantName        {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)} at L12
     * @param merchantCity        {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)} at L13
     * @param merchantZip         {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)} at L14
     * @param cardNumber          {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)} at L15
     * @param originTimestamp     {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)} at L16, the field
     *                            {@code app/cbl/CBTRN02C.cbl:L414} reads the first ten characters of
     * @param processingTimestamp {@code DALYTRAN-PROC-TS}, {@code PIC X(26)} at L17, blank on all
     *                            three hundred fixture records
     */
    public static record DailyTransactionRecord(
            String transactionId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originTimestamp,
            String processingTimestamp) {
        /**
         * Renders this record with its card number masked and its spending detail redacted.
         *
         * <p>The layout is the posted layout byte for byte, so the reasoning at
         * {@link PostedTransactionRecord#toString()} applies here without change.
         *
         * @return the thirteen components, the card number as twelve mask characters and its last
         *         four digits, and the amount, description and merchant name, city and postal code
         *         each as mask characters at their own width
         */
        @Override
        public String toString() {
            return "DailyTransactionRecord[transactionId=" + transactionId
                    + ", typeCode=" + typeCode
                    + ", categoryCode=" + categoryCode
                    + ", source=" + source
                    + ", description=" + redacted(description)
                    + ", amount=" + redacted(String.valueOf(amount))
                    + ", merchantId=" + merchantId
                    + ", merchantName=" + redacted(merchantName)
                    + ", merchantCity=" + redacted(merchantCity)
                    + ", merchantZip=" + redacted(merchantZip)
                    + ", cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", originTimestamp=" + originTimestamp
                    + ", processingTimestamp=" + processingTimestamp + "]";
        }
    }

    /**
     * Fields of {@code REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L176-L178} joined to
     * {@code WS-VALIDATION-TRAILER} at {@code app/cbl/CBTRN02C.cbl:L180-L182}.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:L36} allocates the reject dataset at the same length, as
     * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}.</p>
     *
     * @param transactionData       {@code REJECT-TRAN-DATA}, {@code PIC X(350)} at
     *                              {@code app/cbl/CBTRN02C.cbl:L177}, holding the whole daily
     *                              transaction record unchanged for {@link #parseDailyTransaction}
     * @param failReason            {@code WS-VALIDATION-FAIL-REASON}, {@code PIC 9(04)} at
     *                              {@code app/cbl/CBTRN02C.cbl:L181}
     * @param failReasonDescription {@code WS-VALIDATION-FAIL-REASON-DESC}, {@code PIC X(76)} at
     *                              {@code app/cbl/CBTRN02C.cbl:L182}
     */
    public static record RejectedTransactionRecord(
            String transactionData,
            int failReason,
            String failReasonDescription) {

        /**
         * Renders this record with its transaction blob redacted. The blob holds the whole daily
         * transaction record, whose card number sits at offset 15, so the blob is replaced by mask
         * characters at its own width.
         *
         * @return the three components, the transaction blob as mask characters
         */
        @Override
        public String toString() {
            return "RejectedTransactionRecord[transactionData=" + redacted(transactionData)
                    + ", failReason=" + failReason
                    + ", failReasonDescription=" + failReasonDescription + "]";
        }
    }

    // Parse operations. Each one walks its layout once through a Cursor, listing fields in
    // copybook declaration order, so every field offset is a running sum of the widths above it.

    /**
     * Parses one {@code ACCOUNT-RECORD}, the layout of {@code app/data/ASCII/acctdata.txt}.
     *
     * @param record one fixture record
     * @return the twelve modelled fields of {@code app/cpy/CVACT01Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when a field holds a character its Picture clause forbids
     */
    public static AccountRecord parseAccount(String record) {
        requireDeclaredWidth(record, "CVACT01Y ACCOUNT-RECORD", PicClause.ACCOUNT_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVACT01Y ACCOUNT-RECORD");
        return new AccountRecord(
                cursor.fixedText(PicClause.ACCT_ID_WIDTH, "ACCT-ID"),
                cursor.fixedText(PicClause.ACCT_ACTIVE_STATUS_WIDTH, "ACCT-ACTIVE-STATUS"),
                cursor.signedDecimal(PicClause.ACCT_CURR_BAL_WIDTH,
                        PicClause.ACCT_CURR_BAL_SCALE, "ACCT-CURR-BAL"),
                cursor.signedDecimal(PicClause.ACCT_CREDIT_LIMIT_WIDTH,
                        PicClause.ACCT_CREDIT_LIMIT_SCALE, "ACCT-CREDIT-LIMIT"),
                cursor.signedDecimal(PicClause.ACCT_CASH_CREDIT_LIMIT_WIDTH,
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE, "ACCT-CASH-CREDIT-LIMIT"),
                cursor.fixedText(PicClause.ACCT_OPEN_DATE_WIDTH, "ACCT-OPEN-DATE"),
                cursor.fixedText(PicClause.ACCT_EXPIRATION_DATE_WIDTH, "ACCT-EXPIRAION-DATE"),
                cursor.fixedText(PicClause.ACCT_REISSUE_DATE_WIDTH, "ACCT-REISSUE-DATE"),
                cursor.signedDecimal(PicClause.ACCT_CURR_CYC_CREDIT_WIDTH,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE, "ACCT-CURR-CYC-CREDIT"),
                cursor.signedDecimal(PicClause.ACCT_CURR_CYC_DEBIT_WIDTH,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE, "ACCT-CURR-CYC-DEBIT"),
                cursor.text(PicClause.ACCT_ADDR_ZIP_WIDTH, "ACCT-ADDR-ZIP"),
                cursor.fixedText(PicClause.ACCT_GROUP_ID_WIDTH, "ACCT-GROUP-ID"));
    }

    /**
     * Parses one {@code CARD-RECORD}, the layout of {@code app/data/ASCII/carddata.txt}.
     *
     * @param record one fixture record
     * @return the six modelled fields of {@code app/cpy/CVACT02Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares
     */
    public static CardRecord parseCard(String record) {
        requireDeclaredWidth(record, "CVACT02Y CARD-RECORD", PicClause.CARD_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVACT02Y CARD-RECORD");
        return new CardRecord(
                cursor.fixedText(PicClause.CARD_NUM_WIDTH, "CARD-NUM"),
                cursor.fixedText(PicClause.CARD_ACCT_ID_WIDTH, "CARD-ACCT-ID"),
                cursor.fixedText(PicClause.CARD_CVV_CD_WIDTH, "CARD-CVV-CD"),
                cursor.text(PicClause.CARD_EMBOSSED_NAME_WIDTH, "CARD-EMBOSSED-NAME"),
                cursor.fixedText(PicClause.CARD_EXPIRATION_DATE_WIDTH, "CARD-EXPIRAION-DATE"),
                cursor.fixedText(PicClause.CARD_ACTIVE_STATUS_WIDTH, "CARD-ACTIVE-STATUS"));
    }

    /**
     * Parses one {@code CARD-XREF-RECORD}, the layout of {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>{@code app/jcl/XREFFILE.jcl:L44} declares
     * {@link PicClause#CARD_XREF_RECORD_LENGTH} bytes and the text fixture delivers
     * {@link PicClause#CARDXREF_FIXTURE_RECORD_WIDTH}. {@code XREF-ACCT-ID} closes on the last
     * delivered byte, and this method reads no further.</p>
     *
     * @param record one fixture record, at either width
     * @return the three modelled fields of {@code app/cpy/CVACT03Y.cpy}
     * @throws IllegalArgumentException when {@code record} is neither of those two widths
     */
    public static CardCrossReferenceRecord parseCardCrossReference(String record) {
        requireEitherWidth(record, "CVACT03Y CARD-XREF-RECORD",
                PicClause.CARDXREF_FIXTURE_RECORD_WIDTH, PicClause.CARD_XREF_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVACT03Y CARD-XREF-RECORD");
        return new CardCrossReferenceRecord(
                cursor.fixedText(PicClause.XREF_CARD_NUM_WIDTH, "XREF-CARD-NUM"),
                cursor.fixedText(PicClause.XREF_CUST_ID_WIDTH, "XREF-CUST-ID"),
                cursor.fixedText(PicClause.XREF_ACCT_ID_WIDTH, "XREF-ACCT-ID"));
    }

    /**
     * Parses one {@code CUSTOMER-RECORD}, the layout of {@code app/data/ASCII/custdata.txt}.
     *
     * @param record one fixture record
     * @return the eighteen modelled fields of {@code app/cpy/CVCUS01Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code CUST-FICO-CREDIT-SCORE} holds a character other than a digit
     */
    public static CustomerRecord parseCustomer(String record) {
        requireDeclaredWidth(record, "CVCUS01Y CUSTOMER-RECORD", PicClause.CUSTOMER_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVCUS01Y CUSTOMER-RECORD");
        return new CustomerRecord(
                cursor.fixedText(PicClause.CUST_ID_WIDTH, "CUST-ID"),
                cursor.text(PicClause.CUST_FIRST_NAME_WIDTH, "CUST-FIRST-NAME"),
                cursor.text(PicClause.CUST_MIDDLE_NAME_WIDTH, "CUST-MIDDLE-NAME"),
                cursor.text(PicClause.CUST_LAST_NAME_WIDTH, "CUST-LAST-NAME"),
                cursor.text(PicClause.CUST_ADDR_LINE_1_WIDTH, "CUST-ADDR-LINE-1"),
                cursor.text(PicClause.CUST_ADDR_LINE_2_WIDTH, "CUST-ADDR-LINE-2"),
                cursor.text(PicClause.CUST_ADDR_LINE_3_WIDTH, "CUST-ADDR-LINE-3"),
                cursor.fixedText(PicClause.CUST_ADDR_STATE_CD_WIDTH, "CUST-ADDR-STATE-CD"),
                cursor.fixedText(PicClause.CUST_ADDR_COUNTRY_CD_WIDTH, "CUST-ADDR-COUNTRY-CD"),
                cursor.text(PicClause.CUST_ADDR_ZIP_WIDTH, "CUST-ADDR-ZIP"),
                cursor.text(PicClause.CUST_PHONE_NUM_1_WIDTH, "CUST-PHONE-NUM-1"),
                cursor.text(PicClause.CUST_PHONE_NUM_2_WIDTH, "CUST-PHONE-NUM-2"),
                cursor.fixedText(PicClause.CUST_SSN_WIDTH, "CUST-SSN"),
                cursor.text(PicClause.CUST_GOVT_ISSUED_ID_WIDTH, "CUST-GOVT-ISSUED-ID"),
                cursor.fixedText(PicClause.CUST_DOB_WIDTH, "CUST-DOB-YYYY-MM-DD"),
                cursor.text(PicClause.CUST_EFT_ACCOUNT_ID_WIDTH, "CUST-EFT-ACCOUNT-ID"),
                cursor.fixedText(PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH,
                        "CUST-PRI-CARD-HOLDER-IND"),
                cursor.unsignedInteger(PicClause.CUST_FICO_CREDIT_SCORE_WIDTH,
                        "CUST-FICO-CREDIT-SCORE"));
    }

    /**
     * Parses one {@code TRAN-CAT-BAL-RECORD}, the layout of {@code app/data/ASCII/tcatbal.txt}.
     *
     * @param record one fixture record
     * @return the four modelled fields of {@code app/cpy/CVTRA01Y.cpy}, the first three being the
     *         seventeen-byte composite key
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code TRAN-CAT-BAL} holds an unreadable sign overpunch
     */
    public static TransactionCategoryBalanceRecord parseTransactionCategoryBalance(String record) {
        requireDeclaredWidth(record, "CVTRA01Y TRAN-CAT-BAL-RECORD",
                PicClause.TRAN_CAT_BAL_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA01Y TRAN-CAT-BAL-RECORD");
        return new TransactionCategoryBalanceRecord(
                cursor.fixedText(PicClause.TRANCAT_ACCT_ID_WIDTH, "TRANCAT-ACCT-ID"),
                cursor.fixedText(PicClause.TRANCAT_TYPE_CD_WIDTH, "TRANCAT-TYPE-CD"),
                cursor.fixedText(PicClause.TRANCAT_CD_WIDTH, "TRANCAT-CD"),
                cursor.signedDecimal(PicClause.TRAN_CAT_BAL_WIDTH,
                        PicClause.TRAN_CAT_BAL_SCALE, "TRAN-CAT-BAL"));
    }

    /**
     * Parses one {@code DIS-GROUP-RECORD}, the layout of {@code app/data/ASCII/discgrp.txt}.
     *
     * @param record one fixture record
     * @return the four modelled fields of {@code app/cpy/CVTRA02Y.cpy}, the first three being the
     *         sixteen-byte composite key
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code DIS-INT-RATE} holds an unreadable sign overpunch
     */
    public static DisclosureGroupRecord parseDisclosureGroup(String record) {
        requireDeclaredWidth(record, "CVTRA02Y DIS-GROUP-RECORD",
                PicClause.DIS_GROUP_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA02Y DIS-GROUP-RECORD");
        return new DisclosureGroupRecord(
                cursor.fixedText(PicClause.DIS_ACCT_GROUP_ID_WIDTH, "DIS-ACCT-GROUP-ID"),
                cursor.fixedText(PicClause.DIS_TRAN_TYPE_CD_WIDTH, "DIS-TRAN-TYPE-CD"),
                cursor.fixedText(PicClause.DIS_TRAN_CAT_CD_WIDTH, "DIS-TRAN-CAT-CD"),
                cursor.signedDecimal(PicClause.DIS_INT_RATE_WIDTH,
                        PicClause.DIS_INT_RATE_SCALE, "DIS-INT-RATE"));
    }

    /**
     * Parses one {@code TRAN-TYPE-RECORD}, the layout of {@code app/data/ASCII/trantype.txt}.
     *
     * @param record one fixture record
     * @return the two modelled fields of {@code app/cpy/CVTRA03Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares
     */
    public static TransactionTypeRecord parseTransactionType(String record) {
        requireDeclaredWidth(record, "CVTRA03Y TRAN-TYPE-RECORD",
                PicClause.TRAN_TYPE_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA03Y TRAN-TYPE-RECORD");
        return new TransactionTypeRecord(
                cursor.fixedText(PicClause.TRAN_TYPE_WIDTH, "TRAN-TYPE"),
                cursor.text(PicClause.TRAN_TYPE_DESC_WIDTH, "TRAN-TYPE-DESC"));
    }

    /**
     * Parses one {@code TRAN-CAT-RECORD}, the layout of {@code app/data/ASCII/trancatg.txt}.
     *
     * @param record one fixture record
     * @return the three modelled fields of {@code app/cpy/CVTRA04Y.cpy}, the first two being the
     *         six-byte composite key
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares
     */
    public static TransactionCategoryRecord parseTransactionCategory(String record) {
        requireDeclaredWidth(record, "CVTRA04Y TRAN-CAT-RECORD", PicClause.TRAN_CAT_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA04Y TRAN-CAT-RECORD");
        return new TransactionCategoryRecord(
                cursor.fixedText(PicClause.TRAN_CAT_RECORD_TYPE_CD_WIDTH, "TRAN-TYPE-CD"),
                cursor.fixedText(PicClause.TRAN_CAT_RECORD_CAT_CD_WIDTH, "TRAN-CAT-CD"),
                cursor.text(PicClause.TRAN_CAT_TYPE_DESC_WIDTH, "TRAN-CAT-TYPE-DESC"));
    }

    /**
     * Parses one {@code TRAN-RECORD}, the posted transaction layout of
     * {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param record one posted transaction record
     * @return the thirteen modelled fields of {@code app/cpy/CVTRA05Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code TRAN-AMT} holds an unreadable sign overpunch
     */
    public static PostedTransactionRecord parsePostedTransaction(String record) {
        requireDeclaredWidth(record, "CVTRA05Y TRAN-RECORD", PicClause.TRAN_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA05Y TRAN-RECORD");
        return new PostedTransactionRecord(
                cursor.fixedText(PicClause.TRAN_ID_WIDTH, "TRAN-ID"),
                cursor.fixedText(PicClause.TRAN_TYPE_CD_WIDTH, "TRAN-TYPE-CD"),
                cursor.fixedText(PicClause.TRAN_CAT_CD_WIDTH, "TRAN-CAT-CD"),
                cursor.text(PicClause.TRAN_SOURCE_WIDTH, "TRAN-SOURCE"),
                cursor.text(PicClause.TRAN_DESC_WIDTH, "TRAN-DESC"),
                cursor.signedDecimal(PicClause.TRAN_AMT_WIDTH, PicClause.TRAN_AMT_SCALE, "TRAN-AMT"),
                cursor.fixedText(PicClause.TRAN_MERCHANT_ID_WIDTH, "TRAN-MERCHANT-ID"),
                cursor.text(PicClause.TRAN_MERCHANT_NAME_WIDTH, "TRAN-MERCHANT-NAME"),
                cursor.text(PicClause.TRAN_MERCHANT_CITY_WIDTH, "TRAN-MERCHANT-CITY"),
                cursor.text(PicClause.TRAN_MERCHANT_ZIP_WIDTH, "TRAN-MERCHANT-ZIP"),
                cursor.fixedText(PicClause.TRAN_CARD_NUM_WIDTH, "TRAN-CARD-NUM"),
                cursor.fixedText(PicClause.TRAN_ORIG_TS_WIDTH, "TRAN-ORIG-TS"),
                cursor.fixedText(PicClause.TRAN_PROC_TS_WIDTH, "TRAN-PROC-TS"));
    }

    /**
     * Parses one {@code DALYTRAN-RECORD}, the layout of {@code app/data/ASCII/dailytran.txt}.
     *
     * <p>The amount field of this layout carries all twenty sign overpunch characters across the
     * three hundred fixture records.</p>
     *
     * @param record one fixture record
     * @return the thirteen modelled fields of {@code app/cpy/CVTRA06Y.cpy}
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code DALYTRAN-AMT} holds an unreadable sign overpunch
     */
    public static DailyTransactionRecord parseDailyTransaction(String record) {
        requireDeclaredWidth(record, "CVTRA06Y DALYTRAN-RECORD", PicClause.DALYTRAN_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CVTRA06Y DALYTRAN-RECORD");
        return new DailyTransactionRecord(
                cursor.fixedText(PicClause.DALYTRAN_ID_WIDTH, "DALYTRAN-ID"),
                cursor.fixedText(PicClause.DALYTRAN_TYPE_CD_WIDTH, "DALYTRAN-TYPE-CD"),
                cursor.fixedText(PicClause.DALYTRAN_CAT_CD_WIDTH, "DALYTRAN-CAT-CD"),
                cursor.text(PicClause.DALYTRAN_SOURCE_WIDTH, "DALYTRAN-SOURCE"),
                cursor.text(PicClause.DALYTRAN_DESC_WIDTH, "DALYTRAN-DESC"),
                cursor.signedDecimal(PicClause.DALYTRAN_AMT_WIDTH,
                        PicClause.DALYTRAN_AMT_SCALE, "DALYTRAN-AMT"),
                cursor.fixedText(PicClause.DALYTRAN_MERCHANT_ID_WIDTH, "DALYTRAN-MERCHANT-ID"),
                cursor.text(PicClause.DALYTRAN_MERCHANT_NAME_WIDTH, "DALYTRAN-MERCHANT-NAME"),
                cursor.text(PicClause.DALYTRAN_MERCHANT_CITY_WIDTH, "DALYTRAN-MERCHANT-CITY"),
                cursor.text(PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH, "DALYTRAN-MERCHANT-ZIP"),
                cursor.fixedText(PicClause.DALYTRAN_CARD_NUM_WIDTH, "DALYTRAN-CARD-NUM"),
                cursor.fixedText(PicClause.DALYTRAN_ORIG_TS_WIDTH, "DALYTRAN-ORIG-TS"),
                cursor.fixedText(PicClause.DALYTRAN_PROC_TS_WIDTH, "DALYTRAN-PROC-TS"));
    }

    /**
     * Parses one {@code REJECT-RECORD} of {@link PicClause#REJECT_RECORD_LENGTH} bytes.
     *
     * <p>The {@link PicClause#VALIDATION_TRAILER_WIDTH}-byte {@code VALIDATION-TRAILER} at
     * {@code app/cbl/CBTRN02C.cbl:L178} splits into a reason code at
     * {@code app/cbl/CBTRN02C.cbl:L181} and a description at
     * {@code app/cbl/CBTRN02C.cbl:L182}.</p>
     *
     * @param record one reject record
     * @return the daily transaction bytes joined to the two trailer fields
     * @throws IllegalArgumentException when {@code record} is not the width its layout declares,
     *         or when {@code WS-VALIDATION-FAIL-REASON} holds a character other than a digit
     */
    public static RejectedTransactionRecord parseRejectedTransaction(String record) {
        requireDeclaredWidth(record, "CBTRN02C REJECT-RECORD", PicClause.REJECT_RECORD_LENGTH);
        Cursor cursor = new Cursor(record, "CBTRN02C REJECT-RECORD");
        return new RejectedTransactionRecord(
                cursor.fixedText(PicClause.REJECT_TRAN_DATA_WIDTH, "REJECT-TRAN-DATA"),
                cursor.unsignedInteger(PicClause.VALIDATION_FAIL_REASON_WIDTH,
                        "WS-VALIDATION-FAIL-REASON"),
                cursor.text(PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                        "WS-VALIDATION-FAIL-REASON-DESC"));
    }

    // Timestamp operations. Both keep the field as text.

    /**
     * Returns the leading characters of a timestamp that the account expiration test reads.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L414} compares {@code ACCT-EXPIRAION-DATE} against
     * {@code DALYTRAN-ORIG-TS (1:10)} as text, and
     * {@link PicClause#ACCOUNT_EXPIRATION_COMPARISON_WIDTH} carries that length.</p>
     *
     * @param timestamp an origin or processing timestamp field
     * @return the leading characters of {@code timestamp}
     * @throws IllegalArgumentException when {@code timestamp} is shorter than the comparison reads
     */
    public static String timestampDatePart(String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (timestamp.length() < PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH) {
            throw new IllegalArgumentException("timestamp holds " + timestamp.length()
                    + " characters and the expiration comparison reads "
                    + PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH);
        }
        return timestamp.substring(0, PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH);
    }

    /**
     * Sets the trailing fractional digits of a processing timestamp to zero.
     *
     * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl:L700} carries
     * {@link PicClause#PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS} digits into
     * {@code DB2-MIL}, and {@code app/cbl/CBTRN02C.cbl:L701} zeroes {@code DB2-REST}. A field
     * holding only padding returns unchanged.</p>
     *
     * @param timestamp a {@code TRAN-PROC-TS} field, or a timestamp a service rendered
     * @return a timestamp of {@link PicClause#PROCESSING_TIMESTAMP_WIDTH} characters whose last
     *         {@link PicClause#PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS} digits are zeros
     * @throws IllegalArgumentException when a populated {@code timestamp} is not exactly
     *         {@link PicClause#PROCESSING_TIMESTAMP_WIDTH} characters
     */
    public static String truncateProcessingTimestampToHundredths(String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (timestamp.length() != PicClause.PROCESSING_TIMESTAMP_WIDTH) {
            throw new IllegalArgumentException("TRAN-PROC-TS holds " + timestamp.length()
                    + " characters and the field holds "
                    + PicClause.PROCESSING_TIMESTAMP_WIDTH);
        }
        if (isAllPadding(timestamp)) {
            return timestamp;
        }
        int significantEnd = PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET
                + PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS;
        return timestamp.substring(0, significantEnd)
                + PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS;
    }

    // Field operations. Every read names its own offset and width, so no read touches a byte
    // outside the field it was asked for.

    /**
     * Reads a {@code PIC X(n)} or {@code PIC 9(n)} field and keeps every padding character.
     *
     * @param record the whole fixture record
     * @param offset zero-based byte offset of the field
     * @param width  byte width of the field
     * @param field  COBOL field name, reported when the read fails
     * @return {@code width} characters of {@code record} starting at {@code offset}
     * @throws IllegalArgumentException when the field runs past the end of {@code record}
     */
    public static String fixedText(String record, int offset, int width, String field) {
        return slice(record, offset, width, field);
    }

    /**
     * Reads a {@code PIC X(n)} field and removes the trailing space padding COBOL applies.
     *
     * @param record the whole fixture record
     * @param offset zero-based byte offset of the field
     * @param width  byte width of the field
     * @param field  COBOL field name, reported when the read fails
     * @return the field with its trailing spaces removed
     * @throws IllegalArgumentException when the field runs past the end of {@code record}
     */
    public static String text(String record, int offset, int width, String field) {
        return stripTrailingPadding(slice(record, offset, width, field));
    }

    /**
     * Decodes a {@code PIC S9(n)V99} field into a {@link BigDecimal} at {@code scale}.
     *
     * <p>ADDITIVE. The trailing character carries the sign: {@link #POSITIVE_SIGN_OVERPUNCH_DIGITS}
     * and {@link #NEGATIVE_SIGN_OVERPUNCH_DIGITS} map it to a digit, and a plain digit there reads
     * as positive. The result carries exactly {@code scale} decimal places and drops no digit.</p>
     *
     * @param record the whole fixture record
     * @param offset zero-based byte offset of the field
     * @param width  byte width of the field, which equals its total digit count
     * @param scale  digits after the decimal point, taken from {@link PicClause}
     * @param field  COBOL field name, reported when the decode fails
     * @return the signed value of the field
     * @throws IllegalArgumentException when any of these holds:
     *         <ul>
     *           <li>the field runs past the end of {@code record}</li>
     *           <li>a leading character is not a digit</li>
     *           <li>the trailing character is neither a digit nor a sign overpunch</li>
     *         </ul>
     *         The failure names the field, the position inside it and its width, and never the text
     *         of the field
     */
    public static BigDecimal signedDecimal(String record, int offset, int width, int scale, String field) {
        String encoded = slice(record, offset, width, field);
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException(field + ": a signed display numeric needs at least "
                    + "one digit and the requested width is " + width);
        }
        int signPosition = encoded.length() - 1;
        char trailing = encoded.charAt(signPosition);
        int trailingDigit;
        boolean negative;
        int positiveIndex = POSITIVE_SIGN_OVERPUNCH_DIGITS.indexOf(trailing);
        int negativeIndex = NEGATIVE_SIGN_OVERPUNCH_DIGITS.indexOf(trailing);
        if (isAsciiDigit(trailing)) {
            trailingDigit = trailing - '0';
            negative = false;
        } else if (positiveIndex >= 0) {
            trailingDigit = positiveIndex;
            negative = false;
        } else if (negativeIndex >= 0) {
            trailingDigit = negativeIndex;
            negative = true;
        } else {
            throw new IllegalArgumentException(field + ": the character at field position "
                    + signPosition + " of " + encoded.length() + " is neither a digit nor a sign "
                    + "overpunch");
        }
        StringBuilder digits = new StringBuilder(encoded.length());
        for (int index = 0; index < signPosition; index++) {
            char current = encoded.charAt(index);
            if (!isAsciiDigit(current)) {
                throw new IllegalArgumentException(field + ": the character at field position "
                        + index + " of " + encoded.length() + " is not a digit");
            }
            digits.append(current);
        }
        digits.append((char) ('0' + trailingDigit));
        BigDecimal magnitude = new BigDecimal(digits.toString()).movePointLeft(scale);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Reads an unsigned {@code PIC 9(n)} field as a number.
     *
     * @param record the whole fixture record
     * @param offset zero-based byte offset of the field
     * @param width  byte width of the field
     * @param field  COBOL field name, reported when the read fails
     * @return the value of the field, with its leading zeros dropped
     * @throws IllegalArgumentException when the field runs past the end of {@code record}, holds a
     *         character other than a digit, or holds more digits than an {@code int} carries. The
     *         failure names the field, the position inside it and its width, and never the text of
     *         the field
     */
    public static int unsignedInteger(String record, int offset, int width, String field) {
        String encoded = slice(record, offset, width, field);
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException(field + ": an unsigned display numeric needs at "
                    + "least one digit and the requested width is " + width);
        }
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            if (!isAsciiDigit(current)) {
                throw new IllegalArgumentException(field + ": the character at field position "
                        + index + " of " + encoded.length() + " is not a digit");
            }
        }
        try {
            return Integer.parseInt(encoded);
        } catch (NumberFormatException tooWide) {
            // Integer.parseInt quotes the whole input in its own message, so it is replaced here.
            throw new IllegalArgumentException(field + ": a field of " + width
                    + " characters at offset " + offset + " holds more digits than an int carries. "
                    + "The field text is withheld from this message.");
        }
    }

    /**
     * Returns {@code width} characters of {@code record} starting at {@code offset}.
     *
     * <p>The bounds test subtracts the offset from the record length, so a width close to
     * {@link Integer#MAX_VALUE} reports the documented failure and no arithmetic overflows.</p>
     */
    private static String slice(String record, int offset, int width, String field) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(field, "field");
        if (offset < 0) {
            throw new IllegalArgumentException(field + ": offset " + offset + " is negative");
        }
        if (width < 0) {
            throw new IllegalArgumentException(field + ": width " + width + " is negative");
        }
        if (offset > record.length() || record.length() - offset < width) {
            throw new IllegalArgumentException(field + ": needs " + width + " bytes at offset "
                    + offset + " and the supplied record holds " + record.length() + " bytes");
        }
        return record.substring(offset, offset + width);
    }

    /**
     * Checks that a record holds exactly the byte width its layout declares.
     *
     * @param record        the record about to be parsed
     * @param layout        copybook and record name, reported when the width differs
     * @param declaredWidth the byte width the layout declares
     * @throws NullPointerException     when {@code record} is null
     * @throws IllegalArgumentException when {@code record} is any other width
     */
    private static void requireDeclaredWidth(String record, String layout, int declaredWidth) {
        Objects.requireNonNull(record, "record");
        if (record.length() != declaredWidth) {
            throw new IllegalArgumentException(layout + ": record holds " + record.length()
                    + " characters and the layout declares " + declaredWidth);
        }
    }

    /**
     * Checks that a record holds one of the two byte widths the cross-reference fixture and its
     * dataset definition declare.
     *
     * @param record         the record about to be parsed
     * @param layout         copybook and record name, reported when the width differs
     * @param deliveredWidth the narrower width the text fixture delivers
     * @param declaredWidth  the wider width the dataset definition declares
     * @throws NullPointerException     when {@code record} is null
     * @throws IllegalArgumentException when {@code record} is any other width
     */
    private static void requireEitherWidth(String record, String layout, int deliveredWidth,
            int declaredWidth) {
        Objects.requireNonNull(record, "record");
        if (record.length() != deliveredWidth && record.length() != declaredWidth) {
            throw new IllegalArgumentException(layout + ": record holds " + record.length()
                    + " characters and the layout reads either " + deliveredWidth + " or "
                    + declaredWidth);
        }
    }

    private static String stripTrailingPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == COBOL_TEXT_PAD) {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean isAllPadding(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != COBOL_TEXT_PAD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces every character of a value with {@link PanMasker#MASK_CHARACTER}, keeping the width.
     *
     * <p>The width survives, and carries no character of the value. A null value renders as the
     * four characters {@code null}.</p>
     *
     * @param value the value to redact; may be null
     * @return mask characters at the width of {@code value}
     */
    private static String redacted(String value) {
        if (value == null) {
            return "null";
        }
        return String.valueOf(PanMasker.MASK_CHARACTER).repeat(value.length());
    }

    private static boolean isAsciiDigit(char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * Walks one record, consuming field widths in copybook declaration order. Each read advances
     * the offset by the width it consumed, so a parse method states widths only and every offset
     * is the running sum of the widths above it.
     */
    private static final class Cursor {

        private final String record;
        private final String layout;
        private int offset;

        private Cursor(String record, String layout) {
            this.record = Objects.requireNonNull(record, "record");
            this.layout = Objects.requireNonNull(layout, "layout");
        }

        private String fixedText(int width, String field) {
            String value = CopybookRecordParser.fixedText(record, offset, width, qualify(field));
            offset += width;
            return value;
        }

        private String text(int width, String field) {
            String value = CopybookRecordParser.text(record, offset, width, qualify(field));
            offset += width;
            return value;
        }

        private BigDecimal signedDecimal(int width, int scale, String field) {
            BigDecimal value =
                    CopybookRecordParser.signedDecimal(record, offset, width, scale, qualify(field));
            offset += width;
            return value;
        }

        private int unsignedInteger(int width, String field) {
            int value = CopybookRecordParser.unsignedInteger(record, offset, width, qualify(field));
            offset += width;
            return value;
        }

        private String qualify(String field) {
            return layout + " " + field;
        }
    }
}
