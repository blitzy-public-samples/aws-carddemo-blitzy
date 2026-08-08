package com.carddemo.authorization.domain;

/**
 * Raised when an authenticated caller may not authorize against the subject its request resolved to.
 *
 * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} authorizes a record the nightly feed supplied and has no
 * caller to entitle, and {@code app/cbl/COTRN02C.cbl} captures whatever card number the operator
 * typed. Neither compares the identity that asked against the account or card the request names, so
 * this refusal has no ancestor and is stated as an addition.
 *
 * <p>Why it cannot be a fifth reject reason. The four reject reasons at
 * {@code app/cbl/CBTRN02C.cbl:L385-L420} are outcomes of the transaction, and each one is published
 * as a {@code TransactionDeclined} event that downstream consumers act on. This refusal is an outcome
 * of the caller: no decision was taken, nothing was recorded and no event was written. Publishing it
 * as a decline would tell every consumer that a transaction was declined when no transaction was ever
 * decided, and it would put an account identifier the caller does not own onto a topic.
 *
 * <p>Why the message discloses nothing. {@link #DETAIL} is fixed text. A detail naming the account,
 * the card or the reason the check failed would confirm to an unentitled caller that the subject
 * exists, which is how a caller holding one credential enumerates the identifiers it does not hold.
 * {@code config/SecurityConfig} answers a route-level denial with the same text for the same reason,
 * so the two refusals read alike.
 *
 * <p>{@code api/GlobalExceptionHandler} answers {@code 403} and
 * {@code domain/AuthorizationService} counts the refusal under the
 * {@code entitlement} stage of its failure counter.
 */
public class CallerNotEntitledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The one text a refused caller receives, and the one text a route-level denial receives.
     *
     * <p>It names neither the operation nor any identifier. {@code config/SecurityConfig} writes this
     * same value from its access-denied handler, so a caller cannot tell a role refusal from an
     * ownership refusal by reading the body.
     */
    public static final String DETAIL = "This identity may not use this operation.";

    /**
     * Builds the refusal.
     *
     * <p>No constructor takes an account identifier, a card number or a card token. A value passed to
     * an exception reaches a stack trace and a log line, and every one of those three names a
     * cardholder the caller has just been refused access to.
     */
    public CallerNotEntitledException() {
        super(DETAIL);
    }
}
