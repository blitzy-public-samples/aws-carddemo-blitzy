package com.aws.carddemo.dto;

/**
 * Per-request scratch / work-area holder for the CardDemo card and account online flows.
 *
 * <p>Origin: legacy/cpy/CVCRD01Y.cpy (CC-WORK-AREAS). This plain Java DTO is a faithful
 * translation of the COBOL working-storage copybook {@code CVCRD01Y} (group
 * {@code CC-WORK-AREAS}), which is {@code COPY}'d by the card / account online programs
 * {@code COACTVWC}, {@code COACTUPC}, {@code COCRDLIC}, {@code COCRDUPC} and
 * {@code COCRDSLC}. On the mainframe it held the transient state a CICS
 * pseudo-conversational program needed between screen interactions: the attention
 * identifier (AID / PF-key) the operator pressed, the next program / mapset / map to
 * route to, the error and return message buffers, and the account / card / customer
 * identifier currently being worked on.</p>
 *
 * <p>In the Spring Boot migration this class replaces the CICS card/account work area.
 * It carries no persistent state (it is not a JPA entity) and no monetary fields, so it
 * declares no {@code BigDecimal} columns and never uses floating-point types; it is a
 * mutable holder populated per request. Every {@code PIC X(n)} item is modelled as a
 * {@link String} to preserve the original display / trailing-space semantics, and the
 * three identifier fields additionally expose numeric-view accessors that mirror the
 * COBOL {@code REDEFINES ... PIC 9(n)} clauses.</p>
 *
 * <p>The nested {@link PfKey} enumeration reproduces the {@code CCARD-AID} condition-name
 * (88-level) values. It is intentionally owned by this DTO so that the sibling utility
 * {@code com.aws.carddemo.util.PfKeyHandler} can resolve it as
 * {@code com.aws.carddemo.dto.CardWorkArea.PfKey}. The constant set, names and ordering
 * are a published cross-package contract; see {@link PfKey}.</p>
 *
 * <p>Note: several {@code 10}-level items in the copybook
 * ({@code CCARD-LAST-PROG}, {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG},
 * {@code CCARD-FUNCTION}) are commented out in the source and are deliberately not
 * translated into fields here.</p>
 */
public class CardWorkArea {

    /**
     * Attention-identifier / program-function key tokens.
     *
     * <p>Reproduces the {@code CCARD-AID} 88-level condition names from
     * {@code CVCRD01Y.cpy}: {@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2}
     * and {@code PFK01}..{@code PFK12}, plus a synthetic {@link #OTHER} value used when
     * the raw AID token is unset or unrecognized.</p>
     *
     * <p>The constant order and names are a published cross-package contract consumed by
     * {@code com.aws.carddemo.util.PfKeyHandler}: do not reorder, add or rename the
     * constants and keep {@link #OTHER} last.</p>
     */
    public enum PfKey {

        /** ENTER key (COBOL {@code CCARD-AID-ENTER VALUE 'ENTER'}). */
        ENTER,
        /** CLEAR key (COBOL {@code CCARD-AID-CLEAR VALUE 'CLEAR'}). */
        CLEAR,
        /** PA1 key (COBOL {@code CCARD-AID-PA1 VALUE 'PA1  '}, blank-padded to 5). */
        PA1,
        /** PA2 key (COBOL {@code CCARD-AID-PA2 VALUE 'PA2  '}, blank-padded to 5). */
        PA2,
        /** PF01 key (COBOL {@code CCARD-AID-PFK01 VALUE 'PFK01'}). */
        PFK01,
        /** PF02 key (COBOL {@code CCARD-AID-PFK02 VALUE 'PFK02'}). */
        PFK02,
        /** PF03 key (COBOL {@code CCARD-AID-PFK03 VALUE 'PFK03'}). */
        PFK03,
        /** PF04 key (COBOL {@code CCARD-AID-PFK04 VALUE 'PFK04'}). */
        PFK04,
        /** PF05 key (COBOL {@code CCARD-AID-PFK05 VALUE 'PFK05'}). */
        PFK05,
        /** PF06 key (COBOL {@code CCARD-AID-PFK06 VALUE 'PFK06'}). */
        PFK06,
        /** PF07 key (COBOL {@code CCARD-AID-PFK07 VALUE 'PFK07'}). */
        PFK07,
        /** PF08 key (COBOL {@code CCARD-AID-PFK08 VALUE 'PFK08'}). */
        PFK08,
        /** PF09 key (COBOL {@code CCARD-AID-PFK09 VALUE 'PFK09'}). */
        PFK09,
        /** PF10 key (COBOL {@code CCARD-AID-PFK10 VALUE 'PFK10'}). */
        PFK10,
        /** PF11 key (COBOL {@code CCARD-AID-PFK11 VALUE 'PFK11'}). */
        PFK11,
        /** PF12 key (COBOL {@code CCARD-AID-PFK12 VALUE 'PFK12'}). */
        PFK12,
        /** Unknown, unset or unmapped AID token (synthetic; has no COBOL 88-level). */
        OTHER;

