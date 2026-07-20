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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionCategory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed Spring Data JPA integration test for
 * {@link TransactionCategoryRepository}.
 *
 * <p>Exercises the compound-key reference access that the COBOL transaction-detail
 * report performs against the {@code TRANCATG} VSAM KSDS. In the legacy system
 * {@code legacy/cbl/CBTRN03C.cbl} declares {@code TRANCATG-FILE} with
 * {@code ACCESS MODE IS RANDOM} and {@code RECORD KEY IS FD-TRAN-CAT-KEY}, then
 * issues a keyed {@code READ} on the composite key to resolve a category
 * description ({@code TRAN-CAT-TYPE-DESC}) while printing the report. The
 * record layout comes from copybook {@code legacy/cpy/CVTRA04Y.cpy}
 * ({@code TRAN-CAT-RECORD}, RECLN 60), whose group {@code TRAN-CAT-KEY} is
 * {@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)} — modeled in
 * the Java migration as the composite primary key {@code (type_cd, cat_cd)} via
 * the nested {@code @Embeddable}
 * {@link TransactionCategory.TransactionCategoryId}.</p>
 *
 * <p>This test proves the set-based PostgreSQL 16 replacement preserves that
 * compound-key access: it persists synthetic rows and re-reads them by full
 * composite key through the inherited
 * {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object)},
 * confirms the two key components discriminate independently, confirms an absent
 * composite key yields an empty result, verifies the Flyway {@code V2} reference
 * seed is present, and guards that {@code TRAN-CAT-CD PIC 9(04)} maps to a real
 * {@code INTEGER} rather than a zero-padded string (AAP 0.4.3, 0.9.2).</p>
 *
 * <h2>Harness</h2>
 * <p>The test runs the {@code @DataJpaTest} slice with
 * {@link AutoConfigureTestDatabase.Replace#NONE} against a real PostgreSQL 16
 * container. Flyway applies the production migrations
 * ({@code V1__schema.sql} then {@code V2__reference_data.sql}) and Hibernate is
 * configured with {@code ddl-auto: validate} (see
 * {@code src/test/resources/application-test.yml}), so the run also validates
 * the {@link TransactionCategory} {@code @EmbeddedId} mapping against the
 * {@code transaction_category} table. The container's JDBC coordinates are wired
 * dynamically via {@link DynamicPropertySource} so no database URL, username, or
 * password is ever hard-coded (AAP 0.8.1 / 0.9.3).</p>
 *
 * <p>Each test method runs inside the slice's transaction and is rolled back on
 * completion, so inserts made here never leak into other tests; the Flyway
 * {@code V2} seed rows are committed before the tests run and therefore remain
 * visible.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TransactionCategoryRepositoryTest {

    /**
     * A real PostgreSQL 16 instance for the integration test. Declared
     * {@code static} so the {@code @Testcontainers} extension starts it once for
     * the whole class (before the Spring context is created) and stops it after
     * all tests complete.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Wires the JDBC datasource from the running container. The values are
     * supplied lazily (method references), so they are read after the container
     * has started and never baked into configuration — satisfying the
     * no-hard-coded-credentials constraint.
     *
     * @param registry the registry Spring uses to resolve dynamic properties
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Transaction type code used for every persisted fixture. This must be a code
     * that already exists in {@code transaction_type} because
     * {@code transaction_category.type_cd} carries an enforced foreign key
     * ({@code fk_transaction_category_type}) to it; the Flyway {@code V2} seed
     * loads {@code '01'} (among {@code '01'}..{@code '07'}), so it is a safe,
     * committed parent. The fixtures pair it with high {@code cat_cd} values that
     * do not collide with the seeded categories for {@code '01'} ({@code 1}..{@code 5}).
     */
    private static final String SEEDED_TYPE_CD = "01";

    private final TransactionCategoryRepository repository;
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the repository under test and the test entity
     * manager. {@link TestConstructor @TestConstructor} with
     * {@link TestConstructor.AutowireMode#ALL} lets the Spring
     * {@code SpringExtension} autowire these constructor parameters from the test
     * application context (constructor injection, never field injection).
     *
     * @param repository    the repository under test
     * @param entityManager the JPA test entity manager, used to detach persisted
     *                      instances so reads round-trip through the database
     */
    TransactionCategoryRepositoryTest(TransactionCategoryRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * Persisting a category and re-reading it by its full composite key returns
     * the same row — the fundamental compound-key parity with the COBOL keyed
     * {@code READ} on {@code FD-TRAN-CAT-KEY}.
     */
    @Test
    void saveAndFindByCompositeId_roundTrips() {
        TransactionCategory.TransactionCategoryId id =
                new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, 9999);
        repository.saveAndFlush(new TransactionCategory(id, "TEST CATEGORY"));
        // Detach so the subsequent findById is served from the database, not the
        // persistence-context first-level cache.
        entityManager.clear();

        var found = repository.findById(new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, 9999));

        assertThat(found).isPresent();
        TransactionCategory category = found.orElseThrow();
        assertThat(category.getCatTypeDesc()).isEqualTo("TEST CATEGORY");
        assertThat(category.getId().getTypeCd()).isEqualTo(SEEDED_TYPE_CD);
        assertThat(category.getId().getCatCd()).isEqualTo(9999);
    }

    /**
     * Two rows that share {@code type_cd} but differ only in {@code cat_cd} are
     * distinct entities, each independently addressable by its composite key.
     * This confirms {@code cat_cd} genuinely participates in the primary key.
     */
    @Test
    void findById_withDifferentCatCdSameTypeCd_areDistinct() {
        repository.saveAndFlush(new TransactionCategory(SEEDED_TYPE_CD, 9001, "SYNTHETIC CATEGORY ALPHA"));
        repository.saveAndFlush(new TransactionCategory(SEEDED_TYPE_CD, 9002, "SYNTHETIC CATEGORY BETA"));
        entityManager.clear();

        var alpha = repository.findById(new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, 9001));
        var beta = repository.findById(new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, 9002));

        assertThat(alpha).isPresent();
        assertThat(beta).isPresent();
        assertThat(alpha.orElseThrow().getCatTypeDesc()).isEqualTo("SYNTHETIC CATEGORY ALPHA");
        assertThat(beta.orElseThrow().getCatTypeDesc()).isEqualTo("SYNTHETIC CATEGORY BETA");
        assertThat(alpha.orElseThrow().getCatTypeDesc())
                .isNotEqualTo(beta.orElseThrow().getCatTypeDesc());
    }

    /**
     * Looking up a composite key that was never persisted yields an empty
     * {@link java.util.Optional} — the caller-visible "record not found" outcome
     * of the legacy keyed {@code READ}.
     */
    @Test
    void findById_absentCompositeKey_returnsEmpty() {
        assertThat(repository.findById(new TransactionCategory.TransactionCategoryId("Q9", 4242))).isEmpty();
    }

    /**
     * The Flyway {@code V2__reference_data.sql} migration seeds the
     * {@code transaction_category} reference table, so the repository reports a
     * non-empty table before this test inserts anything. Asserted tolerantly
     * (greater-than-zero rather than an exact count) so it does not couple to the
     * exact number of seeded rows.
     */
    @Test
    void referenceDataSeededByFlywayV2IsPresent() {
        assertThat(repository.count()).isGreaterThan(0);
    }

    /**
     * A multi-digit {@code cat_cd} round-trips as the same {@link Integer} value,
     * guarding the {@code TRAN-CAT-CD PIC 9(04) -> INTEGER} mapping. If the column
     * were a zero-padded string the persisted value would not compare equal to the
     * plain integer {@code 1234}.
     */
    @Test
    void catCdPersistsAsInteger() {
        repository.saveAndFlush(new TransactionCategory(SEEDED_TYPE_CD, 1234, "MULTI DIGIT CATEGORY"));
        entityManager.clear();

        var found = repository.findById(new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, 1234));

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId().getCatCd()).isEqualTo(1234);
    }
}
