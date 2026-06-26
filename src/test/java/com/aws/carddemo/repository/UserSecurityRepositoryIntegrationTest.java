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
package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.UserSecurity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * {@code @DataJpaTest} slice integration test for {@link UserSecurityRepository}, the Spring Data
 * JPA replacement for the legacy VSAM {@code USRSEC} KSDS. The store is described by copybook
 * {@code legacy/app/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}, RECLN 80) and is the credential /
 * role record consulted by the sign-on transaction {@code COSGN00C} ({@code CC00}). This test
 * proves the COBOL-to-JPA parity guarantees of the single-key lookup locally, against a real
 * database, with no running mainframe (Agent Action Plan &sect;0.6.7).
 *
 * <p>The class extends {@link AbstractRepositoryIntegrationTest}, inheriting the entire slice
 * configuration — {@code @DataJpaTest} (transactional, rolled back per method; Flyway applies
 * {@code V1__schema.sql} and {@code V2__seed_reference_data.sql} automatically),
 * {@code @AutoConfigureTestDatabase(replace = NONE)}, {@code @ActiveProfiles("test")}, the
 * JVM-singleton Testcontainers PostgreSQL 16 instance wired through {@code @ServiceConnection}, the
 * {@code TestEntityManager}, and the {@link AbstractRepositoryIntegrationTest#flushAndClear()}
 * helper. Those members are intentionally <em>not</em> re-declared here.
 *
 * <p><strong>The {@code user_security} table is EMPTY in the slice.</strong> Unlike the four
 * reference tables, it is <em>not</em> seeded by the {@code V2} migration: per the
 * security-hardening rule (AAP &sect;0.6.6 / &sect;0.7.2) the two real seed users are inserted at
 * runtime by a {@code CommandLineRunner} bootstrap seeder that reads externalized, BCrypt-encoded
 * credentials and is <em>not</em> part of the {@code @DataJpaTest} slice. Each test therefore
 * builds and persists its own <strong>synthetic</strong> user via {@link #buildUser(String,
 * String)} when a present row is required. No real or default sign-on identity and no legacy
 * default password ever appears here; a clearly non-secret placeholder is used for the BCrypt-hash
 * column.
 *
 * <p>The parity invariants exercised here are:
 *
 * <ul>
 *   <li><b>Single-PK lookup parity</b> — a persisted user is retrievable by its {@code sec_usr_id}
 *       primary key ({@code SEC-USR-ID PIC X(08)} &rarr; {@code char(8)}, KEYLEN 8; AAP
 *       &sect;0.6.2), mirroring the keyed VSAM read that backs sign-on.
 *   <li><b>FILE STATUS {@code '23'} parity</b> — a lookup for an id that was never written returns
 *       {@link Optional#empty()} rather than throwing, matching the COBOL record-not-found contract
 *       (AAP &sect;0.6.4).
 *   <li><b>Fixed-width {@code char(n)} parity</b> — text shorter than its column width is read back
 *       blank-padded to the full width and a short {@code char(8)} key is matched via
 *       trailing-space-insensitive {@code bpchar} equality (AAP &sect;0.6.2); a database round-trip
 *       (forced by {@code flushAndClear()}) is required to observe the padding because the
 *       persistence-context first-level cache would otherwise return the un-padded value as set.
 *   <li><b>Security-hardening parity</b> — the legacy clear-text {@code SEC-USR-PWD PIC X(08)} is
 *       replaced by a {@code varchar(60)} hash column that round-trips verbatim with no
 *       blank-padding, distinct from the fixed-width {@code char} text columns (AAP &sect;0.6.6).
 * </ul>
 */
class UserSecurityRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /**
   * Synthetic primary key for the persisted user; exactly eight characters, filling the {@code
   * char(8)} {@code sec_usr_id} column without padding. Deliberately <em>not</em> one of the real
   * default sign-on identities.
   */
  private static final String PRESENT_USR_ID = "TESTUSR1";

  /**
   * Synthetic key that is never persisted; used to assert record-not-found ({@code '23'}) parity.
   */
  private static final String ABSENT_USR_ID = "NOSUCH99";

  /**
   * A seven-character synthetic key (shorter than the {@code char(8)} column) used to demonstrate
   * fixed-width blank-padding on read-back and trailing-space-insensitive {@code bpchar} key
   * equality (AAP &sect;0.6.2).
   */
  private static final String SHORT_USR_ID = "TESTUSR";

  /**
   * Logical first-name value. Only six characters so that, when stored in the {@code char(20)}
   * {@code sec_usr_fname} column and read back, the blank-padding to the full width is observable
   * (AAP &sect;0.6.2).
   */
  private static final String FIRST_NAME = "TESTFN";

  /** Logical last-name value, asserted trimmed to demonstrate {@code char(20)} text round-trip. */
  private static final String LAST_NAME = "TESTLN";

  /**
   * Clearly non-secret placeholder for the {@code varchar(60)} BCrypt-hash column. This is
   * <strong>not</strong> a real or default credential (AAP &sect;0.6.6 / &sect;0.7.2); the test
   * only verifies that the column round-trips a {@code varchar} value verbatim, with no {@code
   * char(n)} blank-padding.
   */
  private static final String SYNTHETIC_PWD = "test-only-not-a-secret";

  /** Administrator role flag — legacy {@code CDEMO-USRTYP-ADMIN} ({@code SEC-USR-TYPE = 'A'}). */
  private static final String ADMIN_TYPE = "A";

  /** Standard-user role flag — legacy {@code CDEMO-USRTYP-USER} ({@code SEC-USR-TYPE = 'U'}). */
  private static final String USER_TYPE = "U";

  /** Repository under test; injected by the {@code @DataJpaTest} slice context. */
  @Autowired private UserSecurityRepository repository;

  /**
   * Persists a fully populated, synthetic administrator user and confirms it is retrievable by its
   * {@code sec_usr_id} primary key after a real database round-trip. This is the Java equivalent of
   * the keyed VSAM {@code READ} on {@code USRSEC} that sign-on performs (FILE STATUS {@code '00'}).
   * The fixed-width {@code char(n)} text columns ({@code type}, {@code fname}, {@code lname}) come
   * back blank-padded and are compared after {@link String#trim()}; the {@code varchar(60)}
   * password-hash column is compared verbatim (no trim) because it carries no fixed-width
   * semantics.
   */
  @Test
  @DisplayName("save then findById returns the persisted user (single-PK lookup parity)")
  void saveThenFindByIdReturnsUser() {
    repository.save(buildUser(PRESENT_USR_ID, ADMIN_TYPE));
    flushAndClear();

    Optional<UserSecurity> found = repository.findById(PRESENT_USR_ID);

    assertThat(found).isPresent();
    UserSecurity reread = found.orElseThrow();
    assertThat(reread.getSecUsrType().trim()).isEqualTo(ADMIN_TYPE);
    assertThat(reread.getSecUsrFname().trim()).isEqualTo(FIRST_NAME);
    assertThat(reread.getSecUsrLname().trim()).isEqualTo(LAST_NAME);
    assertThat(reread.getSecUsrPwd()).isEqualTo(SYNTHETIC_PWD);
  }

  /**
   * Confirms that a lookup for an id that was never written returns {@link Optional#empty()} (the
   * {@code user_security} table is empty in the slice). This pins the COBOL FILE STATUS {@code
   * '23'} (record-not-found) contract to {@code Optional.empty()} rather than an exception, so the
   * sign-on / user-management service layer can branch on a missing user exactly as the COBOL
   * programs branch on the {@code '23'} status (AAP &sect;0.6.4).
   */
  @Test
  @DisplayName("findById on an absent id returns Optional.empty (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(ABSENT_USR_ID)).isEmpty();
  }

  /**
   * Demonstrates fixed-width {@code char(8)} key parity (AAP &sect;0.6.2). A seven-character id is
   * stored in the {@code char(8)} {@code sec_usr_id} column, where PostgreSQL blank-pads it to the
   * full width. Because {@code bpchar} equality ignores trailing blanks (the reason the entity pins
   * the column to JDBC {@code CHAR}), the row is resolvable both by the bare seven-character probe
   * and by an explicitly space-padded eight-character probe — reproducing the VSAM key-equality
   * semantics of the original {@code USRSEC} KSDS.
   *
   * <p>Fixed-width blank-padding on read-back is asserted on a <em>non-key</em> text column ({@code
   * sec_usr_fname}, {@code char(20)}): the six-character first name comes back padded to the full
   * column width and is recovered with {@link String#trim()}. The {@code char(8)} identifier itself
   * is <em>not</em> asserted to be padded — when an entity is loaded by id, Hibernate populates its
   * identifier property from the lookup key rather than from the re-read primary-key column, so
   * {@code getSecUsrId()} reflects the (un-padded) probe value; only its trimmed logical value is
   * asserted. The {@code flushAndClear()} round-trip is essential: without it {@code findById}
   * would return the cached instance from the persistence context and the column padding would not
   * be visible.
   */
  @Test
  @DisplayName("char(8) key: a short id matches via trailing-space-insensitive bpchar equality")
  void charIdKeyTrailingSpaceInsensitive() {
    repository.save(buildUser(SHORT_USR_ID, USER_TYPE));
    flushAndClear();

    // VSAM-like fixed-width key equality: trailing blanks are not significant, so both the bare
    // seven-character probe and the explicitly space-padded eight-character probe resolve the row.
    assertThat(repository.findById(SHORT_USR_ID)).isPresent();
    assertThat(repository.findById(SHORT_USR_ID + " ")).isPresent();

    UserSecurity reread = repository.findById(SHORT_USR_ID).orElseThrow();
    // Fixed-width char(n) blank-padding observed on a non-key column (char(20) first name).
    assertThat(reread.getSecUsrFname()).hasSize(20);
    assertThat(reread.getSecUsrFname().trim()).isEqualTo(FIRST_NAME);
    // The identifier reflects the lookup key (Hibernate id hydration); its logical value matches.
    assertThat(reread.getSecUsrId().trim()).isEqualTo(SHORT_USR_ID);
    assertThat(reread.getSecUsrType().trim()).isEqualTo(USER_TYPE);
  }

  // ===============================================================================================
  // Keyset browse parity (F-1 fix) + projection over-fetch (F-4 fix). The user list (COUSR00C /
  // CU00) previously read the entire user_security table (findAll(Sort)) and sliced in memory, and
  // it selected the whole entity including sec_usr_pwd (the BCrypt hash) for a screen that renders
  // only id/first/last/type. The browse now uses bounded keyset pages that return the closed
  // UserListProjection. These tests prove against a real PostgreSQL char(8) sec_usr_id that:
  //   (1) the Pageable limit bounds the read to one page (F-1 OOM remedy);
  //   (2) bpchar SEC-USR-ID ordering matches the ascending VSAM-key browse and Java compareTo;
  //   (3) the empty start key positions at LOW-VALUES (STARTBR from the top);
  //   (4) GREATER-THAN-EQUAL is inclusive (ENTER landing) and GREATER-THAN is exclusive (PF8 after
  //       the boundary record), and LESS-THAN descending reproduces PF7 READPREV;
  //   (5) existsBySecUsrIdGreaterThan reproduces the PF8 "is there a next page" guard probe; and
  //   (6) the projection exposes ONLY the four displayed columns (F-4) -- the type has no
  //       getSecUsrPwd accessor, so the credential hash is structurally unreachable for a list
  //       view (the projected select-list omitting sec_usr_pwd is additionally confirmed at runtime
  //       via Hibernate SQL).
  // ===============================================================================================

  @Test
  @DisplayName("keyset forward from the empty start key browses from the top, bounded by the limit")
  void keysetForwardFromTop_boundedByLimit() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildUser(String.format("USER%04d", i), USER_TYPE));
    }
    flushAndClear();

    // STARTBR from LOW-VALUES (empty key) with a two-row page: exactly the first two users
    // ascending are returned, NOT the whole table -- the bounded read that remedies the F-1 OOM.
    List<UserListProjection> page =
        repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc("", PageRequest.of(0, 2));

    assertThat(page).hasSize(2);
    assertThat(page)
        .extracting(p -> p.getSecUsrId().trim())
        .containsExactly("USER0001", "USER0002");
  }

  @Test
  @DisplayName("keyset forward GREATER-THAN-EQUAL includes the start key; GREATER-THAN excludes it")
  void keysetForwardInclusiveVsExclusive() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildUser(String.format("USER%04d", i), USER_TYPE));
    }
    flushAndClear();
    String key = "USER0003";

    List<UserListProjection> gteq =
        repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(key, PageRequest.of(0, 10));
    List<UserListProjection> gt =
        repository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(key, PageRequest.of(0, 10));

    assertThat(gteq)
        .extracting(p -> p.getSecUsrId().trim())
        .containsExactly("USER0003", "USER0004", "USER0005");
    assertThat(gt).extracting(p -> p.getSecUsrId().trim()).containsExactly("USER0004", "USER0005");
  }

  @Test
  @DisplayName("keyset backward (LESS-THAN) returns descending rows below the cursor, bounded")
  void keysetBackwardDescending_boundedByLimit() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildUser(String.format("USER%04d", i), USER_TYPE));
    }
    flushAndClear();

    // PF7 READPREV below USER0005 with a two-row look-back: the two closest-below rows, descending.
    List<UserListProjection> back =
        repository.findBySecUsrIdLessThanOrderBySecUsrIdDesc("USER0005", PageRequest.of(0, 2));

    assertThat(back)
        .extracting(p -> p.getSecUsrId().trim())
        .containsExactly("USER0004", "USER0003");
  }

  @Test
  @DisplayName("existsBySecUsrIdGreaterThan reproduces the PF8 next-page guard probe")
  void existsBySecUsrIdGreaterThanPeek() {
    repository.save(buildUser("USER0001", USER_TYPE));
    repository.save(buildUser("USER0002", USER_TYPE));
    flushAndClear();

    assertThat(repository.existsBySecUsrIdGreaterThan("USER0001")).isTrue();
    assertThat(repository.existsBySecUsrIdGreaterThan("USER0002")).isFalse();
  }

  @Test
  @DisplayName("keyset projection returns only the four displayed columns (F-4 over-fetch fix)")
  void keysetProjectionExposesOnlyDisplayedColumns() {
    // Persist a distinct admin user so the projected first/last/type values are unambiguous.
    repository.save(buildUser(PRESENT_USR_ID, ADMIN_TYPE));
    flushAndClear();

    List<UserListProjection> page =
        repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc("", PageRequest.of(0, 10));

    assertThat(page).hasSize(1);
    UserListProjection row = page.get(0);
    assertThat(row.getSecUsrId().trim()).isEqualTo(PRESENT_USR_ID);
    assertThat(row.getSecUsrFname().trim()).isEqualTo(FIRST_NAME);
    assertThat(row.getSecUsrLname().trim()).isEqualTo(LAST_NAME);
    assertThat(row.getSecUsrType().trim()).isEqualTo(ADMIN_TYPE);
    // The closed projection has no getSecUsrPwd accessor; the BCrypt hash is structurally
    // unreachable for the list view (compile-time guarantee reinforcing the F-4 select-list fix).
  }

  /**
   * Builds a fully populated, synthetic {@link UserSecurity}. Every column of the {@code
   * user_security} table is {@code NOT NULL} in {@code V1__schema.sql}, so every modeled field is
   * set; because Hibernate runs with {@code ddl-auto=validate} and PostgreSQL enforces the
   * constraints, a partially populated entity would fail to insert. All text values stay within
   * their {@code char(n)} widths and the password placeholder stays within {@code varchar(60)}. No
   * real or default credential is used (AAP &sect;0.6.6 / &sect;0.7.2).
   *
   * @param id the primary key ({@code sec_usr_id}) to assign; must fit the {@code char(8)} column
   * @param type the role flag ({@code sec_usr_type}); {@code "A"} for admin or {@code "U"} for user
   * @return a valid, ready-to-persist {@link UserSecurity}
   */
  private static UserSecurity buildUser(String id, String type) {
    UserSecurity user = new UserSecurity();
    user.setSecUsrId(id);
    user.setSecUsrFname(FIRST_NAME);
    user.setSecUsrLname(LAST_NAME);
    user.setSecUsrPwd(SYNTHETIC_PWD);
    user.setSecUsrType(type);
    return user;
  }
}
