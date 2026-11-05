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
package com.cardemo.config;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that bridges Spring Security's authenticated principal into the
 * request-scoped {@link CardDemoContext} bean.
 *
 * <p>In the legacy COBOL/CICS application, the sign-on program (COSGN00C.cbl)
 * populated the COMMAREA fields {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE}
 * upon successful authentication. These values were then carried through every
 * subsequent CICS transaction via the COMMAREA structure (COCOM01Y.cpy).</p>
 *
 * <p>In the Spring Boot migration, the {@code CardDemoContext} replaces the
 * COMMAREA but is {@code @RequestScope} — a fresh instance is created per request
 * with all fields initialized to null/zero. Without this filter, services that
 * check {@code cardDemoContext.isAdmin()} or read {@code cardDemoContext.getUserId()}
 * would always see null values, breaking all admin-gated operations.</p>
 *
 * <p>This filter runs after Spring Security's authentication filters. It reads
 * the authenticated principal from the {@link SecurityContextHolder} and populates
 * the CardDemoContext with:</p>
 * <ul>
 *   <li>{@code userId} — from {@link Authentication#getName()}</li>
 *   <li>{@code userType} — derived from granted authorities:
 *       {@code ROLE_ADMIN} → {@link UserType#ADMIN},
 *       otherwise → {@link UserType#USER}</li>
 * </ul>
 *
 * <p>This is the critical bridge between Spring Security and the COBOL-migrated
 * service layer. Without it, the following services fail:</p>
 * <ul>
 *   <li>{@code UserListService} — throws SecurityException (Issue #7)</li>
 *   <li>{@code UserAddService} — rejects as non-admin (Issue #8)</li>
 *   <li>{@code UserUpdateService} — rejects as non-admin (Issue #9)</li>
 *   <li>{@code UserDeleteService} — throws SecurityException (Issue #9)</li>
 *   <li>{@code AccountUpdateService} — cannot identify user context</li>
 * </ul>
 *
 * @see CardDemoContext
 * @see SecurityConfig
 * @see CardDemoUserDetailsService
 */
@Component
public class CardDemoContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CardDemoContextFilter.class);

    /** Spring Security role name for admin users. */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final ObjectProvider<CardDemoContext> cardDemoContextProvider;

    /**
     * Constructs the filter with a lazy provider for the request-scoped
     * CardDemoContext bean.
     *
     * <p>Uses {@link ObjectProvider} instead of direct injection so that the
     * filter can be instantiated even when {@code CardDemoContext} is not
     * available in the application context (e.g., in {@code @WebMvcTest}
     * sliced test contexts where only controller-layer beans are scanned).
     * At runtime in a full application context, the provider resolves to the
     * request-scoped proxy bound to the current HTTP request.</p>
     *
     * @param cardDemoContextProvider lazy provider for the request-scoped
     *                                CardDemoContext
     */
    public CardDemoContextFilter(ObjectProvider<CardDemoContext> cardDemoContextProvider) {
        this.cardDemoContextProvider = cardDemoContextProvider;
    }

    /**
     * Populates the CardDemoContext from the Spring Security authentication
     * on every authenticated request.
     *
     * <p>Maps to COBOL COSGN00C.cbl paragraph {@code 2000-SIGNIN-PROGRAM}
     * which sets:</p>
     * <pre>
     *   MOVE WS-USER-ID    TO CDEMO-USER-ID      → cardDemoContext.setUserId(name)
     *   MOVE SEC-USR-TYPE   TO CDEMO-USER-TYPE    → cardDemoContext.setUserType(type)
     * </pre>
     *
     * @param request     the HTTP servlet request
     * @param response    the HTTP servlet response
     * @param filterChain the filter chain to continue processing
     * @throws ServletException if a servlet error occurs
     * @throws IOException      if an I/O error occurs
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Resolve the request-scoped CardDemoContext lazily. In @WebMvcTest
        // sliced contexts the bean may not be available — in that case the
        // filter is a no-op and simply continues the chain.
        CardDemoContext cardDemoContext = cardDemoContextProvider.getIfAvailable();
        if (cardDemoContext == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {

            // Populate userId from authenticated principal name
            // Maps to: MOVE WS-USER-ID TO CDEMO-USER-ID (COSGN00C.cbl line 233)
            String username = authentication.getName();
            cardDemoContext.setUserId(username);

            // Determine user type from granted authorities
            // Maps to: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE (COSGN00C.cbl line 236)
            boolean isAdmin = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(ROLE_ADMIN::equals);

            cardDemoContext.setUserType(isAdmin ? UserType.ADMIN : UserType.USER);

            log.debug("CardDemoContext populated from authentication: userId='{}', userType={}",
                    username, cardDemoContext.getUserType());
        }

        filterChain.doFilter(request, response);
    }
}
