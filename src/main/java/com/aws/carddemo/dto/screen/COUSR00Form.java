package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * User-list screen form.
 *
 * <p>Origin: legacy/cpy-bms/COUSR00.CPY (BMS mapset COUSR00, map COUSR0A).</p>
 *
 * <p>Spring MVC form-backing bean for the administrative user-list screen
 * (CICS transaction {@code CU00}, program {@code COUSR00C}). Each property
 * corresponds to a BMS value field of the {@code COUSR0AI} symbolic input map
 * and preserves the original 24x80 field-length contract via {@link Size}
 * upper bounds, so the legacy BMS field-length edits are reproduced during
 * Spring MVC request binding. The screen lists up to ten users per page; the
 * ten repeated rows are modeled as flat indexed properties
 * ({@code sel0001}-{@code sel0010} selection flags with four-digit indexes,
 * and {@code usrid01}-{@code usrid10}, {@code fname01}-{@code fname10},
 * {@code lname01}-{@code lname10}, {@code utype01}-{@code utype10} with
 * two-digit indexes), preserving the exact BMS symbolic digit widths. This is
 * a plain POJO instantiated per request via {@code @ModelAttribute}; it holds
 * no business logic and no monetary fields.</p>
 */
public class COUSR00Form {

    /** TRNNAMEI PIC X(4) - header transaction id. */
    @Size(max = 4)
    private String trnname;

    /** TITLE01I PIC X(40) - header title line 1. */
    @Size(max = 40)
    private String title01;

    /** CURDATEI PIC X(8) - header current date (MM/DD/YY). */
    @Size(max = 8)
    private String curdate;

    /** PGMNAMEI PIC X(8) - header program name. */
    @Size(max = 8)
    private String pgmname;

    /** TITLE02I PIC X(40) - header title line 2. */
    @Size(max = 40)
    private String title02;

    /** CURTIMEI PIC X(8) - header current time (HH:MM:SS). */
    @Size(max = 8)
    private String curtime;

    /** PAGENUMI PIC X(8) - current page number indicator. */
    @Size(max = 8)
    private String pagenum;

    /** USRIDINI PIC X(8) - user id search/filter input. */
    @Size(max = 8)
    private String usridin;

    /** SEL0001I PIC X(1) - row 01 selection flag. */
    @Size(max = 1)
    private String sel0001;

    /** USRID01I PIC X(8) - row 01 user id. */
    @Size(max = 8)
    private String usrid01;

    /** FNAME01I PIC X(20) - row 01 first name. */
    @Size(max = 20)
    private String fname01;

    /** LNAME01I PIC X(20) - row 01 last name. */
    @Size(max = 20)
    private String lname01;

    /** UTYPE01I PIC X(1) - row 01 user type. */
    @Size(max = 1)
    private String utype01;

    /** SEL0002I PIC X(1) - row 02 selection flag. */
    @Size(max = 1)
    private String sel0002;

    /** USRID02I PIC X(8) - row 02 user id. */
    @Size(max = 8)
    private String usrid02;

    /** FNAME02I PIC X(20) - row 02 first name. */
    @Size(max = 20)
    private String fname02;

    /** LNAME02I PIC X(20) - row 02 last name. */
    @Size(max = 20)
    private String lname02;

    /** UTYPE02I PIC X(1) - row 02 user type. */
    @Size(max = 1)
    private String utype02;

    /** SEL0003I PIC X(1) - row 03 selection flag. */
    @Size(max = 1)
    private String sel0003;

    /** USRID03I PIC X(8) - row 03 user id. */
    @Size(max = 8)
    private String usrid03;

    /** FNAME03I PIC X(20) - row 03 first name. */
    @Size(max = 20)
    private String fname03;

    /** LNAME03I PIC X(20) - row 03 last name. */
    @Size(max = 20)
    private String lname03;

    /** UTYPE03I PIC X(1) - row 03 user type. */
    @Size(max = 1)
    private String utype03;

    /** SEL0004I PIC X(1) - row 04 selection flag. */
    @Size(max = 1)
    private String sel0004;

    /** USRID04I PIC X(8) - row 04 user id. */
    @Size(max = 8)
    private String usrid04;

    /** FNAME04I PIC X(20) - row 04 first name. */
    @Size(max = 20)
    private String fname04;

