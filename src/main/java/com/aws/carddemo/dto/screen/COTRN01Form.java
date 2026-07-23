package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Transaction-view screen form.
 *
 * <p>Server-side form-backing bean for the transaction view / detail screen. It exposes
 * the <em>value</em> fields of the BMS symbolic map as bindable JavaBean {@code String}
 * properties, preserving the 24x80 BMS field-length contract so the Thymeleaf template
 * {@code src/main/resources/templates/COTRN01.html} can bind field-for-field to the
 * original 3270 layout. Each {@link Size} constraint reproduces the corresponding BMS
 * {@code PIC X(n)} field-length edit.</p>
 *
 * <p>Only the map's data (value) fields are modeled here; BMS attribute/plumbing bytes
 * (the {@code L}/{@code F}/{@code A}/{@code C}/{@code P}/{@code H}/{@code V} suffixed
 * items) are terminal-rendering concerns handled elsewhere and are intentionally omitted.</p>
 *
 * <p>The monetary field {@code trnamt} is intentionally a display-edited {@code String}
 * (never a numeric type); the authoritative {@link java.math.BigDecimal} amount lives on
 * the {@code Transaction} domain entity and is formatted into this display String by the
 * controller when populating the form.</p>
 *
 * <p>Origin: legacy/cpy-bms/COTRN01.CPY (BMS mapset COTRN01, map COTRN1A)</p>
 */
public class COTRN01Form {

    /** TRNNAMEI — transaction name header (BMS PIC X(4)). */
    @Size(max = 4)
    private String trnname;

    /** TITLE01I — screen title line 1 header (BMS PIC X(40)). */
    @Size(max = 40)
    private String title01;

    /** CURDATEI — current date header (BMS PIC X(8)). */
    @Size(max = 8)
    private String curdate;

    /** PGMNAMEI — program name header (BMS PIC X(8)). */
    @Size(max = 8)
    private String pgmname;

    /** TITLE02I — screen title line 2 header (BMS PIC X(40)). */
    @Size(max = 40)
    private String title02;

    /** CURTIMEI — current time header (BMS PIC X(8)). */
    @Size(max = 8)
    private String curtime;

    /** TRNIDINI — transaction id search input (BMS PIC X(16)). */
    @Size(max = 16)
    private String trnidin;

    /** TRNIDI — transaction id displayed (BMS PIC X(16)). */
    @Size(max = 16)
    private String trnid;

    /** CARDNUMI — card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String cardnum;

    /** TTYPCDI — transaction type code (BMS PIC X(2)). */
    @Size(max = 2)
    private String ttypcd;

    /** TCATCDI — transaction category code (BMS PIC X(4)). */
    @Size(max = 4)
    private String tcatcd;

    /** TRNSRCI — transaction source (BMS PIC X(10)). */
    @Size(max = 10)
    private String trnsrc;

    /** TDESCI — transaction description (BMS PIC X(60)). */
    @Size(max = 60)
    private String tdesc;

    /**
     * TRNAMTI — transaction amount (BMS PIC X(12)).
     *
     * <p>Money-as-String rule: this is a display-edited amount held as a {@code String},
     * never a {@code BigDecimal}/{@code float}/{@code double}. The controller formats the
     * authoritative {@code Transaction} entity amount into this display String.</p>
     */
    @Size(max = 12)
    private String trnamt;

    /** TORIGDTI — transaction original date (BMS PIC X(10)). */
    @Size(max = 10)
    private String torigdt;

    /** TPROCDTI — transaction processing date (BMS PIC X(10)). */
    @Size(max = 10)
    private String tprocdt;

    /** MIDI — merchant id (BMS PIC X(9)). */
    @Size(max = 9)
    private String mid;

    /** MNAMEI — merchant name (BMS PIC X(30)). */
    @Size(max = 30)
    private String mname;

    /** MCITYI — merchant city (BMS PIC X(25)). */
    @Size(max = 25)
    private String mcity;

    /** MZIPI — merchant zip (BMS PIC X(10)). */
    @Size(max = 10)
    private String mzip;

    /** ERRMSGI — error message line (BMS PIC X(78)). */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty transaction-view form. Required for JavaBean/Thymeleaf binding.
     */
    public COTRN01Form() {
    }

    /**
     * Returns the transaction name header value (BMS TRNNAMEI).
     *
     * @return the transaction name header, or {@code null} if unset
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction name header value (BMS TRNNAMEI).
     *
     * @param trnname the transaction name header
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the screen title line 1 header value (BMS TITLE01I).
     *
     * @return the title line 1 header, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the screen title line 1 header value (BMS TITLE01I).
     *
     * @param title01 the title line 1 header
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current date header value (BMS CURDATEI).
     *
     * @return the current date header, or {@code null} if unset
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current date header value (BMS CURDATEI).
     *
     * @param curdate the current date header
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program name header value (BMS PGMNAMEI).
     *
     * @return the program name header, or {@code null} if unset
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program name header value (BMS PGMNAMEI).
     *
     * @param pgmname the program name header
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the screen title line 2 header value (BMS TITLE02I).
     *
     * @return the title line 2 header, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the screen title line 2 header value (BMS TITLE02I).
     *
     * @param title02 the title line 2 header
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current time header value (BMS CURTIMEI).
     *
     * @return the current time header, or {@code null} if unset
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time header value (BMS CURTIMEI).
     *
     * @param curtime the current time header
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the transaction id search input value (BMS TRNIDINI).
     *
     * @return the transaction id search input, or {@code null} if unset
     */
    public String getTrnidin() {
        return trnidin;
    }

