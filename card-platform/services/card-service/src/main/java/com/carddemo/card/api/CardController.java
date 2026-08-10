package com.carddemo.card.api;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.cobol.PanMasker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three routes of the card service: list one account's cards, read one card, update one card.
 *
 * <p>Transformed from three Customer Information Control System (CICS) transactions.
 * {@code app/cbl/COCRDLIC.cbl} lists cards a page at a time, {@code app/cbl/COCRDSLC.cbl} reads one
 * card, and {@code app/cbl/COCRDUPC.cbl} updates one. Each screen turn becomes one request and one
 * response. No conversation state survives a response. The paging position travels in the response
 * and returns on the next request, which is the role {@code WS-CA-LAST-CARD-NUM} at
 * {@code app/cbl/COCRDLIC.cbl:L231} fills between screen turns.
 *
 * <h2>Where an identifier may appear</h2>
 *
 * <p><strong>No card number appears in any path of this service.</strong> The two routes that name one
 * card carry the irreversible card token, and this controller resolves that token to the row before
 * anything else happens. Transaction {@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347-L348} is the
 * read and transaction {@code CCUP} at {@code app/csd/CARDDEMO.CSD:L367-L369} is the update; both
 * still reach the row by the primary key {@code app/cbl/COCRDSLC.cbl:L740} reads by, and only the
 * value a caller quotes has changed.
 *
 * <p>The reason is that a path is the one part of a request this application's redaction cannot
 * reach. It is written to an access log by the container, to a proxy log by whatever sits in front of
 * it, into a distributed trace as the span name, and into a browser history by the client. Masking a
 * number in a response body while putting it in the request line minimises nothing. The token is
 * derived by {@link PanMasker#cardToken} under a deployment-supplied key, is stored in
 * {@code card.card_token} under a unique constraint, and reaches at most one row.
 *
 * <p>The paging cursor of the list route travels in the {@value #CURSOR_HEADER} request header. That
 * cursor is the irreversible card token of a row. {@link CardQueryService} resolves the token to
 * the browse key that {@code app/cbl/COCRDLIC.cbl:L488-L489} reads back.
 *
 * <p>The account identifier is not a card number. It travels as the
 * {@value #ACCOUNT_ID_PARAMETER} query parameter. The filter chain of
 * {@code config/SecurityConfig} reads that parameter to decide ownership of the list route.
 *
 * <p>Every card number leaving this controller is masked. {@link CardSummary} and
 * {@link CardDetailResponse} both refuse an unmasked value in their own constructors, and
 * {@link PanMasker#maskCardNumber} produces what they accept. Lookups and filters run on the full
 * sixteen characters, before a response is built, as section 0.6.4 of the plan requires.
 *
 * <h2>What each route answers</h2>
 *
 * <p>The list route answers {@code 200} with an empty page when nothing matches.
 * {@code app/cbl/COCRDLIC.cbl:L1235} clears the next-page flag and the screen shows no row.
 *
 * <p>The read route answers {@code 200} with the card and {@code 404} when the table holds no such
 * row. The update route answers one of seven outcomes. {@link #statusOf(CardUpdateResponse)} maps
 * each onto a status code.
 *
 * <p>Ownership is decided by the filter chain of {@code config/SecurityConfig} on all three routes.
 * No handler here carries an authority rule.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping(CardController.BASE_PATH)
@Validated
public class CardController {

    /** Records one line per request, naming no card number and no cardholder name. */
    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    /** The collection all three routes sit under. */
    public static final String BASE_PATH = "/cards";

    /** Path of the two routes that name one card, below {@link #BASE_PATH}. */
    public static final String CARD_PATH = "/{cardToken}";

    /** Name of the path variable the two card-named routes capture. */
    public static final String CARD_TOKEN_VARIABLE = "cardToken";

    /** Route template the list route reports in a failing response. */
    public static final String COLLECTION_ROUTE = BASE_PATH;

    /** Route template the two card-named routes report in a failing response. */
    public static final String CARD_ROUTE = BASE_PATH + CARD_PATH;

    /**
     * Shape of the card token a path carries: exactly {@value PanMasker#CARD_TOKEN_LENGTH} lower-case
     * hexadecimal characters.
     *
     * <p>The source edit on a card number, {@code Card number if supplied must be a 16 digit number}
     * at {@code app/cbl/COCRDUPC.cbl:L193-L194}, is not replaced by this pattern. It still runs, in
     * {@link CardUpdateService}, on the number this controller resolves from the token, so a source
     * rule is applied where the source applied it and the target adds one shape check of its own in
     * front of it.
     */
    public static final String CARD_TOKEN_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /** Text a caller reads when the token in the path is not a card token. */
    public static final String CARD_TOKEN_MESSAGE =
            CardValidationMessages.ADDITIVE_CARD_TOKEN_MALFORMED;

    /**
     * Query parameter carrying the account whose cards a caller lists.
     *
     * <p>The filter chain of {@code config/SecurityConfig} reads this exact name to decide whether
     * the caller owns the account. The name is part of the access-control contract.
     */
    public static final String ACCOUNT_ID_PARAMETER = "accountId";

    /**
     * Request header carrying the paging position, which is the card token of a row.
     *
     * <p>{@link CardListResponse#nextCursor()} returns the token in the response body and the next
     * request sends it back here. The token names one browse position and carries no card-number
     * digit. The source kept the full card number in working storage at
     * {@code app/cbl/COCRDLIC.cbl:L231}.
     */
    public static final String CURSOR_HEADER = "X-Card-Cursor";

    /** Query parameter naming which way the browse walks from the cursor. */
    public static final String DIRECTION_PARAMETER = "direction";

    /** Direction value that walks forward, reproducing {@code 9000-READ-FORWARD}. */
    public static final String FORWARD_DIRECTION = "forward";

    /** Direction value that walks back, reproducing {@code 9100-READ-BACKWARDS}. */
    public static final String BACKWARD_DIRECTION = "backward";

    /**
     * Shape of the account identifier a caller lists by, from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}.
     *
     * <p>All eleven digits are required. Column {@code account_id} is {@code CHAR(11)} and matches
     * on text, so a shorter value matches no row. A malformed identifier answers {@code 422} and
     * not an empty page.
     */
    public static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Shape that refuses an account filter of eleven zero digits.
     *
     * <p>{@code CC-ACCT-ID-N EQUAL ZEROS} is the third absence condition of the source edit, at
     * {@code app/cbl/COCRDSLC.cbl:L653}, so eleven zeros name no account.
     * {@code app/cbl/COCRDLIC.cbl:L1385-L1394} then applies no filter and the browse walks every
     * account. The list route requires the account, so it refuses the value here rather than
     * answering a page of every card in the table.
     */
    public static final String ACCOUNT_ID_PRESENT_PATTERN = "^(?!0{11}$).*$";

    /** Shape of the irreversible paging token the card read side issues. */
    public static final String CURSOR_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /** Shape of the direction: one of the two values this controller accepts. */
    public static final String DIRECTION_PATTERN = "^(forward|backward)$";

    /**
     * Text a caller reads when it supplies no account to list by.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl} treats a blank account filter as no filter and emits no text
     * for this case. This route requires the account, and the rule that admits it reads that
     * parameter to decide ownership. The text is the one the two card programs use for a missing
     * account: {@link CardValidationMessages#PROMPT_FOR_ACCT}.
     */
    public static final String ACCOUNT_ID_ABSENT_MESSAGE = CardValidationMessages.PROMPT_FOR_ACCT;

    /**
     * Text a caller reads when the account it lists by is not eleven digits.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1298-L1301} refuses a non-numeric account filter with the
     * wording {@link CardValidationMessages#ACCOUNT_FILTER_NOT_NUMERIC} carries. The source tests
     * the blank condition before the character class, which is the order these two constants keep.
     */
    public static final String ACCOUNT_ID_MALFORMED_MESSAGE =
            CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC;

    /** Text a caller reads when the paging cursor is not a card token. */
    public static final String CURSOR_MESSAGE =
            CardValidationMessages.ADDITIVE_CARD_CURSOR_MALFORMED;

    /**
     * ADDITIVE. Text a caller reads when the direction names neither way.
     *
     * <p>No source text exists. The card list screen carries the direction as a function key, PF7
     * for back and PF8 for forward, which {@code app/cbl/COCRDLIC.cbl:L1055-L1075} reads, and a
     * terminal cannot deliver a third key. A request can name a third value, so this text answers
     * one.
     */
    public static final String DIRECTION_MESSAGE = "Direction must be forward or backward";

    /** Query parameter carrying the rows a caller wants on the page. */
    public static final String PAGE_SIZE_PARAMETER = "pageSize";

    /**
     * Smallest row count the list admits, matching the floor {@link CardQueryService} holds.
     *
     * <p>Declared here as well so the range is refused at the boundary rather than inside the read.
     * A range enforced only in the domain leaves the framework nothing to refuse, and the domain's
     * own refusal then arrives as a fault of this service rather than as a bad request.
     */
    public static final int MIN_PAGE_SIZE = 1;

    /** Largest row count the list admits, matching the ceiling {@link CardQueryService} holds. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Text a caller reads when the row count falls outside {@value #MIN_PAGE_SIZE} through 100. */
    public static final String PAGE_SIZE_MESSAGE =
            CardValidationMessages.ADDITIVE_PAGE_SIZE_OUT_OF_RANGE;

    /** Text a caller reads when the row count is no whole number, an empty value included. */
    public static final String PAGE_SIZE_NOT_A_NUMBER_MESSAGE =
            CardValidationMessages.ADDITIVE_PAGE_SIZE_NOT_A_NUMBER;

    /** Reads pages and single cards. */
    private final CardQueryService cardQueries;

    /** Runs the ordered edit chain and the write. */
    private final CardUpdateService cardUpdates;

    /**
     * Takes the read side and the update side.
     *
     * @param cardQueries the read side over table {@code card}
     * @param cardUpdates the update side, which owns the edit order and the write
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardController(CardQueryService cardQueries, CardUpdateService cardUpdates) {
        this.cardQueries = Objects.requireNonNull(cardQueries, "cardQueries is required");
        this.cardUpdates = Objects.requireNonNull(cardUpdates, "cardUpdates is required");
    }

    /**
     * Lists one page of the cards belonging to one account.
     *
     * <p>Reproduces {@code app/cbl/COCRDLIC.cbl}. The page size defaults to the seven rows
     * {@code WS-MAX-SCREEN-LINES} declares at {@code app/cbl/COCRDLIC.cbl:L177-L178}, which
     * {@link CardQueryService} owns, and the next-page flag comes from the one lookahead row that
     * {@code app/cbl/COCRDLIC.cbl:L1197-L1205} reads.
     *
     * <p>A forward request walks from the token's resolved browse position towards higher card
     * numbers, reproducing {@code 9000-READ-FORWARD} at {@code app/cbl/COCRDLIC.cbl:L1123}. A
     * backward request walks towards lower ones, reproducing {@code 9100-READ-BACKWARDS} at
     * {@code app/cbl/COCRDLIC.cbl:L1264}. Both cursors are exclusive, so no row is returned twice.
     * A request with no cursor asks for the first page going forward or the last page going back.
     *
     * <p>The account filter is required, and the rule that admits this route reads it. The card
     * filter of the source screen is absent here. A card filter in a query string would carry a
     * Primary Account Number.
     *
     * <p>All four request values are constrained here, so a value that misses its constraint is
     * refused by the framework and answered {@code 400} by {@code api/CardApiExceptionHandler}. The
     * row count carries {@value #MIN_PAGE_SIZE} through {@value #MAX_PAGE_SIZE}, the same range
     * {@link CardQueryService} reads with, and holding it in both places keeps a bad request a bad
     * request rather than a fault of this service.
     *
     * <p>Three of the four values are read as text, so a value a caller sent carrying no characters
     * is refused rather than read as absent. A parameter declared with a default takes that default
     * for {@code ?direction=} as readily as for an omitted {@code direction}, and a parameter
     * declared as a number binds {@code ?pageSize=} to {@code null}, which the read then fills with
     * seven. Either way a caller receives a {@code 200} that states nothing about what it did.
     *
     * @param accountId the eleven-digit account whose cards to list
     * @param cursor    the paging position from a previous response, or {@code null} for the first
     *                  or last page
     * @param direction {@value #FORWARD_DIRECTION} or {@value #BACKWARD_DIRECTION}, or {@code null}
     *                  for forward
     * @param pageSize  rows on the page, {@value #MIN_PAGE_SIZE} through {@value #MAX_PAGE_SIZE}, or
     *                  {@code null} for the seven the source screen holds
     * @return the page, empty when the account has no card
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public CardListResponse listCards(
            @RequestParam(name = ACCOUNT_ID_PARAMETER)
            @NotBlank(message = ACCOUNT_ID_ABSENT_MESSAGE)
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MALFORMED_MESSAGE)
            @Pattern(regexp = ACCOUNT_ID_PRESENT_PATTERN, message = ACCOUNT_ID_ABSENT_MESSAGE)
            String accountId,

            @RequestHeader(name = CURSOR_HEADER, required = false)
            @Pattern(regexp = CURSOR_PATTERN, message = CURSOR_MESSAGE)
            String cursor,

            @RequestParam(name = DIRECTION_PARAMETER, required = false)
            @Pattern(regexp = DIRECTION_PATTERN, message = DIRECTION_MESSAGE)
            String direction,

            @RequestParam(name = PAGE_SIZE_PARAMETER, required = false)
            String pageSize) {

        boolean backward = BACKWARD_DIRECTION.equals(direction);
        Integer rows = rowCountOf(pageSize);
        CardPage page = backward
                ? cardQueries.listBackward(cursor, rows, accountId, null)
                : cardQueries.listForward(cursor, rows, accountId, null);

        log.info("A card list answered {} rows, further page {}", page.rows().size(),
                page.nextPageExists());
        return responseOf(page, backward);
    }

    /**
     * Reads one card, named by its token in the path.
     *
     * <p>Reproduces {@code app/cbl/COCRDSLC.cbl}, transaction {@code CCDL} at
     * {@code app/csd/CARDDEMO.CSD:L347-L348}. {@code 9100-GETCARD-BYACCTCARD} at
     * {@code app/cbl/COCRDSLC.cbl:L736} moves the card number into the read key at
     * {@code app/cbl/COCRDSLC.cbl:L740} and reads the card file by card number at
     * {@code app/cbl/COCRDSLC.cbl:L742-L750}. The account move above the key at
     * {@code app/cbl/COCRDSLC.cbl:L739} is commented out, so one card value is the whole key and this
     * method reads by one card value too. That value is the token rather than the number, so no
     * Primary Account Number reaches a request line; {@code card_token} carries a unique constraint,
     * so it selects the same single row the primary key selects.
     *
     * <p>The masked number in the response is derived from the number the row holds, not from the
     * path. The path carries no digit of it.
     *
     * <p>The not-found branch at {@code app/cbl/COCRDSLC.cbl:L755-L761} sets
     * {@code DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDSLC.cbl:L760}, whose text
     * {@code app/cbl/COCRDUPC.cbl:L204} declares. The other candidate text at
     * {@code app/cbl/COCRDUPC.cbl:L202} has one set site, inside {@code 9150-GETCARD-BYACCT} at
     * {@code app/cbl/COCRDSLC.cbl:L779-L810}, which no {@code PERFORM} reaches, so it is
     * unreachable and this route never answers it.
     *
     * <p>Ownership is decided by the filter chain of {@code config/SecurityConfig}, which compares
     * this path value against the caller's {@code SCOPE_CARD_} authorities directly. A caller holding
     * no matching authority reads {@code 403} whether or not the row exists.
     *
     * @param cardToken the card token, {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     *                  characters
     * @return {@code 200} with the card, or {@code 404} carrying the text of
     *         {@code app/cbl/COCRDUPC.cbl:L204}
     */
    @GetMapping(path = CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> readCard(
            @PathVariable(name = CARD_TOKEN_VARIABLE)
            @Pattern(regexp = CARD_TOKEN_PATTERN, message = CARD_TOKEN_MESSAGE)
            String cardToken) {

        Optional<CardEntity> card = cardQueries.findByCardToken(cardToken);
        if (card.isEmpty()) {
            log.info("A card read named no stored card");
            return failure(HttpStatus.NOT_FOUND,
                    CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, CARD_ROUTE);
        }

        log.info("A card read answered one card");
        return ResponseEntity.ok(detailOf(card.get(),
                PanMasker.maskCardNumber(card.get().getCardNumber())));
    }

    /**
     * Updates one card, named by its token in the request path.
     *
     * <p>Reproduces {@code app/cbl/COCRDUPC.cbl}. {@link CardUpdateService} owns the order of the
     * checks, and the order decides which of seven answers a caller receives. This method owns only
     * the status code each answer carries, and the one read that turns a token into the number the
     * update service works on.
     *
     * <p><strong>Token-to-number resolution happens here and nowhere else.</strong> The full number
     * never leaves this service: it is read from the row, handed to
     * {@link CardUpdateService#updateCard(String, CardUpdateRequest)} in the same call, and masked in
     * every response and log line. A token that names no row answers the same
     * {@code cardNotFound()} outcome the update service reaches for a number that names no row, so a
     * caller cannot tell which of the two lookups missed and the seven outcomes stay seven.
     *
     * <p>The two card-number edits of the source remain reachable through the update service, on the
     * number resolved here: {@code PROMPT_FOR_CARD} at {@code app/cbl/COCRDUPC.cbl:L180} and
     * {@code Card number if supplied must be a 16 digit number} at
     * {@code app/cbl/COCRDUPC.cbl:L193-L194}. A stored row always satisfies both, so a caller of this
     * route reaches neither, and {@code domain/CardUpdateServiceTest} is where both are exercised.
     *
     * <p>No {@code @Valid} sits on this body. The source runs its read and its no-change comparison
     * before its field edits. A boundary validation answers a field message where the source
     * answers the text {@code app/cbl/COCRDUPC.cbl:L204} declares.
     * {@link CardUpdateService} applies the same constraints one property at a time, in source edit
     * order.
     *
     * <p>Six of the seven outcomes answer with the update body. The seventh is a write that failed
     * once the row was held, which answers {@code 503} with the failure body every other refusal of
     * this service carries, so one status on one operation carries one schema and one media type.
     * The text {@code app/cbl/COCRDUPC.cbl:L210} declares travels in the message member of that
     * body.
     *
     * @param cardToken the card token, {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     *                  characters
     * @param request   the five values the update carries
     * @return the outcome, with the status {@link #statusOf(CardUpdateResponse)} names
     */
    @PutMapping(path = CARD_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateCard(
            @PathVariable(name = CARD_TOKEN_VARIABLE)
            @Pattern(regexp = CARD_TOKEN_PATTERN, message = CARD_TOKEN_MESSAGE)
            String cardToken,
            @RequestBody CardUpdateRequest request) {
        Optional<CardEntity> named = cardQueries.findByCardToken(cardToken);
        if (named.isEmpty()) {
            log.info("A card update named no stored card");
            CardUpdateResponse missing = CardUpdateResponse.cardNotFound();
            return ResponseEntity.status(statusOf(missing)).body(missing);
        }

        // The row resolved above is handed on, so the service validates against it instead of
        // reading the same key a second time. The locked compare-and-swap read inside the update
        // transaction still happens: that read is the one the rewrite depends on, and a row read
        // before the transaction opened cannot stand in for it.
        CardUpdateResponse outcome =
                cardUpdates.updateCard(named.get().getCardNumber(), request, named.get());
        log.info("A card update answered outcome {}", outcome.outcome());

        HttpStatus status = statusOf(outcome);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return failure(status, outcome.message(), CARD_ROUTE);
        }
        return ResponseEntity.status(status).body(outcome);
    }

    /**
     * Reads the row count, keeping an absent value apart from an empty one.
     *
     * <p>Three cases. A parameter that did not arrive answers {@code null}, which the read fills
     * with the seven {@code WS-MAX-SCREEN-LINES} declares at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}. A parameter that arrived carrying no characters is a
     * value the caller sent, so it is refused. Any other value has to read as a whole number inside
     * {@value #MIN_PAGE_SIZE} through {@value #MAX_PAGE_SIZE}.
     *
     * <p>Refusing an empty value is why the count is read as text. A count declared as a number
     * binds {@code ?pageSize=} to {@code null}, so an empty value took the default and the caller
     * read a {@code 200} that stated nothing about what it did.
     *
     * <p>The submitted value reaches neither text. A count of six figures would put a run of digits
     * into a body whose route member exists to keep runs of digits out of it.
     *
     * @param pageSize the parameter as it arrived, or {@code null} where it did not arrive
     * @return the row count, or {@code null} to take the default the read holds
     * @throws CardQueryService.UnusableListRequest when the value is empty, reads as no whole
     *                                              number, or lies outside the range
     */
    private static Integer rowCountOf(String pageSize) {
        if (pageSize == null) {
            return null;
        }
        int rows;
        try {
            rows = Integer.parseInt(pageSize.strip());
        } catch (NumberFormatException notANumber) {
            throw new CardQueryService.UnusableListRequest(PAGE_SIZE_NOT_A_NUMBER_MESSAGE);
        }
        if (rows < MIN_PAGE_SIZE || rows > MAX_PAGE_SIZE) {
            throw new CardQueryService.UnusableListRequest(PAGE_SIZE_MESSAGE);
        }
        return rows;
    }

    /**
     * Names the status code one update outcome carries.
     *
     * <p>A rewritten row carries {@code 200}. Two outcomes carry {@code 422}: a failing edit, and
     * the no-change comparison at {@code app/cbl/COCRDUPC.cbl:L680-L682}. That comparison writes
     * nothing and reaches the edit exit at {@code app/cbl/COCRDUPC.cbl:L693}, and it carries the
     * text {@code app/cbl/COCRDUPC.cbl:L188} declares.
     *
     * <p>Two races carry {@code 409}: a row another writer changed first, and a row this service
     * could not lock. An absent row carries {@code 404}.
     *
     * <p>A write that failed after the lock was taken carries {@code 503}. The source reaches that
     * outcome from a file status other than normal on the {@code REWRITE} at
     * {@code app/cbl/COCRDUPC.cbl:L1483-L1491}, which is the datastore refusing the write rather
     * than this service holding a defect. The status names a condition a caller may retry, and
     * {@code 500} names one it may not. The outcome carries no {@code Retry-After}, since the
     * source measures no interval.
     *
     * @param outcome the outcome the update produced
     * @return the status code to send
     */
    static HttpStatus statusOf(CardUpdateResponse outcome) {
        return switch (outcome.outcome()) {
            case UPDATED -> HttpStatus.OK;
            case NO_CHANGE_DETECTED, VALIDATION_REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CARD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CHANGED_BEFORE_UPDATE, LOCK_NOT_ACQUIRED -> HttpStatus.CONFLICT;
            case UPDATE_FAILED_AFTER_LOCK -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * Builds the list response, masking every card number on the way out.
     *
     * <p>{@link CardListResponse} requires a cursor exactly when a further page exists, so the
     * cursor of a last page is dropped.
     *
     * <p>The cursor continues the browse in the direction it was walking. A forward page hands back
     * the token of its last card number. {@code app/cbl/COCRDLIC.cbl:L1194-L1195} writes that source
     * value into {@code WS-CA-LAST-CARDKEY}, and {@code app/cbl/COCRDLIC.cbl:L488-L489} reads it
     * back as the browse key. A backward page hands back the token of its first card number, which
     * {@code app/cbl/COCRDLIC.cbl:L1350-L1353} writes into {@code WS-CA-FIRST-CARDKEY}. The source
     * keeps both cursors, and this response carries whichever one the next request needs.
     *
     * <p>A masked card number names no row. The card token names exactly one row and carries no
     * card-number digit. {@link CardQueryService} resolves the token before issuing the database
     * browse.
     *
     * @param page     the page the read side returned
     * @param backward {@code true} when the browse was walking towards lower card numbers
     * @return the response body
     */
    private static CardListResponse responseOf(CardPage page, boolean backward) {
        List<CardSummary> cards = new ArrayList<>(page.rows().size());
        for (CardListRow row : page.rows()) {
            cards.add(new CardSummary(PanMasker.maskCardNumber(row.cardNumber()), row.accountId(),
                    row.activeStatus()));
        }
        String continuation = backward ? page.firstCardToken() : page.lastCardToken();
        String cursor = page.nextPageExists() ? continuation : null;
        return new CardListResponse(cards, page.nextPageExists(), cursor);
    }

    /**
     * Builds the detail response from one stored card.
     *
     * <p>The embossed name and the active status are carried as stored. Column
     * {@code embossed_name} is {@code bpchar(50)}, so the value arrives padded to the width
     * {@code CARD-EMBOSSED-NAME PIC X(50)} declares at {@code app/cpy/CVACT02Y.cpy:L8}. The
     * published event carries the same padded value. This method trims neither field, so every
     * surface reads one card the same way.
     *
     * @param card   the stored card
     * @param masked the masked card number, already produced from the full value
     * @return the response body
     */
    private static CardDetailResponse detailOf(CardEntity card, String masked) {
        return new CardDetailResponse(masked, card.getAccountId(), card.getEmbossedName(),
                card.getExpirationDate(), card.getActiveStatus());
    }

    /**
     * Builds one failing response, carrying a route template and never a resolved path.
     *
     * @param status  the status to send
     * @param message the one text the response carries
     * @param route   the route template of this endpoint
     * @return the response
     */
    private static ResponseEntity<ApiErrorResponse> failure(HttpStatus status, String message,
            String route) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(status.value(), message, route));
    }
}
