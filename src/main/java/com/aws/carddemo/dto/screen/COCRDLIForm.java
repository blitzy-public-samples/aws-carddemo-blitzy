package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Card-list screen form.
 *
 * <p>Server-side form-backing bean for the card list screen. It exposes the
 * <em>value</em> fields of the BMS symbolic map as bindable JavaBean {@code String}
 * properties, preserving the 24x80 BMS field-length contract so the Thymeleaf template
 * {@code src/main/resources/templates/COCRDLI.html} can bind field-for-field to the
 * original 3270 layout. Each {@link Size} constraint reproduces the corresponding BMS
 * {@code PIC X(n)} field-length edit.</p>
 *
 * <p>Only the map's data (value) fields are modeled here; BMS attribute/plumbing bytes
 * (the {@code L}/{@code F}/{@code A}/{@code C}/{@code P}/{@code H}/{@code V} suffixed
 * items) are terminal-rendering concerns handled elsewhere and are intentionally omitted.</p>
 *
 * <p>Row-1 asymmetry: the screen renders seven repeated card rows, but row 1 has
 * <strong>no</strong> status-protect field. Only {@code crdstp2} through {@code crdstp7}
 * exist; there is intentionally no {@code crdstp1} property. This mirrors the copybook
 * exactly and is preserved for traceability.</p>
 *
 * <p>Origin: legacy/cpy-bms/COCRDLI.CPY (BMS mapset COCRDLI, map CCRDLIA)</p>
 */
public class COCRDLIForm {

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

    /** PAGENOI — page number (BMS PIC X(3)). */
    @Size(max = 3)
    private String pageno;

    /** ACCTSIDI — account id search filter (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctsid;

    /** CARDSIDI — card number search filter (BMS PIC X(16)). */
    @Size(max = 16)
    private String cardsid;

    /** CRDSEL1I — row 1 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel1;

    /** ACCTNO1I — row 1 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno1;

    /** CRDNUM1I — row 1 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum1;

    /** CRDSTS1I — row 1 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts1;

    /** CRDSEL2I — row 2 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel2;

    /** CRDSTP2I — row 2 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp2;

    /** ACCTNO2I — row 2 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno2;

    /** CRDNUM2I — row 2 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum2;

    /** CRDSTS2I — row 2 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts2;

    /** CRDSEL3I — row 3 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel3;

    /** CRDSTP3I — row 3 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp3;

    /** ACCTNO3I — row 3 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno3;

    /** CRDNUM3I — row 3 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum3;

    /** CRDSTS3I — row 3 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts3;

    /** CRDSEL4I — row 4 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel4;

    /** CRDSTP4I — row 4 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp4;

    /** ACCTNO4I — row 4 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno4;

    /** CRDNUM4I — row 4 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum4;

    /** CRDSTS4I — row 4 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts4;

    /** CRDSEL5I — row 5 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel5;

    /** CRDSTP5I — row 5 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp5;

    /** ACCTNO5I — row 5 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno5;

    /** CRDNUM5I — row 5 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum5;

    /** CRDSTS5I — row 5 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts5;

    /** CRDSEL6I — row 6 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel6;

    /** CRDSTP6I — row 6 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp6;

    /** ACCTNO6I — row 6 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno6;

    /** CRDNUM6I — row 6 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum6;

    /** CRDSTS6I — row 6 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts6;

    /** CRDSEL7I — row 7 selection flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsel7;

    /** CRDSTP7I — row 7 status-protect indicator (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdstp7;

    /** ACCTNO7I — row 7 account number (BMS PIC X(11)). */
    @Size(max = 11)
    private String acctno7;

    /** CRDNUM7I — row 7 card number (BMS PIC X(16)). */
    @Size(max = 16)
    private String crdnum7;

    /** CRDSTS7I — row 7 card status (BMS PIC X(1)). */
    @Size(max = 1)
    private String crdsts7;

