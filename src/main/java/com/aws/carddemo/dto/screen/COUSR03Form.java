package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * User-delete screen form.
 *
 * <p>Origin: legacy/cpy-bms/COUSR03.CPY (BMS mapset COUSR03, map COUSR3A).</p>
 *
 * <p>Spring MVC form-backing bean for the administrative user-delete screen
 * (CICS transaction {@code CU03}, program {@code COUSR03C}). Each property
 * corresponds to a BMS value field of the {@code COUSR3AI} symbolic input map
 * and preserves the original 24x80 field-length contract via {@link Size} upper
 * bounds, so the legacy BMS field-length edits are reproduced during Spring MVC
 * request binding. The operator keys a user id to locate; the first name, last
 * name, and user type are populated for confirmation before deletion. This is a
 * plain POJO instantiated per request via {@code @ModelAttribute}; it holds no
 * business logic, no monetary fields, and (unlike the add/update screens) no
 * password field.</p>
 */
public class COUSR03Form {

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

    /** USRIDINI PIC X(8) - user id to delete (search key). */
    @Size(max = 8)
    private String usridin;

    /** FNAMEI PIC X(20) - first name (display). */
    @Size(max = 20)
    private String fname;

    /** LNAMEI PIC X(20) - last name (display). */
    @Size(max = 20)
    private String lname;

    /** USRTYPEI PIC X(1) - user type A/U (display). */
    @Size(max = 1)
    private String usrtype;

    /** ERRMSGI PIC X(78) - error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty user-delete screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation;
     * all properties are populated by request binding.
     */
    public COUSR03Form() {
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
     * Returns the user id to delete (search key).
     *
     * @return the {@code USRIDIN} value (max 8 characters)
     */
    public String getUsridin() {
        return usridin;
    }

    /**
     * Sets the user id to delete (search key).
     *
     * @param usridin the {@code USRIDIN} value (max 8 characters)
     */
    public void setUsridin(String usridin) {
        this.usridin = usridin;
    }

    /**
     * Returns the first name display field.
     *
     * @return the {@code FNAME} value (max 20 characters)
     */
    public String getFname() {
        return fname;
    }

    /**
     * Sets the first name display field.
     *
     * @param fname the {@code FNAME} value (max 20 characters)
     */
    public void setFname(String fname) {
        this.fname = fname;
    }

    /**
     * Returns the last name display field.
     *
     * @return the {@code LNAME} value (max 20 characters)
     */
    public String getLname() {
        return lname;
    }

    /**
     * Sets the last name display field.
     *
     * @param lname the {@code LNAME} value (max 20 characters)
     */
    public void setLname(String lname) {
        this.lname = lname;
    }

    /**
     * Returns the user type display field ({@code A} = admin, {@code U} = user).
     *
     * @return the {@code USRTYPE} value (max 1 character)
     */
    public String getUsrtype() {
        return usrtype;
    }

    /**
     * Sets the user type display field ({@code A} = admin, {@code U} = user).
     *
     * @param usrtype the {@code USRTYPE} value (max 1 character)
     */
    public void setUsrtype(String usrtype) {
        this.usrtype = usrtype;
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
        return "COUSR03Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
