package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Transaction-list screen form.
 *
 * <p>Origin: legacy/cpy-bms/COTRN00.CPY (BMS mapset COTRN00, map COTRN0A).</p>
 *
 * <p>Spring MVC form-backing bean for the transaction-list screen (CICS
 * transaction {@code CT00}, program {@code COTRN00C}). Each property corresponds
 * to a BMS value field of the {@code COTRN0AI} symbolic input map and preserves
 * the original 24x80 field-length contract via {@link Size} upper bounds, so the
 * legacy BMS field-length edits are reproduced during Spring MVC request
 * binding. The screen lists up to ten transactions per page; the repeated row
 * fields are modeled as flat indexed properties whose numeric suffixes reproduce
 * the exact BMS digit widths ({@code sel0001}-{@code sel0010},
 * {@code trnid01}-{@code trnid10}, {@code tdate01}-{@code tdate10},
 * {@code tdesc01}-{@code tdesc10}, and {@code tamt001}-{@code tamt010}). The
 * monetary amount fields are held as {@code String} to preserve the fixed-width
 * display formatting exactly; they are never parsed to floating-point in this
 * DTO. This is a plain POJO instantiated per request via {@code @ModelAttribute};
 * it holds no business logic.</p>
 */
public class COTRN00Form {

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

    /** PAGENUMI PIC X(8) - current page number. */
    @Size(max = 8)
    private String pagenum;

    /** TRNIDINI PIC X(16) - transaction id search/filter input. */
    @Size(max = 16)
    private String trnidin;

    /** SEL0001I PIC X(1) - row 1 selection flag. */
    @Size(max = 1)
    private String sel0001;

    /** TRNID01I PIC X(16) - row 1 transaction id. */
    @Size(max = 16)
    private String trnid01;

    /** TDATE01I PIC X(8) - row 1 transaction date. */
    @Size(max = 8)
    private String tdate01;

    /** TDESC01I PIC X(26) - row 1 transaction description. */
    @Size(max = 26)
    private String tdesc01;

    /** TAMT001I PIC X(12) - row 1 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt001;

    /** SEL0002I PIC X(1) - row 2 selection flag. */
    @Size(max = 1)
    private String sel0002;

    /** TRNID02I PIC X(16) - row 2 transaction id. */
    @Size(max = 16)
    private String trnid02;

    /** TDATE02I PIC X(8) - row 2 transaction date. */
    @Size(max = 8)
    private String tdate02;

    /** TDESC02I PIC X(26) - row 2 transaction description. */
    @Size(max = 26)
    private String tdesc02;

    /** TAMT002I PIC X(12) - row 2 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt002;

    /** SEL0003I PIC X(1) - row 3 selection flag. */
    @Size(max = 1)
    private String sel0003;

    /** TRNID03I PIC X(16) - row 3 transaction id. */
    @Size(max = 16)
    private String trnid03;

    /** TDATE03I PIC X(8) - row 3 transaction date. */
    @Size(max = 8)
    private String tdate03;

    /** TDESC03I PIC X(26) - row 3 transaction description. */
    @Size(max = 26)
    private String tdesc03;

    /** TAMT003I PIC X(12) - row 3 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt003;

    /** SEL0004I PIC X(1) - row 4 selection flag. */
    @Size(max = 1)
    private String sel0004;

    /** TRNID04I PIC X(16) - row 4 transaction id. */
    @Size(max = 16)
    private String trnid04;

    /** TDATE04I PIC X(8) - row 4 transaction date. */
    @Size(max = 8)
    private String tdate04;

    /** TDESC04I PIC X(26) - row 4 transaction description. */
    @Size(max = 26)
    private String tdesc04;

    /** TAMT004I PIC X(12) - row 4 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt004;

    /** SEL0005I PIC X(1) - row 5 selection flag. */
    @Size(max = 1)
    private String sel0005;

    /** TRNID05I PIC X(16) - row 5 transaction id. */
    @Size(max = 16)
    private String trnid05;

