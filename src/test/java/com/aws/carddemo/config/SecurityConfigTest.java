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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Pure unit test for {@link SecurityConfig}, the central Spring Security configuration of the
 * migrated AWS CardDemo application. This test pins the two parity-critical contracts that the
 * legacy z/OS sign-on model is translated into (Agent Action Plan &sect;0.6.5, &sect;0.6.6,
 * &sect;0.7.2):
 *
 * <ol>
 *   <li><b>BCrypt credential hygiene.</b> The legacy clear-text {@code SEC-USR-PWD PIC X(08)}
 *       comparison ({@code legacy/app/cbl/COSGN00C.cbl}: {@code IF SEC-USR-PWD = WS-USER-PWD}) is
 *       replaced by a one-way {@link BCryptPasswordEncoder} hash check. The {@link
 *       SecurityConfig#passwordEncoder()} bean must therefore be a BCrypt encoder whose hashes
 *       round-trip and never equal the raw secret.
 *   <li><b>Role-gating parity.</b> On a successful sign-on {@code COSGN00C} performs {@code MOVE
 *       SEC-USR-TYPE TO CDEMO-USER-TYPE} and branches {@code IF CDEMO-USRTYP-ADMIN} ({@code 'A'},
 *       copybook {@code COCOM01Y}) to the admin menu {@code COADM01C}, otherwise to the main menu
 *       {@code COMEN01C}. The {@link SecurityConfig#userDetailsService(UserSecurityRepository)}
 *       bean must reproduce this mapping: type {@code 'A'} yields authority {@code ROLE_ADMIN}; any
 *       other type yields {@code ROLE_USER}; a missing record raises {@link
 *       UsernameNotFoundException} (the legacy "user not found" path).
 * </ol>
 *
 * <p><strong>Scope.</strong> This is a deliberately pure unit test: it instantiates {@link
 * SecurityConfig} directly and exercises the {@code passwordEncoder} and {@code userDetailsService}
 * beans against a plain Mockito mock of {@link UserSecurityRepository}. It does <em>not</em> start
 * a Spring context, a servlet environment, or Testcontainers. Building a real {@link HttpSecurity}
 * to exercise the {@code securityFilterChain} body requires a Spring web context, so the
 * filter-chain bean is verified here only at the contract level (declared method, {@link Bean}
 * annotation, and return type) via reflection; the end-to-end HTTP access-rule behaviour (the
 * public {@code POST /signon} permitAll plus CSRF, the {@code /admin/**} and user-management {@code
 * hasRole('ADMIN')} gates, and the {@code anyRequest().authenticated()} form-login routing) is
 * covered by the sibling {@link SecurityFilterChainTest} ({@code @WebMvcTest} driving real requests
 * through the imported {@link SecurityConfig} filter chain) and by the per-controller
 * {@code @WebMvcTest} web-layer tests under {@code com.aws.carddemo.web} (each asserting its
 * route's CSRF and role gating).
 *
 * <p><strong>Credential hygiene in the test itself.</strong> Every raw secret used here is a
 * freshly generated {@link UUID}; no password value is ever hardcoded or logged (Agent Action Plan
 * &sect;0.7.2). A plain Mockito mock (not the strict {@code MockitoExtension}) is used so that the
 * per-test stubbing of {@link UserSecurityRepository#findById(Object)} does not trigger
 * unnecessary-stubbing failures.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SecurityConfigTest {

  private SecurityConfig securityConfig;
  private UserSecurityRepository userSecurityRepository;

  @BeforeEach
  void setUp() {
    this.securityConfig = new SecurityConfig();
    this.userSecurityRepository = mock(UserSecurityRepository.class);
  }

  @Test
  void password_encoder_is_bcrypt_and_round_trips() {
    PasswordEncoder encoder = securityConfig.passwordEncoder();
    assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);

    String rawSecret = UUID.randomUUID().toString(); // never a hardcoded credential
    String encoded = encoder.encode(rawSecret);
    assertThat(encoded).startsWith("$2").isNotEqualTo(rawSecret);
    assertThat(encoder.matches(rawSecret, encoded)).isTrue();
  }

  @Test
  void user_details_service_maps_type_A_to_role_admin() {
    UserSecurity admin = new UserSecurity();
    admin.setSecUsrId("ADMIN001");
    admin.setSecUsrPwd(new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
    admin.setSecUsrType("A");
    when(userSecurityRepository.findById("ADMIN001")).thenReturn(Optional.of(admin));

    UserDetailsService uds = securityConfig.userDetailsService(userSecurityRepository);
    UserDetails details = uds.loadUserByUsername("ADMIN001");

    assertThat(details.getUsername()).isEqualTo("ADMIN001");
    assertThat(details.getPassword()).isEqualTo(admin.getSecUsrPwd());
    assertThat(details.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void user_details_service_maps_type_U_to_role_user() {
    UserSecurity user = new UserSecurity();
    user.setSecUsrId("USER0001");
    user.setSecUsrPwd(new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
    user.setSecUsrType("U");
    when(userSecurityRepository.findById("USER0001")).thenReturn(Optional.of(user));

    UserDetailsService uds = securityConfig.userDetailsService(userSecurityRepository);
    UserDetails details = uds.loadUserByUsername("USER0001");

    assertThat(details.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
  }

  @Test
  void user_details_service_throws_when_user_is_not_found() {
    when(userSecurityRepository.findById("MISSING01")).thenReturn(Optional.empty());
    UserDetailsService uds = securityConfig.userDetailsService(userSecurityRepository);

    assertThatThrownBy(() -> uds.loadUserByUsername("MISSING01"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void is_annotated_for_web_and_method_security() {
    assertThat(SecurityConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
    assertThat(SecurityConfig.class.isAnnotationPresent(EnableWebSecurity.class)).isTrue();
    assertThat(SecurityConfig.class.isAnnotationPresent(EnableMethodSecurity.class)).isTrue();
    assertThat(SecurityConfig.class.getAnnotation(EnableMethodSecurity.class).prePostEnabled())
        .isTrue();
  }

  @Test
  void exposes_a_security_filter_chain_bean() throws Exception {
    Method method =
        SecurityConfig.class.getDeclaredMethod("securityFilterChain", HttpSecurity.class);
    assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
    assertThat(method.getReturnType()).isEqualTo(SecurityFilterChain.class);
  }
}
