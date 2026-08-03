package com.carddemo.account.entity;

import com.carddemo.cobol.PicClause;
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
 * <p>The trailing {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23} maps to no column.
 * The eighteen mapped widths sum to 332 bytes, and the filler carries each record to the 500 bytes
 * that {@code RECORDSIZE(500 500)} at {@code app/jcl/CUSTFILE.jcl:L51} declares.
 * card-platform/docs/traceability-matrix.md records the dropped bytes.</p>
 *
 * <p>A second copybook, app/cpy/CUSTREC.cpy, declares the same eighteen picture clauses and names
 * the date field {@code CUST-DOB-YYYYMMDD} at its L19. app/cbl/CBSTM03A.CBL:L55 binds that fork,
 * so the notification service reads the fork shape while this service reads the canonical shape.
 * card-platform/docs/decision-log.md records the adoption.</p>
 *
 * <p>Every width below comes from {@link PicClause}. Each character column declares its width, and
 * the three fixed-width columns declare the {@code bpchar} type that PostgreSQL reports for
 * {@code CHAR}. The account service checks this mapping against the migrated schema at start-up
 * and writes no schema of its own.</p>
 *
 * <p>No field here holds a monetary amount, so no field carries an implied decimal. The account
 * record holds no customer identifier, and card-platform/docs/data-model.md shows the path from an
 * account to a customer.</p>
 */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    /**
     * Customer identifier and primary key. {@code CUST-ID PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L5}. Column {@code customer_id} holds {@code NUMERIC(9,0)}, and
     * the account view program keys its customer read on this value at
     * {@code app/cbl/COACTVWC.cbl:L828-L829}. The record supplies the value, and the service
     * generates none.
     */
    @Id
    @Column(name = "customer_id", nullable = false,
            precision = PicClause.CUST_ID_WIDTH, scale = 0)
    private BigDecimal customerId;

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
     * {@code app/cpy/CVCUS01Y.cpy:L10}. {@code app/cbl/COACTUPC.cbl:L1613} marks the line
     * optional and no edit reads it, while the change detector compares it under an upper-case
     * fold at {@code app/cbl/COACTUPC.cbl:L4160-L4161}.
     * card-platform/docs/business-rule-flags.md carries that asymmetry.
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
     * {@code NUMERIC(9,0)}, and the edit at {@code app/cbl/COACTUPC.cbl:L2431-L2491} reads the
     * value as parts of three, two, and four digits. This value reaches no event, no log line, and
     * no response body.
     */
    @Column(name = "social_security_number", nullable = false,
            precision = PicClause.CUST_SSN_WIDTH, scale = 0)
    private BigDecimal socialSecurityNumber;

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

    /**
     * Creates an empty customer. Jakarta Persistence calls this constructor when it materializes a
     * row, and a caller building a new customer sets each field through its accessor.
     */
    public CustomerEntity() {
    }

    /**
     * Returns the customer identifier and primary key.
     *
     * @return the nine-digit customer identifier, or null on an unsaved instance
     */
    public BigDecimal getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer identifier and primary key.
     *
     * @param customerId the nine-digit customer identifier the record supplies
     */
    public void setCustomerId(BigDecimal customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns the customer first name.
     *
     * @return up to 25 characters of first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the customer first name.
     *
     * @param firstName up to 25 characters of first name
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the customer middle name.
     *
     * @return up to 25 characters of middle name
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the customer middle name.
     *
     * @param middleName up to 25 characters of middle name
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the customer last name.
     *
     * @return up to 25 characters of last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the customer last name.
     *
     * @param lastName up to 25 characters of last name
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the first line of the customer address.
     *
     * @return up to 50 characters of street address
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Sets the first line of the customer address.
     *
     * @param addressLine1 up to 50 characters of street address
     */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    /**
     * Returns the second line of the customer address.
     *
     * @return up to 50 characters of street address
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Sets the second line of the customer address.
     *
     * @param addressLine2 up to 50 characters of street address
     */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    /**
     * Returns the city of the customer address.
     *
     * @return up to 50 characters of city name
     */
    public String getAddressCity() {
        return addressCity;
    }

    /**
     * Sets the city of the customer address.
     *
     * @param addressCity up to 50 characters of city name
     */
    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    /**
     * Returns the state code of the customer address.
     *
     * @return the two-letter state code
     */
    public String getAddressStateCode() {
        return addressStateCode;
    }

    /**
     * Sets the state code of the customer address.
     *
     * @param addressStateCode the two-letter state code
     */
    public void setAddressStateCode(String addressStateCode) {
        this.addressStateCode = addressStateCode;
    }

    /**
     * Returns the country code of the customer address.
     *
     * @return the three-letter country code
     */
    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    /**
     * Sets the country code of the customer address.
     *
     * @param addressCountryCode the three-letter country code
     */
    public void setAddressCountryCode(String addressCountryCode) {
        this.addressCountryCode = addressCountryCode;
    }

    /**
     * Returns the postal code of the customer address.
     *
     * @return up to 10 characters of postal code
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the postal code of the customer address.
     *
     * @param addressZip up to 10 characters of postal code
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns the primary telephone number.
     *
     * @return up to 15 characters of telephone number
     */
    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    /**
     * Sets the primary telephone number.
     *
     * @param phoneNumber1 up to 15 characters of telephone number
     */
    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = phoneNumber1;
    }

    /**
     * Returns the secondary telephone number.
     *
     * @return up to 15 characters of telephone number
     */
    public String getPhoneNumber2() {
        return phoneNumber2;
    }

    /**
     * Sets the secondary telephone number.
     *
     * @param phoneNumber2 up to 15 characters of telephone number
     */
    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = phoneNumber2;
    }

    /**
     * Returns the Social Security Number. Callers keep this value inside the account service and
     * place it in no event, no log line, and no response body.
     *
     * @return the nine-digit Social Security Number
     */
    public BigDecimal getSocialSecurityNumber() {
        return socialSecurityNumber;
    }

    /**
     * Sets the Social Security Number.
     *
     * @param socialSecurityNumber the nine-digit Social Security Number
     */
    public void setSocialSecurityNumber(BigDecimal socialSecurityNumber) {
        this.socialSecurityNumber = socialSecurityNumber;
    }

    /**
     * Returns the government-issued identifier. Callers keep this value inside the account service
     * and place it in no event, no log line, and no response body.
     *
     * @return up to 20 characters of government-issued identifier
     */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /**
     * Sets the government-issued identifier.
     *
     * @param governmentIssuedId up to 20 characters of government-issued identifier
     */
    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    /**
     * Returns the date of birth as ten characters in year, month, and day order.
     *
     * @return the ten-character date of birth, separators included
     */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth. The caller supplies ten characters in year, month, and day order
     * with separators, and this class converts nothing.
     *
     * @param dateOfBirth the ten-character date of birth, separators included
     */
    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the electronic funds transfer account identifier.
     *
     * @return up to 10 characters of transfer account identifier
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the electronic funds transfer account identifier.
     *
     * @param eftAccountId up to 10 characters of transfer account identifier
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the flag marking the customer as the primary holder of the card.
     *
     * @return the single-character primary holder flag
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Sets the flag marking the customer as the primary holder of the card.
     *
     * @param primaryCardHolderIndicator the single-character primary holder flag
     */
    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    /**
     * Returns the FICO credit score.
     *
     * @return the three-digit credit score
     */
    public BigDecimal getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score. This class applies no range check.
     *
     * @param ficoCreditScore the three-digit credit score
     */
    public void setFicoCreditScore(BigDecimal ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }
}
