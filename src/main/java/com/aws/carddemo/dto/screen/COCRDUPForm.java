package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Card-update screen form.
 *
 * <p>Form-backing bean for the card-update screen. It holds the BMS map's value
 * fields as bindable {@code String} bean properties, preserving the original 24x80
 * field-length contract: every property carries a {@link Size} upper bound equal to
 * its BMS {@code PIC X(n)} length so that server-side binding and validation reproduce
 * the legacy screen behavior. Attribute/plumbing bytes from the symbolic map (length,
 * flag, attribute, color/highlight/position/validation sub-fields) are intentionally
 * not modeled here; they are handled by the Thymeleaf template and the {@code util}
 * helpers.</p>
 *
 * <p>All monetary values on CardDemo screens are carried as {@code String} to preserve
 * the fixed-width character contract; this particular screen has no monetary fields.</p>
 *
 * <p>Origin: legacy/cpy-bms/COCRDUP.CPY (BMS mapset COCRDUP, map CCRDUPA)</p>
 */
public class COCRDUPForm {

    /**
     * Transaction name shown in the screen header. BMS symbolic {@code TRNNAMEI},
     * PIC X(4).
     */
    @Size(max = 4)
    private String trnname;

    /**
     * First title line shown in the screen header. BMS symbolic {@code TITLE01I},
     * PIC X(40).
     */
    @Size(max = 40)
    private String title01;

    /**
     * Current date shown in the screen header. BMS symbolic {@code CURDATEI},
     * PIC X(8).
     */
    @Size(max = 8)
    private String curdate;

    /**
     * Program name shown in the screen header. BMS symbolic {@code PGMNAMEI},
     * PIC X(8).
     */
    @Size(max = 8)
    private String pgmname;

    /**
     * Second title line shown in the screen header. BMS symbolic {@code TITLE02I},
     * PIC X(40).
     */
    @Size(max = 40)
    private String title02;

    /**
     * Current time shown in the screen header. BMS symbolic {@code CURTIMEI},
     * PIC X(8).
     */
    @Size(max = 8)
    private String curtime;

    /**
     * Account identifier entered on the screen. BMS symbolic {@code ACCTSIDI},
     * PIC X(11).
     */
    @Size(max = 11)
    private String acctsid;

    /**
     * Card number entered on the screen. BMS symbolic {@code CARDSIDI},
     * PIC X(16).
     */
    @Size(max = 16)
    private String cardsid;

    /**
     * Name embossed on the card. BMS symbolic {@code CRDNAMEI}, PIC X(50).
     */
    @Size(max = 50)
    private String crdname;

    /**
     * Card status code. BMS symbolic {@code CRDSTCDI}, PIC X(1).
     */
    @Size(max = 1)
    private String crdstcd;

    /**
     * Card expiry month. BMS symbolic {@code EXPMONI}, PIC X(2).
     */
    @Size(max = 2)
    private String expmon;

    /**
     * Card expiry year. BMS symbolic {@code EXPYEARI}, PIC X(4).
     */
    @Size(max = 4)
    private String expyear;

    /**
     * Card expiry day (present on the update screen, not on the view screen).
     * BMS symbolic {@code EXPDAYI}, PIC X(2).
     */
    @Size(max = 2)
    private String expday;

    /**
     * Informational message line. BMS symbolic {@code INFOMSGI}, PIC X(40).
     */
    @Size(max = 40)
    private String infomsg;

    /**
     * Error message line. BMS symbolic {@code ERRMSGI}, PIC X(80). Note this screen
     * uses an 80-byte error field, wider than the 78-byte variant found on some other
     * CardDemo screens.
     */
    @Size(max = 80)
    private String errmsg;

    /**
     * PF-key legend, part one. BMS symbolic {@code FKEYSI}, PIC X(21).
     */
    @Size(max = 21)
    private String fkeys;

    /**
     * PF-key legend, part two. BMS symbolic {@code FKEYSCI}, PIC X(18).
     */
    @Size(max = 18)
    private String fkeysc;

