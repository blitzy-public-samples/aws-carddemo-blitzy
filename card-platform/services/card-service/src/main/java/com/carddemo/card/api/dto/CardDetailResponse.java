package com.carddemo.card.api.dto;

import java.time.LocalDate;

/**
 * Card detail projection returned by {@code GET /cards/{cardNumber}}.
 *
 * <p>Five components carry the card detail, in the order the card record declares
 * them at {@code app/cpy/CVACT02Y.cpy}. The card detail program
 * {@code app/cbl/COCRDSLC.cbl} displays the same five values, and the card update
 * program {@code app/cbl/COCRDUPC.cbl} writes them.
 *
 * <p>The {@code maskedCardNumber} component holds the masked value. The card
 * service reads a card by its full sixteen-character Primary Account Number, which
 * arrives as the path variable. Masking runs at the serialization boundary, after
 * the read.
 *
 * <p>The omitted source fields and the corrected spelling of the expiration date
 * appear in {@code card-platform/docs/traceability-matrix.md}.
 *
 * @param maskedCardNumber masked form of the card number. Source
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
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
}