    /** LNAME04I PIC X(20) - row 04 last name. */
    @Size(max = 20)
    private String lname04;

    /** UTYPE04I PIC X(1) - row 04 user type. */
    @Size(max = 1)
    private String utype04;

    /** SEL0005I PIC X(1) - row 05 selection flag. */
    @Size(max = 1)
    private String sel0005;

    /** USRID05I PIC X(8) - row 05 user id. */
    @Size(max = 8)
    private String usrid05;

    /** FNAME05I PIC X(20) - row 05 first name. */
    @Size(max = 20)
    private String fname05;

    /** LNAME05I PIC X(20) - row 05 last name. */
    @Size(max = 20)
    private String lname05;

    /** UTYPE05I PIC X(1) - row 05 user type. */
    @Size(max = 1)
    private String utype05;

    /** SEL0006I PIC X(1) - row 06 selection flag. */
    @Size(max = 1)
    private String sel0006;

    /** USRID06I PIC X(8) - row 06 user id. */
    @Size(max = 8)
    private String usrid06;

    /** FNAME06I PIC X(20) - row 06 first name. */
    @Size(max = 20)
    private String fname06;

    /** LNAME06I PIC X(20) - row 06 last name. */
    @Size(max = 20)
    private String lname06;

    /** UTYPE06I PIC X(1) - row 06 user type. */
    @Size(max = 1)
    private String utype06;

    /** SEL0007I PIC X(1) - row 07 selection flag. */
    @Size(max = 1)
    private String sel0007;

    /** USRID07I PIC X(8) - row 07 user id. */
    @Size(max = 8)
    private String usrid07;

    /** FNAME07I PIC X(20) - row 07 first name. */
    @Size(max = 20)
    private String fname07;

    /** LNAME07I PIC X(20) - row 07 last name. */
    @Size(max = 20)
    private String lname07;

    /** UTYPE07I PIC X(1) - row 07 user type. */
    @Size(max = 1)
    private String utype07;

    /** SEL0008I PIC X(1) - row 08 selection flag. */
    @Size(max = 1)
    private String sel0008;

    /** USRID08I PIC X(8) - row 08 user id. */
    @Size(max = 8)
    private String usrid08;

    /** FNAME08I PIC X(20) - row 08 first name. */
    @Size(max = 20)
    private String fname08;

    /** LNAME08I PIC X(20) - row 08 last name. */
    @Size(max = 20)
    private String lname08;

    /** UTYPE08I PIC X(1) - row 08 user type. */
    @Size(max = 1)
    private String utype08;

    /** SEL0009I PIC X(1) - row 09 selection flag. */
    @Size(max = 1)
    private String sel0009;

    /** USRID09I PIC X(8) - row 09 user id. */
    @Size(max = 8)
    private String usrid09;

    /** FNAME09I PIC X(20) - row 09 first name. */
    @Size(max = 20)
    private String fname09;

    /** LNAME09I PIC X(20) - row 09 last name. */
    @Size(max = 20)
    private String lname09;

    /** UTYPE09I PIC X(1) - row 09 user type. */
    @Size(max = 1)
    private String utype09;

    /** SEL0010I PIC X(1) - row 10 selection flag. */
    @Size(max = 1)
    private String sel0010;

    /** USRID10I PIC X(8) - row 10 user id. */
    @Size(max = 8)
    private String usrid10;

    /** FNAME10I PIC X(20) - row 10 first name. */
    @Size(max = 20)
    private String fname10;

    /** LNAME10I PIC X(20) - row 10 last name. */
    @Size(max = 20)
    private String lname10;

    /** UTYPE10I PIC X(1) - row 10 user type. */
    @Size(max = 1)
    private String utype10;

    /** ERRMSGI PIC X(78) - error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty user-list screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation;
     * all properties are populated by request binding.
     */
    public COUSR00Form() {
        // No-argument constructor: fields are populated by request binding.
    }

