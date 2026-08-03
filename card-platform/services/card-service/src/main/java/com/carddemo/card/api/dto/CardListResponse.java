package com.carddemo.card.api.dto;

import java.util.List;

/**
 * One page of the card list, returned by {@code GET /cards}.
 *
 * <p>A page carries three things: the cards on it, a flag stating whether a further
 * page exists, and the cursor the next request supplies. The card list program
 * {@code app/cbl/COCRDLIC.cbl} holds the same state across its screen turns.
 *
 * <p>Each entry is a {@code CardSummary}. The forward browse fills one row per card
 * at {@code app/cbl/COCRDLIC.cbl:L1165-L1171} and clears its row table before every
 * browse at {@code app/cbl/COCRDLIC.cbl:L1124}.
 *
 * <p>The keyset cursor and the masked card number each carry an entry in
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param cards the cards on this page, ordered by ascending card number. The list is
 *        copied on construction and cannot be modified. A page with no cards carries
 *        an empty list.
 * @param nextPageExists {@code true} when a further page exists, derived by the
 *        lookahead at {@code app/cbl/COCRDLIC.cbl:L1284-L1287}. The source reads one
 *        row past the page and sets condition name {@code CA-NEXT-PAGE-EXISTS},
 *        declared at {@code app/cbl/COCRDLIC.cbl:L244}.
 * @param nextCursor the unmasked card number of the last card on this page, and the
 *        cursor the next request supplies. That request returns the cards whose card
 *        number is greater than this value. The value is {@code null} when no further
 *        page exists. Source {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
 *        {@code app/cbl/COCRDLIC.cbl:L231}, written as the browse advances at lines
 *        1195, 1214 and 1237, and read as the browse key at
 *        {@code app/cbl/COCRDLIC.cbl:L488-L489}.
 */
public record CardListResponse(List<CardSummary> cards, boolean nextPageExists, String nextCursor) {

    /**
     * Copies the card list. A page cannot change after construction.
     *
     * <p>A {@code null} card list becomes an empty list. A {@code null} entry inside
     * the card list raises {@link NullPointerException}.
     */
    public CardListResponse {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
