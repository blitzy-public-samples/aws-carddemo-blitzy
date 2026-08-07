package com.carddemo.card.api;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailRequest;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.SecurityConfig;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>No route here carries a card number in its path or in its query string. The two routes that
 * name one card take it in the request body, so the read is a {@code POST} that changes nothing.
 * The paging cursor of the list route travels in the {@value #CURSOR_HEADER} request header. That
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
 * <p>The read route answers {@code 200} with the card, {@code 404} when the table holds no such
 * row, and {@code 422} when an edit refuses a submitted value. The update route answers one of
 * seven outcomes. {@link #statusOf(CardUpdateResponse)} maps each onto a status code.
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

    /** Path of the read route, below {@link #BASE_PATH}. */
    public static final String DETAIL_PATH = "/detail";

    /** Route template the list route and the update route report in a failing response. */
    public static final String COLLECTION_ROUTE = BASE_PATH;

    /** Route template the read route reports in a failing response. */
    public static final String DETAIL_ROUTE = BASE_PATH + DETAIL_PATH;

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

    /** Reads pages and single cards. */
    private final CardQueryService cardQueries;

    /** Runs the ordered edit chain and the write. */
    private final CardUpdateService cardUpdates;

    /** Decides whether the caller of the read route owns the card it named. */
    private final SecurityConfig.CardOwnership cardOwnership;

    /**
     * Takes the read side, the update side and the ownership predicate.
     *
     * @param cardQueries   the read side over table {@code card}
     * @param cardUpdates   the update side, which owns the edit order and the write
     * @param cardOwnership the predicate {@code config/SecurityConfig} declares
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardController(CardQueryService cardQueries, CardUpdateService cardUpdates,
            SecurityConfig.CardOwnership cardOwnership) {
        this.cardQueries = Objects.requireNonNull(cardQueries, "cardQueries is required");
        this.cardUpdates = Objects.requireNonNull(cardUpdates, "cardUpdates is required");
        this.cardOwnership = Objects.requireNonNull(cardOwnership, "cardOwnership is required");
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
     * row count carries {@value #MIN_PAGE_SIZE} through {@value #MAX_PAGE_SIZE}, the range
     * {@link CardQueryService} reads with. That range is held in both places on purpose: a range
     * enforced only in the read leaves the framework nothing to refuse, and the read's own refusal
     * then reaches a caller as a fault of this service rather than as the bad request it is.
     *
     * @param accountId the eleven-digit account whose cards to list
     * @param cursor    the paging position from a previous response, or {@code null} for the first
     *                  or last page
     * @param direction {@value #FORWARD_DIRECTION} or {@value #BACKWARD_DIRECTION}, defaulting to
     *                  forward
     * @param pageSize  rows on the page, {@value #MIN_PAGE_SIZE} through {@value #MAX_PAGE_SIZE}, or
     *                  {@code null} for the seven the source screen holds
     * @return the page, empty when the account has no card
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public CardListResponse listCards(
            @RequestParam(name = ACCOUNT_ID_PARAMETER)
            @NotBlank(message = ACCOUNT_ID_ABSENT_MESSAGE)
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MALFORMED_MESSAGE)
            String accountId,

            @RequestHeader(name = CURSOR_HEADER, required = false)
            @Pattern(regexp = CURSOR_PATTERN, message = CURSOR_MESSAGE)
            String cursor,

            @RequestParam(name = DIRECTION_PARAMETER, required = false,
                    defaultValue = FORWARD_DIRECTION)
            @Pattern(regexp = DIRECTION_PATTERN, message = DIRECTION_MESSAGE)
            String direction,

            @RequestParam(name = PAGE_SIZE_PARAMETER, required = false)
            @Min(value = MIN_PAGE_SIZE, message = PAGE_SIZE_MESSAGE)
            @Max(value = MAX_PAGE_SIZE, message = PAGE_SIZE_MESSAGE)
            Integer pageSize) {

        boolean backward = BACKWARD_DIRECTION.equals(direction);
        CardPage page = backward
                ? cardQueries.listBackward(cursor, pageSize, accountId, null)
                : cardQueries.listForward(cursor, pageSize, accountId, null);

        log.info("A card list answered {} rows, further page {}", page.rows().size(),
                page.nextPageExists());
        return responseOf(page, backward);
    }

    /**
     * Reads one card, named by the full card number in the request body.
     *
     * <p>Reproduces {@code app/cbl/COCRDSLC.cbl}. Two edits run first, in the order
     * {@code 2200-EDIT-MAP-INPUTS} performs them at {@code app/cbl/COCRDSLC.cbl:L630-L634}, and the
     * cross-field rule at {@code app/cbl/COCRDSLC.cbl:L637-L640} overrides both when neither value
     * arrived. {@link CardDetailRequest} carries the two field constraints and this method carries
     * the two rules that span fields.
     *
     * <p>The read keys on the card number alone.
     * {@code app/cbl/COCRDSLC.cbl:L740} moves the card number into the read key. The account move
     * above it at {@code app/cbl/COCRDSLC.cbl:L739} is commented out, so the source edits the
     * account and then keys on the card. This method keeps that key and compares the account after
     * the read. A row whose stored account differs from the supplied account answers as an absent
     * row, with the same status and the same text.
     *
     * <p>That account comparison departs from the commented-out line at
     * {@code app/cbl/COCRDSLC.cbl:L739}. The source paragraph {@code 9100-GETCARD-BYACCTCARD} and
     * the text {@code app/cbl/COCRDUPC.cbl:L204} declares both name a combination read. The
     * register of flagged rules carries the departure with these citations.
     *
     * <p>This method decides ownership, and the filter chain of {@code config/SecurityConfig} does
     * not. The identifier sits in the body, and a chain reading the body consumes the stream this
     * method needs. The predicate is {@link SecurityConfig#cardOwnership()}, which names the
     * authority by the card token derived from the full number. An entitlement admits one card and
     * not every card ending in the same four digits.
     *
     * <p>A caller holding no matching scope reads {@code 403} whether or not the row exists. The
     * refusal carries the same text as an absent row, so the two answers differ in status alone.
     *
     * <p>No {@code @Valid} sits on this body. The source tests each field for absence before it
     * tests its character class, then applies one rule across both fields. An unordered violation
     * set answers the wrong one of four texts.
     * {@link #firstSearchFailure(CardDetailRequest)} applies all four in source order, and
     * {@link CardDetailRequest} declares the same constraints. A test holds the two to agreement.
     *
     * @param request the account identifier and the full card number
     * @return {@code 200} with the card. An edit that refuses a value answers {@code 422}. A caller
     *         that does not own the card reads {@code 403}. An absent row, or one belonging to
     *         another account, answers {@code 404}
     */
    @PostMapping(path = DETAIL_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> readCard(@RequestBody CardDetailRequest request) {
        String searchFailure = firstSearchFailure(request);
        if (searchFailure != null) {
            log.info("A card read supplied a search condition an edit refused");
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, searchFailure, DETAIL_ROUTE);
        }

        String cardNumber = request.cardNumber();
        if (!cardOwnership.ownsCard(cardNumber)) {
            log.info("A card read named a card the caller does not own");
            return failure(HttpStatus.FORBIDDEN,
                    CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, DETAIL_ROUTE);
        }

        Optional<CardEntity> card = cardQueries.findByCardNumber(cardNumber);
        if (card.isEmpty() || !namesTheSameAccount(card.get(), request.accountId())) {
            log.info("A card read named no stored card of that account");
            return failure(HttpStatus.NOT_FOUND,
                    CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, DETAIL_ROUTE);
        }

        log.info("A card read answered one card");
        return ResponseEntity.ok(detailOf(card.get(), PanMasker.maskCardNumber(cardNumber)));
    }

    /**
     * Updates one card, named by the full card number in the request body.
     *
     * <p>Reproduces {@code app/cbl/COCRDUPC.cbl}. {@link CardUpdateService} owns the order of the
     * checks, and the order decides which of seven answers a caller receives. This method owns only
     * the status code each answer carries.
     *
     * <p>No {@code @Valid} sits on this body. The source runs its read and its no-change comparison
     * before its field edits. A boundary validation answers a field message where the source
     * answers the text {@code app/cbl/COCRDUPC.cbl:L204} declares.
     * {@link CardUpdateService} applies the same constraints one property at a time, in source edit
     * order.
     *
     * @param request the card number and the five values the update carries
     * @return the outcome, with the status {@link #statusOf(CardUpdateResponse)} names
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CardUpdateResponse> updateCard(
            @RequestBody CardUpdateRequest request) {
        CardUpdateResponse outcome = cardUpdates.updateCard(request);
        log.info("A card update answered outcome {}", outcome.outcome());
        return ResponseEntity.status(statusOf(outcome)).body(outcome);
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
     * could not lock. An absent row carries {@code 404}. A fault inside this service carries
     * {@code 500}, which a caller may retry.
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
            case UPDATE_FAILED_AFTER_LOCK -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Applies the four edits of the read route in the order the source performs them.
     *
     * <p>{@code 2200-EDIT-MAP-INPUTS} performs {@code 2210-EDIT-ACCOUNT} at
     * {@code app/cbl/COCRDSLC.cbl:L630-L631} and {@code 2220-EDIT-CARD} at
     * {@code app/cbl/COCRDSLC.cbl:L633-L634}. Each of those two tests absence before it tests the
     * character class: {@code app/cbl/COCRDSLC.cbl:L651} then {@code L665} for the account, and
     * {@code app/cbl/COCRDSLC.cbl:L691} then {@code L706} for the card. Every write to
     * {@code WS-RETURN-MSG} after the first sits behind the {@code WS-RETURN-MSG-OFF} guard, so the
     * first failing edit owns the answer.
     *
     * <p>One rule spans both fields. {@code app/cbl/COCRDSLC.cbl:L637-L640} tests both blank flags
     * after both edits have run and under no guard, so it overwrites whichever text an edit had
     * written. This method applies that rule first, which reaches the same answer with one
     * comparison.
     *
     * <p>An all-zero value counts as no value, from the third condition of each absence test:
     * {@code CC-ACCT-ID-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:L653} and
     * {@code CC-CARD-NUM-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:L693}.
     *
     * <p>Visible beyond this class so that {@code api/dto/CardDetailRequestTest} can hold this chain
     * and the constraints {@link CardDetailRequest} declares to the same four texts. A validator
     * reports an unordered set, and one of the rules spans both fields, so a test states that the
     * two agree.
     *
     * @param request the submitted search condition
     * @return the text of the first edit that refused the request, or {@code null} when all four
     *         pass
     */
    public static String firstSearchFailure(CardDetailRequest request) {
        boolean accountAbsent = isAbsent(request.accountId(), PicClause.CARD_ACCT_ID_WIDTH);
        boolean cardAbsent = isAbsent(request.cardNumber(), PicClause.CARD_NUM_WIDTH);

        if (accountAbsent && cardAbsent) {
            return CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED;
        }
        if (accountAbsent) {
            return CardValidationMessages.PROMPT_FOR_ACCT;
        }
        if (!isDigits(request.accountId(), PicClause.CARD_ACCT_ID_WIDTH)) {
            return CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC;
        }
        if (cardAbsent) {
            return CardValidationMessages.PROMPT_FOR_CARD;
        }
        if (!isDigits(request.cardNumber(), PicClause.CARD_NUM_WIDTH)) {
            return CardValidationMessages.CARD_FILTER_NOT_NUMERIC;
        }
        return null;
    }

    /**
     * Reports whether one stored card belongs to the account the caller named.
     *
     * <p>Both values are the eleven digits {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6} declares. The stored one arrives from a {@code CHAR(11)}
     * column and can carry padding a caller's value does not. Both are stripped before the
     * comparison and neither is widened. {@link #firstSearchFailure(CardDetailRequest)} has already
     * refused a caller's value of another width.
     *
     * <p>The stored account is the authoritative one. The comparison keeps a request field from
     * deciding which row a caller reaches.
     *
     * @param card             the row the read found
     * @param suppliedAccountId the account identifier the caller sent
     * @return {@code true} when the two name one account
     */
    private static boolean namesTheSameAccount(CardEntity card, String suppliedAccountId) {
        String stored = card.getAccountId();
        if (stored == null || suppliedAccountId == null) {
            return false;
        }
        return stored.strip().equals(suppliedAccountId.strip());
    }

    /**
     * Reports whether a numeric display field carries no value at all.
     *
     * @param value the submitted value, which may be {@code null}
     * @param width the width the Picture clause declares
     * @return {@code true} when the value is missing, blank, or the digit zero repeated to width
     */
    private static boolean isAbsent(String value, int width) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.strip().equals("0".repeat(width));
    }

    /**
     * Reports whether a value carries exactly the declared width in digits.
     *
     * <p>{@code IS NOT NUMERIC} on a fixed-width alphanumeric field fails for a shorter value, as
     * the field arrives space-padded. The width and the character class are one test in the source
     * and one test here.
     *
     * @param value the submitted value, already known to carry a character
     * @param width the width the Picture clause declares
     * @return {@code true} when every character is a digit and the count is exactly {@code width}
     */
    private static boolean isDigits(String value, int width) {
        String present = value.strip();
        if (present.length() != width) {
            return false;
        }
        for (int position = 0; position < present.length(); position++) {
            char character = present.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
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
