package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Account-update screen form.
 *
 * <p>Form-backing bean for the account-update screen. It holds the BMS map's value
 * fields as bindable {@code String} bean properties, preserving the original 24x80
 * field-length contract: every property carries a {@link Size} upper bound equal to
 * its BMS {@code PIC X(n)} length so that server-side binding and validation reproduce
 * the legacy screen behavior. Attribute and plumbing bytes from the symbolic map
 * (length, flag, attribute, and color/highlight/position/validation sub-fields) are
 * intentionally not modeled here; they are handled by the Thymeleaf template and the
 * {@code util} helpers.</p>
 *
 * <p>Unlike the read-only account-view screen, the update screen splits dates into
 * year, month, and day entry parts, the SSN into three parts, and each phone number
 * into area, prefix, and line parts, and it carries separate PF-key legend buffers.
 * The five display-edited amount fields (credit limit, cash limit, current balance,
 * cycle credit, and cycle debit) are carried as {@code String} to preserve the
 * fixed-width character contract; they are never modeled as floating-point or
 * {@code BigDecimal} on the screen form.</p>
 *
 * <p>Origin: legacy/cpy-bms/COACTUP.CPY (BMS mapset COACTUP, map CACTUPA)</p>
 */
public class COACTUPForm {

    /** Transaction name shown in the screen header. BMS symbolic {@code TRNNAMEI}, PIC X(4). */
    @Size(max = 4)
    private String trnname;

    /** First title line shown in the screen header. BMS symbolic {@code TITLE01I}, PIC X(40). */
    @Size(max = 40)
    private String title01;

    /** Current date shown in the screen header. BMS symbolic {@code CURDATEI}, PIC X(8). */
    @Size(max = 8)
    private String curdate;

    /** Program name shown in the screen header. BMS symbolic {@code PGMNAMEI}, PIC X(8). */
    @Size(max = 8)
    private String pgmname;

    /** Second title line shown in the screen header. BMS symbolic {@code TITLE02I}, PIC X(40). */
    @Size(max = 40)
    private String title02;

    /** Current time shown in the screen header. BMS symbolic {@code CURTIMEI}, PIC X(8). */
    @Size(max = 8)
    private String curtime;

    /** Account identifier entered on the screen. BMS symbolic {@code ACCTSIDI}, PIC X(11). */
    @Size(max = 11)
    private String acctsid;

    /** Account status code. BMS symbolic {@code ACSTTUSI}, PIC X(1). */
    @Size(max = 1)
    private String acsttus;

    /** Account open date, year part. BMS symbolic {@code OPNYEARI}, PIC X(4). */
    @Size(max = 4)
    private String opnyear;

    /** Account open date, month part. BMS symbolic {@code OPNMONI}, PIC X(2). */
    @Size(max = 2)
    private String opnmon;

    /** Account open date, day part. BMS symbolic {@code OPNDAYI}, PIC X(2). */
    @Size(max = 2)
    private String opnday;

    /** Credit limit; display-edited monetary amount carried as text. BMS symbolic {@code ACRDLIMI}, PIC X(15). */
    @Size(max = 15)
    private String acrdlim;

    /** Card expiry date, year part. BMS symbolic {@code EXPYEARI}, PIC X(4). */
    @Size(max = 4)
    private String expyear;

    /** Card expiry date, month part. BMS symbolic {@code EXPMONI}, PIC X(2). */
    @Size(max = 2)
    private String expmon;

    /** Card expiry date, day part. BMS symbolic {@code EXPDAYI}, PIC X(2). */
    @Size(max = 2)
    private String expday;

    /** Cash credit limit; display-edited monetary amount carried as text. BMS symbolic {@code ACSHLIMI}, PIC X(15). */
    @Size(max = 15)
    private String acshlim;

    /** Reissue date, year part. BMS symbolic {@code RISYEARI}, PIC X(4). */
    @Size(max = 4)
    private String risyear;