        /**
         * Maps a raw {@code CCARD-AID} token to the matching {@link PfKey} constant.
         *
         * <p>The lookup trims surrounding blanks first, because the copybook stores the
         * PA-key tokens blank-padded to five characters ({@code 'PA1  '} / {@code 'PA2  '}).
         * A {@code null}, blank or unrecognized token yields {@link #OTHER}; this method
         * never throws, mirroring the COBOL behavior where an unmatched AID simply leaves
         * every 88-level condition {@code false}.</p>
         *
         * @param aid the raw AID token (may be {@code null} or blank-padded)
         * @return the matching {@link PfKey}, or {@link #OTHER} when the token is
         *         {@code null}, blank or unmapped
         */
        public static PfKey fromAid(String aid) {
            if (aid == null) {
                return OTHER;
            }
            String token = aid.trim();
            if (token.isEmpty()) {
                return OTHER;
            }
            return switch (token) {
                case "ENTER" -> ENTER;
                case "CLEAR" -> CLEAR;
                case "PA1" -> PA1;
                case "PA2" -> PA2;
                case "PFK01" -> PFK01;
                case "PFK02" -> PFK02;
                case "PFK03" -> PFK03;
                case "PFK04" -> PFK04;
                case "PFK05" -> PFK05;
                case "PFK06" -> PFK06;
                case "PFK07" -> PFK07;
                case "PFK08" -> PFK08;
                case "PFK09" -> PFK09;
                case "PFK10" -> PFK10;
                case "PFK11" -> PFK11;
                case "PFK12" -> PFK12;
                default -> OTHER;
            };
        }
    }

    /** {@code CCARD-AID PIC X(5)} - raw attention-identifier token the operator pressed. */
    private String aid;

    /** {@code CCARD-NEXT-PROG PIC X(8)} - identifier of the next program to route to. */
    private String nextProg;

    /** {@code CCARD-NEXT-MAPSET PIC X(7)} - next BMS mapset to display. */
    private String nextMapset;

    /** {@code CCARD-NEXT-MAP PIC X(7)} - next BMS map to display. */
    private String nextMap;

    /** {@code CCARD-ERROR-MSG PIC X(75)} - error message buffer surfaced on the screen. */
    private String errorMsg;

    /**
     * {@code CCARD-RETURN-MSG PIC X(75)} - return message buffer.
     *
     * <p>The COBOL 88-level {@code CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} indicates the
     * "no message" state; here that maps to a {@code null} or blank value (see
     * {@link #isReturnMsgOff()}).</p>
     */
    private String returnMsg;

    /**
     * {@code CC-ACCT-ID PIC X(11) VALUE SPACES} - account id in display form.
     *
     * <p>Numeric view available via {@link #getAcctIdNumeric()}, mirroring the COBOL
     * {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)} redefinition.</p>
     */
    private String acctId;

    /**
     * {@code CC-CARD-NUM PIC X(16) VALUE SPACES} - card number in display form.
     *
     * <p>Numeric view available via {@link #getCardNumNumeric()}, mirroring the COBOL
     * {@code CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16)} redefinition.</p>
     */
    private String cardNum;

