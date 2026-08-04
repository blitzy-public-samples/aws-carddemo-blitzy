package com.carddemo.fraud.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.entity.VelocityWindowEntity.VelocityWindowId;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Scores one authorized transaction against every registered risk rule and returns one assessment.
 * No rule is skipped, so the result carries every identifier that triggered.
 *
 * <p>No COBOL (Common Business Oriented Language) program under {@code app/cbl/} scores risk or
 * counts authorization velocity. ADDITIVE IN FULL: net new; no COBOL ancestor.
 *
 * <p>The chain shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment marks the extension point.
 * Shape only, no logic: that paragraph stops at its first failing test and keeps one reject code.
 *
 * <p>One assessment becomes one event: {@link FraudFlagged} when {@link RiskAssessment#flagged()}
 * holds. This class builds no event, publishes nothing and opens no transaction; the consumer that
 * calls it owns all three. The decisions behind this class are recorded in
 * {@code card-platform/docs/decision-log.md}.
 */
@Service
public class RiskScoringService {

    /** Lowest score the published assessment contract admits. */
    private static final int MINIMUM_RISK_SCORE = FraudFlagged.MINIMUM_RISK_SCORE;

    /** Highest score the published assessment contract admits. */
    private static final int MAXIMUM_RISK_SCORE = FraudFlagged.MAXIMUM_RISK_SCORE;

    /** Width of one window row, and the unit an event time truncates to for its bucket. */
    private static final ChronoUnit WINDOW_BUCKET = ChronoUnit.HOURS;

    /** Authorizations one event adds to a window row. */
    private static final int ONE_AUTHORIZATION = 1;

    /** Total a new window row opens from, at the scale the amount column holds. */
    private static final BigDecimal OPENING_TOTAL = new BigDecimal("0.00");

    /** Every risk rule, in the order the framework supplied them. */
    private final List<RiskRule> rules;

    /** Reads one window row by key and stores it. */
    private final VelocityWindowRepository velocityWindows;

    /**
     * Takes every risk rule and the window store.
     *
     * @param rules           every rule the framework supplied, kept in the order supplied. An
     *                        empty collection scores every transaction as cleared
     * @param velocityWindows store the keyed read and the save run against
     * @throws NullPointerException if either argument, or any rule, is null
     */
    public RiskScoringService(List<RiskRule> rules, VelocityWindowRepository velocityWindows) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.velocityWindows = Objects.requireNonNull(velocityWindows, "velocityWindows");
    }

    /**
     * Scores one authorized transaction.
     *
     * <p>Every rule runs once, in the order the framework supplied them. The window row is updated
     * after the last rule has read it, so a rule sees the window as it stood before this event.
     *
     * @param event the authorized transaction to score
     * @return the score, the identifier of each rule that triggered, and the moment scored
     * @throws NullPointerException     if {@code event} is null
     * @throws IllegalArgumentException if two rules report one identifier, or if the window row
     *                                  refuses the total the addition yields
     */
    public RiskAssessment assess(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event");
        Instant assessedAt = Instant.now();

        List<String> triggeredRules = new ArrayList<>();
        int riskScore = 0;
        for (RiskRule rule : rules) {
            RiskRule.Contribution contribution = rule.evaluate(event);
            riskScore = riskScore + contribution.points();
            if (contribution.triggered()) {
                triggeredRules.add(rule.ruleId());
            }
        }
        if (riskScore < MINIMUM_RISK_SCORE) {
            riskScore = MINIMUM_RISK_SCORE;
        }
        if (riskScore > MAXIMUM_RISK_SCORE) {
            riskScore = MAXIMUM_RISK_SCORE;
        }

        recordVelocityWindow(event, assessedAt);
        return new RiskAssessment(event.transactionId(), event.accountId(), riskScore,
                triggeredRules, assessedAt);
    }

    /**
     * Counts one authorization into the window row for the account and the bucket, creating the row
     * when the table holds none.
     *
     * <p>The bucket start is the event time truncated to {@code WINDOW_BUCKET}. The addition
     * truncates toward zero, and a negative amount lowers the total. A duplicate delivery counts
     * once: the consumer checks {@code processed_event} before it calls this class.
     *
     * @param event      the authorized transaction being counted
     * @param recordedAt the moment written to the row
     */
    private void recordVelocityWindow(TransactionAuthorized event, Instant recordedAt) {
        Instant bucketStart = event.occurredAt().truncatedTo(WINDOW_BUCKET);
        Optional<VelocityWindowEntity> existingWindow =
                velocityWindows.findById(new VelocityWindowId(event.accountId(), bucketStart));

        if (existingWindow.isEmpty()) {
            BigDecimal openingTotal =
                    CobolDecimal.add(OPENING_TOTAL, event.amount(), PicClause.TRAN_AMT_SCALE);
            velocityWindows.save(new VelocityWindowEntity(event.accountId(), bucketStart,
                    ONE_AUTHORIZATION, openingTotal, recordedAt));
        } else {
            VelocityWindowEntity window = existingWindow.get();
            window.setAuthorizationCount(window.getAuthorizationCount() + ONE_AUTHORIZATION);
            window.setTotalAmount(CobolDecimal.add(window.getTotalAmount(), event.amount(),
                    PicClause.TRAN_AMT_SCALE));
            window.setUpdatedAt(recordedAt);
            velocityWindows.save(window);
        }
    }

    /**
     * One risk assessment: the score, the identifier of each rule that triggered, and the moment
     * scored. ADDITIVE IN FULL: net new; no COBOL ancestor.
     *
     * @param transactionId  the transaction assessed, from {@code TRAN-ID PIC X(16)} at
     *                       {@code app/cpy/CVTRA05Y.cpy:L5}
     * @param accountId      the account the card resolved to, from
     *                       {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
     * @param riskScore      the summed score, held inside the range the assessment contract
     *                       permits. A count, not money
     * @param triggeredRules the identifier of each rule that triggered, in evaluation order,
     *                       immutable and empty when none triggered
     * @param assessedAt     the moment the assessment was made
     */
    public record RiskAssessment(String transactionId,
                                 String accountId,
                                 int riskScore,
                                 List<String> triggeredRules,
                                 Instant assessedAt) {

        /**
         * Copies the rule list, and refuses a score outside the contract range or a repeated
         * identifier.
         *
         * <p>A failure names the failing field by its JavaScript Object Notation (JSON) pointer and
         * carries no value.
         *
         * @throws NullPointerException     if any reference component, or any element of
         *                                  {@code triggeredRules}, is null
         * @throws IllegalArgumentException if {@code riskScore} falls outside the contract range,
         *                                  or if one identifier appears twice
         */
        public RiskAssessment {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(assessedAt, "assessedAt");
            triggeredRules = List.copyOf(triggeredRules);
            if (riskScore < MINIMUM_RISK_SCORE || riskScore > MAXIMUM_RISK_SCORE) {
                throw new IllegalArgumentException("/riskScore falls from " + MINIMUM_RISK_SCORE
                        + " through " + MAXIMUM_RISK_SCORE);
            }
            if (new HashSet<>(triggeredRules).size() != triggeredRules.size()) {
                throw new IllegalArgumentException("/triggeredRules names each rule once");
            }
        }

        /**
         * Whether any rule triggered, which tells a caller which event to publish.
         *
         * @return {@code true} when {@code triggeredRules} names at least one rule
         */
        public boolean flagged() {
            return !triggeredRules.isEmpty();
        }
    }
}