    /**
     * Returns the transaction id header field.
     *
     * @return the {@code TRNNAME} value (max 4 characters)
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction id header field.
     *
     * @param trnname the {@code TRNNAME} value (max 4 characters)
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the first title header line.
     *
     * @return the {@code TITLE01} value (max 40 characters)
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first title header line.
     *
     * @param title01 the {@code TITLE01} value (max 40 characters)
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current date header field.
     *
     * @return the {@code CURDATE} value (max 8 characters)
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current date header field.
     *
     * @param curdate the {@code CURDATE} value (max 8 characters)
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program name header field.
     *
     * @return the {@code PGMNAME} value (max 8 characters)
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program name header field.
     *
     * @param pgmname the {@code PGMNAME} value (max 8 characters)
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the second title header line.
     *
     * @return the {@code TITLE02} value (max 40 characters)
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second title header line.
     *
     * @param title02 the {@code TITLE02} value (max 40 characters)
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current time header field.
     *
     * @return the {@code CURTIME} value (max 8 characters)
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time header field.
     *
     * @param curtime the {@code CURTIME} value (max 8 characters)
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the current page number indicator.
     *
     * @return the {@code PAGENUM} value (max 8 characters)
     */
    public String getPagenum() {
        return pagenum;
    }

    /**
     * Sets the current page number indicator.
     *
     * @param pagenum the {@code PAGENUM} value (max 8 characters)
     */
    public void setPagenum(String pagenum) {
        this.pagenum = pagenum;
    }

    /**
     * Returns the user id search/filter input.
     *
     * @return the {@code USRIDIN} value (max 8 characters)
     */
    public String getUsridin() {
        return usridin;
    }

    /**
     * Sets the user id search/filter input.
     *
     * @param usridin the {@code USRIDIN} value (max 8 characters)
     */
    public void setUsridin(String usridin) {
        this.usridin = usridin;
    }

    /**
     * Returns the row 01 selection flag.
     *
     * @return the {@code SEL0001} value (max 1 characters)
     */
    public String getSel0001() {
        return sel0001;
    }

    /**
     * Sets the row 01 selection flag.
     *
     * @param sel0001 the {@code SEL0001} value (max 1 characters)
     */
    public void setSel0001(String sel0001) {
        this.sel0001 = sel0001;
    }

    /**
     * Returns the row 01 user id.
     *
     * @return the {@code USRID01} value (max 8 characters)
     */
    public String getUsrid01() {
        return usrid01;
    }

    /**
     * Sets the row 01 user id.
     *
     * @param usrid01 the {@code USRID01} value (max 8 characters)
     */
    public void setUsrid01(String usrid01) {
        this.usrid01 = usrid01;
    }

    /**
     * Returns the row 01 first name.
     *
     * @return the {@code FNAME01} value (max 20 characters)
     */
    public String getFname01() {
        return fname01;
    }

    /**
     * Sets the row 01 first name.
     *
     * @param fname01 the {@code FNAME01} value (max 20 characters)
     */
    public void setFname01(String fname01) {
        this.fname01 = fname01;
    }

    /**
     * Returns the row 01 last name.
     *
     * @return the {@code LNAME01} value (max 20 characters)
     */
    public String getLname01() {
        return lname01;
    }

    /**
     * Sets the row 01 last name.
     *
     * @param lname01 the {@code LNAME01} value (max 20 characters)
     */
    public void setLname01(String lname01) {
        this.lname01 = lname01;
    }

    /**
     * Returns the row 01 user type.
     *
     * @return the {@code UTYPE01} value (max 1 characters)
     */
    public String getUtype01() {
        return utype01;
    }

    /**
     * Sets the row 01 user type.
     *
     * @param utype01 the {@code UTYPE01} value (max 1 characters)
     */
    public void setUtype01(String utype01) {
        this.utype01 = utype01;
    }

    /**
     * Returns the row 02 selection flag.
     *
     * @return the {@code SEL0002} value (max 1 characters)
     */
    public String getSel0002() {
        return sel0002;
    }

    /**
     * Sets the row 02 selection flag.
     *
     * @param sel0002 the {@code SEL0002} value (max 1 characters)
     */
    public void setSel0002(String sel0002) {
        this.sel0002 = sel0002;
    }

    /**
     * Returns the row 02 user id.
     *
     * @return the {@code USRID02} value (max 8 characters)
     */
    public String getUsrid02() {
        return usrid02;
    }

    /**
     * Sets the row 02 user id.
     *
     * @param usrid02 the {@code USRID02} value (max 8 characters)
     */
    public void setUsrid02(String usrid02) {
        this.usrid02 = usrid02;
    }

