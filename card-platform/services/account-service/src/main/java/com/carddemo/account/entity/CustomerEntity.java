package com.carddemo.account.entity;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Customer master record for the account service, mapped from the canonical copybook
 * app/cpy/CVCUS01Y.cpy.
 *
 * <p>This Jakarta Persistence entity maps the eighteen fields that {@code 01 CUSTOMER-RECORD}
 * declares at {@code app/cpy/CVCUS01Y.cpy:L4} through {@code app/cpy/CVCUS01Y.cpy:L22}. Column
 * {@code customer_id} carries the primary key, and the Virtual Storage Access Method (VSAM)
 * cluster definition {@code KEYS(9 0)} at {@code app/jcl/CUSTFILE.jcl:L50} names the same nine
 * digits.</p>
 *
 * <p>The trailing {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23} maps to no
 * column.</p>
 *
 * <p>Every width below comes from {@link PicClause}. Each character column declares its width, and
 * the three fixed-width columns declare the {@code bpchar} type that PostgreSQL reports for
 * {@code CHAR}. The account service checks this mapping against the migrated schema at start-up
 * and writes no schema of its own.</p>
 *
 * <p>No field here holds a monetary amount, so no field carries an implied decimal.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    /**
     * Customer identifier and primary key. {@code CUST-ID PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L5}. Column {@code customer_id} holds {@code CHAR(9)}, and the
     * account view program keys its customer read on this value at
     * {@code app/cbl/COACTVWC.cbl:L828-L829}. The record supplies the value, and the service
     * generates none.
     *
     * <p>The field is a {@link String} and not a number. {@code PIC 9(09)} is a display field nine
     * characters wide, and record one of {@code app/data/ASCII/custdata.txt} holds
     * {@code 000000001}. A numeric column returns {@code 1} for that record, which no longer
     * matches the nine-character key at {@code KEYS(9 0)} in {@code app/jcl/CUSTFILE.jcl:L50} or
     * the customer identifier the cross-reference row carries. The check constraint
     * {@code ck_customer_customer_id_digits} holds the width and the digit class.</p>
     */
    @Id
    @Column(name = "customer_id", nullable = false,
            length = PicClause.CUST_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CUST_ID_WIDTH + ")")
    private String customerId;

    /**
     * Customer first name. {@code CUST-FIRST-NAME PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L6}.
     */
    @Column(name = "first_name", nullable = false, length = PicClause.CUST_FIRST_NAME_WIDTH)
    private String firstName;

    /**
     * Customer middle name. {@code CUST-MIDDLE-NAME PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L7}.
     */
    @Column(name = "middle_name", nullable = false, length = PicClause.CUST_MIDDLE_NAME_WIDTH)
    private String middleName;

    /**
     * Customer last name. {@code CUST-LAST-NAME PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L8}.
     */
    @Column(name = "last_name", nullable = false, length = PicClause.CUST_LAST_NAME_WIDTH)
    private String lastName;

    /**
     * First line of the customer address. {@code CUST-ADDR-LINE-1 PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L9}. The account update program labels the field
     * {@code 'Address Line 1'} and requires a value at
     * {@code app/cbl/COACTUPC.cbl:L1584-L1588}.
     */
    @Column(name = "address_line_1", nullable = false, length = PicClause.CUST_ADDR_LINE_1_WIDTH)
    private String addressLine1;

    /**
     * Second line of the customer address. {@code CUST-ADDR-LINE-2 PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L10}.
     */
    @Column(name = "address_line_2", nullable = false, length = PicClause.CUST_ADDR_LINE_2_WIDTH)
    private String addressLine2;

    /**
     * City of the customer address. {@code CUST-ADDR-LINE-3 PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L11}. {@code app/cbl/COACTUPC.cbl:L1615} labels the field
     * {@code 'City'}, and {@code app/cbl/COACTUPC.cbl:L1618} edits it as fifty required
     * alphabetic characters.
     */
    @Column(name = "address_city", nullable = false, length = PicClause.CUST_ADDR_LINE_3_WIDTH)
    private String addressCity;

    /**
     * Two-letter state code of the customer address. {@code CUST-ADDR-STATE-CD PIC X(02)} at
     * {@code app/cpy/CVCUS01Y.cpy:L12}. Column {@code address_state_code} holds {@code CHAR(2)},
     * which PostgreSQL reports as {@code bpchar}. The state-code edit at
     * {@code app/cbl/COACTUPC.cbl:L2493} tests the value against the 56 codes that
     * app/cpy/CSLKPCDY.cpy declares.
     */
    @Column(name = "address_state_code", nullable = false,
            columnDefinition = "bpchar(" + PicClause.CUST_ADDR_STATE_CD_WIDTH + ")")
    private String addressStateCode;

    /**
     * Three-letter country code of the customer address. {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L13}. Column {@code address_country_code} holds
     * {@code CHAR(3)}, which PostgreSQL reports as {@code bpchar}.
     */
    @Column(name = "address_country_code", nullable = false,
            columnDefinition = "bpchar(" + PicClause.CUST_ADDR_COUNTRY_CD_WIDTH + ")")
    private String addressCountryCode;

    /**
     * Postal code of the customer address. {@code CUST-ADDR-ZIP PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L14}. The state-and-zip edit at
     * {@code app/cbl/COACTUPC.cbl:L2537-L2540} reads the first two digits of this value.
     */
    @Column(name = "address_zip", nullable = false, length = PicClause.CUST_ADDR_ZIP_WIDTH)
    private String addressZip;

    /**
     * Primary telephone number. {@code CUST-PHONE-NUM-1 PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy:L15}. The telephone edit reads the value as an area code, a
     * prefix, and a line number.
     */
    @Column(name = "phone_number_1", nullable = false, length = PicClause.CUST_PHONE_NUM_1_WIDTH)
    private String phoneNumber1;

    /**
     * Secondary telephone number. {@code CUST-PHONE-NUM-2 PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy:L16}.
     */
    @Column(name = "phone_number_2", nullable = false, length = PicClause.CUST_PHONE_NUM_2_WIDTH)
    private String phoneNumber2;

    /**
     * Social Security Number of the customer. {@code CUST-SSN PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L17}. Column {@code social_security_number} holds
     * {@code CHAR(9)}, and the edit at {@code app/cbl/COACTUPC.cbl:L2431-L2491} reads the value as
     * parts of three, two, and four digits. This value reaches no event, no log line, and no
     * response body.
     *
     * <p>The field is a {@link String} and not a number, and here the difference changes the data
     * rather than only its formatting. Offset (280,9) of record one of
     * {@code app/data/ASCII/custdata.txt} holds {@code 020973888}. A numeric column stores
     * {@code 20973888} and returns eight digits. That is a different Social Security Number, and
     * one that no longer splits into the three, two and four digit parts the source edit reads.
     * The check constraint {@code ck_customer_ssn_digits} holds the width and the digit class.</p>
     */
    @Column(name = "social_security_number", nullable = false,
            length = PicClause.CUST_SSN_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CUST_SSN_WIDTH + ")")
    private String socialSecurityNumber;

    /**
     * Government-issued identifier of the customer. {@code CUST-GOVT-ISSUED-ID PIC X(20)} at
     * {@code app/cpy/CVCUS01Y.cpy:L18}. This value reaches no event, no log line, and no response
     * body.
     */
    @Column(name = "government_issued_id", nullable = false,
            length = PicClause.CUST_GOVT_ISSUED_ID_WIDTH)
    private String governmentIssuedId;

    /**
     * Date of birth, held as ten characters in year, month, and day order with two separators.
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19}. Column
     * {@code date_of_birth} holds {@code VARCHAR(10)}, and the change detector at
     * {@code app/cbl/COACTUPC.cbl:L4174-L4179} compares the value as sliced text.
     */
    @Column(name = "date_of_birth", nullable = false, length = PicClause.CUST_DOB_WIDTH)
    private String dateOfBirth;

    /**
     * Electronic funds transfer account identifier. {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L20}.
     */
    @Column(name = "eft_account_id", nullable = false,
            length = PicClause.CUST_EFT_ACCOUNT_ID_WIDTH)
    private String eftAccountId;

    /**
     * Flag marking the customer as the primary holder of the card.
     * {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at {@code app/cpy/CVCUS01Y.cpy:L21}. Column
     * {@code primary_card_holder_indicator} holds {@code CHAR(1)}, which PostgreSQL reports as
     * {@code bpchar}.
     */
    @Column(name = "primary_card_holder_indicator", nullable = false,
            columnDefinition = "bpchar(" + PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH + ")")
    private String primaryCardHolderIndicator;

    /**
     * FICO credit score of the customer. {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L22}. Column {@code fico_credit_score} holds
     * {@code NUMERIC(3,0)} and carries no range constraint. Condition name
     * {@code FICO-RANGE-IS-VALID} at {@code app/cbl/COACTUPC.cbl:L848-L849} accepts 300 through
     * 850 inclusive, and the request types apply that range.
     */
    @Column(name = "fico_credit_score", nullable = false,
            precision = PicClause.CUST_FICO_CREDIT_SCORE_WIDTH, scale = 0)
    private BigDecimal ficoCreditScore;

    public CustomerEntity() {
    }

    /**
     * Returns the customer identifier and primary key.
     *
     * @return the nine-digit customer identifier, or null on an unsaved instance
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer identifier and primary key.
     *
     * @param customerId the customer identifier, exactly {@value PicClause#CUST_ID_WIDTH} digits
     * @throws IllegalArgumentException when the argument is absent, the wrong width, or holds a
     *                                 character that is not a digit
     */
    public void setCustomerId(String customerId) {
        this.customerId = requireDigits("customerId", customerId, PicClause.CUST_ID_WIDTH);
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressStateCode() {
        return addressStateCode;
    }

    public void setAddressStateCode(String addressStateCode) {
        this.addressStateCode = addressStateCode;
    }

    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    public void setAddressCountryCode(String addressCountryCode) {
        this.addressCountryCode = addressCountryCode;
    }

    public String getAddressZip() {
        return addressZip;
    }

    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = phoneNumber1;
    }

    public String getPhoneNumber2() {
        return phoneNumber2;
    }

    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = phoneNumber2;
    }

    /**
     * Returns the Social Security Number. Callers keep this value inside the account service and
     * place it in no event, no log line, and no response body.
     *
     * @return the nine-digit Social Security Number
     */
    public String getSocialSecurityNumber() {
        return socialSecurityNumber;
    }

    /**
     * Sets the Social Security Number.
     *
     * @param socialSecurityNumber the Social Security Number, exactly
     *                             {@value PicClause#CUST_SSN_WIDTH} digits, leading zero included
     * @throws IllegalArgumentException when the argument is absent, the wrong width, or holds a
     *                                 character that is not a digit
     */
    public void setSocialSecurityNumber(String socialSecurityNumber) {
        this.socialSecurityNumber =
                requireDigits("socialSecurityNumber", socialSecurityNumber,
                        PicClause.CUST_SSN_WIDTH);
    }

    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getEftAccountId() {
        return eftAccountId;
    }

    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    public BigDecimal getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score. This class applies no range check.
     *
     * <p>The score stays a number where the two identifiers above are text:
     * {@code app/cbl/COACTUPC.cbl:L848-L849} compares it against 300 and 850 as magnitudes, so it
     * is a quantity and not an identifier.</p>
     *
     * @param ficoCreditScore the three-digit credit score
     */
    public void setFicoCreditScore(BigDecimal ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the class name and one withheld component.
     *
     * <p>Every column of this row is personal data: a name, a postal address, two telephone
     * numbers, a Social Security Number, a government-issued identifier, a date of birth, an
     * electronic funds transfer account and a credit score. The customer identifier is stable and
     * names one cardholder across every table of this platform, so it stays behind
     * {@link #getCustomerId()} with the rest. A rendering reaches a log line as soon as any code
     * concatenates the entity into a message, and this one carries no value at all.</p>
     *
     * @return a single-line rendering naming the class and reporting the identifier as withheld
     */
    @Override
    public String toString() {
        return "CustomerEntity[customerId=" + EventEnvelope.WITHHELD + "]";
    }

    /**
     * Rejects an identifier that is the wrong width or carries a character outside {@code 0}
     * through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits, and
     * the column check constraint repeats both halves in the database. Neither message carries a
     * character of the rejected value. The width message reports a length and the digit message
     * reports a position, which keeps a Social Security Number out of any log line a caller
     * writes from a failure.</p>
     *
     * @param field the field name the message reports
     * @param value the value under test
     * @param width the exact number of digits the source picture clause declares
     * @return the supplied value
     * @throws IllegalArgumentException when the value is absent, the wrong width, or holds a
     *                                 character that is not a digit
     */
    private static String requireDigits(String field, String value, int width) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " must be exactly " + width
                    + " digits wide, found width " + value.length());
        }
        for (int position = 0; position < width; position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(field
                        + " must hold digits only, found a character outside 0 through 9 at "
                        + "position " + (position + 1));
            }
        }
        return value;
    }
}
