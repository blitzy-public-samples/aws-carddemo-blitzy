package com.carddemo.card.api.dto;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import java.util.regex.Pattern;

/**
 * One row of the card list.
 *
 * <p>This record carries three fields. The forward card browse projects exactly these three into its
 * screen row table at {@code app/cbl/COCRDLIC.cbl:L1165-L1171}, and the backward browse repeats them
 * at {@code app/cbl/COCRDLIC.cbl:L1338-L1344}. The source list carries no embossed name, no expiry
 * date and no card verification value.
 *
 * <p>The three field widths come from the card record copybook {@code app/cpy/CVACT02Y.cpy}:
 * {@code CARD-NUM PIC X(16)} at L5, {@code CARD-ACCT-ID PIC 9(11)} at L6 and
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at L10. That copybook ends with a 59-byte trailing
 * {@code FILLER} at L11, which this record drops.
 *
 * @param cardNumber   the card number in its masked form: twelve mask characters then the last four
 *                     digits. The controller masks with
 *                     {@code com.carddemo.cobol.PanMasker.maskCardNumber} when it builds the row,
 *                     so no full Primary Account Number (PAN) reaches a response. Lookups and
 *                     filters run on the full sixteen characters, before the row is built.
 * @param accountId    the eleven-digit identifier of the account the card belongs to, carried as
 *                     text. {@code CARD-ACCT-ID PIC 9(11)} is numeric display, so
 *                     {@code 00000000050} holds three significant digits behind eight leading
 *                     zeros. Text keeps those zeros through serialization, and the same
 *                     eleven-character form appears in the {@code accountId} of every event schema
 *                     and in the Kafka message key.
 * @param activeStatus the one-character active status, carried through with no interpretation.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public record CardSummary(String cardNumber, String accountId, String activeStatus) {

    /** Digits an account identifier holds, from {@code CARD-ACCT-ID PIC 9(11)} at L6. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** The one form the card number component takes: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * Compiled form of {@link #MASKED_CARD_NUMBER_PATTERN}.
     *
     * <p>The same expression governs the published event at
     * {@code com.carddemo.card.messaging.CardUpdated.MASKED_CARD_NUMBER_PATTERN} and the stored
     * reject row, whose column carries a matching check constraint. Enforcing it here is what makes
     * {@link #toString()} safe to print in full: no complete Primary Account Number can reach this
     * record, so the rendering has no component to withhold.
     */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * Checks that the account identifier holds exactly eleven digits.
     *
     * @throws NullPointerException     when {@code accountId} is null
     * @throws IllegalArgumentException when {@code accountId} is any other width, or holds a
     *                                  character other than an ASCII digit
     */
    public CardSummary {
        if (cardNumber == null) {
            throw new NullPointerException("cardNumber is required");
        }
        if (!MASKED_CARD_NUMBER.matcher(cardNumber).matches()) {
            throw new IllegalArgumentException("cardNumber holds " + cardNumber.length()
                    + " characters and a masked card number holds "
                    + PanMasker.CARD_NUMBER_LENGTH + " matching " + MASKED_CARD_NUMBER_PATTERN);
        }
        if (accountId == null) {
            throw new NullPointerException("accountId is required");
        }
        if (accountId.length() != ACCOUNT_ID_DIGITS) {
            throw new IllegalArgumentException("accountId holds " + accountId.length()
                    + " characters and CARD-ACCT-ID holds " + ACCOUNT_ID_DIGITS);
        }
        for (int position = 0; position < accountId.length(); position++) {
            char digit = accountId.charAt(position);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("accountId holds a character other than a digit"
                        + " at position " + (position + 1));
            }
        }
    }

    /**
     * Returns a rendering that carries every component, because no component of this record is
     * sensitive.
     *
     * <p>The override is not decoration. The canonical constructor admits only twelve mask
     * characters followed by four digits, the account identifier is an internal key, and the status
     * is a single flag. The rendering a record carries by default would therefore already be safe.
     * Writing
     * it out states that conclusion where a reader will find it, and makes a component added later a
     * visible decision rather than a silent disclosure through an inherited default.
     *
     * @return one line naming the class and each of the three components
     */
    @Override
    public String toString() {
        return "CardSummary[cardNumber=" + cardNumber
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", activeStatus=" + activeStatus + "]";
    }
}
