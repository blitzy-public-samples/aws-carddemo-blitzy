package com.carddemo.authorization.domain;

import java.time.Duration;

/**
 * Raised when a decision would have to be taken against a projection row older than the configured
 * window allows.
 *
 * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl:L382-L383} and {@code :L395-L396} issue keyed reads against
 * the cross-reference and account datasets themselves, so the source authorizes against the records
 * and has nothing that can go stale. This service authorizes against two copies kept current by
 * state-change events, and a copy whose events stopped arriving keeps answering with whatever it last
 * knew.
 *
 * <p>Which makes the failure silent rather than loud, and that is the condition this type reports. A
 * stale credit limit or a stale pair of cycle accumulators raises nothing on its own: the credit-limit
 * rule at {@code app/cbl/CBTRN02C.cbl:L403-L407} computes an answer from obsolete numbers and approves
 * a transaction the current numbers would have declined.
 *
 * <p>This is not a reject reason. {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code app/cbl/CBTRN02C.cbl:L181} holds the four values of {@code :L385-L420} and no fifth, so a
 * refusal on freshness grounds cannot be expressed as one. No decision row is written and no event is
 * published: {@code api/GlobalExceptionHandler} answers {@code 503}, and the caller may present the
 * same request again once the projection catches up.
 *
 * <p>The message names the window and the projection and quotes no account identifier, no card number
 * and no observation time, so a caller that logs it records no cardholder value.
 */
public class StaleReplicaException extends RuntimeException {

    /** Serialization identity, fixed so a rolling deployment reads one form. */
    private static final long serialVersionUID = 1L;

    /**
     * Builds the refusal, naming the window that lapsed.
     *
     * @param maxStaleness the window from {@code carddemo.replica.max-staleness}
     */
    public StaleReplicaException(Duration maxStaleness) {
        super("the card_xref and account_credit_snapshot rows this decision reads were last"
                + " observed longer than " + maxStaleness + " ago, so this call is refused rather"
                + " than decided against a projection that may be obsolete");
    }
}
