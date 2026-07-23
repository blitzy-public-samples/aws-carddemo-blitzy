package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Admin menu screen form.
 *
 * <p>Origin: legacy/cpy-bms/COADM01.CPY (BMS mapset COADM01, map COADM1A).</p>
 *
 * <p>Form-backing bean for the AWS CardDemo admin menu screen (CICS transaction
 * {@code CA00}, program {@code COADM01C}). It backs the server-rendered Thymeleaf
 * template {@code templates/COADM01.html}. Each property mirrors a single BMS
 * symbolic-map value field and preserves the original fixed-width {@code PIC X(n)}
 * length through {@link Size}, honouring the 24x80 field/label/length contract of
 * the source screen. The BMS attribute and plumbing bytes (length {@code L}, flag
 * {@code F}, attribute {@code A}, and the output-side {@code C}/{@code P}/{@code H}/
 * {@code V} bytes) are intentionally not modelled because they are presentation
 * control artifacts rather than screen values. The layout is structurally identical
 * to the main-menu screen form (COMEN01).</p>
 */
public class COADM01Form {

    /** BMS field TRNNAME, PIC X(4): transaction identifier shown on the screen. */
    @Size(max = 4)
    private String trnname;

    /** BMS field TITLE01, PIC X(40): first screen title line. */
    @Size(max = 40)
    private String title01;

    /** BMS field CURDATE, PIC X(8): current date displayed on the screen. */
    @Size(max = 8)
    private String curdate;

    /** BMS field PGMNAME, PIC X(8): current program name shown on the screen. */
    @Size(max = 8)
    private String pgmname;

    /** BMS field TITLE02, PIC X(40): second screen title line. */
    @Size(max = 40)
    private String title02;

    /** BMS field CURTIME, PIC X(8): current time displayed on the screen. */
    @Size(max = 8)
    private String curtime;

    /** BMS field OPTN001, PIC X(40): admin menu option line 1. */
    @Size(max = 40)
    private String optn001;

    /** BMS field OPTN002, PIC X(40): admin menu option line 2. */
    @Size(max = 40)
    private String optn002;

    /** BMS field OPTN003, PIC X(40): admin menu option line 3. */
    @Size(max = 40)
    private String optn003;

    /** BMS field OPTN004, PIC X(40): admin menu option line 4. */
    @Size(max = 40)
    private String optn004;

    /** BMS field OPTN005, PIC X(40): admin menu option line 5. */
    @Size(max = 40)
    private String optn005;

    /** BMS field OPTN006, PIC X(40): admin menu option line 6. */
    @Size(max = 40)
    private String optn006;

    /** BMS field OPTN007, PIC X(40): admin menu option line 7. */
    @Size(max = 40)
    private String optn007;

    /** BMS field OPTN008, PIC X(40): admin menu option line 8. */
    @Size(max = 40)
    private String optn008;

    /** BMS field OPTN009, PIC X(40): admin menu option line 9. */
    @Size(max = 40)
    private String optn009;

    /** BMS field OPTN010, PIC X(40): admin menu option line 10. */
    @Size(max = 40)
    private String optn010;

    /** BMS field OPTN011, PIC X(40): admin menu option line 11. */
    @Size(max = 40)
    private String optn011;

    /** BMS field OPTN012, PIC X(40): admin menu option line 12. */
    @Size(max = 40)
    private String optn012;

    /** BMS field OPTION, PIC X(2): the option code entered by the user. */
    @Size(max = 2)
    private String option;

    /** BMS field ERRMSG, PIC X(78): error / informational message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty admin-menu screen form. Required for framework
     * instantiation and data binding.
     */
    public COADM01Form() {
        // No-argument constructor for bean instantiation and form binding.
    }

    /**
     * Returns the {@code TRNNAME} screen field value.
     *
     * @return the transaction identifier, or {@code null} if unset
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the {@code TRNNAME} screen field value.
     *
     * @param trnname the transaction identifier to set
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the {@code TITLE01} screen field value.
     *
     * @return the first title line, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the {@code TITLE01} screen field value.
     *
     * @param title01 the first title line to set
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the {@code CURDATE} screen field value.
     *
     * @return the current date text, or {@code null} if unset
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the {@code CURDATE} screen field value.
     *
     * @param curdate the current date text to set
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the {@code PGMNAME} screen field value.
     *
     * @return the current program name, or {@code null} if unset
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the {@code PGMNAME} screen field value.
     *
     * @param pgmname the current program name to set
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the {@code TITLE02} screen field value.
     *
     * @return the second title line, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the {@code TITLE02} screen field value.
     *
     * @param title02 the second title line to set
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the {@code CURTIME} screen field value.
     *
     * @return the current time text, or {@code null} if unset
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the {@code CURTIME} screen field value.
     *
     * @param curtime the current time text to set
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the {@code OPTN001} screen field value.
     *
     * @return admin menu option line 1, or {@code null} if unset
     */
    public String getOptn001() {
        return optn001;
    }

    /**
     * Sets the {@code OPTN001} screen field value.
     *
     * @param optn001 admin menu option line 1 to set
     */
    public void setOptn001(String optn001) {
        this.optn001 = optn001;
    }

    /**
     * Returns the {@code OPTN002} screen field value.
     *
     * @return admin menu option line 2, or {@code null} if unset
     */
    public String getOptn002() {
        return optn002;
    }

    /**
     * Sets the {@code OPTN002} screen field value.
     *
     * @param optn002 admin menu option line 2 to set
     */
    public void setOptn002(String optn002) {
        this.optn002 = optn002;
    }

    /**
     * Returns the {@code OPTN003} screen field value.
     *
     * @return admin menu option line 3, or {@code null} if unset
     */
    public String getOptn003() {
        return optn003;
    }