    /** TDATE05I PIC X(8) - row 5 transaction date. */
    @Size(max = 8)
    private String tdate05;

    /** TDESC05I PIC X(26) - row 5 transaction description. */
    @Size(max = 26)
    private String tdesc05;

    /** TAMT005I PIC X(12) - row 5 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt005;

    /** SEL0006I PIC X(1) - row 6 selection flag. */
    @Size(max = 1)
    private String sel0006;

    /** TRNID06I PIC X(16) - row 6 transaction id. */
    @Size(max = 16)
    private String trnid06;

    /** TDATE06I PIC X(8) - row 6 transaction date. */
    @Size(max = 8)
    private String tdate06;

    /** TDESC06I PIC X(26) - row 6 transaction description. */
    @Size(max = 26)
    private String tdesc06;

    /** TAMT006I PIC X(12) - row 6 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt006;

    /** SEL0007I PIC X(1) - row 7 selection flag. */
    @Size(max = 1)
    private String sel0007;

    /** TRNID07I PIC X(16) - row 7 transaction id. */
    @Size(max = 16)
    private String trnid07;

    /** TDATE07I PIC X(8) - row 7 transaction date. */
    @Size(max = 8)
    private String tdate07;

    /** TDESC07I PIC X(26) - row 7 transaction description. */
    @Size(max = 26)
    private String tdesc07;

    /** TAMT007I PIC X(12) - row 7 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt007;

    /** SEL0008I PIC X(1) - row 8 selection flag. */
    @Size(max = 1)
    private String sel0008;

    /** TRNID08I PIC X(16) - row 8 transaction id. */
    @Size(max = 16)
    private String trnid08;

    /** TDATE08I PIC X(8) - row 8 transaction date. */
    @Size(max = 8)
    private String tdate08;

    /** TDESC08I PIC X(26) - row 8 transaction description. */
    @Size(max = 26)
    private String tdesc08;

    /** TAMT008I PIC X(12) - row 8 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt008;

    /** SEL0009I PIC X(1) - row 9 selection flag. */
    @Size(max = 1)
    private String sel0009;

    /** TRNID09I PIC X(16) - row 9 transaction id. */
    @Size(max = 16)
    private String trnid09;

    /** TDATE09I PIC X(8) - row 9 transaction date. */
    @Size(max = 8)
    private String tdate09;

    /** TDESC09I PIC X(26) - row 9 transaction description. */
    @Size(max = 26)
    private String tdesc09;

    /** TAMT009I PIC X(12) - row 9 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt009;

    /** SEL0010I PIC X(1) - row 10 selection flag. */
    @Size(max = 1)
    private String sel0010;

    /** TRNID10I PIC X(16) - row 10 transaction id. */
    @Size(max = 16)
    private String trnid10;

    /** TDATE10I PIC X(8) - row 10 transaction date. */
    @Size(max = 8)
    private String tdate10;

    /** TDESC10I PIC X(26) - row 10 transaction description. */
    @Size(max = 26)
    private String tdesc10;

    /** TAMT010I PIC X(12) - row 10 transaction amount (money value held as String). */
    @Size(max = 12)
    private String tamt010;

