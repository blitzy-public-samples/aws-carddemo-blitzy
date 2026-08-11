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

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.auth.repository.SecurityUserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * :purpose: Provision a usable credential for the seeded USRSEC accounts whose stored
 *     password is the locked sentinel written by Flyway migration
 *     ``V10__lock_seeded_credentials.sql``, from a password injected at deployment time.
 * :output: On start-up, either nothing (the default), or an encoded credential written to
 *     every seeded account that still carries the sentinel. The number of accounts provisioned
 *     is logged; the password never is.
 * :note: The legacy USRSEC list ships ten accounts that all shared one credential
 *     ('PASSWORD'), which made a known administrator password active on every deployment. V10
 *     replaced that hash with a sentinel that no input can match, and this component is the
 *     ONLY supported way back to a usable credential. It is opt-in twice over - the flag must
 *     be set AND a password supplied - so a deployment that says nothing gets locked accounts
 *     rather than a default one.
 * :note: Enabling the flag without a password is a start-up failure, not a warning. A
 *     silent no-op would present exactly the same symptom as a successful provisioning attempt
 *     (accounts that cannot sign on) and would be diagnosed as a credential problem instead of
 *     a configuration one.
 */
@Component
public class SeedCredentialProvisioner implements ApplicationRunner {

    /**
     * :purpose: Stored value that marks a seeded account with no usable credential.
     *  It is intentionally not a syntactically valid BCrypt hash, so
     *  ``BCryptPasswordEncoder.matches`` rejects every input without comparing.
     *  Kept character-for-character in step with V10__lock_seeded_credentials.sql.
     */
    public static final String LOCKED_SENTINEL = "{bcrypt}$2a$10$locked-seeded-credential-not-provisioned!";

    /**
     * :purpose: Frozen credential width. ``SEC-USR-PWD`` is ``PIC X(08)``
     *  [app/cpy/CSUSR01Y.cpy], and the sign-on map field is eight characters wide
     *  [app/bms/COSGN00.bms], so a longer injected password could never be typed on
     *  the screen it is meant to unlock.
     */
    private static final int PASSWORD_WIDTH = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger(SeedCredentialProvisioner.class);

    /** Repository over ``security_users``. */
    private final SecurityUserRepository securityUserRepository;

    /** Application-wide delegating encoder; the same one sign-on verifies against. */
    private final PasswordEncoder passwordEncoder;

    /** Whether seed-credential provisioning is enabled for this deployment. */
    private final boolean enabled;

    /** Password to provision; blank unless injected by the deployment. */
    private final String seedPassword;

    /**
     * :purpose: Construct the provisioner from its collaborators and the two
     *  deployment-supplied settings that govern it.
     * :param securityUserRepository: repository over the seeded USRSEC accounts.
     * :param passwordEncoder: encoder that produces the stored credential form.
     * :param enabled: ``carddemo.security.seed-credentials.enabled``; false unless
     *  the deployment opts in.
     * :param seedPassword: ``carddemo.security.seed-credentials.password``, bound
     *  from ``CARDDEMO_SEED_USER_PASSWORD``; empty unless the deployment supplies it.
     */
    public SeedCredentialProvisioner(
            SecurityUserRepository securityUserRepository,
            PasswordEncoder passwordEncoder,
            @Value("${carddemo.security.seed-credentials.enabled:false}") boolean enabled,
            @Value("${carddemo.security.seed-credentials.password:}") String seedPassword) {
        this.securityUserRepository = securityUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.seedPassword = seedPassword;
    }

    /**
     * :purpose: Apply the injected credential to every seeded account still holding
     *  the locked sentinel.
     * :param args: the application arguments; not consulted.
     * :raises IllegalStateException: when provisioning is enabled but no password
     *  was supplied, or the supplied password is wider than the frozen eight
     *  characters.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            // Stated at INFO because "no seeded account can sign on" must be a
            // recorded, explainable state rather than a surprise at the sign-on screen.
            LOGGER.info("Seed-credential provisioning is disabled; the {} seeded USRSEC "
                            + "accounts remain locked. Set carddemo.security.seed-credentials.enabled=true "
                            + "with CARDDEMO_SEED_USER_PASSWORD to provision them, or create an "
                            + "administrator through user-service.",
                    countLocked());
            return;
        }
        if (seedPassword == null || seedPassword.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.seed-credentials.enabled is true but no password was supplied. "
                            + "Set CARDDEMO_SEED_USER_PASSWORD, or disable seed-credential provisioning.");
        }
        if (seedPassword.length() > PASSWORD_WIDTH) {
            throw new IllegalStateException(
                    "CARDDEMO_SEED_USER_PASSWORD must be at most " + PASSWORD_WIDTH
                            + " characters: SEC-USR-PWD is PIC X(08) and the sign-on map field is "
                            + PASSWORD_WIDTH + " characters wide, so a longer value could never be entered.");
        }

        List<SecurityUser> locked = securityUserRepository.findAll().stream()
                .filter(user -> LOCKED_SENTINEL.equals(user.getSecUsrPwd()))
                .toList();
        if (locked.isEmpty()) {
            LOGGER.info("Seed-credential provisioning is enabled but no account carries the locked "
                    + "sentinel; every credential has already been provisioned or rotated.");
            return;
        }
        String encoded = passwordEncoder.encode(seedPassword);
        for (SecurityUser user : locked) {
            user.setSecUsrPwd(encoded);
        }
        securityUserRepository.saveAll(locked);
        // The ids are logged (they are not secret and an operator needs to know which
        // accounts were touched); the password is not.
        LOGGER.info("Provisioned the injected seed credential for {} seeded USRSEC account(s): {}",
                locked.size(), locked.stream().map(SecurityUser::getSecUsrId).sorted().toList());
    }

    /**
     * :purpose: Count the seeded accounts that still hold the locked sentinel, for
     *  the disabled-path log line.
     * :returns: the number of locked seeded accounts.
     */
    private long countLocked() {
        return securityUserRepository.findAll().stream()
                .filter(user -> LOCKED_SENTINEL.equals(user.getSecUsrPwd()))
                .count();
    }
}
