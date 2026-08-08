package com.carddemo.fraud.api;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * <h2>The paging contract refuses rather than adjusts</h2>
 *
 * <p>{@link #assessmentsOfAccount(String, String, String, String)} declares {@code page},
 * {@code size} and {@code sort} as request parameters of its own, each with a stated bound, so a value
 * outside those bounds answers {@code 400}. Each arrives as text, which separates an omitted parameter
 * from one that arrived carrying no characters: a parameter declared as a number with a default reads
 * {@code ?page=} as an omitted {@code page} and answers {@code 200}. A caller therefore learns which
 * page it received.
 *
 * <p>A framework {@code Pageable} argument would decide silently instead. It coerces an unparsable
 * size to the default, clamps a size above the configured maximum down to that maximum, and reads a
 * negative page number as page zero, all with a {@code 200} and no statement of what it did. A caller
 * asking for 5,000 rows would receive the first 2,000 and be told nothing, which is the wrong answer
 * to give about somebody's fraud history.
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

    /** Name of the page-number parameter, used in its declaration and in a refusal message. */
    static final String PAGE_PARAMETER = "page";

    /** Name of the page-size parameter, used in its declaration and in a refusal message. */
    static final String SIZE_PARAMETER = "size";

    /**
     * Name of the parameter this route refuses.
     *
     * <p>{@code FraudAssessmentRepository.findByAccountIdOrderByAssessedAtDesc} fixes the order, so
     * an order a caller supplied could only be ignored.</p>
     */
    static final String SORT_PARAMETER = "sort";

    /** Page a caller reaches by naming no page. The first page. */
    static final int FIRST_PAGE = 0;

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

    /**
     * Highest page number this route reads.
     *
     * <p>{@code PageRequest.of} multiplies the page number by the size to reach an offset, so a page
     * number near the widest signed integer overflows that product. This ceiling holds the offset
     * inside the range a query can express, and a higher page number answers {@code 400}.
     */
    static final int MAXIMUM_PAGE_NUMBER = 1_000_000;

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
     * Returns one account's assessments, newest first.
     *
     * <p>{@code page} counts from {@value #FIRST_PAGE} and {@code size} runs from
     * {@value #MINIMUM_PAGE_SIZE} through {@value #MAXIMUM_PAGE_SIZE}. Omitting both returns page
     * {@value #FIRST_PAGE} at {@value #DEFAULT_PAGE_SIZE} rows. A value outside those bounds answers
     * {@code 400}, and so does a parameter that arrived carrying no characters, so the page a caller
     * receives is always the page it asked for.
     *
     * <p>The order is fixed: assessment time descending, which
     * {@code FraudAssessmentRepository.findByAccountIdOrderByAssessedAtDesc} states in its name. A
     * {@code sort} parameter is refused here with {@code 400} rather than accepted and dropped, and
     * this method is the only handler the collection route has.
     *
     * @param accountId the eleven-digit account identifier
     * @param page      the page to return, counting from {@value #FIRST_PAGE}, or {@code null}
     * @param size      the rows one page carries, from {@value #MINIMUM_PAGE_SIZE} through
     *                  {@value #MAXIMUM_PAGE_SIZE}, or {@code null}
     * @param sort      an order to apply, which this route does not accept, or {@code null}
     * @return {@code 200} carrying the assessments, empty when the account holds none or when the
     *         page lies past the rows the account has
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FraudAssessment> assessmentsOfAccount(
            @RequestParam
            @NotBlank
            @Pattern(regexp = ACCOUNT_ID_PATTERN)
            String accountId,
            @RequestParam(name = PAGE_PARAMETER, required = false)
            String page,
            @RequestParam(name = SIZE_PARAMETER, required = false)
            String size,
            @RequestParam(name = SORT_PARAMETER, required = false)
            String sort) {

        if (sort != null) {
            throw new UnsupportedSortException("The " + SORT_PARAMETER + " parameter is not supported."
                    + " Assessments answer in assessment-time descending order only.");
        }

        PageRequest requested = PageRequest.of(
                boundedNumberOf(page, PAGE_PARAMETER, FIRST_PAGE, FIRST_PAGE, MAXIMUM_PAGE_NUMBER),
                boundedNumberOf(size, SIZE_PARAMETER, DEFAULT_PAGE_SIZE, MINIMUM_PAGE_SIZE,
                        MAXIMUM_PAGE_SIZE));
        return assessments.findByAccountIdOrderByAssessedAtDesc(accountId, requested).stream()
                .map(FraudAssessmentController::assessmentFrom)
                .toList();
    }

    /**
     * Reads one paging parameter, refusing every value that is not a number inside its bounds.
     *
     * <p>Three cases are separated. An omitted parameter takes {@code whenAbsent}. A parameter that
     * arrived carrying no characters is a value the caller sent, so it is refused rather than read as
     * absent. Any other value has to parse as a number inside the bounds.
     *
     * <p>Refusing a present-empty value is the point of reading these as text. A parameter declared as
     * a number with a default takes the default for {@code ?page=} as well as for an omitted
     * {@code page}, so a caller sending an empty value receives page {@value #FIRST_PAGE} and a
     * {@code 200} that states nothing about what it did.
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
     * Raised when the collection route is asked for an order it does not apply.
     *
     * <p>{@link ResponseStatus} maps it to {@code 400}: the request named a parameter this route does
     * not honour, which is the caller's error and not a fault of this service.</p>
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class UnsupportedSortException extends RuntimeException {

        /** Serialization identity of this exception. */
        private static final long serialVersionUID = 1L;

        /**
         * Takes the text the response carries.
         *
         * @param message the refusal, naming the parameter and the order this route applies
         */
        public UnsupportedSortException(String message) {
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
