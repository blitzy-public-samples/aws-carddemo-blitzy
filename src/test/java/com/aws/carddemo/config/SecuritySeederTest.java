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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.config.SecuritySeeder.SeedProperties;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure unit test for {@link SecuritySeeder}, the bootstrap credential seeder that replaces the
 * legacy clear-text VSAM {@code USRSEC} credential model with hashed, externalized credentials
 * (Agent Action Plan &sect;0.6.6, &sect;0.7.2, &sect;0.7.3).
 *
 * <p>This is the primary regression guard for credential hygiene. It pins three parity-critical
 * behaviours of the {@link CommandLineRunner} returned by {@link
 * SecuritySeeder#seedSecurityUsers(UserSecurityRepository, PasswordEncoder, SeedProperties)}:
 *
 * <ol>
 *   <li><b>BCrypt hashing, never plaintext.</b> The legacy clear-text {@code SEC-USR-PWD PIC X(08)}
 *       comparison in {@code legacy/app/cbl/COSGN00C.cbl} ({@code IF SEC-USR-PWD = WS-USER-PWD}) is
 *       replaced by a one-way BCrypt hash. Each seeded {@link UserSecurity} must carry a genuine
 *       BCrypt hash (prefix {@code $2}) that round-trips via {@link PasswordEncoder#matches} and is
 *       never equal to the raw secret.
 *   <li><b>Idempotency.</b> When the users already exist the seeder must persist nothing, so
 *       application restarts neither fail on a duplicate key nor overwrite a (possibly rotated)
 *       row.
 *   <li><b>Skip-on-missing-password.</b> When no externalized password is supplied for a user, that
 *       user is skipped rather than being created with an empty or fabricated credential.
 * </ol>
 *
 * <p><strong>Design.</strong> The test exercises the runner with a plain Mockito-mocked {@link
 * UserSecurityRepository} (no {@code MockitoExtension}/strict stubbing, so unused stubs never raise
 * {@code UnnecessaryStubbingException}) and a <em>real</em> {@link BCryptPasswordEncoder} so the
 * persisted hashes are genuine and {@code matches(raw, hash)} is meaningful. Saved entities are
 * captured and filtered by {@code getSecUsrId()}, making the assertions robust to seeding order.
 *
 * <p><strong>Security.</strong> Raw passwords are random {@link UUID} values; no literal password
 * is ever hardcoded, logged, or asserted against. Assertions only verify the BCrypt prefix,
 * inequality to the raw value, and a successful {@code matches} round-trip.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SecuritySeederTest {

  private UserSecurityRepository repository;
  private PasswordEncoder passwordEncoder;
  private SecuritySeeder securitySeeder;
  private SeedProperties seedProperties;
  private String adminSecret;
  private String userSecret;

  @BeforeEach
  void setUp() {
    this.repository = mock(UserSecurityRepository.class);
    this.passwordEncoder = new BCryptPasswordEncoder();
    this.securitySeeder = new SecuritySeeder();
    this.seedProperties = new SeedProperties();
    this.adminSecret = UUID.randomUUID().toString(); // never hardcoded / never logged
    this.userSecret = UUID.randomUUID().toString();
    this.seedProperties.setAdminPassword(adminSecret);
    this.seedProperties.setUserPassword(userSecret);
  }

  @Test
  void seed_properties_expose_legacy_default_identities() {
    SeedProperties defaults = new SeedProperties();
    assertThat(defaults.getAdminId()).isEqualTo("ADMIN001");
    assertThat(defaults.getAdminType()).isEqualTo("A");
    assertThat(defaults.getUserId()).isEqualTo("USER0001");
    assertThat(defaults.getUserType()).isEqualTo("U");
  }

  @Test
  void seeds_both_users_with_bcrypt_hashed_passwords_when_absent() throws Exception {
    when(repository.existsById(anyString())).thenReturn(false);

    CommandLineRunner runner =
        securitySeeder.seedSecurityUsers(repository, passwordEncoder, seedProperties);
    runner.run();

    ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
    verify(repository, times(2)).save(captor.capture());
    List<UserSecurity> saved = captor.getAllValues();

    UserSecurity admin =
        saved.stream().filter(u -> "ADMIN001".equals(u.getSecUsrId())).findFirst().orElseThrow();
    UserSecurity user =
        saved.stream().filter(u -> "USER0001".equals(u.getSecUsrId())).findFirst().orElseThrow();

    assertThat(admin.getSecUsrType()).isEqualTo("A");
    assertThat(user.getSecUsrType()).isEqualTo("U");

    // BCrypt-hashed, never the raw value.
    assertThat(admin.getSecUsrPwd()).startsWith("$2").isNotEqualTo(adminSecret);
    assertThat(user.getSecUsrPwd()).startsWith("$2").isNotEqualTo(userSecret);
    assertThat(passwordEncoder.matches(adminSecret, admin.getSecUsrPwd())).isTrue();
    assertThat(passwordEncoder.matches(userSecret, user.getSecUsrPwd())).isTrue();
  }

  @Test
  void is_idempotent_and_saves_nothing_when_users_already_exist() throws Exception {
    when(repository.existsById(anyString())).thenReturn(true);

    CommandLineRunner runner =
        securitySeeder.seedSecurityUsers(repository, passwordEncoder, seedProperties);
    runner.run();

    verify(repository, never()).save(any());
  }

  @Test
  void skips_a_user_when_its_password_is_missing() throws Exception {
    when(repository.existsById(anyString())).thenReturn(false);
    seedProperties.setAdminPassword(null); // admin password absent -> admin skipped, user seeded

    CommandLineRunner runner =
        securitySeeder.seedSecurityUsers(repository, passwordEncoder, seedProperties);
    runner.run();

    ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
    verify(repository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getSecUsrId()).isEqualTo("USER0001");
  }
}
