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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the notification service.
 *
 * <p>One operation answers, {@code GET /notifications/{cardNumber}}, returning one card's whole
 * history with its count and its total. The path variable is the card number, which is
 * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, the first part of the group
 * {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. {@code TRNX-ID PIC X(16)} at
 * {@code app/cpy/COSTM01.CPY:L23} is the second part. {@code KEYS(32 0)} at
 * {@code app/jcl/CREASTMT.JCL:L30} declares that key, and
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} sets the order
 * rows arrive in.
 *
 * <p>The read model is keyed by the card token that {@link PanMasker#cardToken(String)} derives, and
 * {@link #historyOfCard(String)} derives it from the path value to read. The derivation is one-way and
 * keyed under a deployment-supplied key, so the stored key discloses no card number while the route
 * still names the card the source key names.
 *
 * <p>The response covers the whole history of one card and carries no paging parameter of any kind.
 * {@code app/cbl/CBSTM03A.CBL} reads every row of one card between two key breaks and totals all of
 * them at {@code app/cbl/CBSTM03A.CBL:L429}, so a count and a total that covered part of a history
 * would be two numbers about a statement nobody asked for.
 *
 * <p>A card with rows and a card with none both answer {@code 200}, the second with an empty array,
 * a count of {@code 0} and a total of {@code "0.00"}. A path variable that misses its constraint
 * answers {@code 400} through {@link NotificationApiExceptionHandler}. No card number reaches a
 * response body, a refusal message or a log line: the path value is masked before it is returned and
 * every refusal names the shape it required.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping(NotificationHistoryController.BASE_PATH)
public class NotificationHistoryController {

    static final String BASE_PATH = "/notifications";

    /** The one path variable, named for the value it carries. */
    static final String CARD_NUMBER_PATH = "/{cardNumber}";

    /** The route template a failing response reports in place of the resolved path. */
    static final String ROUTE_TEMPLATE = BASE_PATH + CARD_NUMBER_PATH;

    /**
     * Shape of the path variable: exactly {@value PanMasker#CARD_NUMBER_LENGTH} digits, the card
     * number that {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} holds.
     */
    static final String CARD_NUMBER_PATTERN = "^[0-9]{" + PanMasker.CARD_NUMBER_LENGTH + "}$";

    /**
     * Text a refusal of the path variable carries. It names the shape and never the value submitted.
     *
     * <p>The text names the shape and not the value submitted. A value read back into a response body
     * is a value copied into every log line built from that body, and a card number is the one value
     * this service exists to keep out of both.
     *
     * <p>ADDITIVE. No source message corresponds, because the statement job read its key from a
     * sorted dataset rather than from an operator. {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30} declares that key and no program edited it.
     */
    static final String CARD_NUMBER_MESSAGE = "Card number must be sixteen digits";

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
     * <p>The card number resolves the stored key through {@link PanMasker#cardToken(String)} and
     * reaches nothing else. It is not stored, not logged and not returned: the body carries the
     * masked form {@link PanMasker#maskCardNumber(String)} produces.
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
     * @param cardNumber the card number, sixteen digits, the one value this route reads
     * @return the card's history, empty when the read model holds no row for the card
     */
    @GetMapping(path = CARD_NUMBER_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationHistoryResponse historyOfCard(
            @PathVariable
            @NotBlank(message = CARD_NUMBER_MESSAGE)
            @Pattern(regexp = CARD_NUMBER_PATTERN, message = CARD_NUMBER_MESSAGE)
            String cardNumber) {
        List<StatementTransactionEntity> rows = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(PanMasker.cardToken(cardNumber),
                        Limit.unlimited());
        return NotificationHistoryResponse.fromCardRows(PanMasker.maskCardNumber(cardNumber),
                notifications.totalOf(rows), rows);
    }
}
