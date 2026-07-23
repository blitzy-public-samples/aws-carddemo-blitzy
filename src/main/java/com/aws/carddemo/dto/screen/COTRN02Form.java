package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Transaction-add screen form.
 *
 * <p>Origin: legacy/cpy-bms/COTRN02.CPY (BMS mapset COTRN02, map COTRN2A).</p>
 *
 * <p>Form-backing bean for the AWS CardDemo transaction-add screen (CICS transaction
 * {@code CT02}, program {@code COTRN02C}). This is a faithful translation of the BMS
 * symbolic map copybook into a plain-Java DTO, preserving the 24x80 BMS
 * field/label/length contract. Each property corresponds to exactly one BMS value
 * field ({@code <name>I} on the input map / {@code <name>O} on the output map); the
 * BMS attribute and plumbing bytes ({@code L}/{@code F}/{@code A}/{@code C}/{@code P}/
 * {@code H}/{@code V}) are intentionally not modelled. Every property is a
 * {@link String} sized to the exact BMS {@code PIC X(n)} length, and the monetary
 * amount is held as a display-formatted string rather than a numeric type so that the
 * on-screen presentation is preserved byte-for-byte. It backs the server-rendered
 * Thymeleaf template {@code src/main/resources/templates/COTRN02.html}.</p>
 */
public class COTRN02Form {

    /** BMS field {@code TRNNAME} ({@code PIC X(4)}): transaction-name header label. */
    @Size(max = 4)
    private String trnname;

    /** BMS field {@code TITLE01} ({@code PIC X(40)}): first title header line. */
    @Size(max = 40)
    private String title01;

    /** BMS field {@code CURDATE} ({@code PIC X(8)}): current-date header text. */
    @Size(max = 8)
    private String curdate;

    /** BMS field {@code PGMNAME} ({@code PIC X(8)}): program-name header label. */
    @Size(max = 8)
    private String pgmname;

    /** BMS field {@code TITLE02} ({@code PIC X(40)}): second title header line. */
    @Size(max = 40)
    private String title02;

    /** BMS field {@code CURTIME} ({@code PIC X(8)}): current-time header text. */
    @Size(max = 8)
    private String curtime;

    /** BMS field {@code ACTIDIN} ({@code PIC X(11)}): account-id entry field. */
    @Size(max = 11)
    private String actidin;

    /** BMS field {@code CARDNIN} ({@code PIC X(16)}): card-number entry field. */
    @Size(max = 16)
    private String cardnin;

    /** BMS field {@code TTYPCD} ({@code PIC X(2)}): transaction type code entry. */
    @Size(max = 2)
    private String ttypcd;

    /** BMS field {@code TCATCD} ({@code PIC X(4)}): transaction category code entry. */
    @Size(max = 4)
    private String tcatcd;

    /** BMS field {@code TRNSRC} ({@code PIC X(10)}): transaction source entry. */
    @Size(max = 10)
    private String trnsrc;

    /** BMS field {@code TDESC} ({@code PIC X(60)}): transaction description entry. */
    @Size(max = 60)
    private String tdesc;

    /**
     * BMS field {@code TRNAMT} ({@code PIC X(12)}): display-formatted transaction amount.
     *
     * <p>Held as a {@link String} display amount and never as {@code BigDecimal},
     * {@code double} or {@code float}. The controller parses this submitted display
     * string into a {@code BigDecimal} when building the {@code Transaction} domain
     * entity on save, and formats the persisted amount back into this field when
     * populating the form, so the on-screen amount is preserved byte-for-byte.</p>
     */
    @Size(max = 12)
    private String trnamt;

    /** BMS field {@code TORIGDT} ({@code PIC X(10)}): transaction original date entry. */
    @Size(max = 10)
    private String torigdt;

    /** BMS field {@code TPROCDT} ({@code PIC X(10)}): transaction processing date entry. */
    @Size(max = 10)
    private String tprocdt;

