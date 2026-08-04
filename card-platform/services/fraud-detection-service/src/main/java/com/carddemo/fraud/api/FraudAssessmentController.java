package com.carddemo.fraud.api;

import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p>ADDITIVE IN FULL. No COBOL program scores risk. This resource is net new; no COBOL ancestor
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
 * {@code 400} through the framework's default problem document.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
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
     * <p>The page number and the page size arrive as request parameters and default from
     * configuration.
     *
     * @param accountId the eleven-digit account identifier
     * @param pageable  the page the caller asked for
     * @return {@code 200} carrying the assessments, empty when the account holds none
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FraudAssessment> assessmentsOfAccount(
            @RequestParam
            @NotBlank
            @Pattern(regexp = ACCOUNT_ID_PATTERN)
            String accountId,
            Pageable pageable) {
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return assessments.findByAccountIdOrderByAssessedAtDesc(accountId, page).stream()
                .map(FraudAssessmentController::assessmentFrom)
                .toList();
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
     * rules that triggered, in evaluation order. An empty {@code triggeredRules} is valid here and
     * says the transaction was cleared. {@code FraudFlagged} names at least one rule, and a cleared
     * outcome publishes {@code FraudCleared}.
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
         * Copies the rule list, keeping its order.
         *
         * @throws NullPointerException if {@code triggeredRules} or any rule it holds is
         *                              {@code null}
         */
        public FraudAssessment {
            triggeredRules = List.copyOf(triggeredRules);
        }
    }
}
