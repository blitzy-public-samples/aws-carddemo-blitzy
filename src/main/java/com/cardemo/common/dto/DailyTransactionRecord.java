package com.cardemo.common.dto;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.validation.constraints.Size;

/**
 * Daily transaction feed record DTO — translated from CVTRA06Y.cpy
 * (DALYTRAN-RECORD, RECLN 350).
 *
 * <p>Represents the daily transaction feed input record used by the DALYTRAN
 * VSAM dataset. This DTO has the same structure as {@code TransactionRecord}
 * but with the {@code DALYTRAN-} prefix on all field names, faithfully
 * preserving the COBOL copybook naming convention.</p>
 *
 * <h3>COBOL Source (CVTRA06Y.cpy):</h3>
 * <pre>
 * 01  DALYTRAN-RECORD.
 *     05  DALYTRAN-ID                PIC X(16).
 *     05  DALYTRAN-TYPE-CD           PIC X(02).
 *     05  DALYTRAN-CAT-CD            PIC 9(04).
 *     05  DALYTRAN-SOURCE            PIC X(10).
 *     05  DALYTRAN-DESC              PIC X(100).
 *     05  DALYTRAN-AMT               PIC S9(09)V99.
 *     05  DALYTRAN-MERCHANT-ID       PIC 9(09).
 *     05  DALYTRAN-MERCHANT-NAME     PIC X(50).
 *     05  DALYTRAN-MERCHANT-CITY     PIC X(50).
 *     05  DALYTRAN-MERCHANT-ZIP      PIC X(10).
 *     05  DALYTRAN-CARD-NUM          PIC X(16).
 *     05  DALYTRAN-ORIG-TS           PIC X(26).
 *     05  DALYTRAN-PROC-TS           PIC X(26).
 *     05  FILLER                     PIC X(20).
 * </pre>
 *
 * <p>FILLER (PIC X(20)) is not mapped — padding only.
 * 13 fields total. {@code dalytranAmt} uses {@link BigDecimal} with scale 2
 * to preserve exact COBOL packed-decimal (COMP-3) semantics.</p>
 *
 * @see com.cardemo.entity.DailyTransaction
 */
public class DailyTransactionRecord {

    // -------------------------------------------------------------------------
    // Fields — exact COBOL-to-Java mapping from CVTRA06Y.cpy lines 5–17
    // -------------------------------------------------------------------------

    /** DALYTRAN-ID — PIC X(16), daily transaction identifier. */
    @Size(max = 16)
    private String dalytranId;

    /** DALYTRAN-TYPE-CD — PIC X(02), transaction type code. */
    @Size(max = 2)
    private String dalytranTypeCd;

    /** DALYTRAN-CAT-CD — PIC 9(04), transaction category code. */
    private int dalytranCatCd;

    /** DALYTRAN-SOURCE — PIC X(10), transaction source identifier. */
    @Size(max = 10)
    private String dalytranSource;

    /** DALYTRAN-DESC — PIC X(100), transaction description. */
    @Size(max = 100)
    private String dalytranDesc;

    /**
     * DALYTRAN-AMT — PIC S9(09)V99, transaction amount.
     * <p>Uses {@link BigDecimal} with scale 2 to preserve exact COBOL
     * packed-decimal semantics. Never use {@code float} or {@code double}
     * for monetary fields.</p>
     */
    private BigDecimal dalytranAmt;

    /** DALYTRAN-MERCHANT-ID — PIC 9(09), merchant identifier. */
    @Size(max = 9)
    private String dalytranMerchantId;

    /** DALYTRAN-MERCHANT-NAME — PIC X(50), merchant name. */
    @Size(max = 50)
    private String dalytranMerchantName;

    /** DALYTRAN-MERCHANT-CITY — PIC X(50), merchant city. */
    @Size(max = 50)
    private String dalytranMerchantCity;

    /** DALYTRAN-MERCHANT-ZIP — PIC X(10), merchant zip code. */
    @Size(max = 10)
    private String dalytranMerchantZip;

    /** DALYTRAN-CARD-NUM — PIC X(16), associated card number. */
    @Size(max = 16)
    private String dalytranCardNum;

    /**
     * DALYTRAN-ORIG-TS — PIC X(26), original timestamp.
     * <p>ISO-8601 extended format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm}
     * (26 characters).</p>
     */
    @Size(max = 26)
    private String dalytranOrigTs;

    /**
     * DALYTRAN-PROC-TS — PIC X(26), processing timestamp.
     * <p>ISO-8601 extended format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm}
     * (26 characters).</p>
     */
    @Size(max = 26)
    private String dalytranProcTs;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Default no-argument constructor.
     */
    public DailyTransactionRecord() {
        // Default constructor required for frameworks and serialization
    }

