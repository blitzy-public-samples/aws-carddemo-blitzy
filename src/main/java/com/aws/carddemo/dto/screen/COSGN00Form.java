package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Sign-on screen form.
 *
 * <p>Origin: legacy/cpy-bms/COSGN00.CPY (BMS mapset COSGN00, map COSGN0A).</p>
 *
 * <p>Spring MVC form-backing bean for the sign-on screen (CICS transaction
 * {@code CC00}, program {@code COSGN00C}). Each property corresponds to a BMS
 * value field of the {@code COSGN0AI} symbolic input map and preserves the
 * original 24x80 field-length contract via {@link Size} upper bounds, so the
 * legacy BMS field-length edits are reproduced during Spring MVC request
 * binding. This is a plain POJO instantiated per request via
 * {@code @ModelAttribute}; it holds no business logic and no monetary fields.</p>
 */
public class COSGN00Form {

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

    /** CURTIMEI PIC X(9) - header current time; this screen uses X(9), not X(8). */
    @Size(max = 9)
    private String curtime;

    /** APPLIDI PIC X(8) - CICS APPLID (display). */
    @Size(max = 8)
    private String applid;

    /** SYSIDI PIC X(8) - CICS SYSID (display). */
    @Size(max = 8)
    private String sysid;

    /** USERIDI PIC X(8) - user id entry. */
    @Size(max = 8)
    private String userid;

    /** PASSWDI PIC X(8) - password entry; masked in {@link #toString()}. */
    @Size(max = 8)
    private String passwd;

    /** ERRMSGI PIC X(78) - error message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Server-computed autofocus hint (QA finding P5-08). Carries the field the
     * cursor should land on when the screen is (re-)displayed, mirroring the
     * COBOL {@code MOVE -1 TO USERIDL} / {@code MOVE -1 TO PASSWDL}
     * attribute-length cursor moves in {@code COSGN00C}. The value is one of the
     * {@code th:field} names of the two entry fields ({@link #FOCUS_USER_ID} /
     * {@link #FOCUS_PASSWORD}), or {@code null} when no explicit cursor is
     * requested (COBOL {@code WHEN OTHER} invalid-key path). It is an
     * output-only rendering hint set by the controller from the service's
     * {@code CursorField}; it is never a bound BMS data field.
     */
    private String focusField;

    /** {@link #focusField} token selecting the User ID entry field. */
    public static final String FOCUS_USER_ID = "userid";

    /** {@link #focusField} token selecting the Password entry field. */
    public static final String FOCUS_PASSWORD = "passwd";

    /**
     * Creates an empty sign-on screen form. Required for Spring MVC
     * {@code @ModelAttribute} binding and standard JavaBean instantiation;
     * all properties are populated by request binding.
     */
    public COSGN00Form() {
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
     * @return the {@code CURTIME} value (max 9 characters)
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current time header field.
     *
     * @param curtime the {@code CURTIME} value (max 9 characters)
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the CICS APPLID display field.
     *
     * @return the {@code APPLID} value (max 8 characters)
     */
    public String getApplid() {
        return applid;
    }

    /**
     * Sets the CICS APPLID display field.
     *
     * @param applid the {@code APPLID} value (max 8 characters)
     */
    public void setApplid(String applid) {
        this.applid = applid;
    }

    /**
     * Returns the CICS SYSID display field.
     *
     * @return the {@code SYSID} value (max 8 characters)
     */
    public String getSysid() {
        return sysid;
    }

    /**
     * Sets the CICS SYSID display field.
     *
     * @param sysid the {@code SYSID} value (max 8 characters)
     */
    public void setSysid(String sysid) {
        this.sysid = sysid;
    }

    /**
     * Returns the user id entry field.
     *
     * @return the {@code USERID} value (max 8 characters)
     */
    public String getUserid() {
        return userid;
    }

    /**
     * Sets the user id entry field.
     *
     * @param userid the {@code USERID} value (max 8 characters)
     */
    public void setUserid(String userid) {
        this.userid = userid;
    }

    /**
     * Returns the password entry field. Callers must never log this value in
     * cleartext; {@link #toString()} masks it.
     *
     * @return the {@code PASSWD} value (max 8 characters)
     */
    public String getPasswd() {
        return passwd;
    }

    /**
     * Sets the password entry field.
     *
     * @param passwd the {@code PASSWD} value (max 8 characters)
     */
    public void setPasswd(String passwd) {
        this.passwd = passwd;
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
     * Returns the server-computed autofocus hint (QA finding P5-08).
     *
     * @return {@link #FOCUS_USER_ID}, {@link #FOCUS_PASSWORD}, or {@code null}
     *         when no explicit cursor position is requested
     */
    public String getFocusField() {
        return focusField;
    }

    /**
     * Sets the server-computed autofocus hint (QA finding P5-08).
     *
     * @param focusField {@link #FOCUS_USER_ID}, {@link #FOCUS_PASSWORD}, or
     *                   {@code null} for no explicit cursor position
     */
    public void setFocusField(String focusField) {
        this.focusField = focusField;
    }

    /**
     * Returns a diagnostic representation of this form including every value
     * field. The {@code passwd} field is intentionally rendered as
     * {@code ***} so the cleartext password is never emitted to logs or
     * diagnostics.
     *
     * @return a string representation with the password masked
     */
    @Override
    public String toString() {
        return "COSGN00Form{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", applid='" + applid + '\''
                + ", sysid='" + sysid + '\''
                + ", userid='" + userid + '\''
                + ", passwd='***'"
                + ", errmsg='" + errmsg + '\''
                + ", focusField='" + focusField + '\''
                + '}';
    }
}
