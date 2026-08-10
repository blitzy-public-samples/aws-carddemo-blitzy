package com.carddemo.fraud.api;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one synchronous surface of the fraud detection service, a Representational State Transfer
 * (REST) resource over the stored risk assessments.
 *
 * <p>Two operations answer. {@code GET /fraud-assessments/{transactionId}} returns one assessment,
 * and {@code GET /fraud-assessments?accountId=...} returns one account's assessments, newest first.
 * Both read, and neither writes a row, publishes an event or calls another service. Read-only. This
 * endpoint sits on no authorization or posting path.
 *
 * <p>No COBOL program scores risk. This resource is net new; no COBOL ancestor
 * exists, and it replaces no transaction and no batch step.
 *
 * <p>Three source constructs are borrowed shape only, no logic. The transaction identifier takes
 * its width from {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The account
 * identifier takes its width and its digits from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}. {@code app/cbl/COCRDUPC.cbl:L193-L196} tests a card number for
 * sixteen digits and a card status for one letter, and no route here performs either test.
 *
 * <p>A stored assessment answers {@code 200} carrying one JavaScript Object Notation (JSON) object,
 * and an account holding none answers {@code 200} with an empty JSON array. A transaction the table
 * does not hold answers {@code 404} with no body. An identifier that misses its constraint answers
 * {@code 400} through the framework's default problem document, and so does a page number, a page
 * size or a sort order the collection route does not accept.
 *
 * <h2>Pages are reached by position, not by counting</h2>
 *
 * <p>{@link #assessmentsOfAccount(String, String, String, String, String)} carries a page forward in
 * the {@code X-Fraud-Cursor} header, which names the assessment time and transaction identifier of
 * the last row the previous page served. The next page is the rows after that position, which is a
 * range read of {@code ix_fraud_assessment_account_cursor}: the tenth page and the ten-thousandth
 * cost the same.
 *
 * <p>A page number cannot do that. An offset is reached by reading every row before it and throwing
 * them away, so the work grows with the page reached rather than with the page returned. The
 * {@code page} parameter is therefore refused with {@code 400} rather than served, for the reason
 * {@code sort} is: a route that quietly answered page one to a request for page five thousand would
 * report success for something it did not do.
 *
 * <p>A cursor is also exact where an offset is not. Assessment time is not unique — one consumer
 * batch assesses several transactions and the rows can share a microsecond — so a page boundary
 * counted by row number can land inside a group of equal times, repeat one row on the next page and
 * skip another. The cursor names both columns, and the primary key makes the order total.
 *
 * <p>The cursor names a position and not an entitlement. Every read is bound to the {@code accountId}
 * the request asked for, so a cursor issued for one account reads no other account's rows.
 *
 * <h2>The paging contract refuses rather than adjusts</h2>
 *
 * <p>{@code size} arrives as text with a stated bound, so a value outside that bound answers
 * {@code 400}. Text separates an omitted parameter from one that arrived carrying no characters: a
 * parameter declared as a number with a default reads {@code ?size=} as an omitted {@code size} and
 * answers {@code 200}. A caller therefore learns which page it received.
 *
 * <p>A framework {@code Pageable} argument would decide silently instead. It coerces an unparsable
 * size to the default and clamps a size above the configured maximum down to that maximum, both with
 * a {@code 200} and no statement of what it did. A caller asking for 5,000 rows would receive the
 * first 2,000 and be told nothing, which is the wrong answer to give about somebody's fraud history.
 *
 * <p>{@code sort} is refused outright rather than accepted and dropped. The repository finder orders
 * by assessment time descending in its own name, so any order a caller asked for would be ignored
 * while the response still claimed success. The refusal sits inside the one handler the collection
 * route has, so {@code src/main/resources/openapi.yaml} describes the parameter and its answer on the
 * operation a caller reads.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestController
@RequestMapping("/fraud-assessments")
public class FraudAssessmentController {

    /**
     * Width of the transaction identifier this route accepts: sixteen characters of text, never a
     * number.
     */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * Shape of the account identifier this route accepts: exactly eleven digits. A leading zero is
     * part of the value, so the identifier travels as text.
     */
    private static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Name of the page-number parameter this route refuses.
     *
     * <p>Reaching a page by number means reading and discarding every row before it, so the work
     * grows with the page asked for. This route carries a position instead, in
     * {@value #CURSOR_HEADER}.</p>
     */
    static final String PAGE_PARAMETER = "page";

    /** Name of the page-size parameter, used in its declaration and in a refusal message. */
    static final String SIZE_PARAMETER = "size";

    /**
     * Name of the order parameter this route refuses.
     *
     * <p>{@code FraudAssessmentRepository.findByAccountIdOrderByAssessedAtDescTransactionIdDesc}
     * fixes the order, so an order a caller supplied could only be ignored.</p>
     */
    static final String SORT_PARAMETER = "sort";

    /**
     * Header carrying the position the next page starts after.
     *
     * <p>A header rather than a query parameter, matching {@code X-Card-Cursor} of the card service
     * and {@code X-Notification-Cursor} of the notification service. The value is this route's own
     * bookkeeping, not a choice a caller composes, and keeping it out of the query string keeps it
     * out of the access logs that record one.</p>
     */
    static final String CURSOR_HEADER = "X-Fraud-Cursor";

    /**
     * Character joining the two values a cursor names.
     *
     * <p>It appears in neither part. An instant renders as digits with {@code -}, {@code :},
     * {@code .}, {@code T} and {@code Z}, so the first occurrence separates the two parts whatever
     * the identifier holds.</p>
     */
    static final char CURSOR_SEPARATOR = '|';

    /**
     * Row read past the page to learn whether another page follows.
     *
     * <p>One extra row answers the question. Counting the account's remaining rows would read them
     * all, which is the cost this route exists to avoid.</p>
     */
    static final int LOOKAHEAD_ROW_COUNT = 1;

    /** Rows a caller receives by naming no size. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Fewest rows a caller may ask for. Asking for none would describe no page at all. */
    static final int MINIMUM_PAGE_SIZE = 1;

    /**
     * Most rows one page carries.
     *
     * <p>A larger request answers {@code 400} rather than receiving this many rows silently.</p>
     */
    static final int MAXIMUM_PAGE_SIZE = 200;

    /** Reads the assessments this service records. */
    private final FraudAssessmentRepository assessments;

    /**
     * Takes the repository this controller reads.
     *
     * @param assessments the assessment repository
     * @throws NullPointerException if {@code assessments} is {@code null}
     */
    public FraudAssessmentController(FraudAssessmentRepository assessments) {
        this.assessments = Objects.requireNonNull(assessments, "assessments must not be null");
    }

    /**
     * Returns one transaction's assessment.
     *
     * @param transactionId the sixteen-character transaction identifier
     * @return {@code 200} carrying the assessment, or {@code 404} when the table holds no
     *         assessment of that transaction
     */
    @GetMapping(path = "/{transactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FraudAssessment> assessmentOfTransaction(
            @PathVariable
            @NotBlank
            @Size(min = TRANSACTION_ID_WIDTH, max = TRANSACTION_ID_WIDTH)
            String transactionId) {
        return assessments.findById(transactionId)
                .map(FraudAssessmentController::assessmentFrom)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns one page of one account's assessments, newest first.
     *
     * <p>{@code size} runs from {@value #MINIMUM_PAGE_SIZE} through {@value #MAXIMUM_PAGE_SIZE} and
     * defaults to {@value #DEFAULT_PAGE_SIZE}. A value outside those bounds answers {@code 400}, and
     * so does a parameter that arrived carrying no characters, so the page a caller receives is
     * always the page it asked for.
     *
     * <p>Omitting {@value #CURSOR_HEADER} returns the newest page. Sending back the
     * {@code nextCursor} the previous answer carried returns the page after it. The answer names a
     * cursor exactly when a further page exists, so a caller walks the history by repeating this
     * call until {@code nextPageExists} is false, and each call costs one page.
     *
     * <p>Every read is bound to {@code accountId}, so a cursor issued for one account cannot read
     * another's rows.
     *
     * <p>The order is fixed: assessment time descending, then transaction identifier descending,
     * which {@code findByAccountIdOrderByAssessedAtDescTransactionIdDesc} states in its name. A
     * {@code sort} parameter is refused here with {@code 400} rather than accepted and dropped, and
     * so is a {@code page} parameter, which names a position this route does not reach by counting.
     * This method is the only handler the collection route has.
     *
     * @param accountId the eleven-digit account identifier
     * @param cursor    the position to continue after, as the previous answer named it, or
     *                  {@code null} to start at the newest row
     * @param size      the rows one page carries, from {@value #MINIMUM_PAGE_SIZE} through
     *                  {@value #MAXIMUM_PAGE_SIZE}, or {@code null}
     * @param sort      an order to apply, which this route does not accept, or {@code null}
     * @param page      a page number, which this route does not accept, or {@code null}
     * @return {@code 200} carrying one page, its rows empty when the account holds none or when the
     *         cursor names the account's oldest row
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public AssessmentPage assessmentsOfAccount(
            @RequestParam
            @NotBlank
            @Pattern(regexp = ACCOUNT_ID_PATTERN)
            String accountId,
            @RequestHeader(name = CURSOR_HEADER, required = false)
            String cursor,
            @RequestParam(name = SIZE_PARAMETER, required = false)
            String size,
            @RequestParam(name = SORT_PARAMETER, required = false)
            String sort,
            @RequestParam(name = PAGE_PARAMETER, required = false)
            String page) {

        if (sort != null) {
            throw new UnsupportedParameterException("The " + SORT_PARAMETER + " parameter is not"
                    + " supported. Assessments answer in assessment-time descending order only.");
        }
        if (page != null) {
            throw new UnsupportedParameterException("The " + PAGE_PARAMETER + " parameter is not"
                    + " supported. Continue a page by returning the cursor the previous answer named,"
                    + " in the " + CURSOR_HEADER + " header.");
        }

        int rows = boundedNumberOf(size, SIZE_PARAMETER, DEFAULT_PAGE_SIZE, MINIMUM_PAGE_SIZE,
                MAXIMUM_PAGE_SIZE);
        Limit limit = Limit.of(rows + LOOKAHEAD_ROW_COUNT);

        List<FraudAssessmentEntity> found;
        if (cursor == null) {
            found = assessments.findByAccountIdOrderByAssessedAtDescTransactionIdDesc(accountId,
                    limit);
        } else {
            Position position = positionOf(cursor);
            found = assessments.findPageAfter(accountId, position.assessedAt(),
                    position.transactionId(), limit);
        }

        boolean nextPageExists = found.size() > rows;
        List<FraudAssessmentEntity> served =
                found.subList(0, Math.min(rows, found.size()));
        String nextCursor = nextPageExists ? cursorOf(served.get(served.size() - 1)) : null;

        return new AssessmentPage(
                served.stream().map(FraudAssessmentController::assessmentFrom).toList(),
                nextPageExists,
                nextCursor);
    }

    /**
     * Renders the position of one row as the cursor a caller returns to continue after it.
     *
     * <p>Both ordering columns travel, because the order needs both. The instant renders in the
     * calendar form {@link Instant#toString()} produces, which
     * {@link Instant#parse(CharSequence)} reads back unchanged: the column holds microseconds, so no
     * digit of a stored value is lost on the way out or back in.
     *
     * @param row the last row the page served
     * @return the two ordering values of that row, joined by {@value #CURSOR_SEPARATOR}
     */
    private static String cursorOf(FraudAssessmentEntity row) {
        return row.getAssessedAt() + String.valueOf(CURSOR_SEPARATOR) + row.getTransactionId();
    }

    /**
     * Reads a cursor into the position it names.
     *
     * <p>Every unreadable value is refused rather than repaired. A cursor this route did not issue
     * describes no row, and continuing from a guess would silently return the wrong page.
     *
     * <p>The refusal names the shape and never the value. A cursor holds a transaction identifier,
     * so echoing it would copy that identifier into the response body and into any log built from
     * it.
     *
     * @param cursor the header value as it arrived
     * @return the assessment time and transaction identifier it names
     * @throws UnreadablePagingValueException when the value is empty, carries no separator, or
     *                                        holds an instant or an identifier this route cannot read
     */
    private static Position positionOf(String cursor) {
        int separator = cursor.indexOf(CURSOR_SEPARATOR);
        if (separator < 0 || separator == cursor.length() - 1) {
            throw new UnreadablePagingValueException(unreadableCursor());
        }

        String renderedInstant = cursor.substring(0, separator);
        String transactionId = cursor.substring(separator + 1);
        if (transactionId.length() != TRANSACTION_ID_WIDTH) {
            throw new UnreadablePagingValueException(unreadableCursor());
        }

        try {
            return new Position(Instant.parse(renderedInstant), transactionId);
        } catch (DateTimeParseException notAnInstant) {
            throw new UnreadablePagingValueException(unreadableCursor());
        }
    }

    /**
     * Builds the one refusal a cursor receives, naming the shape and no value.
     *
     * @return the text every cursor refusal carries
     */
    private static String unreadableCursor() {
        return "The " + CURSOR_HEADER + " header reads as an assessment time and a transaction"
                + " identifier of " + TRANSACTION_ID_WIDTH + " characters, joined by "
                + CURSOR_SEPARATOR + ". Send back the cursor the previous answer named, or omit the"
                + " header to start at the newest assessment.";
    }

    /**
     * The position a cursor names: one row's place in the total order this route reads.
     *
     * @param assessedAt    the assessment time of the last row served
     * @param transactionId the identifier of that row, which breaks a tie on {@code assessedAt}
     */
    private record Position(Instant assessedAt, String transactionId) {
    }

    /**
     * Reads one paging parameter, refusing every value that is not a number inside its bounds.
     *
     * <p>Three cases are separated. An omitted parameter takes {@code whenAbsent}. A parameter that
     * arrived carrying no characters is a value the caller sent, so it is refused rather than read as
     * absent. Any other value has to parse as a number inside the bounds.
     *
     * <p>Refusing a present-empty value is the point of reading these as text. A parameter declared as
     * a number with a default takes the default for {@code ?size=} as well as for an omitted
     * {@code size}, so a caller sending an empty value receives {@value #DEFAULT_PAGE_SIZE} rows and
     * a {@code 200} that states nothing about what it did.
     *
     * @param value      the parameter as it arrived, or {@code null} where it did not arrive
     * @param name       the parameter name, which the refusal reports
     * @param whenAbsent the value an omitted parameter takes
     * @param lowest     the lowest value accepted
     * @param highest    the highest value accepted
     * @return the number to page by
     * @throws UnreadablePagingValueException when the parameter arrived empty, does not parse as a
     *                                        number, or lies outside its bounds
     */
    private static int boundedNumberOf(String value, String name, int whenAbsent, int lowest,
            int highest) {

        if (value == null) {
            return whenAbsent;
        }
        if (value.isEmpty()) {
            throw new UnreadablePagingValueException("The " + name + " parameter arrived carrying no"
                    + " value. Omit it to take the default, or send a number from " + lowest
                    + " through " + highest + ".");
        }

        int number;
        try {
            number = Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            throw new UnreadablePagingValueException("The " + name + " parameter reads as a number"
                    + " from " + lowest + " through " + highest + ".");
        }
        if (number < lowest || number > highest) {
            throw new UnreadablePagingValueException("The " + name + " parameter reads as a number"
                    + " from " + lowest + " through " + highest + ".");
        }
        return number;
    }

    /**
     * Raised when the collection route is asked for something it does not do.
     *
     * <p>Two parameters raise it. {@code sort} names an order the repository finder fixes, and
     * {@code page} names a position this route does not reach by counting rows. Either would have to
     * be ignored to answer at all, so it is refused instead.
     *
     * <p>{@link ResponseStatus} maps it to {@code 400}: the request named a parameter this route does
     * not honour, which is the caller's error and not a fault of this service.</p>
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class UnsupportedParameterException extends RuntimeException {

        /** Serialization identity of this exception. */
        private static final long serialVersionUID = 1L;

        /**
         * Takes the text the response carries.
         *
         * @param message the refusal, naming the parameter and what this route does instead
         */
        public UnsupportedParameterException(String message) {
            super(message);
        }
    }

    /**
     * Raised when a paging parameter arrived and this route could not read it as a page.
     *
     * <p>{@link ResponseStatus} maps it to {@code 400}. The text names the parameter and the bounds it
     * accepts, and it carries no value read from the request.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class UnreadablePagingValueException extends RuntimeException {

        /** Serialization identity of this exception. */
        private static final long serialVersionUID = 1L;

        /**
         * Takes the text the response carries.
         *
         * @param message the refusal, naming the parameter and the bounds this route reads
         */
        public UnreadablePagingValueException(String message) {
            super(message);
        }
    }

    /**
     * Reads one stored row into the six values an answer carries.
     *
     * @param row the assessment row to read
     * @return the six values the row holds
     */
    private static FraudAssessment assessmentFrom(FraudAssessmentEntity row) {
        return new FraudAssessment(row.getTransactionId(), row.getAccountId(), row.getRiskScore(),
                row.getTriggeredRules(), row.isFlagged(), row.getAssessedAt());
    }

    /**
     * One page of one account's assessments, and how to reach the page after it.
     *
     * <p>{@code assessments} holds at most the rows the request asked for, newest first.
     * {@code nextPageExists} states whether the account holds older rows, and {@code nextCursor}
     * names the position they start after. The two agree: a cursor is present exactly when a further
     * page exists, so a caller has one thing to test and never composes a cursor of its own.
     *
     * <p>{@link JsonInclude} omits {@code nextCursor} on the last page rather than rendering it as
     * {@code null}. {@code src/main/resources/openapi.yaml} declares the property a string and lists
     * it as optional, so a JSON null would describe a shape the document refuses.
     *
     * <p>No count of the whole history appears here. Counting an account's rows reads them all, which
     * is the work this page exists to avoid, and a caller learns the history is exhausted from
     * {@code nextPageExists} instead.
     *
     * @param assessments    the rows of this page, newest first, empty when there are none
     * @param nextPageExists whether the account holds rows older than this page
     * @param nextCursor     the position the next page starts after, or {@code null} on the last page
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssessmentPage(List<FraudAssessment> assessments, boolean nextPageExists,
            String nextCursor) {

        /**
         * Checks and copies the page.
         *
         * @throws NullPointerException     if {@code assessments} is {@code null}
         * @throws IllegalArgumentException if a cursor is present without a further page, or a
         *                                  further page is claimed without a cursor
         */
        public AssessmentPage {
            Objects.requireNonNull(assessments, "assessments");
            assessments = List.copyOf(assessments);
            if (nextPageExists && nextCursor == null) {
                throw new IllegalArgumentException(
                        "/nextCursor names the next page whenever /nextPageExists is true");
            }
            if (!nextPageExists && nextCursor != null) {
                throw new IllegalArgumentException(
                        "/nextCursor is absent whenever /nextPageExists is false");
            }
        }

        /**
         * Renders the row count and the paging flag, and withholds the cursor.
         *
         * <p>The cursor holds a transaction identifier, and a diagnostic line describing one page
         * needs the shape of the answer rather than the identifier inside it.
         *
         * @return the class name with the row count, the flag, and
         *         {@link EventEnvelope#WITHHELD} in place of the cursor
         */
        @Override
        public String toString() {
            return "AssessmentPage[assessments=" + assessments.size() + " rows, nextPageExists="
                    + nextPageExists + ", nextCursor=" + EventEnvelope.WITHHELD + "]";
        }
    }

    /**
     * The six values one assessment answer carries.
     *
     * <p>{@code riskScore} is a whole number from 0 through 100. {@code triggeredRules} holds the
     * rules that contributed points, in evaluation order. {@code flagged} is the separate
     * threshold-based verdict. {@code FraudFlagged} names at least one rule, and a cleared outcome
     * publishes {@code FraudCleared}.
     *
     * @param transactionId  the assessed transaction, sixteen characters of text
     * @param accountId      the account the transaction belongs to, eleven digits of text
     * @param riskScore      the score the risk rules produced, from 0 through 100
     * @param triggeredRules the rules that triggered, in evaluation order, empty when none did
     * @param flagged        the stored verdict, read from the row
     * @param assessedAt     the time the risk rules finished
     */
    public record FraudAssessment(String transactionId, String accountId, int riskScore,
            List<String> triggeredRules, boolean flagged, Instant assessedAt) {

        /**
         * Checks and copies the values read from one stored row.
         *
         * @throws NullPointerException     if a reference value or a rule is {@code null}
         * @throws IllegalArgumentException if the score or rule list is invalid, or a flagged
         *                                  verdict names no rule
         */
        public FraudAssessment {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(assessedAt, "assessedAt");
            triggeredRules = List.copyOf(triggeredRules);
            if (riskScore < FraudFlagged.MINIMUM_RISK_SCORE
                    || riskScore > FraudFlagged.MAXIMUM_RISK_SCORE) {
                throw new IllegalArgumentException("/riskScore is outside the published range");
            }
            if (!FraudFlagged.RULE_IDENTIFIERS.containsAll(triggeredRules)) {
                throw new IllegalArgumentException(
                        "/triggeredRules names an unknown rule identifier");
            }
            if (new HashSet<>(triggeredRules).size() != triggeredRules.size()) {
                throw new IllegalArgumentException(
                        "/triggeredRules names each rule once");
            }
            if (flagged && triggeredRules.isEmpty()) {
                throw new IllegalArgumentException(
                        "/flagged is true only when /triggeredRules names a rule");
            }
        }

        /**
         * Renders every component as withheld.
         *
         * <p>A generated rendering carries the score, the verdict and the rules that produced them.
         * A diagnostic line that holds them describes how this service decides, and a log reader
         * needs neither to follow one request.
         *
         * @return the class name with {@link EventEnvelope#WITHHELD} in place of every value
         */
        @Override
        public String toString() {
            return "FraudAssessment[transactionId=" + EventEnvelope.WITHHELD + ", accountId="
                    + EventEnvelope.WITHHELD + ", riskScore=" + EventEnvelope.WITHHELD
                    + ", triggeredRules=" + EventEnvelope.WITHHELD + ", flagged="
                    + EventEnvelope.WITHHELD + ", assessedAt=" + EventEnvelope.WITHHELD + "]";
        }
    }
}