    /**
     * Creates an empty {@code COCRDUPForm} with all fields unset.
     */
    public COCRDUPForm() {
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
     * @return the first title line value
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets the first header title line (BMS {@code TITLE01I}).
     *
     * @param title01 the first title line value to set
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
     * @return the second title line value
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets the second header title line (BMS {@code TITLE02I}).
     *
     * @param title02 the second title line value to set
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
     * Returns the card number (BMS {@code CARDSIDI}).
     *
     * @return the card number value
     */
    public String getCardsid() {
        return cardsid;
    }

    /**
     * Sets the card number (BMS {@code CARDSIDI}).
     *
     * @param cardsid the card number value to set
     */
    public void setCardsid(String cardsid) {
        this.cardsid = cardsid;
    }

    /**
     * Returns the name embossed on the card (BMS {@code CRDNAMEI}).
     *
     * @return the name-on-card value
     */
    public String getCrdname() {
        return crdname;
    }

    /**
     * Sets the name embossed on the card (BMS {@code CRDNAMEI}).
     *
     * @param crdname the name-on-card value to set
     */
    public void setCrdname(String crdname) {
        this.crdname = crdname;
    }


    /**
     * Returns the card status code (BMS {@code CRDSTCDI}).
     *
     * @return the card status code value
     */
    public String getCrdstcd() {
        return crdstcd;
    }

    /**
     * Sets the card status code (BMS {@code CRDSTCDI}).
     *
     * @param crdstcd the card status code value to set
     */
    public void setCrdstcd(String crdstcd) {
        this.crdstcd = crdstcd;
    }

    /**
     * Returns the card expiry month (BMS {@code EXPMONI}).
     *
     * @return the expiry month value
     */
    public String getExpmon() {
        return expmon;
    }

    /**
     * Sets the card expiry month (BMS {@code EXPMONI}).
     *
     * @param expmon the expiry month value to set
     */
    public void setExpmon(String expmon) {
        this.expmon = expmon;
    }

    /**
     * Returns the card expiry year (BMS {@code EXPYEARI}).
     *
     * @return the expiry year value
     */
    public String getExpyear() {
        return expyear;
    }

    /**
     * Sets the card expiry year (BMS {@code EXPYEARI}).
     *
     * @param expyear the expiry year value to set
     */
    public void setExpyear(String expyear) {
        this.expyear = expyear;
    }

    /**
     * Returns the card expiry day (BMS {@code EXPDAYI}).
     *
     * @return the expiry day value
     */
    public String getExpday() {
        return expday;
    }

    /**
     * Sets the card expiry day (BMS {@code EXPDAYI}).
     *
     * @param expday the expiry day value to set
     */
    public void setExpday(String expday) {
        this.expday = expday;
    }

    /**
     * Returns the informational message line (BMS {@code INFOMSGI}).
     *
     * @return the informational message value
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * Sets the informational message line (BMS {@code INFOMSGI}).
     *
     * @param infomsg the informational message value to set
     */
    public void setInfomsg(String infomsg) {
        this.infomsg = infomsg;
    }

    /**
     * Returns the error message line (BMS {@code ERRMSGI}).
     *
     * @return the error message value
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets the error message line (BMS {@code ERRMSGI}).
     *
     * @param errmsg the error message value to set
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * Returns the first part of the PF-key legend (BMS {@code FKEYSI}).
     *
     * @return the PF-key legend part-one value
     */
    public String getFkeys() {
        return fkeys;
    }

    /**
     * Sets the first part of the PF-key legend (BMS {@code FKEYSI}).
     *
     * @param fkeys the PF-key legend part-one value to set
     */
    public void setFkeys(String fkeys) {
        this.fkeys = fkeys;
    }

    /**
     * Returns the second part of the PF-key legend (BMS {@code FKEYSCI}).
     *
     * @return the PF-key legend part-two value
     */
    public String getFkeysc() {
        return fkeysc;
    }

    /**
     * Sets the second part of the PF-key legend (BMS {@code FKEYSCI}).
     *
     * @param fkeysc the PF-key legend part-two value to set
     */
    public void setFkeysc(String fkeysc) {
        this.fkeysc = fkeysc;
    }

    /**
     * Returns a string representation listing every screen field value. This form has
     * no password field, so all values are safe to include.
     *
     * @return a string representation of this form
     */
    @Override
    public String toString() {
        return "COCRDUPForm{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", acctsid='" + acctsid + '\''
                + ", cardsid='" + cardsid + '\''
                + ", crdname='" + crdname + '\''
                + ", crdstcd='" + crdstcd + '\''
                + ", expmon='" + expmon + '\''
                + ", expyear='" + expyear + '\''
                + ", expday='" + expday + '\''
                + ", infomsg='" + infomsg + '\''
                + ", errmsg='" + errmsg + '\''
                + ", fkeys='" + fkeys + '\''
                + ", fkeysc='" + fkeysc + '\''
                + '}';
    }
}

