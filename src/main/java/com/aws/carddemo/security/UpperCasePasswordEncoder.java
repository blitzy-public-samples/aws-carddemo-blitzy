/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

/**
 * A {@link PasswordEncoder} that folds the supplied raw password to upper case
 * ({@link Locale#ROOT}) before delegating to a wrapped hashing encoder (BCrypt by
 * default). It is the <strong>single, canonical</strong> place where the legacy
 * CardDemo credential normalization is applied, so that <em>every</em> credential
 * comparison in the application &mdash; the CC00 sign-on screen
 * ({@code service/SignonService}), user administration
 * ({@code service/UserService}), and, critically, Spring Security's framework-built
 * {@code DaoAuthenticationProvider} that guards every protected HTTP endpoint via
 * HTTP&nbsp;Basic &mdash; uses one identical rule.
 *
 * <h2>Why the fold exists (behavioral parity)</h2>
 * <p>The legacy sign-on program folded the entered password to upper case before
 * the comparison:</p>
 * <pre>
 * MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD
 * </pre>
 * ({@code legacy/cbl/COSGN00C.cbl:L132-L136}). The sign-on contract is therefore
 * <em>case-insensitive on the password</em>, and the seed hashes in
 * {@code db/seed/user_security.csv} were derived from the upper-cased demo
 * password. Spring Security's {@code DaoAuthenticationProvider} compares the
 * <em>raw, exact-case</em> HTTP&nbsp;Basic password against the stored hash, so a
 * plain {@link BCryptPasswordEncoder} would accept a credential on the sign-on
 * screen (any case) yet reject the very same credential on the HTTP&nbsp;Basic gate
 * (all-upper-case only), locking the operator out of every API call. Applying the
 * fold <em>inside the encoder</em> makes the gate honor the same case-insensitive
 * contract as the sign-on screen, so the two authentication surfaces accept and
 * reject exactly the same credential set.
 *
 * <p>Because {@code SignonService} and {@code UserService} already upper-case the
 * password before invoking the encoder, the additional fold performed here is
 * <strong>idempotent</strong> ({@code UPPER(UPPER(x)) == UPPER(x)}); those explicit
 * service-level folds remain as self-documenting parity assertions and are
 * unaffected. The rationale for this normalization is recorded in
 * {@code docs/decision-log.md} (decision D26), keeping the "why" in the decision
 * log rather than in code comments.
 *
 * <h2>Delegation and confidentiality</h2>
 * <p>Hashing, salting, verification, and any work-factor upgrade decision are
 * delegated verbatim to the wrapped encoder; this class only transforms the raw
 * input's case. The raw or folded password is never logged, and {@code null} inputs
 * are passed through so the delegate applies its own argument contract (a
 * {@link BCryptPasswordEncoder} rejects a {@code null} raw password), preserving the
 * exact edge-case behavior of the unwrapped encoder.</p>
 *
 * @see PasswordEncoder
 * @see BCryptPasswordEncoder
 * @see CardDemoUserDetailsService
 */
public final class UpperCasePasswordEncoder implements PasswordEncoder {

    /**
     * The wrapped hashing encoder to which encoding, matching, and upgrade
     * decisions are delegated after the raw password has been folded to upper
     * case. Never {@code null}.
     */
    private final PasswordEncoder delegate;

    /**
     * Creates an encoder that folds to upper case and delegates to a new
     * {@link BCryptPasswordEncoder} with default settings (per-hash random salt and
     * default strength) &mdash; the production wiring used by
     * {@code config/SecurityConfig}.
     */
    public UpperCasePasswordEncoder() {
        this(new BCryptPasswordEncoder());
    }

    /**
     * Creates an encoder that folds to upper case and delegates to the supplied
     * hashing encoder. This constructor exists primarily for testing (so a test can
     * supply a deterministic or spy delegate) and for callers that wish to control
     * the BCrypt strength.
     *
     * @param delegate the hashing encoder to wrap; must not be {@code null}
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public UpperCasePasswordEncoder(PasswordEncoder delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /**
     * Folds {@code rawPassword} to upper case ({@link Locale#ROOT}) and delegates to
     * the wrapped encoder to produce the stored hash.
     *
     * @param rawPassword the raw password to encode
     * @return the delegate's hash of the upper-cased password
     */
    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(fold(rawPassword));
    }

    /**
     * Folds {@code rawPassword} to upper case ({@link Locale#ROOT}) and delegates to
     * the wrapped encoder to verify it against {@code encodedPassword}. This is the
     * method Spring Security's {@code DaoAuthenticationProvider} invokes for every
     * HTTP&nbsp;Basic authentication, so the fold applied here is what aligns the
     * gate with the case-insensitive CC00 sign-on contract.
     *
     * @param rawPassword     the presented raw password
     * @param encodedPassword the stored hash to verify against
     * @return {@code true} if the upper-cased raw password matches the stored hash
     */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return delegate.matches(fold(rawPassword), encodedPassword);
    }

    /**
     * Delegates the re-encode (work-factor upgrade) decision unchanged to the
     * wrapped encoder; the case fold does not affect whether a stored hash should be
     * upgraded.
     *
     * @param encodedPassword the stored hash to evaluate
     * @return whether the delegate recommends re-encoding the stored hash
     */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    /**
     * Folds a raw password to upper case using {@link Locale#ROOT} (a
     * locale-independent fold that avoids surprises such as the Turkish dotless-i).
     * A {@code null} input is returned unchanged so the delegate can apply its own
     * argument contract, matching the unwrapped encoder's behavior exactly.
     *
     * @param rawPassword the raw password (may be {@code null})
     * @return the upper-cased password, or {@code null} if the input was {@code null}
     */
    private static CharSequence fold(CharSequence rawPassword) {
        return rawPassword == null ? null : rawPassword.toString().toUpperCase(Locale.ROOT);
    }
}
