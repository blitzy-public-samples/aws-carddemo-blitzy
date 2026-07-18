package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Screen-form DTO backing the transaction report submit screen.
 *
 * <p>Transaction report submit screen form. This screen lets the user choose a
 * Monthly, Yearly, or Custom transaction report and, for a custom report, supply
 * a start-date and end-date range (each broken into MM / DD / YYYY parts). It is
 * the form-backing bean for CICS transaction {@code CR00} (COBOL program
 * {@code CORPT00C}) and is rendered by the Thymeleaf template
 * {@code templates/CORPT00.html}.</p>
 *
 * <p>Only the BMS <em>value</em> fields (the {@code <name>I} / {@code <name>O}
 * bytes) are modeled here as {@link String} properties; the BMS attribute and
 * plumbing bytes (length {@code L}, flag {@code F}, attribute {@code A}, colour
 * {@code C}, programmed-symbol {@code P}, highlight {@code H}, and validation
 * {@code V}) are intentionally not represented, because they are 3270 terminal
 * plumbing rather than business data. Each property preserves the exact BMS
 * {@code PIC X(n)} field length via {@link Size} to keep the 24x80 screen
 * field/label/length contract intact.</p>
 *
 * <p>The {@code monthly}, {@code yearly}, and {@code custom} properties are
 * single-character selection flags; the {@code sdt*} and {@code edt*} properties
 * are the start-date and end-date parts (MM / DD / YYYY).</p>
 *
 * <p>Origin: legacy/cpy-bms/CORPT00.CPY (BMS mapset CORPT00, map CORPT0A)</p>
 */
public class CORPT00Form {

    /** BMS TRNNAME, PIC X(4): transaction name header field. */
    @Size(max = 4)
    private String trnname;

    /** BMS TITLE01, PIC X(40): first title header line. */
    @Size(max = 40)
    private String title01;

    /** BMS CURDATE, PIC X(8): current date header field. */
    @Size(max = 8)
    private String curdate;

    /** BMS PGMNAME, PIC X(8): program name header field. */
    @Size(max = 8)
    private String pgmname;

    /** BMS TITLE02, PIC X(40): second title header line. */
    @Size(max = 40)
    private String title02;

    /** BMS CURTIME, PIC X(8): current time header field. */
    @Size(max = 8)
    private String curtime;

    /** BMS MONTHLY, PIC X(1): monthly-report selection flag. */
    @Size(max = 1)
    private String monthly;

    /** BMS YEARLY, PIC X(1): yearly-report selection flag. */
    @Size(max = 1)
    private String yearly;

    /** BMS CUSTOM, PIC X(1): custom-report selection flag. */
    @Size(max = 1)
    private String custom;

    /** BMS SDTMM, PIC X(2): start-date month (MM) part. */
    @Size(max = 2)
    private String sdtmm;

    /** BMS SDTDD, PIC X(2): start-date day (DD) part. */
    @Size(max = 2)
    private String sdtdd;

    /** BMS SDTYYYY, PIC X(4): start-date year (YYYY) part. */
    @Size(max = 4)
    private String sdtyyyy;

    /** BMS EDTMM, PIC X(2): end-date month (MM) part. */
    @Size(max = 2)
    private String edtmm;

    /** BMS EDTDD, PIC X(2): end-date day (DD) part. */
    @Size(max = 2)
    private String edtdd;

    /** BMS EDTYYYY, PIC X(4): end-date year (YYYY) part. */
    @Size(max = 4)
    private String edtyyyy;

    /** BMS CONFIRM, PIC X(1): confirm (Y/N) input flag. */
    @Size(max = 1)
    private String confirm;

    /** BMS ERRMSG, PIC X(78): error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty form with all fields unset. Required for framework
     * data-binding and for programmatic construction.
     */
    public CORPT00Form() {
    }

    /**
     * Returns the transaction name header field (BMS TRNNAME).
     *
     * @return the transaction name value, or {@code null} if unset
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction name header field (BMS TRNNAME).
     *
     * @param trnname the transaction name value
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the first title header line (BMS TITLE01).
     *
     * @return the first title value, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first title header line (BMS TITLE01).
     *
     * @param title01 the first title value
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current date header field (BMS CURDATE).
     *
     * @return the current date value, or {@code null} if unset
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current date header field (BMS CURDATE).
     *
     * @param curdate the current date value
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program name header field (BMS PGMNAME).
     *
     * @return the program name value, or {@code null} if unset
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program name header field (BMS PGMNAME).
     *
     * @param pgmname the program name value
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the second title header line (BMS TITLE02).
     *
     * @return the second title value, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second title header line (BMS TITLE02).
     *
     * @param title02 the second title value
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current time header field (BMS CURTIME).
     *
     * @return the current time value, or {@code null} if unset
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time header field (BMS CURTIME).
     *
     * @param curtime the current time value
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the monthly-report selection flag (BMS MONTHLY).
     *
     * @return the monthly selection flag, or {@code null} if unset
     */
    public String getMonthly() {
        return monthly;
    }