    /**
     * Returns the row 02 first name.
     *
     * @return the {@code FNAME02} value (max 20 characters)
     */
    public String getFname02() {
        return fname02;
    }

    /**
     * Sets the row 02 first name.
     *
     * @param fname02 the {@code FNAME02} value (max 20 characters)
     */
    public void setFname02(String fname02) {
        this.fname02 = fname02;
    }

    /**
     * Returns the row 02 last name.
     *
     * @return the {@code LNAME02} value (max 20 characters)
     */
    public String getLname02() {
        return lname02;
    }

    /**
     * Sets the row 02 last name.
     *
     * @param lname02 the {@code LNAME02} value (max 20 characters)
     */
    public void setLname02(String lname02) {
        this.lname02 = lname02;
    }

    /**
     * Returns the row 02 user type.
     *
     * @return the {@code UTYPE02} value (max 1 characters)
     */
    public String getUtype02() {
        return utype02;
    }

    /**
     * Sets the row 02 user type.
     *
     * @param utype02 the {@code UTYPE02} value (max 1 characters)
     */
    public void setUtype02(String utype02) {
        this.utype02 = utype02;
    }

    /**
     * Returns the row 03 selection flag.
     *
     * @return the {@code SEL0003} value (max 1 characters)
     */
    public String getSel0003() {
        return sel0003;
    }

    /**
     * Sets the row 03 selection flag.
     *
     * @param sel0003 the {@code SEL0003} value (max 1 characters)
     */
    public void setSel0003(String sel0003) {
        this.sel0003 = sel0003;
    }

    /**
     * Returns the row 03 user id.
     *
     * @return the {@code USRID03} value (max 8 characters)
     */
    public String getUsrid03() {
        return usrid03;
    }

    /**
     * Sets the row 03 user id.
     *
     * @param usrid03 the {@code USRID03} value (max 8 characters)
     */
    public void setUsrid03(String usrid03) {
        this.usrid03 = usrid03;
    }

    /**
     * Returns the row 03 first name.
     *
     * @return the {@code FNAME03} value (max 20 characters)
     */
    public String getFname03() {
        return fname03;
    }

    /**
     * Sets the row 03 first name.
     *
     * @param fname03 the {@code FNAME03} value (max 20 characters)
     */
    public void setFname03(String fname03) {
        this.fname03 = fname03;
    }

    /**
     * Returns the row 03 last name.
     *
     * @return the {@code LNAME03} value (max 20 characters)
     */
    public String getLname03() {
        return lname03;
    }

    /**
     * Sets the row 03 last name.
     *
     * @param lname03 the {@code LNAME03} value (max 20 characters)
     */
    public void setLname03(String lname03) {
        this.lname03 = lname03;
    }

    /**
     * Returns the row 03 user type.
     *
     * @return the {@code UTYPE03} value (max 1 characters)
     */
    public String getUtype03() {
        return utype03;
    }

    /**
     * Sets the row 03 user type.
     *
     * @param utype03 the {@code UTYPE03} value (max 1 characters)
     */
    public void setUtype03(String utype03) {
        this.utype03 = utype03;
    }

    /**
     * Returns the row 04 selection flag.
     *
     * @return the {@code SEL0004} value (max 1 characters)
     */
    public String getSel0004() {
        return sel0004;
    }

    /**
     * Sets the row 04 selection flag.
     *
     * @param sel0004 the {@code SEL0004} value (max 1 characters)
     */
    public void setSel0004(String sel0004) {
        this.sel0004 = sel0004;
    }

    /**
     * Returns the row 04 user id.
     *
     * @return the {@code USRID04} value (max 8 characters)
     */
    public String getUsrid04() {
        return usrid04;
    }

    /**
     * Sets the row 04 user id.
     *
     * @param usrid04 the {@code USRID04} value (max 8 characters)
     */
    public void setUsrid04(String usrid04) {
        this.usrid04 = usrid04;
    }

    /**
     * Returns the row 04 first name.
     *
     * @return the {@code FNAME04} value (max 20 characters)
     */
    public String getFname04() {
        return fname04;
    }

    /**
     * Sets the row 04 first name.
     *
     * @param fname04 the {@code FNAME04} value (max 20 characters)
     */
    public void setFname04(String fname04) {
        this.fname04 = fname04;
    }