    /** Reissue date, month part. BMS symbolic {@code RISMONI}, PIC X(2). */
    @Size(max = 2)
    private String rismon;

    /** Reissue date, day part. BMS symbolic {@code RISDAYI}, PIC X(2). */
    @Size(max = 2)
    private String risday;

    /** Current account balance; display-edited monetary amount carried as text. BMS symbolic {@code ACURBALI}, PIC X(15). */
    @Size(max = 15)
    private String acurbal;

    /** Current cycle credit; display-edited monetary amount carried as text. BMS symbolic {@code ACRCYCRI}, PIC X(15). */
    @Size(max = 15)
    private String acrcycr;

    /** Account group identifier. BMS symbolic {@code AADDGRPI}, PIC X(10). */
    @Size(max = 10)
    private String aaddgrp;

    /** Current cycle debit; display-edited monetary amount carried as text. BMS symbolic {@code ACRCYDBI}, PIC X(15). */
    @Size(max = 15)
    private String acrcydb;

    /** Customer number. BMS symbolic {@code ACSTNUMI}, PIC X(9). */
    @Size(max = 9)
    private String acstnum;

    /** Customer SSN, part one (area). Never emitted by toString() (review finding F9). BMS symbolic {@code ACTSSN1I}, PIC X(3). */
    @Size(max = 3)
    private String actssn1;

    /** Customer SSN, part two (group). Never emitted by toString() (review finding F9). BMS symbolic {@code ACTSSN2I}, PIC X(2). */
    @Size(max = 2)
    private String actssn2;

    /** Customer SSN, part three (serial). Never emitted by toString() (review finding F9). BMS symbolic {@code ACTSSN3I}, PIC X(4). */
    @Size(max = 4)
    private String actssn3;

    /** Date of birth, year part. BMS symbolic {@code DOBYEARI}, PIC X(4). */
    @Size(max = 4)
    private String dobyear;

    /** Date of birth, month part. BMS symbolic {@code DOBMONI}, PIC X(2). */
    @Size(max = 2)
    private String dobmon;

    /** Date of birth, day part. BMS symbolic {@code DOBDAYI}, PIC X(2). */
    @Size(max = 2)
    private String dobday;

    /** Customer FICO credit score. BMS symbolic {@code ACSTFCOI}, PIC X(3). */
    @Size(max = 3)
    private String acstfco;

    /** Customer first name. BMS symbolic {@code ACSFNAMI}, PIC X(25). */
    @Size(max = 25)
    private String acsfnam;

    /** Customer middle name. BMS symbolic {@code ACSMNAMI}, PIC X(25). */
    @Size(max = 25)
    private String acsmnam;

    /** Customer last name. BMS symbolic {@code ACSLNAMI}, PIC X(25). */
    @Size(max = 25)
    private String acslnam;

    /** Customer address line one. BMS symbolic {@code ACSADL1I}, PIC X(50). */
    @Size(max = 50)
    private String acsadl1;

    /** Customer state code. BMS symbolic {@code ACSSTTEI}, PIC X(2). */
    @Size(max = 2)
    private String acsstte;

    /** Customer address line two. BMS symbolic {@code ACSADL2I}, PIC X(50). */
    @Size(max = 50)
    private String acsadl2;

    /** Customer ZIP code. BMS symbolic {@code ACSZIPCI}, PIC X(5). */
    @Size(max = 5)
    private String acszipc;

    /** Customer city. BMS symbolic {@code ACSCITYI}, PIC X(50). */
    @Size(max = 50)
    private String acscity;

    /** Customer country code. BMS symbolic {@code ACSCTRYI}, PIC X(3). */
    @Size(max = 3)
    private String acsctry;

    /** Customer phone one, area-code part. BMS symbolic {@code ACSPH1AI}, PIC X(3). */
    @Size(max = 3)
    private String acsph1a;

