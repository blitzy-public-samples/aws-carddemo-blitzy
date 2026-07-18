package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Account-view screen form.
 *
 * <p>Server-side form-backing bean for the account view screen (CICS transaction
 * {@code CAVW}, program {@code COACTVWC}). It exposes the <em>value</em> fields of the
 * BMS symbolic input map {@code CACTVWAI} as bindable JavaBean {@code String} properties,
 * preserving the original 24x80 BMS field-length contract via {@link Size} upper bounds so
 * the Thymeleaf template {@code src/main/resources/templates/COACTVW.html} can bind
 * field-for-field to the legacy 3270 layout. Each {@link Size} constraint reproduces the
 * corresponding BMS {@code PIC} field-length edit.</p>
 *
 * <p>Only the map's data (value) fields are modeled here; BMS attribute/plumbing bytes
 * (the {@code L}/{@code F}/{@code A}/{@code C}/{@code P}/{@code H}/{@code V} suffixed items)
 * and the display-edited output redefines are terminal-rendering concerns handled elsewhere
 * and are intentionally omitted.</p>
 *
 * <p>This is a field-rich screen carrying account financials plus full customer PII. The
 * five display-edited amount fields ({@code acrdlim}, {@code acshlim}, {@code acurbal},
 * {@code acrcycr}, {@code acrcydb}) are held as pre-formatted {@code String} values, never
 * as {@code BigDecimal}/{@code float}/{@code double}; the authoritative monetary values live
 * on the {@code Account} domain entity and are formatted to display strings by the
 * controller. The Social Security Number ({@code acstssn}) is masked in {@link #toString()}
 * so it is never emitted to logs or diagnostics.</p>
 *
 * <p>Origin: legacy/cpy-bms/COACTVW.CPY (BMS mapset COACTVW, map CACTVWA)</p>
 */
public class COACTVWForm {

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

    /** ACCTSIDI — account id; numeric-edited, modeled as String (BMS PIC 9(11)). */
    @Size(max = 11)
    private String acctsid;

    /** ACSTTUSI — account status code (BMS PIC X(1)). */
    @Size(max = 1)
    private String acsttus;

    /** ADTOPENI — account open date (BMS PIC X(10)). */
    @Size(max = 10)
    private String adtopen;

    /** ACRDLIMI — credit limit; display-edited money as String (BMS PIC X(15)). */
    @Size(max = 15)
    private String acrdlim;

    /** AEXPDTI — account expiration date (BMS PIC X(10)). */
    @Size(max = 10)
    private String aexpdt;

    /** ACSHLIMI — cash credit limit; display-edited money as String (BMS PIC X(15)). */
    @Size(max = 15)
    private String acshlim;

    /** AREISDTI — account reissue date (BMS PIC X(10)). */
    @Size(max = 10)
    private String areisdt;

    /** ACURBALI — current balance; display-edited money as String (BMS PIC X(15)). */
    @Size(max = 15)
    private String acurbal;

    /** ACRCYCRI — current cycle credit; display-edited money as String (BMS PIC X(15)). */
    @Size(max = 15)
    private String acrcycr;

    /** AADDGRPI — account group id (BMS PIC X(10)). */
    @Size(max = 10)
    private String aaddgrp;

    /** ACRCYDBI — current cycle debit; display-edited money as String (BMS PIC X(15)). */
    @Size(max = 15)
    private String acrcydb;

    /** ACSTNUMI — customer number (BMS PIC X(9)). */
    @Size(max = 9)
    private String acstnum;

    /** ACSTSSNI — Social Security Number (PII); masked in {@link #toString()} (BMS PIC X(12)). */
    @Size(max = 12)
    private String acstssn;

    /** ACSTDOBI — customer date of birth (PII) (BMS PIC X(10)). */
    @Size(max = 10)
    private String acstdob;

    /** ACSTFCOI — FICO credit score (BMS PIC X(3)). */
    @Size(max = 3)
    private String acstfco;

    /** ACSFNAMI — customer first name (BMS PIC X(25)). */
    @Size(max = 25)
    private String acsfnam;

    /** ACSMNAMI — customer middle name (BMS PIC X(25)). */
    @Size(max = 25)
    private String acsmnam;

    /** ACSLNAMI — customer last name (BMS PIC X(25)). */
    @Size(max = 25)
    private String acslnam;

    /** ACSADL1I — address line 1 (BMS PIC X(50)). */
    @Size(max = 50)
    private String acsadl1;

    /** ACSSTTEI — state code (BMS PIC X(2)). */
    @Size(max = 2)
    private String acsstte;

    /** ACSADL2I — address line 2 (BMS PIC X(50)). */
    @Size(max = 50)
    private String acsadl2;

    /** ACSZIPCI — zip code (BMS PIC X(5)). */
    @Size(max = 5)
    private String acszipc;

    /** ACSCITYI — city (BMS PIC X(50)). */
    @Size(max = 50)
    private String acscity;

    /** ACSCTRYI — country code (BMS PIC X(3)). */
    @Size(max = 3)
    private String acsctry;

    /** ACSPHN1I — phone number 1 (BMS PIC X(13)). */
    @Size(max = 13)
    private String acsphn1;

    /** ACSGOVTI — government-issued id (BMS PIC X(20)). */
    @Size(max = 20)
    private String acsgovt;

    /** ACSPHN2I — phone number 2 (BMS PIC X(13)). */
    @Size(max = 13)
    private String acsphn2;

    /** ACSEFTCI — EFT account id (BMS PIC X(10)). */
    @Size(max = 10)
    private String acseftc;

    /** ACSPFLGI — primary card holder flag (BMS PIC X(1)). */
    @Size(max = 1)
    private String acspflg;

    /** INFOMSGI — informational message line (BMS PIC X(45)). */
    @Size(max = 45)
    private String infomsg;

    /** ERRMSGI — error message line (BMS PIC X(78)). */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty account-view screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation; all
     * properties are populated by request binding.
     */
    public COACTVWForm() {
        // No-argument constructor: fields are populated by request binding.
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
     * Returns the account status code value (BMS ACSTTUSI).
     *
     * @return the account status code, or {@code null} if unset
     */
    public String getAcsttus() {
        return acsttus;
    }

    /**
     * Sets the account status code value (BMS ACSTTUSI).
     *
     * @param acsttus the account status code
     */
    public void setAcsttus(String acsttus) {
        this.acsttus = acsttus;
    }

    /**
     * Returns the account open date value (BMS ADTOPENI).
     *
     * @return the account open date, or {@code null} if unset
     */
    public String getAdtopen() {
        return adtopen;
    }

    /**
     * Sets the account open date value (BMS ADTOPENI).
     *
     * @param adtopen the account open date
     */
    public void setAdtopen(String adtopen) {
        this.adtopen = adtopen;
    }

    /**
     * Returns the credit limit display value (BMS ACRDLIMI). This is a pre-formatted
     * display string, not a numeric value.
     *
     * @return the credit limit display string, or {@code null} if unset
     */
    public String getAcrdlim() {
        return acrdlim;
    }

    /**
     * Sets the credit limit display value (BMS ACRDLIMI).
     *
     * @param acrdlim the credit limit display string
     */
    public void setAcrdlim(String acrdlim) {
        this.acrdlim = acrdlim;
    }

    /**
     * Returns the account expiration date value (BMS AEXPDTI).
     *
     * @return the account expiration date, or {@code null} if unset
     */
    public String getAexpdt() {
        return aexpdt;
    }

    /**
     * Sets the account expiration date value (BMS AEXPDTI).
     *
     * @param aexpdt the account expiration date
     */
    public void setAexpdt(String aexpdt) {
        this.aexpdt = aexpdt;
    }

    /**
     * Returns the cash credit limit display value (BMS ACSHLIMI). This is a pre-formatted
     * display string, not a numeric value.
     *
     * @return the cash credit limit display string, or {@code null} if unset
     */
    public String getAcshlim() {
        return acshlim;
    }

    /**
     * Sets the cash credit limit display value (BMS ACSHLIMI).
     *
     * @param acshlim the cash credit limit display string
     */
    public void setAcshlim(String acshlim) {
        this.acshlim = acshlim;
    }

    /**
     * Returns the account reissue date value (BMS AREISDTI).
     *
     * @return the account reissue date, or {@code null} if unset
     */
    public String getAreisdt() {
        return areisdt;
    }

    /**
     * Sets the account reissue date value (BMS AREISDTI).
     *
     * @param areisdt the account reissue date
     */
    public void setAreisdt(String areisdt) {
        this.areisdt = areisdt;
    }

    /**
     * Returns the current balance display value (BMS ACURBALI). This is a pre-formatted
     * display string, not a numeric value.
     *
     * @return the current balance display string, or {@code null} if unset
     */
    public String getAcurbal() {
        return acurbal;
    }

    /**
     * Sets the current balance display value (BMS ACURBALI).
     *
     * @param acurbal the current balance display string
     */
    public void setAcurbal(String acurbal) {
        this.acurbal = acurbal;
    }

    /**
     * Returns the current cycle credit display value (BMS ACRCYCRI). This is a pre-formatted
     * display string, not a numeric value.
     *
     * @return the current cycle credit display string, or {@code null} if unset
     */
    public String getAcrcycr() {
        return acrcycr;
    }

    /**
     * Sets the current cycle credit display value (BMS ACRCYCRI).
     *
     * @param acrcycr the current cycle credit display string
     */
    public void setAcrcycr(String acrcycr) {
        this.acrcycr = acrcycr;
    }

    /**
     * Returns the account group id value (BMS AADDGRPI).
     *
     * @return the account group id, or {@code null} if unset
     */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /**
     * Sets the account group id value (BMS AADDGRPI).
     *
     * @param aaddgrp the account group id
     */
    public void setAaddgrp(String aaddgrp) {
        this.aaddgrp = aaddgrp;
    }

    /**
     * Returns the current cycle debit display value (BMS ACRCYDBI). This is a pre-formatted
     * display string, not a numeric value.
     *
     * @return the current cycle debit display string, or {@code null} if unset
     */
    public String getAcrcydb() {
        return acrcydb;
    }

    /**
     * Sets the current cycle debit display value (BMS ACRCYDBI).
     *
     * @param acrcydb the current cycle debit display string
     */
    public void setAcrcydb(String acrcydb) {
        this.acrcydb = acrcydb;
    }

    /**
     * Returns the customer number value (BMS ACSTNUMI).
     *
     * @return the customer number, or {@code null} if unset
     */
    public String getAcstnum() {
        return acstnum;
    }

    /**
     * Sets the customer number value (BMS ACSTNUMI).
     *
     * @param acstnum the customer number
     */
    public void setAcstnum(String acstnum) {
        this.acstnum = acstnum;
    }

    /**
     * Returns the Social Security Number value (BMS ACSTSSNI). This value is PII; callers
     * must never log it in cleartext. {@link #toString()} masks it.
     *
     * @return the Social Security Number, or {@code null} if unset
     */
    public String getAcstssn() {
        return acstssn;
    }

    /**
     * Sets the Social Security Number value (BMS ACSTSSNI).
     *
     * @param acstssn the Social Security Number
     */
    public void setAcstssn(String acstssn) {
        this.acstssn = acstssn;
    }

    /**
     * Returns the customer date of birth value (BMS ACSTDOBI). This value is PII.
     *
     * @return the customer date of birth, or {@code null} if unset
     */
    public String getAcstdob() {
        return acstdob;
    }

    /**
     * Sets the customer date of birth value (BMS ACSTDOBI).
     *
     * @param acstdob the customer date of birth
     */
    public void setAcstdob(String acstdob) {
        this.acstdob = acstdob;
    }

    /**
     * Returns the FICO credit score value (BMS ACSTFCOI).
     *
     * @return the FICO credit score, or {@code null} if unset
     */
    public String getAcstfco() {
        return acstfco;
    }

    /**
     * Sets the FICO credit score value (BMS ACSTFCOI).
     *
     * @param acstfco the FICO credit score
     */
    public void setAcstfco(String acstfco) {
        this.acstfco = acstfco;
    }

    /**
     * Returns the customer first name value (BMS ACSFNAMI).
     *
     * @return the customer first name, or {@code null} if unset
     */
    public String getAcsfnam() {
        return acsfnam;
    }

    /**
     * Sets the customer first name value (BMS ACSFNAMI).
     *
     * @param acsfnam the customer first name
     */
    public void setAcsfnam(String acsfnam) {
        this.acsfnam = acsfnam;
    }

    /**
     * Returns the customer middle name value (BMS ACSMNAMI).
     *
     * @return the customer middle name, or {@code null} if unset
     */
    public String getAcsmnam() {
        return acsmnam;
    }

    /**
     * Sets the customer middle name value (BMS ACSMNAMI).
     *
     * @param acsmnam the customer middle name
     */
    public void setAcsmnam(String acsmnam) {
        this.acsmnam = acsmnam;
    }

    /**
     * Returns the customer last name value (BMS ACSLNAMI).
     *
     * @return the customer last name, or {@code null} if unset
     */
    public String getAcslnam() {
        return acslnam;
    }

    /**
     * Sets the customer last name value (BMS ACSLNAMI).
     *
     * @param acslnam the customer last name
     */
    public void setAcslnam(String acslnam) {
        this.acslnam = acslnam;
    }

    /**
     * Returns the address line 1 value (BMS ACSADL1I).
     *
     * @return the address line 1, or {@code null} if unset
     */
    public String getAcsadl1() {
        return acsadl1;
    }

    /**
     * Sets the address line 1 value (BMS ACSADL1I).
     *
     * @param acsadl1 the address line 1
     */
    public void setAcsadl1(String acsadl1) {
        this.acsadl1 = acsadl1;
    }

    /**
     * Returns the state code value (BMS ACSSTTEI).
     *
     * @return the state code, or {@code null} if unset
     */
    public String getAcsstte() {
        return acsstte;
    }

    /**
     * Sets the state code value (BMS ACSSTTEI).
     *
     * @param acsstte the state code
     */
    public void setAcsstte(String acsstte) {
        this.acsstte = acsstte;
    }

    /**
     * Returns the address line 2 value (BMS ACSADL2I).
     *
     * @return the address line 2, or {@code null} if unset
     */
    public String getAcsadl2() {
        return acsadl2;
    }

    /**
     * Sets the address line 2 value (BMS ACSADL2I).
     *
     * @param acsadl2 the address line 2
     */
    public void setAcsadl2(String acsadl2) {
        this.acsadl2 = acsadl2;
    }

    /**
     * Returns the zip code value (BMS ACSZIPCI).
     *
     * @return the zip code, or {@code null} if unset
     */
    public String getAcszipc() {
        return acszipc;
    }

    /**
     * Sets the zip code value (BMS ACSZIPCI).
     *
     * @param acszipc the zip code
     */
    public void setAcszipc(String acszipc) {
        this.acszipc = acszipc;
    }

    /**
     * Returns the city value (BMS ACSCITYI).
     *
     * @return the city, or {@code null} if unset
     */
    public String getAcscity() {
        return acscity;
    }

    /**
     * Sets the city value (BMS ACSCITYI).
     *
     * @param acscity the city
     */
    public void setAcscity(String acscity) {
        this.acscity = acscity;
    }

    /**
     * Returns the country code value (BMS ACSCTRYI).
     *
     * @return the country code, or {@code null} if unset
     */
    public String getAcsctry() {
        return acsctry;
    }

    /**
     * Sets the country code value (BMS ACSCTRYI).
     *
     * @param acsctry the country code
     */
    public void setAcsctry(String acsctry) {
        this.acsctry = acsctry;
    }

    /**
     * Returns the phone number 1 value (BMS ACSPHN1I).
     *
     * @return the phone number 1, or {@code null} if unset
     */
    public String getAcsphn1() {
        return acsphn1;
    }

    /**
     * Sets the phone number 1 value (BMS ACSPHN1I).
     *
     * @param acsphn1 the phone number 1
     */
    public void setAcsphn1(String acsphn1) {
        this.acsphn1 = acsphn1;
    }

    /**
     * Returns the government-issued id value (BMS ACSGOVTI).
     *
     * @return the government-issued id, or {@code null} if unset
     */
    public String getAcsgovt() {
        return acsgovt;
    }

    /**
     * Sets the government-issued id value (BMS ACSGOVTI).
     *
     * @param acsgovt the government-issued id
     */
    public void setAcsgovt(String acsgovt) {
        this.acsgovt = acsgovt;
    }

    /**
     * Returns the phone number 2 value (BMS ACSPHN2I).
     *
     * @return the phone number 2, or {@code null} if unset
     */
    public String getAcsphn2() {
        return acsphn2;
    }

    /**
     * Sets the phone number 2 value (BMS ACSPHN2I).
     *
     * @param acsphn2 the phone number 2
     */
    public void setAcsphn2(String acsphn2) {
        this.acsphn2 = acsphn2;
    }

    /**
     * Returns the EFT account id value (BMS ACSEFTCI).
     *
     * @return the EFT account id, or {@code null} if unset
     */
    public String getAcseftc() {
        return acseftc;
    }

    /**
     * Sets the EFT account id value (BMS ACSEFTCI).
     *
     * @param acseftc the EFT account id
     */
    public void setAcseftc(String acseftc) {
        this.acseftc = acseftc;
    }

    /**
     * Returns the primary card holder flag value (BMS ACSPFLGI).
     *
     * @return the primary card holder flag, or {@code null} if unset
     */
    public String getAcspflg() {
        return acspflg;
    }

    /**
     * Sets the primary card holder flag value (BMS ACSPFLGI).
     *
     * @param acspflg the primary card holder flag
     */
    public void setAcspflg(String acspflg) {
        this.acspflg = acspflg;
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
     * Returns a diagnostic representation listing every screen value field. The
     * {@code acstssn} field carries a Social Security Number (PII) and is intentionally
     * rendered as {@code ***} so the cleartext SSN is never emitted to logs or diagnostics.
     * All other fields, including the date of birth, are rendered normally.
     *
     * @return a string representation of this form with the SSN masked
     */
    @Override
    public String toString() {
        return "COACTVWForm{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", acctsid='" + acctsid + '\''
                + ", acsttus='" + acsttus + '\''
                + ", adtopen='" + adtopen + '\''
                + ", acrdlim='" + acrdlim + '\''
                + ", aexpdt='" + aexpdt + '\''
                + ", acshlim='" + acshlim + '\''
                + ", areisdt='" + areisdt + '\''
                + ", acurbal='" + acurbal + '\''
                + ", acrcycr='" + acrcycr + '\''
                + ", aaddgrp='" + aaddgrp + '\''
                + ", acrcydb='" + acrcydb + '\''
                + ", acstnum='" + acstnum + '\''
                + ", acstssn='***'"
                + ", acstdob='" + acstdob + '\''
                + ", acstfco='" + acstfco + '\''
                + ", acsfnam='" + acsfnam + '\''
                + ", acsmnam='" + acsmnam + '\''
                + ", acslnam='" + acslnam + '\''
                + ", acsadl1='" + acsadl1 + '\''
                + ", acsstte='" + acsstte + '\''
                + ", acsadl2='" + acsadl2 + '\''
                + ", acszipc='" + acszipc + '\''
                + ", acscity='" + acscity + '\''
                + ", acsctry='" + acsctry + '\''
                + ", acsphn1='" + acsphn1 + '\''
                + ", acsgovt='" + acsgovt + '\''
                + ", acsphn2='" + acsphn2 + '\''
                + ", acseftc='" + acseftc + '\''
                + ", acspflg='" + acspflg + '\''
                + ", infomsg='" + infomsg + '\''
                + ", errmsg='" + errmsg + '\''
                + '}';
    }
}
