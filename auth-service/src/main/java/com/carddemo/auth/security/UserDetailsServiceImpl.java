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

import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.security.UserIdNormalizer;
import java.util.Collections;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * :purpose: Bridges the persistent ``SecurityUser`` (table ``security_users``) to
 *     Spring Security's ``UserDetails`` model. Replaces the ``USRSEC`` keyed read
 *     and role assignment performed by the ``READ-USER-SEC-FILE`` paragraph of the
 *     legacy sign-on program ``COSGN00C``. Loads a user by upper-cased id and
 *     returns the stored encoded credential together with the mapped role
 *     authority; credential verification is delegated to the authentication
 *     provider.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final String ADMIN_TYPE = "A";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_USER = "ROLE_USER";

    private final SecurityUserRepository securityUserRepository;

    public UserDetailsServiceImpl(SecurityUserRepository securityUserRepository) {
        this.securityUserRepository = securityUserRepository;
    }

    /**
     * :purpose: Loads the security user identified by the entered id and adapts it
     *     to a Spring Security ``UserDetails``.
     * :param username: the entered user id; folded to its canonical stored form by
     *     {@link UserIdNormalizer} before the keyed lookup, so it matches an id written
     *     by any other service.
     * :returns: a ``UserDetails`` carrying the stored BCrypt hash and the mapped
     *     role authority.
     * :raises UsernameNotFoundException: when no user matches the upper-cased id.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String userId = UserIdNormalizer.normalizeToKey(username);
        SecurityUser user = securityUserRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new User(
                user.getSecUsrId(),
                user.getSecUsrPwd(),
                Collections.singletonList(mapAuthority(user.getSecUsrType())));
    }

    /**
     * :purpose: Maps the raw single-character user type to a granted role authority.
     * :param secUsrType: the raw ``SEC-USR-TYPE`` value (``A`` or ``U``).
     * :returns: a ``GrantedAuthority`` of ``ROLE_ADMIN`` for ``A``, otherwise
     *     ``ROLE_USER``.
     */
    private GrantedAuthority mapAuthority(String secUsrType) {
        if (ADMIN_TYPE.equals(secUsrType)) {
            return new SimpleGrantedAuthority(ROLE_ADMIN);
        }
        return new SimpleGrantedAuthority(ROLE_USER);
    }
}
