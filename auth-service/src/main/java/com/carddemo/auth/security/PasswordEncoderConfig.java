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
package com.carddemo.auth.security;

import com.carddemo.common.security.PasswordEncoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * :purpose: Exposes the application-wide one-way password encoder that replaces
 *           the legacy plaintext credential comparison performed by ``COSGN00C``
 *           against the VSAM ``USRSEC`` security file.
 * :note: Delegates to the shared {@link PasswordEncoderFactory} so every CardDemo
 *        service uses one tuned, upgradeable encoding policy: a
 *        ``DelegatingPasswordEncoder`` that encodes with ``{bcrypt}`` at the tuned
 *        strength and stores the algorithm identifier with each hash, enabling
 *        transparent rehash-on-authentication if the policy changes. Callers must
 *        enforce the frozen ``PIC X(8)`` password width at the DTO boundary
 *        (see ``SignonRequestDto``) before invoking the encoder.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * :returns: the shared ``PasswordEncoder`` bean (a delegating ``{bcrypt}``
     *           encoder from {@link PasswordEncoderFactory}) used to hash new
     *           passwords and to verify sign-on credentials.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactory.createDelegatingPasswordEncoder();
    }
}
