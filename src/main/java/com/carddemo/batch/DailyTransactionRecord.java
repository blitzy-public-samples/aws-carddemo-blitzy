package com.carddemo.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Plain Java transport model for ONE 350-byte fixed-width {@code DALYTRAN} record from the
 * daily-transaction feed file. This is the faithful Java translation of the {@code DALYTRAN-RECORD}
 * layout defined by copybook {@code app/cpy/CVTRA06Y.cpy} and read sequentially by the legacy
 * batch program {@code app/cbl/CBTRN02C.cbl} ({@code COPY CVTRA06Y} at L102; {@code READ
 * DALYTRAN-FILE INTO DALYTRAN-RECORD} at L346).
 *
 * <p>It is the <strong>read item</strong> of the transaction-posting batch step
 * ({@code TransactionPostingJobConfig}). Each line of the {@code dailytran.txt} feed is converted
 * into one instance via the {@link #parse(String)} static factory. <strong>The COBOL source
 * governs</strong> every field width, offset and decoding rule reproduced here.</p>
 *
 * <h2>Record layout (CVTRA06Y.cpy — RECLN = 350)</h2>
 * <pre>
 *   COBOL field              PIC          1-idx pos   0-idx slice        Java field
 *   ----------------------   ----------   ---------   ----------------   ------------------------
 *   DALYTRAN-ID              X(16)        1-16        [0,16)             id            (String)
 *   DALYTRAN-TYPE-CD         X(02)        17-18       [16,18)            typeCd        (String)
 *   DALYTRAN-CAT-CD          9(04)        19-22       [18,22)            catCd         (Integer)
 *   DALYTRAN-SOURCE          X(10)        23-32       [22,32)            source        (String)
 *   DALYTRAN-DESC            X(100)       33-132      [32,132)           description   (String)
 *   DALYTRAN-AMT             S9(09)V99    133-143     [132,143)          amt           (BigDecimal)
 *   DALYTRAN-MERCHANT-ID     9(09)        144-152     [143,152)          merchantId    (Long)
 *   DALYTRAN-MERCHANT-NAME   X(50)        153-202     [152,202)          merchantName  (String)
 *   DALYTRAN-MERCHANT-CITY   X(50)        203-252     [202,252)          merchantCity  (String)
 *   DALYTRAN-MERCHANT-ZIP    X(10)        253-262     [252,262)          merchantZip   (String)
 *   DALYTRAN-CARD-NUM        X(16)        263-278     [262,278)          cardNum       (String)
 *   DALYTRAN-ORIG-TS         X(26)        279-304     [278,304)          origTs        (String)
 *   DALYTRAN-PROC-TS         X(26)        305-330     [304,330)          procTs        (String)
 *   FILLER                   X(20)        331-350     [330,350)          (not stored)
 *   ----------------------   ----------   ---------   ----------------   ------------------------
 *   16+2+4+10+100+11+9+50+50+10+16+26+26 (stored) + 20 (filler) = 350 bytes
 * </pre>
 *
 * <h2>Critical decoding behaviors (parity)</h2>
 * <ul>
 *   <li><strong>Signed amount</strong> — {@code DALYTRAN-AMT} is a zoned-decimal {@code S9(09)V99}
 *       whose trailing character carries the combined low-order digit and sign (COBOL "overpunch").
 *       It is decoded by {@link #decodeOverpunch(String)} into a scale-2 {@link BigDecimal} with
 *       {@link RoundingMode#HALF_UP}; a plain {@code FixedLengthTokenizer} cannot decode this.</li>
 *   <li><strong>Raw image retention</strong> — {@link #getRawRecord()} returns the FULL original
 *       line right-padded/truncated to exactly {@value #RECORD_LENGTH} characters. {@code CBTRN02C}
 *       writes this verbatim into the 430-byte DALYREJS reject record ({@code MOVE DALYTRAN-RECORD
 *       TO REJECT-TRAN-DATA} at L447), so the reject writer must emit a byte-compatible image.</li>
 *   <li><strong>Origination date</strong> — {@link #getOrigDate()} exposes the first 10 characters
 *       of {@code origTs} (the {@code DALYTRAN-ORIG-TS(1:10)} reference used by the account-expiry /
 *       reject-103 check at {@code CBTRN02C} L414).</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>This is a <strong>plain transport POJO</strong>: it carries NO JPA or Spring annotations and
 *       has zero project dependencies (JDK imports only). It is intentionally count-agnostic — the
 *       reader may supply any number of lines (the canonical {@code dailytran.txt} feed contains 300
 *       records; no count is ever hard-coded).</li>
 *   <li>{@link #parse(String)} is defensive: it never throws on a {@code null}, short or over-length
 *       line, padding or truncating to exactly {@value #RECORD_LENGTH} characters first.</li>
 * </ul>
 *
 * @see <a href="file:app/cpy/CVTRA06Y.cpy">CVTRA06Y.cpy — DALYTRAN-RECORD layout</a>
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl — daily transaction posting</a>
 */
public class DailyTransactionRecord {

    /** Fixed record length of the {@code DALYTRAN} feed in bytes/characters (CVTRA06Y RECLN). */
    public static final int RECORD_LENGTH = 350;

    /**
     * Tolerant formatter for {@code DALYTRAN-ORIG-TS} / {@code DALYTRAN-PROC-TS}. The feed uses a
     * space-separated {@code yyyy-MM-dd HH:mm:ss.SSSSSS} layout (six fractional digits), but the
     * optional fraction sections accept anywhere from one to six fractional digits — or none — so
     * the parser is resilient to minor feed variations.
     */
    private static final DateTimeFormatter ORIG_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]");

    // ---------------------------------------------------------------------------------------------
    // Fields — the FULL raw image plus every stored DALYTRAN-RECORD field (FILLER is not retained).
    // ---------------------------------------------------------------------------------------------

    /** The complete original feed line, normalized to exactly {@link #RECORD_LENGTH} characters. */
    private String rawRecord;

    /** {@code DALYTRAN-ID} — X(16), pos 1-16. */
    private String id;

    /** {@code DALYTRAN-TYPE-CD} — X(02), pos 17-18. */
    private String typeCd;

    /** {@code DALYTRAN-CAT-CD} — 9(04), pos 19-22. {@code null} when the field is blank. */
    private Integer catCd;

    /** {@code DALYTRAN-SOURCE} — X(10), pos 23-32. */
    private String source;

    /** {@code DALYTRAN-DESC} — X(100), pos 33-132. */
    private String description;

    /** {@code DALYTRAN-AMT} — S9(09)V99 overpunch, pos 133-143. Scale-2 signed monetary amount. */
    private BigDecimal amt;

    /** {@code DALYTRAN-MERCHANT-ID} — 9(09), pos 144-152. {@code null} when the field is blank. */
    private Long merchantId;

    /** {@code DALYTRAN-MERCHANT-NAME} — X(50), pos 153-202. */
    private String merchantName;

    /** {@code DALYTRAN-MERCHANT-CITY} — X(50), pos 203-252. */
    private String merchantCity;

    /** {@code DALYTRAN-MERCHANT-ZIP} — X(10), pos 253-262. */
    private String merchantZip;

    /** {@code DALYTRAN-CARD-NUM} — X(16), pos 263-278. */
    private String cardNum;

    /** {@code DALYTRAN-ORIG-TS} — X(26), pos 279-304. Raw {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. */
    private String origTs;

    /** {@code DALYTRAN-PROC-TS} — X(26), pos 305-330. Blank in the inbound feed; retained raw. */
    private String procTs;

    /**
     * Default constructor. Produces an empty record; populate via {@link #parse(String)} or the
     * provided setters.
     */
    public DailyTransactionRecord() {
        // No-arg POJO constructor; fields are populated by parse(...) or setters.
    }

    // ---------------------------------------------------------------------------------------------
    // Static factory
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses one fixed-width {@code DALYTRAN} feed line into a populated {@link DailyTransactionRecord}.
     *
     * <p>The method is fully defensive and never throws for malformed input: a {@code null} line is
     * treated as empty, a short line is right-padded with spaces, and an over-length line is
     * truncated — in all cases the retained {@link #getRawRecord() raw image} is exactly
     * {@link #RECORD_LENGTH} characters so the DALYREJS reject writer remains byte-compatible.</p>
     *
     * @param line a single raw feed line (may be {@code null}, shorter or longer than 350 chars)
     * @return a populated record; never {@code null}
     */
    public static DailyTransactionRecord parse(String line) {
        // 1. Null-guard.
        String input = (line == null) ? "" : line;

        // 2. Normalize to EXACTLY 350 characters (truncate if longer, right-pad with spaces if shorter).
        String r = input.length() >= RECORD_LENGTH
                ? input.substring(0, RECORD_LENGTH)
                : String.format("%-" + RECORD_LENGTH + "s", input);

        DailyTransactionRecord rec = new DailyTransactionRecord();

        // 3. Retain the byte-compatible raw image and slice each field (0-indexed, end-exclusive).
        rec.rawRecord    = r;
        rec.id           = r.substring(0, 16).trim();        // DALYTRAN-ID            X(16)  1-16
        rec.typeCd       = r.substring(16, 18).trim();       // DALYTRAN-TYPE-CD       X(02)  17-18
        rec.catCd        = parseIntOrNull(r.substring(18, 22).trim());   // DALYTRAN-CAT-CD 9(04) 19-22
        rec.source       = r.substring(22, 32).trim();       // DALYTRAN-SOURCE        X(10)  23-32
        rec.description  = r.substring(32, 132).trim();      // DALYTRAN-DESC          X(100) 33-132
        rec.amt          = decodeOverpunch(r.substring(132, 143)); // DALYTRAN-AMT  S9(09)V99 133-143
        rec.merchantId   = parseLongOrNull(r.substring(143, 152).trim());// DALYTRAN-MERCHANT-ID 9(09) 144-152
        rec.merchantName = r.substring(152, 202).trim();     // DALYTRAN-MERCHANT-NAME X(50)  153-202
        rec.merchantCity = r.substring(202, 252).trim();     // DALYTRAN-MERCHANT-CITY X(50)  203-252
        rec.merchantZip  = r.substring(252, 262).trim();     // DALYTRAN-MERCHANT-ZIP  X(10)  253-262
        rec.cardNum      = r.substring(262, 278).trim();     // DALYTRAN-CARD-NUM      X(16)  263-278
        rec.origTs       = r.substring(278, 304).trim();     // DALYTRAN-ORIG-TS       X(26)  279-304
        rec.procTs       = r.substring(304, 330).trim();     // DALYTRAN-PROC-TS       X(26)  305-330
        // FILLER pos 331-350 X(20) is intentionally NOT stored.

        return rec;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Decodes a COBOL zoned-decimal {@code S9(09)V99} field whose trailing character carries the
     * combined low-order digit and sign (an "overpunch"). The feed stores the value without an
     * explicit decimal point; the implied scale is 2.
     *
     * <p>Sign/digit mapping of the trailing character:</p>
     * <ul>
     *   <li>Positive: <code>{</code>=0, {@code A}=1 … {@code I}=9</li>
     *   <li>Negative: <code>}</code>=0, {@code J}=1 … {@code R}=9</li>
     *   <li>A plain digit {@code 0}-{@code 9} is treated as that digit, positive.</li>
     *   <li>Any other character defaults the low-order digit to 0, positive (defensive).</li>
     * </ul>
     *
     * <p>Verified against {@code app/data/ASCII/dailytran.txt}: {@code 0000005047G} &rarr;
     * {@code 504.77}, {@code 0000009190}<code>}</code> &rarr; {@code -919.00}, {@code 0000000678H}
     * &rarr; {@code 67.88}.</p>
     *
     * @param field the raw 11-character amount field (leading/trailing spaces tolerated)
     * @return the decoded amount as a scale-2 {@link BigDecimal}; {@code 0.00} for a blank field
     */
    private static BigDecimal decodeOverpunch(String field) {
        // 1. Blank / null field posts as zero.
        if (field == null || field.trim().isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }

        // 2. Trim, then defensively left-pad with '0' to the full S9(09)V99 width of 11 characters.
        String f = field.trim();
        while (f.length() < 11) {
            f = "0" + f;
        }

        // Split off the trailing overpunch character; strip any stray non-digits from the head
        // (a no-op for the clean numeric feed) so BigDecimal construction can never throw.
        String head = f.substring(0, f.length() - 1).replaceAll("[^0-9]", "");
        char last = f.charAt(f.length() - 1);

        int lastDigit;
        boolean negative;
        switch (last) {
            // Positive overpunch set.
            case '{': lastDigit = 0; negative = false; break;
            case 'A': lastDigit = 1; negative = false; break;
            case 'B': lastDigit = 2; negative = false; break;
            case 'C': lastDigit = 3; negative = false; break;
            case 'D': lastDigit = 4; negative = false; break;
            case 'E': lastDigit = 5; negative = false; break;
            case 'F': lastDigit = 6; negative = false; break;
            case 'G': lastDigit = 7; negative = false; break;
            case 'H': lastDigit = 8; negative = false; break;
            case 'I': lastDigit = 9; negative = false; break;
            // Negative overpunch set.
            case '}': lastDigit = 0; negative = true;  break;
            case 'J': lastDigit = 1; negative = true;  break;
            case 'K': lastDigit = 2; negative = true;  break;
            case 'L': lastDigit = 3; negative = true;  break;
            case 'M': lastDigit = 4; negative = true;  break;
            case 'N': lastDigit = 5; negative = true;  break;
            case 'O': lastDigit = 6; negative = true;  break;
            case 'P': lastDigit = 7; negative = true;  break;
            case 'Q': lastDigit = 8; negative = true;  break;
            case 'R': lastDigit = 9; negative = true;  break;
            default:
                // Plain digit -> positive; anything else defaults to digit 0, positive.
                if (last >= '0' && last <= '9') {
                    lastDigit = last - '0';
                } else {
                    lastDigit = 0;
                }
                negative = false;
                break;
        }

        // 3. Reassemble the magnitude, apply the implied 2-digit scale and the sign.
        String digits = head + lastDigit;
        BigDecimal value = new BigDecimal(digits).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        return negative ? value.negate() : value;
    }

    /**
     * Parses a clean numeric string into an {@link Integer}, returning {@code null} for a blank
     * value and {@code null} (rather than throwing) for a malformed value.
     *
     * @param s the candidate numeric string (may be {@code null} or blank)
     * @return the parsed {@link Integer}, or {@code null}
     */
    private static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses a clean numeric string into a {@link Long}, returning {@code null} for a blank value
     * and {@code null} (rather than throwing) for a malformed value.
     *
     * @param s the candidate numeric string (may be {@code null} or blank)
     * @return the parsed {@link Long}, or {@code null}
     */
    private static Long parseLongOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Convenience accessors used by the transaction-posting processor
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the origination <em>date</em> — the first 10 characters of {@code origTs} parsed as an
     * ISO {@code yyyy-MM-dd} date. This mirrors the COBOL {@code DALYTRAN-ORIG-TS(1:10)} reference
     * used by the account-expiry / reject-103 check ({@code CBTRN02C} L414:
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}).
     *
     * @return the origination date, or {@code null} if {@code origTs} is absent/too short/unparseable
     */
    public LocalDate getOrigDate() {
        if (origTs == null || origTs.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(origTs.substring(0, 10));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * Returns the full origination <em>timestamp</em> parsed from {@code origTs} using the tolerant
     * {@code yyyy-MM-dd HH:mm:ss[.SSSSSS]…} formatter. If the full timestamp cannot be parsed, it
     * falls back to the {@link #getOrigDate() origination date} at start-of-day; if even that is
     * unavailable it returns {@code null}. This value becomes the posted {@code Transaction.origTs}.
     *
     * @return the origination timestamp, or {@code null} when nothing parseable is present
     */
    public LocalDateTime getOrigTimestamp() {
        if (origTs == null || origTs.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(origTs.trim(), ORIG_TS_FORMATTER);
        } catch (DateTimeParseException ex) {
            LocalDate fallback = getOrigDate();
            return (fallback == null) ? null : fallback.atStartOfDay();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Getters / setters (plain mutable POJO)
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the complete original feed line, normalized to exactly {@link #RECORD_LENGTH}
     * characters. Used by the reject writer to emit the byte-compatible DALYREJS feed image.
     *
     * @return the 350-character raw record image
     */
    public String getRawRecord() {
        return rawRecord;
    }

    public void setRawRecord(String rawRecord) {
        this.rawRecord = rawRecord;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTypeCd() {
        return typeCd;
    }

    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    public Integer getCatCd() {
        return catCd;
    }

    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmt() {
        return amt;
    }

    public void setAmt(BigDecimal amt) {
        this.amt = amt;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCity() {
        return merchantCity;
    }

    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    public String getCardNum() {
        return cardNum;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    public String getOrigTs() {
        return origTs;
    }

    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    public String getProcTs() {
        return procTs;
    }

    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Compact diagnostic representation. Deliberately excludes the cardholder-sensitive PAN
     * ({@code cardNum}) and merchant PII beyond identifiers, and never includes the raw image, so
     * that logging an instance cannot leak sensitive feed content.
     *
     * @return a non-sensitive summary of the record
     */
    @Override
    public String toString() {
        return "DailyTransactionRecord{"
                + "id='" + id + '\''
                + ", typeCd='" + typeCd + '\''
                + ", catCd=" + catCd
                + ", amt=" + amt
                + ", origTs='" + origTs + '\''
                + '}';
    }
}