    /** BMS field {@code MID} ({@code PIC X(9)}): merchant-id entry field. */
    @Size(max = 9)
    private String mid;

    /** BMS field {@code MNAME} ({@code PIC X(30)}): merchant-name entry field. */
    @Size(max = 30)
    private String mname;

    /** BMS field {@code MCITY} ({@code PIC X(25)}): merchant-city entry field. */
    @Size(max = 25)
    private String mcity;

    /** BMS field {@code MZIP} ({@code PIC X(10)}): merchant-zip entry field. */
    @Size(max = 10)
    private String mzip;

    /** BMS field {@code CONFIRM} ({@code PIC X(1)}): confirm-add input (Y/N). */
    @Size(max = 1)
    private String confirm;

    /** BMS field {@code ERRMSG} ({@code PIC X(78)}): error / status message line. */
    @Size(max = 78)
    private String errmsg;

    /** Creates an empty transaction-add screen form. */
    public COTRN02Form() {
    }

    /**
     * Returns the transaction-name header ({@code TRNNAME}).
     *
     * @return the transaction-name header text, or {@code null} if unset
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction-name header ({@code TRNNAME}).
     *
     * @param trnname the transaction-name header text
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the first title header line ({@code TITLE01}).
     *
     * @return the first title header line, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first title header line ({@code TITLE01}).
     *
     * @param title01 the first title header line
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current-date header ({@code CURDATE}).
     *
     * @return the current-date header text, or {@code null} if unset
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current-date header ({@code CURDATE}).
     *
     * @param curdate the current-date header text
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program-name header ({@code PGMNAME}).
     *
     * @return the program-name header text, or {@code null} if unset
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program-name header ({@code PGMNAME}).
     *
     * @param pgmname the program-name header text
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the second title header line ({@code TITLE02}).
     *
     * @return the second title header line, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second title header line ({@code TITLE02}).
     *
     * @param title02 the second title header line
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current-time header ({@code CURTIME}).
     *
     * @return the current-time header text, or {@code null} if unset
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current-time header ({@code CURTIME}).
     *
     * @param curtime the current-time header text
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the account-id entry ({@code ACTIDIN}).
     *
     * @return the account-id entry text, or {@code null} if unset
     */
    public String getActidin() {
        return actidin;
    }

    /**
     * Sets the account-id entry ({@code ACTIDIN}).
     *
     * @param actidin the account-id entry text
     */
    public void setActidin(String actidin) {
        this.actidin = actidin;
    }

    /**
     * Returns the card-number entry ({@code CARDNIN}).
     *
     * @return the card-number entry text, or {@code null} if unset
     */
    public String getCardnin() {
        return cardnin;
    }

    /**
     * Sets the card-number entry ({@code CARDNIN}).
     *
     * @param cardnin the card-number entry text
     */
    public void setCardnin(String cardnin) {
        this.cardnin = cardnin;
    }

    /**
     * Returns the transaction type code ({@code TTYPCD}).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTtypcd() {
        return ttypcd;
    }

    /**
     * Sets the transaction type code ({@code TTYPCD}).
     *
     * @param ttypcd the transaction type code
     */
    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    /**
     * Returns the transaction category code ({@code TCATCD}).
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public String getTcatcd() {
        return tcatcd;
    }

    /**
     * Sets the transaction category code ({@code TCATCD}).
     *
     * @param tcatcd the transaction category code
     */
    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    /**
     * Returns the transaction source ({@code TRNSRC}).
     *
     * @return the transaction source text, or {@code null} if unset
     */
    public String getTrnsrc() {
        return trnsrc;
    }

    /**
     * Sets the transaction source ({@code TRNSRC}).
     *
     * @param trnsrc the transaction source text
     */
    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    /**
     * Returns the transaction description ({@code TDESC}).
     *
     * @return the transaction description text, or {@code null} if unset
     */
    public String getTdesc() {
        return tdesc;
    }