    /** Customer phone one, prefix part. BMS symbolic {@code ACSPH1BI}, PIC X(3). */
    @Size(max = 3)
    private String acsph1b;

    /** Customer phone one, line-number part. BMS symbolic {@code ACSPH1CI}, PIC X(4). */
    @Size(max = 4)
    private String acsph1c;

    /** Customer government-issued identifier. BMS symbolic {@code ACSGOVTI}, PIC X(20). */
    @Size(max = 20)
    private String acsgovt;

    /** Customer phone two, area-code part. BMS symbolic {@code ACSPH2AI}, PIC X(3). */
    @Size(max = 3)
    private String acsph2a;

    /** Customer phone two, prefix part. BMS symbolic {@code ACSPH2BI}, PIC X(3). */
    @Size(max = 3)
    private String acsph2b;

    /** Customer phone two, line-number part. BMS symbolic {@code ACSPH2CI}, PIC X(4). */
    @Size(max = 4)
    private String acsph2c;

    /** Customer EFT account identifier. BMS symbolic {@code ACSEFTCI}, PIC X(10). */
    @Size(max = 10)
    private String acseftc;

    /** Primary card holder flag. BMS symbolic {@code ACSPFLGI}, PIC X(1). */
    @Size(max = 1)
    private String acspflg;

    /** Informational message line. BMS symbolic {@code INFOMSGI}, PIC X(45). */
    @Size(max = 45)
    private String infomsg;

    /** Error message line. BMS symbolic {@code ERRMSGI}, PIC X(78). */
    @Size(max = 78)
    private String errmsg;

    /** PF-key legend, part one. BMS symbolic {@code FKEYSI}, PIC X(21). */
    @Size(max = 21)
    private String fkeys;

    /** PF-key legend for function key 5 (F5). BMS symbolic {@code FKEY05I}, PIC X(7). */
    @Size(max = 7)
    private String fkey05;

    /** PF-key legend for function key 12 (F12). BMS symbolic {@code FKEY12I}, PIC X(10). */
    @Size(max = 10)
    private String fkey12;

    /**
     * Creates an empty {@code COACTUPForm} with all fields unset.
     */
    public COACTUPForm() {
    }

    /**
     * Returns the transaction name (BMS {@code TRNNAMEI}).
     *
     * @return the transaction name value
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction name (BMS {@code TRNNAMEI}).
     *
     * @param trnname the transaction name value to set
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the first header title line (BMS {@code TITLE01I}).
     *
     * @return the first header title line value
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first header title line (BMS {@code TITLE01I}).
     *
     * @param title01 the first header title line value to set
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current date (BMS {@code CURDATEI}).
     *
     * @return the current date value
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current date (BMS {@code CURDATEI}).
     *
     * @param curdate the current date value to set
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program name (BMS {@code PGMNAMEI}).
     *
     * @return the program name value
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program name (BMS {@code PGMNAMEI}).
     *
     * @param pgmname the program name value to set
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the second header title line (BMS {@code TITLE02I}).
     *
     * @return the second header title line value
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second header title line (BMS {@code TITLE02I}).
     *
     * @param title02 the second header title line value to set
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current time (BMS {@code CURTIMEI}).
     *
     * @return the current time value
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time (BMS {@code CURTIMEI}).
     *
     * @param curtime the current time value to set
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the account identifier (BMS {@code ACCTSIDI}).
     *
     * @return the account identifier value
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * Sets the account identifier (BMS {@code ACCTSIDI}).
     *
     * @param acctsid the account identifier value to set
     */
    public void setAcctsid(String acctsid) {
        this.acctsid = acctsid;
    }

    /**
     * Returns the account status code (BMS {@code ACSTTUSI}).
     *
     * @return the account status code value
     */
    public String getAcsttus() {
        return acsttus;
    }

    /**
     * Sets the account status code (BMS {@code ACSTTUSI}).
     *
     * @param acsttus the account status code value to set
     */
    public void setAcsttus(String acsttus) {
        this.acsttus = acsttus;
    }

