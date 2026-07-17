/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that maps the legacy COBOL copybook {@code DALYTRAN-RECORD}
 * (source {@code legacy/cpy/CVTRA06Y.cpy}, record length 350) onto the
 * PostgreSQL staging table {@code daily_transaction}.
 *
 * <p>This table is the <em>input staging area</em> consumed by the daily
 * transaction posting batch (COBOL program {@code CBTRN02C}). Each row is a raw,
 * unposted transaction awaiting validation and posting; staging rows may re-use
 * the same business identifier across posting runs, which is why the primary key
 * is a database-generated surrogate rather than the business id.</p>
 *
 * <p><strong>Schema ownership.</strong> The physical schema is created and owned
 * by the Flyway migration {@code V1__schema.sql}; Hibernate runs with
 * {@code ddl-auto: validate}. Consequently every table name, column name, type,
 * length, and the surrogate identity primary key declared here MUST match that
 * DDL exactly. In particular the merchant columns are named
 * {@code merchant_id}/{@code merchant_name}/{@code merchant_city}/{@code merchant_zip}
 * (with no {@code tran_} prefix), which intentionally differs from the posted
 * {@code transaction} table.</p>
 *
 * <p><strong>Monetary fidelity.</strong> The COBOL {@code DALYTRAN-AMT}
 * ({@code PIC S9(09)V99}) packed-decimal amount is modeled as a
 * {@link java.math.BigDecimal} at scale 2 mapped to {@code DECIMAL(11,2)}; binary
 * floating-point types are never used for monetary values.</p>
 *
 * <p>Copybook-to-column mapping (copybook field order preserved; the trailing
 * {@code FILLER PIC X(20)} is padding and is intentionally not mapped):</p>
 * <ul>
 *   <li>{@code DALYTRAN-ID}            {@code PIC X(16)}    &rarr; {@code dalytran_id}</li>
 *   <li>{@code DALYTRAN-TYPE-CD}       {@code PIC X(02)}    &rarr; {@code type_cd}</li>
 *   <li>{@code DALYTRAN-CAT-CD}        {@code PIC 9(04)}    &rarr; {@code cat_cd}</li>
 *   <li>{@code DALYTRAN-SOURCE}        {@code PIC X(10)}    &rarr; {@code tran_source}</li>
 *   <li>{@code DALYTRAN-DESC}          {@code PIC X(100)}   &rarr; {@code tran_desc}</li>
 *   <li>{@code DALYTRAN-AMT}           {@code PIC S9(09)V99}&rarr; {@code tran_amt}</li>
 *   <li>{@code DALYTRAN-MERCHANT-ID}   {@code PIC 9(09)}    &rarr; {@code merchant_id}</li>
 *   <li>{@code DALYTRAN-MERCHANT-NAME} {@code PIC X(50)}    &rarr; {@code merchant_name}</li>
 *   <li>{@code DALYTRAN-MERCHANT-CITY} {@code PIC X(50)}    &rarr; {@code merchant_city}</li>
 *   <li>{@code DALYTRAN-MERCHANT-ZIP}  {@code PIC X(10)}    &rarr; {@code merchant_zip}</li>
 *   <li>{@code DALYTRAN-CARD-NUM}      {@code PIC X(16)}    &rarr; {@code card_num}</li>
 *   <li>{@code DALYTRAN-ORIG-TS}       {@code PIC X(26)}    &rarr; {@code orig_ts}</li>
 *   <li>{@code DALYTRAN-PROC-TS}       {@code PIC X(26)}    &rarr; {@code proc_ts}</li>
 * </ul>
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    /**
     * Surrogate primary key. Maps to the DDL column
     * {@code id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY}; the database
     * always generates the value. This is intentionally <em>not</em> the business
     * identifier {@code DALYTRAN-ID}, because staging rows may re-use the same
     * business id across posting runs.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Business transaction identifier. COBOL {@code DALYTRAN-ID PIC X(16)}.
     * Stored as a regular, non-null column (not the primary key).
     */
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    /** Transaction type code. COBOL {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    /** Transaction category code. COBOL {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    @Column(name = "cat_cd")
    private Integer catCd;

    /** Transaction source. COBOL {@code DALYTRAN-SOURCE PIC X(10)}. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** Transaction description. COBOL {@code DALYTRAN-DESC PIC X(100)}. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /**
     * Transaction amount. COBOL {@code DALYTRAN-AMT PIC S9(09)V99} (packed
     * decimal). Modeled as {@link BigDecimal} at scale 2 mapped to
     * {@code DECIMAL(11,2)}; never a binary floating-point type.
     */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /**
     * Merchant identifier. COBOL {@code DALYTRAN-MERCHANT-ID PIC 9(09)}.
     * The column name intentionally has no {@code tran_} prefix.
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Merchant name. COBOL {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /** Merchant city. COBOL {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /** Merchant ZIP code. COBOL {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /** Card number. COBOL {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * Original transaction timestamp, retained as fixed-width text to preserve the
     * legacy contract. COBOL {@code DALYTRAN-ORIG-TS PIC X(26)}.
     */
    @Column(name = "orig_ts", length = 26)
    private String origTs;

    /**
     * Processing timestamp, retained as fixed-width text to preserve the legacy
     * contract. COBOL {@code DALYTRAN-PROC-TS PIC X(26)}.
     */
    @Column(name = "proc_ts", length = 26)
    private String procTs;

    /**
     * Protected no-argument constructor required by the JPA provider for
     * reflective instantiation. Not intended for direct application use.
     */
    protected DailyTransaction() {
        // Required by JPA/Hibernate.
    }

    /**
     * Convenience constructor populating the thirteen business fields of the
     * {@code DALYTRAN-RECORD} layout, in copybook order. The surrogate
     * {@link #id} is intentionally omitted because it is generated by the
     * database on insert.
     *
     * @param dalytranId   business transaction id ({@code DALYTRAN-ID})
     * @param typeCd       transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd        transaction category code ({@code DALYTRAN-CAT-CD})
     * @param tranSource   transaction source ({@code DALYTRAN-SOURCE})
     * @param tranDesc     transaction description ({@code DALYTRAN-DESC})
     * @param tranAmt      transaction amount ({@code DALYTRAN-AMT})
     * @param merchantId   merchant id ({@code DALYTRAN-MERCHANT-ID})
     * @param merchantName merchant name ({@code DALYTRAN-MERCHANT-NAME})
     * @param merchantCity merchant city ({@code DALYTRAN-MERCHANT-CITY})
     * @param merchantZip  merchant ZIP ({@code DALYTRAN-MERCHANT-ZIP})
     * @param cardNum      card number ({@code DALYTRAN-CARD-NUM})
     * @param origTs       original timestamp text ({@code DALYTRAN-ORIG-TS})
     * @param procTs       processing timestamp text ({@code DALYTRAN-PROC-TS})
     */
    public DailyTransaction(String dalytranId,
                            String typeCd,
                            Integer catCd,
                            String tranSource,
                            String tranDesc,
                            BigDecimal tranAmt,
                            Long merchantId,
                            String merchantName,
                            String merchantCity,
                            String merchantZip,
                            String cardNum,
                            String origTs,
                            String procTs) {
        this.dalytranId = dalytranId;
        this.typeCd = typeCd;
        this.catCd = catCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns the surrogate primary key, or {@code null} if this instance has not
     * yet been persisted.
     *
     * @return the generated identity value, or {@code null} when transient
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the surrogate primary key. Normally only the JPA provider assigns this
     * value; exposed to support detached-entity and test scenarios.
     *
     * @param id the surrogate identity value
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the business transaction identifier ({@code DALYTRAN-ID}).
     *
     * @return the business transaction id
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * Sets the business transaction identifier ({@code DALYTRAN-ID}).
     *
     * @param dalytranId the business transaction id
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * Returns the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @return the transaction type code
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @param typeCd the transaction type code
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @return the transaction category code
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @param catCd the transaction category code
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Returns the transaction source ({@code DALYTRAN-SOURCE}).
     *
     * @return the transaction source
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Sets the transaction source ({@code DALYTRAN-SOURCE}).
     *
     * @param tranSource the transaction source
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description ({@code DALYTRAN-DESC}).
     *
     * @return the transaction description
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Sets the transaction description ({@code DALYTRAN-DESC}).
     *
     * @param tranDesc the transaction description
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the transaction amount ({@code DALYTRAN-AMT}) at scale 2.
     *
     * @return the transaction amount
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the transaction amount ({@code DALYTRAN-AMT}). Callers are responsible
     * for supplying a value at scale 2 to preserve monetary fidelity.
     *
     * @param tranAmt the transaction amount
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @return the merchant id
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @param merchantId the merchant id
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @return the merchant name
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @param merchantName the merchant name
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @return the merchant city
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @param merchantCity the merchant city
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @return the merchant ZIP code
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @param merchantZip the merchant ZIP code
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @param cardNum the card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the original transaction timestamp text ({@code DALYTRAN-ORIG-TS}).
     *
     * @return the original timestamp text
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the original transaction timestamp text ({@code DALYTRAN-ORIG-TS}).
     *
     * @param origTs the original timestamp text
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp text ({@code DALYTRAN-PROC-TS}).
     *
     * @return the processing timestamp text
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp text ({@code DALYTRAN-PROC-TS}).
     *
     * @param procTs the processing timestamp text
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Equality is based solely on the surrogate {@link #id}. Two transient
     * instances (both with a {@code null} id) are never considered equal, which
     * avoids collapsing distinct unpersisted staging rows.
     *
     * @param o the object to compare with
     * @return {@code true} only if {@code o} is a {@code DailyTransaction} with a
     *         non-null id equal to this instance's id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    /**
     * Hash code derived from the surrogate {@link #id}. A transient instance
     * (null id) hashes to zero, consistent with {@link #equals(Object)}.
     *
     * @return the hash code for this instance
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Returns a diagnostic representation of this staging record. Intended for
     * logging and debugging of the posting batch input.
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "id=" + id
                + ", dalytranId='" + dalytranId + '\''
                + ", typeCd='" + typeCd + '\''
                + ", catCd=" + catCd
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranAmt=" + tranAmt
                + ", merchantId=" + merchantId
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", cardNum='" + cardNum + '\''
                + ", origTs='" + origTs + '\''
                + ", procTs='" + procTs + '\''
                + '}';
    }
}
