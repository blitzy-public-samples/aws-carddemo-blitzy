package com.carddemo.authorization.api;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
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
 * {@link #approve(String, String)} builds that outcome, and it requires a resolved account
 * identifier. An approval follows the account read at {@code app/cbl/CBTRN02C.cbl:L396-L399}, the
 * credit test at {@code app/cbl/CBTRN02C.cbl:L403-L413} and the expiry test at
 * {@code app/cbl/CBTRN02C.cbl:L414-L420}, and all three read an account that resolved. An approval
 * without one cannot occur.
 *
 * <p>Every decided outcome carries one account identifier into this response, into the event
 * published beside it and into the Kafka message key. {@code TransactionAuthorized} and
 * {@code TransactionDeclined} both require those eleven digits.
 * {@link DeclineReason#INVALID_CARD_NUMBER} is the one outcome whose cross-reference read resolved
 * none of its own, because that read at {@code app/cbl/CBTRN02C.cbl:L383-L384} took its invalid-key
 * branch; the identifier it carries is the one the caller declared, which
 * {@code AuthorizationRequest#ACCOUNT_ID_PATTERN} already held to eleven digits. A request that
 * declared none establishes no subject and is refused before a decision, so no factory here has to
 * invent a substitute account identifier and none does.
 *
 * <p>A declined response carries one decline reason and its text. The source field holds a single
 * {@code PIC 9(04)} value, overwritten by whichever test fails last. This record holds one decline
 * reason or none, never a list, a set or a bit mask.
 * {@link #decline(String, String, DeclineReason)} builds the three outcomes that follow a resolved
 * account, and reads the text from {@link DeclineReason#description()}.
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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param transactionId            identifier of the transaction this decision applies to, from
 *                                 {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
 *                                 Required, and at most
 *                                 {@value #TRANSACTION_ID_MAX_LENGTH} characters.
 * @param accountId                account identifier the card resolved to, from
 *                                 {@code XREF-ACCT-ID PIC 9(11)} at
 *                                 {@code app/cpy/CVACT03Y.cpy:L7}.
 *                                 {@code app/cbl/CBTRN02C.cbl:L394} reads it from the
 *                                 cross-reference record. Shaped by
 *                                 {@value #ACCOUNT_ID_PATTERN}, and absent only on a decline
 *                                 carrying {@link DeclineReason#INVALID_CARD_NUMBER}, where the
 *                                 keyed read at {@code app/cbl/CBTRN02C.cbl:L383-L384} returned an
 *                                 invalid-key condition and no account exists to name.
 * @param approved                 {@code true} when the platform authorized the transaction.
 *                                 No COBOL ancestor. {@code app/cbl/CBTRN02C.cbl:L211} states
 *                                 the same outcome as a reject-code field holding zero.
 * @param declineReasonCode        the single reject code, from
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
        DeclineReason declineReasonCode,
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
     * <p>An account identifier is absent on exactly one outcome: a decline carrying
     * {@link DeclineReason#INVALID_CARD_NUMBER}. Every other outcome names an account that resolved,
     * so the event published beside the response always has the eleven digits its contract requires.
     *
     * @throws IllegalArgumentException when any of these holds:
     *                                  <ul>
     *                                  <li>{@code transactionId} is absent, or wider than
     *                                  {@value #TRANSACTION_ID_MAX_LENGTH} characters</li>
     *                                  <li>{@code accountId} is present and does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}</li>
     *                                  <li>{@code accountId} is absent on an approved response</li>
     *                                  <li>{@code accountId} is absent on a decline carrying any
     *                                  reason other than
     *                                  {@link DeclineReason#INVALID_CARD_NUMBER}</li>
     *                                  <li>{@code accountId} is present on a decline carrying
     *                                  {@link DeclineReason#INVALID_CARD_NUMBER}</li>
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
        requireAccountIdentity(accountId, approved, declineReasonCode);
        if (approved) {
            if (declineReasonCode != null) {
                throw new IllegalArgumentException("declineReasonCode must be absent on an approved"
                        + " response; the code " + declineReasonCode.code() + " arrived");
            }
            if (present(declineReasonDescription)) {
                throw new IllegalArgumentException(
                        "declineReasonDescription must be absent on an approved response");
            }
            declineReasonDescription = null;
        } else {
            if (declineReasonCode == null) {
                throw new IllegalArgumentException(
                        "declineReasonCode must be present on a declined response");
            }
            declineReasonDescription = requiredDescription(declineReasonDescription);
        }
    }

    /**
     * Builds an approved response, carrying no decline reason and no description.
     *
     * <p>The account identifier is required. An approval passes the account read at
     * {@code app/cbl/CBTRN02C.cbl:L396-L399}, so the account it authorized against is known.
     *
     * @param transactionId identifier of the transaction this decision applies to, at most
     *                      {@value #TRANSACTION_ID_MAX_LENGTH} characters
     * @param accountId     the eleven-digit account identifier the card resolved to; required
     * @return an approved response
     * @throws IllegalArgumentException when {@code transactionId} is absent or too wide, or when
     *                                  {@code accountId} is absent or does not match
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
     * <p>This factory builds all four declines. Three of them follow a resolved account:
     * {@link DeclineReason#ACCOUNT_NOT_FOUND}, {@link DeclineReason#OVER_CREDIT_LIMIT} and
     * {@link DeclineReason#ACCOUNT_EXPIRED} are each reached only after
     * {@code app/cbl/CBTRN02C.cbl:L390-L394} read the cross-reference record and took the account
     * identifier from it. {@link DeclineReason#INVALID_CARD_NUMBER} follows the invalid-key branch of
     * that read at {@code app/cbl/CBTRN02C.cbl:L383-L387}, and the identifier it names is the one the
     * caller declared. The identifier is required for all four, because every decided outcome names
     * the account it applies to and keys its event on it.
     *
     * @param transactionId     identifier of the transaction this decision applies to, at most
     *                          {@value #TRANSACTION_ID_MAX_LENGTH} characters
     * @param accountId         the eleven-digit account identifier this decision applies to; required
     * @param declineReasonCode the single reject code; required, any of the four
     * @return a declined response whose description matches its decline reason
     * @throws IllegalArgumentException when {@code declineReasonCode} is {@code null}, when
     *                                  {@code transactionId} is absent or too wide, or when
     *                                  {@code accountId} is absent or does not match
     *                                  {@value #ACCOUNT_ID_PATTERN}
     */
    public static AuthorizationResponse decline(String transactionId, String accountId,
            DeclineReason declineReasonCode) {
        if (declineReasonCode == null) {
            throw new IllegalArgumentException(
                    "declineReasonCode must be present on a declined response");
        }
        return new AuthorizationResponse(transactionId, accountId, false, declineReasonCode,
                declineReasonCode.description());
    }

    /**
     * Tests the one invariant that ties the account identifier to the outcome.
     *
     * <p>Every decided outcome names the account it applies to. An approval names the account it
     * authorized against, three declines name the account they read, and the decline carrying
     * {@link DeclineReason#INVALID_CARD_NUMBER} names the account the caller declared, because the
     * cross-reference read resolved none of its own. Without this test a response could reach the
     * outbox with no identifier to key its event on, and the event contract requiring eleven digits
     * would fail after the decision had already been taken.
     *
     * <p>No failure text repeats the identifier. The outcome or the word {@code absent} stands in for
     * it.
     *
     * @param accountId         the shaped identifier, or {@code null}
     * @param approved          {@code true} on an approved response
     * @param declineReasonCode the reject code, or {@code null} on an approved response
     * @throws IllegalArgumentException when the outcome names no account
     */
    private static void requireAccountIdentity(String accountId, boolean approved,
            DeclineReason declineReasonCode) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must be present on "
                    + (approved ? "an approved response" : "a decline carrying the reject code "
                            + declineReasonCode.code())
                    + ", and the supplied value is absent");
        }
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

    /**
     * Renders the decision and withholds the account identifier.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier.
     *
     * <p>The transaction identifier, the verdict, the reason code and the reason description stay.
     * A reader diagnosing a declined call needs all four, and the two reason components are fixed
     * constants of {@link DeclineReason} rather than cardholder data.
     *
     * @return the decision of this response with the account identifier withheld, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "AuthorizationResponse[transactionId=" + transactionId + ", accountId="
                + EventEnvelope.WITHHELD + ", approved=" + approved + ", declineReasonCode="
                + declineReasonCode + ", declineReasonDescription=" + declineReasonDescription + "]";
    }
}