    /**
     * Returns the account open-date year part (BMS {@code OPNYEARI}).
     *
     * @return the account open-date year part value
     */
    public String getOpnyear() {
        return opnyear;
    }

    /**
     * Sets the account open-date year part (BMS {@code OPNYEARI}).
     *
     * @param opnyear the account open-date year part value to set
     */
    public void setOpnyear(String opnyear) {
        this.opnyear = opnyear;
    }

    /**
     * Returns the account open-date month part (BMS {@code OPNMONI}).
     *
     * @return the account open-date month part value
     */
    public String getOpnmon() {
        return opnmon;
    }

    /**
     * Sets the account open-date month part (BMS {@code OPNMONI}).
     *
     * @param opnmon the account open-date month part value to set
     */
    public void setOpnmon(String opnmon) {
        this.opnmon = opnmon;
    }

    /**
     * Returns the account open-date day part (BMS {@code OPNDAYI}).
     *
     * @return the account open-date day part value
     */
    public String getOpnday() {
        return opnday;
    }

    /**
     * Sets the account open-date day part (BMS {@code OPNDAYI}).
     *
     * @param opnday the account open-date day part value to set
     */
    public void setOpnday(String opnday) {
        this.opnday = opnday;
    }

    /**
     * Returns the credit limit (BMS {@code ACRDLIMI}).
     *
     * @return the credit limit value
     */
    public String getAcrdlim() {
        return acrdlim;
    }

    /**
     * Sets the credit limit (BMS {@code ACRDLIMI}).
     *
     * @param acrdlim the credit limit value to set
     */
    public void setAcrdlim(String acrdlim) {
        this.acrdlim = acrdlim;
    }

    /**
     * Returns the expiry-date year part (BMS {@code EXPYEARI}).
     *
     * @return the expiry-date year part value
     */
    public String getExpyear() {
        return expyear;
    }

    /**
     * Sets the expiry-date year part (BMS {@code EXPYEARI}).
     *
     * @param expyear the expiry-date year part value to set
     */
    public void setExpyear(String expyear) {
        this.expyear = expyear;
    }

    /**
     * Returns the expiry-date month part (BMS {@code EXPMONI}).
     *
     * @return the expiry-date month part value
     */
    public String getExpmon() {
        return expmon;
    }

    /**
     * Sets the expiry-date month part (BMS {@code EXPMONI}).
     *
     * @param expmon the expiry-date month part value to set
     */
    public void setExpmon(String expmon) {
        this.expmon = expmon;
    }

    /**
     * Returns the expiry-date day part (BMS {@code EXPDAYI}).
     *
     * @return the expiry-date day part value
     */
    public String getExpday() {
        return expday;
    }

    /**
     * Sets the expiry-date day part (BMS {@code EXPDAYI}).
     *
     * @param expday the expiry-date day part value to set
     */
    public void setExpday(String expday) {
        this.expday = expday;
    }

    /**
     * Returns the cash credit limit (BMS {@code ACSHLIMI}).
     *
     * @return the cash credit limit value
     */
    public String getAcshlim() {
        return acshlim;
    }

    /**
     * Sets the cash credit limit (BMS {@code ACSHLIMI}).
     *
     * @param acshlim the cash credit limit value to set
     */
    public void setAcshlim(String acshlim) {
        this.acshlim = acshlim;
    }

    /**
     * Returns the reissue-date year part (BMS {@code RISYEARI}).
     *
     * @return the reissue-date year part value
     */
    public String getRisyear() {
        return risyear;
    }

    /**
     * Sets the reissue-date year part (BMS {@code RISYEARI}).
     *
     * @param risyear the reissue-date year part value to set
     */
    public void setRisyear(String risyear) {
        this.risyear = risyear;
    }

    /**
     * Returns the reissue-date month part (BMS {@code RISMONI}).
     *
     * @return the reissue-date month part value
     */
    public String getRismon() {
        return rismon;
    }

