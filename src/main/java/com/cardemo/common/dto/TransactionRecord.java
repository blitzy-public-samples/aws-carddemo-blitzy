package com.cardemo.common.dto;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.validation.constraints.Size;

/**
 * Transaction master record DTO — translated from CVTRA05Y.cpy (TRAN-RECORD, RECLN 350).
 *
 * <p>Represents the TRANSACT VSAM dataset record layout. Each field maps exactly to a
 * COBOL 05-level field in the TRAN-RECORD group (01-level, 350 bytes). The FILLER
 * field (PIC X(20)) at line 18 of the copybook is intentionally not mapped.
 *
 * <h3>COBOL Source Mapping (CVTRA05Y.cpy):</h3>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                PIC X(16).    → tranId       (String, max 16)
 *     05  TRAN-TYPE-CD           PIC X(02).    → tranTypeCd   (String, max 2)
 *     05  TRAN-CAT-CD            PIC 9(04).    → tranCatCd    (int, 4-digit)
 *     05  TRAN-SOURCE            PIC X(10).    → tranSource   (String, max 10)
 *     05  TRAN-DESC              PIC X(100).   → tranDesc     (String, max 100)
 *     05  TRAN-AMT               PIC S9(09)V99.→ tranAmt      (BigDecimal, scale 2)
 *     05  TRAN-MERCHANT-ID       PIC 9(09).    → tranMerchantId   (String, max 9)
 *     05  TRAN-MERCHANT-NAME     PIC X(50).    → tranMerchantName (String, max 50)
 *     05  TRAN-MERCHANT-CITY     PIC X(50).    → tranMerchantCity (String, max 50)
 *     05  TRAN-MERCHANT-ZIP      PIC X(10).    → tranMerchantZip  (String, max 10)
 *     05  TRAN-CARD-NUM          PIC X(16).    → tranCardNum  (String, max 16)
 *     05  TRAN-ORIG-TS           PIC X(26).    → tranOrigTs   (String, max 26)
 *     05  TRAN-PROC-TS           PIC X(26).    → tranProcTs   (String, max 26)
 *     05  FILLER                 PIC X(20).    → (not mapped)
 * </pre>
 *
 * <p>Identity field: {@code tranId} (COBOL primary key TRAN-ID).
 */
public class TransactionRecord {

    // ---------------------------------------------------------------
    // Fields — exact 1:1 mapping from CVTRA05Y.cpy (13 fields total)
    // ---------------------------------------------------------------

    /** TRAN-ID — PIC X(16), transaction identifier (primary key). */
    @Size(max = 16)
    private String tranId;

    /** TRAN-TYPE-CD — PIC X(02), transaction type code (maps to TransactionType enum). */
    @Size(max = 2)
    private String tranTypeCd;

    /** TRAN-CAT-CD — PIC 9(04), transaction category code (4-digit numeric, no decimal). */
    private int tranCatCd;

    /** TRAN-SOURCE — PIC X(10), transaction source identifier. */
    @Size(max = 10)
    private String tranSource;

    /** TRAN-DESC — PIC X(100), transaction description. */
    @Size(max = 100)
    private String tranDesc;

    /**
     * TRAN-AMT — PIC S9(09)V99, transaction amount.
     * Signed packed decimal with scale 2. MUST use BigDecimal — never float/double.
     */
    private BigDecimal tranAmt;

    /** TRAN-MERCHANT-ID — PIC 9(09), merchant identifier. */
    @Size(max = 9)
    private String tranMerchantId;

    /** TRAN-MERCHANT-NAME — PIC X(50), merchant name. */
    @Size(max = 50)
    private String tranMerchantName;

    /** TRAN-MERCHANT-CITY — PIC X(50), merchant city. */
    @Size(max = 50)
    private String tranMerchantCity;

    /** TRAN-MERCHANT-ZIP — PIC X(10), merchant zip code. */
    @Size(max = 10)
    private String tranMerchantZip;

    /** TRAN-CARD-NUM — PIC X(16), associated card number. */
    @Size(max = 16)
    private String tranCardNum;

    /**
     * TRAN-ORIG-TS — PIC X(26), original timestamp.
     * ISO-8601 extended format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 characters).
     * Matches the VSAM alternate index (AIX) key definition.
     */
    @Size(max = 26)
    private String tranOrigTs;

    /**
     * TRAN-PROC-TS — PIC X(26), processing timestamp.
     * ISO-8601 extended format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 characters).
     */
    @Size(max = 26)
    private String tranProcTs;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /**
     * Default no-argument constructor.
     */
    public TransactionRecord() {
        // Default constructor required for frameworks and serialization
    }

