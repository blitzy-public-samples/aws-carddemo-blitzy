package com.carddemo.fraud.domain;

import com.carddemo.events.TransactionAuthorized;

/**
 * The extension seam for risk scoring. One implementation scores one pattern and returns one
 * {@link Contribution}.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The CardDemo source holds no risk scoring, no
 * pattern analysis and no rules engine.
 *
 * <p>The chain shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code :L377} comment marks the extension point.
 * Shape only, no logic: this interface copies no reject code, no reject text and no arithmetic
 * from that program.
 *
 * <p>An implementation is free of side effects. It may read through the sibling {@code repository}
 * interfaces, and it performs no insert, no update, no delete and no publish. It holds no mutable
 * state, so one instance serves every call.
 */
public interface RiskRule {

    /**
     * Scores one pattern against one authorized transaction.
     *
     * <p>A rule that needs a wall-clock moment reads {@code event.envelope().occurredAt()}.
     *
     * @param event the authorized transaction to score
     * @return whether this rule triggered, and the points it adds
     */
    Contribution evaluate(TransactionAuthorized event);

    /**
     * The stable identifier this rule reports when it triggers.
     *
     * <p>The published assessment contract permits three values: {@code VELOCITY},
     * {@code AMOUNT_ANOMALY} and {@code MERCHANT_CATEGORY}.
     *
     * @return the identifier of this rule
     */
    String ruleId();

    /**
     * What one rule contributes to a risk assessment: whether it triggered, and the points it adds.
     *
     * @param triggered whether the rule matched the transaction
     * @param points    the points this contribution adds, never negative, and zero when
     *                  {@code triggered} is false
     */
    record Contribution(boolean triggered, int points) {

        /**
         * Rejects a negative point count, and a contribution that carries points without
         * triggering.
         *
         * <p>A failure names the failing component by its JavaScript Object Notation (JSON)
         * pointer and never carries the value.
         *
         * @throws IllegalArgumentException when {@code points} is negative, or when
         *                                  {@code triggered} is false and {@code points} is not
         *                                  zero
         */
        public Contribution {
            if (points < 0) {
                throw new IllegalArgumentException("/points counts up from zero");
            }
            if (!triggered && points != 0) {
                throw new IllegalArgumentException("/points is zero when /triggered is false");
            }
        }

        /**
         * A contribution from a rule that matched, carrying the points it adds.
         *
         * @param points the points this contribution adds, never negative
         * @return a contribution that triggered
         * @throws IllegalArgumentException when {@code points} is negative
         */
        public static Contribution triggeredWith(int points) {
            return new Contribution(true, points);
        }

        /**
         * A contribution from a rule that did not match, carrying no points.
         *
         * @return a contribution that did not trigger, carrying zero points
         */
        public static Contribution notTriggered() {
            return new Contribution(false, 0);
        }
    }
}
