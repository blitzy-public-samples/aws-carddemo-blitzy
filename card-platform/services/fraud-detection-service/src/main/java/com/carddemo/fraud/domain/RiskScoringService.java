package com.carddemo.fraud.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Scores one authorized transaction against every registered risk rule and returns one assessment.
 * No rule is skipped, so the result carries every identifier that triggered.
 *
 * <p>No COBOL (Common Business Oriented Language) program under {@code app/cbl/} scores risk or
 * counts authorization velocity, so this class has no ancestor there.
 *
 * <p>The chain shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment marks the extension point.
 * Shape only, no logic: that paragraph stops at its first failing test and keeps one reject code.
 *
 * <p>One assessment becomes one event: {@link FraudFlagged} when {@link RiskAssessment#flagged()}
 * holds. This class builds no event, publishes nothing and opens no transaction; the consumer that
 * calls it owns all three. The decisions behind this class are recorded in
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>The moment each assessment reports is read from an injected {@link Clock}, so a test can fix it
 * and the velocity bucket a run writes is the bucket the test names.
 *
 * <p>A rule that triggers is not by itself a flag. The score every triggered rule contributes to is
 * compared against {@code carddemo.fraud.risk.flag-threshold}, and only a total at or above that
 * threshold flags the transaction. A single rule worth less than the threshold therefore appears in
 * the assessment's rule list and on its row while the transaction publishes as
 * {@link com.carddemo.events.FraudCleared}, which is the whole purpose of having a threshold.
 */
@Service
public class RiskScoringService {

    /** Lowest score the published assessment contract admits. */
    private static final int MINIMUM_RISK_SCORE = FraudFlagged.MINIMUM_RISK_SCORE;

    /** Highest score the published assessment contract admits. */
    private static final int MAXIMUM_RISK_SCORE = FraudFlagged.MAXIMUM_RISK_SCORE;

    /** Lowest useful threshold: zero would flag a score with no contributing rule. */
    private static final int MINIMUM_FLAG_THRESHOLD = 1;

    /**
     * Width of one window row, and the unit an event time truncates to for its bucket. The row that
     * holds it declares it, so the rule that reads these rows reads the same unit.
     */
    private static final ChronoUnit WINDOW_BUCKET = VelocityWindowEntity.WINDOW_BUCKET;

    /** Rows the window statement writes on either of its arms. */
    private static final int ROWS_ONE_UPSERT_WRITES = 1;

    /** Value the event amount is truncated against, at the scale the amount column holds. */
    private static final BigDecimal OPENING_TOTAL = new BigDecimal("0.00");

    /** Every risk rule, in the order the framework supplied them. */
    private final List<RiskRule> rules;

    /** Runs the atomic velocity-window upsert and reads the rows a velocity rule evaluates. */
    private final VelocityWindowRepository velocityWindows;

    /** Score at or above which the assessment is flagged. */
    private final int flagThreshold;

    /** Reads the instant each assessment reports as the moment it was scored. */
    private final Clock clock;

    /**
     * Takes every risk rule, the window store and the bound settings the threshold comes from.
     *
     * <p>The threshold is read once here rather than at each assessment, so one running instance
     * scores every transaction against one policy. It is read from the bound
     * {@link FraudProperties} record rather than from a separate {@code @Value} injection, so the
     * one place {@code carddemo.fraud.risk.flag-threshold} is declared is the one place it is
     * validated.
     *
     * <p>The range is checked here as well as by the bean validation the record carries, because a
     * caller can build the record directly. A threshold outside the range the event contract
     * permits for a risk score could never be reached, which would make every assessment cleared.
     *
     * @param rules           every rule the framework supplied, kept in the order supplied. An
     *                        empty collection scores every transaction as cleared
     * @param velocityWindows store the atomic window update runs against
     * @param properties      the bound {@code carddemo} block, read for
     *                        {@code fraud.risk.flag-threshold}
     * @throws NullPointerException     if any argument, or any rule, is null
     * @throws IllegalArgumentException if the configured threshold is outside the range the event
     *                                  contract permits for a risk score
     */
    @Autowired
    public RiskScoringService(List<RiskRule> rules, VelocityWindowRepository velocityWindows,
            FraudProperties properties) {
        this(rules, velocityWindows, properties, Clock.systemUTC());
    }

    /**
     * Takes the rules, the window store, the bound settings and the clock each assessment reads.
     *
     * @param rules           every rule the framework supplied, kept in the order supplied. An
     *                        empty collection scores every transaction as cleared
     * @param velocityWindows store the atomic window update runs against
     * @param properties      the bound {@code carddemo} block, read for
     *                        {@code fraud.risk.flag-threshold}
     * @param clock           the clock each assessment reads the moment scored from
     * @throws NullPointerException     if any argument, or any rule, is null
     * @throws IllegalArgumentException if the configured threshold is outside the range the event
     *                                  contract permits for a risk score
     */
    RiskScoringService(List<RiskRule> rules, VelocityWindowRepository velocityWindows,
            FraudProperties properties, Clock clock) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.velocityWindows = Objects.requireNonNull(velocityWindows, "velocityWindows");
        int configured = Objects.requireNonNull(properties, "properties")
                .fraud().risk().flagThreshold();
        if (configured < MINIMUM_FLAG_THRESHOLD || configured > MAXIMUM_RISK_SCORE) {
            throw new IllegalArgumentException("carddemo.fraud.risk.flag-threshold must be between "
                    + MINIMUM_FLAG_THRESHOLD + " and " + MAXIMUM_RISK_SCORE);
        }
        this.flagThreshold = configured;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Scores one authorized transaction.
     *
     * <p>The current event is added to its velocity bucket before any rule runs. Every rule then runs
     * once, in the order the framework supplied it, and the configured score threshold determines
     * the verdict.
     *
     * @param event the authorized transaction to score
     * @return the score, the verdict, the identifier of each rule that triggered, and the moment
     *         scored
     * @throws NullPointerException     if {@code event} is null
     * @throws IllegalArgumentException if two rules report one identifier
     * @throws IllegalStateException    if the window statement reports a row count other than
     *                                  one
     */
    public RiskAssessment assess(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event");
        Instant assessedAt = clock.instant();
        recordVelocityWindow(event, assessedAt);

        List<String> triggeredRules = new ArrayList<>();
        long accumulatedScore = 0L;
        for (RiskRule rule : rules) {
            RiskRule.Contribution contribution = rule.evaluate(event);
            accumulatedScore = Math.min(
                    MAXIMUM_RISK_SCORE, accumulatedScore + contribution.points());
            if (contribution.triggered()) {
                triggeredRules.add(rule.ruleId());
            }
        }
        int riskScore = (int) Math.max(MINIMUM_RISK_SCORE, accumulatedScore);

        return new RiskAssessment(event.transactionId(), event.accountId(), riskScore,
                riskScore >= flagThreshold,
                triggeredRules, assessedAt);
    }

    /**
     * Atomically counts one authorization into the window row for the account and the bucket.
     *
     * <p>The bucket start is the event time truncated to {@code WINDOW_BUCKET}. The amount is
     * truncated to transaction scale after taking its absolute value. A duplicate delivery counts
     * once: the consumer checks {@code processed_event} before it calls this class.
     *
     * @param event      the authorized transaction being counted
     * @param recordedAt the moment written to the row
     * @throws IllegalStateException if the statement reports a row count other than one
     */
    private void recordVelocityWindow(TransactionAuthorized event, Instant recordedAt) {
        Instant bucketStart = event.occurredAt().truncatedTo(WINDOW_BUCKET);
        BigDecimal magnitude = CobolDecimal.add(OPENING_TOTAL, event.amount().abs(),
                PicClause.TRAN_AMT_SCALE);
        int updated = velocityWindows.addAuthorization(
                event.accountId(), bucketStart, magnitude, recordedAt);
        if (updated != ROWS_ONE_UPSERT_WRITES) {
            throw new IllegalStateException("velocity window update affected an unexpected row count");
        }
    }

    /**
     * One risk assessment: the score, the identifier of each rule that triggered, the threshold the
     * score was compared against, and the moment scored. ADDITIVE IN FULL: net new; no COBOL
     * ancestor.
     *
     * <p>The verdict travels with the score, and {@link RiskScoringService#assess} is the one place
     * that derives it: {@code riskScore >= flagThreshold}, against the threshold this instance was
     * configured with. Nothing else recomputes it, so a stored verdict cannot disagree with the
     * policy that produced it.
     *
     * @param transactionId  the transaction assessed, from {@code TRAN-ID PIC X(16)} at
     *                       {@code app/cpy/CVTRA05Y.cpy:L5}
     * @param accountId      the account the card resolved to, from
     *                       {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
     * @param riskScore      the summed score, held inside the range the assessment contract
     *                       permits. A count, not money
     * @param flagged        whether {@code riskScore} reached the configured threshold, derived once
     *                       in {@link RiskScoringService#assess}
     * @param triggeredRules the identifier of each rule that contributed points, in evaluation
     *                       order, immutable and empty when none triggered
     * @param assessedAt     the moment the assessment was made
     */
    public record RiskAssessment(
            String transactionId,
            String accountId,
            int riskScore,
            boolean flagged,
            List<String> triggeredRules,
            Instant assessedAt) {

        /**
         * Copies the rule list, and refuses a score or a threshold outside the contract range, or a
         * repeated identifier.
         *
         * <p>A failure names the failing field by its JavaScript Object Notation (JSON) pointer and
         * carries no value.
         *
         * @throws NullPointerException     if any reference component, or any element of
         *                                  {@code triggeredRules}, is null
         * @throws IllegalArgumentException if {@code riskScore} falls outside the contract range, or
         *                                  if one identifier appears twice
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

    }
}