    /**
     * All-arguments constructor with all 13 fields.
     *
     * @param tranId           transaction identifier (PK)
     * @param tranTypeCd       transaction type code
     * @param tranCatCd        transaction category code
     * @param tranSource       transaction source
     * @param tranDesc         transaction description
     * @param tranAmt          transaction amount (BigDecimal, scale 2)
     * @param tranMerchantId   merchant identifier
     * @param tranMerchantName merchant name
     * @param tranMerchantCity merchant city
     * @param tranMerchantZip  merchant zip code
     * @param tranCardNum      associated card number
     * @param tranOrigTs       original timestamp (ISO-8601, 26 chars)
     * @param tranProcTs       processing timestamp (ISO-8601, 26 chars)
     */
    public TransactionRecord(String tranId, String tranTypeCd, int tranCatCd,
                             String tranSource, String tranDesc, BigDecimal tranAmt,
                             String tranMerchantId, String tranMerchantName,
                             String tranMerchantCity, String tranMerchantZip,
                             String tranCardNum, String tranOrigTs, String tranProcTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.tranMerchantId = tranMerchantId;
        this.tranMerchantName = tranMerchantName;
        this.tranMerchantCity = tranMerchantCity;
        this.tranMerchantZip = tranMerchantZip;
        this.tranCardNum = tranCardNum;
        this.tranOrigTs = tranOrigTs;
        this.tranProcTs = tranProcTs;
    }

    // ---------------------------------------------------------------
    // Getters and Setters — 13 pairs
    // ---------------------------------------------------------------

    /** Returns the transaction identifier (TRAN-ID). */
    public String getTranId() {
        return tranId;
    }

    /** Sets the transaction identifier (TRAN-ID). */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /** Returns the transaction type code (TRAN-TYPE-CD). */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /** Sets the transaction type code (TRAN-TYPE-CD). */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /** Returns the transaction category code (TRAN-CAT-CD). */
    public int getTranCatCd() {
        return tranCatCd;
    }

    /** Sets the transaction category code (TRAN-CAT-CD). */
    public void setTranCatCd(int tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /** Returns the transaction source (TRAN-SOURCE). */
    public String getTranSource() {
        return tranSource;
    }

    /** Sets the transaction source (TRAN-SOURCE). */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /** Returns the transaction description (TRAN-DESC). */
    public String getTranDesc() {
        return tranDesc;
    }

    /** Sets the transaction description (TRAN-DESC). */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /** Returns the transaction amount (TRAN-AMT, BigDecimal scale 2). */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /** Sets the transaction amount (TRAN-AMT, BigDecimal scale 2). */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /** Returns the merchant identifier (TRAN-MERCHANT-ID). */
    public String getTranMerchantId() {
        return tranMerchantId;
    }

    /** Sets the merchant identifier (TRAN-MERCHANT-ID). */
    public void setTranMerchantId(String tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    /** Returns the merchant name (TRAN-MERCHANT-NAME). */
    public String getTranMerchantName() {
        return tranMerchantName;
    }

    /** Sets the merchant name (TRAN-MERCHANT-NAME). */
    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    /** Returns the merchant city (TRAN-MERCHANT-CITY). */
    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    /** Sets the merchant city (TRAN-MERCHANT-CITY). */
    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    /** Returns the merchant zip code (TRAN-MERCHANT-ZIP). */
    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    /** Sets the merchant zip code (TRAN-MERCHANT-ZIP). */
    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    /** Returns the associated card number (TRAN-CARD-NUM). */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /** Sets the associated card number (TRAN-CARD-NUM). */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /** Returns the original timestamp (TRAN-ORIG-TS, ISO-8601, 26 chars). */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /** Sets the original timestamp (TRAN-ORIG-TS, ISO-8601, 26 chars). */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /** Returns the processing timestamp (TRAN-PROC-TS, ISO-8601, 26 chars). */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /** Sets the processing timestamp (TRAN-PROC-TS, ISO-8601, 26 chars). */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    // ---------------------------------------------------------------
    // toString, equals, hashCode
    // ---------------------------------------------------------------

    /**
     * Returns a string representation containing all 13 fields.
     */
    @Override
    public String toString() {
        return "TransactionRecord{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranAmt=" + tranAmt
                + ", tranMerchantId='" + tranMerchantId + '\''
                + ", tranMerchantName='" + tranMerchantName + '\''
                + ", tranMerchantCity='" + tranMerchantCity + '\''
                + ", tranMerchantZip='" + tranMerchantZip + '\''
                + ", tranCardNum='" + tranCardNum + '\''
                + ", tranOrigTs='" + tranOrigTs + '\''
                + ", tranProcTs='" + tranProcTs + '\''
                + '}';
    }

    /**
     * Equality based on {@code tranId} (COBOL primary key TRAN-ID).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionRecord that)) {
            return false;
        }
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Hash code based on {@code tranId} (COBOL primary key TRAN-ID).
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }
}
