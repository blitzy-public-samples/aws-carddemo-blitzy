package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>The response covers the whole history of one card and carries no paging parameter of any kind.
 * {@code app/cbl/CBSTM03A.CBL} reads every row of one card between two key breaks and totals all of
 * them at {@code app/cbl/CBSTM03A.CBL:L429}, so a count and a total that covered part of a history
 * would be two numbers about a statement nobody asked for.
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
     * Returns one card's whole history in ascending transaction-identifier order, with its count and
     * its total.
     *
     * <p>The token is the stored key, so it reaches the read directly and nothing derives anything
     * from it. No card number is supplied, stored, logged or returned; the masked form the body
     * carries is the {@code masked_card_number} column of the rows read, which
     * {@code messaging/TransactionPostedConsumer} wrote from an event that already carried it masked.
     *
     * <p>The read passes {@link Limit#unlimited()}, so the answer covers every row of the card.
     * {@code app/cbl/CBSTM03A.CBL} reads every row between two key breaks and
     * {@code app/cbl/CBSTM03A.CBL:L429} totals all of them, so the count and the total this response
     * carries describe the same set of rows the source would have printed.
     *
     * <p>{@link NotificationService#totalOf(List)} totals the rows returned, and
     * {@link NotificationHistoryResponse#fromCardRows(String, java.math.BigDecimal, List)} builds the
     * body.
     *
     * <p>A token with no row answers {@code 404} carrying {@value #NO_HISTORY_MESSAGE}. Answering
     * {@code 200} would need a masked card number, and with no row there is none to read; inventing
     * one would put a card in a response that names no card this service holds.
     *
     * @param cardToken the card token, {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     *                  characters, the one value this route reads
     * @return {@code 200} with the card's history, or {@code 404} when the read model holds no row
     *         under the token
     */
    @GetMapping(path = CARD_TOKEN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> historyOfCard(
            @PathVariable
            @NotBlank(message = CARD_TOKEN_MESSAGE)
            @Pattern(regexp = CARD_TOKEN_PATTERN, message = CARD_TOKEN_MESSAGE)
            String cardToken) {
        List<StatementTransactionEntity> rows = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(cardToken, Limit.unlimited());
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse(HttpStatus.NOT_FOUND.value(), NO_HISTORY_MESSAGE,
                            ROUTE_TEMPLATE));
        }

        return ResponseEntity.ok(NotificationHistoryResponse.fromCardRows(
                rows.get(0).getMaskedCardNumber(), notifications.totalOf(rows), rows));
    }
}
