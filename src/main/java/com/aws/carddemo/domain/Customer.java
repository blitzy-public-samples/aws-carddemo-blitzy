/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity for the master customer record.
 *
 * <p>Migrated from the COBOL copybook {@code CVCUS01Y.cpy} record
 * {@code CUSTOMER-RECORD} (fixed length 500 bytes) to the PostgreSQL table
 * {@code customer}. Field order is preserved from the copybook for
 * traceability. The trailing {@code FILLER PIC X(168)} in the copybook is
 * record padding only and is intentionally not mapped to a column.
 *
 * <p><strong>Schema ownership.</strong> The physical schema is owned by the
 * Flyway migration {@code V1__schema.sql}; Hibernate is configured with
 * {@code spring.jpa.hibernate.ddl-auto=validate} and therefore only validates
 * this mapping against the existing table. Column names, types, and lengths
 * declared here must match the Flyway DDL exactly.
 *
 * <p><strong>Sensitive PII.</strong> The fields {@link #custSsn} (social
 * security number), {@link #custGovtIssuedId} (government-issued identifier),
 * and {@link #custDob} (date of birth) are sensitive personal information.
 * They are deliberately excluded from {@link #toString()} and must never be
 * written to logs.
 *
 * <p>The primary key {@code cust_id} is a natural, externally seeded
 * identifier; there is no generated-value strategy and no optimistic-locking
 * version column on this table.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /** {@code CUST-ID PIC 9(09)} — primary key (natural, seeded). */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    @Column(name = "cust_first_name", length = 25)
    private String custFirstName;

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    @Column(name = "cust_middle_name", length = 25)
    private String custMiddleName;

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    @Column(name = "cust_last_name", length = 25)
    private String custLastName;

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    @Column(name = "cust_addr_line_1", length = 50)
    private String custAddrLine1;

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    @Column(name = "cust_addr_line_2", length = 50)
    private String custAddrLine2;

    /** {@code CUST-ADDR-LINE-3 PIC X(50)}. */
    @Column(name = "cust_addr_line_3", length = 50)
    private String custAddrLine3;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    @Column(name = "cust_addr_state_cd", length = 2)
    private String custAddrStateCd;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    @Column(name = "cust_addr_country_cd", length = 3)
    private String custAddrCountryCd;

    /** {@code CUST-ADDR-ZIP PIC X(10)}. */
    @Column(name = "cust_addr_zip", length = 10)
    private String custAddrZip;

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    @Column(name = "cust_phone_num_1", length = 15)
    private String custPhoneNum1;

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    @Column(name = "cust_phone_num_2", length = 15)
    private String custPhoneNum2;

    /**
     * {@code CUST-SSN PIC 9(09)} — social security number.
     *
     * <p>Sensitive PII. Stored as {@code String} to preserve leading zeros
     * that a numeric type would drop. Never logged; excluded from
     * {@link #toString()}.
     */
    @Column(name = "cust_ssn", length = 9)
    private String custSsn;

    /**
     * {@code CUST-GOVT-ISSUED-ID PIC X(20)} — government-issued identifier.
     *
     * <p>Sensitive PII. Never logged; excluded from {@link #toString()}.
     */
    @Column(name = "cust_govt_issued_id", length = 20)
    private String custGovtIssuedId;

    /**
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} — date of birth as text.
     *
     * <p>Sensitive PII. Retained as text (the legacy {@code YYYY-MM-DD} string
     * contract) rather than a temporal type. Never logged; excluded from
     * {@link #toString()}.
     */
    @Column(name = "cust_dob", length = 10)
    private String custDob;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    @Column(name = "cust_eft_account_id", length = 10)
    private String custEftAccountId;

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    @Column(name = "cust_pri_card_holder_ind", length = 1)
    private String custPriCardHolderInd;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    @Column(name = "cust_fico_credit_score")
    private Integer custFicoCreditScore;

    /**
     * Protected no-argument constructor required by the JPA provider.
     */
    protected Customer() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Convenience constructor that populates every mapped field.
     *
     * <p>Fields are assigned directly (not via setters) so that no overridable
     * method is invoked during construction of this non-final entity.
     *
     * @param custId               primary key ({@code CUST-ID})
     * @param custFirstName        first name ({@code CUST-FIRST-NAME})
     * @param custMiddleName       middle name ({@code CUST-MIDDLE-NAME})
     * @param custLastName         last name ({@code CUST-LAST-NAME})
     * @param custAddrLine1        address line 1 ({@code CUST-ADDR-LINE-1})
     * @param custAddrLine2        address line 2 ({@code CUST-ADDR-LINE-2})
     * @param custAddrLine3        address line 3 ({@code CUST-ADDR-LINE-3})
     * @param custAddrStateCd      state code ({@code CUST-ADDR-STATE-CD})
     * @param custAddrCountryCd    country code ({@code CUST-ADDR-COUNTRY-CD})
     * @param custAddrZip          ZIP code ({@code CUST-ADDR-ZIP})
     * @param custPhoneNum1        primary phone ({@code CUST-PHONE-NUM-1})
     * @param custPhoneNum2        secondary phone ({@code CUST-PHONE-NUM-2})
     * @param custSsn              social security number ({@code CUST-SSN}) — sensitive
     * @param custGovtIssuedId     government id ({@code CUST-GOVT-ISSUED-ID}) — sensitive
     * @param custDob              date of birth ({@code CUST-DOB-YYYY-MM-DD}) — sensitive
     * @param custEftAccountId     EFT account id ({@code CUST-EFT-ACCOUNT-ID})
     * @param custPriCardHolderInd primary card-holder indicator ({@code CUST-PRI-CARD-HOLDER-IND})
     * @param custFicoCreditScore  FICO credit score ({@code CUST-FICO-CREDIT-SCORE})
     */
    public Customer(Long custId,
                    String custFirstName,
                    String custMiddleName,
                    String custLastName,
                    String custAddrLine1,
                    String custAddrLine2,
                    String custAddrLine3,
                    String custAddrStateCd,
                    String custAddrCountryCd,
                    String custAddrZip,
                    String custPhoneNum1,
                    String custPhoneNum2,
                    String custSsn,
                    String custGovtIssuedId,
                    String custDob,
                    String custEftAccountId,
                    String custPriCardHolderInd,
                    Integer custFicoCreditScore) {
        this.custId = custId;
        this.custFirstName = custFirstName;
        this.custMiddleName = custMiddleName;
        this.custLastName = custLastName;
        this.custAddrLine1 = custAddrLine1;
        this.custAddrLine2 = custAddrLine2;
        this.custAddrLine3 = custAddrLine3;
        this.custAddrStateCd = custAddrStateCd;
        this.custAddrCountryCd = custAddrCountryCd;
        this.custAddrZip = custAddrZip;
        this.custPhoneNum1 = custPhoneNum1;
        this.custPhoneNum2 = custPhoneNum2;
        this.custSsn = custSsn;
        this.custGovtIssuedId = custGovtIssuedId;
        this.custDob = custDob;
        this.custEftAccountId = custEftAccountId;
        this.custPriCardHolderInd = custPriCardHolderInd;
        this.custFicoCreditScore = custFicoCreditScore;
    }

    /**
     * Returns the primary key.
     *
     * @return the customer id ({@code CUST-ID})
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the primary key.
     *
     * @param custId the customer id ({@code CUST-ID})
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * @return the first name ({@code CUST-FIRST-NAME})
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * @param custFirstName the first name ({@code CUST-FIRST-NAME})
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * @return the middle name ({@code CUST-MIDDLE-NAME})
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * @param custMiddleName the middle name ({@code CUST-MIDDLE-NAME})
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * @return the last name ({@code CUST-LAST-NAME})
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * @param custLastName the last name ({@code CUST-LAST-NAME})
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * @return the first address line ({@code CUST-ADDR-LINE-1})
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * @param custAddrLine1 the first address line ({@code CUST-ADDR-LINE-1})
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * @return the second address line ({@code CUST-ADDR-LINE-2})
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * @param custAddrLine2 the second address line ({@code CUST-ADDR-LINE-2})
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * @return the third address line ({@code CUST-ADDR-LINE-3})
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * @param custAddrLine3 the third address line ({@code CUST-ADDR-LINE-3})
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * @return the state code ({@code CUST-ADDR-STATE-CD})
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * @param custAddrStateCd the state code ({@code CUST-ADDR-STATE-CD})
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * @return the country code ({@code CUST-ADDR-COUNTRY-CD})
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * @param custAddrCountryCd the country code ({@code CUST-ADDR-COUNTRY-CD})
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * @return the ZIP code ({@code CUST-ADDR-ZIP})
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * @param custAddrZip the ZIP code ({@code CUST-ADDR-ZIP})
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * @return the primary phone number ({@code CUST-PHONE-NUM-1})
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * @param custPhoneNum1 the primary phone number ({@code CUST-PHONE-NUM-1})
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * @return the secondary phone number ({@code CUST-PHONE-NUM-2})
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * @param custPhoneNum2 the secondary phone number ({@code CUST-PHONE-NUM-2})
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the social security number.
     *
     * <p>Sensitive PII — callers must never write this value to logs.
     *
     * @return the social security number ({@code CUST-SSN})
     */
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * Sets the social security number (sensitive PII).
     *
     * @param custSsn the social security number ({@code CUST-SSN})
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identifier.
     *
     * <p>Sensitive PII — callers must never write this value to logs.
     *
     * @return the government-issued identifier ({@code CUST-GOVT-ISSUED-ID})
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Sets the government-issued identifier (sensitive PII).
     *
     * @param custGovtIssuedId the government-issued identifier ({@code CUST-GOVT-ISSUED-ID})
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * Returns the date of birth (text {@code YYYY-MM-DD}).
     *
     * <p>Sensitive PII — callers must never write this value to logs.
     *
     * @return the date of birth ({@code CUST-DOB-YYYY-MM-DD})
     */
    public String getCustDob() {
        return custDob;
    }

    /**
     * Sets the date of birth (sensitive PII, text {@code YYYY-MM-DD}).
     *
     * @param custDob the date of birth ({@code CUST-DOB-YYYY-MM-DD})
     */
    public void setCustDob(String custDob) {
        this.custDob = custDob;
    }

    /**
     * @return the EFT account id ({@code CUST-EFT-ACCOUNT-ID})
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * @param custEftAccountId the EFT account id ({@code CUST-EFT-ACCOUNT-ID})
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * @return the primary card-holder indicator ({@code CUST-PRI-CARD-HOLDER-IND})
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * @param custPriCardHolderInd the primary card-holder indicator ({@code CUST-PRI-CARD-HOLDER-IND})
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * @return the FICO credit score ({@code CUST-FICO-CREDIT-SCORE})
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * @param custFicoCreditScore the FICO credit score ({@code CUST-FICO-CREDIT-SCORE})
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    /**
     * Compares two customers by their primary key {@code custId}.
     *
     * <p>Uses a pattern {@code instanceof} check so that comparison remains
     * correct when one operand is a Hibernate lazy proxy.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Customer} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer other)) {
            return false;
        }
        return Objects.equals(custId, other.custId);
    }

    /**
     * Returns a hash code derived from the primary key {@code custId}.
     *
     * @return the hash code for this customer
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    /**
     * Returns a diagnostic string for this customer.
     *
     * <p><strong>Security:</strong> the sensitive fields {@code custSsn},
     * {@code custGovtIssuedId}, and {@code custDob} are intentionally omitted
     * and must never be included here.
     *
     * @return a string containing only non-sensitive fields
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId=" + custId
                + ", custFirstName=" + custFirstName
                + ", custMiddleName=" + custMiddleName
                + ", custLastName=" + custLastName
                + ", custAddrLine1=" + custAddrLine1
                + ", custAddrLine2=" + custAddrLine2
                + ", custAddrLine3=" + custAddrLine3
                + ", custAddrStateCd=" + custAddrStateCd
                + ", custAddrCountryCd=" + custAddrCountryCd
                + ", custAddrZip=" + custAddrZip
                + ", custPhoneNum1=" + custPhoneNum1
                + ", custPhoneNum2=" + custPhoneNum2
                + ", custEftAccountId=" + custEftAccountId
                + ", custPriCardHolderInd=" + custPriCardHolderInd
                + ", custFicoCreditScore=" + custFicoCreditScore
                + '}';
    }
}
