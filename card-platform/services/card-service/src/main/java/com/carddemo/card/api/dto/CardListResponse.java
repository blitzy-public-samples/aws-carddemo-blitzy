package com.carddemo.card.api.dto;

import com.carddemo.cobol.PanMasker;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One page of the card list, returned by {@code GET /cards}.
 *
 * <p>A page carries three things: the cards on it, a flag stating whether a further page exists,
 * and the cursor the next request passes back. The card list program
 * {@code app/cbl/COCRDLIC.cbl} holds the same state across its screen turns, keyed on the row it
 * stopped at and not on a page ordinal.
 *
 * <p>Each entry is a {@link CardSummary}. The forward browse fills one row per card at
 * {@code app/cbl/COCRDLIC.cbl:L1165-L1171} and clears its row table before every browse at
 * {@code app/cbl/COCRDLIC.cbl:L1124}.
 *
 * <p>The source keeps its browse position in {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
 * {@code app/cbl/COCRDLIC.cbl:L231}, written as the browse advances at lines 1195, 1214 and 1237
 * and read back as the browse key at {@code app/cbl/COCRDLIC.cbl:L488-L489}. {@link #nextCursor()}
 * carries that same position in the one form a response may carry it. Source mapping for this
 * record sits in {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>That form is a card token, not a card number. The source keeps its browse key in working
 * storage the terminal never receives, so a card number there reaches nobody. This cursor leaves
 * the service in a response and returns in the next request, which makes it published data, and AAP
 * section 0.6.4 admits only a tokenized or masked form in published data. A masked number names
 * every card sharing four digits and therefore names no single row, so the token is the only form
 * that both hides the card and identifies the position. {@code CardQueryService} derives it from
 * {@link PanMasker#cardToken(String)} and resolves it back to a browse position server-side.
 *
 * @param cards the cards on this page, ordered by ascending card number. The list is copied on
 *        construction and cannot be modified. A page with no cards carries an empty list.
 * @param nextPageExists {@code true} when a further page exists, derived by the forward lookahead
 *        at {@code app/cbl/COCRDLIC.cbl:L1284-L1287}. That lookahead computes
 *        {@code WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES + 1} and sets condition name
 *        {@code CA-NEXT-PAGE-EXISTS}, declared at {@code app/cbl/COCRDLIC.cbl:L244}, so the source
 *        reads one row beyond the page to learn whether a further page exists.
 * @param nextCursor the card token of the last row on this page, which the next request passes as
 *        its cursor. It is opaque: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
 *        characters carrying no digit of any card number, and nothing derives a card number back
 *        from it. The next page starts after the row it names, so the cursor is exclusive and that
 *        row is never returned twice. The value is {@code null} exactly when
 *        {@code nextPageExists} is {@code false}, and a first request passes no cursor at all.
 */
public record CardListResponse(List<CardSummary> cards, boolean nextPageExists, String nextCursor) {

    /**
     * The shape a cursor holds, compiled once.
     *
     * <p>{@link PanMasker#CARD_TOKEN_PATTERN} is the one declaration of that shape across this
     * platform, and the same pattern guards the column in {@code V1__schema.sql} and the cursor in
     * {@code CardQueryService}.
     */
    private static final Pattern CURSOR_SHAPE = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /**
     * Copies the card list and checks the cursor against the next-page flag and against its shape.
     *
     * <p>A {@code null} card list becomes an empty list. A {@code null} entry inside the card list
     * raises {@link NullPointerException}.
     *
     * <p>The shape check is what keeps a card number out of this component. Sixteen digits fail
     * {@link PanMasker#CARD_TOKEN_PATTERN} on width and again on character class, so a response
     * carrying one cannot be built at all.
     *
     * @throws IllegalArgumentException when {@code nextCursor} disagrees with
     *         {@code nextPageExists}, or when it is not a card token
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
        if (nextCursor != null && !CURSOR_SHAPE.matcher(nextCursor).matches()) {
            throw new IllegalArgumentException("a cursor holds the card token of the last row: "
                    + PanMasker.CARD_TOKEN_LENGTH + " lower-case hexadecimal characters, and this "
                    + "value holds " + nextCursor.length() + " characters");
        }
    }

    /**
     * Returns a rendering that counts the cards and names the paging state, and prints no card.
     *
     * <p>Every entry renders safely on its own, so the objection is not to any one card but to the
     * page. The rendering a record carries by default expands the whole list. One interpolated
     * response then becomes as many masked numbers and account identifiers as the page holds,
     * multiplied by every log line that touches it. A count says everything an operator reading a
     * paging problem needs, and a caller that wants a card can render that card.
     *
     * <p>The cursor appears in full. It is a card token, which carries no digit of a card number
     * and is not reversible, so a log line holding it discloses nothing and names the exact browse
     * position a paging problem asks about.
     *
     * @return one line naming the class, how many cards the page holds and the paging state
     */
    @Override
    public String toString() {
        return "CardListResponse[cards=" + cards.size() + " on this page"
                + ", nextPageExists=" + nextPageExists
                + ", nextCursor=" + (nextCursor == null ? "absent" : nextCursor) + "]";
    }
}
