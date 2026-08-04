package com.carddemo.card.domain;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads cards one page at a time and one card at a time.
 *
 * <p>Transformed from two Customer Information Control System (CICS) programs. The card list
 * program {@code app/cbl/COCRDLIC.cbl} supplies the paging, the two filters and the three-field
 * list row. The card detail program {@code app/cbl/COCRDSLC.cbl} supplies the two finders.
 *
 * <p>Paging reads one row past the page and derives the next-page flag from whether that row
 * arrived. {@link #listForward} and {@link #listBackward} carry that read. Neither runs a count
 * query and neither makes a second round trip.
 *
 * <p>Both filters travel to the database as query arguments, so the predicate runs ahead of the
 * row limit. {@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1411} holds the two
 * tests they carry, and the screen counter at {@code app/cbl/COCRDLIC.cbl:L1162-L1163} advances
 * only past those tests. A full page therefore holds filtered rows, and no method here filters a
 * list the repository has already returned.
 *
 * <p>This class writes nothing. A lookup that matches no row yields an empty page, an empty
 * {@link Optional} or an empty {@link List}, and no method here builds a message for a caller to
 * display.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Service
@Transactional(readOnly = true)
public class CardQueryService {

    /**
     * Rows on one page when a caller names no page size.
     *
     * <p>{@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178} declares this count inside {@code 01 WS-CONSTANTS} at
     * L176. Three further declarations in the same program carry the same count:
     * {@code WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES} at L75-L76,
     * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} at L86, and
     * {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} at L94.
     *
     * <p>The screen row table corroborates the count. {@code WS-ALL-ROWS PIC X(196)} at L253 holds
     * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} at L255, whose three members at L258 through L260
     * measure 11 plus 16 plus 1 characters. Seven rows of 28 characters fill 196 exactly.
     *
     * <p>A caller may name any page size from {@value #MIN_PAGE_SIZE} through
     * {@value #MAX_PAGE_SIZE}.
     */
    private static final int DEFAULT_PAGE_SIZE = 7;

    /**
     * Rows fetched beyond the page so that the next-page flag can be derived.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1197-L1205} performs one further {@code READNEXT} once the
     * page is full, and {@code app/cbl/COCRDLIC.cbl:L1191-L1216} reads the outcome of that one
     * read.
     */
    private static final int LOOKAHEAD_ROW_COUNT = 1;

    /** Smallest page size a caller may name. */
    private static final int MIN_PAGE_SIZE = 1;

    /** Largest page size a caller may name. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * The character a short identifier is left-padded with.
     *
     * <p>A filter holding this character and no other names no account and no card.
     * {@code app/cbl/COCRDLIC.cbl:L64} and {@code app/cbl/COCRDLIC.cbl:L68} declare the two blank
     * filter conditions {@code FLG-ACCTFILTER-BLANK} and {@code FLG-CARDFILTER-BLANK}, and
     * {@code 9500-FILTER-RECORDS} applies a filter only while its validity condition holds.
     */
    private static final char DIGIT_ZERO = '0';

    /** The highest character a {@code PIC 9} display field admits. */
    private static final char DIGIT_NINE = '9';

    private final CardRepository cardRepository;

    /**
     * Builds the read side over the card table.
     *
     * @param cardRepository the repository over table {@code card}
     * @throws NullPointerException if {@code cardRepository} is {@code null}
     */
    public CardQueryService(CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
    }

    /**
     * Returns the page of cards that follows one card number, in ascending card-number order.
     *
     * <p>Reproduces {@code 9000-READ-FORWARD} at {@code app/cbl/COCRDLIC.cbl:L1123}. Its
     * {@code STARTBR} at L1129-L1136 positions on a card number with {@code GTEQ} at L1133, and
     * its {@code READNEXT} at L1146-L1154 walks forward from that position.
     *
     * <p>The next-page flag comes from one extra row.
     * {@code app/cbl/COCRDLIC.cbl:L1191-L1216} tests the page as full at L1191 and reads once more
     * at L1197-L1205. That read sets {@code CA-NEXT-PAGE-EXISTS} at L1210-L1211 when it returns a
     * row, and {@code CA-NEXT-PAGE-NOT-EXISTS} at L1216 when it reports end of file.
     *
     * <p>This method asks the database for {@value #LOOKAHEAD_ROW_COUNT} row beyond the page and
     * reads the flag from the row count it receives.
     *
     * <p>The cursor is exclusive, so the row it names is never returned twice. A {@code null} or
     * blank cursor asks for the first page.
     *
     * @param afterCardNumber  the exclusive lower bound, or {@code null} for the first page
     * @param pageSize         rows on the page, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @param accountIdFilter  the account identifier to match, or {@code null} to match every
     *                         account
     * @param cardNumberFilter the card number to match, or {@code null} to match every card
     * @return the page, which holds no row when nothing matches
     * @throws IllegalArgumentException if {@code pageSize} falls outside {@value #MIN_PAGE_SIZE}
     *                                  through {@value #MAX_PAGE_SIZE}, or if any card number or
     *                                  account identifier exceeds the width its Picture clause
     *                                  declares
     */
    public CardPage listForward(String afterCardNumber, Integer pageSize, String accountIdFilter,
            String cardNumberFilter) {
        int rowsPerPage = resolvePageSize(pageSize);
        List<CardEntity> fetched = cardRepository.findPageForward(
                normalizeCursor(afterCardNumber),
                normalizeAccountIdFilter(accountIdFilter),
                normalizeCardNumberFilter(cardNumberFilter),
                Limit.of(rowsPerPage + LOOKAHEAD_ROW_COUNT));

        return buildPage(fetched, rowsPerPage, false);
    }

    /**
     * Returns the page of cards that precedes one card number, in ascending card-number order.
     *
     * <p>Reproduces {@code 9100-READ-BACKWARDS} at {@code app/cbl/COCRDLIC.cbl:L1264}, whose
     * {@code READPREV} loop at L1320-L1371 walks back from the cursor and whose {@code ENDBR} at
     * L1374-L1377 closes the browse.
     *
     * <p>The source skips the row its cursor names: the priming {@code READPREV} at L1294-L1302
     * consumes that row and L1304-L1307 discards it without projecting it. The exclusive cursor
     * below carries that skip.
     *
     * <p>The source hands its rows to the screen in ascending order, filling the row table from
     * the high index down at L1338-L1344 and stepping the index down at L1346. This method
     * reverses the descending rows the database returns, so a backward page and a forward page
     * reach a caller in the same order.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1284-L1288} presets the row counter and the next-page flag
     * before the browse reads anything. Those four statements are unconditional and no response
     * code reaches them. This method derives its flag from the same extra row that
     * {@link #listForward} uses.
     *
     * @param beforeCardNumber the exclusive upper bound, or {@code null} for the last page
     * @param pageSize         rows on the page, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @param accountIdFilter  the account identifier to match, or {@code null} to match every
     *                         account
     * @param cardNumberFilter the card number to match, or {@code null} to match every card
     * @return the page, which holds no row when nothing matches
     * @throws IllegalArgumentException if {@code pageSize} falls outside {@value #MIN_PAGE_SIZE}
     *                                  through {@value #MAX_PAGE_SIZE}, or if any card number or
     *                                  account identifier exceeds the width its Picture clause
     *                                  declares
     */
    public CardPage listBackward(String beforeCardNumber, Integer pageSize, String accountIdFilter,
            String cardNumberFilter) {
        int rowsPerPage = resolvePageSize(pageSize);
        List<CardEntity> fetched = cardRepository.findPageBackward(
                normalizeCursor(beforeCardNumber),
                normalizeAccountIdFilter(accountIdFilter),
                normalizeCardNumberFilter(cardNumberFilter),
                Limit.of(rowsPerPage + LOOKAHEAD_ROW_COUNT));

        return buildPage(fetched, rowsPerPage, true);
    }

    /**
     * Returns the card carrying one card number.
     *
     * <p>Reproduces {@code 9100-GETCARD-BYACCTCARD} at {@code app/cbl/COCRDSLC.cbl:L736}, whose
     * {@code EXEC CICS READ} at {@code app/cbl/COCRDSLC.cbl:L742-L750} names
     * {@code FILE(LIT-CARDFILENAME)} and keys on {@code RIDFLD(WS-CARD-RID-CARDNUM)}.
     * {@code app/cbl/COCRDSLC.cbl:L740} moves the supplied card number into that key, and the
     * account identifier move above it at L739 is commented out.
     *
     * <p>{@code KEYS(16 0)} at {@code app/jcl/CARDFILE.jcl:L54} declares the primary key of the
     * Virtual Storage Access Method (VSAM) dataset as sixteen bytes at offset zero. One card number
     * therefore reaches at most one row.
     *
     * <p>A missing row yields an empty {@link Optional}.
     * {@code app/cbl/COCRDSLC.cbl:L755-L761} takes its {@code DFHRESP(NOTFND)} branch and sets a
     * screen message behind the guard at L759. A caller here decides the status code and the
     * message text.
     *
     * <p>The argument carries the full Primary Account Number (PAN). A masked value matches no
     * row.
     *
     * @param cardNumber the full card number, at most {@value PicClause#CARD_NUM_WIDTH} characters
     * @return the matching card, or an empty {@link Optional} when the table holds none
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is blank or exceeds
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters
     */
    public Optional<CardEntity> findByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        return cardRepository.findByCardNumber(requireKey("cardNumber", cardNumber,
                PicClause.CARD_NUM_WIDTH));
    }

    /**
     * Returns every card belonging to one account.
     *
     * <p>Reproduces {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779}, whose
     * {@code EXEC CICS READ} at {@code app/cbl/COCRDSLC.cbl:L783-L791} names
     * {@code FILE(LIT-CARDFILENAME-ACCT-PATH)} and keys on {@code RIDFLD(WS-CARD-RID-ACCT-ID)}.
     * That literal names the Alternate Index (AIX) path over the card file.
     *
     * <p>{@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:L85} places an eleven-byte key at
     * offset 16, where {@code CARD-ACCT-ID} starts, and {@code NONUNIQUEKEY} at
     * {@code app/jcl/CARDFILE.jcl:L86} admits many rows under one key. The return type is a
     * {@link List} for that reason, and the rows arrive in no declared order.
     *
     * <p>No {@code PERFORM} statement in {@code app/cbl/COCRDSLC.cbl} names that paragraph. A
     * search for {@code 9150} returns its label at L779 and its exit at L810 and nothing else, and
     * {@code 9000-READ-DATA} at L726-L730 performs {@code 9100-GETCARD-BYACCTCARD} alone.
     *
     * <p>The account filter on {@link #listForward} reaches the same access path, so this method
     * carries a capability the source declares and never calls.
     *
     * <p>An account with no card yields an empty {@link List}.
     *
     * @param accountId the account identifier, at most {@value PicClause#CARD_ACCT_ID_WIDTH}
     *                  digits, left-padded with zeros to that width
     * @return every matching card, or an empty {@link List} when the table holds none
     * @throws NullPointerException     if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if {@code accountId} is blank, exceeds
     *                                  {@value PicClause#CARD_ACCT_ID_WIDTH} digits, or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    public List<CardEntity> findByAccountId(String accountId) {
        Objects.requireNonNull(accountId, "accountId is required");
        String key = requireKey("accountId", accountId, PicClause.CARD_ACCT_ID_WIDTH);
        requireDigitsOnly("accountId", key);
        return cardRepository.findByAccountId(key);
    }

    /**
     * Trims the fetched rows to the page and derives the next-page flag from what is left over.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1141} sets {@code CA-NEXT-PAGE-EXISTS} before the browse
     * reads anything, and two later statements clear it. L1216 clears it when the extra read
     * reports end of file, and L1235 clears it when the main read reaches end of file before the
     * page fills. Both clears hold when the extra row fails to arrive, which is the single test
     * below.
     *
     * <p>The two cursors come from the trimmed page, so neither names a row a caller never
     * received. The source keeps the same pair. {@code WS-CA-FIRST-CARDKEY} takes the first row at
     * L1173-L1181 going forward and at L1350-L1353 going back.
     *
     * <p>{@code WS-CA-LAST-CARDKEY} takes the last row at L1194-L1195. Two later statements write
     * it again: L1212-L1214 from the extra row, and L1236-L1237 on end of file.
     *
     * @param fetched           the rows the database returned, one more than the page holds when a
     *                          further page exists
     * @param rowsPerPage       rows the page holds
     * @param fetchedDescending {@code true} when {@code fetched} runs in descending card-number
     *                          order and the page needs reversing
     * @return the page
     */
    private static CardPage buildPage(List<CardEntity> fetched, int rowsPerPage,
            boolean fetchedDescending) {
        boolean nextPageExists = fetched.size() > rowsPerPage;
        List<CardEntity> onPage = nextPageExists ? fetched.subList(0, rowsPerPage) : fetched;
        List<CardEntity> ascending = fetchedDescending ? onPage.reversed() : onPage;

        List<CardListRow> rows = new ArrayList<>(ascending.size());
        for (CardEntity card : ascending) {
            rows.add(new CardListRow(card.getCardNumber(), card.getAccountId(),
                    card.getActiveStatus()));
        }

        String firstCardNumber = rows.isEmpty() ? null : rows.getFirst().cardNumber();
        String lastCardNumber = rows.isEmpty() ? null : rows.getLast().cardNumber();
        return new CardPage(rows, nextPageExists, firstCardNumber, lastCardNumber);
    }

    /**
     * Settles the page size, falling back to {@value #DEFAULT_PAGE_SIZE}.
     *
     * @param requestedPageSize the page size a caller named, or {@code null}
     * @return the page size to read with
     * @throws IllegalArgumentException if the named size falls outside {@value #MIN_PAGE_SIZE}
     *                                  through {@value #MAX_PAGE_SIZE}
     */
    private static int resolvePageSize(Integer requestedPageSize) {
        if (requestedPageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }

        int pageSize = requestedPageSize;
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize holds " + pageSize
                    + " and a page size runs from " + MIN_PAGE_SIZE + " through " + MAX_PAGE_SIZE);
        }
        return pageSize;
    }

    /**
     * Settles the browse position, treating a blank value as no position at all.
     *
     * @param cardNumber the card number the caller paged from, or {@code null}
     * @return the position, or {@code null} for the first or the last page
     * @throws IllegalArgumentException if the value exceeds
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters
     */
    private static String normalizeCursor(String cardNumber) {
        String trimmed = trimToNull(cardNumber);
        if (trimmed == null) {
            return null;
        }
        return requireKey("cursor", trimmed, PicClause.CARD_NUM_WIDTH);
    }

    /**
     * Settles the account identifier filter, treating a blank or all-zero value as absent.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1385-L1394} applies {@code CARD-ACCT-ID = CC-ACCT-ID} while
     * {@code FLG-ACCTFILTER-ISVALID} holds and applies nothing while it does not. A {@code null}
     * return carries that second case to the query, which then matches every account.
     *
     * @param accountId the account identifier a caller named, or {@code null}
     * @return the eleven-digit filter, or {@code null} to match every account
     * @throws IllegalArgumentException if the value exceeds
     *                                  {@value PicClause#CARD_ACCT_ID_WIDTH} digits or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    private static String normalizeAccountIdFilter(String accountId) {
        String trimmed = trimToNull(accountId);
        if (trimmed == null || isAllZeroDigits(trimmed)) {
            return null;
        }

        String filter = requireKey("accountIdFilter", trimmed, PicClause.CARD_ACCT_ID_WIDTH);
        requireDigitsOnly("accountIdFilter", filter);
        return filter;
    }

    /**
     * Settles the card number filter, treating a blank or all-zero value as absent.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1396-L1405} applies {@code CARD-NUM = CC-CARD-NUM-N} while
     * {@code FLG-CARDFILTER-ISVALID} holds. The comparison names the numeric side of the redefine
     * pair, so a value narrower than the stored key carries leading zeros into it. The padding
     * below carries that.
     *
     * <p>The filter holds the full Primary Account Number. A masked value matches no row.
     *
     * @param cardNumber the card number a caller named, or {@code null}
     * @return the sixteen-character filter, or {@code null} to match every card
     * @throws IllegalArgumentException if the value exceeds
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters
     */
    private static String normalizeCardNumberFilter(String cardNumber) {
        String trimmed = trimToNull(cardNumber);
        if (trimmed == null || isAllZeroDigits(trimmed)) {
            return null;
        }
        return requireKey("cardNumberFilter", trimmed, PicClause.CARD_NUM_WIDTH);
    }

    /**
     * Checks one identifier and left-pads it with zeros to the width its Picture clause declares.
     *
     * <p>The failure messages report a length and a position and carry no character of the value,
     * which keeps a card number out of every message this class produces.
     *
     * @param fieldName the field under check, named in any failure message
     * @param value     the value to check
     * @param width     the width the Picture clause declares
     * @return the value at exactly {@code width} characters
     * @throws IllegalArgumentException if the value holds no character or exceeds {@code width}
     */
    private static String requireKey(String fieldName, String value, int width) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " holds no character and names no row");
        }
        if (trimmed.length() > width) {
            throw new IllegalArgumentException(fieldName + " holds " + trimmed.length()
                    + " characters and its Picture clause declares " + width);
        }
        return padWithLeadingZeros(trimmed, width);
    }

    /**
     * Rejects a value holding a character outside {@code 0} through {@code 9}.
     *
     * <p>{@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} is a numeric display
     * field, so every one of its characters is a digit.
     *
     * @param fieldName the field under check, named in any failure message
     * @param value     the value to check
     * @throws IllegalArgumentException if any character falls outside {@code 0} through {@code 9}
     */
    private static void requireDigitsOnly(String fieldName, String value) {
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < DIGIT_ZERO || character > DIGIT_NINE) {
                throw new IllegalArgumentException(fieldName + " holds a character outside "
                        + DIGIT_ZERO + " through " + DIGIT_NINE + " at position "
                        + (position + 1));
            }
        }
    }

    /**
     * Left-pads a value with zeros to the given width.
     *
     * @param value the value to pad
     * @param width the width to reach
     * @return the padded value, or the value unchanged when it already reaches {@code width}
     */
    private static String padWithLeadingZeros(String value, int width) {
        int missing = width - value.length();
        if (missing <= 0) {
            return value;
        }
        return String.valueOf(DIGIT_ZERO).repeat(missing) + value;
    }

    /**
     * Strips a value and reports a blank one as absent.
     *
     * @param value the value to strip, which may be {@code null}
     * @return the stripped value, or {@code null} when it holds no character
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Reports whether a value holds the zero character and nothing else.
     *
     * @param value the stripped, non-empty value to test
     * @return {@code true} when every character is {@value #DIGIT_ZERO}
     */
    private static boolean isAllZeroDigits(String value) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != DIGIT_ZERO) {
                return false;
            }
        }
        return true;
    }

    /**
     * One row of the card list, carrying the three fields the source projects.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves {@code CARD-NUM}, {@code CARD-ACCT-ID} and
     * {@code CARD-ACTIVE-STATUS} into the screen row table, and
     * {@code app/cbl/COCRDLIC.cbl:L1338-L1344} repeats the same three on the backward path. The
     * source list holds no embossed name, no expiration date and no card verification value, and
     * this record holds none of the three either.
     *
     * @param cardNumber   the full card number, from {@code CARD-NUM PIC X(16)} at
     *                     {@code app/cpy/CVACT02Y.cpy:L5}. A caller masks this value through
     *                     {@code com.carddemo.cobol.PanMasker} before it reaches a response.
     * @param accountId    the account identifier, eleven digits with leading zeros, from
     *                     {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}
     * @param activeStatus the one-character active status, carried through with no
     *                     interpretation, from {@code CARD-ACTIVE-STATUS PIC X(01)} at
     *                     {@code app/cpy/CVACT02Y.cpy:L10}
     */
    public record CardListRow(String cardNumber, String accountId, String activeStatus) {

        /**
         * Checks that all three components hold a value.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public CardListRow {
            Objects.requireNonNull(cardNumber, "cardNumber is required");
            Objects.requireNonNull(accountId, "accountId is required");
            Objects.requireNonNull(activeStatus, "activeStatus is required");
        }

        /**
         * Returns a rendering that masks the card number and carries the other two components.
         *
         * <p>The card number is a Primary Account Number, and the rendering a record carries by
         * default would print all sixteen characters into any log line that interpolates a row.
         *
         * @return one line naming the class, the masked card number, the account identifier and
         *         the active status
         */
        @Override
        public String toString() {
            return "CardListRow[cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", accountId=" + accountId
                    + ", activeStatus=" + activeStatus + "]";
        }
    }

    /**
     * One page of the card list, with the paging state the next request needs.
     *
     * <p>The source holds the same state across its screen turns, in
     * {@code WS-CA-FIRST-CARDKEY} and {@code WS-CA-LAST-CARDKEY} at
     * {@code app/cbl/COCRDLIC.cbl:L230-L235} and in {@code WS-CA-NEXT-PAGE-IND} at
     * {@code app/cbl/COCRDLIC.cbl:L242-L244}. Both cursors are card numbers and neither is a page
     * ordinal, so an insert between two requests renumbers nothing.
     *
     * @param rows            the cards on this page, in ascending card-number order. The list is
     *                        copied on construction and admits no modification. A page matching no
     *                        card holds an empty list.
     * @param nextPageExists  {@code true} when a further page follows this one, derived from the
     *                        extra row at {@code app/cbl/COCRDLIC.cbl:L1191-L1216}
     * @param firstCardNumber the card number of the first row, which a backward request passes as
     *                        its cursor, or {@code null} when the page holds no row
     * @param lastCardNumber  the card number of the last row, which a forward request passes as its
     *                        cursor, or {@code null} when the page holds no row
     */
    public record CardPage(List<CardListRow> rows, boolean nextPageExists, String firstCardNumber,
            String lastCardNumber) {

        /**
         * Copies the row list and checks the cursors against it.
         *
         * <p>A {@code null} row list becomes an empty list.
         *
         * @throws NullPointerException     if the row list holds a {@code null} entry
         * @throws IllegalArgumentException if a page holding no row reports a further page or
         *                                  carries a cursor, or if a page holding rows carries no
         *                                  cursor
         */
        public CardPage {
            rows = rows == null ? List.of() : List.copyOf(rows);

            if (rows.isEmpty()) {
                if (nextPageExists) {
                    throw new IllegalArgumentException(
                            "a page holding no row reports no further page");
                }
                if (firstCardNumber != null || lastCardNumber != null) {
                    throw new IllegalArgumentException("a page holding no row carries no cursor");
                }
            } else if (firstCardNumber == null || lastCardNumber == null) {
                throw new IllegalArgumentException(
                        "a page holding rows carries the card number of its first and last row");
            }
        }

        /**
         * Returns a rendering that counts the rows, names the paging state and masks both cursors.
         *
         * <p>Each cursor is a full card number. The rendering a record carries by default prints
         * both of them, and expands the row list to as many more as the page holds. A count answers
         * what a paging problem asks.
         *
         * @return one line naming the class, the row count, the paging state and both masked
         *         cursors
         */
        @Override
        public String toString() {
            return "CardPage[rows=" + rows.size() + " on this page"
                    + ", nextPageExists=" + nextPageExists
                    + ", firstCardNumber=" + maskCursor(firstCardNumber)
                    + ", lastCardNumber=" + maskCursor(lastCardNumber) + "]";
        }

        /**
         * Masks a cursor, naming an absent one.
         *
         * @param cursor the cursor to mask, which may be {@code null}
         * @return the masked cursor, or {@code absent}
         */
        private static String maskCursor(String cursor) {
            return cursor == null ? "absent" : PanMasker.maskCardNumber(cursor);
        }
    }
}
