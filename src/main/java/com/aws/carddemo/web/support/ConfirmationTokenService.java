/*
 * AWS CardDemo - Java migration.
 *
 * Origin: no direct COBOL source. This service is a web-tier confirmation-integrity control
 * introduced by the Java migration to restore, over stateless HTTP, the implicit "protected
 * confirmation field" contract of the 3270/CICS presentation. See the decision log entry
 * "single-use confirmation token + server-owned target" (review finding F12).
 */
package com.aws.carddemo.web.support;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Session-scoped, single-use confirmation-token store shared by the confirm-then-commit online
 * flows (user update {@code CU02}, user delete {@code CU03}, bill payment {@code CB00}). It is the
 * web-tier realization of the BMS/3270 protected confirmation field: the mainframe operator could
 * only confirm the record the terminal had just displayed, so the confirmed target and the
 * "please confirm" gesture were server-owned. Over stateless HTTP those become a re-postable form,
 * which is the weakness review finding F12 (CWE-639 insecure direct object reference on the
 * confirmation target; CWE-20 insufficient validation of a security-relevant re-post) calls out:
 * "confirmation targets are round-tripped client values with no server-owned nonce/snapshot".
 *
 * <p>The control has two moves:</p>
 * <ol>
 *   <li><b>Arm</b> ({@link #arm(HttpSession, String, String)}) &mdash; when a controller renders a
 *       confirm prompt, it records, <em>server-side</em>, the operation, the target the commit must
 *       act upon (captured from the server's own state, not the client), and a fresh 256-bit nonce;
 *       the nonce is echoed onto the rendered form's hidden field.</li>
 *   <li><b>Validate &amp; consume</b> ({@link #validate(HttpSession, String, String, String)} then
 *       {@link #consume(HttpSession)}) &mdash; on the commit turn the controller checks the round-
 *       tripped nonce against the armed one (constant-time), the operation label, and the target,
 *       then clears the pending record so the confirmation cannot be replayed.</li>
 * </ol>
 *
 * <p>A swapped target (the client re-posts a different {@code usridin}/{@code actidin} on the commit
 * turn) fails the target check; a replayed or forged nonce fails the constant-time nonce check; a
 * missing arm (a client that tries to one-shot the commit without the server ever having displayed
 * the confirm prompt) fails because there is no pending record. In every rejection case the caller
 * re-displays the confirm prompt and re-arms, performing no write.</p>
 *
 * <p>Only one confirmation is pending per session at a time, which matches the pseudo-conversational
 * model (one screen in flight per terminal). Arming a new confirmation overwrites any prior one.</p>
 *
 * @see PendingConfirmation
 */
@Service
public class ConfirmationTokenService {

    /**
     * {@code HttpSession} attribute under which the single pending confirmation is held. The
     * fully-qualified class name is used to avoid collision with any other session attribute.
     */
    static final String SESSION_ATTRIBUTE = ConfirmationTokenService.class.getName() + ".PENDING";

    /** Nonce entropy in bytes (256 bits), hex-encoded to a 64-character token. */
    private static final int NONCE_BYTES = 32;

    /** Cryptographically strong RNG for nonce generation (thread-safe). */
    private static final SecureRandom NONCE_RNG = new SecureRandom();

    /**
     * Arms a pending confirmation for {@code operation} bound to {@code target}, replacing any
     * confirmation already pending in the session, and returns the freshly generated single-use
     * nonce for the caller to echo onto the rendered form.
     *
     * <p>{@code target} is captured from the server's own prompt-turn state (the looked-up user id
     * or the account whose balance was just displayed) and stored trimmed; it is what the commit
     * turn will be forced to act upon, so a later swapped re-post cannot re-aim the commit.</p>
     *
     * @param session   the current HTTP session (must not be {@code null})
     * @param operation the stable, low-cardinality operation label (must not be {@code null})
     * @param target    the server-owned confirmation target (must not be {@code null})
     * @return the newly armed single-use nonce (a 64-character hex string)
     * @throws NullPointerException if {@code session}, {@code operation} or {@code target} is
     *                              {@code null}
     */
    public String arm(HttpSession session, String operation, String target) {
        if (session == null) {
            throw new NullPointerException("session must not be null");
        }
        String nonce = newNonce();
        session.setAttribute(SESSION_ATTRIBUTE,
                new PendingConfirmation(operation, normalize(target), nonce));
        return nonce;
    }

    /**
     * Returns the server-owned target of the confirmation currently armed for {@code operation}, or
     * {@code null} when nothing is armed for it. Callers use this to force the commit onto the
     * server-confirmed identity rather than a client re-post.
     *
     * @param session   the current HTTP session (may be {@code null})
     * @param operation the operation label to look up (may be {@code null})
     * @return the armed target, or {@code null} when no matching confirmation is pending
     */
    public String armedTarget(HttpSession session, String operation) {
        PendingConfirmation pending = read(session);
        if (pending == null || operation == null || !pending.operation().equals(operation)) {
            return null;
        }
        return pending.target();
    }

    /**
     * Validates a commit-turn confirmation against the armed pending record without consuming it.
     * All three bindings must hold: the operation label must match, the presented target must equal
     * the armed (server-owned) target, and the presented nonce must match the armed nonce under a
     * constant-time comparison. A {@code null}/absent pending record, a swapped target, or a
     * missing/forged/replayed nonce all yield {@code false}.
     *
     * @param session         the current HTTP session (may be {@code null})
     * @param operation       the expected operation label (may be {@code null})
     * @param presentedTarget the target carried on the commit turn (may be {@code null})
     * @param presentedNonce  the confirmation nonce carried on the commit turn (may be {@code null})
     * @return {@code true} only when the operation, target and nonce all match the armed record
     */
    public boolean validate(HttpSession session, String operation,
                            String presentedTarget, String presentedNonce) {
        PendingConfirmation pending = read(session);
        if (pending == null || operation == null) {
            return false;
        }
        if (!pending.operation().equals(operation)) {
            return false;
        }
        if (!pending.target().equals(normalize(presentedTarget))) {
            return false;
        }
        return constantTimeEquals(pending.nonce(), presentedNonce);
    }

    /**
     * Clears any pending confirmation from the session, making the current nonce single-use. It is
     * safe to call when nothing is pending and safe to call with a {@code null} session.
     *
     * @param session the current HTTP session (may be {@code null})
     */
    public void consume(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    /**
     * Reads the pending confirmation from the session, or {@code null} when none is present or the
     * session is {@code null}.
     *
     * @param session the current HTTP session (may be {@code null})
     * @return the pending confirmation, or {@code null}
     */
    private static PendingConfirmation read(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object attribute = session.getAttribute(SESSION_ATTRIBUTE);
        return attribute instanceof PendingConfirmation pending ? pending : null;
    }

    /**
     * Normalizes a target for storage and comparison by trimming surrounding whitespace and mapping
     * {@code null} to the empty string, so a {@code null} target and a blank target compare equal
     * and fixed-width trailing spaces on a round-tripped id do not defeat the target check.
     *
     * @param target the raw target (may be {@code null})
     * @return the normalized, never-{@code null} target
     */
    private static String normalize(String target) {
        return target == null ? "" : target.trim();
    }

    /**
     * Generates a fresh 256-bit nonce, hex-encoded to a 64-character lowercase string.
     *
     * @return the newly generated nonce
     */
    private static String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        NONCE_RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Compares two strings for equality in time independent of the position of the first differing
     * byte, defeating timing side channels on the nonce comparison. A {@code null} argument yields
     * {@code false}.
     *
     * @param expected the armed value (may be {@code null})
     * @param actual   the presented value (may be {@code null})
     * @return {@code true} only when both are non-{@code null} and byte-for-byte equal
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
