package com.carddemo.card.api.dto;

import com.carddemo.events.EventEnvelope;
import java.util.List;

/**
 * One page of the card list, returned by {@code GET /cards}.
 *
 * <p>A page carries three things: the cards on it, a flag stating whether a further page exists,
 * and the cursor the next request passes back. The card list program
 * {@code app/cbl/COCRDLIC.cbl} holds the same state across its screen turns, keyed on a card
 * number and not on a page ordinal.
 *
 * <p>Each entry is a {@link CardSummary}. The forward browse fills one row per card at
 * {@code app/cbl/COCRDLIC.cbl:L1165-L1171} and clears its row table before every browse at
 * {@code app/cbl/COCRDLIC.cbl:L1124}.
 *
 * <p>The source keeps its browse position in {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
 * {@code app/cbl/COCRDLIC.cbl:L231}, written as the browse advances at lines 1195, 1214 and 1237
 * and read back as the browse key at {@code app/cbl/COCRDLIC.cbl:L488-L489}. {@link #nextCursor()}
 * carries that same position. Source mapping for this record sits in
 * {@code card-platform/docs/traceability-matrix.md} (planned).
 *
 * @param cards the cards on this page, ordered by ascending card number. The list is copied on
 *        construction and cannot be modified. A page with no cards carries an empty list.
 * @param nextPageExists {@code true} when a further page exists, derived by the forward lookahead
 *        at {@code app/cbl/COCRDLIC.cbl:L1284-L1287}. That lookahead computes
 *        {@code WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES + 1} and sets condition name
 *        {@code CA-NEXT-PAGE-EXISTS}, declared at {@code app/cbl/COCRDLIC.cbl:L244}, so the source
 *        reads one row beyond the page to learn whether a further page exists.
 * @param nextCursor the card number of the last row on this page, which the next request passes
 *        as its cursor. The next page starts after this card number, so the cursor is exclusive
 *        and the row it names is never returned twice. The value is {@code null} exactly when
 *        {@code nextPageExists} is {@code false}, and a first request passes no cursor at all.
 */
public record CardListResponse(List<CardSummary> cards, boolean nextPageExists, String nextCursor) {

    /**
     * Copies the card list and checks the cursor against the next-page flag.
     *
     * <p>A {@code null} card list becomes an empty list. A {@code null} entry inside the card list
     * raises {@link NullPointerException}.
     *
     * @throws IllegalArgumentException when {@code nextCursor} disagrees with
     *         {@code nextPageExists}, or when it holds no character
     */
    public CardListResponse {
        cards = cards == null ? List.of() : List.copyOf(cards);

        if (nextPageExists && nextCursor == null) {
            throw new IllegalArgumentException(
                    "a page that reports a further page carries the cursor of that page");
        }
        if (!nextPageExists && nextCursor != null) {
            throw new IllegalArgumentException(
                    "a page that reports no further page carries no cursor");
        }
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("a cursor holds the card number of the last row, "
                    + "and a blank value names no row");
        }
    }

    /**
     * Returns a rendering that counts the cards and names the paging state, and prints no card.
     *
     * <p>Every entry renders safely on its own, so the objection is not to any one card but to the
     * page. The rendering a record carries by default expands the whole list, which turns one
     * interpolated response into as many masked numbers and account identifiers as the page holds,
     * multiplied by every log line that touches it. A count says everything an operator reading a
     * paging problem needs, and a caller that wants a card can render that card.
     *
     * <p>The cursor is withheld rather than counted. It holds the card number of the last row on
     * this page, read back as the browse key at {@code app/cbl/COCRDLIC.cbl:L488-L489}, so printing
     * it would put a card number in every log line that touches a page. Whether a cursor is present
     * is what a paging problem needs, and that is what the rendering reports.
     *
     * @return one line naming the class, how many cards the page holds and the paging state
     */
    @Override
    public String toString() {
        return "CardListResponse[cards=" + cards.size() + " on this page"
                + ", nextPageExists=" + nextPageExists
                + ", nextCursor=" + (nextCursor == null ? "absent" : EventEnvelope.WITHHELD) + "]";
    }
}
