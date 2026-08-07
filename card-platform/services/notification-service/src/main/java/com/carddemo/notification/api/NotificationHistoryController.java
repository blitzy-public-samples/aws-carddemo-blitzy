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
 * <p>One operation answers, {@code GET /notifications/{cardToken}}, returning one card's
 * transactions with their count and their total. The path variable is the card token, which stands
 * in for {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, the first part of the
 * group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. {@code TRNX-ID PIC X(16)} at
 * {@code app/cpy/COSTM01.CPY:L23} is the second part. {@code KEYS(32 0)} at
 * {@code app/jcl/CREASTMT.JCL:L30} declares that key, and
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} sets the order
 * rows arrive in.
 *
 * <p>A card with rows and a card with none both answer {@code 200}, the second with an empty array,
 * a count of {@code 0} and a total of {@code "0.00"}. A path variable or a page size that misses
 * its constraint answers {@code 400} through {@link NotificationApiExceptionHandler}. No card
 * number of either form reaches a route, a request or an access log.
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

    /** Smallest page size this route admits, the bound {@code @Min} carries. */
    static final int MINIMUM_PAGE_SIZE = 1;

    /**
     * Text a refusal of the path variable carries.
     *
     * <p>The route carries a card token and no account identifier, so the text names the card token.
     * It names the shape and not the value submitted: a value read back into a response body is a
     * value copied into every log line built from that body, and this route exists precisely to keep
     * a card number of either form out of both.
     *
     * <p>ADDITIVE. No source message corresponds, because the statement job read its key from a
     * sorted dataset rather than from an operator. {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30} declares that key and no program edited it.
     */
    static final String CARD_TOKEN_MESSAGE =
            "Card token must be sixty-four lower-case hexadecimal characters";

    /**
     * Text a refusal of the page size below its floor carries.
     *
     * <p>Above the ceiling nothing is refused: {@code NotificationProperties.History} clamps a page
     * size larger than the ceiling down to it, so only a value below {@value #MINIMUM_PAGE_SIZE}
     * reaches this text.
     *
     * <p>ADDITIVE. A 3270 screen delivered its row count as a compile-time constant, so no operator
     * could supply one and no edit existed to refuse one.
     */
    static final String PAGE_SIZE_MESSAGE = "Page size must be at least one";

    /**
     * Text a refusal of a page size that is no whole number carries.
     *
     * <p>{@link #PAGE_SIZE_MESSAGE} answers a number the floor does not admit. This text answers a
     * value that is no number at all, which is a different failure and reaches the framework
     * earlier: a query string is text, and the page size is the one value of this route that is not
     * text, so it is converted before any constraint runs and a conversion that fails never reaches
     * one. A value too large for the type it converts to fails the same way.
     *
     * <p>ADDITIVE, and the same text {@code card-service} answers for the same failure on
     * {@code GET /cards}, so a caller of either list reads one wording.
     */
    static final String PAGE_SIZE_NOT_A_NUMBER_MESSAGE = "Page size must be a whole number";

    /** Reads the card-keyed read model. */
    private final StatementTransactionRepository statementTransactions;

    /**
     * Totals one card's rows, reproducing {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
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
     * Returns one card's transactions in ascending transaction-identifier order, with their count
     * and their total.
     *
     * <p>The read names the resolved page size, so at most
     * {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} rows leave the database.
     * {@link NotificationService#totalOf(List)} totals the rows returned, and
     * {@link NotificationHistoryResponse#fromCardRows(String, java.math.BigDecimal, List)} builds
     * the body.
     *
     * @param cardToken the card token, the one form this route accepts
     * @param pageSize  rows to return at most, or {@code null} to take the configured default
     * @return the card's history, empty when the read model holds no row for the card
     */
    @GetMapping(path = CARD_TOKEN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationHistoryResponse historyOfCard(
            @PathVariable
            @NotBlank(message = CARD_TOKEN_MESSAGE)
            @Pattern(regexp = CARD_TOKEN_PATTERN, message = CARD_TOKEN_MESSAGE)
            String cardToken,
            @RequestParam(name = PAGE_SIZE_PARAMETER, required = false)
            @Min(value = MINIMUM_PAGE_SIZE, message = PAGE_SIZE_MESSAGE)
            Integer pageSize) {
        List<StatementTransactionEntity> rows = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(cardToken,
                        Limit.of(history.resolvePageSize(pageSize)));
        return NotificationHistoryResponse.fromCardRows(cardToken, notifications.totalOf(rows),
                rows);
    }
}
