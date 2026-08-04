package com.carddemo.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The four reject reasons the CardDemo batch posting program assigns, and the closed set a
 * {@code TransactionDeclined} event may carry.
 *
 * <p>Provenance is one program. {@code app/cbl/CBTRN02C.cbl} holds the only validate-and-authorize
 * logic in the CardDemo source, in paragraphs {@code 1500-A-LOOKUP-XREF} and
 * {@code 1500-B-LOOKUP-ACCT}. Each constant below reproduces one reason code and the text the
 * source writes beside it.
 *
 * <p>Two source fields fix the two widths. {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code app/cbl/CBTRN02C.cbl:L181} gives the code four digits, so {@link #code()} returns four
 * zero-padded characters. {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code app/cbl/CBTRN02C.cbl:L182} caps the text at seventy-six characters, and the longest of
 * the four texts holds forty-two. The batch job writes both fields into the eighty-byte trailer of
 * a four-hundred-and-thirty-byte reject record, allocated at {@code app/jcl/POSTTRAN.jcl:L36}.
 *
 * <p>The padded code is the wire form. {@link #code()} carries the annotation that serializes a
 * constant as {@code "0102"}, matching the {@code declineReasonCode} values in
 * {@code schemas/transaction-declined-v1.json}. {@link #fromCode(String)} reads that form back and
 * rejects every other value, exactly as the schema document does.
 *
 * <p>One declined transaction carries one reason. The source checks the card cross-reference first
 * and reads the account only while the code is still zero, gated at
 * {@code app/cbl/CBTRN02C.cbl:L372}. A card that fails lookup therefore reports
 * {@link #INVALID_CARD_NUMBER} and never {@link #ACCOUNT_NOT_FOUND}. No gate separates the
 * credit-limit test from the expiration test, so a record failing both reports
 * {@link #ACCOUNT_EXPIRED}.
 *
 * <p>A decline is expected traffic, not a failure. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the
 * batch job with return code 4 when any record was rejected. The authorization service answers the
 * caller with the reason and publishes one {@code TransactionDeclined} event.
 *
 * <p>Add a fifth reason here, as a new constant. The source marks the same extension point with the
 * comment {@code * ADD MORE VALIDATIONS HERE} at {@code app/cbl/CBTRN02C.cbl:L377}. A new constant
 * also needs its code and its text added to the two value lists in
 * {@code schemas/transaction-declined-v1.json}.
 */
public enum DeclineReason {

    /**
     * Reason 100, {@code INVALID CARD NUMBER FOUND}.
     *
     * <p>The keyed read of the card cross-reference file on the transaction's card number returned
     * an invalid-key condition. The read sits at {@code app/cbl/CBTRN02C.cbl:L382-L383} and
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns this reason.
     */
    INVALID_CARD_NUMBER("0100", "INVALID CARD NUMBER FOUND"),

    /**
     * Reason 101, {@code ACCOUNT RECORD NOT FOUND}.
     *
     * <p>The keyed read of the account file on the account identifier from the cross-reference
     * returned an invalid-key condition. The read sits at {@code app/cbl/CBTRN02C.cbl:L394-L395}
     * and {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns this reason.
     */
    ACCOUNT_NOT_FOUND("0101", "ACCOUNT RECORD NOT FOUND"),

    /**
     * Reason 102, {@code OVERLIMIT TRANSACTION}.
     *
     * <p>The credit limit did not reach the cycle credit less the cycle debit plus the transaction
     * amount. {@code app/cbl/CBTRN02C.cbl:L403-L405} computes that working balance,
     * {@code app/cbl/CBTRN02C.cbl:L407} compares the limit against it, and
     * {@code app/cbl/CBTRN02C.cbl:L410-L412} assigns this reason. The working field is
     * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}, one integer digit
     * narrower than the two {@code PIC S9(10)V99} accumulators feeding it.
     */
    OVER_CREDIT_LIMIT("0102", "OVERLIMIT TRANSACTION"),

    /**
     * Reason 103, {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.
     *
     * <p>The account expiration date did not reach the first ten characters of the transaction's
     * origin timestamp. {@code app/cbl/CBTRN02C.cbl:L414} compares the two values as text and
     * {@code app/cbl/CBTRN02C.cbl:L417-L419} assigns this reason.
     */
    ACCOUNT_EXPIRED("0103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

    /** The reason code, four digits zero-padded to the width the source writes. */
    private final String code;

    /** The reason text, character for character from the source. */
    private final String description;

    private DeclineReason(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * The reason code as four zero-padded digits, and the form a constant serializes as.
     *
     * <p>Width from {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}. A serialized event carries {@code "0102"}, never the
     * number 102.
     *
     * @return four characters, one of {@code "0100"}, {@code "0101"}, {@code "0102"} or
     *         {@code "0103"}
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * The reason text the source writes beside the code.
     *
     * <p>Width from {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}. The text populates {@code declineReasonDescription} on a
     * {@code TransactionDeclined} event.
     *
     * @return the reason text, at most seventy-six characters
     */
    public String description() {
        return description;
    }

    /**
     * The reason code as a number, for a caller that stores or compares it numerically.
     *
     * <p>Derived from {@link #code()}, so the padded form and the numeric form cannot disagree.
     * The numeric form is not the wire form.
     *
     * @return 100, 101, 102 or 103
     */
    public int numericCode() {
        return Integer.parseInt(code);
    }

    /**
     * Whether an account identifier is known by the time this reason is assigned.
     *
     * <p>{@link #INVALID_CARD_NUMBER} is assigned inside the {@code INVALID KEY} limb of
     * {@code READ XREF-FILE INTO CARD-XREF-RECORD} at {@code app/cbl/CBTRN02C.cbl:L383-L387}, so
     * {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:L7} was never read. The gate at
     * {@code app/cbl/CBTRN02C.cbl:L372} then keeps the account lookup from running. The reject
     * record written at {@code app/cbl/CBTRN02C.cbl:L448-L449} carries the daily transaction record
     * and the validation trailer, and neither holds an account identifier.
     *
     * <p>The other three reasons are assigned after
     * {@code MOVE XREF-ACCT-ID TO FD-ACCT-ID} at {@code app/cbl/CBTRN02C.cbl:L394}, so an account
     * identifier is in hand. {@link TransactionDeclined} publishes the one reason that answers
     * {@code false} under {@link TransactionDeclined#UNRESOLVED_ACCOUNT_SCHEMA_VERSION}, the
     * contract that declares no account identifier at all.
     *
     * @return {@code true} for the three reasons that follow a successful cross-reference read,
     *         {@code false} for {@link #INVALID_CARD_NUMBER}
     */
    public boolean resolvesAccount() {
        return this != INVALID_CARD_NUMBER;
    }

    /**
     * Resolves a four-character reason code to its constant, and rejects anything else.
     *
     * <p>The match is exact: the lookup neither trims nor pads. Deserializing an event calls this
     * method, so a code outside the four fails on arrival and never reaches a consumer.
     *
     * @param code the four-character zero-padded reason code, as {@link #code()} returns it
     * @return the constant carrying that code
     * @throws IllegalArgumentException when {@code code} is null or is not one of the four codes.
     *         The failure text names the value received.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DeclineReason fromCode(String code) {
        for (DeclineReason reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("no decline reason carries the code "
                + (code == null ? "null" : "\"" + code + "\"")
                + "; the four codes are \"0100\", \"0101\", \"0102\" and \"0103\"");
    }
}
