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
package com.aws.carddemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Security configuration for the CardDemo application.
 *
 * <p>This class supplies the application-wide {@link PasswordEncoder} bean that the
 * authentication collaborators depend on. It is the single, canonical declaration of that
 * bean referenced by {@code com.aws.carddemo.security.CardDemoUserDetailsService} and consumed
 * (via constructor injection) by {@code com.aws.carddemo.service.SignonService} (the {@code CC00}
 * sign-on flow, legacy {@code COSGN00C}) and {@code com.aws.carddemo.service.UserService}
 * (the {@code CU01}/{@code CU02} user-administration flows, legacy {@code COUSR01C}/{@code COUSR02C}).
 * Without it those beans cannot be constructed and the application context cannot start.
 *
 * <h2>Why a hashing encoder (documented deviation, AAP &sect;0.7 hotspot L1)</h2>
 * <p>The legacy mainframe design stored user passwords in the {@code USRSEC} VSAM dataset in
 * <em>plaintext</em> (an intentional demonstration anti-pattern). Reproducing plaintext storage
 * verbatim would be an insecure regression, so the migration contract records password hashing as
 * an explicit, documented security improvement rather than a silent behavior change: credentials
 * are verified through {@link PasswordEncoder#matches(CharSequence, String)} against a
 * {@link BCryptPasswordEncoder} hash. The observable authentication contract — role {@code A} =
 * Admin and role {@code U} = User, derived from the {@code COCOM01Y} condition names — is preserved
 * unchanged; only the on-storage credential representation is hardened.
 *
 * <h2>Scope</h2>
 * <p>This configuration deliberately contributes <strong>only</strong> the {@link PasswordEncoder}
 * bean. It does not declare an HTTP {@code SecurityFilterChain}, so Spring Boot's security
 * auto-configuration remains in effect exactly as before; the encoder is a pure collaborator that
 * carries no connection strings or secrets, honoring the "no hardcoded credentials" constraint
 * (AAP &sect;0.8.1).
 */
@Configuration
public class SecurityConfig {

    /**
     * The application-wide password encoder used to verify and (for user administration) encode
     * {@code USRSEC} credentials.
     *
     * <p>A {@link BCryptPasswordEncoder} is used: it applies a per-hash random salt and an adaptive
     * work factor, so equal plaintext passwords produce distinct hashes and verification is
     * performed with {@link PasswordEncoder#matches(CharSequence, String)}. This is the "BCrypt bean"
     * the authentication collaborators document as their expected dependency.
     *
     * @return the singleton {@link BCryptPasswordEncoder} shared across the authentication and
     *         user-administration services
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
