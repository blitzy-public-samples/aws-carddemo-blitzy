package com.carddemo.cobol;

/**
 * Total digits, digits after the decimal point, and byte widths for every COBOL Picture clause
 * this platform reads or writes.
 *
 * <p>{@link CobolDecimal} and the equivalence fixture parser take their numbers from here, so one
 * field carries one scale wherever it is read. Constants are grouped by source copybook and appear
 * in copybook line order. Each group opens with its record length. Every constant names its source
 * file and line.</p>
 *
 * <p>Two conventions run through the file. Precision counts total digits and scale counts digits
 * after the decimal point, so {@code PIC S9(10)V99} has precision 12 and scale 2. A signed
 * display numeric of n digits occupies n bytes on a fixture record, with the sign overpunched on
 * the trailing digit. Each money field defines its byte width from its precision.</p>
 *
 * <p>Identifier fields keep leading-zero semantics. Several COBOL programs compare an identifier
 * as text, and a target column holds the padded digits, not a trimmed integer.</p>
 *
 * <p>Field names match the copybooks with two exceptions. The account and card expiration date
 * constants use the correct spelling; the copybooks spell both fields {@code EXPIRAION}.</p>

 */
public final class PicClause {

    // Account record. app/cpy/CVACT01Y.cpy, 01 ACCOUNT-RECORD at L4.

    /**
     * Byte length of one {@code ACCOUNT-RECORD}. The copybook header at
     * {@code app/cpy/CVACT01Y.cpy:L2} states RECLN 300.
     */
    public static final int ACCOUNT_RECORD_LENGTH = 300;

    /** Byte width of {@code ACCT-ID}, {@code PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. */
    public static final int ACCT_ID_WIDTH = 11;

    /**
     * Byte width of {@code ACCT-ACTIVE-STATUS}, {@code PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy:L6}. The field holds {@code Y} or {@code N}.
     */
    public static final int ACCT_ACTIVE_STATUS_WIDTH = 1;

    /**
     * Total digits in {@code ACCT-CURR-BAL}, {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7}.
     */
    public static final int ACCT_CURR_BAL_PRECISION = 12;

    /** Digits after the decimal point in {@code ACCT-CURR-BAL}, {@code app/cpy/CVACT01Y.cpy:L7}. */
    public static final int ACCT_CURR_BAL_SCALE = 2;

    /** Byte width of {@code ACCT-CURR-BAL} on a fixture record, {@code app/cpy/CVACT01Y.cpy:L7}. */
    public static final int ACCT_CURR_BAL_WIDTH = ACCT_CURR_BAL_PRECISION;

    /**
     * Total digits in {@code ACCT-CREDIT-LIMIT}, {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    public static final int ACCT_CREDIT_LIMIT_PRECISION = 12;

    /**
     * Digits after the decimal point in {@code ACCT-CREDIT-LIMIT}, {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    public static final int ACCT_CREDIT_LIMIT_SCALE = 2;

    /**
     * Byte width of {@code ACCT-CREDIT-LIMIT} on a fixture record, {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    public static final int ACCT_CREDIT_LIMIT_WIDTH = ACCT_CREDIT_LIMIT_PRECISION;

    /**
     * Total digits in {@code ACCT-CASH-CREDIT-LIMIT}, {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L9}.
     */
    public static final int ACCT_CASH_CREDIT_LIMIT_PRECISION = 12;

    /**
     * Digits after the decimal point in {@code ACCT-CASH-CREDIT-LIMIT},
     * {@code app/cpy/CVACT01Y.cpy:L9}.
     */
    public static final int ACCT_CASH_CREDIT_LIMIT_SCALE = 2;

    /**
     * Byte width of {@code ACCT-CASH-CREDIT-LIMIT} on a fixture record,
     * {@code app/cpy/CVACT01Y.cpy:L9}.
     */
    public static final int ACCT_CASH_CREDIT_LIMIT_WIDTH = ACCT_CASH_CREDIT_LIMIT_PRECISION;

    /**
     * Byte width of {@code ACCT-OPEN-DATE}, {@code PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L10}.
     */
    public static final int ACCT_OPEN_DATE_WIDTH = 10;

    /**
     * Byte width of the account expiration date, {@code PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L11}. The copybook spells the field {@code ACCT-EXPIRAION-DATE}.
     */
    public static final int ACCT_EXPIRATION_DATE_WIDTH = 10;

    /** Target column type for the account expiration date. */
    public static final String ACCT_EXPIRATION_DATE_COLUMN_TYPE = "VARCHAR(10)";

    /**
     * Byte width of {@code ACCT-REISSUE-DATE}, {@code PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L12}.
     */
    public static final int ACCT_REISSUE_DATE_WIDTH = 10;

    /**
     * Total digits in {@code ACCT-CURR-CYC-CREDIT}, {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13}.
     */
    public static final int ACCT_CURR_CYC_CREDIT_PRECISION = 12;

    /**
     * Digits after the decimal point in {@code ACCT-CURR-CYC-CREDIT},
     * {@code app/cpy/CVACT01Y.cpy:L13}.
     */
    public static final int ACCT_CURR_CYC_CREDIT_SCALE = 2;

    /**
     * Byte width of {@code ACCT-CURR-CYC-CREDIT} on a fixture record,
     * {@code app/cpy/CVACT01Y.cpy:L13}.
     */
    public static final int ACCT_CURR_CYC_CREDIT_WIDTH = ACCT_CURR_CYC_CREDIT_PRECISION;

