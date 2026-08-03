package com.carddemo.authorization.api;

import com.carddemo.events.DeclineReason;
import java.util.regex.Pattern;

/**
 * Response body of the synchronous authorization call.
 *
 * <p>Two source fields shape this record. {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code app/cbl/CBTRN02C.cbl:L181} holds a four-digit reject code, and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:L182} holds its
 * text. The two fill the eighty-byte trailer declared at {@code app/cbl/CBTRN02C.cbl:L178}. That
 * trailer closes the four-hundred-and-thirty-byte reject record allocated at
 * {@code app/jcl/POSTTRAN.jcl:L36}.
 *
 * <p>An approved response carries no decline reason and no description.
 * {@code app/cbl/CBTRN02C.cbl:L208} clears the reject-code field for each record, and
 * {@code app/cbl/CBTRN02C.cbl:L211-L212} posts the transaction while that field still holds zero.
 * {@link #approve(String, String)} builds that outcome.
 *
 * <p>A declined response carries one decline reason and its text. The source field holds a single
 * {@code PIC 9(04)} value, overwritten by whichever test fails last. This record holds one decline
 * reason or none, never a list, a set or a bit mask.
 * {@link #decline(String, String, DeclineReason)} builds that outcome and reads the text from
 * {@link DeclineReason#description()}.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L214-L215} counts the record and
 * writes a reject row, and {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with return
 * code 4 when any record was rejected.
 *
 * <p>{@link DeclineReason} serializes as four zero-padded characters, {@code "0100"} through
 * {@code "0103"}. A serialized response carries {@code "0102"} and never the number 102. The four
 * texts live on that enum, and this record restates none of them.
 *
 * <p>No component carries a card number, masked or unmasked. None carries the card verification
 * value stored at {@code app/cpy/CVACT02Y.cpy:L7}, an amount, a merchant field, a timestamp, a card
 * status, an account status or a failure trace. The three-hundred-and-fifty-byte payload beside the
 * trailer, {@code REJECT-TRAN-DATA PIC X(350)} at {@code app/cbl/CBTRN02C.cbl:L177}, has no
 * component here either.
 *
 * <p>The trailer-to-response mapping is recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 *
 * @param transactionId            identifier of the transaction this decision applies to, from
 *                                 {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
 *                                 Required, and at most
 *                                 {@value #TRANSACTION_ID_MAX_LENGTH} characters.
 * @param accountId                account identifier the card resolved to, from
 *                                 {@code XREF-ACCT-ID PIC 9(11)} at
 *                                 {@code app/cpy/CVACT03Y.cpy:L7}.
 *                                 {@code app/cbl/CBTRN02C.cbl:L394} reads it from the
 *                                 cross-reference record. Absent when the keyed read at
 *                                 {@code app/cbl/CBTRN02C.cbl:L383-L384} returned an invalid-key
 *                                 condition. Shaped by {@value #ACCOUNT_ID_PATTERN} when present.
 * @param approved                 {@code true} when the platform authorized the transaction.
 *                                 ADDITIVE. {@code app/cbl/CBTRN02C.cbl:L211} states the same
 *                                 outcome as a reject-code field holding zero.
 * @param declineReason            the single reject code, from
 *                                 {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 *                                 {@code app/cbl/CBTRN02C.cbl:L181}. Present on a declined
 *                                 response, absent on an approved one.
 * @param declineReasonDescription text of that reject code, from
 *                                 {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 *                                 {@code app/cbl/CBTRN02C.cbl:L182}. Present on a declined
 *                                 response, absent on an approved one, and at most
 *                                 {@value #DECLINE_REASON_DESCRIPTION_MAX_LENGTH} characters.
 */
