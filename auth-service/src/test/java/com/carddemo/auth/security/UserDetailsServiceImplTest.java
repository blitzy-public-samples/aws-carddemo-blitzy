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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.common.domain.SecurityUser;

/**
 * :purpose: Unit tests for {@link UserDetailsServiceImpl}, the Spring Security
 *     bridge re-platforming the legacy ``COSGN00C`` sign-on credential lookup
 *     and role assignment (``app/cbl/COSGN00C.cbl``; copybook
 *     ``app/cpy/CSUSR01Y.cpy``). The collaborating repository is mocked so the
 *     id upper-casing, user-not-found, role-mapping, and password-passthrough
 *     behaviors are verified in isolation.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    /**
     * :purpose: Frozen BCrypt hash fixture proving the stored credential is
     *     returned verbatim (never re-encoded or upper-cased).
     */
    private static final String BCRYPT_HASH =
            "$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm";

    @Mock
    private SecurityUserRepository securityUserRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsServiceImpl;

    /**
     * :purpose: Builds a ``SecurityUser`` fixture via the no-arg constructor and
     *     setters (no Lombok), mirroring a persistent ``security_users`` row.
     * :param id: the stored canonical (upper-case) user id.
     * :param pwd: the stored BCrypt password hash.
     * :param type: the raw single-character user type (``A`` or ``U``).
     * :returns: a populated ``SecurityUser`` instance.
     */
    private static SecurityUser newUser(String id, String pwd, String type) {
        SecurityUser user = new SecurityUser();
        user.setSecUsrId(id);
        user.setSecUsrPwd(pwd);
        user.setSecUsrType(type);
        return user;
    }

    @Test
    @DisplayName("loadUserByUsername upper-cases the id before the keyed lookup")
    void loadUserByUsername_upperCasesUserId_beforeRepositoryLookup() {
        when(securityUserRepository.findBySecUsrId("ADMIN001"))
                .thenReturn(Optional.of(newUser("ADMIN001", BCRYPT_HASH, "A")));

        UserDetails result = userDetailsServiceImpl.loadUserByUsername("admin001");

        assertNotNull(result);
        assertEquals("ADMIN001", result.getUsername());
        verify(securityUserRepository).findBySecUsrId("ADMIN001");
    }

    @Test
    @DisplayName("loadUserByUsername throws UsernameNotFoundException when absent")
    void loadUserByUsername_userNotFound_throwsUsernameNotFoundException() {
        when(securityUserRepository.findBySecUsrId("NOSUCHUSR"))
                .thenReturn(Optional.empty());

        UsernameNotFoundException ex = assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsServiceImpl.loadUserByUsername("nosuchusr"));

        assertEquals("User not found", ex.getMessage());
    }

    @Test
    @DisplayName("'A' user type maps to a single ROLE_ADMIN authority")
    void loadUserByUsername_adminType_grantsRoleAdmin() {
        when(securityUserRepository.findBySecUsrId("ADMIN001"))
                .thenReturn(Optional.of(newUser("ADMIN001", BCRYPT_HASH, "A")));

        UserDetails result = userDetailsServiceImpl.loadUserByUsername("ADMIN001");

        Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_ADMIN", authorities.iterator().next().getAuthority());
    }

    @Test
    @DisplayName("'U' user type maps to a single ROLE_USER authority")
    void loadUserByUsername_userType_grantsRoleUser() {
        when(securityUserRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(newUser("USER0001", BCRYPT_HASH, "U")));

        UserDetails result = userDetailsServiceImpl.loadUserByUsername("user0001");

        Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_USER", authorities.iterator().next().getAuthority());
    }

    @Test
    @DisplayName("Unknown (non-'A') user type defaults to ROLE_USER (COBOL ELSE branch)")
    void loadUserByUsername_unknownType_defaultsToRoleUser() {
        when(securityUserRepository.findBySecUsrId("GUEST001"))
                .thenReturn(Optional.of(newUser("GUEST001", BCRYPT_HASH, "X")));

        UserDetails result = userDetailsServiceImpl.loadUserByUsername("GUEST001");

        Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_USER", authorities.iterator().next().getAuthority());
    }

    @Test
    @DisplayName("Stored BCrypt hash is returned unchanged (password never upper-cased)")
    void loadUserByUsername_returnsStoredBcryptHash_unchanged() {
        when(securityUserRepository.findBySecUsrId("ADMIN001"))
                .thenReturn(Optional.of(newUser("ADMIN001", BCRYPT_HASH, "A")));

        UserDetails result = userDetailsServiceImpl.loadUserByUsername("admin001");

        assertEquals(BCRYPT_HASH, result.getPassword());
    }
}