    /**
     * Sets the reissue-date month part (BMS {@code RISMONI}).
     *
     * @param rismon the reissue-date month part value to set
     */
    public void setRismon(String rismon) {
        this.rismon = rismon;
    }

    /**
     * Returns the reissue-date day part (BMS {@code RISDAYI}).
     *
     * @return the reissue-date day part value
     */
    public String getRisday() {
        return risday;
    }

    /**
     * Sets the reissue-date day part (BMS {@code RISDAYI}).
     *
     * @param risday the reissue-date day part value to set
     */
    public void setRisday(String risday) {
        this.risday = risday;
    }

    /**
     * Returns the current balance (BMS {@code ACURBALI}).
     *
     * @return the current balance value
     */
    public String getAcurbal() {
        return acurbal;
    }

    /**
     * Sets the current balance (BMS {@code ACURBALI}).
     *
     * @param acurbal the current balance value to set
     */
    public void setAcurbal(String acurbal) {
        this.acurbal = acurbal;
    }

    /**
     * Returns the current cycle credit (BMS {@code ACRCYCRI}).
     *
     * @return the current cycle credit value
     */
    public String getAcrcycr() {
        return acrcycr;
    }

    /**
     * Sets the current cycle credit (BMS {@code ACRCYCRI}).
     *
     * @param acrcycr the current cycle credit value to set
     */
    public void setAcrcycr(String acrcycr) {
        this.acrcycr = acrcycr;
    }

    /**
     * Returns the account group identifier (BMS {@code AADDGRPI}).
     *
     * @return the account group identifier value
     */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /**
     * Sets the account group identifier (BMS {@code AADDGRPI}).
     *
     * @param aaddgrp the account group identifier value to set
     */
    public void setAaddgrp(String aaddgrp) {
        this.aaddgrp = aaddgrp;
    }

    /**
     * Returns the current cycle debit (BMS {@code ACRCYDBI}).
     *
     * @return the current cycle debit value
     */
    public String getAcrcydb() {
        return acrcydb;
    }

    /**
     * Sets the current cycle debit (BMS {@code ACRCYDBI}).
     *
     * @param acrcydb the current cycle debit value to set
     */
    public void setAcrcydb(String acrcydb) {
        this.acrcydb = acrcydb;
    }

    /**
     * Returns the customer number (BMS {@code ACSTNUMI}).
     *
     * @return the customer number value
     */
    public String getAcstnum() {
        return acstnum;
    }

    /**
     * Sets the customer number (BMS {@code ACSTNUMI}).
     *
     * @param acstnum the customer number value to set
     */
    public void setAcstnum(String acstnum) {
        this.acstnum = acstnum;
    }

    /**
     * Returns the customer SSN part one (BMS {@code ACTSSN1I}).
     *
     * @return the customer SSN part one value
     */
    public String getActssn1() {
        return actssn1;
    }

    /**
     * Sets the customer SSN part one (BMS {@code ACTSSN1I}).
     *
     * @param actssn1 the customer SSN part one value to set
     */
    public void setActssn1(String actssn1) {
        this.actssn1 = actssn1;
    }

    /**
     * Returns the customer SSN part two (BMS {@code ACTSSN2I}).
     *
     * @return the customer SSN part two value
     */
    public String getActssn2() {
        return actssn2;
    }

    /**
     * Sets the customer SSN part two (BMS {@code ACTSSN2I}).
     *
     * @param actssn2 the customer SSN part two value to set
     */
    public void setActssn2(String actssn2) {
        this.actssn2 = actssn2;
    }

    /**
     * Returns the customer SSN part three (BMS {@code ACTSSN3I}).
     *
     * @return the customer SSN part three value
     */
    public String getActssn3() {
        return actssn3;
    }

