package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * User-add screen form.
 *
 * <p>Origin: legacy/cpy-bms/COUSR01.CPY (BMS mapset COUSR01, map COUSR1A).</p>
 *
 * <p>Spring MVC form-backing bean for the user-add (admin) screen (CICS
 * transaction {@code CU01}, program {@code COUSR01C}). Each property
 * corresponds to a BMS value field of the {@code COUSR1AI} symbolic input map
 * and preserves the original 24x80 field-length contract via {@link Size}
 * upper bounds, so the legacy BMS field-length edits are reproduced during
 * Spring MVC request binding. This is a plain POJO instantiated per request via
 * {@code @ModelAttribute}; it holds no business logic and no monetary fields.
 * The {@code passwd} property carries a cleartext password and is never emitted by
 * {@link #toString()} (which returns only an identity string), so it never reaches logs or diagnostics.</p>
 */
public class COUSR01Form {

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

    /** FNAMEI PIC X(20) - new user first name. */
    @Size(max = 20)
    private String fname;

    /** LNAMEI PIC X(20) - new user last name. */
    @Size(max = 20)
    private String lname;

    /** USERIDI PIC X(8) - new user id. */
    @Size(max = 8)
    private String userid;

    /** PASSWDI PIC X(8) - cleartext password entry; never emitted by {@link #toString()} (review finding F9). */
    @Size(max = 8)
    private String passwd;

    /** USRTYPEI PIC X(1) - user type (A = admin, U = user). */
    @Size(max = 1)
    private String usrtype;

    /** ERRMSGI PIC X(78) - error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty user-add screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation;
     * all properties are populated by request binding.
     */
    public COUSR01Form() {
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
     * Returns the new user first name entry field.
     *
     * @return the {@code FNAME} value (max 20 characters)
     */
    public String getFname() {
        return fname;
    }

    /**
     * Sets the new user first name entry field.
     *
     * @param fname the {@code FNAME} value (max 20 characters)
     */
    public void setFname(String fname) {
        this.fname = fname;
    }

    /**
     * Returns the new user last name entry field.
     *
     * @return the {@code LNAME} value (max 20 characters)
     */
    public String getLname() {
        return lname;
    }

    /**
     * Sets the new user last name entry field.
     *
     * @param lname the {@code LNAME} value (max 20 characters)
     */
    public void setLname(String lname) {
        this.lname = lname;
    }

    /**
     * Returns the new user id entry field.
     *
     * @return the {@code USERID} value (max 8 characters)
     */
    public String getUserid() {
        return userid;
    }

    /**
     * Sets the new user id entry field.
     *
     * @param userid the {@code USERID} value (max 8 characters)
     */
    public void setUserid(String userid) {
        this.userid = userid;
    }

    /**
     * Returns the cleartext password entry field. Callers must never log this
     * value in cleartext; {@link #toString()} never emits it (review finding F9).
     *
     * @return the {@code PASSWD} value (max 8 characters)
     */
    public String getPasswd() {
        return passwd;
    }

    /**
     * Sets the cleartext password entry field.
     *
     * @param passwd the {@code PASSWD} value (max 8 characters)
     */
    public void setPasswd(String passwd) {
        this.passwd = passwd;
    }

    /**
     * Returns the user type entry field.
     *
     * @return the {@code USRTYPE} value (max 1 character; {@code A} = admin,
     *         {@code U} = user)
     */
    public String getUsrtype() {
        return usrtype;
    }

    /**
     * Sets the user type entry field.
     *
     * @param usrtype the {@code USRTYPE} value (max 1 character; {@code A} =
     *                admin, {@code U} = user)
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
     * BMS {@code ERRMSGC} &mdash; the colour attribute of the {@link #errmsg} line, reproducing the
     * COBOL {@code MOVE DFHxxx TO ERRMSGC OF COUSR1AO}. Holds the semantic 3270 colour token the
     * template uses to pick the message colour class: {@code "red"} (error; the BMS map default
     * {@code COLOR=RED}), {@code "green"} (COBOL {@code DFHGREEN}, the "has been added" confirmation
     * line), or {@code "neutral"} (COBOL {@code DFHNEUTR}). Render-only (never a bound input;
     * excluded from the {@code @InitBinder} allow-list); defaults to {@code "red"} so any path that
     * does not explicitly set a colour reproduces the legacy default red line.
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
        return "COUSR01Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
