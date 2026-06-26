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

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * Bootstrap credential seeder for the migrated AWS CardDemo application. This {@link Configuration}
 * is the single, dedicated place where the two default sign-on identities are created at runtime,
 * replacing the legacy clear-text VSAM {@code USRSEC} credential model (copybook {@code
 * legacy/app/cpy/CSUSR01Y.cpy}, field {@code SEC-USR-PWD PIC X(08)}) and the shipped default
 * credentials {@code ADMIN001 / USER0001 = PASSWORD} documented in {@code README.md} (L156-158)
 * with hashed, externalized credentials (Agent Action Plan &sect;0.6.6, &sect;0.7.2).
 *
 * <h2>What it seeds</h2>
 *
 * <p>On context startup a {@link CommandLineRunner} inserts at most two rows into the {@code
 * user_security} table (entity {@link UserSecurity}):
 *
 * <ul>
 *   <li>an administrator &mdash; default id {@code ADMIN001}, type {@code 'A'} ({@code
 *       CDEMO-USRTYP-ADMIN}); and
 *   <li>a standard user &mdash; default id {@code USER0001}, type {@code 'U'} ({@code
 *       CDEMO-USRTYP-USER}).
 * </ul>
 *
 * <p>The legacy {@code SEC-USER-DATA} record carries a first and last name ({@code SEC-USR-FNAME
 * PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)}); both are {@code NOT NULL} in the Flyway schema, so
 * the seeder always supplies a (non-secret, defaulted) display name for each user. The seeded ids
 * are already uppercase, matching the way the sign-on program {@code COSGN00C} upper-cases the
 * entered user id before reading {@code USRSEC}, so the rows created here are directly loadable by
 * the {@code UserDetailsService} declared in {@link SecurityConfig}.
 *
 * <h2>Security hardening</h2>
 *
 * <p>This class never hardcodes, defaults, or logs a plaintext password and never embeds a literal
 * BCrypt hash (Agent Action Plan &sect;0.7.2; OWASP gate &sect;0.7.3). Raw passwords flow in
 * exclusively through the env-bound {@link SeedProperties} block ({@code carddemo.security.seed.*}
 * in {@code application.yml}, mapped to {@code ${CARDDEMO_ADMIN_PASSWORD:}} / {@code
 * ${CARDDEMO_USER_PASSWORD:}}). Each raw password is one-way hashed with the application-wide
 * {@link PasswordEncoder} (the {@code BCryptPasswordEncoder} bean from {@link SecurityConfig})
 * before it is persisted; only the BCrypt hash is ever written to {@code sec_usr_pwd} (a {@code
 * varchar(60)} column). This is the only place seed users &mdash; and therefore default credentials
 * &mdash; are created: the Flyway migration {@code V2__seed_reference_data.sql} seeds only the four
 * reference tables and intentionally contains no user rows or credentials, and {@link
 * SecurityConfig} only declares the encoder and the lookup/authorization policy.
 *
 * <h2>Idempotency and safe-when-unset behaviour</h2>
 *
 * <p>The seeder is idempotent: before inserting it checks {@link
 * UserSecurityRepository#existsById(Object)} and skips any id that already exists, so application
 * restarts never fail on a duplicate key and never overwrite an existing (possibly
 * password-rotated) row. It is also safe when a seed password is not configured: if a password
 * property is blank or absent (the committed default of {@code application.yml} resolves the
 * passwords to blank when the environment variables are unset), that user is skipped with a warning
 * rather than being created with an empty or fabricated password. The application therefore boots
 * cleanly in every environment &mdash; with seeding when passwords are supplied (for example the
 * {@code test} profile provides non-blank defaults so integration tests get two usable sign-on
 * identities), and without creating any unusable user when they are not.
 *
 * <h2>PII / secret hygiene</h2>
 *
 * <p>No Lombok {@code @ToString}/{@code @Data} is applied and no log statement includes a password
 * (raw or hashed): success and skip messages emit only the user id and type. {@link SeedProperties}
 * relies on the default {@link Object#toString()}, so the bound password values are never rendered
 * through a generated {@code toString()}.
 */
@Configuration
@EnableConfigurationProperties(SecuritySeeder.SeedProperties.class)
public class SecuritySeeder {

  /**
   * SLF4J logger for non-secret bootstrap diagnostics. Only the seed user id and type are ever
   * logged; raw and hashed passwords are never emitted.
   */
  private static final Logger log = LoggerFactory.getLogger(SecuritySeeder.class);

  /**
   * Registers the startup seeding action. The returned {@link CommandLineRunner} runs once after
   * the application context is fully initialized &mdash; therefore after Flyway has created the
   * (empty) {@code user_security} table and after JPA schema validation &mdash; and seeds the
   * administrator and standard user in turn by delegating to {@link
   * #seedUser(UserSecurityRepository, PasswordEncoder, String, String, String, String, String)}.
   *
   * <p>The {@link UserSecurityRepository} and {@link PasswordEncoder} (the BCrypt bean from {@link
   * SecurityConfig}) are injected by Spring; {@link SeedProperties} is bound from the {@code
   * carddemo.security.seed.*} configuration block.
   *
   * @param repository the Spring Data JPA repository backing the {@code user_security} table
   * @param passwordEncoder the application-wide BCrypt encoder used to hash raw seed passwords
   * @param props the env-bound seed identities and (externalized) raw passwords
   * @return a {@link CommandLineRunner} that idempotently seeds the two default users on startup
   */
  @Bean
  public CommandLineRunner seedSecurityUsers(
      UserSecurityRepository repository, PasswordEncoder passwordEncoder, SeedProperties props) {
    return args -> {
      seedUser(
          repository,
          passwordEncoder,
          props.getAdminId(),
          props.getAdminType(),
          props.getAdminFname(),
          props.getAdminLname(),
          props.getAdminPassword());
      seedUser(
          repository,
          passwordEncoder,
          props.getUserId(),
          props.getUserType(),
          props.getUserFname(),
          props.getUserLname(),
          props.getUserPassword());
    };
  }

  /**
   * Idempotently seeds a single security user, hashing the supplied raw password with BCrypt before
   * persisting. The method enforces two guard conditions before any write:
   *
   * <ol>
   *   <li><b>Idempotency.</b> If a row with the given id already exists, the method logs and
   *       returns without modifying it &mdash; restarts neither fail nor overwrite existing
   *       credentials.
   *   <li><b>Missing-password policy.</b> If the raw password is {@code null} or blank, the method
   *       logs a warning (with no secret content) and returns without inserting. No empty or
   *       fabricated password is ever stored, and the application still boots normally.
   * </ol>
   *
   * <p>When both guards pass, a fully populated {@link UserSecurity} is built &mdash; every column
   * of {@code user_security} is {@code NOT NULL}, so the id, type, first name, last name, and the
   * BCrypt-hashed password are all set &mdash; and saved. Only the hash produced by {@link
   * PasswordEncoder#encode(CharSequence)} is persisted; the raw password is never stored or logged.
   *
   * @param repository the repository used for the existence check and the insert
   * @param passwordEncoder the BCrypt encoder applied to {@code rawPassword}
   * @param id the user id / primary key ({@code SEC-USR-ID}); expected already uppercase
   * @param type the role flag ({@code SEC-USR-TYPE}): {@code "A"} for admin, {@code "U"} for user
   * @param fname the display first name ({@code SEC-USR-FNAME}); not a secret
   * @param lname the display last name ({@code SEC-USR-LNAME}); not a secret
   * @param rawPassword the externalized raw password to hash; the user is skipped if blank/absent
   */
  private static void seedUser(
      UserSecurityRepository repository,
      PasswordEncoder passwordEncoder,
      String id,
      String type,
      String fname,
      String lname,
      String rawPassword) {
    if (repository.existsById(id)) {
      log.info("Seed user {} already present; skipping", id);
      return;
    }
    if (!StringUtils.hasText(rawPassword)) {
      log.warn(
          "Seed password for {} not provided; skipping seed (set the corresponding env var)", id);
      return;
    }
    UserSecurity user = new UserSecurity();
    user.setSecUsrId(id);
    user.setSecUsrType(type);
    user.setSecUsrFname(fname);
    user.setSecUsrLname(lname);
    user.setSecUsrPwd(passwordEncoder.encode(rawPassword));
    repository.save(user);
    log.info("Seeded security user {} (type {})", id, type);
  }

  /**
   * Typed binding of the {@code carddemo.security.seed.*} configuration block (see {@code
   * application.yml} and the {@code test} profile {@code application-test.yml}). Holds the two seed
   * identities and their externalized raw passwords.
   *
   * <p>The ids, role types, and display names carry sensible non-secret defaults, so a minimal
   * configuration only needs to supply the passwords. The password fields deliberately have <b>no
   * in-code default</b>: their only source is the configuration (which maps them to the {@code
   * CARDDEMO_ADMIN_PASSWORD} / {@code CARDDEMO_USER_PASSWORD} environment variables, defaulting to
   * blank when unset). A blank password causes the corresponding user to be skipped (see {@link
   * SecuritySeeder#seedUser}); no credential is ever hardcoded here.
   */
  @ConfigurationProperties(prefix = "carddemo.security.seed")
  public static class SeedProperties {

    /** Administrator user id ({@code SEC-USR-ID}). Default {@code ADMIN001}. */
    private String adminId = "ADMIN001";

    /**
     * Administrator role flag ({@code SEC-USR-TYPE}). Default {@code A} ({@code
     * CDEMO-USRTYP-ADMIN}).
     */
    private String adminType = "A";

    /**
     * Administrator raw password. No in-code default: supplied via {@code
     * carddemo.security.seed.admin-password} (env {@code CARDDEMO_ADMIN_PASSWORD}). Blank/absent
     * causes the admin user to be skipped. Never logged; BCrypt-hashed before persistence.
     */
    private String adminPassword;

    /**
     * Administrator display first name ({@code SEC-USR-FNAME}); not a secret. Default {@code
     * Admin}.
     */
    private String adminFname = "Admin";

    /**
     * Administrator display last name ({@code SEC-USR-LNAME}); not a secret. Default {@code User}.
     */
    private String adminLname = "User";

    /** Standard user id ({@code SEC-USR-ID}). Default {@code USER0001}. */
    private String userId = "USER0001";

    /**
     * Standard-user role flag ({@code SEC-USR-TYPE}). Default {@code U} ({@code
     * CDEMO-USRTYP-USER}).
     */
    private String userType = "U";

    /**
     * Standard-user raw password. No in-code default: supplied via {@code
     * carddemo.security.seed.user-password} (env {@code CARDDEMO_USER_PASSWORD}). Blank/absent
     * causes the standard user to be skipped. Never logged; BCrypt-hashed before persistence.
     */
    private String userPassword;

    /**
     * Standard-user display first name ({@code SEC-USR-FNAME}); not a secret. Default {@code
     * Regular}.
     */
    private String userFname = "Regular";

    /**
     * Standard-user display last name ({@code SEC-USR-LNAME}); not a secret. Default {@code User}.
     */
    private String userLname = "User";

    public String getAdminId() {
      return adminId;
    }

    public void setAdminId(String adminId) {
      this.adminId = adminId;
    }

    public String getAdminType() {
      return adminType;
    }

    public void setAdminType(String adminType) {
      this.adminType = adminType;
    }

    public String getAdminPassword() {
      return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
      this.adminPassword = adminPassword;
    }

    public String getAdminFname() {
      return adminFname;
    }

    public void setAdminFname(String adminFname) {
      this.adminFname = adminFname;
    }

    public String getAdminLname() {
      return adminLname;
    }

    public void setAdminLname(String adminLname) {
      this.adminLname = adminLname;
    }

    public String getUserId() {
      return userId;
    }

    public void setUserId(String userId) {
      this.userId = userId;
    }

    public String getUserType() {
      return userType;
    }

    public void setUserType(String userType) {
      this.userType = userType;
    }

    public String getUserPassword() {
      return userPassword;
    }

    public void setUserPassword(String userPassword) {
      this.userPassword = userPassword;
    }

    public String getUserFname() {
      return userFname;
    }

    public void setUserFname(String userFname) {
      this.userFname = userFname;
    }

    public String getUserLname() {
      return userLname;
    }

    public void setUserLname(String userLname) {
      this.userLname = userLname;
    }
  }
}
