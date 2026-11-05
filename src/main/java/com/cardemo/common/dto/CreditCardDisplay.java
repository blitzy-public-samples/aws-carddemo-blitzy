package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Credit card display working area DTO — translated from CVCRD01Y.cpy (CC-WORK-AREAS).
 *
 * <p>This class faithfully maps the non-REDEFINES, non-88-level, non-commented fields
 * from the COBOL copybook {@code CVCRD01Y.cpy} CC-WORK-AREA group. It captures the
 * credit card working area fields used by online CICS programs for card
 * display and navigation operations.</p>
 *
 * <h3>COBOL Source Mapping (CVCRD01Y.cpy):</h3>
 * <pre>
 *   01  CC-WORK-AREAS.
 *      05 CC-WORK-AREA.
 *         10 CCARD-AID              PIC X(5)    → ccardAid
 *         10 CCARD-NEXT-PROG        PIC X(8)    → ccardNextProg
 *         10 CCARD-NEXT-MAPSET      PIC X(7)    → ccardNextMapset
 *         10 CCARD-NEXT-MAP         PIC X(7)    → ccardNextMap
 *         10 CCARD-ERROR-MSG        PIC X(75)   → ccardErrorMsg
 *         10 CCARD-RETURN-MSG       PIC X(75)   → ccardReturnMsg
 *         10 CC-ACCT-ID             PIC X(11)   → ccAcctId
 *         10 CC-CARD-NUM            PIC X(16)   → ccCardNum
 *         10 CC-CUST-ID             PIC X(09)   → ccCustId
 * </pre>
 *
 * <p>REDEFINES fields (CC-ACCT-ID-N, CC-CARD-NUM-N, CC-CUST-ID-N) are not mapped
 * separately — they represent numeric reinterpretation of the same storage in COBOL
 * and are handled as String fields in Java.</p>
 *
 * <p>88-level conditions on CCARD-AID (ENTER, CLEAR, PA1, PA2, PFK01–PFK12) are not
 * separate fields — they are condition checks translated to constants or utility methods
 * elsewhere (e.g., {@code AttributeUtil}).</p>
 *
 * <p>Commented-out fields (CCARD-LAST-PROG, CCARD-RETURN-TO-PROG, CCARD-RETURN-FLAG,
 * CCARD-FUNCTION) are not mapped since they are commented in the COBOL source.</p>
 */
public class CreditCardDisplay {

    // -----------------------------------------------------------------------
    // Fields — 9 fields mapped from CVCRD01Y.cpy CC-WORK-AREA group
    // -----------------------------------------------------------------------

    /**
     * Attention identifier key pressed by the user.
     * Maps to COBOL: {@code CCARD-AID PIC X(5)}.
     * Valid values include ENTER, CLEAR, PA1, PA2, PFK01–PFK12.
     */
    @Size(max = 5)
    private String ccardAid;

    /**
     * Next program name for XCTL transfer.
     * Maps to COBOL: {@code CCARD-NEXT-PROG PIC X(8)}.
     */
    @Size(max = 8)
    private String ccardNextProg;

    /**
     * Next mapset name for screen navigation.
     * Maps to COBOL: {@code CCARD-NEXT-MAPSET PIC X(7)}.
     */
    @Size(max = 7)
    private String ccardNextMapset;

    /**
     * Next map name for screen navigation.
     * Maps to COBOL: {@code CCARD-NEXT-MAP PIC X(7)}.
     */
    @Size(max = 7)
    private String ccardNextMap;

    /**
     * Error message displayed to the user.
     * Maps to COBOL: {@code CCARD-ERROR-MSG PIC X(75)}.
     */
    @Size(max = 75)
    private String ccardErrorMsg;

    /**
     * Return message displayed to the user.
     * Maps to COBOL: {@code CCARD-RETURN-MSG PIC X(75)}.
     */
    @Size(max = 75)
    private String ccardReturnMsg;

    /**
     * Account identifier.
     * Maps to COBOL: {@code CC-ACCT-ID PIC X(11) VALUE SPACES}.
     * REDEFINES field CC-ACCT-ID-N (PIC 9(11)) is not mapped separately.
     */
    @Size(max = 11)
    private String ccAcctId;

    /**
     * Credit card number — primary reference field for identity.
     * Maps to COBOL: {@code CC-CARD-NUM PIC X(16) VALUE SPACES}.
     * REDEFINES field CC-CARD-NUM-N (PIC 9(16)) is not mapped separately.
     */
    @Size(max = 16)
    private String ccCardNum;

    /**
     * Customer identifier.
     * Maps to COBOL: {@code CC-CUST-ID PIC X(09) VALUE SPACES}.
     * REDEFINES field CC-CUST-ID-N (PIC 9(9)) is not mapped separately.
     */
    @Size(max = 9)
    private String ccCustId;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default no-argument constructor.
     */
    public CreditCardDisplay() {
        // Default constructor for framework and serialization compatibility
    }