    /**
     * Sets the customer SSN part three (BMS {@code ACTSSN3I}).
     *
     * @param actssn3 the customer SSN part three value to set
     */
    public void setActssn3(String actssn3) {
        this.actssn3 = actssn3;
    }

    /**
     * Returns the date-of-birth year part (BMS {@code DOBYEARI}).
     *
     * @return the date-of-birth year part value
     */
    public String getDobyear() {
        return dobyear;
    }

    /**
     * Sets the date-of-birth year part (BMS {@code DOBYEARI}).
     *
     * @param dobyear the date-of-birth year part value to set
     */
    public void setDobyear(String dobyear) {
        this.dobyear = dobyear;
    }

    /**
     * Returns the date-of-birth month part (BMS {@code DOBMONI}).
     *
     * @return the date-of-birth month part value
     */
    public String getDobmon() {
        return dobmon;
    }

    /**
     * Sets the date-of-birth month part (BMS {@code DOBMONI}).
     *
     * @param dobmon the date-of-birth month part value to set
     */
    public void setDobmon(String dobmon) {
        this.dobmon = dobmon;
    }

    /**
     * Returns the date-of-birth day part (BMS {@code DOBDAYI}).
     *
     * @return the date-of-birth day part value
     */
    public String getDobday() {
        return dobday;
    }

    /**
     * Sets the date-of-birth day part (BMS {@code DOBDAYI}).
     *
     * @param dobday the date-of-birth day part value to set
     */
    public void setDobday(String dobday) {
        this.dobday = dobday;
    }

    /**
     * Returns the customer FICO score (BMS {@code ACSTFCOI}).
     *
     * @return the customer FICO score value
     */
    public String getAcstfco() {
        return acstfco;
    }

    /**
     * Sets the customer FICO score (BMS {@code ACSTFCOI}).
     *
     * @param acstfco the customer FICO score value to set
     */
    public void setAcstfco(String acstfco) {
        this.acstfco = acstfco;
    }

    /**
     * Returns the customer first name (BMS {@code ACSFNAMI}).
     *
     * @return the customer first name value
     */
    public String getAcsfnam() {
        return acsfnam;
    }

    /**
     * Sets the customer first name (BMS {@code ACSFNAMI}).
     *
     * @param acsfnam the customer first name value to set
     */
    public void setAcsfnam(String acsfnam) {
        this.acsfnam = acsfnam;
    }

    /**
     * Returns the customer middle name (BMS {@code ACSMNAMI}).
     *
     * @return the customer middle name value
     */
    public String getAcsmnam() {
        return acsmnam;
    }

    /**
     * Sets the customer middle name (BMS {@code ACSMNAMI}).
     *
     * @param acsmnam the customer middle name value to set
     */
    public void setAcsmnam(String acsmnam) {
        this.acsmnam = acsmnam;
    }

    /**
     * Returns the customer last name (BMS {@code ACSLNAMI}).
     *
     * @return the customer last name value
     */
    public String getAcslnam() {
        return acslnam;
    }

    /**
     * Sets the customer last name (BMS {@code ACSLNAMI}).
     *
     * @param acslnam the customer last name value to set
     */
    public void setAcslnam(String acslnam) {
        this.acslnam = acslnam;
    }

    /**
     * Returns the customer address line one (BMS {@code ACSADL1I}).
     *
     * @return the customer address line one value
     */
    public String getAcsadl1() {
        return acsadl1;
    }

    /**
     * Sets the customer address line one (BMS {@code ACSADL1I}).
     *
     * @param acsadl1 the customer address line one value to set
     */
    public void setAcsadl1(String acsadl1) {
        this.acsadl1 = acsadl1;
    }

    /**
     * Returns the customer state code (BMS {@code ACSSTTEI}).
     *
     * @return the customer state code value
     */
    public String getAcsstte() {
        return acsstte;
    }

    /**
     * Sets the customer state code (BMS {@code ACSSTTEI}).
     *
     * @param acsstte the customer state code value to set
     */
    public void setAcsstte(String acsstte) {
        this.acsstte = acsstte;
    }