    /** INFOMSGI — informational message line (BMS PIC X(45)). */
    @Size(max = 45)
    private String infomsg;

    /** ERRMSGI — error message line (BMS PIC X(78)). */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty card-list screen form. Required for JavaBean/Thymeleaf binding.
     */
    public COCRDLIForm() {
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
     * @return the screen title line 1 header, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the screen title line 1 header value (BMS TITLE01I).
     *
     * @param title01 the screen title line 1 header
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
     * @return the screen title line 2 header, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the screen title line 2 header value (BMS TITLE02I).
     *
     * @param title02 the screen title line 2 header
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
     * Returns the page number value (BMS PAGENOI).
     *
     * @return the page number, or {@code null} if unset
     */
    public String getPageno() {
        return pageno;
    }

    /**
     * Sets the page number value (BMS PAGENOI).
     *
     * @param pageno the page number
     */
    public void setPageno(String pageno) {
        this.pageno = pageno;
    }

    /**
     * Returns the account id search filter value (BMS ACCTSIDI).
     *
     * @return the account id search filter, or {@code null} if unset
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Sets the account id search filter value (BMS ACCTSIDI).
     *
     * @param acctsid the account id search filter
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = acctsid;
    }

    /**
     * Returns the card number search filter value (BMS CARDSIDI).
     *
     * @return the card number search filter, or {@code null} if unset
     */
    public String getCardsid() {
        return cardsid;
    }

    /**
     * Sets the card number search filter value (BMS CARDSIDI).
     *
     * @param cardsid the card number search filter
     */
    public void setCardsid(String cardsid) {
        this.cardsid = cardsid;
    }

    /**
     * Returns the row 1 selection flag value (BMS CRDSEL1I).
     *
     * @return the row 1 selection flag, or {@code null} if unset
     */
    public String getCrdsel1() {
        return crdsel1;
    }

    /**
     * Sets the row 1 selection flag value (BMS CRDSEL1I).
     *
     * @param crdsel1 the row 1 selection flag
     */
    public void setCrdsel1(String crdsel1) {
        this.crdsel1 = crdsel1;
    }

    /**
     * Returns the row 1 account number value (BMS ACCTNO1I).
     *
     * @return the row 1 account number, or {@code null} if unset
     */
    public String getAcctno1() {
        return acctno1;
    }

    /**
     * Sets the row 1 account number value (BMS ACCTNO1I).
     *
     * @param acctno1 the row 1 account number
     */
    public void setAcctno1(String acctno1) {
        this.acctno1 = acctno1;
    }

    /**
     * Returns the row 1 card number value (BMS CRDNUM1I).
     *
     * @return the row 1 card number, or {@code null} if unset
     */
    public String getCrdnum1() {
        return crdnum1;
    }

    /**
     * Sets the row 1 card number value (BMS CRDNUM1I).
     *
     * @param crdnum1 the row 1 card number
     */
    public void setCrdnum1(String crdnum1) {
        this.crdnum1 = crdnum1;
    }

    /**
     * Returns the row 1 card status value (BMS CRDSTS1I).
     *
     * @return the row 1 card status, or {@code null} if unset
     */
    public String getCrdsts1() {
        return crdsts1;
    }

    /**
     * Sets the row 1 card status value (BMS CRDSTS1I).
     *
     * @param crdsts1 the row 1 card status
     */
    public void setCrdsts1(String crdsts1) {
        this.crdsts1 = crdsts1;
    }

    /**
     * Returns the row 2 selection flag value (BMS CRDSEL2I).
     *
     * @return the row 2 selection flag, or {@code null} if unset
     */
    public String getCrdsel2() {
        return crdsel2;
    }

    /**
     * Sets the row 2 selection flag value (BMS CRDSEL2I).
     *
     * @param crdsel2 the row 2 selection flag
     */
    public void setCrdsel2(String crdsel2) {
        this.crdsel2 = crdsel2;
    }

    /**
     * Returns the row 2 status-protect indicator value (BMS CRDSTP2I).
     *
     * @return the row 2 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp2() {
        return crdstp2;
    }

    /**
     * Sets the row 2 status-protect indicator value (BMS CRDSTP2I).
     *
     * @param crdstp2 the row 2 status-protect indicator
     */
    public void setCrdstp2(String crdstp2) {
        this.crdstp2 = crdstp2;
    }

    /**
     * Returns the row 2 account number value (BMS ACCTNO2I).
     *
     * @return the row 2 account number, or {@code null} if unset
     */
    public String getAcctno2() {
        return acctno2;
    }

    /**
     * Sets the row 2 account number value (BMS ACCTNO2I).
     *
     * @param acctno2 the row 2 account number
     */
    public void setAcctno2(String acctno2) {
        this.acctno2 = acctno2;
    }

    /**
     * Returns the row 2 card number value (BMS CRDNUM2I).
     *
     * @return the row 2 card number, or {@code null} if unset
     */
    public String getCrdnum2() {
        return crdnum2;
    }

    /**
     * Sets the row 2 card number value (BMS CRDNUM2I).
     *
     * @param crdnum2 the row 2 card number
     */
    public void setCrdnum2(String crdnum2) {
        this.crdnum2 = crdnum2;
    }

    /**
     * Returns the row 2 card status value (BMS CRDSTS2I).
     *
     * @return the row 2 card status, or {@code null} if unset
     */
    public String getCrdsts2() {
        return crdsts2;
    }

    /**
     * Sets the row 2 card status value (BMS CRDSTS2I).
     *
     * @param crdsts2 the row 2 card status
     */
    public void setCrdsts2(String crdsts2) {
        this.crdsts2 = crdsts2;
    }

    /**
     * Returns the row 3 selection flag value (BMS CRDSEL3I).
     *
     * @return the row 3 selection flag, or {@code null} if unset
     */
    public String getCrdsel3() {
        return crdsel3;
    }

    /**
     * Sets the row 3 selection flag value (BMS CRDSEL3I).
     *
     * @param crdsel3 the row 3 selection flag
     */
    public void setCrdsel3(String crdsel3) {
        this.crdsel3 = crdsel3;
    }

    /**
     * Returns the row 3 status-protect indicator value (BMS CRDSTP3I).
     *
     * @return the row 3 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp3() {
        return crdstp3;
    }

    /**
     * Sets the row 3 status-protect indicator value (BMS CRDSTP3I).
     *
     * @param crdstp3 the row 3 status-protect indicator
     */
    public void setCrdstp3(String crdstp3) {
        this.crdstp3 = crdstp3;
    }

    /**
     * Returns the row 3 account number value (BMS ACCTNO3I).
     *
     * @return the row 3 account number, or {@code null} if unset
     */
    public String getAcctno3() {
        return acctno3;
    }

    /**
     * Sets the row 3 account number value (BMS ACCTNO3I).
     *
     * @param acctno3 the row 3 account number
     */
    public void setAcctno3(String acctno3) {
        this.acctno3 = acctno3;
    }

    /**
     * Returns the row 3 card number value (BMS CRDNUM3I).
     *
     * @return the row 3 card number, or {@code null} if unset
     */
    public String getCrdnum3() {
        return crdnum3;
    }

    /**
     * Sets the row 3 card number value (BMS CRDNUM3I).
     *
     * @param crdnum3 the row 3 card number
     */
    public void setCrdnum3(String crdnum3) {
        this.crdnum3 = crdnum3;
    }

    /**
     * Returns the row 3 card status value (BMS CRDSTS3I).
     *
     * @return the row 3 card status, or {@code null} if unset
     */
    public String getCrdsts3() {
        return crdsts3;
    }

    /**
     * Sets the row 3 card status value (BMS CRDSTS3I).
     *
     * @param crdsts3 the row 3 card status
     */
    public void setCrdsts3(String crdsts3) {
        this.crdsts3 = crdsts3;
    }

    /**
     * Returns the row 4 selection flag value (BMS CRDSEL4I).
     *
     * @return the row 4 selection flag, or {@code null} if unset
     */
    public String getCrdsel4() {
        return crdsel4;
    }

    /**
     * Sets the row 4 selection flag value (BMS CRDSEL4I).
     *
     * @param crdsel4 the row 4 selection flag
     */
    public void setCrdsel4(String crdsel4) {
        this.crdsel4 = crdsel4;
    }

    /**
     * Returns the row 4 status-protect indicator value (BMS CRDSTP4I).
     *
     * @return the row 4 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp4() {
        return crdstp4;
    }

    /**
     * Sets the row 4 status-protect indicator value (BMS CRDSTP4I).
     *
     * @param crdstp4 the row 4 status-protect indicator
     */
    public void setCrdstp4(String crdstp4) {
        this.crdstp4 = crdstp4;
    }

    /**
     * Returns the row 4 account number value (BMS ACCTNO4I).
     *
     * @return the row 4 account number, or {@code null} if unset
     */
    public String getAcctno4() {
        return acctno4;
    }

    /**
     * Sets the row 4 account number value (BMS ACCTNO4I).
     *
     * @param acctno4 the row 4 account number
     */
    public void setAcctno4(String acctno4) {
        this.acctno4 = acctno4;
    }

    /**
     * Returns the row 4 card number value (BMS CRDNUM4I).
     *
     * @return the row 4 card number, or {@code null} if unset
     */
    public String getCrdnum4() {
        return crdnum4;
    }

    /**
     * Sets the row 4 card number value (BMS CRDNUM4I).
     *
     * @param crdnum4 the row 4 card number
     */
    public void setCrdnum4(String crdnum4) {
        this.crdnum4 = crdnum4;
    }

    /**
     * Returns the row 4 card status value (BMS CRDSTS4I).
     *
     * @return the row 4 card status, or {@code null} if unset
     */
    public String getCrdsts4() {
        return crdsts4;
    }

    /**
     * Sets the row 4 card status value (BMS CRDSTS4I).
     *
     * @param crdsts4 the row 4 card status
     */
    public void setCrdsts4(String crdsts4) {
        this.crdsts4 = crdsts4;
    }

    /**
     * Returns the row 5 selection flag value (BMS CRDSEL5I).
     *
     * @return the row 5 selection flag, or {@code null} if unset
     */
    public String getCrdsel5() {
        return crdsel5;
    }

    /**
     * Sets the row 5 selection flag value (BMS CRDSEL5I).
     *
     * @param crdsel5 the row 5 selection flag
     */
    public void setCrdsel5(String crdsel5) {
        this.crdsel5 = crdsel5;
    }

    /**
     * Returns the row 5 status-protect indicator value (BMS CRDSTP5I).
     *
     * @return the row 5 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp5() {
        return crdstp5;
    }

    /**
     * Sets the row 5 status-protect indicator value (BMS CRDSTP5I).
     *
     * @param crdstp5 the row 5 status-protect indicator
     */
    public void setCrdstp5(String crdstp5) {
        this.crdstp5 = crdstp5;
    }

    /**
     * Returns the row 5 account number value (BMS ACCTNO5I).
     *
     * @return the row 5 account number, or {@code null} if unset
     */
    public String getAcctno5() {
        return acctno5;
    }

    /**
     * Sets the row 5 account number value (BMS ACCTNO5I).
     *
     * @param acctno5 the row 5 account number
     */
    public void setAcctno5(String acctno5) {
        this.acctno5 = acctno5;
    }

    /**
     * Returns the row 5 card number value (BMS CRDNUM5I).
     *
     * @return the row 5 card number, or {@code null} if unset
     */
    public String getCrdnum5() {
        return crdnum5;
    }

    /**
     * Sets the row 5 card number value (BMS CRDNUM5I).
     *
     * @param crdnum5 the row 5 card number
     */
    public void setCrdnum5(String crdnum5) {
        this.crdnum5 = crdnum5;
    }

    /**
     * Returns the row 5 card status value (BMS CRDSTS5I).
     *
     * @return the row 5 card status, or {@code null} if unset
     */
    public String getCrdsts5() {
        return crdsts5;
    }

    /**
     * Sets the row 5 card status value (BMS CRDSTS5I).
     *
     * @param crdsts5 the row 5 card status
     */
    public void setCrdsts5(String crdsts5) {
        this.crdsts5 = crdsts5;
    }

    /**
     * Returns the row 6 selection flag value (BMS CRDSEL6I).
     *
     * @return the row 6 selection flag, or {@code null} if unset
     */
    public String getCrdsel6() {
        return crdsel6;
    }

    /**
     * Sets the row 6 selection flag value (BMS CRDSEL6I).
     *
     * @param crdsel6 the row 6 selection flag
     */
    public void setCrdsel6(String crdsel6) {
        this.crdsel6 = crdsel6;
    }

    /**
     * Returns the row 6 status-protect indicator value (BMS CRDSTP6I).
     *
     * @return the row 6 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp6() {
        return crdstp6;
    }

    /**
     * Sets the row 6 status-protect indicator value (BMS CRDSTP6I).
     *
     * @param crdstp6 the row 6 status-protect indicator
     */
    public void setCrdstp6(String crdstp6) {
        this.crdstp6 = crdstp6;
    }

    /**
     * Returns the row 6 account number value (BMS ACCTNO6I).
     *
     * @return the row 6 account number, or {@code null} if unset
     */
    public String getAcctno6() {
        return acctno6;
    }

    /**
     * Sets the row 6 account number value (BMS ACCTNO6I).
     *
     * @param acctno6 the row 6 account number
     */
    public void setAcctno6(String acctno6) {
        this.acctno6 = acctno6;
    }

    /**
     * Returns the row 6 card number value (BMS CRDNUM6I).
     *
     * @return the row 6 card number, or {@code null} if unset
     */
    public String getCrdnum6() {
        return crdnum6;
    }

    /**
     * Sets the row 6 card number value (BMS CRDNUM6I).
     *
     * @param crdnum6 the row 6 card number
     */
    public void setCrdnum6(String crdnum6) {
        this.crdnum6 = crdnum6;
    }

    /**
     * Returns the row 6 card status value (BMS CRDSTS6I).
     *
     * @return the row 6 card status, or {@code null} if unset
     */
    public String getCrdsts6() {
        return crdsts6;
    }

    /**
     * Sets the row 6 card status value (BMS CRDSTS6I).
     *
     * @param crdsts6 the row 6 card status
     */
    public void setCrdsts6(String crdsts6) {
        this.crdsts6 = crdsts6;
    }

    /**
     * Returns the row 7 selection flag value (BMS CRDSEL7I).
     *
     * @return the row 7 selection flag, or {@code null} if unset
     */
    public String getCrdsel7() {
        return crdsel7;
    }

    /**
     * Sets the row 7 selection flag value (BMS CRDSEL7I).
     *
     * @param crdsel7 the row 7 selection flag
     */
    public void setCrdsel7(String crdsel7) {
        this.crdsel7 = crdsel7;
    }

    /**
     * Returns the row 7 status-protect indicator value (BMS CRDSTP7I).
     *
     * @return the row 7 status-protect indicator, or {@code null} if unset
     */
    public String getCrdstp7() {
        return crdstp7;
    }

    /**
     * Sets the row 7 status-protect indicator value (BMS CRDSTP7I).
     *
     * @param crdstp7 the row 7 status-protect indicator
     */
    public void setCrdstp7(String crdstp7) {
        this.crdstp7 = crdstp7;
    }

    /**
     * Returns the row 7 account number value (BMS ACCTNO7I).
     *
     * @return the row 7 account number, or {@code null} if unset
     */
    public String getAcctno7() {
        return acctno7;
    }

    /**
     * Sets the row 7 account number value (BMS ACCTNO7I).
     *
     * @param acctno7 the row 7 account number
     */
    public void setAcctno7(String acctno7) {
        this.acctno7 = acctno7;
    }

    /**
     * Returns the row 7 card number value (BMS CRDNUM7I).
     *
     * @return the row 7 card number, or {@code null} if unset
     */
    public String getCrdnum7() {
        return crdnum7;
    }

    /**
     * Sets the row 7 card number value (BMS CRDNUM7I).
     *
     * @param crdnum7 the row 7 card number
     */
    public void setCrdnum7(String crdnum7) {
        this.crdnum7 = crdnum7;
    }

    /**
     * Returns the row 7 card status value (BMS CRDSTS7I).
     *
     * @return the row 7 card status, or {@code null} if unset
     */
    public String getCrdsts7() {
        return crdsts7;
    }

    /**
     * Sets the row 7 card status value (BMS CRDSTS7I).
     *
     * @param crdsts7 the row 7 card status
     */
    public void setCrdsts7(String crdsts7) {
        this.crdsts7 = crdsts7;
    }

    /**
     * Returns the informational message line value (BMS INFOMSGI).
     *
     * @return the informational message line, or {@code null} if unset
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Sets the informational message line value (BMS INFOMSGI).
     *
     * @param infomsg the informational message line
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = infomsg;
    }

    /**
     * Returns the error message line value (BMS ERRMSGI).
     *
     * @return the error message line, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line value (BMS ERRMSGI).
     *
     * @param errmsg the error message line
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns a diagnostic representation listing all screen value fields. No sensitive
     * credential fields are present on this screen, so every value field is included.
     *
     * @return a string representation of this form
     */
    @Override
    public String toString() {
        return "COCRDLIForm{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", pageno='" + pageno + '\''
                + ", acctsid='" + acctsid + '\''
                + ", cardsid='" + cardsid + '\''
                + ", crdsel1='" + crdsel1 + '\''
                + ", acctno1='" + acctno1 + '\''
                + ", crdnum1='" + crdnum1 + '\''
                + ", crdsts1='" + crdsts1 + '\''
                + ", crdsel2='" + crdsel2 + '\''
                + ", crdstp2='" + crdstp2 + '\''
                + ", acctno2='" + acctno2 + '\''
                + ", crdnum2='" + crdnum2 + '\''
                + ", crdsts2='" + crdsts2 + '\''
                + ", crdsel3='" + crdsel3 + '\''
                + ", crdstp3='" + crdstp3 + '\''
                + ", acctno3='" + acctno3 + '\''
                + ", crdnum3='" + crdnum3 + '\''
                + ", crdsts3='" + crdsts3 + '\''
                + ", crdsel4='" + crdsel4 + '\''
                + ", crdstp4='" + crdstp4 + '\''
                + ", acctno4='" + acctno4 + '\''
                + ", crdnum4='" + crdnum4 + '\''
                + ", crdsts4='" + crdsts4 + '\''
                + ", crdsel5='" + crdsel5 + '\''
                + ", crdstp5='" + crdstp5 + '\''
                + ", acctno5='" + acctno5 + '\''
                + ", crdnum5='" + crdnum5 + '\''
                + ", crdsts5='" + crdsts5 + '\''
                + ", crdsel6='" + crdsel6 + '\''
                + ", crdstp6='" + crdstp6 + '\''
                + ", acctno6='" + acctno6 + '\''
                + ", crdnum6='" + crdnum6 + '\''
                + ", crdsts6='" + crdsts6 + '\''
                + ", crdsel7='" + crdsel7 + '\''
                + ", crdstp7='" + crdstp7 + '\''
                + ", acctno7='" + acctno7 + '\''
                + ", crdnum7='" + crdnum7 + '\''
                + ", crdsts7='" + crdsts7 + '\''
                + ", infomsg='" + infomsg + '\''
                + ", errmsg='" + errmsg + '\''
                + '}';
    }
}