    /**
     * Sets the transaction description ({@code TDESC}).
     *
     * @param tdesc the transaction description text
     */
    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    /**
     * Returns the display-formatted transaction amount ({@code TRNAMT}).
     *
     * @return the transaction-amount display string, or {@code null} if unset
     */
    public String getTrnamt() {
        return trnamt;
    }

    /**
     * Sets the display-formatted transaction amount ({@code TRNAMT}).
     *
     * @param trnamt the transaction-amount display string
     */
    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    /**
     * Returns the transaction original date ({@code TORIGDT}).
     *
     * @return the transaction original date text, or {@code null} if unset
     */
    public String getTorigdt() {
        return torigdt;
    }

    /**
     * Sets the transaction original date ({@code TORIGDT}).
     *
     * @param torigdt the transaction original date text
     */
    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    /**
     * Returns the transaction processing date ({@code TPROCDT}).
     *
     * @return the transaction processing date text, or {@code null} if unset
     */
    public String getTprocdt() {
        return tprocdt;
    }

    /**
     * Sets the transaction processing date ({@code TPROCDT}).
     *
     * @param tprocdt the transaction processing date text
     */
    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    /**
     * Returns the merchant id ({@code MID}).
     *
     * @return the merchant-id text, or {@code null} if unset
     */
    public String getMid() {
        return mid;
    }

    /**
     * Sets the merchant id ({@code MID}).
     *
     * @param mid the merchant-id text
     */
    public void setMid(String mid) {
        this.mid = mid;
    }

    /**
     * Returns the merchant name ({@code MNAME}).
     *
     * @return the merchant-name text, or {@code null} if unset
     */
    public String getMname() {
        return mname;
    }

    /**
     * Sets the merchant name ({@code MNAME}).
     *
     * @param mname the merchant-name text
     */
    public void setMname(String mname) {
        this.mname = mname;
    }

    /**
     * Returns the merchant city ({@code MCITY}).
     *
     * @return the merchant-city text, or {@code null} if unset
     */
    public String getMcity() {
        return mcity;
    }

    /**
     * Sets the merchant city ({@code MCITY}).
     *
     * @param mcity the merchant-city text
     */
    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    /**
     * Returns the merchant zip ({@code MZIP}).
     *
     * @return the merchant-zip text, or {@code null} if unset
     */
    public String getMzip() {
        return mzip;
    }

    /**
     * Sets the merchant zip ({@code MZIP}).
     *
     * @param mzip the merchant-zip text
     */
    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    /**
     * Returns the confirm-add input ({@code CONFIRM}).
     *
     * @return the confirm-add input (Y/N), or {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the confirm-add input ({@code CONFIRM}).
     *
     * @param confirm the confirm-add input (Y/N)
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns the error / status message line ({@code ERRMSG}).
     *
     * @return the error / status message line, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error / status message line ({@code ERRMSG}).
     *
     * @param errmsg the error / status message line
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * BMS {@code ERRMSGC} &mdash; the colour attribute of the {@link #errmsg} line, reproducing the
     * COBOL {@code MOVE DFHxxx TO ERRMSGC OF COTRN2AO}. Holds the semantic 3270 colour token the
     * template uses to pick the message colour class: {@code "red"} (error; the BMS map default
     * {@code COLOR=RED}), {@code "green"} (COBOL {@code DFHGREEN}, the "Transaction added
     * successfully" line), or {@code "neutral"} (COBOL {@code DFHNEUTR}). Render-only (never a bound
     * input; excluded from the {@code @InitBinder} allow-list); defaults to {@code "red"} so any
     * path that does not explicitly set a colour reproduces the legacy default red line.
     */
    private String errmsgColor = "red";

    public String getErrmsgColor() {
        return errmsgColor;
    }

    public void setErrmsgColor(String errmsgColor) {
        this.errmsgColor = errmsgColor;
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token. Screen-form fields (which may include card numbers, account and
     * customer identifiers, names, balances, or credentials) are never rendered, so form state cannot
     * leak into logs or error messages (CWE-532; review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "COTRN02Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