    /**
     * Returns the row 04 last name.
     *
     * @return the {@code LNAME04} value (max 20 characters)
     */
    public String getLname04() {
        return lname04;
    }

    /**
     * Sets the row 04 last name.
     *
     * @param lname04 the {@code LNAME04} value (max 20 characters)
     */
    public void setLname04(String lname04) {
        this.lname04 = lname04;
    }

    /**
     * Returns the row 04 user type.
     *
     * @return the {@code UTYPE04} value (max 1 characters)
     */
    public String getUtype04() {
        return utype04;
    }

    /**
     * Sets the row 04 user type.
     *
     * @param utype04 the {@code UTYPE04} value (max 1 characters)
     */
    public void setUtype04(String utype04) {
        this.utype04 = utype04;
    }

    /**
     * Returns the row 05 selection flag.
     *
     * @return the {@code SEL0005} value (max 1 characters)
     */
    public String getSel0005() {
        return sel0005;
    }

    /**
     * Sets the row 05 selection flag.
     *
     * @param sel0005 the {@code SEL0005} value (max 1 characters)
     */
    public void setSel0005(String sel0005) {
        this.sel0005 = sel0005;
    }

    /**
     * Returns the row 05 user id.
     *
     * @return the {@code USRID05} value (max 8 characters)
     */
    public String getUsrid05() {
        return usrid05;
    }

    /**
     * Sets the row 05 user id.
     *
     * @param usrid05 the {@code USRID05} value (max 8 characters)
     */
    public void setUsrid05(String usrid05) {
        this.usrid05 = usrid05;
    }

    /**
     * Returns the row 05 first name.
     *
     * @return the {@code FNAME05} value (max 20 characters)
     */
    public String getFname05() {
        return fname05;
    }

    /**
     * Sets the row 05 first name.
     *
     * @param fname05 the {@code FNAME05} value (max 20 characters)
     */
    public void setFname05(String fname05) {
        this.fname05 = fname05;
    }

    /**
     * Returns the row 05 last name.
     *
     * @return the {@code LNAME05} value (max 20 characters)
     */
    public String getLname05() {
        return lname05;
    }

    /**
     * Sets the row 05 last name.
     *
     * @param lname05 the {@code LNAME05} value (max 20 characters)
     */
    public void setLname05(String lname05) {
        this.lname05 = lname05;
    }

    /**
     * Returns the row 05 user type.
     *
     * @return the {@code UTYPE05} value (max 1 characters)
     */
    public String getUtype05() {
        return utype05;
    }

    /**
     * Sets the row 05 user type.
     *
     * @param utype05 the {@code UTYPE05} value (max 1 characters)
     */
    public void setUtype05(String utype05) {
        this.utype05 = utype05;
    }

    /**
     * Returns the row 06 selection flag.
     *
     * @return the {@code SEL0006} value (max 1 characters)
     */
    public String getSel0006() {
        return sel0006;
    }

    /**
     * Sets the row 06 selection flag.
     *
     * @param sel0006 the {@code SEL0006} value (max 1 characters)
     */
    public void setSel0006(String sel0006) {
        this.sel0006 = sel0006;
    }

    /**
     * Returns the row 06 user id.
     *
     * @return the {@code USRID06} value (max 8 characters)
     */
    public String getUsrid06() {
        return usrid06;
    }

    /**
     * Sets the row 06 user id.
     *
     * @param usrid06 the {@code USRID06} value (max 8 characters)
     */
    public void setUsrid06(String usrid06) {
        this.usrid06 = usrid06;
    }

    /**
     * Returns the row 06 first name.
     *
     * @return the {@code FNAME06} value (max 20 characters)
     */
    public String getFname06() {
        return fname06;
    }

    /**
     * Sets the row 06 first name.
     *
     * @param fname06 the {@code FNAME06} value (max 20 characters)
     */
    public void setFname06(String fname06) {
        this.fname06 = fname06;
    }

    /**
     * Returns the row 06 last name.
     *
     * @return the {@code LNAME06} value (max 20 characters)
     */
    public String getLname06() {
        return lname06;
    }

    /**
     * Sets the row 06 last name.
     *
     * @param lname06 the {@code LNAME06} value (max 20 characters)
     */
    public void setLname06(String lname06) {
        this.lname06 = lname06;
    }

