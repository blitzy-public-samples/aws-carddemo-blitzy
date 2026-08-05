package com.carddemo.fraud.domain.rules;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.domain.RiskRule;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Triggers when the transaction amount reaches the configured threshold, and reports
 * {@code AMOUNT_ANOMALY}.
 *
 * <p>No COBOL ancestor. The class shape follows
 * {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment
 * marks the extension point. Shape only, no logic: this class copies no reject code, no reject text
 * and no balance arithmetic from that program.
 *
 * <p>Risk is based on transaction magnitude. A refund therefore uses its absolute amount instead of
 * clearing this rule by carrying a negative sign. This service is net new, so that fraud-specific
 * treatment does not alter the ledger's source-compatible refund arithmetic.
 */
@Component
@Order(20)
public class AmountAnomalyRule implements RiskRule {

    /** The identifier this rule reports, one of the three the assessment contract permits. */
    private static final String RULE_ID = "AMOUNT_ANOMALY";

    /**
     * Points a triggered contribution carries. A demonstration value, and no measurement stands
     * behind it.
     */
    private static final int TRIGGERED_POINTS = 30;

    /**
     * The amount at or above which this rule triggers, held at the scale of
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    private final BigDecimal threshold;

    /**
     * Reads the threshold from configuration and truncates it to the scale of {@code TRAN-AMT}.
     *
     * <p>The key is the one {@code src/main/resources/application.yml} ships, and the default here
     * repeats the value that file carries.
     *
     * @param amountAnomalyThreshold the amount at or above which this rule triggers, as a decimal
     *                               string. A demonstration value, and no measurement stands behind
     *                               the shipped one
     * @throws NullPointerException  when the property resolves to no value
     * @throws NumberFormatException when the property holds text that is not a decimal number
     */
    public AmountAnomalyRule(
            @Value("${carddemo.fraud.risk.amount-anomaly-threshold:500.00}")
            String amountAnomalyThreshold) {
        Objects.requireNonNull(amountAnomalyThreshold,
                "carddemo.fraud.risk.amount-anomaly-threshold must be present");

        this.threshold = CobolDecimal.truncateToScale(
                new BigDecimal(amountAnomalyThreshold), PicClause.TRAN_AMT_SCALE);
    }

    /**
     * Compares the absolute event amount against the configured threshold.
     *
     * @param event the authorized transaction to score
     * @return a contribution carrying {@link #TRIGGERED_POINTS} when the amount reaches the
     *         threshold, and one carrying no points when it does not
     * @throws NullPointerException when {@code event} is {@code null}
     */
    @Override
    public Contribution evaluate(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event must be present");

        if (event.amount().abs().compareTo(threshold) >= 0) {
            return Contribution.triggeredWith(TRIGGERED_POINTS);
        }
        return Contribution.notTriggered();
    }

    /**
     * Reports the identifier of this rule.
     *
     * @return {@code AMOUNT_ANOMALY}
     */
    @Override
    public String ruleId() {
        return RULE_ID;
    }
}