public record AuthorizationResponse(
        String transactionId,
        String accountId,
        boolean approved,
        DeclineReason declineReason,
        String declineReasonDescription) {

    /**
     * Widest {@code transactionId} this record holds, from {@code TRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L5}. The source field holds characters, not digits. A value keeps
     * the shape the caller sent, within this width.
     */
    public static final int TRANSACTION_ID_MAX_LENGTH = 16;

    /**
     * Shape of {@code accountId}: eleven digits, left-filled with zeros to the width of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     * {@code app/cbl/CBTRN02C.cbl:L394} moves that field into the key the account read uses, and
     * {@link AuthorizationRequest#canonicalAccountId()} produces the same eleven-digit form.
     */
    public static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Widest {@code declineReasonDescription} this record holds, from
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:L182}. The
     * longest of the four texts {@link DeclineReason} carries holds forty-two characters.
     */
    public static final int DECLINE_REASON_DESCRIPTION_MAX_LENGTH = 76;

    /** {@link #ACCOUNT_ID_PATTERN} compiled once. */
    private static final Pattern ACCOUNT_ID_SHAPE = Pattern.compile(ACCOUNT_ID_PATTERN);

    /**
     * Normalises the two identity components and rejects an inconsistent outcome.
     *
     * <p>An approved response reaching this constructor with a decline reason, or a declined
     * response reaching it without one, fails here. Each failure names the component that broke the
     * invariant. No failure text repeats a component value. A character count or a four-character
     * code stands in for it, and a card number supplied in the wrong position never reaches a log.
     *
     * @throws IllegalArgumentException when any of these holds:
     *                                  <ul>
     *                                  <li>{@code transactionId} is absent, or wider than
     *                                  {@value #TRANSACTION_ID_MAX_LENGTH} characters</li>
     *                                  <li>{@code accountId} is present and does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}</li>
     *                                  <li>an approved response carries a decline reason</li>
     *                                  <li>an approved response carries a description</li>
     *                                  <li>a declined response carries no decline reason</li>
     *                                  <li>a declined response carries no description, or one
     *                                  wider than
     *                                  {@value #DECLINE_REASON_DESCRIPTION_MAX_LENGTH}
     *                                  characters</li>
     *                                  </ul>
     */
    public AuthorizationResponse {
        transactionId = requiredTransactionId(transactionId);
        accountId = shapedAccountId(accountId);
        if (approved) {
            if (declineReason != null) {
                throw new IllegalArgumentException("declineReason must be absent on an approved"
                        + " response; the code " + declineReason.code() + " arrived");
            }
            if (present(declineReasonDescription)) {
                throw new IllegalArgumentException(
                        "declineReasonDescription must be absent on an approved response");
            }
            declineReasonDescription = null;
        } else {
            if (declineReason == null) {
                throw new IllegalArgumentException(
                        "declineReason must be present on a declined response");
            }
            declineReasonDescription = requiredDescription(declineReasonDescription);
        }
    }

    /**
     * Builds an approved response, carrying no decline reason and no description.
     *
     * @param transactionId identifier of the transaction this decision applies to, at most
     *                      {@value #TRANSACTION_ID_MAX_LENGTH} characters
     * @param accountId     the eleven-digit account identifier, or {@code null} when no
     *                      cross-reference record resolved
     * @return an approved response
     * @throws IllegalArgumentException when {@code transactionId} is absent or too wide, or when
     *                                  {@code accountId} is present and does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}
     */
    public static AuthorizationResponse approve(String transactionId, String accountId) {
        return new AuthorizationResponse(transactionId, accountId, true, null, null);
    }

    /**
     * Builds a declined response carrying one decline reason and the text that reason holds.
     *
     * <p>The description comes from {@link DeclineReason#description()}, which reproduces the text
     * the source moves into {@code WS-VALIDATION-FAIL-REASON-DESC} beside each reject code.
     *
     * @param transactionId identifier of the transaction this decision applies to, at most
     *                      {@value #TRANSACTION_ID_MAX_LENGTH} characters
     * @param accountId     the eleven-digit account identifier, or {@code null} when no
     *                      cross-reference record resolved
     * @param declineReason the single reject code; required
     * @return a declined response whose description matches its decline reason
     * @throws IllegalArgumentException when {@code declineReason} is {@code null}, when
     *                                  {@code transactionId} is absent or too wide, or when
     *                                  {@code accountId} is present and does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}
     */
    public static AuthorizationResponse decline(String transactionId, String accountId,
            DeclineReason declineReason) {
        if (declineReason == null) {
            throw new IllegalArgumentException(
                    "declineReason must be present on a declined response");
        }
        return new AuthorizationResponse(transactionId, accountId, false, declineReason,
                declineReason.description());
    }

    /**
     * Strips a transaction identifier and tests it against the width of its record field.
     *
     * @param value the component to read; may be {@code null}
     * @return the stripped identifier
     * @throws IllegalArgumentException when {@code value} holds no content, or holds more than
     *                                  {@value #TRANSACTION_ID_MAX_LENGTH} characters once
     *                                  stripped
     */
    private static String requiredTransactionId(String value) {
        if (!present(value)) {
            throw new IllegalArgumentException("transactionId must be present on a response");
        }
        String text = value.strip();
        if (text.length() > TRANSACTION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("transactionId holds " + text.length()
                    + " characters; TRAN-ID PIC X(16) holds at most "
                    + TRANSACTION_ID_MAX_LENGTH);
        }
        return text;
    }

    /**
     * Strips an account identifier and tests it against the eleven-digit form.
     *
     * @param value the component to read; may be {@code null}
     * @return the stripped identifier, or {@code null} when {@code value} holds no content
     * @throws IllegalArgumentException when {@code value} holds content that does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}. The failure text reports the
     *                                  count of characters and not the value
     */
    private static String shapedAccountId(String value) {
        if (!present(value)) {
            return null;
        }
        String text = value.strip();
        if (!ACCOUNT_ID_SHAPE.matcher(text).matches()) {
            throw new IllegalArgumentException("accountId does not match " + ACCOUNT_ID_PATTERN
                    + "; it holds " + text.length() + " characters");
        }
        return text;
    }

    /**
     * Strips a decline reason description and tests it against the width of its record field.
     *
     * @param value the component to read; may be {@code null}
     * @return the stripped description
     * @throws IllegalArgumentException when {@code value} holds no content, or holds more than
     *                                  {@value #DECLINE_REASON_DESCRIPTION_MAX_LENGTH} characters
     *                                  once stripped
     */
    private static String requiredDescription(String value) {
        if (!present(value)) {
            throw new IllegalArgumentException(
                    "declineReasonDescription must be present on a declined response");
        }
        String text = value.strip();
        if (text.length() > DECLINE_REASON_DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("declineReasonDescription holds " + text.length()
                    + " characters; WS-VALIDATION-FAIL-REASON-DESC PIC X(76) holds at most "
                    + DECLINE_REASON_DESCRIPTION_MAX_LENGTH);
        }
        return text;
    }

    /**
     * Reports whether a component arrived with content.
     *
     * @param value the component to test; may be {@code null}
     * @return {@code true} when {@code value} holds at least one character that is not whitespace
     */
    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
