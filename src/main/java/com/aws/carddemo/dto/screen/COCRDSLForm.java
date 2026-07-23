package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Card-detail view screen form.
 *
 * <p>Server-side form-backing bean for the card view / detail screen. It exposes the
 * <em>value</em> fields of the BMS symbolic map as bindable JavaBean {@code String}
 * properties, preserving the 24x80 BMS field-length contract so the Thymeleaf template
 * {@code src/main/resources/templates/COCRDSL.html} can bind field-for-field to the
 * original 3270 layout. Each {@link Size} constraint reproduces the corresponding BMS
 * {@code PIC X(n)} field-length edit.</p>
 *
 * <p>Only the map's data (value) fields are modeled here; BMS attribute/plumbing bytes
 * (the {@code L}/{@code F}/{@code A}/{@code C}/{@code P}/{@code H}/{@code V} suffixed
 * items) are terminal-rendering concerns handled elsewhere and are intentionally omitted.</p>
 *
 * <p>Origin: legacy/cpy-bms/COCRDSL.CPY (BMS mapset COCRDSL, map CCRDSLA)</p>
 */
public class COCRDSLForm {

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

    /** ACCTSIDI — account id (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctsid;

    /** CARDSIDI — card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String cardsid;

    /** CRDNAMEI — name embossed on card (BMS PIC X(50)). */
    @Size(max = 50)
    private String crdname;

    /** CRDSTCDI — card active status code (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstcd;

    /** EXPMONI — card expiry month (BMS PIC X(2)). */
    @Size(max = 2)
    private String expmon;

    /** EXPYEARI — card expiry year (BMS PIC X(4)). */
    @Size(max = 4)
    private String expyear;

    /** INFOMSGI — informational message line (BMS PIC X(40)). */
    @Size(max = 40)
    private String infomsg;

    /** ERRMSGI — error message line (BMS PIC X(80); X(80) on this screen, not X(78)). */
    @Size(max = 80)
    private String errmsg;

    /** FKEYSI — PF-key legend text (BMS PIC X(75)). */
    @Size(max = 75)
    private String fkeys;

    /**
     * Creates an empty card-detail view form. Required for JavaBean/Thymeleaf binding.
     */
    public COCRDSLForm() {
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
     * Returns the account id value (BMS ACCTSIDI).
     *
     * @return the account id, or {@code null} if unset
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Sets the account id value (BMS ACCTSIDI).
     *
     * @param acctsid the account id
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = acctsid;
    }

    /**
     * Returns the card number value (BMS CARDSIDI).
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardsid() {
        return cardsid;
    }

    /**
     * Sets the card number value (BMS CARDSIDI).
     *
     * @param cardsid the card number
     */
    public void setCardsid(String cardsid) {
        this.cardsid = cardsid;
    }

    /**
     * Returns the name embossed on the card (BMS CRDNAMEI).
     *
     * @return the embossed card name, or {@code null} if unset
     */
    public String getCrdname() {
        return crdname;
    }

    /**
     * Sets the name embossed on the card (BMS CRDNAMEI).
     *
     * @param crdname the embossed card name
     */
    public void setCrdname(String crdname) {
        this.crdname = crdname;
    }

    /**
     * Returns the card active status code (BMS CRDSTCDI).
     *
     * @return the card status code, or {@code null} if unset
     */
    public String getCrdstcd() {
        return crdstcd;
    }

    /**
     * Sets the card active status code (BMS CRDSTCDI).
     *
     * @param crdstcd the card status code
     */
    public void setCrdstcd(String crdstcd) {
        this.crdstcd = crdstcd;
    }

    /**
     * Returns the card expiry month (BMS EXPMONI).
     *
     * @return the expiry month, or {@code null} if unset
     */
    public String getExpmon() {
        return expmon;
    }

    /**
     * Sets the card expiry month (BMS EXPMONI).
     *
     * @param expmon the expiry month
     */
    public void setExpmon(String expmon) {
        this.expmon = expmon;
    }

    /**
     * Returns the card expiry year (BMS EXPYEARI).
     *
     * @return the expiry year, or {@code null} if unset
     */
    public String getExpyear() {
        return expyear;
    }

    /**
     * Sets the card expiry year (BMS EXPYEARI).
     *
     * @param expyear the expiry year
     */
    public void setExpyear(String expyear) {
        this.expyear = expyear;
    }

    /**
     * Returns the informational message line (BMS INFOMSGI).
     *
     * @return the informational message, or {@code null} if unset
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Sets the informational message line (BMS INFOMSGI).
     *
     * @param infomsg the informational message
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = infomsg;
    }

    /**
     * Returns the error message line (BMS ERRMSGI).
     *
     * @return the error message, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line (BMS ERRMSGI).
     *
     * @param errmsg the error message
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns the PF-key legend text (BMS FKEYSI).
     *
     * @return the PF-key legend text, or {@code null} if unset
     */
    public String getFkeys() {
        return fkeys;
    }

    /**
     * Sets the PF-key legend text (BMS FKEYSI).
     *
     * @param fkeys the PF-key legend text
     */
    public void setFkeys(String fkeys) {
        this.fkeys = fkeys;
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
        return "COCRDSLForm@" + Integer.toHexString(System.identityHashCode(this));
    }
}