    /**
     * Returns the row 06 user type.
     *
     * @return the {@code UTYPE06} value (max 1 characters)
     */
    public String getUtype06() {
        return utype06;
    }

    /**
     * Sets the row 06 user type.
     *
     * @param utype06 the {@code UTYPE06} value (max 1 characters)
     */
    public void setUtype06(String utype06) {
        this.utype06 = utype06;
    }

    /**
     * Returns the row 07 selection flag.
     *
     * @return the {@code SEL0007} value (max 1 characters)
     */
    public String getSel0007() {
        return sel0007;
    }

    /**
     * Sets the row 07 selection flag.
     *
     * @param sel0007 the {@code SEL0007} value (max 1 characters)
     */
    public void setSel0007(String sel0007) {
        this.sel0007 = sel0007;
    }

    /**
     * Returns the row 07 user id.
     *
     * @return the {@code USRID07} value (max 8 characters)
     */
    public String getUsrid07() {
        return usrid07;
    }

    /**
     * Sets the row 07 user id.
     *
     * @param usrid07 the {@code USRID07} value (max 8 characters)
     */
    public void setUsrid07(String usrid07) {
        this.usrid07 = usrid07;
    }

    /**
     * Returns the row 07 first name.
     *
     * @return the {@code FNAME07} value (max 20 characters)
     */
    public String getFname07() {
        return fname07;
    }

    /**
     * Sets the row 07 first name.
     *
     * @param fname07 the {@code FNAME07} value (max 20 characters)
     */
    public void setFname07(String fname07) {
        this.fname07 = fname07;
    }

    /**
     * Returns the row 07 last name.
     *
     * @return the {@code LNAME07} value (max 20 characters)
     */
    public String getLname07() {
        return lname07;
    }

    /**
     * Sets the row 07 last name.
     *
     * @param lname07 the {@code LNAME07} value (max 20 characters)
     */
    public void setLname07(String lname07) {
        this.lname07 = lname07;
    }

    /**
     * Returns the row 07 user type.
     *
     * @return the {@code UTYPE07} value (max 1 characters)
     */
    public String getUtype07() {
        return utype07;
    }

    /**
     * Sets the row 07 user type.
     *
     * @param utype07 the {@code UTYPE07} value (max 1 characters)
     */
    public void setUtype07(String utype07) {
        this.utype07 = utype07;
    }

    /**
     * Returns the row 08 selection flag.
     *
     * @return the {@code SEL0008} value (max 1 characters)
     */
    public String getSel0008() {
        return sel0008;
    }

    /**
     * Sets the row 08 selection flag.
     *
     * @param sel0008 the {@code SEL0008} value (max 1 characters)
     */
    public void setSel0008(String sel0008) {
        this.sel0008 = sel0008;
    }

    /**
     * Returns the row 08 user id.
     *
     * @return the {@code USRID08} value (max 8 characters)
     */
    public String getUsrid08() {
        return usrid08;
    }

    /**
     * Sets the row 08 user id.
     *
     * @param usrid08 the {@code USRID08} value (max 8 characters)
     */
    public void setUsrid08(String usrid08) {
        this.usrid08 = usrid08;
    }

    /**
     * Returns the row 08 first name.
     *
     * @return the {@code FNAME08} value (max 20 characters)
     */
    public String getFname08() {
        return fname08;
    }

    /**
     * Sets the row 08 first name.
     *
     * @param fname08 the {@code FNAME08} value (max 20 characters)
     */
    public void setFname08(String fname08) {
        this.fname08 = fname08;
    }

    /**
     * Returns the row 08 last name.
     *
     * @return the {@code LNAME08} value (max 20 characters)
     */
    public String getLname08() {
        return lname08;
    }

    /**
     * Sets the row 08 last name.
     *
     * @param lname08 the {@code LNAME08} value (max 20 characters)
     */
    public void setLname08(String lname08) {
        this.lname08 = lname08;
    }

    /**
     * Returns the row 08 user type.
     *
     * @return the {@code UTYPE08} value (max 1 characters)
     */
    public String getUtype08() {
        return utype08;
    }

