package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.StatementTransactionRepository;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the notification service.
 *
 * <p>One operation returns one card's transaction history: {@code GET /notifications/{cardToken}}.
 * The key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}, which holds
 * {@code TRNX-CARD-NUM} at {@code :L22} and {@code TRNX-ID} at {@code :L23}. The cluster declares
 * that key as {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} and the sort orders it as
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}.
 *
 * <p>The path variable is the card token, which is the value the key column holds and the value a
 * response returns as {@code cardToken}. A caller reads that value from one response and uses it as
 * the path of the next. The token is what makes the path unambiguous: a masked card number identifies
 * no single card, so a route keyed on one would answer with the merged history of every card sharing
 * those last four digits, and the ownership rule over it would admit a caller to a card it does not
 * hold. No card number of either form reaches a request line, an access log or a route.
 *
 * <p>The response is bounded by a row count rather than paged by offset, because an offset lets an
 * insert concurrent with a read shift a page the caller has already seen. A caller may name
 * {@code pageSize}; {@code carddemo.history.maximum-page-size} caps it and
 * {@code carddemo.history.default-page-size} applies when a caller names none. Both are held at or
 * under {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS}, the row ceiling one rendered alert
 * carries, and the bound reaches the database as a {@code LIMIT}, so neither this handler nor the
 * response grows with the life of the card.
 *
 * <p>Two status codes. A card with rows and a card with none both answer {@code 200}, the second
 * with an empty array, a count of {@code 0}, a total of {@code "0.00"} and a fully masked card
 * number. A path variable or a page size that misses its constraint answers {@code 400} through
 * {@link NotificationApiExceptionHandler}.
 *
 * <p>The framework validates the path variable and the query parameter themselves. A class annotated
 * {@code @Validated} would hand that work to a proxy, and the constraints would then hold only where
 * a proxy wraps this class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping(NotificationHistoryController.BASE_PATH)
public class NotificationHistoryController {

    /** The collection this controller serves. */
    static final String BASE_PATH = "/notifications";

    /** The one path variable, named for the value it carries. */
    static final String CARD_TOKEN_PATH = "/{cardToken}";

    /** The route template a failing response reports in place of the resolved path. */
    static final String ROUTE_TEMPLATE = BASE_PATH + CARD_TOKEN_PATH;

    /**
     * Shape of the path variable: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters, the card token that stands in for {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22}.
     */
    static final String CARD_TOKEN_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /** The one query parameter, named for the bound it carries. */
    static final String PAGE_SIZE_PARAMETER = "pageSize";

    /** Reads the card-keyed read model. */
    private final StatementTransactionRepository statementTransactions;

    /**
     * Totals one account's rows, reproducing {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
     * {@code app/cbl/CBSTM03A.CBL:L429}.
     */
    private final NotificationService notifications;

    /** The paging bounds this route answers under. */
    private final NotificationProperties.History history;

    /**
     * Takes the read model this controller reads, the service that totals its rows, and the bound
     * configuration.
     *
     * @param statementTransactions the read model repository
     * @param notifications the domain service owning the per-card total
     * @param properties the bound {@code carddemo} block, read for its paging bounds
     * @throws NullPointerException when an argument is null
     */
    public NotificationHistoryController(StatementTransactionRepository statementTransactions,
            NotificationService notifications, NotificationProperties properties) {
        this.statementTransactions =
                Objects.requireNonNull(statementTransactions, "statementTransactions is required");
        this.notifications = Objects.requireNonNull(notifications, "notifications is required");
        this.history = Objects.requireNonNull(properties, "properties is required").history();
    }

    /**
     * Returns one card's transactions, the count and the total of the rows returned.
     *
     * <p>The read names the resolved page size, so at most
     * {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} rows leave the database. Rows arrive in
     * ascending transaction-identifier order and are returned in that order.
     * {@link NotificationService#totalOf(List)} totals them, and
     * {@link NotificationHistoryResponse#fromCardRows(String, java.math.BigDecimal, List)} renders
     * the response. The total therefore covers every item the response carries and no item it does
     * not.
     *
     * @param cardToken the card token, which is the only form this route accepts
     * @param pageSize  rows to return at most, or {@code null} to take the configured default
     * @return the card's history, empty when the read model holds no row for the card
     */
    @GetMapping(path = CARD_TOKEN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationHistoryResponse historyOfCard(
            @PathVariable
            @NotBlank
            @Pattern(regexp = CARD_TOKEN_PATTERN)
            String cardToken,
            @RequestParam(name = PAGE_SIZE_PARAMETER, required = false)
            @Min(1)
            Integer pageSize) {
        List<StatementTransactionEntity> rows = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(cardToken,
                        Limit.of(history.resolvePageSize(pageSize)));
        return NotificationHistoryResponse.fromCardRows(cardToken, notifications.totalOf(rows),
                rows);
    }
}