    /**
     * Returns the customer address line two (BMS {@code ACSADL2I}).
     *
     * @return the customer address line two value
     */
    public String getAcsadl2() {
        return acsadl2;
    }

    /**
     * Sets the customer address line two (BMS {@code ACSADL2I}).
     *
     * @param acsadl2 the customer address line two value to set
     */
    public void setAcsadl2(String acsadl2) {
        this.acsadl2 = acsadl2;
    }

    /**
     * Returns the customer ZIP code (BMS {@code ACSZIPCI}).
     *
     * @return the customer ZIP code value
     */
    public String getAcszipc() {
        return acszipc;
    }

    /**
     * Sets the customer ZIP code (BMS {@code ACSZIPCI}).
     *
     * @param acszipc the customer ZIP code value to set
     */
    public void setAcszipc(String acszipc) {
        this.acszipc = acszipc;
    }

    /**
     * Returns the customer city (BMS {@code ACSCITYI}).
     *
     * @return the customer city value
     */
    public String getAcscity() {
        return acscity;
    }

    /**
     * Sets the customer city (BMS {@code ACSCITYI}).
     *
     * @param acscity the customer city value to set
     */
    public void setAcscity(String acscity) {
        this.acscity = acscity;
    }

    /**
     * Returns the customer country code (BMS {@code ACSCTRYI}).
     *
     * @return the customer country code value
     */
    public String getAcsctry() {
        return acsctry;
    }

    /**
     * Sets the customer country code (BMS {@code ACSCTRYI}).
     *
     * @param acsctry the customer country code value to set
     */
    public void setAcsctry(String acsctry) {
        this.acsctry = acsctry;
    }

    /**
     * Returns the customer phone-one area code (BMS {@code ACSPH1AI}).
     *
     * @return the customer phone-one area code value
     */
    public String getAcsph1a() {
        return acsph1a;
    }

    /**
     * Sets the customer phone-one area code (BMS {@code ACSPH1AI}).
     *
     * @param acsph1a the customer phone-one area code value to set
     */
    public void setAcsph1a(String acsph1a) {
        this.acsph1a = acsph1a;
    }

    /**
     * Returns the customer phone-one prefix (BMS {@code ACSPH1BI}).
     *
     * @return the customer phone-one prefix value
     */
    public String getAcsph1b() {
        return acsph1b;
    }

    /**
     * Sets the customer phone-one prefix (BMS {@code ACSPH1BI}).
     *
     * @param acsph1b the customer phone-one prefix value to set
     */
    public void setAcsph1b(String acsph1b) {
        this.acsph1b = acsph1b;
    }

    /**
     * Returns the customer phone-one line number (BMS {@code ACSPH1CI}).
     *
     * @return the customer phone-one line number value
     */
    public String getAcsph1c() {
        return acsph1c;
    }

    /**
     * Sets the customer phone-one line number (BMS {@code ACSPH1CI}).
     *
     * @param acsph1c the customer phone-one line number value to set
     */
    public void setAcsph1c(String acsph1c) {
        this.acsph1c = acsph1c;
    }

    /**
     * Returns the customer government-issued identifier (BMS {@code ACSGOVTI}).
     *
     * @return the customer government-issued identifier value
     */
    public String getAcsgovt() {
        return acsgovt;
    }

    /**
     * Sets the customer government-issued identifier (BMS {@code ACSGOVTI}).
     *
     * @param acsgovt the customer government-issued identifier value to set
     */
    public void setAcsgovt(String acsgovt) {
        this.acsgovt = acsgovt;
    }

    /**
     * Returns the customer phone-two area code (BMS {@code ACSPH2AI}).
     *
     * @return the customer phone-two area code value
     */
    public String getAcsph2a() {
        return acsph2a;
    }

