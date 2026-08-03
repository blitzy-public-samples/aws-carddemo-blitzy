package com.carddemo.card.api.dto;

import java.util.List;

/**
 * One page of the card list, returned by {@code GET /cards}.
 *
 * <p>A page carries three things: the cards on it, a flag stating whether a further
 * page exists, and the number the next request asks for. The card list program
 * {@code app/cbl/COCRDLIC.cbl} holds the same state across its screen turns.
 *
 * <p>Each entry is a {@code CardSummary}. The forward browse fills one row per card
 * at {@code app/cbl/COCRDLIC.cbl:L1165-L1171} and clears its row table before every
 * browse at {@code app/cbl/COCRDLIC.cbl:L1124}.
 *
 * <p>No component of this response carries card data. The source keeps its browse position in
 * {@code WS-CA-LAST-CARD-NUM PIC X(16)} at {@code app/cbl/COCRDLIC.cbl:L231}, a full Primary
 * Account Number (PAN) written as the browse advances at lines 1195, 1214 and 1237 and read back as
 * the browse key at {@code app/cbl/COCRDLIC.cbl:L488-L489}. That field never leaves the mainframe:
 * it lives in a communication area between screen turns, not in a response a client, a proxy or an
 * access log can keep. A page number carries the same paging intent and no card data, so the client
 * echoes {@link #nextPageNumber()} instead of a browse key.
 *
 * @param cards the cards on this page, ordered by ascending card number. The list is
 *        copied on construction and cannot be modified. A page with no cards carries
 *        an empty list.
 * @param nextPageExists {@code true} when a further page exists, derived by the forward
 *        lookahead at {@code app/cbl/COCRDLIC.cbl:L1191-L1216}. The read at
 *        L1197-L1205 fetches one row past the page, and L1208-L1211 set condition
 *        name {@code CA-NEXT-PAGE-EXISTS}, declared at
 *        {@code app/cbl/COCRDLIC.cbl:L244}. The same condition name also carries an
 *        unconditional preset at {@code app/cbl/COCRDLIC.cbl:L1287}, which serves the
 *        backward browse.
 * @param nextPageNumber the one-based number of the page the next request asks for, one past the
 *        number of this page. The value is {@code null} exactly when {@code nextPageExists} is
 *        {@code false}. It identifies a position in the list and no card, so nothing here reveals a
 *        card number, masked or otherwise.
 */
public record CardListResponse(List<CardSummary> cards, boolean nextPageExists,
        Integer nextPageNumber) {

    /** The number of the first page, so a client can start a browse without a marker. */
    public static final int FIRST_PAGE_NUMBER = 1;

    /**
     * Copies the card list and checks the page number.
     *
     * <p>A {@code null} card list becomes an empty list. A {@code null} entry inside
     * the card list raises {@link NullPointerException}.
     *
     * @throws IllegalArgumentException when {@code nextPageNumber} disagrees with
     *         {@code nextPageExists}, or when it is below {@link #FIRST_PAGE_NUMBER} plus one
     */
    public CardListResponse {
        cards = cards == null ? List.of() : List.copyOf(cards);

        if (nextPageExists && nextPageNumber == null) {
            throw new IllegalArgumentException(
                    "a page that reports a further page names the number of that page");
        }
        if (!nextPageExists && nextPageNumber != null) {
            throw new IllegalArgumentException(
                    "a page that reports no further page names no page number");
        }
        if (nextPageNumber != null && nextPageNumber <= FIRST_PAGE_NUMBER) {
            throw new IllegalArgumentException("the next page number follows the first page, and "
                    + nextPageNumber + " does not");
        }
    }
}