    /**
     * {@code CC-CUST-ID PIC X(09) VALUE SPACES} - customer id in display form.
     *
     * <p>Numeric view available via {@link #getCustIdNumeric()}, mirroring the COBOL
     * {@code CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)} redefinition.</p>
     */
    private String custId;

    /**
     * Creates an empty work area.
     *
     * <p>The three identifier fields ({@code acctId}, {@code cardNum}, {@code custId})
     * default to the empty string to mirror the COBOL {@code VALUE SPACES} initialization.
     * All other fields default to {@code null} (unset); for {@code returnMsg} an unset
     * value corresponds to the {@code CCARD-RETURN-MSG-OFF} / {@code LOW-VALUES}
     * condition.</p>
     */
    public CardWorkArea() {
        this.acctId = "";
        this.cardNum = "";
        this.custId = "";
    }

    /**
     * Returns the raw 5-character AID token ({@code CCARD-AID}).
     *
     * @return the raw AID token, or {@code null} if unset
     */
    public String getAid() {
        return aid;
    }

    /**
     * Sets the raw 5-character AID token ({@code CCARD-AID}).
     *
     * @param aid the raw AID token
     */
    public void setAid(String aid) {
        this.aid = aid;
    }

    /**
     * Returns the next program to route to ({@code CCARD-NEXT-PROG}).
     *
     * @return the next program name, or {@code null} if unset
     */
    public String getNextProg() {
        return nextProg;
    }

    /**
     * Sets the next program to route to ({@code CCARD-NEXT-PROG}).
     *
     * @param nextProg the next program name
     */
    public void setNextProg(String nextProg) {
        this.nextProg = nextProg;
    }

    /**
     * Returns the next BMS mapset ({@code CCARD-NEXT-MAPSET}).
     *
     * @return the next mapset name, or {@code null} if unset
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next BMS mapset ({@code CCARD-NEXT-MAPSET}).
     *
     * @param nextMapset the next mapset name
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = nextMapset;
    }

    /**
     * Returns the next BMS map ({@code CCARD-NEXT-MAP}).
     *
     * @return the next map name, or {@code null} if unset
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the next BMS map ({@code CCARD-NEXT-MAP}).
     *
     * @param nextMap the next map name
     */
    public void setNextMap(String nextMap) {
        this.nextMap = nextMap;
    }

    /**
     * Returns the error message buffer ({@code CCARD-ERROR-MSG}).
     *
     * @return the error message, or {@code null} if unset
     */
    public String getErrorMsg() {
        return errorMsg;
    }

    /**
     * Sets the error message buffer ({@code CCARD-ERROR-MSG}).
     *
     * @param errorMsg the error message
     */
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    /**
     * Returns the return message buffer ({@code CCARD-RETURN-MSG}).
     *
     * @return the return message, or {@code null} if unset
     */
    public String getReturnMsg() {
        return returnMsg;
    }

    /**
     * Sets the return message buffer ({@code CCARD-RETURN-MSG}).
     *
     * @param returnMsg the return message
     */
    public void setReturnMsg(String returnMsg) {
        this.returnMsg = returnMsg;
    }

    /**
     * Returns the account id in display form ({@code CC-ACCT-ID}).
     *
     * @return the account id string (never {@code null} unless explicitly set so)
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account id in display form ({@code CC-ACCT-ID}).
     *
     * @param acctId the account id string
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the card number in display form ({@code CC-CARD-NUM}).
     *
     * @return the card number string (never {@code null} unless explicitly set so)
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number in display form ({@code CC-CARD-NUM}).
     *
     * @param cardNum the card number string
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the customer id in display form ({@code CC-CUST-ID}).
     *
     * @return the customer id string (never {@code null} unless explicitly set so)
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Sets the customer id in display form ({@code CC-CUST-ID}).
     *
     * @param custId the customer id string
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Derives the {@link PfKey} corresponding to the current raw {@link #getAid() AID}
     * token, applying the same tolerant mapping as {@link PfKey#fromAid(String)}.
     *
     * @return the mapped {@link PfKey}, or {@link PfKey#OTHER} when the AID is unset or
     *         unmapped
     */
    public PfKey getPfKey() {
        return PfKey.fromAid(this.aid);
    }