    /**
     * Sets the {@code OPTN003} screen field value.
     *
     * @param optn003 admin menu option line 3 to set
     */
    public void setOptn003(String optn003) {
        this.optn003 = optn003;
    }

    /**
     * Returns the {@code OPTN004} screen field value.
     *
     * @return admin menu option line 4, or {@code null} if unset
     */
    public String getOptn004() {
        return optn004;
    }

    /**
     * Sets the {@code OPTN004} screen field value.
     *
     * @param optn004 admin menu option line 4 to set
     */
    public void setOptn004(String optn004) {
        this.optn004 = optn004;
    }

    /**
     * Returns the {@code OPTN005} screen field value.
     *
     * @return admin menu option line 5, or {@code null} if unset
     */
    public String getOptn005() {
        return optn005;
    }

    /**
     * Sets the {@code OPTN005} screen field value.
     *
     * @param optn005 admin menu option line 5 to set
     */
    public void setOptn005(String optn005) {
        this.optn005 = optn005;
    }

    /**
     * Returns the {@code OPTN006} screen field value.
     *
     * @return admin menu option line 6, or {@code null} if unset
     */
    public String getOptn006() {
        return optn006;
    }

    /**
     * Sets the {@code OPTN006} screen field value.
     *
     * @param optn006 admin menu option line 6 to set
     */
    public void setOptn006(String optn006) {
        this.optn006 = optn006;
    }

    /**
     * Returns the {@code OPTN007} screen field value.
     *
     * @return admin menu option line 7, or {@code null} if unset
     */
    public String getOptn007() {
        return optn007;
    }

    /**
     * Sets the {@code OPTN007} screen field value.
     *
     * @param optn007 admin menu option line 7 to set
     */
    public void setOptn007(String optn007) {
        this.optn007 = optn007;
    }

    /**
     * Returns the {@code OPTN008} screen field value.
     *
     * @return admin menu option line 8, or {@code null} if unset
     */
    public String getOptn008() {
        return optn008;
    }

    /**
     * Sets the {@code OPTN008} screen field value.
     *
     * @param optn008 admin menu option line 8 to set
     */
    public void setOptn008(String optn008) {
        this.optn008 = optn008;
    }

    /**
     * Returns the {@code OPTN009} screen field value.
     *
     * @return admin menu option line 9, or {@code null} if unset
     */
    public String getOptn009() {
        return optn009;
    }

    /**
     * Sets the {@code OPTN009} screen field value.
     *
     * @param optn009 admin menu option line 9 to set
     */
    public void setOptn009(String optn009) {
        this.optn009 = optn009;
    }

    /**
     * Returns the {@code OPTN010} screen field value.
     *
     * @return admin menu option line 10, or {@code null} if unset
     */
    public String getOptn010() {
        return optn010;
    }

    /**
     * Sets the {@code OPTN010} screen field value.
     *
     * @param optn010 admin menu option line 10 to set
     */
    public void setOptn010(String optn010) {
        this.optn010 = optn010;
    }

    /**
     * Returns the {@code OPTN011} screen field value.
     *
     * @return admin menu option line 11, or {@code null} if unset
     */
    public String getOptn011() {
        return optn011;
    }

    /**
     * Sets the {@code OPTN011} screen field value.
     *
     * @param optn011 admin menu option line 11 to set
     */
    public void setOptn011(String optn011) {
        this.optn011 = optn011;
    }

    /**
     * Returns the {@code OPTN012} screen field value.
     *
     * @return admin menu option line 12, or {@code null} if unset
     */
    public String getOptn012() {
        return optn012;
    }

    /**
     * Sets the {@code OPTN012} screen field value.
     *
     * @param optn012 admin menu option line 12 to set
     */
    public void setOptn012(String optn012) {
        this.optn012 = optn012;
    }

    /**
     * Returns the {@code OPTION} screen field value.
     *
     * @return the option code entered by the user, or {@code null} if unset
     */
    public String getOption() {
        return option;
    }

    /**
     * Sets the {@code OPTION} screen field value.
     *
     * @param option the option code to set
     */
    public void setOption(String option) {
        this.option = option;
    }

    /**
     * Returns the {@code ERRMSG} screen field value.
     *
     * @return the error / informational message, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the {@code ERRMSG} screen field value.
     *
     * @param errmsg the error / informational message to set
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * BMS {@code ERRMSGC} &mdash; the colour attribute of the {@link #errmsg} line, reproducing the
     * COBOL {@code MOVE DFHxxx TO ERRMSGC OF COADM1AO}. Holds the semantic 3270 colour token the
     * template uses to pick the message colour class: {@code "red"} (error; the BMS map default
     * {@code COLOR=RED}), {@code "green"} (COBOL {@code DFHGREEN}, the "coming soon" informational
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
     * Returns a string representation of this form containing every screen-value
     * field. This screen has no password field, so no sensitive data is exposed.
     *
     * @return a debug-friendly representation of all screen field values
     */
    @Override
    public String toString() {
        return "COADM01Form{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", optn001='" + optn001 + '\''
                + ", optn002='" + optn002 + '\''
                + ", optn003='" + optn003 + '\''
                + ", optn004='" + optn004 + '\''
                + ", optn005='" + optn005 + '\''
                + ", optn006='" + optn006 + '\''
                + ", optn007='" + optn007 + '\''
                + ", optn008='" + optn008 + '\''
                + ", optn009='" + optn009 + '\''
                + ", optn010='" + optn010 + '\''
                + ", optn011='" + optn011 + '\''
                + ", optn012='" + optn012 + '\''
                + ", option='" + option + '\''
                + ", errmsg='" + errmsg + '\''
                + '}';
    }
}
