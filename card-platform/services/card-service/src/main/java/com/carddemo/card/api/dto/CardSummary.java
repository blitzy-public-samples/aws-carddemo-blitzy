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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param cardNumber   the card number in its masked form: twelve mask characters then the last four
 *                     digits. The controller masks with
 *                     {@code com.carddemo.cobol.PanMasker.maskCardNumber} when it builds the row,
 *                     so no full Primary Account Number (PAN) reaches a response. Lookups and
 *                     filters run on the full sixteen characters, before the row is built.
 * @param accountId    the eleven-digit identifier of the account the card belongs to, carried as
 *                     text. {@code CARD-ACCT-ID PIC 9(11)} is numeric display, so text keeps every
 *                     leading zero through serialization. The same eleven-character form is the
 *                     {@code accountId} of every account-keyed event schema and the Kafka message
 *                     key of those events; {@code TransactionDeclined} version 2 is keyed on a
 *                     transaction identifier instead.
 * @param activeStatus the one-character active status, {@value #ACTIVE_STATUS_YES} or
 *        {@value #ACTIVE_STATUS_NO} from {@code 88 FLG-YES-NO-VALID} at
 *        {@code app/cbl/COCRDUPC.cbl:L91}
 */
public record CardSummary(String cardNumber, String accountId, String activeStatus) {

    /** Digits an account identifier holds, from {@code CARD-ACCT-ID PIC 9(11)} at L6. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * The affirmative value of {@code active_status}, from
     * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}.
     *
     * <p>Public because two response records carry the column and one domain governs both. It is
     * declared here rather than on {@code entity/CardEntity} so that a response never depends on the
     * persistence layer to know what it may answer.
     */
    public static final String ACTIVE_STATUS_YES = "Y";

    /**
     * The negative value of {@code active_status}, from the same condition name at
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    public static final String ACTIVE_STATUS_NO = "N";

    /** The one form the card number component takes: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * Compiled form of {@link #MASKED_CARD_NUMBER_PATTERN}.
     *
     * <p>The same expression governs the published event at
     * {@code com.carddemo.card.messaging.CardUpdated.MASKED_CARD_NUMBER_PATTERN} and the stored
     * reject row, whose column carries a matching check constraint. Enforcing it here is what lets
     * {@link #toString()} print the card number: no complete Primary Account Number can reach this
     * record.
     */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * Checks that the card number is masked and the account identifier holds eleven digits.
     *
     * @throws NullPointerException     when {@code cardNumber}, {@code accountId} or
     *                                  {@code activeStatus} is null
     * @throws IllegalArgumentException when {@code cardNumber} is not twelve mask characters
     *                                  followed by four digits, when {@code accountId} is any
     *                                  other width or holds a character other than an ASCII digit,
     *                                  or when {@code activeStatus} is neither
     *                                  {@value #ACTIVE_STATUS_YES} nor {@value #ACTIVE_STATUS_NO}
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
        requireActiveStatusFlag(activeStatus);
    }

    /**
     * Refuses an active status outside the two values the column may hold.
     *
     * <p>{@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91} is the
     * domain, tested at {@code app/cbl/COCRDUPC.cbl:L1861-L1863}. Three places now hold a card row to
     * it: {@code api/dto/CardUpdateRequest} on the way in, {@code ck_card_active_status} in
     * {@code src/main/resources/db/migration/V5__xref_reconciliation_and_status_domain.sql} for every
     * writer including a direct load, and this method on the way out. Before those two additions the
     * column was {@code CHAR(1)} with no constraint and a read answered whatever a row held, inside a
     * contract that named two values.
     *
     * <p>The source folds no case on this field, so a lower-case {@code y} is refused rather than
     * accepted and corrected.
     *
     * @param value the status a response is being built with
     * @throws NullPointerException     when {@code value} is null
     * @throws IllegalArgumentException when it is neither {@value #ACTIVE_STATUS_YES} nor
     *                                  {@value #ACTIVE_STATUS_NO}
     */
    public static void requireActiveStatusFlag(String value) {
        if (value == null) {
            throw new NullPointerException("activeStatus is required");
        }
        if (!ACTIVE_STATUS_YES.equals(value) && !ACTIVE_STATUS_NO.equals(value)) {
            throw new IllegalArgumentException("activeStatus holds '" + value + "' and CARD-ACTIVE-"
                    + "STATUS holds " + ACTIVE_STATUS_YES + " or " + ACTIVE_STATUS_NO
                    + ", from 88 FLG-YES-NO-VALID at app/cbl/COCRDUPC.cbl:L91");
        }
    }

    /**
     * @return one line naming the class, the already-masked card number, the withheld account
     *         identifier and the status. Writing the override out rather than inheriting it makes a
     *         component added later a visible decision instead of a silent disclosure
     */
    @Override
    public String toString() {
        return "CardSummary[cardNumber=" + cardNumber
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", activeStatus=" + activeStatus + "]";
    }
}
