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
package com.aws.carddemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardDemoUserDetailsService}, the
 * Java re-platform of the record-load half of the CardDemo COBOL sign-on program
 * {@code COSGN00C} (paragraph {@code READ-USER-SEC-FILE}).
 *
 * <p>The service has a single collaborator ({@link UserSecurityRepository}), which is
 * mocked here so the tests exercise the service's own behavior in isolation &mdash; no
 * Spring context, no database, no Testcontainers. They lock down the behavioral-parity
 * contract enumerated in the file's implementation checklist:</p>
 * <ul>
 *   <li><strong>Username normalization</strong> &mdash; the id is upper-cased only
 *       ({@code FUNCTION UPPER-CASE} parity; COSGN00C does not trim), so {@code "admin001"}
 *       resolves the {@code "ADMIN001"} record and the repository is queried with the
 *       upper-cased key, while a space-padded id is upper-cased but <em>not</em> trimmed &mdash;
 *       matching the CC00 sign-on service so both authentication surfaces resolve the same key.</li>
 *   <li><strong>Role mapping</strong> &mdash; {@code SEC-USR-TYPE 'A'} yields authority
 *       {@code "ROLE_ADMIN"}; every other value (here {@code 'U'}) yields {@code "ROLE_USER"}.</li>
 *   <li><strong>Not-found</strong> &mdash; an empty repository result raises
 *       {@link UsernameNotFoundException}, the analog of the COBOL {@code WHEN 13}
 *       ({@code NOTFND}) branch.</li>
 *   <li><strong>Blank input</strong> &mdash; {@code null} or blank usernames raise
 *       {@link UsernameNotFoundException} without touching the repository.</li>
 *   <li><strong>Credential pass-through</strong> &mdash; the stored (hashed) credential is
 *       copied onto the principal untouched for the framework's {@code PasswordEncoder}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDemoUserDetailsService — COSGN00C READ-USER-SEC-FILE lookup/adapt parity")
class CardDemoUserDetailsServiceTest {

    /**
     * A representative encoded-credential placeholder. It is intentionally NOT a real
     * hash or secret; the tests only assert that whatever the entity holds is passed
     * through to the principal untouched.
     */
    private static final String ENCODED_CREDENTIAL = "{bcrypt}$2a$10$PLACEHOLDERplaceholderPLACEHO";

    /** Mocked data-access collaborator standing in for the {@code user_security} table. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** System under test, wired via constructor injection (single-constructor bean). */
    @InjectMocks
    private CardDemoUserDetailsService service;

    /**
     * Builds a {@link UserSecurity} fixture using the entity's business-field constructor.
     *
     * @param id   the primary-key user id (already upper-cased, as stored)
     * @param type the single-character role code ({@code "A"} or {@code "U"})
     * @return a populated, unpersisted {@link UserSecurity}
     */
    private static UserSecurity user(String id, String type) {
        return new UserSecurity(id, "First", "Last", ENCODED_CREDENTIAL, type);
    }

    @Test
    @DisplayName("admin ('A') resolves to ROLE_ADMIN and the id is upper-cased before lookup")
    void loadsAdminPrincipalAndUpperCasesUsername() {
        when(userSecurityRepository.findBySecUsrId("ADMIN001"))
                .thenReturn(Optional.of(user("ADMIN001", "A")));

        UserDetails principal = service.loadUserByUsername("admin001");

        assertThat(principal.getUsername()).isEqualTo("ADMIN001");
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        assertThat(principal).isInstanceOf(CardDemoUserDetails.class);
        assertThat(((CardDemoUserDetails) principal).getRole()).isEqualTo(UserRole.ADMIN);

        // Parity check: the repository must be queried with the upper-cased key.
        verify(userSecurityRepository).findBySecUsrId("ADMIN001");
    }

    @Test
    @DisplayName("standard user ('U') resolves to ROLE_USER")
    void loadsStandardUserPrincipal() {
        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(user("USER0001", "U")));

        UserDetails principal = service.loadUserByUsername("user0001");

        assertThat(principal.getUsername()).isEqualTo("USER0001");
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        assertThat(((CardDemoUserDetails) principal).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("stored credential is passed through to the principal untouched")
    void passesStoredCredentialThroughUntouched() {
        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(user("USER0001", "U")));

        UserDetails principal = service.loadUserByUsername("user0001");

        // The service must not encode, hash, or otherwise mutate the credential; the
        // DaoAuthenticationProvider / PasswordEncoder verifies it downstream.
        assertThat(principal.getPassword()).isEqualTo(ENCODED_CREDENTIAL);
    }

    @Test
    @DisplayName("username is upper-cased WITHOUT trimming before the keyed lookup (CC00 parity)")
    void upperCasesWithoutTrimmingBeforeLookup() {
        // COSGN00C applies FUNCTION UPPER-CASE to the entered id and only that - it
        // does not strip surrounding spaces. The service must therefore query the
        // repository with the upper-cased but UNTRIMMED id, so the HTTP Basic gate
        // and the CC00 sign-on service (SignonService, which also does not trim)
        // resolve the same key and neither silently accepts a space-padded id the
        // other would reject. The stub is keyed on the untrimmed value; had the
        // service still trimmed, it would query "ADMIN001" (unstubbed -> empty) and
        // raise UsernameNotFoundException, so this test is discriminating.
        when(userSecurityRepository.findBySecUsrId("  ADMIN001  "))
                .thenReturn(Optional.of(user("ADMIN001", "A")));

        service.loadUserByUsername("  admin001  ");

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userSecurityRepository).findBySecUsrId(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("  ADMIN001  ");
    }

    @Test
    @DisplayName("missing user raises UsernameNotFoundException (COBOL WHEN 13 / NOTFND)")
    void throwsWhenUserNotFound() {
        when(userSecurityRepository.findBySecUsrId("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nope"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("null or blank username raises UsernameNotFoundException without querying the repository")
    void throwsWhenUsernameNullOrBlank(String candidate) {
        assertThatThrownBy(() -> service.loadUserByUsername(candidate))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(userSecurityRepository);
    }
}