    /**
     * Numeric view of {@link #getAcctId() acctId}, mirroring the COBOL
     * {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)} redefinition.
     *
     * @return the account id as a {@link Long}, or {@code null} when the display value is
     *         blank or not composed solely of digits
     */
    public Long getAcctIdNumeric() {
        return parseNumeric(this.acctId);
    }

    /**
     * Numeric view of {@link #getCardNum() cardNum}, mirroring the COBOL
     * {@code CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16)} redefinition.
     *
     * @return the card number as a {@link Long}, or {@code null} when the display value is
     *         blank or not composed solely of digits
     */
    public Long getCardNumNumeric() {
        return parseNumeric(this.cardNum);
    }

    /**
     * Numeric view of {@link #getCustId() custId}, mirroring the COBOL
     * {@code CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)} redefinition.
     *
     * @return the customer id as a {@link Long}, or {@code null} when the display value is
     *         blank or not composed solely of digits
     */
    public Long getCustIdNumeric() {
        return parseNumeric(this.custId);
    }

    /**
     * Indicates whether the return-message buffer is in the "off" state, mirroring the
     * COBOL 88-level {@code CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}. A {@code null} or
     * blank buffer is treated as off.
     *
     * @return {@code true} when no return message is set
     */
    public boolean isReturnMsgOff() {
        return returnMsg == null || returnMsg.isBlank();
    }

    /**
     * Parses a fixed-width display value into its numeric ({@code PIC 9(n)}) view.
     *
     * <p>A {@code null} or blank value yields {@code null}. The trimmed value is accepted
     * only when it is composed solely of ASCII digits ({@code '0'}..{@code '9'}), matching
     * COBOL {@code PIC 9} semantics; any other content yields {@code null}. Should an
     * all-digit value exceed the {@link Long} range it likewise yields {@code null} instead
     * of propagating an exception, honoring the "do not throw" contract.</p>
     *
     * @param value the display-form value to interpret
     * @return the numeric value as a {@link Long}, or {@code null} when blank / non-numeric
     */
    private static Long parseNumeric(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            // All-digit token wider than Long can represent: yield no numeric view rather
            // than propagate an exception (COBOL parity - the redefinition never "throws").
            return null;
        }
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token. The work area holds the selected card number ({@code cardNum}, an
     * unmasked PAN) alongside account and customer identifiers and screen messages; rendering those
     * into logs or error messages would leak payment/customer data (CWE-532), so no business field is
     * ever emitted here (review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "CardWorkArea@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Compares this work area to another for value equality across all scalar fields.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@code CardWorkArea} with equal fields
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CardWorkArea that = (CardWorkArea) o;
        return equalsField(aid, that.aid)
                && equalsField(nextProg, that.nextProg)
                && equalsField(nextMapset, that.nextMapset)
                && equalsField(nextMap, that.nextMap)
                && equalsField(errorMsg, that.errorMsg)
                && equalsField(returnMsg, that.returnMsg)
                && equalsField(acctId, that.acctId)
                && equalsField(cardNum, that.cardNum)
                && equalsField(custId, that.custId);
    }

    /**
     * Computes a hash consistent with {@link #equals(Object)} over all scalar fields.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + hashField(aid);
        result = 31 * result + hashField(nextProg);
        result = 31 * result + hashField(nextMapset);
        result = 31 * result + hashField(nextMap);
        result = 31 * result + hashField(errorMsg);
        result = 31 * result + hashField(returnMsg);
        result = 31 * result + hashField(acctId);
        result = 31 * result + hashField(cardNum);
        result = 31 * result + hashField(custId);
        return result;
    }

    /**
     * Null-safe equality helper for {@link String} fields (avoids importing
     * {@code java.util.Objects} so this DTO requires no imports).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code true} when both are {@code null} or are equal
     */
    private static boolean equalsField(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Null-safe hash helper for {@link String} fields (avoids importing
     * {@code java.util.Objects} so this DTO requires no imports).
     *
     * @param value the value to hash
     * @return {@code 0} when {@code null}, otherwise {@code value.hashCode()}
     */
    private static int hashField(String value) {
        return value == null ? 0 : value.hashCode();
    }
}
