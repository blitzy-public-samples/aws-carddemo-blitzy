/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * :purpose: Central factory for the single, tuned password-encoding policy shared by every
 *     CardDemo service that authenticates users, replacing the legacy RACF/USRSEC plaintext
 *     comparison. It returns a Spring Security {@link DelegatingPasswordEncoder} that stores
 *     an algorithm identifier prefix (for example ``{bcrypt}``) with every hash, so the
 *     encoding algorithm can be rotated over time without invalidating existing credentials.
 * :note: Encoding always uses BCrypt at {@link #BCRYPT_STRENGTH}, the same cost factor
 *     carried by every stored credential, so verification cost does not reveal whether a user
 *     id exists; the map also registers PBKDF2 so credentials persisted under that identifier
 *     can still be verified and are transparently rehashed to the default algorithm on the
 *     next successful authentication (Spring Security calls {@link
 *     PasswordEncoder#upgradeEncoding(String)} and re-encodes when it returns ``true``). Only
 *     algorithms whose implementations are on the default classpath (BCrypt, PBKDF2) are
 *     registered, so no optional cryptographic provider is required.
 * :note: Callers must enforce the frozen ``PIC X(8)`` password width at the DTO boundary
 *     before invoking the encoder, so oversized input never reaches the adaptive hashing
 *     routine.
 * :note: Credentials migrated from the legacy ``USRSEC`` file are stored as BARE BCrypt
 *     hashes carrying no ``{id}`` prefix (see the ``security_users`` seed migration). A {@link
 *     DelegatingPasswordEncoder} rejects an unprefixed stored value with {@link
 *     IllegalArgumentException} unless a match-only fallback encoder is registered, so BCrypt
 *     is installed as that fallback through {@link
 *     DelegatingPasswordEncoder#setDefaultPasswordEncoderForMatches}. Without it no migrated
 *     user can sign on at all. Encoding is unaffected: new hashes are still written with the
 *     ``{bcrypt}`` prefix.
 */
public final class PasswordEncoderFactory {

    /**
     * :purpose: Algorithm identifier used to encode new passwords; stored as the
     *     ``{bcrypt}`` prefix on every freshly encoded hash.
     */
    public static final String DEFAULT_ENCODER_ID = "bcrypt";

    /**
     * :purpose: BCrypt cost factor (work factor) applied to new hashes. Set to the
     *     library default, which is the cost factor every stored CardDemo credential
     *     carries, so that a verification against a stored hash and a verification
     *     against the sign-on timing-equalization hash cost the same. A higher factor
     *     made an unknown user id measurably slower than a wrong password (a
     *     user-enumeration oracle) and pushed sign-on past the response-time budget of
     *     the non-functional requirements.
     */
    public static final int BCRYPT_STRENGTH = 10;

    /**
     * :purpose: Algorithm identifier under which legacy PBKDF2 hashes are matched
     *     and from which they are upgraded to {@link #DEFAULT_ENCODER_ID}.
     */
    public static final String PBKDF2_ENCODER_ID = "pbkdf2";

    /**
     * :purpose: Prevent instantiation of this stateless factory.
     */
    private PasswordEncoderFactory() {
    }

    /**
     * :purpose: Build the shared delegating password encoder with BCrypt as the
     *     encoding algorithm and PBKDF2 registered for verification/upgrade.
     * :returns: a {@link PasswordEncoder} that encodes with
     *     ``{bcrypt}`` at strength {@link #BCRYPT_STRENGTH}, verifies any
     *     registered ``{id}``-prefixed hash, verifies a bare (unprefixed) BCrypt
     *     hash migrated from ``USRSEC`` through the match-only fallback, and reports
     *     {@link PasswordEncoder#upgradeEncoding(String)} ``true`` for hashes not
     *     already produced by the default algorithm.
     */
    public static PasswordEncoder createDelegatingPasswordEncoder() {
        Map<String, PasswordEncoder> encoders = new LinkedHashMap<>();
        encoders.put(DEFAULT_ENCODER_ID, new BCryptPasswordEncoder(BCRYPT_STRENGTH));
        encoders.put(PBKDF2_ENCODER_ID, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        DelegatingPasswordEncoder delegatingPasswordEncoder =
                new DelegatingPasswordEncoder(DEFAULT_ENCODER_ID, encoders);
        // Bare BCrypt hashes carry no {id} prefix; BCrypt reads the cost factor out of
        // the hash itself, so this fallback verifies the seeded strength-10 values while
        // new hashes continue to be encoded at BCRYPT_STRENGTH under the {bcrypt} prefix.
        delegatingPasswordEncoder.setDefaultPasswordEncoderForMatches(
                new BCryptPasswordEncoder(BCRYPT_STRENGTH));
        return delegatingPasswordEncoder;
    }
}
