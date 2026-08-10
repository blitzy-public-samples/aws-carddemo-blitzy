package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.StatementTransactionRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.carddemo.notification.domain.NotificationRenderer.MAXIMUM_STATEMENT_ROWS;

/**
 * The one synchronous surface of the notification service.
 *
 * <p>One operation answers, {@code GET /notifications/{cardToken}}, returning one card's whole
 * history with its count and its total. The read model is keyed by the card token, which stands in
 * this platform for {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, the first
 * part of the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. {@code TRNX-ID PIC X(16)}
 * at {@code app/cpy/COSTM01.CPY:L23} is the second part. {@code KEYS(32 0)} at
 * {@code app/jcl/CREASTMT.JCL:L30} declares that key, and
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} sets the order
 * rows arrive in.
 *
 * <p><strong>No card number appears in this path.</strong> The route carries the token the read model
 * is already keyed on, so this controller reads by the value it was given and derives nothing. A path
 * is written to an access log by the container, to a proxy log by whatever sits in front of it, into a
 * distributed trace as the span name, and into a browser history by the client, and none of those is
 * reachable by this application's redaction. Masking a number in a response body while putting it in
 * the request line would minimise nothing.
 *
 * <p>The response describes the whole history of one card and carries one bounded page of it. The
 * count and the total cover every row the card holds, because {@code app/cbl/CBSTM03A.CBL} reads every
 * row of one card between two key breaks and totals all of them at
 * {@code app/cbl/CBSTM03A.CBL:L429}, so a count or a total that covered part of a history would be
 * two numbers about a statement nobody asked for. The entries are paged because a card's history has
 * no natural end: it grows by one row per posted transaction until retention removes rows, and a
 * response that carried all of them made the work and the size of one request a function of how long
 * a cardholder had been transacting. Two numbers over the key cost the same at any history length;
 * the entries walk the key {@value #DEFAULT_PAGE_SIZE} rows at a time by default.
 *
 * <p>A token with rows answers {@code 200}. A token with none answers {@code 404}, and that is a
 * consequence of holding no card number: the masked number in the response body is read from a row,
 * and with no row there is no number to mask and nothing truthful to put in the field.
 * {@code app/cbl/CBSTM03A.CBL:L318-L325} put the card in the statement header before reading any row
 * of it, which a path carrying the number could reproduce and a path carrying a token cannot. A
 * caller reaches this route only for a token it holds a {@code SCOPE_CARD_} authority for, so the
 * status discloses nothing it did not already know.
 *
 * <p>A path variable that misses its constraint answers {@code 400} through
 * {@link NotificationApiExceptionHandler}. No card number reaches a request line, a response body, a
 * refusal message or a log line: the masked form comes from the row and every refusal names the shape
 * it required.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping(NotificationHistoryController.BASE_PATH)
public class NotificationHistoryController {

    static final String BASE_PATH = "/notifications";

    /** The one path variable, named for the value it carries. */
    static final String CARD_TOKEN_PATH = "/{cardToken}";

    /** The route template a failing response reports in place of the resolved path. */
    static final String ROUTE_TEMPLATE = BASE_PATH + CARD_TOKEN_PATH;

    /**
     * Shape of the path variable: exactly {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters, which is the value {@code statement_transaction.card_token} holds and the value a
     * {@code SCOPE_CARD_} authority names.
     */
    static final String CARD_TOKEN_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /**
     * Text a refusal of the path variable carries. It names the shape and never the value submitted.
     *
     * <p>A value read back into a response body is a value copied into every log line built from that
     * body, and even a token is an identifier this service has no reason to echo.
     *
     * <p>The width is spelled in words rather than in figures on purpose. Every text a response of
     * this service can carry is letters, spaces and four punctuation marks, which is what makes it
     * impossible for a submitted card number, token or page size to reach a body through one, and
     * {@code api/ApiErrorResponseTest} holds that set. The card service words the same shape with a
     * figure, because its message inventory carries no such rule.
     *
     * <p>ADDITIVE. No source message corresponds, because the statement job read its key from a
     * sorted dataset rather than from an operator. {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30} declares that key and no program edited it.
     */
    static final String CARD_TOKEN_MESSAGE =
            "Card token must be sixty-four lower-case hexadecimal characters";

    /**
     * Text the {@code 404} carries when the read model holds no row under the token.
     *
     * <p>ADDITIVE, and it names no value. The source printed a statement header for a card with no
     * rows at {@code app/cbl/CBSTM03A.CBL:L318-L325}; this route cannot, because the masked number the
     * body carries is read from a row.
     */
    static final String NO_HISTORY_MESSAGE = "No statement history is held for that card";

    /** Request header carrying the paging position of a previous response. */
    public static final String CURSOR_HEADER = "X-Notification-Cursor";

    /** Query parameter naming how many entries one page carries. */
    public static final String PAGE_SIZE_PARAMETER = "pageSize";

    /** Fewest entries one page carries. */
    public static final int MIN_PAGE_SIZE = 1;

    /**
     * Most entries one page carries.
     *
     * <p>The same ceiling {@code NotificationRenderer.MAXIMUM_STATEMENT_ROWS} puts on one rendered
     * alert, so one number describes how many statement rows this service ever holds at once.
     */
    public static final int MAX_PAGE_SIZE = MAXIMUM_STATEMENT_ROWS;

    /** Entries a page carries where the request named no size. */
    public static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * Rows read beyond the page, to learn whether a further page exists without counting.
     *
     * <p>One row is enough: the read is ordered, so a row arriving after the page proves there is
     * more, and the page is served without it.
     */
    private static final int LOOKAHEAD_ROW_COUNT = 1;

    /**
     * Text a refusal of the page size carries. It names the range in words and never the value.
     *
     * <p>The bounds are spelled in words for the reason {@link #CARD_TOKEN_MESSAGE} gives: every text
     * this service can answer with is letters, spaces and four punctuation marks, which is what keeps
     * a submitted value out of a response body and out of every log line built from one.
     */
    static final String PAGE_SIZE_MESSAGE =
            "Page size must be a whole number from one to two hundred";

    /**
     * Text a refusal of the paging cursor carries. It names the shape and never the value.
     *
     * <p>The cursor is a transaction identifier, sixteen characters as
     * {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} declares.
     */
    static final String CURSOR_MESSAGE =
            "Paging cursor must be a transaction identifier of sixteen characters";

    private final StatementTransactionRepository statementTransactions;

    /**
     * Totals one card's rows, reproducing {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
     * {@code app/cbl/CBSTM03A.CBL:L429}.
     */
    private final NotificationService notifications;

    /**
     * Takes the read model this controller reads and the service that totals its rows.
     *
     * @param statementTransactions the read model repository
     * @param notifications the domain service owning the per-card total
     * @throws NullPointerException when an argument is null
     */
    public NotificationHistoryController(StatementTransactionRepository statementTransactions,
            NotificationService notifications) {
        this.statementTransactions =
                Objects.requireNonNull(statementTransactions, "statementTransactions is required");
        this.notifications = Objects.requireNonNull(notifications, "notifications is required");
    }

    /**
     * Returns one bounded page of a card's history in ascending transaction-identifier order, with the
     * count and the total of the card's whole history.
     *
     * <p>The token is the stored key, so it reaches the read directly and nothing derives anything
     * from it. No card number is supplied, stored, logged or returned; the masked form the body
     * carries is the {@code masked_card_number} column of the card's rows, which
     * {@code messaging/TransactionPostedConsumer} wrote from an event that already carried it masked.
     * Ownership is settled before this method runs, by the {@code SCOPE_CARD_} authority the security
     * chain requires for the token in the path, so it holds on every page alike: paging changes which
     * rows of one card are returned and never which card.
     *
     * <p>This read is bounded in both of its parts, and it did not used to be. It passed
     * {@link Limit#unlimited()} and then totalled in memory, so one request read every retained row
     * of a card, mapped all of them and copied the list: query work, heap and response bytes all grew
     * with one card's history and nothing capped any of them.
     *
     * <p>What replaces it keeps the source's own claim exactly. The count and the total still cover
     * the whole card, because {@code app/cbl/CBSTM03A.CBL} reads every row between two key breaks and
     * {@code app/cbl/CBSTM03A.CBL:L429} totals all of them, so a count or a total over one page would
     * describe a statement the source never produced. Both come from
     * {@link StatementTransactionRepository#totalsOfCard(String)}, one aggregate over the primary-key
     * prefix, and {@link NotificationService#totalOfCard(String,
     * StatementTransactionRepository.CardHistoryTotals)} turns that aggregate into the value the
     * source would have accumulated. The entries themselves arrive one page at a time through a keyset
     * walk of the same key.
     *
     * <p>The page reads {@value #LOOKAHEAD_ROW_COUNT} row beyond what it serves. That row is not
     * returned; its presence is what {@link NotificationHistoryResponse#nextPageExists()} reports, and
     * it is read rather than inferred from the count so that a row arriving between two requests
     * cannot stall the walk.
     *
     * <p>A token with no row answers {@code 404} carrying {@value #NO_HISTORY_MESSAGE}. Answering
     * {@code 200} would need a masked card number, and with no row there is none to read; inventing
     * one would put a card in a response that names no card this service holds. A cursor that has run
     * past the last row of a card answers {@code 404} for the same reason a token with no row does:
     * the walk is over and there is no page to describe.
     *
     * @param cardToken the card token, {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     *                  characters, the one value this route reads
     * @param cursor    the paging position from a previous response, or {@code null} for the first
     *                  page
     * @param pageSize  entries on the page as it arrived, {@value #MIN_PAGE_SIZE} through
     *                  {@value #MAX_PAGE_SIZE}, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @return {@code 200} with one page of the card's history, {@code 400} when the cursor or the page
     *         size misses its shape, or {@code 404} when the read model holds no row to describe
     */
    @GetMapping(path = CARD_TOKEN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> historyOfCard(
            @PathVariable
            @NotBlank(message = CARD_TOKEN_MESSAGE)
            @Pattern(regexp = CARD_TOKEN_PATTERN, message = CARD_TOKEN_MESSAGE)
            String cardToken,
            @RequestHeader(name = CURSOR_HEADER, required = false)
            String cursor,
            // Bound as text rather than as a number. A parameter declared as a number binds an empty
            // ?pageSize= to null, which would silently take the default where a caller sent something
            // it believed was a size, and it answers a type-mismatch failure rather than this route's
            // own wording. Both are settled here instead.
            @RequestParam(name = PAGE_SIZE_PARAMETER, required = false)
            String pageSize) {

        String continueAfter = cursorOf(cursor);
        if (cursor != null && continueAfter == null) {
            return badRequest(CURSOR_MESSAGE);
        }
        Integer rows = rowCountOf(pageSize);
        if (rows == null) {
            return badRequest(PAGE_SIZE_MESSAGE);
        }

        StatementTransactionRepository.CardHistoryTotals totals =
                statementTransactions.totalsOfCard(cardToken);
        if (totals.getTransactionCount() == 0L) {
            return notFound();
        }

        Limit limit = Limit.of(rows + LOOKAHEAD_ROW_COUNT);
        List<StatementTransactionEntity> read = continueAfter == null
                ? statementTransactions.findByIdCardTokenOrderByIdTransactionIdAsc(cardToken, limit)
                : statementTransactions
                        .findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(
                                cardToken, continueAfter, limit);
        if (read.isEmpty()) {
            return notFound();
        }

        boolean furtherPageExists = read.size() > rows;
        List<StatementTransactionEntity> page =
                furtherPageExists ? read.subList(0, rows) : read;

        return ResponseEntity.ok(NotificationHistoryResponse.fromCardRows(
                page.get(0).getMaskedCardNumber(),
                Math.toIntExact(totals.getTransactionCount()),
                notifications.totalOfCard(cardToken, totals),
                page,
                furtherPageExists));
    }

    /**
     * Settles the page size, answering {@code null} where the value cannot be one.
     *
     * @param pageSize the parameter as it arrived, or {@code null} where it did not arrive
     * @return the row count to serve, or {@code null} when the value is not a whole number in range
     */
    private static Integer rowCountOf(String pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        int rows;
        try {
            rows = Integer.parseInt(pageSize.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return rows < MIN_PAGE_SIZE || rows > MAX_PAGE_SIZE ? null : rows;
    }

    /**
     * Settles the paging cursor, answering {@code null} where the value cannot be one.
     *
     * <p>A cursor is a stored transaction identifier, so it is checked against that column's width
     * before it reaches a comparison. The value is never echoed.
     *
     * @param cursor the header as it arrived, or {@code null} where it did not arrive
     * @return the identifier to continue after, or {@code null} for a first page or a bad value
     */
    private static String cursorOf(String cursor) {
        if (cursor == null) {
            return null;
        }
        String supplied = cursor.strip();
        return supplied.length() == PicClause.TRAN_ID_WIDTH ? supplied : null;
    }

    /**
     * @param message the wording, which names a shape and never a submitted value
     * @return {@code 400} carrying that wording and the route template
     */
    private static ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message, ROUTE_TEMPLATE));
    }

    /**
     * @return {@code 404} carrying {@value #NO_HISTORY_MESSAGE} and the route template
     */
    private static ResponseEntity<ApiErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.NOT_FOUND.value(), NO_HISTORY_MESSAGE,
                        ROUTE_TEMPLATE));
    }
}
