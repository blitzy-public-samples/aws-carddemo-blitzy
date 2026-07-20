/*
 * AWS CardDemo - Java migration.
 *
 * Origin: no direct COBOL source. This type is a web-tier confirmation-integrity control
 * introduced by the Java migration to restore, over stateless HTTP, the BMS "protected
 * confirmation field" contract that the 3270/CICS presentation enforced implicitly. See
 * {@link com.aws.carddemo.web.support.ConfirmationTokenService} and the decision log entry for
 * "single-use confirmation token + server-owned target".
 */
package com.aws.carddemo.web.support;

import java.io.Serializable;

/**
 * Immutable, session-stored record of a pending two-step confirmation (review finding F12 /
 * CWE-639, CWE-384-adjacent, CWE-20). It binds a destructive/committing gesture (a user update,
 * a user delete, or a bill payment) to <em>server-owned</em> state so that the commit turn cannot
 * be re-aimed at a different target or replayed by a tampered client re-post.
 *
 * <p>Three components make up the binding:</p>
 * <ul>
 *   <li><b>{@code operation}</b> &mdash; a stable, low-cardinality label for the gesture
 *       (for example {@code "USER_UPDATE"}, {@code "USER_DELETE"}, {@code "BILLPAY"}). Matching it
 *       prevents a nonce armed for one screen from being spent on another.</li>
 *   <li><b>{@code target}</b> &mdash; the server-confirmed identity the commit must act upon (the
 *       looked-up user id, or the account id whose balance was displayed). It is captured from the
 *       server's own prompt-turn state, never trusted from the commit re-post, so a swapped
 *       {@code usridin}/{@code actidin} on the commit turn is rejected.</li>
 *   <li><b>{@code nonce}</b> &mdash; a single-use, high-entropy token (256 bits of
 *       {@link java.security.SecureRandom}, hex-encoded) armed when the confirm prompt is rendered
 *       and consumed on the commit turn, defeating replay of a captured confirmation.</li>
 * </ul>
 *
 * <p>Instances are held in the {@code HttpSession} (hence {@link Serializable}); they carry no
 * sensitive secret beyond the opaque nonce, and {@link #toString()} deliberately omits the
 * {@code target} and {@code nonce} so confirmation state cannot leak into logs (CWE-532).</p>
 *
 * @param operation the stable operation label the nonce is bound to (never {@code null})
 * @param target    the server-owned confirmation target the commit must act upon (never
 *                  {@code null}; stored trimmed by {@link ConfirmationTokenService})
 * @param nonce     the single-use confirmation nonce (never {@code null})
 * @see ConfirmationTokenService
 */
public record PendingConfirmation(String operation, String target, String nonce)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Canonical constructor enforcing the non-null invariant. A {@code null} component would
     * silently weaken the equality checks the service relies on to reject swaps and replays, so it
     * is rejected up front.
     *
     * @param operation the stable operation label the nonce is bound to
     * @param target    the server-owned confirmation target the commit must act upon
     * @param nonce     the single-use confirmation nonce
     * @throws NullPointerException if any component is {@code null}
     */
    public PendingConfirmation {
        if (operation == null || target == null || nonce == null) {
            throw new NullPointerException("operation, target and nonce must all be non-null");
        }
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name, the
     * (non-secret) operation label, and an opaque identity token. The {@code target} and
     * {@code nonce} are intentionally omitted so confirmation state never leaks into logs or error
     * messages (CWE-532).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "PendingConfirmation[operation=" + operation + ", @"
                + Integer.toHexString(System.identityHashCode(this)) + "]";
    }
}