    /**
     * Total digits in {@code ACCT-CURR-CYC-DEBIT}, {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    public static final int ACCT_CURR_CYC_DEBIT_PRECISION = 12;

    /**
     * Digits after the decimal point in {@code ACCT-CURR-CYC-DEBIT},
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    public static final int ACCT_CURR_CYC_DEBIT_SCALE = 2;

    /**
     * Byte width of {@code ACCT-CURR-CYC-DEBIT} on a fixture record,
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    public static final int ACCT_CURR_CYC_DEBIT_WIDTH = ACCT_CURR_CYC_DEBIT_PRECISION;

    /**
     * Byte width of {@code ACCT-ADDR-ZIP}, {@code PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L15}.
     */
    public static final int ACCT_ADDR_ZIP_WIDTH = 10;

    /**
     * Byte width of {@code ACCT-GROUP-ID}, {@code PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L16}.
     * The interest program keys the disclosure group lookup on this field.
     */
    public static final int ACCT_GROUP_ID_WIDTH = 10;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(178)} at
     * {@code app/cpy/CVACT01Y.cpy:L17}. The account table models no column for these bytes.
     */
    public static final int ACCOUNT_RECORD_FILLER_WIDTH = 178;

    // Card record. app/cpy/CVACT02Y.cpy, 01 CARD-RECORD at L4.

    /**
     * Byte length of one {@code CARD-RECORD}. The copybook header at
     * {@code app/cpy/CVACT02Y.cpy:L2} states RECLN 150.
     */
    public static final int CARD_RECORD_LENGTH = 150;

    /**
     * Byte width of {@code CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. This
     * is the full Primary Account Number, and it is the key of the card cross-reference file.
     */
    public static final int CARD_NUM_WIDTH = 16;

    /** Byte width of {@code CARD-ACCT-ID}, {@code PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. */
    public static final int CARD_ACCT_ID_WIDTH = 11;

    /** Byte width of {@code CARD-CVV-CD}, {@code PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. */
    public static final int CARD_CVV_CD_WIDTH = 3;

    /**
     * Byte width of {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    public static final int CARD_EMBOSSED_NAME_WIDTH = 50;

    /**
     * Byte width of the card expiration date, {@code PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}.
     * The copybook spells the field {@code CARD-EXPIRAION-DATE}.
     */
    public static final int CARD_EXPIRATION_DATE_WIDTH = 10;

    public static final String CARD_EXPIRATION_DATE_COLUMN_TYPE = "DATE";

    /**
     * Byte width of {@code CARD-EXPIRY-YEAR} inside the card expiration date,
     * {@code PIC X(4)} at {@code app/cbl/COCRDUPC.cbl:L117}.
     */
    public static final int CARD_EXPIRATION_DATE_YEAR_WIDTH = 4;

    /**
     * Byte offset of {@code CARD-EXPIRY-YEAR} inside the card expiration date,
     * {@code app/cbl/COCRDUPC.cbl:L117}.
     */
    public static final int CARD_EXPIRATION_DATE_YEAR_OFFSET = 0;

    /**
     * Byte width of {@code CARD-EXPIRY-MONTH} inside the card expiration date,
     * {@code PIC X(2)} at {@code app/cbl/COCRDUPC.cbl:L119}.
     */
    public static final int CARD_EXPIRATION_DATE_MONTH_WIDTH = 2;

    /**
     * Byte offset of {@code CARD-EXPIRY-MONTH} inside the card expiration date,
     * {@code app/cbl/COCRDUPC.cbl:L119}.
     */
    public static final int CARD_EXPIRATION_DATE_MONTH_OFFSET = 5;

    /**
     * Byte width of {@code CARD-EXPIRY-DAY} inside the card expiration date,
     * {@code PIC X(2)} at {@code app/cbl/COCRDUPC.cbl:L121}.
     */
    public static final int CARD_EXPIRATION_DATE_DAY_WIDTH = 2;

    /**
     * Byte offset of {@code CARD-EXPIRY-DAY} inside the card expiration date,
     * {@code app/cbl/COCRDUPC.cbl:L121}.
     */
    public static final int CARD_EXPIRATION_DATE_DAY_OFFSET = 8;

    /**
     * Byte width of each separator inside the card expiration date, {@code PIC X(1)} at
     * {@code app/cbl/COCRDUPC.cbl:L118} and {@code app/cbl/COCRDUPC.cbl:L120}.
     */
    public static final int CARD_EXPIRATION_DATE_SEPARATOR_WIDTH = 1;

    /**
     * Byte width of {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}. No program reads this field on the posting path.
     */
    public static final int CARD_ACTIVE_STATUS_WIDTH = 1;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(59)} at
     * {@code app/cpy/CVACT02Y.cpy:L11}. The card table models no column for these bytes.
     */
    public static final int CARD_RECORD_FILLER_WIDTH = 59;

    // Card cross-reference record. app/cpy/CVACT03Y.cpy, 01 CARD-XREF-RECORD at L4.

    /**
     * Byte length of one {@code CARD-XREF-RECORD}. The copybook header at
     * {@code app/cpy/CVACT03Y.cpy:L2} states RECLN 50.
     */
    public static final int CARD_XREF_RECORD_LENGTH = 50;

    /**
     * Byte width of {@code XREF-CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}.
     */
    public static final int XREF_CARD_NUM_WIDTH = 16;

    /** Byte width of {@code XREF-CUST-ID}, {@code PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}. */
    public static final int XREF_CUST_ID_WIDTH = 9;

    /** Byte width of {@code XREF-ACCT-ID}, {@code PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. */
    public static final int XREF_ACCT_ID_WIDTH = 11;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(14)} at
     * {@code app/cpy/CVACT03Y.cpy:L8}. The cross-reference table models no column for these bytes.
     */
    public static final int CARD_XREF_RECORD_FILLER_WIDTH = 14;

    // Customer record. app/cpy/CVCUS01Y.cpy, 01 CUSTOMER-RECORD at L4. This copybook is the
    // canonical customer layout; app/cpy/CUSTREC.cpy is the divergent fork.

    /**
     * Byte length of one {@code CUSTOMER-RECORD}. The copybook header at
     * {@code app/cpy/CVCUS01Y.cpy:L2} states RECLN 500.
     */
    public static final int CUSTOMER_RECORD_LENGTH = 500;

    /**
     * Byte width of {@code CUST-ID}, {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}. The
     * account view program carries the cross-reference customer identifier at
     * {@code app/cbl/COACTVWC.cbl:L739} and keys its customer read on it at
     * {@code app/cbl/COACTVWC.cbl:L828-L829}.
     */
    public static final int CUST_ID_WIDTH = 9;

    /**
     * Byte width of {@code CUST-FIRST-NAME}, {@code PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L6}.
     */
    public static final int CUST_FIRST_NAME_WIDTH = 25;

    /**
     * Byte width of {@code CUST-MIDDLE-NAME}, {@code PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L7}.
     */
    public static final int CUST_MIDDLE_NAME_WIDTH = 25;

    /**
     * Byte width of {@code CUST-LAST-NAME}, {@code PIC X(25)} at
     * {@code app/cpy/CVCUS01Y.cpy:L8}.
     */
    public static final int CUST_LAST_NAME_WIDTH = 25;

    /**
     * Byte width of {@code CUST-ADDR-LINE-1}, {@code PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L9}.
     */
    public static final int CUST_ADDR_LINE_1_WIDTH = 50;

    /**
     * Byte width of {@code CUST-ADDR-LINE-2}, {@code PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L10}.
     */
    public static final int CUST_ADDR_LINE_2_WIDTH = 50;

    /**
     * Byte width of {@code CUST-ADDR-LINE-3}, {@code PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L11}.
     */
    public static final int CUST_ADDR_LINE_3_WIDTH = 50;

    /**
     * Byte width of {@code CUST-ADDR-STATE-CD}, {@code PIC X(02)} at
     * {@code app/cpy/CVCUS01Y.cpy:L12}. The state-code edit at
     * {@code app/cbl/COACTUPC.cbl:L2493} tests this field against the 56 codes that
     * {@code app/cpy/CSLKPCDY.cpy} declares at lines 1014 through 1069.
     */
    public static final int CUST_ADDR_STATE_CD_WIDTH = 2;

    /**
     * Byte width of {@code CUST-ADDR-COUNTRY-CD}, {@code PIC X(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L13}.
     */
    public static final int CUST_ADDR_COUNTRY_CD_WIDTH = 3;

    /**
     * Byte width of {@code CUST-ADDR-ZIP}, {@code PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L14}. The state-and-zip edit at
     * {@code app/cbl/COACTUPC.cbl:L2537-L2540} reads its first two digits.
     */
    public static final int CUST_ADDR_ZIP_WIDTH = 10;

    /**
     * Byte width of {@code CUST-PHONE-NUM-1}, {@code PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy:L15}. The telephone edit reads the field as an area code, a
     * prefix, and a line number.
     */
    public static final int CUST_PHONE_NUM_1_WIDTH = 15;

    /**
     * Byte width of {@code CUST-PHONE-NUM-2}, {@code PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy:L16}.
     */
    public static final int CUST_PHONE_NUM_2_WIDTH = 15;

    /**
     * Byte width of {@code CUST-SSN}, {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17}. The
     * Social Security Number edit at {@code app/cbl/COACTUPC.cbl:L2431-L2491} reads the field as
     * parts of three, two, and four digits. This field reaches no event, no log line, and no
     * response body.
     */
    public static final int CUST_SSN_WIDTH = 9;

    /**
     * Byte width of {@code CUST-GOVT-ISSUED-ID}, {@code PIC X(20)} at
     * {@code app/cpy/CVCUS01Y.cpy:L18}. This field reaches no event, no log line, and no response
     * body.
     */
    public static final int CUST_GOVT_ISSUED_ID_WIDTH = 20;

    /**
     * Byte width of {@code CUST-DOB-YYYY-MM-DD}, {@code PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L19}. The field name carries the format, and the column holds
     * the ten characters as text. The date-of-birth edit at
     * {@code app/cpy/CSUTLDPY.cpy:L341-L370} reads this field.
     */
    public static final int CUST_DOB_WIDTH = 10;

    /**
     * Byte width of {@code CUST-EFT-ACCOUNT-ID}, {@code PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L20}.
     */
    public static final int CUST_EFT_ACCOUNT_ID_WIDTH = 10;

    /**
     * Byte width of {@code CUST-PRI-CARD-HOLDER-IND}, {@code PIC X(01)} at
     * {@code app/cpy/CVCUS01Y.cpy:L21}.
     */
    public static final int CUST_PRI_CARD_HOLDER_IND_WIDTH = 1;

    /**
     * Byte width of {@code CUST-FICO-CREDIT-SCORE}, {@code PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L22}. The account update program edits this width at
     * {@code app/cbl/COACTUPC.cbl:L1549} and then checks the range at
     * {@code app/cbl/COACTUPC.cbl:L1554}, where condition name {@code FICO-RANGE-IS-VALID} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} accepts 300 through 850 inclusive.
     */
    public static final int CUST_FICO_CREDIT_SCORE_WIDTH = 3;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(168)} at
     * {@code app/cpy/CVCUS01Y.cpy:L23}. The customer table models no column for these bytes.
     */
    public static final int CUSTOMER_RECORD_FILLER_WIDTH = 168;

    // Transaction category balance record. app/cpy/CVTRA01Y.cpy,
    // 01 TRAN-CAT-BAL-RECORD at L4.

    /**
     * Byte length of one {@code TRAN-CAT-BAL-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA01Y.cpy:L2} states RECLN 50.
     */
    public static final int TRAN_CAT_BAL_RECORD_LENGTH = 50;

    /**
     * Byte width of {@code TRANCAT-ACCT-ID}, {@code PIC 9(11)} at
     * {@code app/cpy/CVTRA01Y.cpy:L6}. First part of the composite key.
     */
    public static final int TRANCAT_ACCT_ID_WIDTH = 11;

    /**
     * Byte width of {@code TRANCAT-TYPE-CD}, {@code PIC X(02)} at
     * {@code app/cpy/CVTRA01Y.cpy:L7}. Second part of the composite key.
     */
    public static final int TRANCAT_TYPE_CD_WIDTH = 2;

    /**
     * Byte width of {@code TRANCAT-CD}, {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA01Y.cpy:L8}. Third part of the composite key.
     */
    public static final int TRANCAT_CD_WIDTH = 4;

    /**
     * Byte width of the {@code TRAN-CAT-KEY} group at {@code app/cpy/CVTRA01Y.cpy:L5}, which
     * spans the three fields at {@code app/cpy/CVTRA01Y.cpy:L6-L8}.
     */
    public static final int TRAN_CAT_KEY_WIDTH =
            TRANCAT_ACCT_ID_WIDTH + TRANCAT_TYPE_CD_WIDTH + TRANCAT_CD_WIDTH;

    /**
     * Total digits in {@code TRAN-CAT-BAL}, {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA01Y.cpy:L9}.
     */
    public static final int TRAN_CAT_BAL_PRECISION = 11;

    /** Digits after the decimal point in {@code TRAN-CAT-BAL}, {@code app/cpy/CVTRA01Y.cpy:L9}. */
    public static final int TRAN_CAT_BAL_SCALE = 2;

    /** Byte width of {@code TRAN-CAT-BAL} on a fixture record, {@code app/cpy/CVTRA01Y.cpy:L9}. */
    public static final int TRAN_CAT_BAL_WIDTH = TRAN_CAT_BAL_PRECISION;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(22)} at
     * {@code app/cpy/CVTRA01Y.cpy:L10}. The category balance table models no column for these
     * bytes.
     */
    public static final int TRAN_CAT_BAL_RECORD_FILLER_WIDTH = 22;

    // Disclosure group record. app/cpy/CVTRA02Y.cpy, 01 DIS-GROUP-RECORD at L4.

    /**
     * Byte length of one {@code DIS-GROUP-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA02Y.cpy:L2} states RECLN 50.
     */
    public static final int DIS_GROUP_RECORD_LENGTH = 50;

    /**
     * Byte width of {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)} at
     * {@code app/cpy/CVTRA02Y.cpy:L6}. First part of the composite key.
     */
    public static final int DIS_ACCT_GROUP_ID_WIDTH = 10;

    /**
     * Byte width of {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)} at
     * {@code app/cpy/CVTRA02Y.cpy:L7}. Second part of the composite key.
     */
    public static final int DIS_TRAN_TYPE_CD_WIDTH = 2;

    /**
     * Byte width of {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA02Y.cpy:L8}. Third part of the composite key.
     */
    public static final int DIS_TRAN_CAT_CD_WIDTH = 4;

    /**
     * Byte width of the {@code DIS-GROUP-KEY} group at {@code app/cpy/CVTRA02Y.cpy:L5}, which
     * spans the three fields at {@code app/cpy/CVTRA02Y.cpy:L6-L8}.
     */
    public static final int DIS_GROUP_KEY_WIDTH =
            DIS_ACCT_GROUP_ID_WIDTH + DIS_TRAN_TYPE_CD_WIDTH + DIS_TRAN_CAT_CD_WIDTH;

    /**
     * Total digits in {@code DIS-INT-RATE}, {@code PIC S9(04)V99} at
     * {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final int DIS_INT_RATE_PRECISION = 6;

    /** Digits after the decimal point in {@code DIS-INT-RATE}, {@code app/cpy/CVTRA02Y.cpy:L9}. */
    public static final int DIS_INT_RATE_SCALE = 2;

    /** Byte width of {@code DIS-INT-RATE} on a fixture record, {@code app/cpy/CVTRA02Y.cpy:L9}. */
    public static final int DIS_INT_RATE_WIDTH = DIS_INT_RATE_PRECISION;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(28)} at
     * {@code app/cpy/CVTRA02Y.cpy:L10}. The disclosure group table models no column for these
     * bytes.
     */
    public static final int DIS_GROUP_RECORD_FILLER_WIDTH = 28;

    // Transaction type record. app/cpy/CVTRA03Y.cpy, 01 TRAN-TYPE-RECORD at L4. Flyway seeds
    // these rows into every schema that reads them.

    /**
     * Byte length of one {@code TRAN-TYPE-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA03Y.cpy:L2} states RECLN 60, and
     * {@code app/jcl/TRANTYPE.jcl:L41} repeats it as {@code RECORDSIZE(60 60)}.
     */
    public static final int TRAN_TYPE_RECORD_LENGTH = 60;

    /**
     * Byte width of {@code TRAN-TYPE}, {@code PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy:L5}. The
     * key of the transaction type dataset, which {@code app/jcl/TRANTYPE.jcl:L40} declares as
     * {@code KEYS(2 0)}.
     */
    public static final int TRAN_TYPE_WIDTH = 2;

    /**
     * Byte width of {@code TRAN-TYPE-DESC}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA03Y.cpy:L6}.
     */
    public static final int TRAN_TYPE_DESC_WIDTH = 50;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(08)} at
     * {@code app/cpy/CVTRA03Y.cpy:L7}. The transaction type table models no column for these
     * bytes.
     */
    public static final int TRAN_TYPE_RECORD_FILLER_WIDTH = 8;

    // Transaction category record. app/cpy/CVTRA04Y.cpy, 01 TRAN-CAT-RECORD at L4. The group
    // 05 TRAN-CAT-KEY at L5 spans six bytes and two parts, unlike the seventeen-byte group of
    // the same name at app/cpy/CVTRA01Y.cpy:L5.

    /**
     * Byte length of one {@code TRAN-CAT-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA04Y.cpy:L2} states RECLN 60, and
     * {@code app/jcl/TRANCATG.jcl:L41} repeats it as {@code RECORDSIZE(60 60)}.
     */
    public static final int TRAN_CAT_RECORD_LENGTH = 60;

    /**
     * Byte width of {@code TRAN-TYPE-CD}, {@code PIC X(02)} at
     * {@code app/cpy/CVTRA04Y.cpy:L6}. First part of the composite key.
     */
    public static final int TRAN_CAT_RECORD_TYPE_CD_WIDTH = 2;

    /**
     * Byte width of {@code TRAN-CAT-CD}, {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA04Y.cpy:L7}. Second part of the composite key.
     */
    public static final int TRAN_CAT_RECORD_CAT_CD_WIDTH = 4;

    /**
     * Byte width of {@code TRAN-CAT-KEY}, the group at {@code app/cpy/CVTRA04Y.cpy:L5}, which is
     * the sum of its two parts. {@code app/jcl/TRANCATG.jcl:L40} repeats the width as
     * {@code KEYS(6 0)}.
     */
    public static final int TRAN_CAT_RECORD_KEY_WIDTH =
            TRAN_CAT_RECORD_TYPE_CD_WIDTH + TRAN_CAT_RECORD_CAT_CD_WIDTH;

    /**
     * Byte width of {@code TRAN-CAT-TYPE-DESC}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA04Y.cpy:L8}.
     */
    public static final int TRAN_CAT_TYPE_DESC_WIDTH = 50;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(04)} at
     * {@code app/cpy/CVTRA04Y.cpy:L9}. The transaction category table models no column for these
     * bytes.
     */
    public static final int TRAN_CAT_RECORD_FILLER_WIDTH = 4;

    // Posted transaction record. app/cpy/CVTRA05Y.cpy, 01 TRAN-RECORD at L4.

    /**
     * Byte length of one {@code TRAN-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA05Y.cpy:L2} states RECLN 350.
     */
    public static final int TRAN_RECORD_LENGTH = 350;

    /**
     * Byte width of {@code TRAN-ID}, {@code PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The
     * primary key of the transaction table.
     */
    public static final int TRAN_ID_WIDTH = 16;

    /** Byte width of {@code TRAN-TYPE-CD}, {@code PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6}. */
    public static final int TRAN_TYPE_CD_WIDTH = 2;

    /**
     * Byte width of {@code TRAN-CAT-CD}, {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:L7}. The merchant category the authorization event carries.
     */
    public static final int TRAN_CAT_CD_WIDTH = 4;

    /** Byte width of {@code TRAN-SOURCE}, {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8}. */
    public static final int TRAN_SOURCE_WIDTH = 10;

    /** Byte width of {@code TRAN-DESC}, {@code PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9}. */
    public static final int TRAN_DESC_WIDTH = 100;

    /**
     * Total digits in {@code TRAN-AMT}, {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    public static final int TRAN_AMT_PRECISION = 11;

    /** Digits after the decimal point in {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}. */
    public static final int TRAN_AMT_SCALE = 2;

    /** Byte width of {@code TRAN-AMT} on a fixture record, {@code app/cpy/CVTRA05Y.cpy:L10}. */
    public static final int TRAN_AMT_WIDTH = TRAN_AMT_PRECISION;

    /**
     * Byte width of {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)} at
     * {@code app/cpy/CVTRA05Y.cpy:L11}.
     */
    public static final int TRAN_MERCHANT_ID_WIDTH = 9;

    /**
     * Byte width of {@code TRAN-MERCHANT-NAME}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA05Y.cpy:L12}.
     */
    public static final int TRAN_MERCHANT_NAME_WIDTH = 50;

    /**
     * Byte width of {@code TRAN-MERCHANT-CITY}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA05Y.cpy:L13}.
     */
    public static final int TRAN_MERCHANT_CITY_WIDTH = 50;

    /**
     * Byte width of {@code TRAN-MERCHANT-ZIP}, {@code PIC X(10)} at
     * {@code app/cpy/CVTRA05Y.cpy:L14}.
     */
    public static final int TRAN_MERCHANT_ZIP_WIDTH = 10;

    /**
     * Byte width of {@code TRAN-CARD-NUM}, {@code PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. The full Primary Account Number. The authorization
     * event carries the masked form of this field.
     */
    public static final int TRAN_CARD_NUM_WIDTH = 16;

    /**
     * Byte width of {@code TRAN-ORIG-TS}, {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}.
     */
    public static final int TRAN_ORIG_TS_WIDTH = 26;

    /**
     * Byte width of {@code TRAN-PROC-TS}, {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17}.
     */
    public static final int TRAN_PROC_TS_WIDTH = 26;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(20)} at
     * {@code app/cpy/CVTRA05Y.cpy:L18}. The transaction table models no column for these bytes.
     */
    public static final int TRAN_RECORD_FILLER_WIDTH = 20;

    // Daily transaction record. app/cpy/CVTRA06Y.cpy, 01 DALYTRAN-RECORD at L4. The layout
    // repeats app/cpy/CVTRA05Y.cpy byte for byte under the DALYTRAN- prefix, at the same
    // line offsets L5 through L18.

    /**
     * Byte length of one {@code DALYTRAN-RECORD}. The copybook header at
     * {@code app/cpy/CVTRA06Y.cpy:L2} states RECLN 350.
     */
    public static final int DALYTRAN_RECORD_LENGTH = 350;

    /** Byte width of {@code DALYTRAN-ID}, {@code PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L5}. */
    public static final int DALYTRAN_ID_WIDTH = 16;

    /**
     * Byte width of {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)} at {@code app/cpy/CVTRA06Y.cpy:L6}.
     */
    public static final int DALYTRAN_TYPE_CD_WIDTH = 2;

    /**
     * Byte width of {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:L7}.
     */
    public static final int DALYTRAN_CAT_CD_WIDTH = 4;

    /**
     * Byte width of {@code DALYTRAN-SOURCE}, {@code PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L8}.
     */
    public static final int DALYTRAN_SOURCE_WIDTH = 10;

    /**
     * Byte width of {@code DALYTRAN-DESC}, {@code PIC X(100)} at {@code app/cpy/CVTRA06Y.cpy:L9}.
     */
    public static final int DALYTRAN_DESC_WIDTH = 100;

    /**
     * Total digits in {@code DALYTRAN-AMT}, {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    public static final int DALYTRAN_AMT_PRECISION = 11;

    /** Digits after the decimal point in {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}. */
    public static final int DALYTRAN_AMT_SCALE = 2;

    /** Byte width of {@code DALYTRAN-AMT} on a fixture record, {@code app/cpy/CVTRA06Y.cpy:L10}. */
    public static final int DALYTRAN_AMT_WIDTH = DALYTRAN_AMT_PRECISION;

    /**
     * Byte width of {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)} at
     * {@code app/cpy/CVTRA06Y.cpy:L11}.
     */
    public static final int DALYTRAN_MERCHANT_ID_WIDTH = 9;

    /**
     * Byte width of {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA06Y.cpy:L12}.
     */
    public static final int DALYTRAN_MERCHANT_NAME_WIDTH = 50;

    /**
     * Byte width of {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)} at
     * {@code app/cpy/CVTRA06Y.cpy:L13}.
     */
    public static final int DALYTRAN_MERCHANT_CITY_WIDTH = 50;

    /**
     * Byte width of {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)} at
     * {@code app/cpy/CVTRA06Y.cpy:L14}.
     */
    public static final int DALYTRAN_MERCHANT_ZIP_WIDTH = 10;

    /**
     * Byte width of {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15}.
     */
    public static final int DALYTRAN_CARD_NUM_WIDTH = 16;

    /**
     * Byte width of {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)} at
     * {@code app/cpy/CVTRA06Y.cpy:L16}.
     */
    public static final int DALYTRAN_ORIG_TS_WIDTH = 26;

    /**
     * Byte width of {@code DALYTRAN-PROC-TS}, {@code PIC X(26)} at
     * {@code app/cpy/CVTRA06Y.cpy:L17}.
     */
    public static final int DALYTRAN_PROC_TS_WIDTH = 26;

    /**
     * Byte width of the trailing {@code FILLER}, {@code PIC X(20)} at
     * {@code app/cpy/CVTRA06Y.cpy:L18}.
     */
    public static final int DALYTRAN_RECORD_FILLER_WIDTH = 20;

    // Posting program working storage. app/cbl/CBTRN02C.cbl.

    /**
     * Total digits in {@code WS-TEMP-BAL}, {@code PIC S9(09)V99} at
     * {@code app/cbl/CBTRN02C.cbl:L187}. The field is one integer digit narrower than the two cycle
     * accumulators that feed it at {@code app/cpy/CVACT01Y.cpy:L13-L14} and narrower than the
     * credit limit it is compared against at {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    public static final int WS_TEMP_BAL_PRECISION = 11;

    /** Digits after the decimal point in {@code WS-TEMP-BAL}, {@code app/cbl/CBTRN02C.cbl:L187}. */
    public static final int WS_TEMP_BAL_SCALE = 2;

    /**
     * Number of leading characters of {@code DALYTRAN-ORIG-TS} the expiration test reads at
     * {@code app/cbl/CBTRN02C.cbl:L414}. The test compares those characters against the
     * account expiration date as text.
     */
    public static final int ACCOUNT_EXPIRATION_COMPARISON_WIDTH = 10;

    /**
     * Byte length of one {@code REJECT-RECORD}, the group at
     * {@code app/cbl/CBTRN02C.cbl:L176}. The value is the sum of the two fields below.
     */
    public static final int REJECT_RECORD_LENGTH = 430;

    /**
     * Byte width of {@code REJECT-TRAN-DATA}, {@code PIC X(350)} at
     * {@code app/cbl/CBTRN02C.cbl:L177}. The field carries the whole daily transaction record.
     */
    public static final int REJECT_TRAN_DATA_WIDTH = 350;

    /**
     * Byte width of {@code VALIDATION-TRAILER}, {@code PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:L178}. The trailer holds the two fields below.
     */
    public static final int VALIDATION_TRAILER_WIDTH = 80;

    /**
     * Byte width of {@code WS-VALIDATION-FAIL-REASON}, {@code PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}.
     */
    public static final int VALIDATION_FAIL_REASON_WIDTH = 4;

    /**
     * Byte width of {@code WS-VALIDATION-FAIL-REASON-DESC}, {@code PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    public static final int VALIDATION_FAIL_REASON_DESC_WIDTH = 76;

    // Rendered processing timestamp. app/cbl/CBTRN02C.cbl:L149-L174 declares the layout and
    // app/cbl/CBTRN02C.cbl:L692-L705 fills it. app/cbl/CBACT04C.cbl:L150-L165 and
    // app/cbl/CBACT04C.cbl:L613-L626 repeat both. These constants carry the numbers; the
    // formatting belongs to the caller.

    /**
     * Byte width of {@code DB2-FORMAT-TS}, {@code PIC X(26)} at {@code app/cbl/CBTRN02C.cbl:L159}.
     */
    public static final int PROCESSING_TIMESTAMP_WIDTH = 26;

    /**
     * Shape of the rendered timestamp, read off the field layout at
     * {@code app/cbl/CBTRN02C.cbl:L161-L174} and the fill routine at
     * {@code app/cbl/CBTRN02C.cbl:L700-L703}. The form is neither ISO 8601 nor the space-separated
     * DB2 form.
     */
    public static final String PROCESSING_TIMESTAMP_SHAPE = "YYYY-MM-DD-HH.MM.SS.hh0000";

    /** Number of fractional-second digits the fill routine carries a value into. */
    public static final int PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS = 2;

    /** Number of trailing digits the fill routine sets to zero. */
    public static final int PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS = 4;

    /**
     * Literal the fill routine moves into {@code DB2-REST} at
     * {@code app/cbl/CBTRN02C.cbl:L701} and at {@code app/cbl/CBACT04C.cbl:L622}.
     */
    public static final String PROCESSING_TIMESTAMP_TRAILING_ZEROS = "0000";

    /**
     * Character the fill routine moves into all three {@code DB2-STREEP} fields at
     * {@code app/cbl/CBTRN02C.cbl:L702} and at {@code app/cbl/CBACT04C.cbl:L623}.
     */
    public static final char PROCESSING_TIMESTAMP_DASH = '-';

    /**
     * Character the fill routine moves into all three {@code DB2-DOT} fields at
     * {@code app/cbl/CBTRN02C.cbl:L703} and at {@code app/cbl/CBACT04C.cbl:L624}.
     */
    public static final char PROCESSING_TIMESTAMP_DOT = '.';

    /**
     * Byte offset of {@code DB2-YYYY} inside the rendered timestamp,
     * {@code PIC X(004)} at {@code app/cbl/CBTRN02C.cbl:L161}.
     */
    public static final int PROCESSING_TIMESTAMP_YEAR_OFFSET = 0;

    /** Byte width of {@code DB2-YYYY}, {@code app/cbl/CBTRN02C.cbl:L161}. */
    public static final int PROCESSING_TIMESTAMP_YEAR_WIDTH = 4;

    /**
     * Byte offset of {@code DB2-MM} inside the rendered timestamp,
     * {@code PIC X(002)} at {@code app/cbl/CBTRN02C.cbl:L163}.
     */
    public static final int PROCESSING_TIMESTAMP_MONTH_OFFSET = 5;

    /**
     * Byte offset of {@code DB2-DD} inside the rendered timestamp,
     * {@code PIC X(002)} at {@code app/cbl/CBTRN02C.cbl:L165}.
     */
    public static final int PROCESSING_TIMESTAMP_DAY_OFFSET = 8;

    /**
     * Byte offset of {@code DB2-HH} inside the rendered timestamp, {@code PIC X(002)} at
     * {@code app/cbl/CBTRN02C.cbl:L167}.
     */
    public static final int PROCESSING_TIMESTAMP_HOUR_OFFSET = 11;

    /**
     * Byte offset of {@code DB2-MIN} inside the rendered timestamp,
     * {@code PIC X(002)} at {@code app/cbl/CBTRN02C.cbl:L169}.
     */
    public static final int PROCESSING_TIMESTAMP_MINUTE_OFFSET = 14;

    /**
     * Byte offset of {@code DB2-SS} inside the rendered timestamp,
     * {@code PIC X(002)} at {@code app/cbl/CBTRN02C.cbl:L171}.
     */
    public static final int PROCESSING_TIMESTAMP_SECOND_OFFSET = 17;

    /**
     * Byte width of each of the month, day, hour, minute, second, and fraction components,
     * {@code app/cbl/CBTRN02C.cbl:L163-L173}.
     */
    public static final int PROCESSING_TIMESTAMP_COMPONENT_WIDTH = 2;

    /**
     * Byte offset of {@code DB2-MIL} inside the rendered timestamp,
     * {@code PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173}.
     */
    public static final int PROCESSING_TIMESTAMP_FRACTION_OFFSET = 20;

    /**
     * Byte offset of {@code DB2-REST} inside the rendered timestamp,
     * {@code PIC X(04)} at {@code app/cbl/CBTRN02C.cbl:L174}.
     */
    public static final int PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET = 22;

    /**
     * Byte width of each separator inside the rendered timestamp,
     * {@code app/cbl/CBTRN02C.cbl:L162-L172}.
     */
    public static final int PROCESSING_TIMESTAMP_SEPARATOR_WIDTH = 1;

    // Interest program working storage. app/cbl/CBACT04C.cbl.

    /**
     * Total digits in {@code WS-MONTHLY-INT}, {@code PIC S9(09)V99} at
     * {@code app/cbl/CBACT04C.cbl:L168}.
     */
    public static final int WS_MONTHLY_INT_PRECISION = 11;

    /**
     * Digits after the decimal point in {@code WS-MONTHLY-INT}, {@code app/cbl/CBACT04C.cbl:L168}.
     */
    public static final int WS_MONTHLY_INT_SCALE = 2;

    /**
     * Total digits in {@code WS-TOTAL-INT}, {@code PIC S9(09)V99} at
     * {@code app/cbl/CBACT04C.cbl:L169}.
     */
    public static final int WS_TOTAL_INT_PRECISION = 11;

    /**
     * Digits after the decimal point in {@code WS-TOTAL-INT}, {@code app/cbl/CBACT04C.cbl:L169}.
     */
    public static final int WS_TOTAL_INT_SCALE = 2;

    // Equivalence fixtures under app/data/ASCII. Record counts and record widths measured from
    // the files. One fixture is narrower than its copybook declares, so the loader reads a
    // record width from these constants and pads to the declared layout.

    /** Records in {@code app/data/ASCII/acctdata.txt}, laid out by {@code app/cpy/CVACT01Y.cpy}. */
    public static final int ACCTDATA_FIXTURE_RECORD_COUNT = 50;

    /**
     * Measured record width of {@code app/data/ASCII/acctdata.txt}, which matches
     * {@link #ACCOUNT_RECORD_LENGTH}.
     */
    public static final int ACCTDATA_FIXTURE_RECORD_WIDTH = 300;

    /** Records in {@code app/data/ASCII/carddata.txt}, laid out by {@code app/cpy/CVACT02Y.cpy}. */
    public static final int CARDDATA_FIXTURE_RECORD_COUNT = 50;

    /**
     * Measured record width of {@code app/data/ASCII/carddata.txt}, which matches
     * {@link #CARD_RECORD_LENGTH}.
     */
    public static final int CARDDATA_FIXTURE_RECORD_WIDTH = 150;

    /** Records in {@code app/data/ASCII/cardxref.txt}, laid out by {@code app/cpy/CVACT03Y.cpy}. */
    public static final int CARDXREF_FIXTURE_RECORD_COUNT = 50;

    /**
     * Measured record width of {@code app/data/ASCII/cardxref.txt}. The file stops after the
     * three named fields; {@code app/cpy/CVACT03Y.cpy:L2} declares 50 and
     * {@code app/cpy/CVACT03Y.cpy:L8} declares the 14 filler bytes the file omits.
     */
    public static final int CARDXREF_FIXTURE_RECORD_WIDTH = 36;

    /** Records in {@code app/data/ASCII/custdata.txt}, laid out by {@code app/cpy/CVCUS01Y.cpy}. */
    public static final int CUSTDATA_FIXTURE_RECORD_COUNT = 50;

    /**
     * Measured record width of {@code app/data/ASCII/custdata.txt}. The copybook header at
     * {@code app/cpy/CVCUS01Y.cpy:L2} states RECLN 500.
     */
    public static final int CUSTDATA_FIXTURE_RECORD_WIDTH = 500;

    /**
     * Records in {@code app/data/ASCII/dailytran.txt}, laid out by {@code app/cpy/CVTRA06Y.cpy}.
     * This is the posting fixture the equivalence suite drives end to end.
     */
    public static final int DAILYTRAN_FIXTURE_RECORD_COUNT = 300;

    /**
     * Measured record width of {@code app/data/ASCII/dailytran.txt}, which matches
     * {@link #DALYTRAN_RECORD_LENGTH}.
     */
    public static final int DAILYTRAN_FIXTURE_RECORD_WIDTH = 350;

    /** Records in {@code app/data/ASCII/discgrp.txt}, laid out by {@code app/cpy/CVTRA02Y.cpy}. */
    public static final int DISCGRP_FIXTURE_RECORD_COUNT = 51;

    /**
     * Measured record width of {@code app/data/ASCII/discgrp.txt}, which matches
     * {@link #DIS_GROUP_RECORD_LENGTH}.
     */
    public static final int DISCGRP_FIXTURE_RECORD_WIDTH = 50;

    /** Records in {@code app/data/ASCII/tcatbal.txt}, laid out by {@code app/cpy/CVTRA01Y.cpy}. */
    public static final int TCATBAL_FIXTURE_RECORD_COUNT = 50;

    /**
     * Measured record width of {@code app/data/ASCII/tcatbal.txt}, which matches
     * {@link #TRAN_CAT_BAL_RECORD_LENGTH}.
     */
    public static final int TCATBAL_FIXTURE_RECORD_WIDTH = 50;

    /** Records in {@code app/data/ASCII/trancatg.txt}, laid out by {@code app/cpy/CVTRA04Y.cpy}. */
    public static final int TRANCATG_FIXTURE_RECORD_COUNT = 18;

    /**
     * Measured record width of {@code app/data/ASCII/trancatg.txt}. The copybook header at
     * {@code app/cpy/CVTRA04Y.cpy:L2} states RECLN 60.
     */
    public static final int TRANCATG_FIXTURE_RECORD_WIDTH = 60;

    /** Records in {@code app/data/ASCII/trantype.txt}, laid out by {@code app/cpy/CVTRA03Y.cpy}. */
    public static final int TRANTYPE_FIXTURE_RECORD_COUNT = 7;

    /**
     * Measured record width of {@code app/data/ASCII/trantype.txt}. The copybook header at
     * {@code app/cpy/CVTRA03Y.cpy:L2} states RECLN 60.
     */
    public static final int TRANTYPE_FIXTURE_RECORD_WIDTH = 60;

    private PicClause() {
    }
}
