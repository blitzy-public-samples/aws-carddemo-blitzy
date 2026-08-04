package com.carddemo.notification.api;

import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.StatementTransactionRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the notification service.
 *
 * <p>One operation returns one card's transaction history: {@code GET /notifications/{maskedCardNumber}}.
 * The key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}, which holds
 * {@code TRNX-CARD-NUM} at {@code :L22} and {@code TRNX-ID} at {@code :L23}. The cluster declares
 * that key as {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} and the sort orders it as
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}.
 *
 * <p>The path variable is the masked card number, which is the value the key column holds and the
 * value a response returns. A caller reads {@code cardNumber} from one response and uses it as the
 * path of the next. No full Primary Account Number (PAN) reaches a request line, an access log or a
 * response body.
 *
 * <p>Two status codes. A card with rows and a card with none both answer {@code 200}, the second
 * with an empty array, a count of {@code 0} and a total of {@code "0.00"}. A path variable that
 * misses its pattern answers {@code 400} through {@link NotificationApiExceptionHandler}.
 *
 * <p>The framework validates the path variable itself. A class annotated {@code @Validated} would
 * hand that work to a proxy, and the constraint would then hold only where a proxy wraps this
 * class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@RestController
@RequestMapping(NotificationHistoryController.BASE_PATH)
public class NotificationHistoryController {

    /** The collection this controller serves. */
    static final String BASE_PATH = "/notifications";

    /** The one path variable, named for the value it carries. */
    static final String CARD_NUMBER_PATH = "/{maskedCardNumber}";

    /** The route template a failing response reports in place of the resolved path. */
    static final String ROUTE_TEMPLATE = BASE_PATH + CARD_NUMBER_PATH;

    /**
     * Shape of the path variable: twelve mask characters then the last four digits, which is the
     * masked form of {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}.
     */
    static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Reads the card-keyed read model. */
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
     */
    public NotificationHistoryController(StatementTransactionRepository statementTransactions,
            NotificationService notifications) {
        this.statementTransactions = statementTransactions;
        this.notifications = notifications;
    }

    /**
     * Returns one card's transactions, the count and the per-card total.
     *
     * <p>Rows arrive in ascending transaction-identifier order and are returned in that order.
     * {@link NotificationService#totalOf(List)} totals them, and
     * {@link NotificationHistoryResponse#fromCardRows(String, java.math.BigDecimal, List)} renders
     * the response.
     *
     * @param maskedCardNumber the masked card number, which is the only form this route accepts
     * @return the card's history, empty when the read model holds no row for the card
     */
    @GetMapping(path = CARD_NUMBER_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationHistoryResponse historyOfCard(
            @PathVariable
            @NotBlank
            @Pattern(regexp = MASKED_CARD_NUMBER_PATTERN)
            String maskedCardNumber) {
        List<StatementTransactionEntity> rows = statementTransactions
                .findByIdCardNumberOrderByIdTransactionIdAsc(maskedCardNumber);
        return NotificationHistoryResponse.fromCardRows(maskedCardNumber,
                notifications.totalOf(rows), rows);
    }
}