    /**
     * All-arguments constructor initialising every field of the daily
     * transaction record.
     *
     * @param dalytranId           daily transaction identifier (max 16 chars)
     * @param dalytranTypeCd       transaction type code (max 2 chars)
     * @param dalytranCatCd        transaction category code (4 digits)
     * @param dalytranSource       transaction source (max 10 chars)
     * @param dalytranDesc         transaction description (max 100 chars)
     * @param dalytranAmt          transaction amount ({@link BigDecimal}, scale 2)
     * @param dalytranMerchantId   merchant identifier (max 9 chars)
     * @param dalytranMerchantName merchant name (max 50 chars)
     * @param dalytranMerchantCity merchant city (max 50 chars)
     * @param dalytranMerchantZip  merchant zip code (max 10 chars)
     * @param dalytranCardNum      associated card number (max 16 chars)
     * @param dalytranOrigTs       original timestamp (max 26 chars, ISO-8601)
     * @param dalytranProcTs       processing timestamp (max 26 chars, ISO-8601)
     */
    public DailyTransactionRecord(String dalytranId,
                                  String dalytranTypeCd,
                                  int dalytranCatCd,
                                  String dalytranSource,
                                  String dalytranDesc,
                                  BigDecimal dalytranAmt,
                                  String dalytranMerchantId,
                                  String dalytranMerchantName,
                                  String dalytranMerchantCity,
                                  String dalytranMerchantZip,
                                  String dalytranCardNum,
                                  String dalytranOrigTs,
                                  String dalytranProcTs) {
        this.dalytranId = dalytranId;
        this.dalytranTypeCd = dalytranTypeCd;
        this.dalytranCatCd = dalytranCatCd;
        this.dalytranSource = dalytranSource;
        this.dalytranDesc = dalytranDesc;
        this.dalytranAmt = dalytranAmt;
        this.dalytranMerchantId = dalytranMerchantId;
        this.dalytranMerchantName = dalytranMerchantName;
        this.dalytranMerchantCity = dalytranMerchantCity;
        this.dalytranMerchantZip = dalytranMerchantZip;
        this.dalytranCardNum = dalytranCardNum;
        this.dalytranOrigTs = dalytranOrigTs;
        this.dalytranProcTs = dalytranProcTs;
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    /** Returns the daily transaction identifier. */
    public String getDalytranId() {
        return dalytranId;
    }

    /** Sets the daily transaction identifier. */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /** Returns the transaction type code. */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /** Sets the transaction type code. */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /** Returns the transaction category code. */
    public int getDalytranCatCd() {
        return dalytranCatCd;
    }

    /** Sets the transaction category code. */
    public void setDalytranCatCd(int dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /** Returns the transaction source. */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /** Sets the transaction source. */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /** Returns the transaction description. */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /** Sets the transaction description. */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /** Returns the transaction amount as {@link BigDecimal} (scale 2). */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /** Sets the transaction amount. Must be {@link BigDecimal} with scale 2. */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /** Returns the merchant identifier. */
    public String getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /** Sets the merchant identifier. */
    public void setDalytranMerchantId(String dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /** Returns the merchant name. */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /** Sets the merchant name. */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /** Returns the merchant city. */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /** Sets the merchant city. */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /** Returns the merchant zip code. */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /** Sets the merchant zip code. */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /** Returns the associated card number. */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /** Sets the associated card number. */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /** Returns the original timestamp (ISO-8601, 26 chars). */
    public String getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /** Sets the original timestamp. */
    public void setDalytranOrigTs(String dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /** Returns the processing timestamp (ISO-8601, 26 chars). */
    public String getDalytranProcTs() {
        return dalytranProcTs;
    }

    /** Sets the processing timestamp. */
    public void setDalytranProcTs(String dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    /**
     * Returns a string representation of this daily transaction record,
     * including all 13 fields.
     */
    @Override
    public String toString() {
        return "DailyTransactionRecord{"
                + "dalytranId='" + dalytranId + '\''
                + ", dalytranTypeCd='" + dalytranTypeCd + '\''
                + ", dalytranCatCd=" + dalytranCatCd
                + ", dalytranSource='" + dalytranSource + '\''
                + ", dalytranDesc='" + dalytranDesc + '\''
                + ", dalytranAmt=" + dalytranAmt
                + ", dalytranMerchantId='" + dalytranMerchantId + '\''
                + ", dalytranMerchantName='" + dalytranMerchantName + '\''
                + ", dalytranMerchantCity='" + dalytranMerchantCity + '\''
                + ", dalytranMerchantZip='" + dalytranMerchantZip + '\''
                + ", dalytranCardNum='" + dalytranCardNum + '\''
                + ", dalytranOrigTs='" + dalytranOrigTs + '\''
                + ", dalytranProcTs='" + dalytranProcTs + '\''
                + '}';
    }

    /**
     * Compares this record with another for equality based on
     * {@code dalytranId} as the identity field.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects have the same {@code dalytranId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransactionRecord that)) {
            return false;
        }
        return Objects.equals(dalytranId, that.dalytranId);
    }

    /**
     * Returns a hash code based on {@code dalytranId}.
     *
     * @return hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }
}
