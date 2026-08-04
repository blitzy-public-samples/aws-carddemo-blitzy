package com.carddemo.fraud.domain.rules;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.domain.RiskRule;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scores one authorized transaction on its merchant category code, and triggers when the configured
 * set of elevated-risk categories holds that code.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The scored field is not additive:
 * {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7} declares it.
 *
 * <p>The chain shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment marks the extension point.
 * Shape only, no logic: no reject code, no reject text and no arithmetic cross from that program.
 *
 * <p>The code is four digits held as text, so a leading zero belongs to the value. Membership in
 * {@link #ELEVATED_RISK_CATEGORIES} is the whole test: no lookup, no reference table and no join.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Component
@Order(30)
public class MerchantCategoryRule implements RiskRule {

    /**
     * The identifier this rule reports when it triggers. {@code schemas/fraud-flagged-v1.json}
     * permits three values, and {@code MERCHANT_CATEGORY} is one.
     */
    private static final String RULE_ID = "MERCHANT_CATEGORY";

    /**
     * The category codes that trigger this rule. Demonstration values: no measurement stands behind
     * the list. Every code holds four digits as text, and the set is immutable and admits no null.
     */
    private static final Set<String> ELEVATED_RISK_CATEGORIES = Set.of("0001", "0002", "0004");

    /**
     * The points a triggered contribution carries. A demonstration value: no measurement stands
     * behind it. A score runs from zero to one hundred, and
     * {@code carddemo.fraud.risk.flag-threshold} sets which score is flagged.
     */
    private static final int RISK_POINTS = 25;

    /**
     * Tests the merchant category code of one event for membership in
     * {@link #ELEVATED_RISK_CATEGORIES}. A code the set does not hold adds no points.
     *
     * @param event the authorized transaction to score
     * @return a triggered contribution carrying {@link #RISK_POINTS} when the set holds the code,
     *         and a contribution carrying no points when it does not
     */
    @Override
    public Contribution evaluate(TransactionAuthorized event) {
        String categoryCode = event.merchantCategoryCode();
        if (categoryCode != null && ELEVATED_RISK_CATEGORIES.contains(categoryCode)) {
            return Contribution.triggeredWith(RISK_POINTS);
        }
        return Contribution.notTriggered();
    }

    /**
     * The identifier a triggered contribution from this rule reports.
     *
     * @return {@link #RULE_ID}
     */
    @Override
    public String ruleId() {
        return RULE_ID;
    }
}
