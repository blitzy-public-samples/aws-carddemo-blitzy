package com.carddemo.common.domain;

import com.carddemo.common.crypto.CryptoConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer master record entity mapped to the ``customers`` table.
 *
 * :purpose: Persistent JPA representation of the legacy COBOL ``CUSTOMER-RECORD``
 *           (copybook ``CVCUS01Y``, fixed length 500 bytes) re-platformed to a
 *           PostgreSQL relational row. Each instance is a single customer keyed
 *           by :field:`custId`. Field names and column widths are preserved
 *           one-to-one from the source copybook; the trailing ``FILLER`` is not
 *           mapped.
 * :output: A managed JPA entity whose public accessors expose every non-FILLER
 *          field of the source record. Sensitive PII (social-security number and
 *          government-issued id) is masked by {@link #toString()} and is never
 *          rendered in full for diagnostic output.
 */
@Entity
@Table(name = "customers")
public class Customer {

    /** Unique customer identifier (source ``CUST-ID`` PIC 9(09)). */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /** Customer first name (source ``CUST-FIRST-NAME`` PIC X(25)). */
    @NotBlank
    @Size(max = 25)
    @Column(name = "cust_first_name", length = 25, nullable = false)
    private String custFirstName;

    /** Customer middle name (source ``CUST-MIDDLE-NAME`` PIC X(25)). */
    @Size(max = 25)
    @Column(name = "cust_middle_name", length = 25)
    private String custMiddleName;

    /** Customer last name (source ``CUST-LAST-NAME`` PIC X(25)). */
    @NotBlank
    @Size(max = 25)
    @Column(name = "cust_last_name", length = 25, nullable = false)
    private String custLastName;

    /** Address line 1 (source ``CUST-ADDR-LINE-1`` PIC X(50)). */
    @Column(name = "cust_addr_line_1", length = 50)
    private String custAddrLine1;

    /** Address line 2 (source ``CUST-ADDR-LINE-2`` PIC X(50)). */
    @Column(name = "cust_addr_line_2", length = 50)
    private String custAddrLine2;

    /** Address line 3 (source ``CUST-ADDR-LINE-3`` PIC X(50)). */
    @Column(name = "cust_addr_line_3", length = 50)
    private String custAddrLine3;

    /** Address state code (source ``CUST-ADDR-STATE-CD`` PIC X(02)). */
    @Column(name = "cust_addr_state_cd", length = 2)
    private String custAddrStateCd;

    /** Address country code (source ``CUST-ADDR-COUNTRY-CD`` PIC X(03)). */
    @Column(name = "cust_addr_country_cd", length = 3)
    private String custAddrCountryCd;

    /** Address ZIP code (source ``CUST-ADDR-ZIP`` PIC X(10)). */
    @Column(name = "cust_addr_zip", length = 10)
    private String custAddrZip;

    /** Primary phone number (source ``CUST-PHONE-NUM-1`` PIC X(15)). */
    @Column(name = "cust_phone_num_1", length = 15)
    private String custPhoneNum1;

    /** Secondary phone number (source ``CUST-PHONE-NUM-2`` PIC X(15)). */
    @Column(name = "cust_phone_num_2", length = 15)
    private String custPhoneNum2;

    /**
     * Social-security number (source ``CUST-SSN`` PIC 9(09)).
     *
     * :sensitive: PII. Encrypted at rest via {@link CryptoConverter} and never
     *             serialized to clients; masked by {@link #toString()}. The
     *             column is widened to hold the Base64 ciphertext token.
     */
    @Column(name = "cust_ssn", length = 512)
    @Convert(converter = CryptoConverter.class)
    @JsonIgnore
    private String custSsn;

    /**
     * Government-issued identifier (source ``CUST-GOVT-ISSUED-ID`` PIC X(20)).
     *
     * :sensitive: PII. Encrypted at rest via {@link CryptoConverter} and never
     *             serialized to clients; masked by {@link #toString()}.
     */
    @Column(name = "cust_govt_issued_id", length = 512)
    @Convert(converter = CryptoConverter.class)
    @JsonIgnore
    private String custGovtIssuedId;

    /**
     * Date of birth in ``YYYY-MM-DD`` form (source ``CUST-DOB-YYYY-MM-DD``
     * PIC X(10)). Kept as ``String`` to preserve the exact wire format.
     */
    @Column(name = "cust_dob_yyyy_mm_dd", length = 10)
    private String custDobYyyyMmDd;

    /**
     * EFT account identifier (source ``CUST-EFT-ACCOUNT-ID`` PIC X(10)).
     *
     * :sensitive: PII. Encrypted at rest via {@link CryptoConverter} and never
     *             serialized to clients.
     */
    @Column(name = "cust_eft_account_id", length = 512)
    @Convert(converter = CryptoConverter.class)
    @JsonIgnore
    private String custEftAccountId;

    /** Primary card-holder indicator (source ``CUST-PRI-CARD-HOLDER-IND`` PIC X(01)). */
    @Column(name = "cust_pri_card_holder_ind", length = 1)
    private String custPriCardHolderInd;

    /** FICO credit score (source ``CUST-FICO-CREDIT-SCORE`` PIC 9(03)). */
    @Column(name = "cust_fico_credit_score", nullable = false)
    private Integer custFicoCreditScore;

    /**
     * Optimistic-locking version counter.
     *
     * :purpose: Reproduce the legacy ``COACTUPC`` read-snapshot-compare-rewrite
     *           concurrency guard for the customer aggregate as JPA optimistic
     *           locking; a concurrent modification raises an optimistic-lock
     *           failure that maps to the legacy "please review" outcome. There
     *           is no legacy column analogue.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Creates an empty customer instance.
     *
     * :purpose: No-argument constructor required by the JPA provider for entity
     *           instantiation and by mappers that populate fields via setters.
     */
    public Customer() {
        // Required by JPA; fields are populated by the provider or via setters.
    }

    /**
     * Returns the unique customer identifier.
     *
     * :return: the customer id, or ``null`` if unset.
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the unique customer identifier.
     *
     * :param custId: the customer id to assign.
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the customer first name.
     *
     * :return: the first name, or ``null`` if unset.
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Sets the customer first name.
     *
     * :param custFirstName: the first name to assign.
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * Returns the customer middle name.
     *
     * :return: the middle name, or ``null`` if unset.
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Sets the customer middle name.
     *
     * :param custMiddleName: the middle name to assign.
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * Returns the customer last name.
     *
     * :return: the last name, or ``null`` if unset.
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Sets the customer last name.
     *
     * :param custLastName: the last name to assign.
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * Returns address line 1.
     *
     * :return: address line 1, or ``null`` if unset.
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * Sets address line 1.
     *
     * :param custAddrLine1: the value to assign.
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * Returns address line 2.
     *
     * :return: address line 2, or ``null`` if unset.
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * Sets address line 2.
     *
     * :param custAddrLine2: the value to assign.
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * Returns address line 3.
     *
     * :return: address line 3, or ``null`` if unset.
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * Sets address line 3.
     *
     * :param custAddrLine3: the value to assign.
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * Returns the address state code.
     *
     * :return: the state code, or ``null`` if unset.
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * Sets the address state code.
     *
     * :param custAddrStateCd: the state code to assign.
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * Returns the address country code.
     *
     * :return: the country code, or ``null`` if unset.
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * Sets the address country code.
     *
     * :param custAddrCountryCd: the country code to assign.
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * Returns the address ZIP code.
     *
     * :return: the ZIP code, or ``null`` if unset.
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * Sets the address ZIP code.
     *
     * :param custAddrZip: the ZIP code to assign.
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * Returns the primary phone number.
     *
     * :return: the primary phone number, or ``null`` if unset.
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * Sets the primary phone number.
     *
     * :param custPhoneNum1: the phone number to assign.
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * Returns the secondary phone number.
     *
     * :return: the secondary phone number, or ``null`` if unset.
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * Sets the secondary phone number.
     *
     * :param custPhoneNum2: the phone number to assign.
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the social-security number.
     *
     * :sensitive: PII. Callers must not write the returned value to logs or any
     *             diagnostic output; use {@link #toString()} for safe rendering.
     * :return: the social-security number, or ``null`` if unset; never
     *          serialized to clients.
     */
    @JsonIgnore
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * Sets the social-security number.
     *
     * :sensitive: PII.
     * :param custSsn: the social-security number to assign.
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identifier.
     *
     * :sensitive: PII. Callers must not write the returned value to logs or any
     *             diagnostic output; use {@link #toString()} for safe rendering.
     * :return: the government-issued id, or ``null`` if unset; never serialized
     *          to clients.
     */
    @JsonIgnore
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Sets the government-issued identifier.
     *
     * :sensitive: PII.
     * :param custGovtIssuedId: the government-issued id to assign.
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * Returns the date of birth in ``YYYY-MM-DD`` form.
     *
     * :return: the date of birth string, or ``null`` if unset.
     */
    public String getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * Sets the date of birth in ``YYYY-MM-DD`` form.
     *
     * :param custDobYyyyMmDd: the date-of-birth string to assign.
     */
    public void setCustDobYyyyMmDd(String custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /**
     * Returns the EFT account identifier.
     *
     * :return: the EFT account id, or ``null`` if unset; never serialized to
     *          clients.
     */
    @JsonIgnore
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * Sets the EFT account identifier.
     *
     * :param custEftAccountId: the EFT account id to assign.
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * :return: the current version, or ``null`` before the row is first persisted.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * :param version: the version value to assign; normally managed by the JPA
     *        provider.
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Returns the primary card-holder indicator.
     *
     * :return: the indicator, or ``null`` if unset.
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * Sets the primary card-holder indicator.
     *
     * :param custPriCardHolderInd: the indicator to assign.
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * Returns the FICO credit score.
     *
     * :return: the FICO credit score, or ``null`` if unset.
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * Sets the FICO credit score.
     *
     * :param custFicoCreditScore: the FICO credit score to assign.
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    /**
     * Masks a sensitive value for safe diagnostic rendering.
     *
     * :param value: the raw sensitive value (may be ``null``).
     * :return: ``"null"`` when the input is ``null``; a fully asterisked token
     *          when the value has four or fewer characters; otherwise the value
     *          with every character except the trailing four replaced by ``*``.
     */
    private static String maskSensitive(String value) {
        if (value == null) {
            return "null";
        }
        int length = value.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + value.substring(length - 4);
    }

    /**
     * Renders a diagnostic representation of this customer.
     *
     * :output: A string containing all non-sensitive fields verbatim; the
     *          social-security number and government-issued id are masked so
     *          that raw PII never appears in logs or diagnostic output.
     * :return: the PII-safe string representation.
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId=" + custId
                + ", custFirstName='" + custFirstName + '\''
                + ", custMiddleName='" + custMiddleName + '\''
                + ", custLastName='" + custLastName + '\''
                + ", custAddrLine1='" + custAddrLine1 + '\''
                + ", custAddrLine2='" + custAddrLine2 + '\''
                + ", custAddrLine3='" + custAddrLine3 + '\''
                + ", custAddrStateCd='" + custAddrStateCd + '\''
                + ", custAddrCountryCd='" + custAddrCountryCd + '\''
                + ", custAddrZip='" + custAddrZip + '\''
                + ", custPhoneNum1='" + custPhoneNum1 + '\''
                + ", custPhoneNum2='" + custPhoneNum2 + '\''
                + ", custSsn='" + maskSensitive(custSsn) + '\''
                + ", custGovtIssuedId='" + maskSensitive(custGovtIssuedId) + '\''
                + ", custDobYyyyMmDd='" + custDobYyyyMmDd + '\''
                + ", custEftAccountId='" + maskSensitive(custEftAccountId) + '\''
                + ", custPriCardHolderInd='" + custPriCardHolderInd + '\''
                + ", custFicoCreditScore=" + custFicoCreditScore
                + '}';
    }

    /**
     * Compares this customer with another for identity equality.
     *
     * :param o: the object to compare against.
     * :return: ``true`` when the argument is a ``Customer`` with an equal
     *          :field:`custId`; ``false`` otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer other)) {
            return false;
        }
        return custId != null && custId.equals(other.getCustId());
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * :return: a proxy-stable, identity-consistent hash code.
     */
    @Override
    public int hashCode() {
        return Customer.class.hashCode();
    }
}