    /**
     * Sets the row 08 user type.
     *
     * @param utype08 the {@code UTYPE08} value (max 1 characters)
     */
    public void setUtype08(String utype08) {
        this.utype08 = utype08;
    }

    /**
     * Returns the row 09 selection flag.
     *
     * @return the {@code SEL0009} value (max 1 characters)
     */
    public String getSel0009() {
        return sel0009;
    }

    /**
     * Sets the row 09 selection flag.
     *
     * @param sel0009 the {@code SEL0009} value (max 1 characters)
     */
    public void setSel0009(String sel0009) {
        this.sel0009 = sel0009;
    }

    /**
     * Returns the row 09 user id.
     *
     * @return the {@code USRID09} value (max 8 characters)
     */
    public String getUsrid09() {
        return usrid09;
    }

    /**
     * Sets the row 09 user id.
     *
     * @param usrid09 the {@code USRID09} value (max 8 characters)
     */
    public void setUsrid09(String usrid09) {
        this.usrid09 = usrid09;
    }

    /**
     * Returns the row 09 first name.
     *
     * @return the {@code FNAME09} value (max 20 characters)
     */
    public String getFname09() {
        return fname09;
    }

    /**
     * Sets the row 09 first name.
     *
     * @param fname09 the {@code FNAME09} value (max 20 characters)
     */
    public void setFname09(String fname09) {
        this.fname09 = fname09;
    }

    /**
     * Returns the row 09 last name.
     *
     * @return the {@code LNAME09} value (max 20 characters)
     */
    public String getLname09() {
        return lname09;
    }

    /**
     * Sets the row 09 last name.
     *
     * @param lname09 the {@code LNAME09} value (max 20 characters)
     */
    public void setLname09(String lname09) {
        this.lname09 = lname09;
    }

    /**
     * Returns the row 09 user type.
     *
     * @return the {@code UTYPE09} value (max 1 characters)
     */
    public String getUtype09() {
        return utype09;
    }

    /**
     * Sets the row 09 user type.
     *
     * @param utype09 the {@code UTYPE09} value (max 1 characters)
     */
    public void setUtype09(String utype09) {
        this.utype09 = utype09;
    }

    /**
     * Returns the row 10 selection flag.
     *
     * @return the {@code SEL0010} value (max 1 characters)
     */
    public String getSel0010() {
        return sel0010;
    }

    /**
     * Sets the row 10 selection flag.
     *
     * @param sel0010 the {@code SEL0010} value (max 1 characters)
     */
    public void setSel0010(String sel0010) {
        this.sel0010 = sel0010;
    }

    /**
     * Returns the row 10 user id.
     *
     * @return the {@code USRID10} value (max 8 characters)
     */
    public String getUsrid10() {
        return usrid10;
    }

    /**
     * Sets the row 10 user id.
     *
     * @param usrid10 the {@code USRID10} value (max 8 characters)
     */
    public void setUsrid10(String usrid10) {
        this.usrid10 = usrid10;
    }

    /**
     * Returns the row 10 first name.
     *
     * @return the {@code FNAME10} value (max 20 characters)
     */
    public String getFname10() {
        return fname10;
    }

    /**
     * Sets the row 10 first name.
     *
     * @param fname10 the {@code FNAME10} value (max 20 characters)
     */
    public void setFname10(String fname10) {
        this.fname10 = fname10;
    }

    /**
     * Returns the row 10 last name.
     *
     * @return the {@code LNAME10} value (max 20 characters)
     */
    public String getLname10() {
        return lname10;
    }

    /**
     * Sets the row 10 last name.
     *
     * @param lname10 the {@code LNAME10} value (max 20 characters)
     */
    public void setLname10(String lname10) {
        this.lname10 = lname10;
    }

    /**
     * Returns the row 10 user type.
     *
     * @return the {@code UTYPE10} value (max 1 characters)
     */
    public String getUtype10() {
        return utype10;
    }

    /**
     * Sets the row 10 user type.
     *
     * @param utype10 the {@code UTYPE10} value (max 1 characters)
     */
    public void setUtype10(String utype10) {
        this.utype10 = utype10;
    }

    /**
     * Returns the error message line.
     *
     * @return the {@code ERRMSG} value (max 78 characters)
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line.
     *
     * @param errmsg the {@code ERRMSG} value (max 78 characters)
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
        return "COUSR00Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
