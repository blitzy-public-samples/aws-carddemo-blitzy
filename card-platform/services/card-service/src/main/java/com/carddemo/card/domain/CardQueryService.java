package com.carddemo.card.domain;

import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * arrived. {@link #listForward} and {@link #listBackward} carry that read. A request carrying a
 * cursor first resolves its opaque random token to the private card-number key; no client receives
 * that key as paging state.
 *
 * <p>Both filters travel to the database as query arguments, so the predicate runs ahead of the
 * row limit. The public card filter is an opaque token and resolves to the private card-number key
 * first. {@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1411} holds the two
 * tests, and the screen counter advances only past them.
 *
 * <p>This class writes nothing. A lookup that matches no row yields an empty page, an empty
 * {@link Optional} or an empty {@link List}, and no method here builds a message for a caller to
 * display.
 */
@Service
@Transactional(readOnly = true)
public class CardQueryService {

    /**
     * Raised when a list request names a paging position or a row count this class cannot browse
     * with.
     *
     * <p>The two failures this carries are a caller's, not this service's, and the difference decides
     * the status a caller reads. {@code api/CardApiExceptionHandler} answers this type with
     * {@code 400} and answers every other fault with {@code 500}, so a row count of zero is reported
     * as the bad request it is rather than as a fault of this service. Before this type existed both
     * left the same {@link IllegalArgumentException}, the handler could not tell one from the other,
     * and a caller reading {@code 500} had no way to learn that correcting its own request would fix
     * it.
     *
     * <p>The message is always one of the texts {@code api/dto/CardValidationMessages} declares and
     * never a value read from the request, because the handler puts it in the response body. The
     * submitted value reaches no message here: a row count of six figures would put a run of digits
     * into a body whose route member exists to keep runs of digits out of it.
     *
     * <p>It extends {@link IllegalArgumentException} because that is what an argument this class
     * cannot use is, and a caller of this service that catches the general type keeps catching it.
     */
    public static class UnusableListRequest extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * Builds one refusal.
         *
         * @param callerText one text of {@code api/dto/CardValidationMessages}, which reaches the
         *                   caller verbatim
         */
        public UnusableListRequest(String callerText) {
            super(callerText);
        }
    }

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

    /**
     * The shape a paging cursor holds, compiled once.
     *
     * <p>{@link PanMasker#CARD_TOKEN_PATTERN} is the one declaration of that shape across this
     * platform. Compiling it here costs one field and saves a compile on every request.
     */
    private static final Pattern CARD_TOKEN_SHAPE = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

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
     * <p>The cursor is a card token and never a card number. {@link #resolveCursor} turns it back
     * into the browse position, and {@link CardPage} documents why the two forms differ.
     *
     * @param afterCardToken   the card token of the last row of the previous page, or {@code null}
     *                         for the first page
     * @param pageSize         rows on the page, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @param accountIdFilter  the account identifier to match, or {@code null} to match every
     *                         account
     * @param cardNumberFilter the card number to match, or {@code null} to match every card
     * @return the page, which holds no row when nothing matches
     * @throws IllegalArgumentException if {@code pageSize} falls outside {@value #MIN_PAGE_SIZE}
     *                                  through {@value #MAX_PAGE_SIZE}, if the cursor is not a
     *                                  card token this service issued, or if either filter breaks
     *                                  the width or the character class its Picture clause declares
     */
    public CardPage listForward(String afterCardToken, Integer pageSize, String accountIdFilter,
            String cardNumberFilter) {
        int rowsPerPage = resolvePageSize(pageSize);
        String cursor = resolveCursor(afterCardToken);
        String accountId = normalizeAccountIdFilter(accountIdFilter);
        String cardNumber = normalizeCardNumberFilter(cardNumberFilter);

        if (cardNumber != null) {
            return oneCardPage(cardNumber, accountId, cursor, false, rowsPerPage);
        }

        Limit limit = Limit.of(rowsPerPage + LOOKAHEAD_ROW_COUNT);
        List<CardEntity> fetched;
        if (accountId == null) {
            fetched = cursor == null
                    ? cardRepository.findFirstPage(limit)
                    : cardRepository.findPageAfter(cursor, limit);
        } else {
            fetched = cursor == null
                    ? cardRepository.findFirstPageForAccount(accountId, limit)
                    : cardRepository.findPageAfterForAccount(accountId, cursor, limit);
        }
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
     * <p>The cursor is a card token and never a card number, exactly as on the forward path.
     *
     * @param beforeCardToken  the card token of the first row of the following page, or
     *                         {@code null} for the last page
     * @param pageSize         rows on the page, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @param accountIdFilter  the account identifier to match, or {@code null} to match every
     *                         account
     * @param cardNumberFilter the card number to match, or {@code null} to match every card
     * @return the page, which holds no row when nothing matches
     * @throws IllegalArgumentException if {@code pageSize} falls outside {@value #MIN_PAGE_SIZE}
     *                                  through {@value #MAX_PAGE_SIZE}, if the cursor is not a
     *                                  card token this service issued, or if either filter breaks
     *                                  the width or the character class its Picture clause declares
     */
    public CardPage listBackward(String beforeCardToken, Integer pageSize, String accountIdFilter,
            String cardNumberFilter) {
        int rowsPerPage = resolvePageSize(pageSize);
        String cursor = resolveCursor(beforeCardToken);
        String accountId = normalizeAccountIdFilter(accountIdFilter);
        String cardNumber = normalizeCardNumberFilter(cardNumberFilter);

        if (cardNumber != null) {
            return oneCardPage(cardNumber, accountId, cursor, true, rowsPerPage);
        }

        Limit limit = Limit.of(rowsPerPage + LOOKAHEAD_ROW_COUNT);
        List<CardEntity> fetched;
        if (accountId == null) {
            fetched = cursor == null
                    ? cardRepository.findLastPage(limit)
                    : cardRepository.findPageBefore(cursor, limit);
        } else {
            fetched = cursor == null
                    ? cardRepository.findLastPageForAccount(accountId, limit)
                    : cardRepository.findPageBeforeForAccount(accountId, cursor, limit);
        }
        return buildPage(fetched, rowsPerPage, true);
    }

    /**
     * Returns the page a card number filter selects, which holds one row or none.
     *
     * <p>{@code card_number} is the primary key, from {@code KEYS(16 0)} at
     * {@code app/jcl/CARDFILE.jcl:L54}, so the filter {@code CARD-NUM = CC-CARD-NUM-N} at
     * {@code app/cbl/COCRDLIC.cbl:L1397} selects at most one row. The account test at
     * {@code app/cbl/COCRDLIC.cbl:L1386} and the browse bound then apply to that one row, which is
     * what {@code 9500-FILTER-RECORDS} does to each row the browse hands it.
     *
     * <p>A page holding one row reports no further page, so the lookahead has nothing to read.
     *
     * @param cardNumber      the sixteen-character filter
     * @param accountId       the account filter, or {@code null} to match every account
     * @param cursor          the exclusive browse bound, or {@code null} for the first or last page
     * @param cursorIsUpper   {@code true} when the bound is an upper one, as a backward page carries
     * @param rowsPerPage     rows the page holds
     * @return the page, holding the matching row or no row at all
     */
    private CardPage oneCardPage(String cardNumber, String accountId, String cursor,
            boolean cursorIsUpper, int rowsPerPage) {
        List<CardEntity> matching = cardRepository.findByCardNumber(cardNumber)
                .filter(card -> accountId == null || accountId.equals(card.getAccountId()))
                .filter(card -> cursor == null || (cursorIsUpper
                        ? card.getCardNumber().compareTo(cursor) < 0
                        : card.getCardNumber().compareTo(cursor) > 0))
                .map(List::of)
                .orElseGet(List::of);

        return buildPage(matching, rowsPerPage, false);
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
     * <p>The value carries digits and nothing else. The storage Picture clause
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is alphanumeric, and every
     * input path in the source narrows it. {@code IF CC-CARD-NUM IS NOT NUMERIC} at
     * {@code app/cbl/COCRDUPC.cbl:L784} rejects the value, and the condition name
     * {@code SEARCHED-CARD-NOT-NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L193-L194} states the rule
     * as {@code Card number if supplied must be a 16 digit number}. Width alone would admit a
     * value the source rejects, and the alphanumeric key would then carry it into a lookup that
     * matches nothing.
     *
     * <p>No checksum applies and no card status is read. {@code app/cbl/COCRDUPC.cbl:L194} names
     * sixteen digits and nothing further, and AAP section 0.2.2 records that adding a checksum
     * check would change an outcome the source produces.
     *
     * @param cardNumber the full card number, at most {@value PicClause#CARD_NUM_WIDTH} digits,
     *                   left-padded with zeros to that width
     * @return the matching card, or an empty {@link Optional} when the table holds none
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is blank, exceeds
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters, or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    public Optional<CardEntity> findByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        String key = requireKey("cardNumber", cardNumber, PicClause.CARD_NUM_WIDTH);
        requireDigitsOnly("cardNumber", key);
        return cardRepository.findByCardNumber(key);
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
     * <p>The read is bounded at {@value #MAX_PAGE_SIZE} rows, the same ceiling a page carries. The
     * alternate index admits many rows under one key, so an unbounded read would materialise however
     * many cards one account has come to hold.
     *
     * @param accountId the account identifier, at most {@value PicClause#CARD_ACCT_ID_WIDTH}
     *                  digits, left-padded with zeros to that width
     * @return every matching card up to {@value #MAX_PAGE_SIZE} of them, or an empty {@link List}
     *         when the table holds none
     * @throws NullPointerException     if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if {@code accountId} is blank, exceeds
     *                                  {@value PicClause#CARD_ACCT_ID_WIDTH} digits, or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    public List<CardEntity> findByAccountId(String accountId) {
        Objects.requireNonNull(accountId, "accountId is required");
        String key = requireKey("accountId", accountId, PicClause.CARD_ACCT_ID_WIDTH);
        requireDigitsOnly("accountId", key);
        return cardRepository.findByAccountId(key, Limit.of(MAX_PAGE_SIZE));
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
     * <p>Both cursors carry the card token of their row and never its card number. The source
     * holds its two cursors in working storage that no terminal ever displays, so a card number
     * there reaches nobody. A cursor here travels to a caller and back, so it carries the token
     * instead. {@link #resolveCursor} turns it into the browse position the query needs.
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
        String firstCardToken = null;
        String lastCardToken = null;
        for (CardEntity card : ascending) {
            rows.add(new CardListRow(card.getCardNumber(), card.getAccountId(),
                    card.getActiveStatus()));
            if (firstCardToken == null) {
                firstCardToken = requireCardToken(card);
            }
            lastCardToken = requireCardToken(card);
        }

        return new CardPage(rows, nextPageExists, firstCardToken, lastCardToken);
    }

    /**
     * Reads the card token of one row and refuses a row that carries none.
     *
     * <p>Column {@code card_token} is {@code NOT NULL}, so a row read from the database always
     * carries one. A row built in memory carries one too, because
     * {@link com.carddemo.card.entity.CardEntity} derives it in its constructor. A {@code null}
     * therefore names a row that reached neither path, and a cursor built from it would name no
     * card.
     *
     * @param card the row to read
     * @return the card token
     * @throws IllegalStateException if the row carries no card token
     */
    private static String requireCardToken(CardEntity card) {
        String cardToken = card.getCardToken();
        if (cardToken == null) {
            throw new IllegalStateException(
                    "a card row carries no card token, so this page can carry no cursor");
        }
        return cardToken;
    }

    /**
     * Settles the page size, falling back to {@value #DEFAULT_PAGE_SIZE}.
     *
     * @param requestedPageSize the page size a caller named, or {@code null}
     * @return the page size to read with
     * @throws UnusableListRequest if the named size falls outside {@value #MIN_PAGE_SIZE}
     *                             through {@value #MAX_PAGE_SIZE}
     */
    private static int resolvePageSize(Integer requestedPageSize) {
        if (requestedPageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }

        int pageSize = requestedPageSize;
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            // The submitted count reaches no message. api/CardController constrains the same range
            // declaratively, so a request over the Hypertext Transfer Protocol is refused before it
            // arrives here; this guard holds the range for a caller of this class.
            throw new UnusableListRequest(CardValidationMessages.ADDITIVE_PAGE_SIZE_OUT_OF_RANGE);
        }
        return pageSize;
    }

    /**
     * Settles the browse position from a card token, treating a blank value as no position at all.
     *
     * <p>The caller supplies a card token, and the browse needs the card number that token names.
     * The lookup below is that translation, and it is the only path from a token back to a card
     * number: {@link PanMasker#cardToken(String)} is a digest, so nothing computes the number from
     * the token.
     *
     * <p>A token this service never issued reaches no row, and this method rejects it rather than
     * starting the browse over. Returning {@code null} for an unresolved cursor would answer a
     * request for page nine with page one, and the caller would read rows it already holds.
     *
     * @param cardToken the card token the caller paged from, or {@code null}
     * @return the card number the browse positions on, or {@code null} for the first or the last
     *         page
     * @throws UnusableListRequest if the value is not {@value PanMasker#CARD_TOKEN_LENGTH}
     *                             lower-case hexadecimal characters, or if it names no card
     */
    private String resolveCursor(String cardToken) {
        String trimmed = trimToNull(cardToken);
        if (trimmed == null) {
            return null;
        }

        requireCardTokenShape(trimmed);
        return cardRepository.findByCardToken(trimmed)
                .map(CardEntity::getCardNumber)
                .orElseThrow(() -> new UnusableListRequest(
                        CardValidationMessages.ADDITIVE_CARD_CURSOR_UNKNOWN));
    }

    /**
     * Rejects a cursor that is not shaped like a card token.
     *
     * <p>{@link PanMasker#CARD_TOKEN_PATTERN} is the one declaration of that shape. The same
     * pattern appears as the check constraint {@code ck_card_card_token_hex} in
     * {@code V1__schema.sql} and as the route constraint of the notification history endpoint, so
     * one change reaches every guard.
     *
     * <p>The check runs before the lookup so that a caller passing a card number where a token
     * belongs is told the shape is wrong rather than that the card is absent. Sixteen digits fail
     * the pattern on width and again on character class.
     *
     * @param value the stripped, non-empty cursor to check
     * @throws IllegalArgumentException if the value does not match
     *                                  {@link PanMasker#CARD_TOKEN_PATTERN}
     */
    private static void requireCardTokenShape(String value) {
        if (!CARD_TOKEN_SHAPE.matcher(value).matches()) {
            // The submitted length reaches no message. The header constraint of api/CardController
            // carries the same text, so a request over the Hypertext Transfer Protocol is refused
            // before it arrives here.
            throw new UnusableListRequest(CardValidationMessages.ADDITIVE_CARD_CURSOR_MALFORMED);
        }
    }

    /**
     * Settles the account identifier filter, treating a blank or all-zero value as absent.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1385-L1394} applies {@code CARD-ACCT-ID = CC-ACCT-ID} while
     * {@code FLG-ACCTFILTER-ISVALID} holds and applies nothing while it does not. A {@code null}
     * return carries that second case to the query, which then matches every account.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1017} tests {@code IF CC-ACCT-ID IS NOT NUMERIC} and rejects
     * the filter, and the class test runs after the width is settled. A value narrower than the
     * stored key is padded first, so the digits a caller sent are what the test reads.
     *
     * <p>The route reaches this method with a filter or with nothing. {@code GET /cards} requires the
     * account and refuses eleven zero digits at the boundary, through
     * {@code api/CardController#ACCOUNT_ID_PRESENT_PATTERN}, so no request over the Hypertext
     * Transfer Protocol names an account and reads a page of every account's cards. The blank and
     * all-zero branch below answers a direct call that names no filter.
     *
     * @param accountId the account identifier a caller named, or {@code null}
     * @return the eleven-digit filter, or {@code null} to match every account
     * @throws CardFilterRejectedException if the value exceeds
     *                                    {@value PicClause#CARD_ACCT_ID_WIDTH} digits or holds a
     *                                    character outside {@code 0} through {@code 9}
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
     * <p>The value carries digits and nothing else. {@code 2220-EDIT-CARD} at
     * {@code app/cbl/COCRDLIC.cbl:L1042-L1066} runs exactly two tests on this filter, in this
     * order. {@code app/cbl/COCRDLIC.cbl:L1042-L1044} treats low values, spaces and zeros as no
     * filter at all, which the blank and all-zero test above carries. Then
     * {@code IF CC-CARD-NUM IS NOT NUMERIC} at {@code app/cbl/COCRDLIC.cbl:L1052} rejects the
     * value with the message {@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER} at
     * {@code app/cbl/COCRDLIC.cbl:L1058}, and only the {@code ELSE} branch at
     * {@code app/cbl/COCRDLIC.cbl:L1063-L1065} sets {@code FLG-CARDFILTER-ISVALID}. A filter that
     * fails the numeric test therefore never reaches {@code 9500-FILTER-RECORDS}, and this method
     * rejects it for the same reason rather than sending it to the database.
     *
     * @param cardNumber the card number a caller named, or {@code null}
     * @return the sixteen-character filter, or {@code null} to match every card
     * @throws IllegalArgumentException if the value exceeds
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    private static String normalizeCardNumberFilter(String cardNumber) {
        String trimmed = trimToNull(cardNumber);
        if (trimmed == null || isAllZeroDigits(trimmed)) {
            return null;
        }

        String filter = requireKey("cardNumberFilter", trimmed, PicClause.CARD_NUM_WIDTH);
        requireDigitsOnly("cardNumberFilter", filter);
        return filter;
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
     * <p>Two kinds of field reach this check. {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6} is a numeric display field, so every one of its characters is
     * a digit by declaration. {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is
     * alphanumeric by declaration, and the source narrows it at every input path instead:
     * {@code app/cbl/COCRDLIC.cbl:L1052} on the list filter and
     * {@code app/cbl/COCRDUPC.cbl:L784} on the update screen both reject a non-numeric value.
     *
     * <p>The check reads the character class and nothing else. No checksum applies, per AAP
     * section 0.2.2.
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
     * Reports whether every character of a value is a digit.
     *
     * <p>This is the class test {@code IS NUMERIC} performs on an alphanumeric item. The source
     * applies it to both filters, at {@code app/cbl/COCRDLIC.cbl:L1017} and at
     * {@code app/cbl/COCRDLIC.cbl:L1052}.
     *
     * @param value the stripped, non-empty value to test
     * @return {@code true} when every character falls between {@code 0} and {@code 9}
     */
    private static boolean holdsDigitsOnly(String value) {
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < DIGIT_ZERO || character > DIGIT_NINE) {
                return false;
            }
        }
        return true;
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
         * default would print all sixteen characters into any log line that interpolates a row. The
         * account identifier is stable and names one account across every table of this platform, so
         * it is withheld beside it.
         *
         * @return one line naming the class, the masked card number, one withheld component and
         *         the active status
         */
        @Override
        public String toString() {
            return "CardListRow[cardNumber=" + PanMasker.maskCardNumber(cardNumber)
                    + ", accountId=" + EventEnvelope.WITHHELD
                    + ", activeStatus=" + activeStatus + "]";
        }
    }

    /**
     * One page of the card list, with the paging state the next request needs.
     *
     * <p>The source holds the same state across its screen turns, in
     * {@code WS-CA-FIRST-CARDKEY} and {@code WS-CA-LAST-CARDKEY} at
     * {@code app/cbl/COCRDLIC.cbl:L230-L235} and in {@code WS-CA-NEXT-PAGE-IND} at
     * {@code app/cbl/COCRDLIC.cbl:L242-L244}. Neither cursor is a page ordinal, so an insert
     * between two requests renumbers nothing.
     *
     * <p>Both cursors carry a card token where the source carries a card number, and the
     * difference follows from where the value travels. The source keeps its two keys in working
     * storage the terminal never receives, so a card number there reaches nobody. A cursor here
     * leaves the service in a response and returns in the next request, which makes it published
     * data: AAP section 0.6.4 admits only a tokenized or masked form there, and a masked form names
     * every card sharing four digits rather than one row.
     *
     * <p>A card token names exactly one row, reveals no digit of the card number and is not
     * reversible, so it carries the browse position without carrying the card.
     * {@link CardQueryService#resolveCursor} turns it back into that position.
     *
     * @param rows            the cards on this page, in ascending card-number order. The list is
     *                        copied on construction and admits no modification. A page matching no
     *                        card holds an empty list.
     * @param nextPageExists  {@code true} when a further page follows this one, derived from the
     *                        extra row at {@code app/cbl/COCRDLIC.cbl:L1191-L1216}
     * @param firstCardToken  the card token of the first row, which a backward request passes as
     *                        its cursor, or {@code null} when the page holds no row
     * @param lastCardToken   the card token of the last row, which a forward request passes as its
     *                        cursor, or {@code null} when the page holds no row
     */
    public record CardPage(List<CardListRow> rows, boolean nextPageExists, String firstCardToken,
            String lastCardToken) {

        /**
         * Copies the row list and checks the cursors against it.
         *
         * <p>A {@code null} row list becomes an empty list.
         *
         * <p>Each cursor is checked against {@link PanMasker#CARD_TOKEN_PATTERN}. The check is what
         * stops a card number reaching a caller through this component: sixteen digits fail the
         * pattern, so a page carrying one cannot be built at all.
         *
         * @throws NullPointerException     if the row list holds a {@code null} entry
         * @throws IllegalArgumentException if a page holding no row reports a further page or
         *                                  carries a cursor, if a page holding rows carries no
         *                                  cursor, or if a cursor is not a card token
         */
        public CardPage {
            rows = rows == null ? List.of() : List.copyOf(rows);

            if (rows.isEmpty()) {
                if (nextPageExists) {
                    throw new IllegalArgumentException(
                            "a page holding no row reports no further page");
                }
                if (firstCardToken != null || lastCardToken != null) {
                    throw new IllegalArgumentException("a page holding no row carries no cursor");
                }
            } else if (firstCardToken == null || lastCardToken == null) {
                throw new IllegalArgumentException(
                        "a page holding rows carries the card token of its first and last row");
            } else {
                requireCursorIsCardToken("firstCardToken", firstCardToken);
                requireCursorIsCardToken("lastCardToken", lastCardToken);
            }
        }

        /**
         * Returns a rendering that counts the rows and names the paging state.
         *
         * <p>The rendering a record carries by default expands the row list to as many entries as
         * the page holds. A count answers what a paging problem asks. Both cursors are card tokens,
         * which reveal no digit of a card number, so both appear in full.
         *
         * @return one line naming the class, the row count, the paging state and both cursors
         */
        @Override
        public String toString() {
            return "CardPage[rows=" + rows.size() + " on this page"
                    + ", nextPageExists=" + nextPageExists
                    + ", firstCardToken=" + namedCursor(firstCardToken)
                    + ", lastCardToken=" + namedCursor(lastCardToken) + "]";
        }

        /**
         * Rejects a cursor that is not shaped like a card token.
         *
         * @param componentName the component under check, named in any failure message
         * @param cursor        the cursor to check
         * @throws IllegalArgumentException if the value does not match
         *                                  {@link PanMasker#CARD_TOKEN_PATTERN}
         */
        private static void requireCursorIsCardToken(String componentName, String cursor) {
            if (!CARD_TOKEN_SHAPE.matcher(cursor).matches()) {
                throw new IllegalArgumentException(componentName + " holds " + cursor.length()
                        + " characters and a card token holds " + PanMasker.CARD_TOKEN_LENGTH
                        + " lower-case hexadecimal characters");
            }
        }

        /**
         * Names an absent cursor.
         *
         * @param cursor the cursor to render, which may be {@code null}
         * @return the cursor, or {@code absent}
         */
        private static String namedCursor(String cursor) {
            return cursor == null ? "absent" : cursor;
        }
    }
}