    /**
     * Sets the customer phone-two area code (BMS {@code ACSPH2AI}).
     *
     * @param acsph2a the customer phone-two area code value to set
     */
    public void setAcsph2a(String acsph2a) {
        this.acsph2a = acsph2a;
    }

    /**
     * Returns the customer phone-two prefix (BMS {@code ACSPH2BI}).
     *
     * @return the customer phone-two prefix value
     */
    public String getAcsph2b() {
        return acsph2b;
    }

    /**
     * Sets the customer phone-two prefix (BMS {@code ACSPH2BI}).
     *
     * @param acsph2b the customer phone-two prefix value to set
     */
    public void setAcsph2b(String acsph2b) {
        this.acsph2b = acsph2b;
    }

    /**
     * Returns the customer phone-two line number (BMS {@code ACSPH2CI}).
     *
     * @return the customer phone-two line number value
     */
    public String getAcsph2c() {
        return acsph2c;
    }

    /**
     * Sets the customer phone-two line number (BMS {@code ACSPH2CI}).
     *
     * @param acsph2c the customer phone-two line number value to set
     */
    public void setAcsph2c(String acsph2c) {
        this.acsph2c = acsph2c;
    }

    /**
     * Returns the customer EFT account identifier (BMS {@code ACSEFTCI}).
     *
     * @return the customer EFT account identifier value
     */
    public String getAcseftc() {
        return acseftc;
    }

    /**
     * Sets the customer EFT account identifier (BMS {@code ACSEFTCI}).
     *
     * @param acseftc the customer EFT account identifier value to set
     */
    public void setAcseftc(String acseftc) {
        this.acseftc = acseftc;
    }

    /**
     * Returns the primary card holder flag (BMS {@code ACSPFLGI}).
     *
     * @return the primary card holder flag value
     */
    public String getAcspflg() {
        return acspflg;
    }

    /**
     * Sets the primary card holder flag (BMS {@code ACSPFLGI}).
     *
     * @param acspflg the primary card holder flag value to set
     */
    public void setAcspflg(String acspflg) {
        this.acspflg = acspflg;
    }

    /**
     * Returns the informational message line (BMS {@code INFOMSGI}).
     *
     * @return the informational message line value
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Sets the informational message line (BMS {@code INFOMSGI}).
     *
     * @param infomsg the informational message line value to set
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = infomsg;
    }

    /**
     * Returns the error message line (BMS {@code ERRMSGI}).
     *
     * @return the error message line value
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line (BMS {@code ERRMSGI}).
     *
     * @param errmsg the error message line value to set
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns the PF-key legend part one (BMS {@code FKEYSI}).
     *
     * @return the PF-key legend part one value
     */
    public String getFkeys() {
        return fkeys;
    }

    /**
     * Sets the PF-key legend part one (BMS {@code FKEYSI}).
     *
     * @param fkeys the PF-key legend part one value to set
     */
    public void setFkeys(String fkeys) {
        this.fkeys = fkeys;
    }

    /**
     * Returns the PF-key legend for F5 (BMS {@code FKEY05I}).
     *
     * @return the PF-key legend for F5 value
     */
    public String getFkey05() {
        return fkey05;
    }

    /**
     * Sets the PF-key legend for F5 (BMS {@code FKEY05I}).
     *
     * @param fkey05 the PF-key legend for F5 value to set
     */
    public void setFkey05(String fkey05) {
        this.fkey05 = fkey05;
    }

    /**
     * Returns the PF-key legend for F12 (BMS {@code FKEY12I}).
     *
     * @return the PF-key legend for F12 value
     */
    public String getFkey12() {
        return fkey12;
    }

    /**
     * Sets the PF-key legend for F12 (BMS {@code FKEY12I}).
     *
     * @param fkey12 the PF-key legend for F12 value to set
     */
    public void setFkey12(String fkey12) {
        this.fkey12 = fkey12;
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
        return "COACTUPForm@" + Integer.toHexString(System.identityHashCode(this));
    }
}
