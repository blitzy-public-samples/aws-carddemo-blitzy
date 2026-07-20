package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Bill payment screen form.
 *
 * <p>Origin: legacy/cpy-bms/COBIL00.CPY (BMS mapset COBIL00, map COBIL0A).</p>
 *
 * <p>Form-backing bean for the AWS CardDemo bill-payment screen (CICS transaction
 * {@code CB00}, program {@code COBIL00C}). This is a faithful translation of the BMS
 * symbolic map copybook into a plain-Java DTO, preserving the 24x80 BMS
 * field/label/length contract. Each property corresponds to exactly one BMS value
 * field ({@code <name>I} on the input map / {@code <name>O} on the output map); the
 * BMS attribute and plumbing bytes ({@code L}/{@code F}/{@code A}/{@code C}/{@code P}/
 * {@code H}/{@code V}) are intentionally not modelled. Every property is a
 * {@link String} sized to the exact BMS {@code PIC X(n)} length, and monetary values
 * are held as display-formatted strings rather than numeric types so that the
 * on-screen presentation is preserved byte-for-byte.</p>
 */
public class COBIL00Form {

    /** BMS field {@code TRNNAME} ({@code PIC X(4)}): transaction-name header label. */
    @Size(max = 4)
    private String trnname;

    /** BMS field {@code TITLE01} ({@code PIC X(40)}): first title header line. */
    @Size(max = 40)
    private String title01;

    /** BMS field {@code CURDATE} ({@code PIC X(8)}): current-date header text. */
    @Size(max = 8)
    private String curdate;

    /** BMS field {@code PGMNAME} ({@code PIC X(8)}): program-name header label. */
    @Size(max = 8)
    private String pgmname;

    /** BMS field {@code TITLE02} ({@code PIC X(40)}): second title header line. */
    @Size(max = 40)
    private String title02;

    /** BMS field {@code CURTIME} ({@code PIC X(8)}): current-time header text. */
    @Size(max = 8)
    private String curtime;

    /** BMS field {@code ACTIDIN} ({@code PIC X(11)}): account-id entry field. */
    @Size(max = 11)
    private String actidin;

    /**
     * BMS field {@code CURBAL} ({@code PIC X(14)}): display-formatted current balance.
     *
     * <p>Held as a {@link String} display amount and never as {@code BigDecimal},
     * {@code double} or {@code float}. The authoritative balance lives in the
     * {@code domain} entities; the controller formats it into this display string when
     * populating the form and parses it back on submit.</p>
     */
    @Size(max = 14)
    private String curbal;

    /** BMS field {@code CONFIRM} ({@code PIC X(1)}): payment confirmation input (Y/N). */
    @Size(max = 1)
    private String confirm;

    /** BMS field {@code ERRMSG} ({@code PIC X(78)}): error / status message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Single-use confirmation nonce (review finding F12). No BMS origin: this hidden field carries
     * the server-armed token that binds a {@code confirm=Y} payment to the account whose balance the
     * server displayed, so a tampered re-post cannot re-aim or replay the payment. Populated by the
     * controller on the confirm-prompt render and echoed back on the confirming submit; validated
     * and consumed server-side by {@link com.aws.carddemo.web.support.ConfirmationTokenService}.
     */
    @Size(max = 64)
    private String confirmToken;

    /** Creates an empty bill-payment screen form. */
    public COBIL00Form() {
    }

    /**
     * Returns the transaction-name header ({@code TRNNAME}).
     *
     * @return the transaction-name header text, or {@code null} if unset
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets the transaction-name header ({@code TRNNAME}).
     *
     * @param trnname the transaction-name header text
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * Returns the first title header line ({@code TITLE01}).
     *
     * @return the first title header line, or {@code null} if unset
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first title header line ({@code TITLE01}).
     *
     * @param title01 the first title header line
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns the current-date header ({@code CURDATE}).
     *
     * @return the current-date header text, or {@code null} if unset
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets the current-date header ({@code CURDATE}).
     *
     * @param curdate the current-date header text
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * Returns the program-name header ({@code PGMNAME}).
     *
     * @return the program-name header text, or {@code null} if unset
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets the program-name header ({@code PGMNAME}).
     *
     * @param pgmname the program-name header text
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * Returns the second title header line ({@code TITLE02}).
     *
     * @return the second title header line, or {@code null} if unset
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second title header line ({@code TITLE02}).
     *
     * @param title02 the second title header line
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns the current-time header ({@code CURTIME}).
     *
     * @return the current-time header text, or {@code null} if unset
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets the current-time header ({@code CURTIME}).
     *
     * @param curtime the current-time header text
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * Returns the account-id entry ({@code ACTIDIN}).
     *
     * @return the account-id entry text, or {@code null} if unset
     */
    public String getActidin() {
        return actidin;
    }

    /**
     * Sets the account-id entry ({@code ACTIDIN}).
     *
     * @param actidin the account-id entry text
     */
    public void setActidin(String actidin) {
        this.actidin = actidin;
    }

    /**
     * Returns the display-formatted current balance ({@code CURBAL}).
     *
     * @return the current-balance display string, or {@code null} if unset
     */
    public String getCurbal() {
        return curbal;
    }

    /**
     * Sets the display-formatted current balance ({@code CURBAL}).
     *
     * @param curbal the current-balance display string
     */
    public void setCurbal(String curbal) {
        this.curbal = curbal;
    }

    /**
     * Returns the payment confirmation input ({@code CONFIRM}).
     *
     * @return the confirmation input (Y/N), or {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the payment confirmation input ({@code CONFIRM}).
     *
     * @param confirm the confirmation input (Y/N)
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns the error / status message line ({@code ERRMSG}).
     *
     * @return the error / status message line, or {@code null} if unset
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error / status message line ({@code ERRMSG}).
     *
     * @param errmsg the error / status message line
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns the single-use confirmation nonce (review finding F12), or {@code null} if unset.
     *
     * @return the confirmation nonce (max 64 characters)
     */
    public String getConfirmToken() {
        return confirmToken;
    }

    /**
     * Sets the single-use confirmation nonce (review finding F12).
     *
     * @param confirmToken the confirmation nonce (max 64 characters)
     */
    public void setConfirmToken(String confirmToken) {
        this.confirmToken = confirmToken;
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
        return "COBIL00Form@" + Integer.toHexString(System.identityHashCode(this));
    }
}