    /**
     * All-arguments constructor initializing every field from the CC-WORK-AREA group.
     *
     * @param ccardAid        attention identifier (max 5 chars)
     * @param ccardNextProg   next program name (max 8 chars)
     * @param ccardNextMapset next mapset name (max 7 chars)
     * @param ccardNextMap    next map name (max 7 chars)
     * @param ccardErrorMsg   error message (max 75 chars)
     * @param ccardReturnMsg  return message (max 75 chars)
     * @param ccAcctId        account ID (max 11 chars)
     * @param ccCardNum       card number (max 16 chars)
     * @param ccCustId        customer ID (max 9 chars)
     */
    public CreditCardDisplay(String ccardAid,
                             String ccardNextProg,
                             String ccardNextMapset,
                             String ccardNextMap,
                             String ccardErrorMsg,
                             String ccardReturnMsg,
                             String ccAcctId,
                             String ccCardNum,
                             String ccCustId) {
        this.ccardAid = ccardAid;
        this.ccardNextProg = ccardNextProg;
        this.ccardNextMapset = ccardNextMapset;
        this.ccardNextMap = ccardNextMap;
        this.ccardErrorMsg = ccardErrorMsg;
        this.ccardReturnMsg = ccardReturnMsg;
        this.ccAcctId = ccAcctId;
        this.ccCardNum = ccCardNum;
        this.ccCustId = ccCustId;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the attention identifier.
     *
     * @return attention identifier (ENTER, CLEAR, PA1, PA2, PFK01–PFK12)
     */
    public String getCcardAid() {
        return ccardAid;
    }

    /**
     * Sets the attention identifier.
     *
     * @param ccardAid attention identifier (max 5 chars)
     */
    public void setCcardAid(String ccardAid) {
        this.ccardAid = ccardAid;
    }

    /**
     * Returns the next program name for XCTL transfer.
     *
     * @return next program name
     */
    public String getCcardNextProg() {
        return ccardNextProg;
    }

    /**
     * Sets the next program name for XCTL transfer.
     *
     * @param ccardNextProg next program name (max 8 chars)
     */
    public void setCcardNextProg(String ccardNextProg) {
        this.ccardNextProg = ccardNextProg;
    }

    /**
     * Returns the next mapset name.
     *
     * @return next mapset name
     */
    public String getCcardNextMapset() {
        return ccardNextMapset;
    }

    /**
     * Sets the next mapset name.
     *
     * @param ccardNextMapset next mapset name (max 7 chars)
     */
    public void setCcardNextMapset(String ccardNextMapset) {
        this.ccardNextMapset = ccardNextMapset;
    }

    /**
     * Returns the next map name.
     *
     * @return next map name
     */
    public String getCcardNextMap() {
        return ccardNextMap;
    }

    /**
     * Sets the next map name.
     *
     * @param ccardNextMap next map name (max 7 chars)
     */
    public void setCcardNextMap(String ccardNextMap) {
        this.ccardNextMap = ccardNextMap;
    }

    /**
     * Returns the error message.
     *
     * @return error message
     */
    public String getCcardErrorMsg() {
        return ccardErrorMsg;
    }

    /**
     * Sets the error message.
     *
     * @param ccardErrorMsg error message (max 75 chars)
     */
    public void setCcardErrorMsg(String ccardErrorMsg) {
        this.ccardErrorMsg = ccardErrorMsg;
    }

    /**
     * Returns the return message.
     *
     * @return return message
     */
    public String getCcardReturnMsg() {
        return ccardReturnMsg;
    }

    /**
     * Sets the return message.
     *
     * @param ccardReturnMsg return message (max 75 chars)
     */
    public void setCcardReturnMsg(String ccardReturnMsg) {
        this.ccardReturnMsg = ccardReturnMsg;
    }

    /**
     * Returns the account identifier.
     *
     * @return account ID
     */
    public String getCcAcctId() {
        return ccAcctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param ccAcctId account ID (max 11 chars)
     */
    public void setCcAcctId(String ccAcctId) {
        this.ccAcctId = ccAcctId;
    }

    /**
     * Returns the credit card number.
     *
     * @return card number
     */
    public String getCcCardNum() {
        return ccCardNum;
    }

    /**
     * Sets the credit card number.
     *
     * @param ccCardNum card number (max 16 chars)
     */
    public void setCcCardNum(String ccCardNum) {
        this.ccCardNum = ccCardNum;
    }

    /**
     * Returns the customer identifier.
     *
     * @return customer ID
     */
    public String getCcCustId() {
        return ccCustId;
    }

    /**
     * Sets the customer identifier.
     *
     * @param ccCustId customer ID (max 9 chars)
     */
    public void setCcCustId(String ccCustId) {
        this.ccCustId = ccCustId;
    }

    // -----------------------------------------------------------------------
    // Object overrides
    // -----------------------------------------------------------------------

    /**
     * Returns a string representation of all fields in this credit card display record.
     *
     * @return formatted string with all 9 field values
     */
    @Override
    public String toString() {
        return "CreditCardDisplay{"
                + "ccardAid='" + ccardAid + '\''
                + ", ccardNextProg='" + ccardNextProg + '\''
                + ", ccardNextMapset='" + ccardNextMapset + '\''
                + ", ccardNextMap='" + ccardNextMap + '\''
                + ", ccardErrorMsg='" + ccardErrorMsg + '\''
                + ", ccardReturnMsg='" + ccardReturnMsg + '\''
                + ", ccAcctId='" + ccAcctId + '\''
                + ", ccCardNum='" + ccCardNum + '\''
                + ", ccCustId='" + ccCustId + '\''
                + '}';
    }

    /**
     * Checks equality based on the {@code ccCardNum} field, which serves as
     * the primary reference identifier for credit card display records
     * (maps to COBOL {@code CC-CARD-NUM PIC X(16)}).
     *
     * @param obj the object to compare
     * @return {@code true} if both objects have the same ccCardNum value
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CreditCardDisplay other)) {
            return false;
        }
        return Objects.equals(ccCardNum, other.ccCardNum);
    }

    /**
     * Computes hash code based on the {@code ccCardNum} field, consistent
     * with the {@link #equals(Object)} contract.
     *
     * @return hash code derived from ccCardNum
     */
    @Override
    public int hashCode() {
        return Objects.hash(ccCardNum);
    }
}
