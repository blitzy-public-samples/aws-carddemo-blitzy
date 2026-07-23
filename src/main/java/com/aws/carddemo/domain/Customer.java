package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * JPA entity representing a CardDemo customer master record.
 *
 * <p>This entity is the Java 25 / Spring Boot migration target for the legacy IBM z/OS VSAM
 * {@code CUSTDAT} KSDS. It maps the COBOL {@code CUSTOMER-RECORD} fixed-width record
 * layout (record length 500) one-for-one, preserving field order, sizes and the single primary
 * key {@code CUST-ID} exactly so that byte-level and behavioral parity with the mainframe is
 * retained (see Technical Specification &sect;0.6.2).</p>
 *
 * <p><strong>Provenance (dual-source consolidation):</strong> this entity is consolidated from
 * <em>two</em> copybook sources that declare the identical 500-byte {@code CUSTOMER-RECORD}
 * layout, retained read-only under the {@code legacy/} tree:</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVCUS01Y.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500</li>
 *   <li>{@code legacy/cpy/CUSTREC.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500</li>
 * </ul>
 * <p>The two copybooks differ only in the name of the date-of-birth field
 * ({@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y} versus {@code CUST-DOB-YYYYMMDD} in
 * {@code CUSTREC}); both describe the same 10-byte ISO date. The decision to consolidate the two
 * copybooks into this single entity is recorded in {@code docs/decision-log.md} and is not
 * re-argued here.</p>
 *
 * <p><strong>PII:</strong> this record contains personally identifiable information. In
 * particular {@code cust_ssn} (Social Security Number) and {@code cust_dob} (date of birth) are
 * sensitive. Callers and logging must treat these fields accordingly; {@link #toString()} in this
 * class deliberately masks the SSN and does not emit the raw date of birth so that PII is not
 * leaked to logs.</p>
 *
 * <p>The COBOL {@code FILLER PIC X(168)} trailing slack bytes are intentionally not persisted as a
 * column; record-length parity for fixed-width flat-file interfaces is enforced separately by the
 * {@code FixedWidthRecordMapper} utility rather than by this entity.</p>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /**
     * Customer identifier. COBOL {@code CUST-ID PIC 9(09)}; VSAM {@code CUSTDAT} primary key.
     * Mapped to {@code NUMERIC(9)} per the deliberate {@code 9(n) -> NUMERIC(n)} rule.
     */
    @Id
    @Column(name = "cust_id", precision = 9)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long custId;

    /** Customer first name. COBOL {@code CUST-FIRST-NAME PIC X(25)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_first_name", length = 25)
    private String firstName;

    /** Customer middle name. COBOL {@code CUST-MIDDLE-NAME PIC X(25)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_middle_name", length = 25)
    private String middleName;

    /** Customer last name. COBOL {@code CUST-LAST-NAME PIC X(25)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_last_name", length = 25)
    private String lastName;

    /** Address line 1. COBOL {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_1", length = 50)
    private String addrLine1;

    /** Address line 2. COBOL {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_2", length = 50)
    private String addrLine2;

    /** Address line 3. COBOL {@code CUST-ADDR-LINE-3 PIC X(50)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_3", length = 50)
    private String addrLine3;

    /** Address state code. COBOL {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_state_cd", length = 2)
    private String addrStateCd;

    /** Address country code. COBOL {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_country_cd", length = 3)
    private String addrCountryCd;

    /** Address ZIP code. COBOL {@code CUST-ADDR-ZIP PIC X(10)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_zip", length = 10)
    private String addrZip;

    /** Primary phone number. COBOL {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_1", length = 15)
    private String phoneNum1;

    /** Secondary phone number. COBOL {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_2", length = 15)
    private String phoneNum2;

    /**
     * Social Security Number (PII). COBOL {@code CUST-SSN PIC 9(09)}; mapped to {@code NUMERIC(9)}
     * per the deliberate {@code 9(n) -> NUMERIC(n)} rule.
     *
     * <p>Leading zeros are significant in the source data (for example {@code 020973888}). The
     * database stores the numeric value; fixed-width output re-pads to nine digits ({@code %09d})
     * via the {@code FixedWidthRecordMapper} utility, so the numeric mapping is loss-free.</p>
     */
    @Column(name = "cust_ssn", precision = 9)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long ssn;

    /** Government-issued identifier. COBOL {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_govt_issued_id", length = 20)
    private String govtIssuedId;

    /**
     * Date of birth (PII). COBOL {@code CUST-DOB-YYYY-MM-DD PIC X(10)} in
     * {@code legacy/cpy/CVCUS01Y.cpy} (named {@code CUST-DOB-YYYYMMDD} in
     * {@code legacy/cpy/CUSTREC.cpy}) &mdash; the same 10-byte ISO ({@code yyyy-MM-dd}) date field.
     */
    @Column(name = "cust_dob")
    private LocalDate dateOfBirth;

    /** Electronic funds transfer account identifier. COBOL {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_eft_account_id", length = 10)
    private String eftAccountId;

    /** Primary card holder indicator. COBOL {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_pri_card_holder_ind", length = 1)
    private String priCardHolderInd;

    /**
     * FICO credit score. COBOL {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}; mapped to
     * {@code NUMERIC(3)} per the deliberate {@code 9(n) -> NUMERIC(n)} rule.
     */
    @Column(name = "cust_fico_credit_score", precision = 3)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Integer ficoCreditScore;

    /**
     * No-argument constructor required by the JPA specification for entity instantiation.
     */
    public Customer() {
        // Intentionally empty: field state is populated by JPA and by setters.
    }

    /**
     * Returns the customer identifier (primary key).
     *
     * @return the customer id
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the customer identifier (primary key).
     *
     * @param custId the customer id
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the customer first name.
     *
     * @return the first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the customer first name.
     *
     * @param firstName the first name
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the customer middle name.
     *
     * @return the middle name
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the customer middle name.
     *
     * @param middleName the middle name
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the customer last name.
     *
     * @return the last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the customer last name.
     *
     * @param lastName the last name
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the first address line.
     *
     * @return address line 1
     */
    public String getAddrLine1() {
        return addrLine1;
    }

    /**
     * Sets the first address line.
     *
     * @param addrLine1 address line 1
     */
    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    /**
     * Returns the second address line.
     *
     * @return address line 2
     */
    public String getAddrLine2() {
        return addrLine2;
    }

    /**
     * Sets the second address line.
     *
     * @param addrLine2 address line 2
     */
    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    /**
     * Returns the third address line.
     *
     * @return address line 3
     */
    public String getAddrLine3() {
        return addrLine3;
    }

    /**
     * Sets the third address line.
     *
     * @param addrLine3 address line 3
     */
    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    /**
     * Returns the address state code.
     *
     * @return the state code
     */
    public String getAddrStateCd() {
        return addrStateCd;
    }

    /**
     * Sets the address state code.
     *
     * @param addrStateCd the state code
     */
    public void setAddrStateCd(String addrStateCd) {
        this.addrStateCd = addrStateCd;
    }

    /**
     * Returns the address country code.
     *
     * @return the country code
     */
    public String getAddrCountryCd() {
        return addrCountryCd;
    }

    /**
     * Sets the address country code.
     *
     * @param addrCountryCd the country code
     */
    public void setAddrCountryCd(String addrCountryCd) {
        this.addrCountryCd = addrCountryCd;
    }

    /**
     * Returns the address ZIP code.
     *
     * @return the ZIP code
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * Sets the address ZIP code.
     *
     * @param addrZip the ZIP code
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * Returns the primary phone number.
     *
     * @return the primary phone number
     */
    public String getPhoneNum1() {
        return phoneNum1;
    }

    /**
     * Sets the primary phone number.
     *
     * @param phoneNum1 the primary phone number
     */
    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    /**
     * Returns the secondary phone number.
     *
     * @return the secondary phone number
     */
    public String getPhoneNum2() {
        return phoneNum2;
    }

    /**
     * Sets the secondary phone number.
     *
     * @param phoneNum2 the secondary phone number
     */
    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    /**
     * Returns the Social Security Number (PII).
     *
     * @return the SSN as a numeric value; leading zeros are re-applied on fixed-width output
     */
    public Long getSsn() {
        return ssn;
    }

    /**
     * Sets the Social Security Number (PII).
     *
     * @param ssn the SSN as a numeric value
     */
    public void setSsn(Long ssn) {
        this.ssn = ssn;
    }

    /**
     * Returns the government-issued identifier.
     *
     * @return the government-issued id
     */
    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    /**
     * Sets the government-issued identifier.
     *
     * @param govtIssuedId the government-issued id
     */
    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = govtIssuedId;
    }

    /**
     * Returns the date of birth (PII).
     *
     * @return the date of birth
     */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth (PII).
     *
     * @param dateOfBirth the date of birth
     */
    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the EFT account identifier.
     *
     * @return the EFT account id
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the EFT account identifier.
     *
     * @param eftAccountId the EFT account id
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the primary card holder indicator.
     *
     * @return the primary card holder indicator
     */
    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    /**
     * Sets the primary card holder indicator.
     *
     * @param priCardHolderInd the primary card holder indicator
     */
    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    /**
     * Returns the FICO credit score.
     *
     * @return the FICO credit score
     */
    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score.
     *
     * @param ficoCreditScore the FICO credit score
     */
    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Compares this customer to another object for equality based solely on the primary key
     * {@code custId}, consistent with JPA identity semantics. Two customers are equal when both
     * have a non-null, equal {@code custId}. Instances with a {@code null} identifier are treated
     * as not equal to any other instance (including other unsaved instances).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code Customer} with the same non-null id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer other)) {
            return false;
        }
        return custId != null && custId.equals(other.custId);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one derived from
     * {@code custId}) is used so the hash does not change when the mutable primary key is assigned
     * on persist, keeping instances locatable in hash-based collections and consistent with
     * {@link #equals(Object)}. This unifies {@code Customer} with the stable-hash identity strategy
     * applied across all entities (review finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return Customer.class.hashCode();
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token.
     *
     * <p><strong>PII safety:</strong> this record contains personally identifiable information
     * (name, address, phone, SSN, government id, date of birth, FICO score). None of it — not even
     * partially masked — is emitted here, so {@link #toString()} output (for example in application
     * logs or error messages) cannot leak customer PII (CWE-532; review finding F9).</p>
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "Customer@" + Integer.toHexString(System.identityHashCode(this));
    }

}