    /**
     * Sets the monthly-report selection flag (BMS MONTHLY).
     *
     * @param monthly the monthly selection flag
     */
    public void setMonthly(String monthly) {
        this.monthly = monthly;
    }

    /**
     * Returns the yearly-report selection flag (BMS YEARLY).
     *
     * @return the yearly selection flag, or {@code null} if unset
     */
    public String getYearly() {
        return yearly;
    }

    /**
     * Sets the yearly-report selection flag (BMS YEARLY).
     *
     * @param yearly the yearly selection flag
     */
    public void setYearly(String yearly) {
        this.yearly = yearly;
    }

    /**
     * Returns the custom-report selection flag (BMS CUSTOM).
     *
     * @return the custom selection flag, or {@code null} if unset
     */
    public String getCustom() {
        return custom;
    }

    /**
     * Sets the custom-report selection flag (BMS CUSTOM).
     *
     * @param custom the custom selection flag
     */
    public void setCustom(String custom) {
        this.custom = custom;
    }

    /**
     * Returns the start-date month (MM) part (BMS SDTMM).
     *
     * @return the start-date month value, or {@code null} if unset
     */
    public String getSdtmm() {
        return sdtmm;
    }

    /**
     * Sets the start-date month (MM) part (BMS SDTMM).
     *
     * @param sdtmm the start-date month value
     */
    public void setSdtmm(String sdtmm) {
        this.sdtmm = sdtmm;
    }

    /**
     * Returns the start-date day (DD) part (BMS SDTDD).
     *
     * @return the start-date day value, or {@code null} if unset
     */
    public String getSdtdd() {
        return sdtdd;
    }

    /**
     * Sets the start-date day (DD) part (BMS SDTDD).
     *
     * @param sdtdd the start-date day value
     */
    public void setSdtdd(String sdtdd) {
        this.sdtdd = sdtdd;
    }

    /**
     * Returns the start-date year (YYYY) part (BMS SDTYYYY).
     *
     * @return the start-date year value, or {@code null} if unset
     */
    public String getSdtyyyy() {
        return sdtyyyy;
    }

    /**
     * Sets the start-date year (YYYY) part (BMS SDTYYYY).
     *
     * @param sdtyyyy the start-date year value
     */
    public void setSdtyyyy(String sdtyyyy) {
        this.sdtyyyy = sdtyyyy;
    }

    /**
     * Returns the end-date month (MM) part (BMS EDTMM).
     *
     * @return the end-date month value, or {@code null} if unset
     */
    public String getEdtmm() {
        return edtmm;
    }

    /**
     * Sets the end-date month (MM) part (BMS EDTMM).
     *
     * @param edtmm the end-date month value
     */
    public void setEdtmm(String edtmm) {
        this.edtmm = edtmm;
    }

    /**
     * Returns the end-date day (DD) part (BMS EDTDD).
     *
     * @return the end-date day value, or {@code null} if unset
     */
    public String getEdtdd() {
        return edtdd;
    }

    /**
     * Sets the end-date day (DD) part (BMS EDTDD).
     *
     * @param edtdd the end-date day value
     */
    public void setEdtdd(String edtdd) {
        this.edtdd = edtdd;
    }

    /**
     * Returns the end-date year (YYYY) part (BMS EDTYYYY).
     *
     * @return the end-date year value, or {@code null} if unset
     */
    public String getEdtyyyy() {
        return edtyyyy;
    }

    /**
     * Sets the end-date year (YYYY) part (BMS EDTYYYY).
     *
     * @param edtyyyy the end-date year value
     */
    public void setEdtyyyy(String edtyyyy) {
        this.edtyyyy = edtyyyy;
    }

    /**
     * Returns the confirm (Y/N) input flag (BMS CONFIRM).
     *
     * @return the confirm flag, or {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the confirm (Y/N) input flag (BMS CONFIRM).
     *
     * @param confirm the confirm flag
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns the error message line (BMS ERRMSG).
     *
     * @return the error message value, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line (BMS ERRMSG).
     *
     * @param errmsg the error message value
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns a string representation of this form including every value field.
     * No sensitive fields exist on this screen, so all fields are included.
     *
     * @return a string containing all 17 screen-form field values
     */
    @Override
    public String toString() {
        return "CORPT00Form{"
                + "trnname=" + trnname
                + ", title01=" + title01
                + ", curdate=" + curdate
                + ", pgmname=" + pgmname
                + ", title02=" + title02
                + ", curtime=" + curtime
                + ", monthly=" + monthly
                + ", yearly=" + yearly
                + ", custom=" + custom
                + ", sdtmm=" + sdtmm
                + ", sdtdd=" + sdtdd
                + ", sdtyyyy=" + sdtyyyy
                + ", edtmm=" + edtmm
                + ", edtdd=" + edtdd
                + ", edtyyyy=" + edtyyyy
                + ", confirm=" + confirm
                + ", errmsg=" + errmsg
                + '}';
    }
}