    /** ERRMSGI PIC X(78) - error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty transaction-list screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation;
     * all properties are populated by request binding.
     */
    public COTRN00Form() {
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
     * @return the {@code CURDATE} value (max 8 characters, MM/DD/YY)
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current date header field.
     *
     * @param curdate the {@code CURDATE} value (max 8 characters, MM/DD/YY)
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
     * @return the {@code CURTIME} value (max 8 characters, HH:MM:SS)
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time header field.
     *
     * @param curtime the {@code CURTIME} value (max 8 characters, HH:MM:SS)
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the current page-number field.
     *
     * @return the {@code PAGENUM} value (max 8 characters)
     */
    public String getPagenum() {
        return pagenum;
    }

    /**
     * Sets the current page-number field.
     *
     * @param pagenum the {@code PAGENUM} value (max 8 characters)
     */
    public void setPagenum(String pagenum) {
        this.pagenum = pagenum;
    }

    /**
     * Returns the transaction id search input field.
     *
     * @return the {@code TRNIDIN} value (max 16 characters)
     */
    public String getTrnidin() {
        return trnidin;
    }

    /**
     * Sets the transaction id search input field.
     *
     * @param trnidin the {@code TRNIDIN} value (max 16 characters)
     */
    public void setTrnidin(String trnidin) {
        this.trnidin = trnidin;
    }

    /**
     * Returns the selection flag for list row 1.
     *
     * @return the {@code SEL0001} value (max 1 characters)
     */
    public String getSel0001() {
        return sel0001;
    }

    /**
     * Sets the selection flag for list row 1.
     *
     * @param sel0001 the {@code SEL0001} value (max 1 characters)
     */
    public void setSel0001(String sel0001) {
        this.sel0001 = sel0001;
    }

    /**
     * Returns the transaction id for list row 1.
     *
     * @return the {@code TRNID01} value (max 16 characters)
     */
    public String getTrnid01() {
        return trnid01;
    }

    /**
     * Sets the transaction id for list row 1.
     *
     * @param trnid01 the {@code TRNID01} value (max 16 characters)
     */
    public void setTrnid01(String trnid01) {
        this.trnid01 = trnid01;
    }

    /**
     * Returns the transaction date for list row 1.
     *
     * @return the {@code TDATE01} value (max 8 characters)
     */
    public String getTdate01() {
        return tdate01;
    }

    /**
     * Sets the transaction date for list row 1.
     *
     * @param tdate01 the {@code TDATE01} value (max 8 characters)
     */
    public void setTdate01(String tdate01) {
        this.tdate01 = tdate01;
    }

    /**
     * Returns the transaction description for list row 1.
     *
     * @return the {@code TDESC01} value (max 26 characters)
     */
    public String getTdesc01() {
        return tdesc01;
    }

    /**
     * Sets the transaction description for list row 1.
     *
     * @param tdesc01 the {@code TDESC01} value (max 26 characters)
     */
    public void setTdesc01(String tdesc01) {
        this.tdesc01 = tdesc01;
    }

    /**
     * Returns the transaction amount for list row 1.
     *
     * @return the {@code TAMT001} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt001() {
        return tamt001;
    }

    /**
     * Sets the transaction amount for list row 1.
     *
     * @param tamt001 the {@code TAMT001} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt001(String tamt001) {
        this.tamt001 = tamt001;
    }

    /**
     * Returns the selection flag for list row 2.
     *
     * @return the {@code SEL0002} value (max 1 characters)
     */
    public String getSel0002() {
        return sel0002;
    }

    /**
     * Sets the selection flag for list row 2.
     *
     * @param sel0002 the {@code SEL0002} value (max 1 characters)
     */
    public void setSel0002(String sel0002) {
        this.sel0002 = sel0002;
    }

    /**
     * Returns the transaction id for list row 2.
     *
     * @return the {@code TRNID02} value (max 16 characters)
     */
    public String getTrnid02() {
        return trnid02;
    }

    /**
     * Sets the transaction id for list row 2.
     *
     * @param trnid02 the {@code TRNID02} value (max 16 characters)
     */
    public void setTrnid02(String trnid02) {
        this.trnid02 = trnid02;
    }

    /**
     * Returns the transaction date for list row 2.
     *
     * @return the {@code TDATE02} value (max 8 characters)
     */
    public String getTdate02() {
        return tdate02;
    }

    /**
     * Sets the transaction date for list row 2.
     *
     * @param tdate02 the {@code TDATE02} value (max 8 characters)
     */
    public void setTdate02(String tdate02) {
        this.tdate02 = tdate02;
    }

    /**
     * Returns the transaction description for list row 2.
     *
     * @return the {@code TDESC02} value (max 26 characters)
     */
    public String getTdesc02() {
        return tdesc02;
    }

    /**
     * Sets the transaction description for list row 2.
     *
     * @param tdesc02 the {@code TDESC02} value (max 26 characters)
     */
    public void setTdesc02(String tdesc02) {
        this.tdesc02 = tdesc02;
    }

    /**
     * Returns the transaction amount for list row 2.
     *
     * @return the {@code TAMT002} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt002() {
        return tamt002;
    }

    /**
     * Sets the transaction amount for list row 2.
     *
     * @param tamt002 the {@code TAMT002} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt002(String tamt002) {
        this.tamt002 = tamt002;
    }

    /**
     * Returns the selection flag for list row 3.
     *
     * @return the {@code SEL0003} value (max 1 characters)
     */
    public String getSel0003() {
        return sel0003;
    }

    /**
     * Sets the selection flag for list row 3.
     *
     * @param sel0003 the {@code SEL0003} value (max 1 characters)
     */
    public void setSel0003(String sel0003) {
        this.sel0003 = sel0003;
    }

    /**
     * Returns the transaction id for list row 3.
     *
     * @return the {@code TRNID03} value (max 16 characters)
     */
    public String getTrnid03() {
        return trnid03;
    }

    /**
     * Sets the transaction id for list row 3.
     *
     * @param trnid03 the {@code TRNID03} value (max 16 characters)
     */
    public void setTrnid03(String trnid03) {
        this.trnid03 = trnid03;
    }

    /**
     * Returns the transaction date for list row 3.
     *
     * @return the {@code TDATE03} value (max 8 characters)
     */
    public String getTdate03() {
        return tdate03;
    }

    /**
     * Sets the transaction date for list row 3.
     *
     * @param tdate03 the {@code TDATE03} value (max 8 characters)
     */
    public void setTdate03(String tdate03) {
        this.tdate03 = tdate03;
    }

    /**
     * Returns the transaction description for list row 3.
     *
     * @return the {@code TDESC03} value (max 26 characters)
     */
    public String getTdesc03() {
        return tdesc03;
    }

    /**
     * Sets the transaction description for list row 3.
     *
     * @param tdesc03 the {@code TDESC03} value (max 26 characters)
     */
    public void setTdesc03(String tdesc03) {
        this.tdesc03 = tdesc03;
    }

    /**
     * Returns the transaction amount for list row 3.
     *
     * @return the {@code TAMT003} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt003() {
        return tamt003;
    }

    /**
     * Sets the transaction amount for list row 3.
     *
     * @param tamt003 the {@code TAMT003} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt003(String tamt003) {
        this.tamt003 = tamt003;
    }

    /**
     * Returns the selection flag for list row 4.
     *
     * @return the {@code SEL0004} value (max 1 characters)
     */
    public String getSel0004() {
        return sel0004;
    }

    /**
     * Sets the selection flag for list row 4.
     *
     * @param sel0004 the {@code SEL0004} value (max 1 characters)
     */
    public void setSel0004(String sel0004) {
        this.sel0004 = sel0004;
    }

    /**
     * Returns the transaction id for list row 4.
     *
     * @return the {@code TRNID04} value (max 16 characters)
     */
    public String getTrnid04() {
        return trnid04;
    }

    /**
     * Sets the transaction id for list row 4.
     *
     * @param trnid04 the {@code TRNID04} value (max 16 characters)
     */
    public void setTrnid04(String trnid04) {
        this.trnid04 = trnid04;
    }

    /**
     * Returns the transaction date for list row 4.
     *
     * @return the {@code TDATE04} value (max 8 characters)
     */
    public String getTdate04() {
        return tdate04;
    }

    /**
     * Sets the transaction date for list row 4.
     *
     * @param tdate04 the {@code TDATE04} value (max 8 characters)
     */
    public void setTdate04(String tdate04) {
        this.tdate04 = tdate04;
    }

    /**
     * Returns the transaction description for list row 4.
     *
     * @return the {@code TDESC04} value (max 26 characters)
     */
    public String getTdesc04() {
        return tdesc04;
    }

    /**
     * Sets the transaction description for list row 4.
     *
     * @param tdesc04 the {@code TDESC04} value (max 26 characters)
     */
    public void setTdesc04(String tdesc04) {
        this.tdesc04 = tdesc04;
    }

    /**
     * Returns the transaction amount for list row 4.
     *
     * @return the {@code TAMT004} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt004() {
        return tamt004;
    }

    /**
     * Sets the transaction amount for list row 4.
     *
     * @param tamt004 the {@code TAMT004} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt004(String tamt004) {
        this.tamt004 = tamt004;
    }

    /**
     * Returns the selection flag for list row 5.
     *
     * @return the {@code SEL0005} value (max 1 characters)
     */
    public String getSel0005() {
        return sel0005;
    }

    /**
     * Sets the selection flag for list row 5.
     *
     * @param sel0005 the {@code SEL0005} value (max 1 characters)
     */
    public void setSel0005(String sel0005) {
        this.sel0005 = sel0005;
    }

    /**
     * Returns the transaction id for list row 5.
     *
     * @return the {@code TRNID05} value (max 16 characters)
     */
    public String getTrnid05() {
        return trnid05;
    }

    /**
     * Sets the transaction id for list row 5.
     *
     * @param trnid05 the {@code TRNID05} value (max 16 characters)
     */
    public void setTrnid05(String trnid05) {
        this.trnid05 = trnid05;
    }

    /**
     * Returns the transaction date for list row 5.
     *
     * @return the {@code TDATE05} value (max 8 characters)
     */
    public String getTdate05() {
        return tdate05;
    }

    /**
     * Sets the transaction date for list row 5.
     *
     * @param tdate05 the {@code TDATE05} value (max 8 characters)
     */
    public void setTdate05(String tdate05) {
        this.tdate05 = tdate05;
    }

    /**
     * Returns the transaction description for list row 5.
     *
     * @return the {@code TDESC05} value (max 26 characters)
     */
    public String getTdesc05() {
        return tdesc05;
    }

    /**
     * Sets the transaction description for list row 5.
     *
     * @param tdesc05 the {@code TDESC05} value (max 26 characters)
     */
    public void setTdesc05(String tdesc05) {
        this.tdesc05 = tdesc05;
    }

    /**
     * Returns the transaction amount for list row 5.
     *
     * @return the {@code TAMT005} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt005() {
        return tamt005;
    }

    /**
     * Sets the transaction amount for list row 5.
     *
     * @param tamt005 the {@code TAMT005} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt005(String tamt005) {
        this.tamt005 = tamt005;
    }

    /**
     * Returns the selection flag for list row 6.
     *
     * @return the {@code SEL0006} value (max 1 characters)
     */
    public String getSel0006() {
        return sel0006;
    }

    /**
     * Sets the selection flag for list row 6.
     *
     * @param sel0006 the {@code SEL0006} value (max 1 characters)
     */
    public void setSel0006(String sel0006) {
        this.sel0006 = sel0006;
    }

    /**
     * Returns the transaction id for list row 6.
     *
     * @return the {@code TRNID06} value (max 16 characters)
     */
    public String getTrnid06() {
        return trnid06;
    }

    /**
     * Sets the transaction id for list row 6.
     *
     * @param trnid06 the {@code TRNID06} value (max 16 characters)
     */
    public void setTrnid06(String trnid06) {
        this.trnid06 = trnid06;
    }

    /**
     * Returns the transaction date for list row 6.
     *
     * @return the {@code TDATE06} value (max 8 characters)
     */
    public String getTdate06() {
        return tdate06;
    }

    /**
     * Sets the transaction date for list row 6.
     *
     * @param tdate06 the {@code TDATE06} value (max 8 characters)
     */
    public void setTdate06(String tdate06) {
        this.tdate06 = tdate06;
    }

    /**
     * Returns the transaction description for list row 6.
     *
     * @return the {@code TDESC06} value (max 26 characters)
     */
    public String getTdesc06() {
        return tdesc06;
    }

    /**
     * Sets the transaction description for list row 6.
     *
     * @param tdesc06 the {@code TDESC06} value (max 26 characters)
     */
    public void setTdesc06(String tdesc06) {
        this.tdesc06 = tdesc06;
    }

    /**
     * Returns the transaction amount for list row 6.
     *
     * @return the {@code TAMT006} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt006() {
        return tamt006;
    }

    /**
     * Sets the transaction amount for list row 6.
     *
     * @param tamt006 the {@code TAMT006} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt006(String tamt006) {
        this.tamt006 = tamt006;
    }

    /**
     * Returns the selection flag for list row 7.
     *
     * @return the {@code SEL0007} value (max 1 characters)
     */
    public String getSel0007() {
        return sel0007;
    }

    /**
     * Sets the selection flag for list row 7.
     *
     * @param sel0007 the {@code SEL0007} value (max 1 characters)
     */
    public void setSel0007(String sel0007) {
        this.sel0007 = sel0007;
    }

    /**
     * Returns the transaction id for list row 7.
     *
     * @return the {@code TRNID07} value (max 16 characters)
     */
    public String getTrnid07() {
        return trnid07;
    }

    /**
     * Sets the transaction id for list row 7.
     *
     * @param trnid07 the {@code TRNID07} value (max 16 characters)
     */
    public void setTrnid07(String trnid07) {
        this.trnid07 = trnid07;
    }

    /**
     * Returns the transaction date for list row 7.
     *
     * @return the {@code TDATE07} value (max 8 characters)
     */
    public String getTdate07() {
        return tdate07;
    }

    /**
     * Sets the transaction date for list row 7.
     *
     * @param tdate07 the {@code TDATE07} value (max 8 characters)
     */
    public void setTdate07(String tdate07) {
        this.tdate07 = tdate07;
    }

    /**
     * Returns the transaction description for list row 7.
     *
     * @return the {@code TDESC07} value (max 26 characters)
     */
    public String getTdesc07() {
        return tdesc07;
    }

    /**
     * Sets the transaction description for list row 7.
     *
     * @param tdesc07 the {@code TDESC07} value (max 26 characters)
     */
    public void setTdesc07(String tdesc07) {
        this.tdesc07 = tdesc07;
    }

    /**
     * Returns the transaction amount for list row 7.
     *
     * @return the {@code TAMT007} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt007() {
        return tamt007;
    }

    /**
     * Sets the transaction amount for list row 7.
     *
     * @param tamt007 the {@code TAMT007} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt007(String tamt007) {
        this.tamt007 = tamt007;
    }

    /**
     * Returns the selection flag for list row 8.
     *
     * @return the {@code SEL0008} value (max 1 characters)
     */
    public String getSel0008() {
        return sel0008;
    }

    /**
     * Sets the selection flag for list row 8.
     *
     * @param sel0008 the {@code SEL0008} value (max 1 characters)
     */
    public void setSel0008(String sel0008) {
        this.sel0008 = sel0008;
    }

    /**
     * Returns the transaction id for list row 8.
     *
     * @return the {@code TRNID08} value (max 16 characters)
     */
    public String getTrnid08() {
        return trnid08;
    }

    /**
     * Sets the transaction id for list row 8.
     *
     * @param trnid08 the {@code TRNID08} value (max 16 characters)
     */
    public void setTrnid08(String trnid08) {
        this.trnid08 = trnid08;
    }

    /**
     * Returns the transaction date for list row 8.
     *
     * @return the {@code TDATE08} value (max 8 characters)
     */
    public String getTdate08() {
        return tdate08;
    }

    /**
     * Sets the transaction date for list row 8.
     *
     * @param tdate08 the {@code TDATE08} value (max 8 characters)
     */
    public void setTdate08(String tdate08) {
        this.tdate08 = tdate08;
    }

    /**
     * Returns the transaction description for list row 8.
     *
     * @return the {@code TDESC08} value (max 26 characters)
     */
    public String getTdesc08() {
        return tdesc08;
    }

    /**
     * Sets the transaction description for list row 8.
     *
     * @param tdesc08 the {@code TDESC08} value (max 26 characters)
     */
    public void setTdesc08(String tdesc08) {
        this.tdesc08 = tdesc08;
    }

    /**
     * Returns the transaction amount for list row 8.
     *
     * @return the {@code TAMT008} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt008() {
        return tamt008;
    }

    /**
     * Sets the transaction amount for list row 8.
     *
     * @param tamt008 the {@code TAMT008} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt008(String tamt008) {
        this.tamt008 = tamt008;
    }

    /**
     * Returns the selection flag for list row 9.
     *
     * @return the {@code SEL0009} value (max 1 characters)
     */
    public String getSel0009() {
        return sel0009;
    }

    /**
     * Sets the selection flag for list row 9.
     *
     * @param sel0009 the {@code SEL0009} value (max 1 characters)
     */
    public void setSel0009(String sel0009) {
        this.sel0009 = sel0009;
    }

    /**
     * Returns the transaction id for list row 9.
     *
     * @return the {@code TRNID09} value (max 16 characters)
     */
    public String getTrnid09() {
        return trnid09;
    }

    /**
     * Sets the transaction id for list row 9.
     *
     * @param trnid09 the {@code TRNID09} value (max 16 characters)
     */
    public void setTrnid09(String trnid09) {
        this.trnid09 = trnid09;
    }

    /**
     * Returns the transaction date for list row 9.
     *
     * @return the {@code TDATE09} value (max 8 characters)
     */
    public String getTdate09() {
        return tdate09;
    }

    /**
     * Sets the transaction date for list row 9.
     *
     * @param tdate09 the {@code TDATE09} value (max 8 characters)
     */
    public void setTdate09(String tdate09) {
        this.tdate09 = tdate09;
    }

    /**
     * Returns the transaction description for list row 9.
     *
     * @return the {@code TDESC09} value (max 26 characters)
     */
    public String getTdesc09() {
        return tdesc09;
    }

    /**
     * Sets the transaction description for list row 9.
     *
     * @param tdesc09 the {@code TDESC09} value (max 26 characters)
     */
    public void setTdesc09(String tdesc09) {
        this.tdesc09 = tdesc09;
    }

    /**
     * Returns the transaction amount for list row 9.
     *
     * @return the {@code TAMT009} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt009() {
        return tamt009;
    }

    /**
     * Sets the transaction amount for list row 9.
     *
     * @param tamt009 the {@code TAMT009} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt009(String tamt009) {
        this.tamt009 = tamt009;
    }

    /**
     * Returns the selection flag for list row 10.
     *
     * @return the {@code SEL0010} value (max 1 characters)
     */
    public String getSel0010() {
        return sel0010;
    }

    /**
     * Sets the selection flag for list row 10.
     *
     * @param sel0010 the {@code SEL0010} value (max 1 characters)
     */
    public void setSel0010(String sel0010) {
        this.sel0010 = sel0010;
    }

    /**
     * Returns the transaction id for list row 10.
     *
     * @return the {@code TRNID10} value (max 16 characters)
     */
    public String getTrnid10() {
        return trnid10;
    }

    /**
     * Sets the transaction id for list row 10.
     *
     * @param trnid10 the {@code TRNID10} value (max 16 characters)
     */
    public void setTrnid10(String trnid10) {
        this.trnid10 = trnid10;
    }

    /**
     * Returns the transaction date for list row 10.
     *
     * @return the {@code TDATE10} value (max 8 characters)
     */
    public String getTdate10() {
        return tdate10;
    }

    /**
     * Sets the transaction date for list row 10.
     *
     * @param tdate10 the {@code TDATE10} value (max 8 characters)
     */
    public void setTdate10(String tdate10) {
        this.tdate10 = tdate10;
    }

    /**
     * Returns the transaction description for list row 10.
     *
     * @return the {@code TDESC10} value (max 26 characters)
     */
    public String getTdesc10() {
        return tdesc10;
    }

    /**
     * Sets the transaction description for list row 10.
     *
     * @param tdesc10 the {@code TDESC10} value (max 26 characters)
     */
    public void setTdesc10(String tdesc10) {
        this.tdesc10 = tdesc10;
    }

    /**
     * Returns the transaction amount for list row 10.
     *
     * @return the {@code TAMT010} value (max 12 characters, formatted amount text held as String)
     */
    public String getTamt010() {
        return tamt010;
    }

    /**
     * Sets the transaction amount for list row 10.
     *
     * @param tamt010 the {@code TAMT010} value (max 12 characters, formatted amount text held as String)
     */
    public void setTamt010(String tamt010) {
        this.tamt010 = tamt010;
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
     * Returns a diagnostic representation of this form including every value
     * field. This screen has no password field, so no masking is required.
     *
     * @return a string representation of all form fields
     */
    @Override
    public String toString() {
        return "COTRN00Form{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", pagenum='" + pagenum + '\''
                + ", trnidin='" + trnidin + '\''
                + ", sel0001='" + sel0001 + '\''
                + ", trnid01='" + trnid01 + '\''
                + ", tdate01='" + tdate01 + '\''
                + ", tdesc01='" + tdesc01 + '\''
                + ", tamt001='" + tamt001 + '\''
                + ", sel0002='" + sel0002 + '\''
                + ", trnid02='" + trnid02 + '\''
                + ", tdate02='" + tdate02 + '\''
                + ", tdesc02='" + tdesc02 + '\''
                + ", tamt002='" + tamt002 + '\''
                + ", sel0003='" + sel0003 + '\''
                + ", trnid03='" + trnid03 + '\''
                + ", tdate03='" + tdate03 + '\''
                + ", tdesc03='" + tdesc03 + '\''
                + ", tamt003='" + tamt003 + '\''
                + ", sel0004='" + sel0004 + '\''
                + ", trnid04='" + trnid04 + '\''
                + ", tdate04='" + tdate04 + '\''
                + ", tdesc04='" + tdesc04 + '\''
                + ", tamt004='" + tamt004 + '\''
                + ", sel0005='" + sel0005 + '\''
                + ", trnid05='" + trnid05 + '\''
                + ", tdate05='" + tdate05 + '\''
                + ", tdesc05='" + tdesc05 + '\''
                + ", tamt005='" + tamt005 + '\''
                + ", sel0006='" + sel0006 + '\''
                + ", trnid06='" + trnid06 + '\''
                + ", tdate06='" + tdate06 + '\''
                + ", tdesc06='" + tdesc06 + '\''
                + ", tamt006='" + tamt006 + '\''
                + ", sel0007='" + sel0007 + '\''
                + ", trnid07='" + trnid07 + '\''
                + ", tdate07='" + tdate07 + '\''
                + ", tdesc07='" + tdesc07 + '\''
                + ", tamt007='" + tamt007 + '\''
                + ", sel0008='" + sel0008 + '\''
                + ", trnid08='" + trnid08 + '\''
                + ", tdate08='" + tdate08 + '\''
                + ", tdesc08='" + tdesc08 + '\''
                + ", tamt008='" + tamt008 + '\''
                + ", sel0009='" + sel0009 + '\''
                + ", trnid09='" + trnid09 + '\''
                + ", tdate09='" + tdate09 + '\''
                + ", tdesc09='" + tdesc09 + '\''
                + ", tamt009='" + tamt009 + '\''
                + ", sel0010='" + sel0010 + '\''
                + ", trnid10='" + trnid10 + '\''
                + ", tdate10='" + tdate10 + '\''
                + ", tdesc10='" + tdesc10 + '\''
                + ", tamt010='" + tamt010 + '\''
                + ", errmsg='" + errmsg + '\''
                + '}';
    }
}
