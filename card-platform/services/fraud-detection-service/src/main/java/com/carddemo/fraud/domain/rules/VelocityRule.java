package com.carddemo.fraud.domain.rules;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.domain.RiskRule;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Counts one account's recent authorizations and triggers when the count or the totalled amount
 * reaches its threshold.
 *
 * <p>No COBOL (Common Business Oriented Language) program under {@code app/cbl/} counts
 * authorization velocity. No COBOL ancestor.
 *
 * <p>The rule-object shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment marks the extension point:
 * shape only, no logic.
 *
 * <p>A call reads {@code velocity_window} and writes nothing. {@code RiskScoringService} atomically
 * adds the current event before every rule runs, so the decision includes the event being assessed.
 * The {@code processed_event} check in the sibling {@code messaging} package runs first, so a
 * repeated delivery counts once. One account's events all carry its identifier as the Kafka message
 * key, so one partition holds them in publish order.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(10)
public class VelocityRule implements RiskRule {

    /** The identifier this rule reports, and one of the three values an assessment may carry. */
    private static final String RULE_ID = "VELOCITY";

    /**
     * Points this rule contributes when triggered, on the zero-to-one-hundred scale a risk score
     * carries. Demonstration value: no measurement stands behind it.
     */
    private static final int TRIGGERED_POINTS = 30;

    /**
     * Totalled amount at or above which this rule triggers, at the two-digit scale of
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}. Demonstration value: no
     * measurement stands behind it. Stored totals are magnitudes, so a refund raises the velocity
     * total by its absolute amount instead of lowering it.
     */
    private static final BigDecimal AMOUNT_THRESHOLD = new BigDecimal("2500.00");

    /** Where totalling starts, at the scale {@link #AMOUNT_THRESHOLD} carries. */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /** Reads the window rows. This rule holds no other collaborator. */
    private final VelocityWindowRepository velocityWindows;

    /**
     * Configured width of the span this rule reads, taken back from the moment the event was
     * written. A stored row spans one whole {@link VelocityWindowEntity#WINDOW_BUCKET}, and the row
     * carries the start of that span, so the span start is truncated to the same unit. The span
     * this rule evaluates therefore covers the configured width and at most one bucket more.
     */
    private final Duration lookback;

    /** Authorizations in the span at or above which this rule triggers. */
    private final int countThreshold;

    /**
     * Takes the window reader and the two configured values.
     *
     * @param velocityWindows the reader of {@code velocity_window}
     * @param lookbackMinutes value of {@code carddemo.fraud.risk.velocity-window-minutes}, one
     *                        minute or more
     * @param countThreshold  value of {@code carddemo.fraud.risk.velocity-count-threshold}, one
     *                        authorization or more
     * @throws NullPointerException     when {@code velocityWindows} is absent
     * @throws IllegalArgumentException when either configured value falls below one. The message
     *                                  names the property key and carries no value
     */
    public VelocityRule(VelocityWindowRepository velocityWindows,
            @Value("${carddemo.fraud.risk.velocity-window-minutes:60}") int lookbackMinutes,
            @Value("${carddemo.fraud.risk.velocity-count-threshold:5}") int countThreshold) {
        this.velocityWindows = Objects.requireNonNull(velocityWindows, "velocityWindows");
        if (lookbackMinutes < 1) {
            throw new IllegalArgumentException(
                    "carddemo.fraud.risk.velocity-window-minutes counts up from one");
        }
        if (countThreshold < 1) {
            throw new IllegalArgumentException(
                    "carddemo.fraud.risk.velocity-count-threshold counts up from one");
        }
        this.lookback = Duration.ofMinutes(lookbackMinutes);
        this.countThreshold = countThreshold;
    }

    /**
     * Totals the account's window rows from the span start onwards, then compares the
     * authorization count and the amount against their thresholds. The span start is the moment the
     * event was written, less the configured width, truncated to the bucket unit; a bucket starting
     * exactly there counts. Truncating keeps the whole bucket that holds the configured width
     * inside the span, so two events one millisecond apart read the same rows.
     *
     * @param event the authorized transaction to score
     * @return a triggered contribution when either total reaches its threshold, and a contribution
     *         that did not trigger when neither does or when the account holds no row in the span
     * @throws NullPointerException when {@code event} is absent
     */
    @Override
    public Contribution evaluate(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event must be present");

        Instant boundary = event.envelope().occurredAt().minus(lookback)
                .truncatedTo(VelocityWindowEntity.WINDOW_BUCKET);
        List<VelocityWindowEntity> windows = velocityWindows
                .findByAccountIdAndWindowStartGreaterThanEqual(event.accountId(), boundary);
        if (windows.isEmpty()) {
            return Contribution.notTriggered();
        }

        long authorizations = 0L;
        BigDecimal totalled = ZERO_AMOUNT;
        for (VelocityWindowEntity window : windows) {
            authorizations += window.getAuthorizationCount();
            totalled = CobolDecimal.add(totalled, window.getTotalAmount().abs(),
                    PicClause.TRAN_AMT_SCALE);
        }

        if (authorizations >= countThreshold || totalled.compareTo(AMOUNT_THRESHOLD) >= 0) {
            return Contribution.triggeredWith(TRIGGERED_POINTS);
        }
        return Contribution.notTriggered();
    }

    /**
     * Reports the identifier a triggered assessment records for this rule.
     *
     * @return {@code VELOCITY}
     */
    @Override
    public String ruleId() {
        return RULE_ID;
    }
}