    /**
     * Sets the transaction id search input value (BMS TRNIDINI).
     *
     * @param trnidin the transaction id search input
     */
    public void setTrnidin(String trnidin) {
        this.trnidin = trnidin;
    }

    /**
     * Returns the displayed transaction id value (BMS TRNIDI).
     *
     * @return the displayed transaction id, or {@code null} if unset
     */
    public String getTrnid() {
        return trnid;
    }

    /**
     * Sets the displayed transaction id value (BMS TRNIDI).
     *
     * @param trnid the displayed transaction id
     */
    public void setTrnid(String trnid) {
        this.trnid = trnid;
    }

    /**
     * Returns the card number value (BMS CARDNUMI).
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardnum() {
        return cardnum;
    }

    /**
     * Sets the card number value (BMS CARDNUMI).
     *
     * @param cardnum the card number
     */
    public void setCardnum(String cardnum) {
        this.cardnum = cardnum;
    }

    /**
     * Returns the transaction type code value (BMS TTYPCDI).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTtypcd() {
        return ttypcd;
    }

    /**
     * Sets the transaction type code value (BMS TTYPCDI).
     *
     * @param ttypcd the transaction type code
     */
    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    /**
     * Returns the transaction category code value (BMS TCATCDI).
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public String getTcatcd() {
        return tcatcd;
    }

    /**
     * Sets the transaction category code value (BMS TCATCDI).
     *
     * @param tcatcd the transaction category code
     */
    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    /**
     * Returns the transaction source value (BMS TRNSRCI).
     *
     * @return the transaction source, or {@code null} if unset
     */
    public String getTrnsrc() {
        return trnsrc;
    }

    /**
     * Sets the transaction source value (BMS TRNSRCI).
     *
     * @param trnsrc the transaction source
     */
    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    /**
     * Returns the transaction description value (BMS TDESCI).
     *
     * @return the transaction description, or {@code null} if unset
     */
    public String getTdesc() {
        return tdesc;
    }

    /**
     * Sets the transaction description value (BMS TDESCI).
     *
     * @param tdesc the transaction description
     */
    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    /**
     * Returns the display-edited transaction amount value (BMS TRNAMTI).
     *
     * <p>Money-as-String: the value is a formatted display String, not a numeric type.</p>
     *
     * @return the transaction amount display String, or {@code null} if unset
     */
    public String getTrnamt() {
        return trnamt;
    }

    /**
     * Sets the display-edited transaction amount value (BMS TRNAMTI).
     *
     * <p>Money-as-String: the value is a formatted display String, not a numeric type.</p>
     *
     * @param trnamt the transaction amount display String
     */
    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    /**
     * Returns the transaction original date value (BMS TORIGDTI).
     *
     * @return the transaction original date, or {@code null} if unset
     */
    public String getTorigdt() {
        return torigdt;
    }

    /**
     * Sets the transaction original date value (BMS TORIGDTI).
     *
     * @param torigdt the transaction original date
     */
    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    /**
     * Returns the transaction processing date value (BMS TPROCDTI).
     *
     * @return the transaction processing date, or {@code null} if unset
     */
    public String getTprocdt() {
        return tprocdt;
    }

    /**
     * Sets the transaction processing date value (BMS TPROCDTI).
     *
     * @param tprocdt the transaction processing date
     */
    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    /**
     * Returns the merchant id value (BMS MIDI).
     *
     * @return the merchant id, or {@code null} if unset
     */
    public String getMid() {
        return mid;
    }

    /**
     * Sets the merchant id value (BMS MIDI).
     *
     * @param mid the merchant id
     */
    public void setMid(String mid) {
        this.mid = mid;
    }

    /**
     * Returns the merchant name value (BMS MNAMEI).
     *
     * @return the merchant name, or {@code null} if unset
     */
    public String getMname() {
        return mname;
    }

    /**
     * Sets the merchant name value (BMS MNAMEI).
     *
     * @param mname the merchant name
     */
    public void setMname(String mname) {
        this.mname = mname;
    }

    /**
     * Returns the merchant city value (BMS MCITYI).
     *
     * @return the merchant city, or {@code null} if unset
     */
    public String getMcity() {
        return mcity;
    }

    /**
     * Sets the merchant city value (BMS MCITYI).
     *
     * @param mcity the merchant city
     */
    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    /**
     * Returns the merchant zip value (BMS MZIPI).
     *
     * @return the merchant zip, or {@code null} if unset
     */
    public String getMzip() {
        return mzip;
    }

    /**
     * Sets the merchant zip value (BMS MZIPI).
     *
     * @param mzip the merchant zip
     */
    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    /**
     * Returns the error message line value (BMS ERRMSGI).
     *
     * @return the error message, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line value (BMS ERRMSGI).
     *
     * @param errmsg the error message
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
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
        return "COTRN01Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
