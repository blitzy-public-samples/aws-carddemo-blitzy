package com.carddemo.card.api.dto;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Card detail projection returned by {@code POST /cards/detail}.
 *
 * <p>Five components carry the card detail, in the order the card record declares
 * them at {@code app/cpy/CVACT02Y.cpy}. The card detail program
 * {@code app/cbl/COCRDSLC.cbl} displays the same five values, and the card update
 * program {@code app/cbl/COCRDUPC.cbl} writes them.
 *
 * <p>The card service reads a card by its full sixteen-character Primary Account Number (PAN),
 * which arrives in the request body, and masks with
 * {@code com.carddemo.cobol.PanMasker.maskCardNumber} when it builds this response.
 *
 * @param maskedCardNumber the card number in its masked form, sixteen characters holding twelve
 *        mask characters then the last four digits.
 *        Source {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
 * @param accountId eleven-digit account identifier, padded on the left with zeros.
 *        Source {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}.
 * @param embossedName cardholder name embossed on the card, up to fifty
 *        characters. Source {@code CARD-EMBOSSED-NAME PIC X(50)} at
 *        {@code app/cpy/CVACT02Y.cpy:L8}.
 * @param expirationDate calendar date the card expires, ten characters in
 *        {@code YYYY-MM-DD} form. Source {@code CARD-EXPIRAION-DATE PIC X(10)} at
 *        {@code app/cpy/CVACT02Y.cpy:L9}, sliced into a four-character year, a
 *        two-character month and a two-character day at
 *        {@code app/cbl/COCRDUPC.cbl:L115-L123}.
 * @param activeStatus single-character active status flag. Source
 *        {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}.
 */
public record CardDetailResponse(
        String maskedCardNumber,
        String accountId,
        String embossedName,
        LocalDate expirationDate,
        String activeStatus) {

    /** The one form the card number component takes: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Compiled form of {@link #MASKED_CARD_NUMBER_PATTERN}. */
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * Checks that the card number component holds the masked form and nothing else.
     *
     * <p>The check is what makes {@link #toString()} safe to print the component in full. The same
     * expression governs the published event at
     * {@code com.carddemo.card.messaging.CardUpdated.MASKED_CARD_NUMBER_PATTERN}, so a response and
     * an event report one card the same way.
     *
     * @throws NullPointerException     when {@code maskedCardNumber} is null
     * @throws IllegalArgumentException when it holds any other form, a full Primary Account Number
     *                                  included
     */
    public CardDetailResponse {
        if (maskedCardNumber == null) {
            throw new NullPointerException("maskedCardNumber is required");
        }
        if (!MASKED_CARD_NUMBER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber holds "
                    + maskedCardNumber.length() + " characters and a masked card number holds "
                    + PanMasker.CARD_NUMBER_LENGTH + " matching " + MASKED_CARD_NUMBER_PATTERN);
        }
    }

    /**
     * Returns a rendering that keeps the two identifiers and the status flag, and withholds the
     * cardholder name and the expiry date.
     *
     * <p>The card number is safe by construction: the canonical constructor accepts only twelve
     * mask characters and four digits, so no full number can reach this rendering. The embossed name
     * and
     * the expiry date are not protected that way. Both are values a card-not-present authorization
     * asks for, and printing them beside four real digits of the number narrows the gap further
     * than any one of the three does alone.
     *
     * @return one line naming the class, the masked number, the account and the status, with the
     *         cardholder name and the expiry date withheld
     */
    @Override
    public String toString() {
        return "CardDetailResponse[maskedCardNumber=" + maskedCardNumber
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", embossedName=" + EventEnvelope.WITHHELD
                + ", expirationDate=" + EventEnvelope.WITHHELD
                + ", activeStatus=" + activeStatus + "]";
    }
}
